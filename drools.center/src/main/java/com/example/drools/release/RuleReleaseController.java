package com.example.drools.release;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rule/releases")
public class RuleReleaseController {
  private final RuleReleaseService service;

  public RuleReleaseController(RuleReleaseService service) {
    this.service = service;
  }

  @GetMapping("/{type}/draft")
  public JsonNode draft(@PathVariable String type) {
    return service.draft(type);
  }

  @PutMapping("/{type}/draft")
  public JsonNode save(@PathVariable String type, @RequestBody JsonNode body) {
    return service.saveDraft(
        type, number(body, "expectedDraftVersion"), body.path("configuration"));
  }

  @GetMapping("/{type}")
  public List<Map<String, Object>> history(@PathVariable String type) {
    return service.history(type);
  }

  @PostMapping("/{type}")
  public JsonNode publish(@PathVariable String type, @RequestBody JsonNode body) {
    return service.publish(
        type,
        number(body, "expectedRevision"),
        number(body, "expectedDraftVersion"),
        body.path("remark").asText());
  }

  @PostMapping("/{type}/rollback")
  public JsonNode rollback(@PathVariable String type, @RequestBody JsonNode body) {
    return service.rollback(
        type,
        number(body, "releaseId"),
        number(body, "expectedRevision"),
        body.path("remark").asText());
  }

  private long number(JsonNode body, String key) {
    if (!body.has(key) || !body.get(key).isIntegralNumber() || body.get(key).asLong() < 0)
      throw new IllegalArgumentException(key + " must be a non-negative integer");
    return body.get(key).asLong();
  }
}
