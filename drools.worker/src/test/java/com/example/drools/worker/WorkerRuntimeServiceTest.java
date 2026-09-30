package com.example.drools.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.drools.sdk.document.DroolsRuleCenterSdk;
import com.example.drools.sdk.document.JdbcKieSessionCoordinator;
import com.example.drools.sdk.document.JdbcKieSessionStore;
import com.example.drools.sdk.document.KieSessionCoordinator;
import com.example.drools.sdk.document.KieSessionStore;
import com.example.drools.sdk.document.feign.RuleTypeConfigFeignClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public class WorkerRuntimeServiceTest {
  private com.example.drools.testsupport.IsolatedTestDatabase databaseFixture;

  @org.junit.jupiter.api.AfterEach
  void cleanupDatabase() {
    if (databaseFixture != null) databaseFixture.close();
  }

  @Test
  public void httpRuntimeCommandServiceCreatesTypedSessionAndRecoversCheckpoint() throws Exception {
    databaseFixture = new com.example.drools.testsupport.IsolatedTestDatabase();
    DriverManagerDataSource database = databaseFixture.dataSource();
    JdbcTemplate jdbc = new JdbcTemplate(database);
    jdbc.execute(
        "CREATE TABLE kie_session_snapshot(session_id VARCHAR(64) PRIMARY KEY, rule_type"
            + " VARCHAR(64), rule_fingerprint CHAR(64), rule_bundle LONGTEXT, owner_id"
            + " VARCHAR(128), fencing_token BIGINT DEFAULT 0, session_payload LONGBLOB, create_time"
            + " TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT"
            + " CURRENT_TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE kie_session_lease(session_id VARCHAR(64) PRIMARY KEY, owner_id VARCHAR(128),"
            + " lease_until TIMESTAMP(6) NOT NULL, fencing_token BIGINT DEFAULT 0, create_time"
            + " TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT"
            + " CURRENT_TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE kie_worker_node(node_id VARCHAR(128) PRIMARY KEY, base_url VARCHAR(512),"
            + " heartbeat_time TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE kie_worker_session(session_id VARCHAR(64) PRIMARY KEY, rule_type VARCHAR(64),"
            + " clock_type VARCHAR(16), active_mode TINYINT, timed_auto TINYINT, status"
            + " VARCHAR(16), create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP"
            + " DEFAULT CURRENT_TIMESTAMP)");

    jdbc.execute(
        "CREATE TABLE kie_worker_global(session_id VARCHAR(64),global_name VARCHAR(128),value_json"
            + " LONGTEXT,PRIMARY KEY(session_id,global_name))");
    jdbc.execute(
        "CREATE TABLE kie_worker_command(session_id VARCHAR(64),command_id"
            + " VARCHAR(128),request_hash CHAR(64),response_json LONGTEXT,PRIMARY"
            + " KEY(session_id,command_id))");
    ObjectMapper mapper = new ObjectMapper();
    String drl =
        "package worker.test;\n"
            + "global java.lang.Boolean enabled; global java.util.List audit;\n"
            + "declare WorkerTestFact value : int end\n"
            + "rule \"entrypoint\" when WorkerTestFact() from entry-point \"Signals\" then end\n"
            + "rule \"increment-once\" when $f : WorkerTestFact(value == 3)"
            + " eval(Boolean.TRUE.equals(enabled)) then $f.setValue(4); update($f);"
            + " audit.add(\"fired\"); end\n";
    JsonNode bundle =
        mapper.readTree(
            "{\"ruleType\":\"WORKER_TEST\",\"rules\":[{\"name\":\"worker\",\"drl\":"
                + mapper.writeValueAsString(drl)
                + "}]}");
    RuleTypeConfigFeignClient client = ruleType -> bundle;
    KieSessionStore store = new JdbcKieSessionStore(database);
    KieSessionCoordinator coordinator = new JdbcKieSessionCoordinator(database);
    WorkerStateRepository repository = new WorkerStateRepository(jdbc);
    DroolsRuleCenterSdk firstSdk = newSdk(client, store, coordinator);
    WorkerRuntimeService first = service(firstSdk, repository, mapper);
    String sessionId = String.valueOf(first.createSession(request("WORKER_TEST")).get("sessionId"));
    Map<String, Object> global = new LinkedHashMap<>();
    global.put("name", "enabled");
    global.put("value", true);
    first.setGlobal(sessionId, global);
    Map<String, Object> auditGlobal = new LinkedHashMap<>();
    auditGlobal.put("name", "audit");
    auditGlobal.put("value", new java.util.ArrayList<String>());
    first.setGlobal(sessionId, auditGlobal);
    Map<String, Object> fact = new LinkedHashMap<String, Object>();
    fact.put("value", 3);
    Map<String, Object> insert = new LinkedHashMap<String, Object>();
    insert.put("type", "worker.test.WorkerTestFact");
    insert.put("fact", fact);
    Map<String, Object> inserted =
        first.idempotent(
            sessionId, "insert-once", "insert", insert, () -> first.insert(sessionId, insert));
    assertTrue(inserted.containsKey("factHandle"));
    assertEquals(
        inserted,
        first.idempotent(
            sessionId, "insert-once", "insert", insert, () -> first.insert(sessionId, insert)));
    Map<String, Object> event = new LinkedHashMap<String, Object>(insert);
    event.put("entryPoint", "Signals");
    String eventHandle = String.valueOf(first.insert(sessionId, event).get("factHandle"));
    assertEquals(2, ((java.util.List<?>) first.objects(sessionId).get("facts")).size());
    Map<String, Object> change = new LinkedHashMap<String, Object>(event);
    change.put("factHandle", eventHandle);
    first.update(sessionId, change);
    first.retract(sessionId, eventHandle);
    assertEquals(1, ((java.util.List<?>) first.objects(sessionId).get("facts")).size());
    first.checkpoint(sessionId);

    repository.heartbeat("worker-a", "http://worker-a:8090");
    DroolsRuleCenterSdk competingSdk = newSdk(client, store, coordinator, "worker-b");
    WorkerRuntimeService competingWorker =
        new WorkerRuntimeService(
            competingSdk, repository, mapper, "worker-b", "http://worker-b:8090", "", 60000L);
    WorkerNotOwnerException notOwner =
        assertThrows(
            WorkerNotOwnerException.class,
            () -> competingWorker.fire(sessionId, Collections.<String, Object>emptyMap()));
    assertEquals("worker-a", notOwner.getOwnerNode());
    assertEquals("http://worker-a:8090", notOwner.getOwnerUrl());
    competingSdk.close();

    firstSdk.close(); // simulate owner process exit; worker session catalog deliberately stays OPEN

    DroolsRuleCenterSdk restartedSdk = newSdk(client, store, coordinator);
    WorkerRuntimeService restarted = service(restartedSdk, repository, mapper);
    restarted.maintainSessions();
    assertEquals(
        1,
        ((Integer) restarted.fire(sessionId, Collections.<String, Object>emptyMap()).get("fired"))
            .intValue());
    restartedSdk.close();
    DroolsRuleCenterSdk afterFireSdk = newSdk(client, store, coordinator);
    try {
      WorkerRuntimeService afterFire = service(afterFireSdk, repository, mapper);
      afterFire.maintainSessions();
      assertEquals(
          Collections.singletonList("fired"),
          afterFireSdk.session(sessionId).kieSession().getGlobal("audit"));
    } finally {
      afterFireSdk.close();
    }
  }

  private WorkerRuntimeService service(
      DroolsRuleCenterSdk sdk, WorkerStateRepository repository, ObjectMapper mapper) {
    return new WorkerRuntimeService(
        sdk, repository, mapper, "worker-a", "http://worker-a:8090", "", 60000L);
  }

  private DroolsRuleCenterSdk newSdk(
      RuleTypeConfigFeignClient client, KieSessionStore store, KieSessionCoordinator coordinator) {
    return newSdk(client, store, coordinator, "worker-a");
  }

  private DroolsRuleCenterSdk newSdk(
      RuleTypeConfigFeignClient client,
      KieSessionStore store,
      KieSessionCoordinator coordinator,
      String nodeId) {
    return new DroolsRuleCenterSdk(
        client, store, null, coordinator, nodeId, 30000L, 1024L * 1024L, null);
  }

  private Map<String, Object> request(String type) {
    Map<String, Object> request = new LinkedHashMap<String, Object>();
    request.put("ruleType", type);
    request.put("clockType", "realtime");
    return request;
  }
}
