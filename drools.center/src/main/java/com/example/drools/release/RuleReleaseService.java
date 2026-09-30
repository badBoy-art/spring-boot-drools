package com.example.drools.release;

import com.example.drools.dao.*;
import com.example.drools.entity.*;
import com.example.drools.runtime.*;
import com.example.drools.service.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.kie.api.KieServices;
import org.kie.api.conf.EventProcessingOption;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A publication is a validated, immutable rule-type bundle. No in-memory mutation inside a DB
 * transaction.
 */
@Service
public class RuleReleaseService {
  private final RuleReleaseRepository repository;
  private final RuleTypeMetaDao types;
  private final RuleDefinitionDao rules;
  private final RuleDocumentDao documents;
  private final RuleOutputDao outputs;
  private final DrlGenerator generator;
  private final ObjectMapper mapper = new ObjectMapper();
  private final RuleBundleCompiler compiler = new RuleBundleCompiler();

  public RuleReleaseService(
      RuleReleaseRepository repository,
      RuleTypeMetaDao types,
      RuleDefinitionDao rules,
      RuleDocumentDao documents,
      RuleOutputDao outputs,
      DrlGenerator generator) {
    this.repository = repository;
    this.types = types;
    this.rules = rules;
    this.documents = documents;
    this.outputs = outputs;
    this.generator = generator;
  }

  @Transactional
  public JsonNode saveDraft(String type, long expectedVersion, JsonNode configuration) {
    repository.lockType(type);
    if (repository.draftVersion(type) != expectedVersion)
      throw new ReleaseConflictException("Rule resource draft changed; reload before saving");
    if (!configuration.isObject())
      throw new IllegalArgumentException("Bundle configuration must be an object");
    repository.saveDraft(type, expectedVersion + 1, configuration);
    ObjectNode result = mapper.createObjectNode();
    result.put("draftVersion", expectedVersion + 1);
    result.set("configuration", configuration);
    return result;
  }

  public JsonNode draft(String type) {
    ObjectNode result = mapper.createObjectNode();
    result.put("draftVersion", repository.draftVersion(type));
    result.put("revision", repository.revision(type));
    result.set("configuration", repository.draft(type));
    return result;
  }

  @Transactional
  public JsonNode publish(
      String type, long expectedRevision, long expectedDraftVersion, String remark) {
    repository.lockType(type);
    checkRevision(type, expectedRevision);
    if (repository.draftVersion(type) != expectedDraftVersion)
      throw new ReleaseConflictException("Resource draft changed; reload before publishing");
    return append(type, build(type), remark);
  }

  /** For the existing operations form, invoked in the same transaction as its rule changes. */
  @Transactional
  public JsonNode publishCurrent(String type) {
    repository.lockType(type);
    return append(type, build(type), "Operational rule publication");
  }

  @Transactional
  public JsonNode rollback(String type, long releaseId, long expectedRevision, String remark) {
    repository.lockType(type);
    checkRevision(type, expectedRevision);
    ObjectNode bundle = (ObjectNode) repository.release(type, releaseId).deepCopy();
    bundle.remove(Arrays.asList("releaseId", "revision", "contentHash"));
    bundle.put("rollbackOf", releaseId);
    return append(type, bundle, remark);
  }

  public void lockType(String type) {
    repository.lockType(type);
  }

  public JsonNode current(String type) {
    return repository.current(type);
  }

  public List<Map<String, Object>> history(String type) {
    return repository.history(type);
  }

  private void checkRevision(String type, long expected) {
    if (repository.revision(type) != expected)
      throw new ReleaseConflictException("Published version changed; reload before publishing");
  }

  private ObjectNode build(String type) {
    RuleTypeMeta meta = types.findByType(type);
    if (meta == null) throw new IllegalArgumentException("Unknown rule type: " + type);
    ObjectNode bundle = (ObjectNode) repository.draft(type).deepCopy();
    bundle.put("schemaVersion", 1);
    bundle.put("ruleType", type);
    bundle.put("docCode", meta.getDocCode());
    bundle.set("fields", mapper.valueToTree(meta.getFields()));
    bundle.set("outputFields", mapper.valueToTree(outputs.findByType(type)));
    if (meta.getDocCode() != null) {
      bundle.set("documentFields", mapper.valueToTree(documents.findFields(meta.getDocCode())));
      bundle.set("documentObjects", mapper.valueToTree(documents.findObjects(meta.getDocCode())));
    }
    ArrayNode drls = mapper.createArrayNode();
    if (!bundle.hasNonNull("artifact") && !bundle.hasNonNull("kjarBase64")) {
      for (RuleDefinition rule : rules.findByTypeAndStatus(type, 1)) {
        ObjectNode item = drls.addObject();
        item.put("id", rule.getId());
        item.put("name", rule.getRuleName());
        item.put("version", rule.getVersion());
        String source = rule.getDrlContent();
        if (source == null || source.trim().isEmpty()) source = generator.generate(rule);
        item.put("drl", RuleDefinitionService.stripNativeMarker(source));
      }
    }
    bundle.set("rules", drls);
    return bundle;
  }

  private JsonNode append(String type, ObjectNode bundle, String remark) {
    org.kie.api.KieBaseConfiguration configuration =
        KieServices.Factory.get().newKieBaseConfiguration();
    configuration.setOption(
        EventProcessingOption.valueOf(
            bundle
                .path("eventProcessingMode")
                .asText("stream")
                .toUpperCase(java.util.Locale.ROOT)));
    try (CompiledRuleBundle checked = compiler.compile(bundle, configuration)) {
      // Freeze the actual imported Maven artifact, so a later SNAPSHOT cannot silently change this
      // release.
      if (bundle.hasNonNull("artifact")) {
        org.drools.compiler.kie.builder.impl.InternalKieModule module =
            (org.drools.compiler.kie.builder.impl.InternalKieModule)
                KieServices.Factory.get()
                    .getRepository()
                    .getKieModule(checked.container().getReleaseId());
        bundle.set("sourceArtifact", bundle.get("artifact"));
        bundle.remove("artifact");
        bundle.put("kjarBase64", Base64.getEncoder().encodeToString(module.getBytes()));
        ArrayNode dependencies = mapper.createArrayNode();
        freezeDependencies(module, dependencies, new HashSet<String>());
        bundle.set("dependencyKjars", dependencies);
      }
    }
    String actor =
        SecurityContextHolder.getContext().getAuthentication() == null
            ? "system"
            : SecurityContextHolder.getContext().getAuthentication().getName();
    JsonNode released = repository.append(type, bundle, hash(bundle.toString()), actor, remark);
    rules.recordPublishEvent(type, "RELEASE");
    return released;
  }

  private void freezeDependencies(
      org.drools.compiler.kie.builder.impl.InternalKieModule module,
      ArrayNode result,
      Set<String> seen) {
    for (org.drools.compiler.kie.builder.impl.InternalKieModule dependency :
        module.getKieDependencies().values()) {
      if (seen.add(dependency.getReleaseId().toString())) {
        freezeDependencies(dependency, result, seen);
        result.add(Base64.getEncoder().encodeToString(dependency.getBytes()));
      }
    }
  }

  private String hash(String json) {
    try {
      StringBuilder result = new StringBuilder();
      for (byte b :
          MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)))
        result.append(String.format("%02x", b & 255));
      return result.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
