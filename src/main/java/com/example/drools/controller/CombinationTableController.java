package com.example.drools.controller;

import com.example.drools.service.CombinationTableService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 组合规则表（页面版决策表）：一张表 = 条件列 + 动作 + N 行组合，一次发布生成 N 条规则。
 * 数据存在库里，引擎直接编译生成的 DRL —— 全程无 Excel。
 */
@RestController
@RequestMapping("/rule/ct")
public class CombinationTableController {

    private final CombinationTableService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public CombinationTableController(CombinationTableService service) {
        this.service = service;
    }

    @GetMapping("/assets")
    public List<Map<String, Object>> assets() {
        return service.list();
    }

    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam String assetKey) {
        return service.detail(assetKey);
    }

    /** 预览：校验 + 生成 DRL（不落库、不发布） */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, Object> body) throws Exception {
        return service.preview(mapper.valueToTree(body));
    }

    /** 保存定义（不动状态，发布才进引擎） */
    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> body,
                                    @RequestParam(value = "updatedBy", defaultValue = "admin") String updatedBy) throws Exception {
        JsonNode req = mapper.valueToTree(body);
        return service.save(req, updatedBy);
    }

    /** 发布生效：生成 DRL → 试编译 → 入库 → 重建引擎缓存 */
    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestParam String assetKey,
                                       @RequestParam(value = "updatedBy", defaultValue = "admin") String updatedBy) {
        return service.publish(assetKey, updatedBy);
    }

    /** 启停（status 1 启用 / 2 停用） */
    @PostMapping("/status")
    public Map<String, Object> status(@RequestParam String assetKey, @RequestParam int status) {
        return service.changeStatus(assetKey, status);
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestParam String assetKey) {
        return service.delete(assetKey);
    }
}
