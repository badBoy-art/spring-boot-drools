package com.example.drools.controller;

import com.example.drools.entity.RuleOutputField;
import com.example.drools.entity.RuleTypeMeta;
import com.example.drools.service.RuleTypeConfigurationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rule/type")
public class RuleTypeController {
  private final RuleTypeConfigurationService service;

  public RuleTypeController(RuleTypeConfigurationService service) {
    this.service = service;
  }

  @PostMapping("/native")
  public RuleTypeMeta nativeType(@RequestBody RuleTypeMeta meta) {
    return service.registerNative(meta);
  }

  @GetMapping("/list")
  public List<Map<String, Object>> list() {
    return service.list();
  }

  @GetMapping("/syntax")
  public Map<String, Object> syntax() {
    return service.syntax();
  }

  @GetMapping("/runtime")
  public JsonNode runtime(@RequestParam String ruleType) {
    return service.runtimeBundle(ruleType);
  }

  @GetMapping("/detail")
  public Map<String, Object> detail(@RequestParam String ruleType) {
    return service.detail(ruleType);
  }

  @GetMapping("/steps")
  public Map<String, Object> steps(@RequestParam String ruleType) {
    return service.steps(ruleType);
  }

  @PostMapping("/build")
  public Map<String, Object> build(@RequestBody Map<String, Object> body) {
    return service.build(body);
  }

  @PostMapping("/save")
  public Map<String, Object> save(@RequestBody Map<String, Object> body) {
    return service.save(body);
  }

  @PostMapping("/delete")
  public Map<String, Object> delete(@RequestParam String ruleType) {
    return service.delete(ruleType);
  }

  @GetMapping("/outputs/tree")
  public List<RuleOutputField> outputTree(@RequestParam String ruleType) {
    return service.outputTree(ruleType);
  }

  @PostMapping("/outputs/tree")
  public Map<String, Object> outputTree(@RequestBody Map<String, Object> body) {
    return service.saveOutputTree(body);
  }
}
