package com.example.drools;

import static org.junit.jupiter.api.Assertions.*;

import com.example.drools.domain.DocFact;
import com.example.drools.runtime.FrozenDocumentRuntime;
import com.example.drools.service.RuleStepBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

/** Runs the actual seed configuration against native Drools and the frozen output schema. */
class ProductApprovalExampleTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final Path samples =
      Paths.get(System.getProperty("basedir", "."), "..", "docs", "samples", "product-approval");

  private JsonNode sample(String name) throws Exception {
    return mapper.readTree(samples.resolve(name).toFile());
  }

  private Map<String, Object> configuration() throws Exception {
    return mapper.convertValue(sample("configuration.json"), Map.class);
  }

  private KieBase compile(String threshold) throws Exception {
    Map<String, Object> built = new RuleStepBuilder().build(configuration());
    assertEquals("marginThreshold", ((List<Map<String, Object>>) built.get("fields")).get(0).get("fieldKey"));
    String drl = String.valueOf(built.get("drlPreview"))
        .replace("@RULE@", "PRODUCT_MULTI_APPROVAL_DEFAULT")
        .replace("${marginThreshold}", threshold);
    return new KieHelper().addContent(drl, ResourceType.DRL).build();
  }

  private Map<String, Object> evaluate(KieBase base, JsonNode request) throws Exception {
    ObjectNode bundle = mapper.createObjectNode();
    bundle.put("ruleType", "PRODUCT_MULTI_APPROVAL");
    bundle.put("docCode", "PRODUCT_SKU");
    bundle.set("documentObjects", sample("registration.json").get("objects"));
    bundle.set("documentFields", sample("registration.json").get("fields"));
    // A publication made before schema cleanup still contains this retired authoring flag.
    ((ObjectNode) bundle.get("documentFields").get(0)).put("isOutput", 0);
    bundle.set("outputFields", sample("configuration.json").get("outputStructure"));
    FrozenDocumentRuntime runtime = new FrozenDocumentRuntime();
    KieSession session = base.newKieSession();
    try {
      DocFact fact = runtime.insert(bundle, mapper.convertValue(request, Map.class), session);
      int fired = session.fireAllRules(100);
      assertTrue(fired < 100, "Steps must converge");
      return runtime.result(bundle, fact, fired);
    } finally {
      session.dispose();
    }
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> rows(Map<String, Object> decision) {
    List<Map<String, Object>> rows = new ArrayList<>();
    rows.add((Map<String, Object>) decision.get("spu"));
    rows.addAll((List<Map<String, Object>>) decision.get("sku"));
    rows.addAll((List<Map<String, Object>>) decision.get("regionPrice"));
    return rows;
  }

  @Test
  @SuppressWarnings("unchecked")
  void publishedExampleCalculatesIndependentRowsAndExactThresholdBoundaries() throws Exception {
    KieBase base = compile("0.3");
    Map<String, Object> result = evaluate(base, sample("request.json"));
    assertEquals(7, result.get("fired"));
    Map<String, Object> decision = (Map<String, Object>) result.get("decision");
    assertEquals(new HashSet<>(Arrays.asList("spu", "sku", "regionPrice")), decision.keySet());
    List<Map<String, Object>> skus = (List<Map<String, Object>>) decision.get("sku");
    assertEquals("SKU-001", skus.get(0).get("skuCode"));
    assertEquals("T+2", skus.get(0).get("instanceId"));
    assertEquals("T", skus.get(1).get("instanceId"));
    assertEquals(new BigDecimal("0.30000000"), skus.get(2).get("marginRate"));
    List<Map<String, Object>> regions = (List<Map<String, Object>>) decision.get("regionPrice");
    assertEquals("STANDARD", regions.get(0).get("priceDecision"));
    assertEquals("REVIEW", regions.get(1).get("priceDecision"));

    String[][] cases = {
      {"0.13", "0.10", "T"},
      {"0.130000001", "0.10", "T+2"},
      {"0.129999999", "0.10", "T"},
      {"100", "0", "INVALID_COST"},
      {"100", "-1", "INVALID_COST"},
      {"90", "100", "T"}
    };
    for (String[] values : cases) {
      ObjectNode request = (ObjectNode) sample("request.json");
      List<JsonNode> inputs = new ArrayList<>();
      inputs.add(request.get("spu"));
      request.get("sku").forEach(inputs::add);
      request.get("regionPrice").forEach(inputs::add);
      for (JsonNode input : inputs) {
        ((ObjectNode) input).put("salePrice", values[0]);
        ((ObjectNode) input).put("costPrice", values[1]);
      }
      Map<String, Object> output = (Map<String, Object>) evaluate(base, request).get("decision");
      for (Map<String, Object> row : rows(output)) {
        assertEquals(values[2], row.get("instanceId"), Arrays.toString(values));
        if ("INVALID_COST".equals(values[2])) assertFalse(row.containsKey("marginRate"));
      }
    }
    ObjectNode empty = (ObjectNode) sample("request.json");
    empty.putArray("sku");
    empty.putArray("regionPrice");
    Map<String, Object> emptyOutput = evaluate(base, empty);
    assertEquals(1, emptyOutput.get("fired"));
    assertEquals(Collections.emptyList(), ((Map<?, ?>) emptyOutput.get("decision")).get("sku"));
    assertEquals(Collections.emptyList(), ((Map<?, ?>) emptyOutput.get("decision")).get("regionPrice"));
    // The displayed ratio rounds to 8 places; routing still compares exact values before rounding.
    Map<String, Object> changed = (Map<String, Object>) evaluate(compile("0.5"), sample("request.json")).get("decision");
    for (Map<String, Object> row : rows(changed)) assertEquals("T", row.get("instanceId"));
  }

  @Test
  void explicitParametersRejectDuplicateReservedAndMalformedDefinitions() throws Exception {
    Map<String, Object> request = configuration();
    Map<String, Object> parameter = new LinkedHashMap<>(((List<Map<String, Object>>) request.get("parameters")).get(0));
    request.put("parameters", Arrays.asList(parameter, parameter));
    assertThrows(IllegalArgumentException.class, () -> new RuleStepBuilder().build(request));
    parameter.put("fieldKey", "step1Value");
    request.put("parameters", Collections.singletonList(parameter));
    assertThrows(IllegalArgumentException.class, () -> new RuleStepBuilder().build(request));
    request.put("parameters", "invalid");
    assertThrows(IllegalArgumentException.class, () -> new RuleStepBuilder().build(request));
  }
}
