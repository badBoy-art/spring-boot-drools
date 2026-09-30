package com.example.drools.release;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.drools.dao.*;
import com.example.drools.entity.*;
import com.example.drools.service.DrlGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class RulePublicationIntegrationTest {
  private com.example.drools.testsupport.IsolatedTestDatabase databaseFixture;
  private RuleReleaseService releases;
  private RuleReleaseRepository repository;
  private TransactionTemplate transaction;
  private RuleDefinitionDao rules;
  private ObjectMapper mapper = new ObjectMapper();
  private RuleRuntimeRegistry registry;

  @BeforeEach
  void setup() {
    databaseFixture = new com.example.drools.testsupport.IsolatedTestDatabase();
    DriverManagerDataSource database = databaseFixture.dataSource();
    JdbcTemplate jdbc = new JdbcTemplate(database);
    jdbc.execute("CREATE TABLE rule_type_meta(rule_type VARCHAR(64) PRIMARY KEY)");
    jdbc.update("INSERT INTO rule_type_meta VALUES('A'),('B')");
    jdbc.execute(
        "CREATE TABLE rule_bundle_draft(rule_type VARCHAR(64) PRIMARY KEY,draft_version"
            + " BIGINT,config_json LONGTEXT)");
    jdbc.execute(
        "CREATE TABLE rule_release(id BIGINT AUTO_INCREMENT PRIMARY KEY,rule_type"
            + " VARCHAR(64),revision BIGINT,bundle_json LONGTEXT,content_hash CHAR(64),published_by"
            + " VARCHAR(128),remark VARCHAR(512),create_time TIMESTAMP NOT NULL DEFAULT"
            + " CURRENT_TIMESTAMP,UNIQUE(rule_type,revision))");
    jdbc.execute(
        "CREATE TABLE rule_release_head(rule_type VARCHAR(64) PRIMARY KEY,release_id"
            + " BIGINT,revision BIGINT)");
    transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
    repository = new RuleReleaseRepository(jdbc);
    RuleTypeMetaDao types = mock(RuleTypeMetaDao.class);
    rules = mock(RuleDefinitionDao.class);
    for (String type : Arrays.asList("A", "B")) {
      RuleTypeMeta meta = new RuleTypeMeta();
      meta.setRuleType(type);
      meta.setDocCode("DOC");
      when(types.findByType(type)).thenReturn(meta);
      when(rules.findByTypeAndStatus(type, 1)).thenReturn(Collections.emptyList());
    }
    RuleDocumentDao documents = mock(RuleDocumentDao.class);
    RuleOutputDao outputs = mock(RuleOutputDao.class);
    releases =
        new RuleReleaseService(
            repository, types, rules, documents, outputs, mock(DrlGenerator.class));
    registry = new RuleRuntimeRegistry(releases);
  }

  @AfterEach
  void close() {
    if (registry != null) registry.close();
    if (databaseFixture != null) databaseFixture.close();
  }

  private ObjectNode config(String name) {
    ObjectNode c = mapper.createObjectNode();
    c.putArray("resources")
        .addObject()
        .put("path", "src/main/resources/rules.drl")
        .put(
            "content",
            "package scoped; global java.util.List audit; rule \""
                + name
                + "\" when String() then audit.add(\""
                + name
                + "\"); end");
    return c;
  }

  private JsonNode publish(String type, String name, long expected, long draft) {
    transaction.execute(status -> releases.saveDraft(type, draft, config(name)));
    return transaction.execute(status -> releases.publish(type, expected, draft + 1, "test"));
  }

  private List<String> execute(RuleRuntimeRegistry.Lease lease) {
    org.kie.api.runtime.KieSession s = lease.runtime().base().newKieSession();
    try {
      List<String> audit = new ArrayList<String>();
      s.setGlobal("audit", audit);
      s.insert("x");
      s.fireAllRules();
      return audit;
    } finally {
      s.dispose();
    }
  }

  @Test
  void typesAreIsolatedAndAnInFlightExecutionKeepsItsRelease() {
    JsonNode first = publish("A", "first", 0, 0);
    publish("B", "other", 0, 0);
    try (RuleRuntimeRegistry.Lease old = registry.acquire("A")) {
      publish("A", "second", 1, 1);
      assertEquals(Collections.singletonList("first"), execute(old));
      try (RuleRuntimeRegistry.Lease latest = registry.acquire("A")) {
        assertEquals(Collections.singletonList("second"), execute(latest));
      }
      try (RuleRuntimeRegistry.Lease other = registry.acquire("B")) {
        assertEquals(Collections.singletonList("other"), execute(other));
      }
    }
  }

  @Test
  void failedPublicationRollsBackHeadAndLeavesRunningRulesIntact() {
    JsonNode first = publish("A", "first", 0, 0);
    transaction.execute(status -> releases.saveDraft("A", 1, config("second")));
    doThrow(new IllegalStateException("event table unavailable"))
        .when(rules)
        .recordPublishEvent("A", "RELEASE");
    assertThrows(
        IllegalStateException.class,
        () -> transaction.execute(status -> releases.publish("A", 1, 2, "fail")));
    assertEquals(
        first.path("releaseId").asLong(), releases.current("A").path("releaseId").asLong());
    assertEquals(1, releases.history("A").size());
    try (RuleRuntimeRegistry.Lease lease = registry.acquire("A")) {
      assertEquals(Collections.singletonList("first"), execute(lease));
    }
  }

  @Test
  void optimisticConflictsAndRollbackAreExplicit() {
    JsonNode first = publish("A", "first", 0, 0);
    publish("A", "second", 1, 1);
    assertThrows(
        ReleaseConflictException.class,
        () -> transaction.execute(status -> releases.publish("A", 1, 2, "stale")));
    assertThrows(
        ReleaseConflictException.class,
        () -> transaction.execute(status -> releases.saveDraft("A", 1, config("stale"))));
    JsonNode restored =
        transaction.execute(
            status -> releases.rollback("A", first.path("releaseId").asLong(), 2, "rollback"));
    assertEquals(3, restored.path("revision").asLong());
    assertEquals(3, releases.history("A").size());
    try (RuleRuntimeRegistry.Lease lease = registry.acquire("A")) {
      assertEquals(Collections.singletonList("first"), execute(lease));
    }
  }

  @Test
  void invalidBundleCannotChangeThePublishedHead() {
    JsonNode first = publish("A", "first", 0, 0);
    ObjectNode invalid = config("broken");
    ((ObjectNode) invalid.path("resources").get(0))
        .put("content", "rule broken when Bad syntax then end");
    transaction.execute(status -> releases.saveDraft("A", 1, invalid));
    assertThrows(
        IllegalArgumentException.class,
        () -> transaction.execute(status -> releases.publish("A", 1, 2, "invalid")));
    assertEquals(
        first.path("releaseId").asLong(), releases.current("A").path("releaseId").asLong());
  }

  @Test
  void mavenArtifactPublicationArchivesTheActualKjar() {
    org.kie.api.builder.ReleaseId id;
    try (com.example.drools.runtime.CompiledRuleBundle original =
        new com.example.drools.runtime.RuleBundleCompiler()
            .compile(
                config("archived"),
                org.kie.api.KieServices.Factory.get().newKieBaseConfiguration())) {
      id = original.container().getReleaseId();
    }
    ObjectNode artifact = mapper.createObjectNode();
    artifact
        .putObject("artifact")
        .put("groupId", id.getGroupId())
        .put("artifactId", id.getArtifactId())
        .put("version", id.getVersion());
    transaction.execute(status -> releases.saveDraft("A", 0, artifact));
    JsonNode release = transaction.execute(status -> releases.publish("A", 0, 1, "archive"));
    assertFalse(release.has("artifact"));
    assertTrue(release.hasNonNull("kjarBase64"));
    assertEquals(id.getVersion(), release.path("sourceArtifact").path("version").asText());
    try (RuleRuntimeRegistry.Lease lease = registry.acquire("A")) {
      assertEquals(Collections.singletonList("archived"), execute(lease));
    }
  }
}
