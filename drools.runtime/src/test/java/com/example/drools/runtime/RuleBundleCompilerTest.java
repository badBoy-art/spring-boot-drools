package com.example.drools.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.kie.api.KieServices;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.runtime.KieSession;
import org.kie.dmn.api.core.*;

class RuleBundleCompilerTest {
  private final ObjectMapper mapper = new ObjectMapper();

  private ObjectNode bundle(String... sources) {
    ObjectNode b = mapper.createObjectNode().put("ruleType", "TEST");
    ArrayNode resources = b.putArray("resources");
    for (int i = 0; i < sources.length; i++)
      resources
          .addObject()
          .put("path", "src/main/resources/test/part" + i + ".drl")
          .put("content", sources[i]);
    return b;
  }

  private CompiledRuleBundle compile(JsonNode b) {
    org.kie.api.KieBaseConfiguration options = KieServices.Factory.get().newKieBaseConfiguration();
    options.setOption(EventProcessingOption.STREAM);
    return new RuleBundleCompiler().compile(b, options);
  }

  @Test
  void crossFileFunctionsQueriesAndLogicalInsertionRemainNative() {
    ObjectNode bundle =
        bundle(
            "package test; global java.util.List audit; declare Marker code:String end function"
                + " String label(int value) {return \"v\"+value;}",
            "package test; query Markers $m:Marker() end rule derive when Integer(this>0) then"
                + " insertLogical(new Marker(label(2))); end rule observe when Marker($c:code) then"
                + " audit.add($c); end");
    try (CompiledRuleBundle compiled = compile(bundle)) {
      KieSession s = compiled.base().newKieSession();
      try {
        List<String> audit = new ArrayList<String>();
        s.setGlobal("audit", audit);
        org.kie.api.runtime.rule.FactHandle handle = s.insert(1);
        assertEquals(2, s.fireAllRules());
        assertEquals(Collections.singletonList("v2"), audit);
        assertEquals(1, s.getQueryResults("Markers").size());
        s.delete(handle);
        s.fireAllRules();
        assertEquals(0, s.getQueryResults("Markers").size());
      } finally {
        s.dispose();
      }
    }
  }

  @Test
  void dslAndDslrResourcesAreCompiledAsOneBundle() {
    ObjectNode b = mapper.createObjectNode().put("ruleType", "DSL");
    ArrayNode resources = b.putArray("resources");
    resources
        .addObject()
        .put("path", "src/main/resources/test/rules.dsl")
        .put(
            "content",
            "[when]There is a number=Integer()\n[then]Record success=audit.add(\"ok\");\n");
    resources
        .addObject()
        .put("path", "src/main/resources/test/rules.dslr")
        .put(
            "content",
            "package test;\n"
                + "global java.util.List audit;\n"
                + "rule \"dsl\"\n"
                + "when\n"
                + "There is a number\n"
                + "then\n"
                + "Record success\n"
                + "end\n");
    try (CompiledRuleBundle compiled = compile(b)) {
      KieSession s = compiled.base().newKieSession();
      try {
        List<String> audit = new ArrayList<String>();
        s.setGlobal("audit", audit);
        s.insert(7);
        assertEquals(1, s.fireAllRules());
        assertEquals(Collections.singletonList("ok"), audit);
      } finally {
        s.dispose();
      }
    }
  }

  @Test
  void csvDecisionTableCompilesAndExecutes() {
    ObjectNode b = mapper.createObjectNode().put("ruleType", "TABLE");
    b.putArray("resources")
        .addObject()
        .put("path", "src/main/resources/test/decisions.csv")
        .put("resourceType", "DTABLE")
        .put("inputType", "CSV")
        .put(
            "content",
            "RuleSet,test\n"
                + "Variables,java.util.List audit\n\n"
                + "RuleTable Check\n"
                + "NAME,CONDITION,ACTION\n"
                + ",Integer,\n"
                + ",this > $param,audit.add(\"$param\");\n"
                + "Name,Threshold,Output\n"
                + "large,10,hit\n");
    try (CompiledRuleBundle compiled = compile(b)) {
      KieSession s = compiled.base().newKieSession();
      try {
        List<String> audit = new ArrayList<String>();
        s.setGlobal("audit", audit);
        s.insert(11);
        assertEquals(1, s.fireAllRules());
        assertEquals(Collections.singletonList("hit"), audit);
      } finally {
        s.dispose();
      }
    }
  }

  @Test
  void dmnFeelEvaluatesThroughNativeRuntime() {
    String dmn =
        "<?xml version=\"1.0\"?><definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\""
            + " id=\"model\" name=\"Price\" namespace=\"urn:test\"><inputData id=\"input\""
            + " name=\"amount\"><variable id=\"inputvar\" name=\"amount\""
            + " typeRef=\"number\"/></inputData><decision id=\"decision\""
            + " name=\"doubleAmount\"><variable id=\"outvar\" name=\"doubleAmount\""
            + " typeRef=\"number\"/><informationRequirement><requiredInput"
            + " href=\"#input\"/></informationRequirement><literalExpression"
            + " id=\"expr\"><text>amount * 2</text></literalExpression></decision></definitions>";
    ObjectNode b = mapper.createObjectNode().put("ruleType", "DMN");
    b.putArray("resources")
        .addObject()
        .put("path", "src/main/resources/test/price.dmn")
        .put("content", dmn);
    try (CompiledRuleBundle compiled = compile(b)) {
      DMNRuntime runtime =
          org.kie.api.runtime.KieRuntimeFactory.of(compiled.base()).get(DMNRuntime.class);
      DMNContext context = runtime.newContext();
      context.set("amount", 12);
      DMNResult result = runtime.evaluateAll(runtime.getModel("urn:test", "Price"), context);
      assertFalse(result.hasErrors(), result.getMessages().toString());
      assertEquals(new java.math.BigDecimal("24"), result.getContext().get("doubleAmount"));
    }
  }

  @Test
  void nativeKmoduleAndExecutableModelRemainAvailable() {
    ObjectNode b = bundle("package test; rule match when String() then end");
    b.put("executableModel", true);
    b.put(
        "kmoduleXml",
        "<kmodule xmlns=\"http://www.drools.org/xsd/kmodule\"><kbase name=\"testBase\""
            + " default=\"true\" packages=\"test\"><ksession name=\"named\""
            + " default=\"true\"/></kbase></kmodule>");
    b.put("kieBaseName", "testBase");
    try (CompiledRuleBundle compiled = compile(b)) {
      KieSession s = compiled.container().newKieSession("named");
      try {
        s.insert("x");
        assertEquals(1, s.fireAllRules());
      } finally {
        s.dispose();
      }
    }
  }

  @Test
  void kjarCanBePublishedAndExecutedWithoutRewritingItsRules() {
    byte[] jar;
    try (CompiledRuleBundle original =
        compile(bundle("package test; rule jar when Integer(this==7) then end"))) {
      jar =
          ((org.drools.compiler.kie.builder.impl.InternalKieModule)
                  KieServices.Factory.get()
                      .getRepository()
                      .getKieModule(original.container().getReleaseId()))
              .getBytes();
    }
    ObjectNode binary =
        mapper
            .createObjectNode()
            .put("ruleType", "KJAR")
            .put("kjarBase64", Base64.getEncoder().encodeToString(jar));
    try (CompiledRuleBundle imported = compile(binary)) {
      KieSession s = imported.base().newKieSession();
      try {
        s.insert(7);
        assertEquals(1, s.fireAllRules());
      } finally {
        s.dispose();
      }
    }
  }

  @Test
  void nativePmmlRegressionModelExecutes() {
    String pmml =
        "<?xml version=\"1.0\"?><PMML xmlns=\"http://www.dmg.org/PMML-4_4\""
            + " version=\"4.4\"><Header/><DataDictionary numberOfFields=\"2\"><DataField name=\"x\""
            + " optype=\"continuous\" dataType=\"double\"/><DataField name=\"y\""
            + " optype=\"continuous\" dataType=\"double\"/></DataDictionary><RegressionModel"
            + " modelName=\"Linear\" functionName=\"regression\" targetFieldName=\"y\""
            + " normalizationMethod=\"none\"><MiningSchema><MiningField name=\"x\""
            + " usageType=\"active\"/><MiningField name=\"y\""
            + " usageType=\"target\"/></MiningSchema><RegressionTable"
            + " intercept=\"1\"><NumericPredictor name=\"x\""
            + " coefficient=\"2\"/></RegressionTable></RegressionModel></PMML>";
    ObjectNode b = mapper.createObjectNode().put("ruleType", "PMML");
    b.putArray("resources")
        .addObject()
        .put("path", "src/main/resources/test/linear.pmml")
        .put("content", pmml);
    try (CompiledRuleBundle compiled = compile(b)) {
      org.kie.pmml.api.runtime.PMMLRuntime runtime =
          org.kie.api.runtime.KieRuntimeFactory.of(compiled.base())
              .get(org.kie.pmml.api.runtime.PMMLRuntime.class);
      org.kie.api.pmml.PMMLRequestData request =
          new org.kie.api.pmml.PMMLRequestData("test", "Linear");
      request.addRequestParam("x", 3d);
      org.kie.api.pmml.PMML4Result result =
          runtime.evaluate("Linear", new org.kie.pmml.evaluator.core.PMMLContextImpl(request));
      assertEquals("OK", result.getResultCode());
      assertEquals(7d, ((Number) result.getResultVariables().get("y")).doubleValue());
    }
  }

  @Test
  void javaFactsCanBeCompiledAsPartOfTheNativeKieModule() throws Exception {
    ObjectNode b = mapper.createObjectNode();
    ArrayNode assets = b.putArray("resources");
    assets
        .addObject()
        .put("path", "src/main/java/test/NativeInput.java")
        .put(
            "content",
            "package test; public class NativeInput implements java.io.Serializable { public int"
                + " value=7; }");
    assets
        .addObject()
        .put("path", "src/main/resources/test/native.drl")
        .put("content", "package test; rule Native when NativeInput(value==7) then end");
    try (CompiledRuleBundle compiled = compile(b)) {
      Object fact =
          compiled.container().getClassLoader().loadClass("test.NativeInput").newInstance();
      KieSession session = compiled.base().newKieSession();
      try {
        session.insert(fact);
        assertEquals(1, session.fireAllRules());
      } finally {
        session.dispose();
      }
    }
  }

  @Test
  void excelDecisionTableCompilesAndExecutes() throws Exception {
    byte[] bytes;
    try (org.apache.poi.hssf.usermodel.HSSFWorkbook workbook =
        new org.apache.poi.hssf.usermodel.HSSFWorkbook()) {
      org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("Rules");
      String[][] rows = {
        {"RuleSet", "test"},
        {"Variables", "java.util.List audit"},
        {},
        {"RuleTable Check"},
        {"NAME", "CONDITION", "ACTION"},
        {"", "Integer", ""},
        {"", "this > $param", "audit.add(\"$param\");"},
        {"Name", "Threshold", "Output"},
        {"large", "10", "hit"}
      };
      for (int row = 0; row < rows.length; row++) {
        org.apache.poi.ss.usermodel.Row target = sheet.createRow(row);
        for (int column = 0; column < rows[row].length; column++)
          target.createCell(column).setCellValue(rows[row][column]);
      }
      java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
      workbook.write(output);
      bytes = output.toByteArray();
    }
    ObjectNode b = mapper.createObjectNode();
    b.putArray("resources")
        .addObject()
        .put("path", "src/main/resources/test/table.xls")
        .put("resourceType", "DTABLE")
        .put("inputType", "XLS")
        .put("worksheetName", "Rules")
        .put("base64", Base64.getEncoder().encodeToString(bytes));
    try (CompiledRuleBundle compiled = compile(b)) {
      KieSession session = compiled.base().newKieSession();
      try {
        List<String> audit = new ArrayList<>();
        session.setGlobal("audit", audit);
        session.insert(11);
        assertEquals(1, session.fireAllRules());
        assertEquals(Collections.singletonList("hit"), audit);
      } finally {
        session.dispose();
      }
    }
  }
}
