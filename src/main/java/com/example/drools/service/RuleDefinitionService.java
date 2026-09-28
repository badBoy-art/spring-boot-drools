package com.example.drools.service;

import com.example.drools.dao.RuleDefinitionDao;
import com.example.drools.dao.RuleHttpActionDao;
import com.example.drools.dao.RuleTypeMetaDao;
import com.example.drools.entity.RuleDefinition;
import com.example.drools.entity.RuleTypeMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 规则定义业务层：运营配置 -> 类型校验(元数据) -> 参数校验 -> 生成 DRL -> 试编译 -> 入库 -> 刷新缓存。
 * 规则类型、参数字段、DRL 模板全部数据驱动（rule_type_meta / rule_type_field / rule_template），
 * 新增规则类型无需改 Java 代码。
 */
@Service
public class RuleDefinitionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RuleDefinitionDao dao;
    private final RuleTypeMetaDao metaDao;
    private final DrlGenerator generator;
    private final DynamicRuleEngine engine;
    private final RuleParamValidator validator;
    private final RuleHttpActionDao httpActionDao;

    public RuleDefinitionService(RuleDefinitionDao dao, RuleTypeMetaDao metaDao,
                                 DrlGenerator generator, DynamicRuleEngine engine,
                                 RuleParamValidator validator, RuleHttpActionDao httpActionDao) {
        this.dao = dao;
        this.metaDao = metaDao;
        this.generator = generator;
        this.engine = engine;
        this.validator = validator;
        this.httpActionDao = httpActionDao;
    }

    public List<RuleDefinition> list() {
        return dao.findAll();
    }

    /** 规则类型元数据（供前端渲染配置表单 + 后端校验） */
    public List<RuleTypeMeta> listTypeMetas() {
        return metaDao.findAll();
    }

    /**
     * 新增规则（草稿态）：类型校验 -> 参数校验 -> 生成 DRL -> 试编译，全部通过才入库。
     */
    public RuleDefinition create(RuleDefinition req) {
        RuleTypeMeta meta = metaDao.findByType(req.getRuleType());
        if (meta == null) {
            throw new IllegalArgumentException("未知规则类型: " + req.getRuleType());
        }
        validator.validate(req.getRuleType(), req.getRuleParams()); // 参数校验
        validateActionCode(req.getRuleType(), req.getRuleParams()); // 引用 HTTP 动作时校验动作存在
        // 建草稿时就先拦"条件重复"：否则草稿建出来了、发布时才被拒，库里会留一堆永远发不出去的废草稿
        checkDuplicateCondition(req, req.getRuleParams());
        String drl = generator.generate(req.getRuleType(), req.getRuleName(), req.getRuleParams());
        engine.validate(drl); // 试编译，语法错误直接抛异常
        req.setRuleGroup(meta.getRuleGroup());
        req.setDrlContent(drl);
        req.setStatus(0);
        // 草稿版本号从 0 起，首次发布 +1 后正好是 v1（和直接入库的种子规则版本号语义一致）
        req.setVersion(0);
        dao.insert(req);
        return req;
    }

    /**
     * 更新参数并发布：参数校验 -> 重新生成 DRL -> 试编译 -> 入库(status=1) -> 刷新缓存。
     */
    public RuleDefinition publish(Long id, String ruleParamsJson) {
        RuleDefinition r = dao.findById(id);
        if (r == null) {
            throw new IllegalArgumentException("规则不存在: " + id);
        }
        validator.validate(r.getRuleType(), ruleParamsJson); // 参数校验
        validateActionCode(r.getRuleType(), ruleParamsJson); // 引用的接口（含步骤链的 stepNAction）必须在册
        checkDuplicateCondition(r, ruleParamsJson); // 重复条件拦截
        String drl = generator.generate(r.getRuleType(), r.getRuleName(), ruleParamsJson);
        engine.validate(drl); // 试编译
        r.setRuleParams(ruleParamsJson);
        r.setDrlContent(drl);
        dao.updateDrlAndParams(r);
        dao.updateStatus(id, 1);
        engine.refresh(); // 重新编译整组规则并切换缓存
        return dao.findById(id);
    }

    /**
     * 同一类型 + 同一参数 = 两条规则的条件完全一样，谁先点火由引擎内部顺序决定，结果不可预期
     * （要改条件就发布同一条规则，而不是再建一条）。
     * 决策表生成的规则不受影响：它走 rule_decision_table 单独编译。
     */
    private void checkDuplicateCondition(RuleDefinition current, String ruleParamsJson) {
        for (RuleDefinition other : dao.findByStatus(1)) {
            if (other.getId().equals(current.getId())) {
                continue;
            }
            if (other.getRuleType().equals(current.getRuleType())
                    && normalize(other.getRuleParams()).equals(normalize(ruleParamsJson))) {
                throw new IllegalArgumentException("该类型 + 该参数组合已有生效规则[" + other.getRuleName()
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

    /**
     * 规则参数里带 actionCode 时（调用 HTTP 接口类规则），校验动作已配置且启用 ——
     * 免得规则发布成功、运行期调用时才报"动作不存在"。
     */
    private void validateActionCode(String ruleType, String ruleParamsJson) {
        if (ruleParamsJson == null || ruleParamsJson.trim().isEmpty()) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(ruleParamsJson);
            // 逐一看所有"动作码"参数：经典约定的 actionCode，以及步骤链的 stepNAction（每一步调哪个接口）
            java.util.List<String> codes = new java.util.ArrayList<String>();
            com.fasterxml.jackson.databind.JsonNode classic = node.get("actionCode");
            if (classic != null && !classic.asText().trim().isEmpty()) {
                codes.add(classic.asText().trim());
            }
            java.util.Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String k = names.next();
                if (k.matches("step\\d+Action")) {
                    String v = node.get(k).asText().trim();
                    if (!v.isEmpty()) codes.add(v);
                }
            }
            for (String code : codes) {
                com.example.drools.entity.RuleHttpAction action = httpActionDao.findByCode(code);
                if (action == null) {
                    throw new IllegalArgumentException("规则类型[" + ruleType + "]引用的接口动作不存在: " + code
                            + "（请先在「② 接口注册」里注册，或把这一步的接口换成已注册的）");
                }
                if (action.getStatus() != null && action.getStatus() != 1) {
                    throw new IllegalArgumentException("接口动作已停用: " + code);
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 参数 JSON 非法的问题交给 RuleParamValidator 报更准确的信息
        }
    }

    /** 启用 / 禁用（status: 1 启用 / 2 禁用） */
    public void changeStatus(Long id, int status) {
        if (status == 1) {
            // 直接"启用"一条草稿也会进 KieBase，这里必须和 publish 走同一套重复条件检查，
            // 否则绕过 publish 就能塞进两条 LHS/RHS 完全相同的规则（点火顺序不可预期）
            RuleDefinition target = dao.findById(id);
            if (target == null) {
                throw new IllegalArgumentException("规则不存在: " + id);
            }
            checkDuplicateCondition(target, target.getRuleParams());
        }
        dao.updateStatus(id, status);
        engine.refresh();
    }

    public void delete(Long id) {
        dao.delete(id);
        engine.refresh();
    }

    /** 手动触发规则重载 */
    public void refresh() {
        engine.refresh();
    }
}
