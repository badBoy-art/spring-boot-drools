package com.example.drools.service;

import com.example.drools.dao.RuleDefinitionDao;
import com.example.drools.dao.RuleTypeMetaDao;
import com.example.drools.entity.RuleDefinition;
import com.example.drools.entity.RuleTypeMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规则定义业务层：运营配置 -> 类型校验(元数据) -> 参数校验 -> 生成 DRL -> 试编译 -> 入库 -> 刷新缓存。 规则类型、参数字段、DRL
 * 模板全部数据驱动（rule_type_meta / rule_type_field / rule_template）， 新增规则类型无需改 Java 代码。
 */
@Service
public class RuleDefinitionService {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A legal DRL comment used to preserve the authoring mode without adding a schema column. */
  public static final String NATIVE_DRL_MARKER = "// @rule-source-mode: NATIVE_DRL\n";

  private final RuleDefinitionDao dao;
  private final RuleTypeMetaDao metaDao;
  private final DrlGenerator generator;
  private final DrlValidator drlValidator;
  private final RuleParamValidator validator;
  private final com.example.drools.release.RuleReleaseService releases;

  public RuleDefinitionService(
      RuleDefinitionDao dao,
      RuleTypeMetaDao metaDao,
      DrlGenerator generator,
      DrlValidator drlValidator,
      RuleParamValidator validator,
      com.example.drools.release.RuleReleaseService releases) {
    this.dao = dao;
    this.metaDao = metaDao;
    this.generator = generator;
    this.drlValidator = drlValidator;
    this.validator = validator;
    this.releases = releases;
  }

  public List<RuleDefinition> list() {
    return dao.findAll();
  }

  /** 新增规则（草稿态）：类型校验 -> 参数校验 -> 生成 DRL -> 试编译，全部通过才入库。 */
  public RuleDefinition create(RuleDefinition req) {
    RuleTypeMeta meta = metaDao.findByType(req.getRuleType());
    if (meta == null) {
      throw new IllegalArgumentException("未知规则类型: " + req.getRuleType());
    }
    validator.validate(req.getRuleType(), req.getRuleParams()); // 参数校验
    // 建草稿时就先拦"条件重复"：否则草稿建出来了、发布时才被拒，库里会留一堆永远发不出去的废草稿
    checkDuplicateCondition(req, req.getRuleParams());
    String drl = generator.generate(req.getRuleType(), req.getRuleName(), req.getRuleParams());
    drlValidator.validate(drl); // 试编译，语法错误直接抛异常
    req.setRuleGroup(meta.getRuleGroup());
    req.setDrlContent(drl);
    req.setStatus(0);
    // 草稿版本号从 0 起，首次发布 +1 后正好是 v1（和直接入库的种子规则版本号语义一致）
    req.setVersion(0);
    dao.insert(req);
    return req;
  }

  /**
   * Save an unmodified native DRL draft. The comment marker is metadata only and is accepted by
   * Drools.
   */
  public RuleDefinition createNative(RuleDefinition req) {
    if (req.getRuleName() == null || req.getRuleName().trim().isEmpty()) {
      throw new IllegalArgumentException("规则记录名称必填");
    }
    RuleTypeMeta meta = metaDao.findByType(req.getRuleType());
    if (meta == null) throw new IllegalArgumentException("未知规则类型: " + req.getRuleType());
    String source = requireNativeSource(req.getDrlContent());
    // Draft sources can depend on other resources in the type bundle; validate the complete
    // publication.
    req.setRuleGroup(meta.getRuleGroup());
    req.setRuleParams("{}");
    req.setDrlContent(NATIVE_DRL_MARKER + source);
    req.setStatus(0);
    req.setVersion(0);
    dao.insert(req);
    return req;
  }

  /**
   * Publish native DRL exactly as authored; no template rendering, DSL translation, or parameter
   * validation.
   */
  @Transactional
  public RuleDefinition publishNative(Long id, String source, long expectedVersion) {
    RuleDefinition rule = dao.findById(id);
    if (rule == null) throw new IllegalArgumentException("规则不存在: " + id);
    if (!isNativeDrl(rule.getDrlContent())) {
      throw new IllegalArgumentException("该规则不是原生 DRL 规则");
    }
    releases.lockType(rule.getRuleType());
    rule = dao.findById(id);
    checkVersion(rule, expectedVersion);
    String nativeSource =
        source == null || source.trim().isEmpty()
            ? stripNativeMarker(rule.getDrlContent())
            : requireNativeSource(source);
    // Complete rule-type bundle is validated before the transaction commits.
    rule.setDrlContent(NATIVE_DRL_MARKER + nativeSource);
    rule.setRuleParams("{}");
    dao.updateDrlAndParams(rule);
    dao.updateStatus(id, 1);
    releases.publishCurrent(rule.getRuleType());
    return dao.findById(id);
  }

  public static boolean isNativeDrl(String source) {
    return source != null && source.startsWith(NATIVE_DRL_MARKER);
  }

  public static String stripNativeMarker(String source) {
    return isNativeDrl(source) ? source.substring(NATIVE_DRL_MARKER.length()) : source;
  }

  private String requireNativeSource(String source) {
    if (source == null || source.trim().isEmpty())
      throw new IllegalArgumentException("原生 DRL 内容不能为空");
    return source;
  }

  /** 更新参数并发布：参数校验 -> 重新生成 DRL -> 试编译 -> 入库(status=1) -> 刷新缓存。 */
  @Transactional
  public RuleDefinition publish(Long id, String ruleParamsJson, long expectedVersion) {
    RuleDefinition r = dao.findById(id);
    if (r == null) {
      throw new IllegalArgumentException("规则不存在: " + id);
    }
    if (isNativeDrl(r.getDrlContent())) {
      throw new IllegalArgumentException(
          "该规则使用原生 DRL，请通过 /rule/native/publish/{id} 发布，避免源码被配置模板覆盖");
    }
    releases.lockType(r.getRuleType());
    r = dao.findById(id);
    checkVersion(r, expectedVersion);
    validator.validate(r.getRuleType(), ruleParamsJson); // 参数校验
    checkDuplicateCondition(r, ruleParamsJson); // 重复条件拦截
    String drl = generator.generate(r.getRuleType(), r.getRuleName(), ruleParamsJson);
    drlValidator.validate(drl); // 试编译
    r.setRuleParams(ruleParamsJson);
    r.setDrlContent(drl);
    dao.updateDrlAndParams(r);
    dao.updateStatus(id, 1);
    releases.publishCurrent(r.getRuleType());
    return dao.findById(id);
  }

  /** 同一类型 + 同一参数 = 两条规则的条件完全一样，谁先点火由引擎内部顺序决定，结果不可预期 （要改条件就发布同一条规则，而不是再建一条）。 */
  private void checkDuplicateCondition(RuleDefinition current, String ruleParamsJson) {
    for (RuleDefinition other : dao.findByStatus(1)) {
      if (other.getId().equals(current.getId())) {
        continue;
      }
      if (other.getRuleType().equals(current.getRuleType())
          && normalize(other.getRuleParams()).equals(normalize(ruleParamsJson))) {
        throw new IllegalArgumentException(
            "该类型 + 该参数组合已有生效规则["
                + other.getRuleName()
                + "]。要改它请点列表里的「编辑」；要再加一条规则，请让参数不同（例如会员等级选 GOLD 而不是 VIP）");
      }
    }
  }

  private String normalize(String json) {
    if (json == null || json.trim().isEmpty()) {
      return "{}";
    }
    try {
      return MAPPER.readTree(json).toString();
    } catch (Exception e) {
      return json.trim();
    }
  }

  /** 启用 / 禁用（status: 1 启用 / 2 禁用） */
  @Transactional
  public void changeStatus(Long id, int status, long expectedVersion) {
    RuleDefinition target = dao.findById(id);
    if (target == null) throw new IllegalArgumentException("规则不存在: " + id);
    releases.lockType(target.getRuleType());
    target = dao.findById(id);
    checkVersion(target, expectedVersion);
    if (status != 0 && status != 1 && status != 2)
      throw new IllegalArgumentException("Invalid rule status");
    if (status == 1) {
      // 直接"启用"一条草稿也会进 KieBase，这里必须和 publish 走同一套重复条件检查，
      // 否则绕过 publish 就能塞进两条 LHS/RHS 完全相同的规则（点火顺序不可预期）
      if (!isNativeDrl(target.getDrlContent()))
        checkDuplicateCondition(target, target.getRuleParams());
    }
    dao.bumpVersion(id);
    dao.updateStatus(id, status);
    releases.publishCurrent(target.getRuleType());
  }

  @Transactional
  public void delete(Long id, long expectedVersion) {
    RuleDefinition target = dao.findById(id);
    if (target != null) {
      releases.lockType(target.getRuleType());
      checkVersion(dao.findById(id), expectedVersion);
    }
    dao.delete(id);
    if (target != null) releases.publishCurrent(target.getRuleType());
  }

  private void checkVersion(RuleDefinition rule, long expectedVersion) {
    if (rule == null
        || rule.getVersion() == null
        || rule.getVersion().longValue() != expectedVersion)
      throw new com.example.drools.release.ReleaseConflictException(
          "Rule changed; reload before modifying it");
  }

}
