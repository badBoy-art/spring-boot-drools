package com.example.drools.runtime;

import com.example.drools.domain.*;
import com.example.drools.entity.*;
import com.example.drools.runtime.document.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.kie.api.runtime.KieSession;

/** Executes the schema archived with a release, with no dependency on a live authoring database. */
public final class FrozenDocumentRuntime {
  private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private final DocumentFactBuilder builder = new DocumentFactBuilder();
  private final DocumentOutputAssembler assembler = new DocumentOutputAssembler();

  public DocFact insert(JsonNode bundle, Map<String, Object> body, KieSession session) {
    String doc = bundle.path("docCode").asText();
    if (doc.isEmpty()) throw new IllegalArgumentException("Rule type has no registered document");
    Map<String, Object> copied =
        mapper.convertValue(body == null ? Collections.emptyMap() : body, Map.class);
    DocFact fact =
        builder.build(doc, copied, list(bundle.path("documentFields"), RuleDocumentField.class));
    if (!fact.getMessages().isEmpty())
      throw new IllegalArgumentException(String.join("; ", fact.getMessages()));
    session.insert(fact);
    for (DocItem item :
        builder.buildItems(
            doc, fact, list(bundle.path("documentObjects"), RuleDocumentObject.class)))
      session.insert(item);
    return fact;
  }

  public Map<String, Object> result(JsonNode bundle, DocFact fact, int fired) {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("ruleType", bundle.path("ruleType").asText());
    result.put("releaseId", bundle.path("releaseId").asLong());
    result.put("revision", bundle.path("revision").asLong());
    result.put("docCode", fact.getDocCode());
    result.put("bizId", fact.getBizId());
    result.put(
        "decision",
        assembler.assemble(fact, list(bundle.path("outputFields"), RuleOutputField.class)));
    result.put("data", fact.getData());
    result.put("ext", fact.getExt());
    result.put("messages", fact.getMessages());
    result.put("fired", fired);
    return result;
  }

  private <T> List<T> list(JsonNode node, Class<T> type) {
    if (!node.isArray()) return Collections.emptyList();
    return mapper.convertValue(
        node, mapper.getTypeFactory().constructCollectionType(List.class, type));
  }
}
