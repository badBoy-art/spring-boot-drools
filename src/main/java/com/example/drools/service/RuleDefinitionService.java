package com.example.drools.service;

import com.example.drools.dao.RuleDefinitionDao;
import com.example.drools.dao.RuleTypeMetaDao;
import com.example.drools.entity.RuleDefinition;
import com.example.drools.entity.RuleTypeMeta;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 规则定义业务层：运营配置 -> 类型校验(元数据) -> 参数校验 -> 生成 DRL -> 试编译 -> 入库 -> 刷新缓存。
 * 规则类型、参数字段、DRL 模板全部数据驱动（rule_type_meta / rule_type_field / rule_template），
 * 新增规则类型无需改 Java 代码。
 */
@Service
public class RuleDefinitionService {

    private final RuleDefinitionDao dao;
    private final RuleTypeMetaDao metaDao;
    private final DrlGenerator generator;
    private final DynamicRuleEngine engine;
    private final RuleParamValidator validator;

    public RuleDefinitionService(RuleDefinitionDao dao, RuleTypeMetaDao metaDao,
                                 DrlGenerator generator, DynamicRuleEngine engine,
                                 RuleParamValidator validator) {
        this.dao = dao;
        this.metaDao = metaDao;
        this.generator = generator;
        this.engine = engine;
        this.validator = validator;
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
        String drl = generator.generate(req.getRuleType(), req.getRuleName(), req.getRuleParams());
        engine.validate(drl); // 试编译，语法错误直接抛异常
        req.setRuleGroup(meta.getRuleGroup());
        req.setDrlContent(drl);
        req.setStatus(0);
        req.setVersion(1);
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
        String drl = generator.generate(r.getRuleType(), r.getRuleName(), ruleParamsJson);
        engine.validate(drl); // 试编译
        r.setRuleParams(ruleParamsJson);
        r.setDrlContent(drl);
        dao.updateDrlAndParams(r);
        dao.updateStatus(id, 1);
        engine.refresh(); // 重新编译整组规则并切换缓存
        return dao.findById(id);
    }

    /** 启用 / 禁用（status: 1 启用 / 2 禁用） */
    public void changeStatus(Long id, int status) {
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
