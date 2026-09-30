package com.example.drools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.KieBaseConfiguration;
import org.kie.api.KieServices;
import org.kie.api.conf.EventProcessingOption;
import org.kie.api.definition.type.FactType;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.QueryResults;
import org.kie.internal.utils.KieHelper;

/** Verifies advanced DRL assets reach the native Drools compiler/runtime without DSL rewriting. */
class NativeDrlCompatibilityTest {
  @Test
  void nativeFunctionGlobalQueryAgendaAndDeclaredFactWorkTogether() throws Exception {
    String drl =
        "package advanced.sample;\n"
            + "declare RiskSignal\n"
            + "  code : String\n"
            + "  score : Integer\n"
            + "end\n"
            + "global java.util.List auditTrail;\n"
            + "function String riskLevel(Integer score) { return score >= 90 ? \"HIGH\" :"
            + " \"NORMAL\"; }\n"
            + "query HighRisk(Integer minimum)\n"
            + "  $signal : RiskSignal(score >= minimum) from entry-point \"Signals\"\n"
            + "end\n"
            + "rule \"native-risk-rule\"\n"
            + "  agenda-group \"decision\"\n"
            + "  salience 50\n"
            + "when\n"
            + "  $signal : RiskSignal($score : score >= 80) from entry-point \"Signals\"\n"
            + "then\n"
            + "  auditTrail.add(riskLevel($score));\n"
            + "end\n";

    KieBaseConfiguration baseConfiguration = KieServices.Factory.get().newKieBaseConfiguration();
    baseConfiguration.setOption(EventProcessingOption.STREAM);
    KieBase base = new KieHelper().addContent(drl, ResourceType.DRL).build(baseConfiguration);
    KieSession session = base.newKieSession();
    List<String> audit = new ArrayList<String>();
    session.setGlobal("auditTrail", audit);
    try {
      FactType signalType = base.getFactType("advanced.sample", "RiskSignal");
      Object signal = signalType.newInstance();
      signalType.set(signal, "code", "BLOCK");
      signalType.set(signal, "score", 95);
      session.getEntryPoint("Signals").insert(signal);
      session.getAgenda().getAgendaGroup("decision").setFocus();

      assertEquals(1, session.fireAllRules());
      assertEquals(Collections.singletonList("HIGH"), audit);
      QueryResults results = session.getQueryResults("HighRisk", 90);
      assertEquals(1, results.size());
      assertEquals("BLOCK", signalType.get(signal, "code"));
      new ObjectMapper().writeValueAsString(results.toList());
    } finally {
      session.dispose();
    }
  }
}
