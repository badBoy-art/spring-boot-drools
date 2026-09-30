package com.example.drools.controller;

import com.example.drools.entity.*;
import com.example.drools.service.DocumentRegistrationService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rule/doc")
public class RuleDocumentController {
  private final DocumentRegistrationService service;

  public RuleDocumentController(DocumentRegistrationService service) {
    this.service = service;
  }

  @GetMapping("/tree")
  public List<Map<String, Object>> tree() {
    return service.tree();
  }

  @PostMapping("/save")
  public Object save(@RequestBody RuleDocument doc) {
    return service.save(doc);
  }

  @PostMapping("/object/save")
  public Object object(@RequestBody RuleDocumentObject object) {
    return service.saveObject(object);
  }

  @PostMapping("/field/save")
  public Object field(@RequestBody RuleDocumentField field) {
    return service.saveField(field);
  }

  @PostMapping("/field/delete")
  public Object delete(@RequestBody RuleDocumentField field) {
    return service.deleteField(field);
  }
}
