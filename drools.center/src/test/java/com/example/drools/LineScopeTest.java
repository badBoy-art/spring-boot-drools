package com.example.drools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.drools.domain.DocFact;
import com.example.drools.domain.DocItem;
import com.example.drools.entity.RuleOutputField;
import com.example.drools.service.DrlSyntax;
import com.example.drools.runtime.document.DocumentOutputAssembler;
import com.example.drools.service.RuleStepBuilder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

/** 明细行校验（第 N 条 / 每条）+ 返回值结构 —— 纯单元测试（不起服务、不连库）。 */
class LineScopeTest {

  private static final String HEADER =
      "package com.example.drools.dynamic;\n"
          + "dialect \"mvel\"\n"
          + "import java.util.List;\n"
          + "import java.util.ArrayList;\n"
          + "import com.example.drools.domain.DocFact;\n"
          + "import com.example.drools.domain.DocItem;\n\n";

  /** EACH（每条）+ NTH（第N条）生成的 DRL 能编译、能跑、能收敛 */
  @Test
  void eachAndNthDrlCompilesAndRuns() {
    RuleStepBuilder builder = new RuleStepBuilder();
    Map<String, Object> req = new LinkedHashMap<String, Object>();
    req.put("ruleType", "ITEM_CHECK_TEST");
    req.put("typeName", "明细校验");
    req.put("docCode", "ORDER");
    List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
    Map<String, Object> s1 = new LinkedHashMap<String, Object>();
    s1.put("stepName", "每条明细售价非负");
    s1.put("condField", "price");
    s1.put("condOp", "<=");
    s1.put("condType", "NUMBER");
    s1.put("condValue", "0");
    s1.put("condScope", "EACH");
    s1.put("collectionPath", "items");
    s1.put("actionType", "SET_EXT");
    steps.add(s1);
    Map<String, Object> s2 = new LinkedHashMap<String, Object>();
    s2.put("stepName", "第3条高价标记");
    s2.put("condField", "price");
    s2.put("condOp", ">");
    s2.put("condType", "NUMBER");
    s2.put("condValue", "100");
    s2.put("condScope", "NTH");
    s2.put("collectionPath", "items");
    s2.put("nthIndex", 2);
    s2.put("actionType", "SET_EXT");
    s2.put("outputs", outputs(output("nthHigh", "BOOLEAN", "true")));
    steps.add(s2);
    req.put("steps", steps);

    Map<String, Object> built = builder.build(req);
    String body = (String) built.get("templateBody");
    // 断言生成的 DRL 关键片段
    assertTrue(body.contains("accumulate"), "EACH 应用 accumulate 收齐违规行");
    assertTrue(body.contains("collectList"), "EACH 应用 collectList");
    assertTrue(body.contains("index == 2"), "NTH 应有 index == 2");
    assertTrue(body.contains("$bad : List( size > 0 )"), "EACH 应绑定 List(size>0)");

    String drl =
        HEADER
            + body.replace("@RULE@", "ITEM_CHECK_TEST")
                .replace("${step1Value}", "0")
                .replace("${step2Value}", "100");

    KieBase base = new KieHelper().addContent(drl, ResourceType.DRL).build();
    KieSession session = base.newKieSession();
    DocFact fact = new DocFact("ORDER", new LinkedHashMap<String, Object>());
    DocItem thirdItem = new DocItem("ORDER", "items", 2, item("SKU-C", 150));
    try {
      session.insert(fact);
      session.insert(new DocItem("ORDER", "items", 0, item("SKU-A", 50)));
      session.insert(new DocItem("ORDER", "items", 1, item("SKU-B", -5)));
      session.insert(thirdItem);
      int fired = session.fireAllRules(200);
      assertEquals(2, fired, "EACH 一条 + NTH 一条，收敛不重入");
    } finally {
      session.dispose();
    }
    assertEquals(1, ((Number) fact.getExt().get("itemFailCount")).intValue(), "违规计数=1");
    @SuppressWarnings("unchecked")
    List<DocItem> badItems = (List<DocItem>) fact.getExt().get("badItems");
    assertEquals(1, badItems.size(), "违规行列表=1");
    assertEquals(1, badItems.get(0).getIndex(), "违规行是第2条(index=1)");
    assertEquals(Boolean.TRUE, thirdItem.getExt().get("nthHigh"), "第3条高价标记写入当前行");
    assertTrue(!fact.getExt().containsKey("nthHigh"), "逐项输出不能污染单据级结果");
  }

  @Test
  void multiScopeStepsAssignIndependentOutputsToSpuSkuAndRegionPrice() {
    RuleStepBuilder builder = new RuleStepBuilder();
    Map<String, Object> req = new LinkedHashMap<String, Object>();
    req.put("ruleType", "PRODUCT_FLOW");
    req.put("typeName", "商品多层审批");
    req.put("docCode", "PRODUCT");
    List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
    steps.add(
        step(
            "SPU审批",
            "DOC",
            null,
            outputs(
                output(
                    "instanceId",
                    "EXPR",
                    "(spu.salePrice - spu.costPrice) / spu.costPrice > 0.3 ? \"T+2\" : \"T\""))));
    steps.add(
        step(
            "SKU逐项审批",
            "EACH",
            "skus",
            outputs(
                output(
                    "skuInstanceId",
                    "EXPR",
                    "(salePrice - costPrice) / costPrice > 0.3 ? \"T+2\" : \"T\""),
                output("skuDecision", "EXPR", "stock > 0 ? \"AVAILABLE\" : \"OUT_OF_STOCK\""))));
    steps.add(
        step(
            "区域价格逐项审批",
            "EACH",
            "regionPrices",
            outputs(
                output(
                    "regionInstanceId",
                    "EXPR",
                    "(regionSalePrice - regionCostPrice) / regionCostPrice > 0.3 ? \"T+2\" :"
                        + " \"T\""),
                output(
                    "regionMarginRate",
                    "EXPR",
                    "(regionSalePrice - regionCostPrice) / regionCostPrice"),
                output(
                    "regionDecision",
                    "EXPR",
                    "regionSalePrice <= marketPrice ? \"STANDARD\" : \"REVIEW\""))));
    req.put("steps", steps);

    String body = (String) builder.build(req).get("templateBody");
    String drl = HEADER + body.replace("@RULE@", "PRODUCT_FLOW");
    KieBase base = new KieHelper().addContent(drl, ResourceType.DRL).build();
    KieSession session = base.newKieSession();

    Map<String, Object> spu =
        map("salePrice", 120, "costPrice", 80, "spuId", "SPU-1", "spuName", "Coat");
    List<Map<String, Object>> skuData =
        Arrays.asList(
            map("skuId", "SKU-1", "salePrice", 120, "costPrice", 80, "stock", 5),
            map("skuId", "SKU-2", "salePrice", 100, "costPrice", 80, "stock", 0));
    List<Map<String, Object>> regionData =
        Arrays.asList(
            map(
                "regionCode",
                "EAST",
                "regionSalePrice",
                130,
                "regionCostPrice",
                80,
                "marketPrice",
                135),
            map(
                "regionCode",
                "WEST",
                "regionSalePrice",
                120,
                "regionCostPrice",
                100,
                "marketPrice",
                110));
    spu.put("skus", skuData);
    spu.put("regionPrices", regionData);
    DocFact fact = new DocFact("PRODUCT", map("spu", spu));
    List<DocItem> skuItems = items("PRODUCT", "skus", skuData);
    List<DocItem> regionItems = items("PRODUCT", "regionPrices", regionData);
    fact.getLineItemsByPath().put("spu.skus", skuItems);
    fact.getLineItemsByPath().put("spu.regionPrices", regionItems);
    try {
      session.insert(fact);
      for (DocItem item : skuItems) session.insert(item);
      for (DocItem item : regionItems) session.insert(item);
      assertEquals(5, session.fireAllRules(100));
    } finally {
      session.dispose();
    }
    assertEquals("T+2", fact.getExt().get("instanceId"));
    assertEquals("T+2", skuItems.get(0).getExt().get("skuInstanceId"));
    assertEquals("T", skuItems.get(1).getExt().get("skuInstanceId"));
    assertEquals("AVAILABLE", skuItems.get(0).getExt().get("skuDecision"));
    assertEquals("OUT_OF_STOCK", skuItems.get(1).getExt().get("skuDecision"));
    assertEquals("T+2", regionItems.get(0).getExt().get("regionInstanceId"));
    assertEquals("T", regionItems.get(1).getExt().get("regionInstanceId"));

    List<RuleOutputField> nodes = Arrays.asList(
                out("spu", null, "OBJECT", null, null, null, null, 1),
                out("spu.spuId", "spu", "LEAF", "DATA", "spuId", "STRING", null, 1),
                out("spu.instanceId", "spu", "LEAF", "EXT", "instanceId", "STRING", null, 2),
                out(
                    "spu.marginRate",
                    "spu",
                    "LEAF",
                    "EXPR",
                    "(salePrice - costPrice) / costPrice",
                    "DECIMAL",
                    null,
                    3),
                out("skus", null, "ARRAY", null, null, null, "data.spu.skus", 2),
                out("skus.skuId", "skus", "LEAF", "DATA", "skuId", "STRING", null, 1),
                out(
                    "skus.skuInstanceId",
                    "skus",
                    "LEAF",
                    "EXT",
                    "skuInstanceId",
                    "STRING",
                    null,
                    2),
                out("skus.skuDecision", "skus", "LEAF", "EXT", "skuDecision", "STRING", null, 3),
                out("regionPrices", null, "ARRAY", null, null, null, "data.spu.regionPrices", 3),
                out(
                    "regionPrices.regionCode",
                    "regionPrices",
                    "LEAF",
                    "DATA",
                    "regionCode",
                    "STRING",
                    null,
                    1),
                out(
                    "regionPrices.regionInstanceId",
                    "regionPrices",
                    "LEAF",
                    "EXT",
                    "regionInstanceId",
                    "STRING",
                    null,
                    2),
                out(
                    "regionPrices.regionMarginRate",
                    "regionPrices",
                    "LEAF",
                    "EXT",
                    "regionMarginRate",
                    "DECIMAL",
                    null,
                    3),
                out(
                    "regionPrices.regionDecision",
                    "regionPrices",
                    "LEAF",
                    "EXT",
                    "regionDecision",
                    "STRING",
                    null,
                    4));
    Map<String, Object> result = new DocumentOutputAssembler().assemble(fact, nodes);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> mappedSkus = (List<Map<String, Object>>) result.get("skus");
    assertEquals("T", mappedSkus.get(1).get("skuInstanceId"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> mappedRegions =
        (List<Map<String, Object>>) result.get("regionPrices");
    assertEquals("REVIEW", mappedRegions.get(1).get("regionDecision"));
  }

  private Map<String, Object> step(
      String name, String scope, String collection, List<Map<String, Object>> outputs) {
    Map<String, Object> step = new LinkedHashMap<String, Object>();
    step.put("stepName", name);
    step.put("condScope", scope);
    step.put("collectionPath", collection);
    step.put("actionType", "SET_EXT");
    step.put("outputs", outputs);
    return step;
  }

  private Map<String, Object> output(String key, String type, String expression) {
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("outputKey", key);
    output.put("valueType", type);
    output.put("expression", expression);
    return output;
  }

  private List<Map<String, Object>> outputs(Map<String, Object>... rows) {
    return Arrays.asList(rows);
  }

  private Map<String, Object> map(Object... kv) {
    Map<String, Object> out = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) out.put(String.valueOf(kv[i]), kv[i + 1]);
    return out;
  }

  private List<DocItem> items(String doc, String collection, List<Map<String, Object>> data) {
    List<DocItem> out = new ArrayList<DocItem>();
    for (int i = 0; i < data.size(); i++) out.add(new DocItem(doc, collection, i, data.get(i)));
    return out;
  }

  private Map<String, Object> item(String sku, double price) {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put("sku", sku);
    m.put("price", price);
    m.put("quantity", 1);
    return m;
  }

  /** 多字段算式里的数组下标路径（items[2].price）不再被拆坏 */
  @Test
  void toFactExpressionKeepsArrayIndex() {
    assertEquals("getNumber(\"items[2].price\")", DrlSyntax.toFactExpression("items[2].price"));
    String expr = DrlSyntax.toFactExpression("(items[2].price - items[2].cost) / items[2].cost");
    assertEquals(
        "(getNumber(\"items[2].price\") - getNumber(\"items[2].cost\")) /"
            + " getNumber(\"items[2].cost\")",
        expr);
    assertEquals(
        "getNumber(\"marginRate\") > 0.3 ? \"T+2\" : \"T\"",
        DrlSyntax.toFactExpression("marginRate > 0.3 ? \"T+2\" : \"T\""));
  }

  /** 返回值结构：OBJECT/LEAF/ARRAY/EXPR 组装成嵌套 decision */
  @Test
  void returnStructureAssemblesNestedDecision() {
    List<RuleOutputField> nodes = Arrays.asList(
                out("result", null, "OBJECT", null, null, null, null, 1),
                out(
                    "result.failCount",
                    "result",
                    "LEAF",
                    "EXT",
                    "itemFailCount",
                    "NUMBER",
                    null,
                    1),
                out(
                    "result.hasViolation",
                    "result",
                    "LEAF",
                    "EXPR",
                    "itemFailCount > 0",
                    "BOOLEAN",
                    null,
                    2),
                out("result.badItems", "result", "ARRAY", null, null, null, "ext.badItems", 3),
                out(
                    "result.badItems.sku",
                    "result.badItems",
                    "LEAF",
                    "DATA",
                    "sku",
                    "STRING",
                    null,
                    1),
                out(
                    "result.badItems.price",
                    "result.badItems",
                    "LEAF",
                    "DATA",
                    "price",
                    "DECIMAL",
                    null,
                    2),
                out(
                    "result.marginRate",
                    "result",
                    "LEAF",
                    "DATA",
                    "marginRate",
                    "DECIMAL",
                    null,
                    4),
                out(
                    "discount10",
                    null,
                    "LEAF",
                    "EXPR",
                    "round(totalAmount * 0.1, 2)",
                    "NUMBER",
                    null,
                    4));

    DocumentOutputAssembler svc = new DocumentOutputAssembler();
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("totalAmount", 300);
    data.put("marginRate", "0.35");
    DocFact fact = new DocFact("ORDER", data);
    fact.getExt().put("itemFailCount", 1);
    List<DocItem> badItems = new ArrayList<DocItem>();
    Map<String, Object> badItem = item("SKU-B", -5);
    badItems.add(new DocItem("ORDER", "items", 1, badItem));
    fact.getExt().put("badItems", badItems);

    Map<String, Object> decision = svc.assemble(fact, nodes);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) decision.get("result");
    assertEquals(1, ((Number) result.get("failCount")).intValue());
    assertEquals(Boolean.TRUE, result.get("hasViolation"));
    assertEquals(1, ((List<?>) result.get("badItems")).size());
    @SuppressWarnings("unchecked")
    Map<String, Object> mappedItem =
        (Map<String, Object>) ((List<?>) result.get("badItems")).get(0);
    assertEquals("SKU-B", mappedItem.get("sku"));
    assertEquals(new BigDecimal("-5.0"), mappedItem.get("price"));
    assertEquals(new BigDecimal("0.35"), result.get("marginRate"));
    assertEquals(30.0, (Double) decision.get("discount10"), 0.001);
  }

  private RuleOutputField out(
      String path,
      String parent,
      String kind,
      String source,
      String sourceValue,
      String valueType,
      String arrayFrom,
      int sort) {
    RuleOutputField f = new RuleOutputField();
    f.setOutputPath(path);
    f.setParentPath(parent);
    f.setLabel(path);
    f.setNodeKind(kind);
    f.setSource(source);
    f.setSourceValue(sourceValue);
    f.setValueType(valueType);
    f.setArrayFrom(arrayFrom);
    f.setSortOrder(sort);
    return f;
  }
}
