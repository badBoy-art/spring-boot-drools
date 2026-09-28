package com.example.drools.controller;

import com.example.drools.entity.RuleDefinition;
import com.example.drools.entity.RuleTypeMeta;
import com.example.drools.dao.RuleDocumentDao;
import com.example.drools.domain.DocFact;
import com.example.drools.service.DocFactBuilder;
import com.example.drools.service.DynamicRuleEngine;
import com.example.drools.service.RuleDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运营规则管理接口：配置 -> 生成 DRL -> 入库 -> 编译生效。
 */
@RestController
@RequestMapping("/rule")
public class RuleController {

    private final RuleDefinitionService service;
    private final DynamicRuleEngine engine;
    private final RuleDocumentDao documentDao;
    private final DocFactBuilder docFactBuilder;
    private final ObjectMapper mapper = new ObjectMapper();

    public RuleController(RuleDefinitionService service, DynamicRuleEngine engine, RuleDocumentDao documentDao,
                          DocFactBuilder docFactBuilder) {
        this.service = service;
        this.engine = engine;
        this.documentDao = documentDao;
        this.docFactBuilder = docFactBuilder;
    }

    /**
     * 通用单据评估入口：任何注册进中台的单据都能这样跑规则，不用为它写 Java 类。
     * POST /rule/evaluate?docCode=WF   body = 单据注册里登记的那些字段（可嵌套）
     * 返回：data（原样）+ ext（接口回填）+ messages（规则过程信息）
     */
    @PostMapping("/evaluate")
    public Map<String, Object> evaluate(@RequestParam String docCode, @RequestBody(required = false) Map<String, Object> body) {
        if (documentDao.findDocument(docCode) == null) {
            throw new IllegalArgumentException("单据未注册: " + docCode + "（请先在 ⑤ 单据注册里登记单据/对象/字段）");
        }
        // 单据报文 → 事实：注册的派生字段（如 毛利率 = (售价-成本)/售价）在这里统一算好再进规则
        DocFact fact = docFactBuilder.build(docCode, body);
        org.kie.api.runtime.KieSession session = engine.newKieSession();
        try {
            session.insert(fact);
            int fired = engine.fireAllRules(session);
            Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
            // decision：只装"单据注册里标了决策输出"的字段（业务只读这一块就够）
            //   取值优先 ext（规则写进去的决策），其次 data（入参/派生的原值）
            Map<String, Object> decision = new java.util.LinkedHashMap<String, Object>();
            for (String outKey : documentDao.findOutputFieldKeys(docCode)) {
                Object v = fact.getExt().containsKey(outKey) ? fact.getExt().get(outKey) : fact.getData().get(outKey);
                if (v != null) {
                    decision.put(outKey, v);
                }
            }
            result.put("decision", decision);
            result.put("evalId", java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            result.put("evaluatedAt", java.time.LocalDateTime.now().withNano(0).toString());
            result.put("docCode", docCode);
            result.put("bizId", fact.getBizId());
            result.put("data", fact.getData());
            result.put("ext", fact.getExt());
            result.put("messages", fact.getMessages());
            result.put("fired", fired);
            return result;
        } finally {
            session.dispose();
        }
    }

    /** 全部规则 */
    @GetMapping("/list")
    public List<RuleDefinition> list() {
        return service.list();
    }

    /** 规则类型元数据（管理后台据此渲染配置表单） */
    @GetMapping("/type/meta")
    public List<RuleTypeMeta> typeMetas() {
        return service.listTypeMetas();
    }

    /** 当前生效规则数 + 引擎状态 */
    @GetMapping("/engine/info")
    public Map<String, Object> engineInfo() {
        Map<String, Object> info = new java.util.HashMap<>();
        // ruleCount：KieBase 内实际规则总数（含组合规则表展开出来的规则）
        info.put("ruleCount", engine.getRuleCount());
        // publishedRuleCount：数据库里 status=1 的单条规则条数（不含组合规则表展开）
        info.put("publishedRuleCount", engine.getPublishedRuleCount());
        // 最近一次加载规则失败的原因（成功时为 null）
        info.put("lastRefreshError", engine.getLastRefreshError());
        return info;
    }

    /**
     * 新增规则（草稿态，元数据校验 + 试编译通过才入库，不立即生效）。
     * body 示例：{"ruleName":"FULL_REDUCTION_2000","ruleType":"FULL_REDUCTION","ruleParams":"{\"threshold\":2000,\"reduction\":150}"}
     */
    @PostMapping("/create")
    public RuleDefinition create(@RequestBody RuleDefinition req) {
        return service.create(req);
    }

    /**
     * 更新参数并发布（元数据校验 + 重新生成 DRL + 试编译 + 刷新缓存，立即生效）。
     * body 为参数 JSON 对象，如 {"threshold":2000,"reduction":150}
     */
    @PostMapping("/publish/{id}")
    public RuleDefinition publish(@PathVariable Long id, @RequestBody Map<String, Object> params) throws Exception {
        return service.publish(id, mapper.writeValueAsString(params));
    }

    /** 启用/禁用，body: {"status": 1} */
    @PostMapping("/status/{id}")
    public String changeStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        service.changeStatus(id, body.get("status"));
        return "ok";
    }

    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id) {
        service.delete(id);
        return "ok";
    }

    /** 手动重载全部规则 */
    @PostMapping("/refresh")
    public String refresh() {
        service.refresh();
        return "ok";
    }
}
