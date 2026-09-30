package com.example.drools.controller;

import com.example.drools.domain.DocFact;
import com.example.drools.entity.RuleDefinition;
import com.example.drools.service.DrlValidator;
import com.example.drools.service.RuleDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.kie.api.definition.type.FactField;
import org.kie.api.definition.type.FactType;
import org.kie.api.runtime.rule.QueryResults;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 运营规则管理接口：配置 -> 生成 DRL -> 入库 -> 编译生效。 */
@RestController
@RequestMapping("/rule")
public class RuleController {

  private final RuleDefinitionService service;
  private final DrlValidator drlValidator;
  private final com.example.drools.release.RuleRuntimeRegistry registry;
  private final com.example.drools.runtime.FrozenDocumentRuntime documents =
      new com.example.drools.runtime.FrozenDocumentRuntime();
  private final ObjectMapper mapper = new ObjectMapper();

  public RuleController(
      RuleDefinitionService service,
      DrlValidator drlValidator,
      com.example.drools.release.RuleRuntimeRegistry registry) {
    this.service = service;
    this.drlValidator = drlValidator;
    this.registry = registry;
  }

  /**
   * 通用单据评估入口：任何注册进中台的单据都能这样跑规则，不用为它写 Java 类。 POST /rule/evaluate?ruleType=TYPE_CODE body =
   * 单据注册里登记的那些字段（可嵌套） 返回：decision（冻结返回结构）+ data（输入）+ ext（规则结果）+ messages（执行消息）
   */
  @PostMapping("/evaluate")
  public Map<String, Object> evaluate(
      @RequestParam String ruleType, @RequestBody(required = false) Map<String, Object> body) {
    return evaluateInternal(ruleType, body, null);
  }

  /**
   * Native DRL HTTP bridge. Envelope: {data, facts:[{package,type,values,entryPoint}], globals,
   * agendaGroups:[...], queries:[{name,args:[]}]}.
   */
  @PostMapping("/native/evaluate")
  @SuppressWarnings("unchecked")
  public Map<String, Object> evaluateNative(
      @RequestParam String ruleType, @RequestBody Map<String, Object> envelope) {
    Object rawData = envelope.get("data");
    if (rawData != null && !(rawData instanceof Map))
      throw new IllegalArgumentException("data must be an object");
    return evaluateInternal(ruleType, (Map<String, Object>) rawData, envelope);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> evaluateInternal(
      String ruleType, Map<String, Object> body, Map<String, Object> options) {
    try (com.example.drools.release.RuleRuntimeRegistry.Lease lease = registry.acquire(ruleType)) {
      org.kie.api.runtime.KieSessionConfiguration config =
          org.kie.api.KieServices.Factory.get().newKieSessionConfiguration();
      if (options != null && options.containsKey("clockType"))
        config.setOption(
            org.kie.api.runtime.conf.ClockTypeOption.get(String.valueOf(options.get("clockType"))));
      org.kie.api.runtime.KieSession session = lease.runtime().base().newKieSession(config, null);
      try {
        DocFact fact =
            options != null && (body == null || lease.bundle().path("docCode").asText().isEmpty())
                ? null
                : documents.insert(lease.bundle(), body, session);
        if (options != null) {
          Object globals = options.get("globals");
          if (globals instanceof Map)
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) globals).entrySet())
              session.setGlobal(String.valueOf(entry.getKey()), entry.getValue());
          insertDeclaredFacts(session, options.get("facts"));
          if (options.get("advanceTimeMs") != null) {
            if (!(session.getSessionClock() instanceof org.kie.api.time.SessionPseudoClock))
              throw new IllegalArgumentException("advanceTimeMs requires pseudo clock");
            long amount = Long.parseLong(String.valueOf(options.get("advanceTimeMs")));
            if (amount < 0) throw new IllegalArgumentException("Time cannot move backwards");
            ((org.kie.api.time.SessionPseudoClock) session.getSessionClock())
                .advanceTime(amount, java.util.concurrent.TimeUnit.MILLISECONDS);
          }
          Object groups = options.get("agendaGroups");
          if (groups instanceof List) {
            List<?> names = (List<?>) groups;
            for (int i = names.size() - 1; i >= 0; i--)
              session.getAgenda().getAgendaGroup(String.valueOf(names.get(i))).setFocus();
          }
        }
        int fired =
            fireNativeRules(session, options == null ? java.util.Collections.emptyMap() : options);
        Map<String, Object> result =
            fact == null
                ? new java.util.LinkedHashMap<String, Object>()
                : documents.result(lease.bundle(), fact, fired);
        result.put("ruleType", ruleType);
        result.put("releaseId", lease.bundle().path("releaseId").asLong());
        result.put("revision", lease.bundle().path("revision").asLong());
        result.put("fired", fired);
        result.put("evalId", java.util.UUID.randomUUID().toString());
        if (options != null && options.get("queries") instanceof List)
          result.put("queries", executeQueries(session, (List<?>) options.get("queries")));
        return result;
      } finally {
        session.dispose();
      }
    }
  }

  private int fireNativeRules(org.kie.api.runtime.KieSession session, Map<String, Object> options) {
    Object rawNames = options.get("ruleNames");
    org.kie.api.runtime.rule.AgendaFilter filter = null;
    if (rawNames != null) {
      if (!(rawNames instanceof List)) throw new IllegalArgumentException("ruleNames 必须是规则名数组");
      final java.util.Set<String> names = new java.util.HashSet<String>();
      for (Object name : (List<?>) rawNames) names.add(String.valueOf(name));
      filter = match -> names.contains(match.getRule().getName());
    }
    Object rawLimit = options.get("maxFirings");
    if (rawLimit == null) {
      // Native mode follows KIE's unbounded fireAllRules semantics unless caller opts into a limit.
      return filter == null ? session.fireAllRules() : session.fireAllRules(filter);
    }
    int limit = Integer.parseInt(String.valueOf(rawLimit));
    if (limit < 1) throw new IllegalArgumentException("maxFirings 必须大于 0；不传表示使用 Drools 原生无限点火");
    return filter == null ? session.fireAllRules(limit) : session.fireAllRules(filter, limit);
  }

  @SuppressWarnings("unchecked")
  private void insertDeclaredFacts(org.kie.api.runtime.KieSession session, Object rawFacts) {
    if (rawFacts == null) return;
    if (!(rawFacts instanceof List)) throw new IllegalArgumentException("facts 必须是数组");
    for (Object raw : (List<?>) rawFacts) {
      if (!(raw instanceof Map)) throw new IllegalArgumentException("facts 中每一项必须是对象");
      Map<String, Object> input = (Map<String, Object>) raw;
      String packageName = String.valueOf(input.get("package"));
      String typeName = String.valueOf(input.get("type"));
      Object values = input.get("values");
      if (!(values instanceof Map)) throw new IllegalArgumentException("native fact.values 必须是对象");
      FactType type = session.getKieBase().getFactType(packageName, typeName);
      if (type == null)
        throw new IllegalArgumentException("DRL 未声明事实类型: " + packageName + "." + typeName);
      try {
        Object instance = type.newInstance();
        Map<String, Object> valueMap = (Map<String, Object>) values;
        for (Map.Entry<String, Object> value : valueMap.entrySet()) {
          FactField field = type.getField(value.getKey());
          if (field == null)
            throw new IllegalArgumentException("事实类型 " + typeName + " 不存在字段: " + value.getKey());
          Object converted = mapper.convertValue(value.getValue(), field.getType());
          type.set(instance, value.getKey(), converted);
        }
        Object entryPoint = input.get("entryPoint");
        if (entryPoint == null || String.valueOf(entryPoint).trim().isEmpty()) {
          session.insert(instance);
        } else {
          org.kie.api.runtime.rule.EntryPoint point =
              session.getEntryPoint(String.valueOf(entryPoint));
          if (point == null)
            throw new IllegalArgumentException("DRL/会话不存在 entry point: " + entryPoint);
          point.insert(instance);
        }
      } catch (IllegalArgumentException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalArgumentException("无法创建 DRL 声明事实 " + packageName + "." + typeName, e);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> executeQueries(
      org.kie.api.runtime.KieSession session, List<?> queries) {
    Map<String, Object> output = new java.util.LinkedHashMap<String, Object>();
    for (Object raw : queries) {
      if (!(raw instanceof Map)) throw new IllegalArgumentException("queries 中每一项必须是对象");
      Map<String, Object> query = (Map<String, Object>) raw;
      String name = String.valueOf(query.get("name"));
      Object rawArgs = query.get("args");
      Object[] args = rawArgs instanceof List ? ((List<?>) rawArgs).toArray() : new Object[0];
      QueryResults rows = session.getQueryResults(name, args);
      output.put(name, rows.toList());
    }
    return output;
  }

  /** 全部规则 */
  @GetMapping("/list")
  public List<RuleDefinition> list() {
    return service.list();
  }

  /**
   * 新增规则（草稿态，元数据校验 + 试编译通过才入库，不立即生效）。 body
   * 示例：{"ruleName":"GROSS_MARGIN_30","ruleType":"GROSS_MARGIN_CHECK","ruleParams":"{\"step1Value\":0.30}"}
   */
  @PostMapping("/create")
  public RuleDefinition create(@RequestBody RuleDefinition req) {
    return service.create(req);
  }

  /** Validate and save original DRL without applying the configuration DSL transformations. */
  @PostMapping("/native/validate")
  public Map<String, Object> validateNative(@RequestBody Map<String, Object> body) {
    Object source = body.get("drlContent");
    if (source == null || String.valueOf(source).trim().isEmpty())
      throw new IllegalArgumentException("原生 DRL 内容不能为空");
    drlValidator.validate(String.valueOf(source));
    Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
    result.put("valid", true);
    result.put("message", "DRL 原文编译通过；发布时不会经过模板或字段语法改写");
    return result;
  }

  @PostMapping("/native/create")
  public RuleDefinition createNative(@RequestBody RuleDefinition req) {
    return service.createNative(req);
  }

  @PostMapping("/native/publish/{id}")
  public RuleDefinition publishNative(
      @PathVariable Long id,
      @RequestHeader("If-Match") long expectedVersion,
      @RequestBody(required = false) Map<String, Object> body) {
    Object source = body == null ? null : body.get("drlContent");
    return service.publishNative(
        id, source == null ? null : String.valueOf(source), expectedVersion);
  }

  /**
   * 更新参数并发布（元数据校验 + 重新生成 DRL + 试编译 + 刷新缓存，立即生效）。 body 为参数 JSON 对象，如
   * {"threshold":2000,"reduction":150}
   */
  @PostMapping("/publish/{id}")
  public RuleDefinition publish(
      @PathVariable Long id,
      @RequestHeader("If-Match") long expectedVersion,
      @RequestBody Map<String, Object> params)
      throws Exception {
    return service.publish(id, mapper.writeValueAsString(params), expectedVersion);
  }

  /** 启用/禁用，body: {"status": 1} */
  @PostMapping("/status/{id}")
  public String changeStatus(
      @PathVariable Long id,
      @RequestHeader("If-Match") long expectedVersion,
      @RequestBody Map<String, Integer> body) {
    service.changeStatus(id, body.get("status"), expectedVersion);
    return "ok";
  }

  @DeleteMapping("/{id}")
  public String delete(@PathVariable Long id, @RequestHeader("If-Match") long expectedVersion) {
    service.delete(id, expectedVersion);
    return "ok";
  }

}
