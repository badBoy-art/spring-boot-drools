package com.example.drools.controller;

import com.example.drools.entity.RuleDefinition;
import com.example.drools.entity.RuleTypeMeta;
import com.example.drools.service.DynamicRuleEngine;
import com.example.drools.service.RuleDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    private final ObjectMapper mapper = new ObjectMapper();

    public RuleController(RuleDefinitionService service, DynamicRuleEngine engine) {
        this.service = service;
        this.engine = engine;
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
        info.put("ruleCount", engine.getRuleCount());
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
