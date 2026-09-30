package com.example.drools.controller;

import com.example.drools.release.RuleRuntimeRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.kie.api.runtime.KieRuntimeFactory;
import org.kie.dmn.api.core.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class DecisionModelController {
  private final RuleRuntimeRegistry registry;

  public DecisionModelController(RuleRuntimeRegistry registry) {
    this.registry = registry;
  }

  @PostMapping("/rule/dmn/evaluate")
  public Map<String, Object> evaluate(
      @RequestParam String ruleType, @RequestBody JsonNode request) {
    try (RuleRuntimeRegistry.Lease lease = registry.acquire(ruleType)) {
      DMNRuntime runtime = KieRuntimeFactory.of(lease.runtime().base()).get(DMNRuntime.class);
      DMNModel model =
          runtime.getModel(request.path("namespace").asText(), request.path("modelName").asText());
      if (model == null) throw new IllegalArgumentException("DMN model not found in this release");
      DMNContext context = runtime.newContext();
      if (!request.path("data").isObject())
        throw new IllegalArgumentException("DMN data must be an object");
      Map<String, Object> data =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .convertValue(request.get("data"), Map.class);
      for (Map.Entry<String, Object> entry : data.entrySet())
        context.set(entry.getKey(), entry.getValue());
      DMNResult evaluated = runtime.evaluateAll(model, context);
      Map<String, Object> result = new LinkedHashMap<String, Object>();
      result.put("ruleType", ruleType);
      result.put("releaseId", lease.bundle().path("releaseId").asLong());
      result.put("decision", evaluated.getContext().getAll());
      result.put("hasErrors", evaluated.hasErrors());
      List<String> messages = new ArrayList<String>();
      for (DMNMessage message : evaluated.getMessages()) messages.add(message.toString());
      result.put("messages", messages);
      return result;
    }
  }

  @PostMapping("/rule/pmml/evaluate")
  public Map<String, Object> pmml(@RequestParam String ruleType, @RequestBody JsonNode request) {
    try (RuleRuntimeRegistry.Lease lease = registry.acquire(ruleType)) {
      org.kie.pmml.api.runtime.PMMLRuntime runtime =
          KieRuntimeFactory.of(lease.runtime().base())
              .get(org.kie.pmml.api.runtime.PMMLRuntime.class);
      String name = request.path("modelName").asText();
      org.kie.api.pmml.PMMLRequestData data =
          new org.kie.api.pmml.PMMLRequestData(java.util.UUID.randomUUID().toString(), name);
      if (!request.path("data").isObject())
        throw new IllegalArgumentException("PMML data must be an object");
      Map<String, Object> values =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .convertValue(request.get("data"), Map.class);
      for (Map.Entry<String, Object> entry : values.entrySet())
        data.addRequestParam(entry.getKey(), entry.getValue());
      org.kie.api.pmml.PMML4Result evaluated =
          runtime.evaluate(name, new org.kie.pmml.evaluator.core.PMMLContextImpl(data));
      Map<String, Object> result = new LinkedHashMap<String, Object>();
      result.put("ruleType", ruleType);
      result.put("releaseId", lease.bundle().path("releaseId").asLong());
      result.put("resultCode", evaluated.getResultCode());
      result.put("decision", evaluated.getResultVariables());
      return result;
    }
  }
}
