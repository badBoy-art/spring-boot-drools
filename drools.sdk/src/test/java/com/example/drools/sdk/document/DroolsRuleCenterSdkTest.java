package com.example.drools.sdk.document;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.drools.sdk.document.feign.RuleTypeConfigFeignClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.conf.TimedRuleExecutionOption;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.api.time.Calendar;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

public class DroolsRuleCenterSdkTest {
  @Test
  public void runtimeOptionsExposeEventModeTimerCalendarsAndNativeSessionProperties()
      throws Exception {
    String drl =
        "package sdk.options;\n"
            + "global java.util.List audit;\n"
            + "rule \"windowed\" timer (int: 100ms 100ms; start=1000, end=1200, repeat-limit=2)"
            + " calendars \"never\" when String(this == \"start\") then audit.add(\"fired\");"
            + " end\n";
    DroolsRuleCenterSdk sdk = sdkFor("runtime-options", drl);
    try {
      RuleRuntimeOptions options =
          RuleRuntimeOptions.builder()
              .eventProcessingMode(org.kie.api.conf.EventProcessingOption.CLOUD)
              .sessionProperty("drools.clockType", "pseudo")
              .sessionOption(TimedRuleExecutionOption.YES)
              .calendar(
                  "never",
                  new Calendar() {
                    @Override
                    public boolean isTimeIncluded(long timestamp) {
                      return false;
                    }
                  })
              .build();
      sdk.refresh("runtime-options", options);
      String id = sdk.newSession("runtime-options", options);
      KieSession session = sdk.session(id).kieSession();
      List<String> audit = new CopyOnWriteArrayList<String>();
      session.setGlobal("audit", audit);
      session.insert("start");
      session.fireAllRules();
      ((org.kie.api.time.SessionPseudoClock) session.getSessionClock())
          .advanceTime(2, TimeUnit.SECONDS);
      session.fireAllRules();
      assertTrue("calendar must be registered on the native session", audit.isEmpty());
      assertTrue("CLOUD KieBase should create a working session", session.getKieBase() != null);
      sdk.closeSession(id);
    } finally {
      sdk.close();
    }
  }

  @Test
  public void jdbcSnapshotCanBeRestoredAfterSdkRestart() throws Exception {
    org.springframework.jdbc.datasource.embedded.EmbeddedDatabase database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .addScript("classpath:db/kie-session-store-test.sql")
            .build();
    String drl =
        "package sdk.restore;\n"
            + "global java.util.List audit;\n"
            + "rule \"restore\" timer (int: 1s; repeat-limit=1) when String(this =="
            + " \"persisted-fact\") then audit.add(\"restored\"); end\n";
    final AtomicReference<JsonNode> config =
        new AtomicReference<JsonNode>(runtimeBundle("restore-test", drl));
    RuleTypeConfigFeignClient feign = type -> config.get();
    JdbcKieSessionStore store = new JdbcKieSessionStore(database);
    RuleRuntimeOptions options =
        RuleRuntimeOptions.builder().sessionProperty("drools.clockType", "pseudo").build();
    String sessionId;
    DroolsRuleCenterSdk first = new DroolsRuleCenterSdk(feign, store);
    first.refresh("restore-test", options);
    sessionId = first.newSession("restore-test", options);
    KieSession original = first.session(sessionId).kieSession();
    original.insert("persisted-fact");
    original.fireAllRules();
    first.checkpointSession(sessionId);
    first.close();

    // The current publication may advance while an older long-running session still exists.
    config.set(
        runtimeBundle(
            "restore-test", "package sdk.restore; rule \"new-version\" when String() then end\n"));
    final List<String> restoredGlobals = new CopyOnWriteArrayList<String>();
    DroolsRuleCenterSdk restarted =
        new DroolsRuleCenterSdk(
            feign,
            store,
            new KieSessionLifecycle() {
              @Override
              public void onCreate(String id, String type, KieSession session) {
                session.setGlobal("audit", restoredGlobals);
              }

              @Override
              public void onRestore(String id, String type, KieSession session) {
                session.setGlobal("audit", restoredGlobals);
              }
            });
    try {
      restarted.restoreSession(sessionId, "restore-test", options);
      KieSession restored = restarted.session(sessionId).kieSession();
      ((org.kie.api.time.SessionPseudoClock) restored.getSessionClock())
          .advanceTime(2, TimeUnit.SECONDS);
      assertEquals(1, restored.fireAllRules());
      assertEquals("restored", restoredGlobals.get(0));
      restarted.deletePersistedSession(sessionId);
      assertEquals(null, store.load(sessionId));
    } finally {
      restarted.close();
      database.shutdown();
    }
  }

  @Test
  public void jdbcLeaseAllowsSingleOwnerAndFencesAStaleNodeAfterExpiry() throws Exception {
    org.springframework.jdbc.datasource.embedded.EmbeddedDatabase database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .addScript("classpath:db/kie-session-store-test.sql")
            .build();
    try {
      JdbcKieSessionCoordinator coordinator = new JdbcKieSessionCoordinator(database);
      JdbcKieSessionStore store = new JdbcKieSessionStore(database);
      KieSessionLease oldLease = coordinator.acquire("shared-session", "node-a", 250L);
      assertNotNull(oldLease);
      assertEquals("node-a", coordinator.ownerOf("shared-session"));
      assertNull(
          "a second node must not acquire a live session",
          coordinator.acquire("shared-session", "node-b", 250L));

      store.save(
          new StoredKieSession(
              "shared-session",
              "test",
              "fingerprint",
              "{}",
              new byte[] {1},
              "node-a",
              oldLease.getFencingToken()));
      Thread.sleep(400L); // simulate node-a process loss: its lease renewer no longer runs
      KieSessionLease newLease = coordinator.acquire("shared-session", "node-b", 1000L);
      assertNotNull(newLease);
      assertTrue(
          "takeover must advance the fencing epoch",
          newLease.getFencingToken() > oldLease.getFencingToken());
      assertEquals("node-b", coordinator.ownerOf("shared-session"));
      assertFalse(
          "the stale owner cannot renew after takeover", coordinator.renew(oldLease, 1000L));

      store.save(
          new StoredKieSession(
              "shared-session",
              "test",
              "fingerprint",
              "{}",
              new byte[] {2},
              "node-b",
              newLease.getFencingToken()));
      try {
        store.save(
            new StoredKieSession(
                "shared-session",
                "test",
                "stale",
                "{}",
                new byte[] {3},
                "node-a",
                oldLease.getFencingToken()));
        throw new AssertionError("stale owner checkpoint must be rejected");
      } catch (IllegalStateException expected) {
      }
      assertEquals(2, store.load("shared-session").getPayload()[0]);
      assertTrue(coordinator.release(newLease));
      assertNull(coordinator.ownerOf("shared-session"));
    } finally {
      database.shutdown();
    }
  }

  @Test
  public void sdkRoutesRestoredSessionToItsExclusiveOwner() throws Exception {
    org.springframework.jdbc.datasource.embedded.EmbeddedDatabase database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .addScript("classpath:db/kie-session-store-test.sql")
            .build();
    String drl = "package sdk.owner; rule \"owner\" when String(this == \"x\") then end\n";
    JsonNode config = runtimeBundle("owner-test", drl);
    RuleTypeConfigFeignClient feign = type -> config;
    JdbcKieSessionStore store = new JdbcKieSessionStore(database);
    JdbcKieSessionCoordinator coordinator = new JdbcKieSessionCoordinator(database);
    final List<String> observations = new CopyOnWriteArrayList<String>();
    KieSessionSnapshotObserver observer =
        new KieSessionSnapshotObserver() {
          @Override
          public void onCheckpoint(String id, long bytes, long elapsed) {
            observations.add("checkpoint:" + bytes);
          }

          @Override
          public void onRestore(String id, long bytes, long elapsed) {
            observations.add("restore:" + bytes);
          }
        };
    DroolsRuleCenterSdk nodeA =
        new DroolsRuleCenterSdk(
            feign, store, null, coordinator, "node-a", 3000L, 1024 * 1024, observer);
    DroolsRuleCenterSdk nodeB =
        new DroolsRuleCenterSdk(
            feign, store, null, coordinator, "node-b", 3000L, 1024 * 1024, observer);
    String sessionId = null;
    try {
      nodeA.refresh("owner-test");
      sessionId = nodeA.newSession("owner-test");
      nodeA.checkpointSession(sessionId);
      assertEquals("node-a", nodeB.sessionOwner(sessionId));
      try {
        nodeB.restoreSession(sessionId, "owner-test", RuleRuntimeOptions.defaults());
        throw new AssertionError("a second node must not restore a live owner's session");
      } catch (IllegalStateException expected) {
      }

      nodeA.closeSession(sessionId);
      nodeB.restoreSession(sessionId, "owner-test", RuleRuntimeOptions.defaults());
      assertEquals("node-b", nodeB.sessionOwner(sessionId));
      assertNotNull(nodeB.session(sessionId).kieSession());
      assertTrue(observations.get(0).startsWith("checkpoint:"));
      assertTrue(observations.get(1).startsWith("restore:"));
    } finally {
      nodeA.close();
      nodeB.close();
      database.shutdown();
    }
  }

  @Test
  public void timerStartEndCalendarAndPassiveAutoExecutionAreAcceptedByDrools() throws Exception {
    String drl =
        "package sdk.timers;\n"
            + "global java.util.List audit;\n"
            + "rule \"bounded\" timer (int: 100ms 100ms; start=1000, end=1200, repeat-limit=2)"
            + " calendars \"all\" when String(this == \"start\") then audit.add(\"tick\"); end\n";
    DroolsRuleCenterSdk sdk = sdkFor("bounded-timer", drl);
    try {
      RuleRuntimeOptions options =
          RuleRuntimeOptions.builder()
              .sessionProperty("drools.clockType", "pseudo")
              .sessionOption(TimedRuleExecutionOption.YES)
              .calendar(
                  "all",
                  new Calendar() {
                    @Override
                    public boolean isTimeIncluded(long timestamp) {
                      return true;
                    }
                  })
              .build();
      sdk.refresh("bounded-timer", options);
      String id = sdk.newSession("bounded-timer", options);
      KieSession session = sdk.session(id).kieSession();
      List<String> audit = new CopyOnWriteArrayList<String>();
      session.setGlobal("audit", audit);
      session.insert("start");
      org.kie.api.time.SessionPseudoClock clock =
          (org.kie.api.time.SessionPseudoClock) session.getSessionClock();
      clock.advanceTime(900, TimeUnit.MILLISECONDS);
      session.fireAllRules();
      assertTrue("start time must defer timer", audit.isEmpty());
      clock.advanceTime(500, TimeUnit.MILLISECONDS);
      session.fireAllRules();
      assertTrue("bounded timer should fire after its start", audit.size() > 0);
      assertTrue("end/repeat-limit must bound timer activations", audit.size() <= 2);
      sdk.closeSession(id);
    } finally {
      sdk.close();
    }
  }

  @Test
  public void timedRuleExecutionOptionFiresPassiveTimerWithoutManualFireCall() throws Exception {
    String drl =
        "package sdk.passiveauto;\n"
            + "global java.util.List audit;\n"
            + "rule \"automatic\" timer (int: 100ms; repeat-limit=1) when String(this == \"start\")"
            + " then audit.add(\"automatic\"); end\n";
    DroolsRuleCenterSdk sdk = sdkFor("passive-auto", drl);
    try {
      RuleRuntimeOptions options =
          RuleRuntimeOptions.builder().sessionOption(TimedRuleExecutionOption.YES).build();
      sdk.refresh("passive-auto", options);
      String id = sdk.newSession("passive-auto", options);
      KieSession session = sdk.session(id).kieSession();
      List<String> audit = new CopyOnWriteArrayList<String>();
      session.setGlobal("audit", audit);
      session.insert("start");
      session.fireAllRules(); // establishes the timer activation; no further polling is done
      assertTrue(
          "TimedRuleExecutionOption.YES should run passive timer without fireAllRules",
          awaitSize(audit, 1));
      sdk.closeSession(id);
    } finally {
      sdk.close();
    }
  }

  @Test
  public void sdkKeepsStateAcrossBusinessRequestsAndRunsFireUntilHalt() throws Exception {
    String drl =
        "package sdk.sample;\n"
            + "global java.util.List audit;\n"
            + "rule \"first\" when String(this == \"first-request\") then audit.add(\"first\");"
            + " end\n"
            + "rule \"second\" when String(this == \"second-request\") then audit.add(\"second\");"
            + " end\n"
            + "declare Pulse\n"
            + " @role(event)\n"
            + " @timestamp(ts)\n"
            + " ts: long\n"
            + "end\n"
            + "rule \"delayed\" timer (int: 1s) when Pulse() then audit.add(\"timer\"); end\n";
    final String bundle =
        "{\"ruleType\":\"sdk-test\",\"docCode\":\"TEST\",\"fields\":[],\"outputFields\":[],"
            + "\"rules\":[{\"id\":1,\"name\":\"test\",\"version\":1,\"drl\":"
            + quote(drl)
            + "}]}";
    final ObjectMapper mapper = new ObjectMapper();
    final JsonNode runtimeConfig = mapper.readTree(bundle);
    RuleTypeConfigFeignClient feign = ruleType -> runtimeConfig;
    DroolsRuleCenterSdk sdk = new DroolsRuleCenterSdk(feign);
    try {
      sdk.refresh("sdk-test");
      String id = sdk.newSession("sdk-test");
      KieSession session = sdk.session(id).kieSession();
      List<String> audit = new CopyOnWriteArrayList<String>();
      session.setGlobal("audit", audit);
      sdk.startFireUntilHalt(id);

      // Simulate two independent business HTTP requests against the same retained session.
      session.insert("first-request");
      assertTrue(awaitSize(audit, 1));
      session.insert("second-request");
      assertTrue(awaitSize(audit, 2));
      assertEquals("first", audit.get(0));
      assertEquals("second", audit.get(1));
      sdk.closeSession(id);

      String pseudoId = sdk.newSession("sdk-test", "pseudo");
      KieSession pseudo = sdk.session(pseudoId).kieSession();
      List<String> timed = new CopyOnWriteArrayList<String>();
      pseudo.setGlobal("audit", timed);
      sdk.startFireUntilHalt(pseudoId);
      org.kie.api.definition.type.FactType pulseType =
          pseudo.getKieBase().getFactType("sdk.sample", "Pulse");
      Object pulse = pulseType.newInstance();
      pulseType.set(pulse, "ts", 0L);
      pseudo.insert(pulse);
      org.kie.api.time.SessionPseudoClock clock =
          (org.kie.api.time.SessionPseudoClock) pseudo.getSessionClock();
      long timerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (timed.isEmpty() && System.nanoTime() < timerDeadline) {
        clock.advanceTime(100, TimeUnit.MILLISECONDS);
        Thread.sleep(10); // allow the active agenda to register and execute scheduled work
      }
      assertTrue("pseudo-clock timer should fire", awaitSize(timed, 1));
      assertEquals("timer", timed.get(0));
      sdk.closeSession(pseudoId);
    } finally {
      sdk.close();
    }
  }

  @Test
  public void pseudoClockSupportsIntervalCronAndExpressionTimers() throws Exception {
    String drl =
        "package sdk.timer;\n"
            + "global java.util.List audit;\n"
            + "declare Pulse\n"
            + " @role(event)\n"
            + " @timestamp(ts)\n"
            + " ts: long\n"
            + " delay: String\n"
            + " period: long\n"
            + "end\n"
            + "rule \"interval\" timer (int: 0ms 100ms; repeat-limit=2) when Pulse() then"
            + " audit.add(\"interval\"); end\n"
            + "rule \"expression\" timer (expr: $delay, $period; repeat-limit=2) when Pulse($delay:"
            + " delay, $period: period) then audit.add(\"expression\"); end\n"
            + "rule \"cron\" timer (cron: * * * * * ?; repeat-limit=1) when Pulse() then"
            + " audit.add(\"cron\"); end\n";
    DroolsRuleCenterSdk sdk = sdkFor("timer-matrix", drl);
    try {
      sdk.refresh("timer-matrix");
      String id = sdk.newSession("timer-matrix", "pseudo");
      KieSession session = sdk.session(id).kieSession();
      List<String> audit = new CopyOnWriteArrayList<String>();
      session.setGlobal("audit", audit);
      sdk.startFireUntilHalt(id);
      org.kie.api.definition.type.FactType pulseType =
          session.getKieBase().getFactType("sdk.timer", "Pulse");
      Object pulse = pulseType.newInstance();
      pulseType.set(pulse, "ts", 0L);
      pulseType.set(pulse, "delay", "100ms");
      pulseType.set(pulse, "period", 100L);
      session.insert(pulse);

      org.kie.api.time.SessionPseudoClock clock =
          (org.kie.api.time.SessionPseudoClock) session.getSessionClock();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
      while ((!contains(audit, "interval")
              || !contains(audit, "expression")
              || !contains(audit, "cron"))
          && System.nanoTime() < deadline) {
        clock.advanceTime(100, TimeUnit.MILLISECONDS);
        Thread.sleep(10);
      }
      assertTrue("interval timer did not fire", contains(audit, "interval"));
      assertTrue("expression timer did not fire", contains(audit, "expression"));
      assertTrue("cron timer did not fire", contains(audit, "cron"));
      assertTrue("repeat-limit must bound interval timer", count(audit, "interval") <= 3);
      assertTrue("repeat-limit must bound expression timer", count(audit, "expression") <= 3);
      assertEquals(1, count(audit, "cron"));
    } finally {
      sdk.close();
    }
  }

  @Test
  public void realtimeTimerRunsInActiveMode() throws Exception {
    String drl =
        "package sdk.realtime;\n"
            + "global java.util.List audit;\n"
            + "rule \"realtime\" timer (int: 100ms; repeat-limit=1) when String(this == \"start\")"
            + " then audit.add(\"fired\"); end\n";
    DroolsRuleCenterSdk sdk = sdkFor("realtime-timer", drl);
    try {
      sdk.refresh("realtime-timer");
      String id = sdk.newSession("realtime-timer", "realtime");
      KieSession session = sdk.session(id).kieSession();
      List<String> audit = new CopyOnWriteArrayList<String>();
      session.setGlobal("audit", audit);
      sdk.startFireUntilHalt(id);
      session.insert("start");
      assertTrue("realtime timer did not fire", awaitSize(audit, 1));
      assertEquals("fired", audit.get(0));
    } finally {
      sdk.close();
    }
  }

  @Test
  public void passiveTimerFiresWhenFireAllRulesIsCalledAgainAfterClockAdvance() throws Exception {
    String drl =
        "package sdk.passive;\n"
            + "global java.util.List audit;\n"
            + "rule \"passive\" timer (int: 1s; repeat-limit=1) when String(this == \"start\") then"
            + " audit.add(\"fired\"); end\n";
    DroolsRuleCenterSdk sdk = sdkFor("passive-timer", drl);
    try {
      sdk.refresh("passive-timer");
      String id = sdk.newSession("passive-timer", "pseudo");
      KieSession session = sdk.session(id).kieSession();
      List<String> audit = new CopyOnWriteArrayList<String>();
      session.setGlobal("audit", audit);
      session.insert("start");
      session.fireAllRules(); // passive mode schedules the timer
      ((org.kie.api.time.SessionPseudoClock) session.getSessionClock())
          .advanceTime(1, TimeUnit.SECONDS);
      session.fireAllRules(); // passive timed consequence is evaluated on a subsequent call
      assertEquals(1, audit.size());
      assertEquals("fired", audit.get(0));
    } finally {
      sdk.close();
    }
  }

  @Test
  public void timerActivationStopsWhenItsMatchingFactIsRetracted() throws Exception {
    String drl =
        "package sdk.cancel;\n"
            + "global java.util.List audit;\n"
            + "rule \"cancelled\" timer (int: 500ms; repeat-limit=1) when String(this == \"start\")"
            + " then audit.add(\"fired\"); end\n";
    DroolsRuleCenterSdk sdk = sdkFor("cancel-timer", drl);
    try {
      sdk.refresh("cancel-timer");
      String id = sdk.newSession("cancel-timer", "pseudo");
      KieSession session = sdk.session(id).kieSession();
      List<String> audit = new CopyOnWriteArrayList<String>();
      session.setGlobal("audit", audit);
      sdk.startFireUntilHalt(id);
      FactHandle handle = session.insert("start");
      Thread.sleep(100); // allow the active agenda to register its timer
      session.delete(handle);
      ((org.kie.api.time.SessionPseudoClock) session.getSessionClock())
          .advanceTime(1, TimeUnit.SECONDS);
      Thread.sleep(100);
      assertFalse(
          "timer should be cancelled after its condition stops matching", audit.contains("fired"));
    } finally {
      sdk.close();
    }
  }

  private DroolsRuleCenterSdk sdkFor(final String ruleType, String drl) throws Exception {
    final JsonNode runtimeConfig = runtimeBundle(ruleType, drl);
    RuleTypeConfigFeignClient feign = requestedType -> runtimeConfig;
    return new DroolsRuleCenterSdk(feign);
  }

  private JsonNode runtimeBundle(String ruleType, String drl) throws Exception {
    String json =
        "{\"ruleType\":\""
            + ruleType
            + "\",\"rules\":[{\"name\":\"test\",\"drl\":"
            + quote(drl)
            + "}]}";
    return new ObjectMapper().readTree(json);
  }

  private boolean contains(List<String> values, String value) {
    return count(values, value) > 0;
  }

  private int count(List<String> values, String value) {
    int total = 0;
    for (String current : values) if (value.equals(current)) total++;
    return total;
  }

  private boolean awaitSize(List<?> values, int size) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      synchronized (values) {
        if (values.size() >= size) return true;
      }
      Thread.sleep(10);
    }
    return false;
  }

  private String quote(String value) throws Exception {
    return new ObjectMapper().writeValueAsString(value);
  }

  @Test
  public void documentFactsCanCheckpointAndRestoreAndTypedOptionValuesAreChecked()
      throws Exception {
    org.springframework.jdbc.datasource.embedded.EmbeddedDatabase database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .addScript("classpath:db/kie-session-store-test.sql")
            .build();
    JsonNode bundle =
        runtimeBundle(
            "DOC",
            "package sdk.doc; import com.example.drools.domain.DocFact; rule r when DocFact() then"
                + " end");
    JdbcKieSessionStore store = new JdbcKieSessionStore(database);
    DroolsRuleCenterSdk original = new DroolsRuleCenterSdk(type -> bundle, store);
    String id = original.newSession("DOC");
    com.example.drools.domain.DocFact fact = new com.example.drools.domain.DocFact();
    com.example.drools.domain.DocItem item = new com.example.drools.domain.DocItem();
    item.getData().put("price", 7);
    fact.getLineItemsByPath().put("items", java.util.Collections.singletonList(item));
    original.session(id).kieSession().insert(fact);
    original.session(id).kieSession().insert(item);
    original.checkpointSession(id);
    original.close();
    try (DroolsRuleCenterSdk restored = new DroolsRuleCenterSdk(type -> bundle, store)) {
      restored.restoreSession(id, "DOC", RuleRuntimeOptions.defaults());
      assertEquals(2, restored.session(id).kieSession().getObjects().size());
    } finally {
      database.shutdown();
    }
    try (DroolsRuleCenterSdk sdk = new DroolsRuleCenterSdk(type -> bundle)) {
      sdk.refresh(
          "DOC",
          RuleRuntimeOptions.builder()
              .baseOption(org.kie.api.conf.EqualityBehaviorOption.IDENTITY)
              .build());
      try {
        sdk.newSession(
            "DOC",
            RuleRuntimeOptions.builder()
                .baseOption(org.kie.api.conf.EqualityBehaviorOption.EQUALITY)
                .build());
        org.junit.Assert.fail("Different option values must not share a fingerprint");
      } catch (IllegalStateException expected) {
        assertTrue(expected.getMessage().contains("different runtime options"));
      }
    }
  }
}
