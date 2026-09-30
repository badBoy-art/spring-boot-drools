package com.example.drools.release;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

@Repository
public class RuleReleaseRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper = new ObjectMapper();

  public RuleReleaseRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void lockType(String type) {
    List<String> found =
        jdbc.query(
            "SELECT rule_type FROM rule_type_meta WHERE rule_type=? FOR UPDATE",
            (rs, n) -> rs.getString(1),
            type);
    if (found.isEmpty()) throw new IllegalArgumentException("Unknown rule type: " + type);
  }

  public JsonNode draft(String type) {
    List<String> rows =
        jdbc.query(
            "SELECT config_json FROM rule_bundle_draft WHERE rule_type=?",
            (rs, n) -> rs.getString(1),
            type);
    return rows.isEmpty() ? mapper.createObjectNode() : parse(rows.get(0));
  }

  public long draftVersion(String type) {
    List<Long> rows =
        jdbc.query(
            "SELECT draft_version FROM rule_bundle_draft WHERE rule_type=?",
            (rs, n) -> rs.getLong(1),
            type);
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  public void saveDraft(String type, long version, JsonNode body) {
    int changed =
        jdbc.update(
            "UPDATE rule_bundle_draft SET draft_version=?,config_json=? WHERE rule_type=?",
            version,
            body.toString(),
            type);
    if (changed == 0)
      jdbc.update(
          "INSERT INTO rule_bundle_draft(rule_type,draft_version,config_json) VALUES(?,?,?)",
          type,
          version,
          body.toString());
  }

  public long revision(String type) {
    List<Long> rows =
        jdbc.query(
            "SELECT revision FROM rule_release_head WHERE rule_type=?",
            (rs, n) -> rs.getLong(1),
            type);
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  public JsonNode current(String type) {
    List<String> rows =
        jdbc.query(
            "SELECT r.bundle_json FROM rule_release r JOIN rule_release_head h ON r.id=h.release_id"
                + " WHERE h.rule_type=?",
            (rs, n) -> rs.getString(1),
            type);
    if (rows.isEmpty())
      throw new IllegalStateException("No published release for rule type: " + type);
    return parse(rows.get(0));
  }

  public JsonNode release(String type, long id) {
    List<String> rows =
        jdbc.query(
            "SELECT bundle_json FROM rule_release WHERE rule_type=? AND id=?",
            (rs, n) -> rs.getString(1),
            type,
            id);
    if (rows.isEmpty()) throw new IllegalArgumentException("Release not found for this rule type");
    return parse(rows.get(0));
  }

  public List<Map<String, Object>> history(String type) {
    return jdbc.queryForList(
        "SELECT id,rule_type,revision,content_hash,published_by,remark,create_time FROM"
            + " rule_release WHERE rule_type=? ORDER BY revision DESC",
        type);
  }

  public JsonNode append(String type, ObjectNode bundle, String hash, String actor, String remark) {
    long revision = revision(type) + 1;
    bundle.put("revision", revision);
    Map<String, Object> values = new LinkedHashMap<String, Object>();
    values.put("rule_type", type);
    values.put("revision", revision);
    values.put("bundle_json", bundle.toString());
    values.put("content_hash", hash);
    values.put("published_by", actor);
    values.put("remark", remark);
    long id =
        new SimpleJdbcInsert(jdbc)
            .withTableName("rule_release")
            .usingColumns(values.keySet().toArray(new String[0]))
            .usingGeneratedKeyColumns("id")
            .executeAndReturnKey(values)
            .longValue();
    bundle.put("releaseId", id);
    bundle.put("contentHash", hash);
    jdbc.update("UPDATE rule_release SET bundle_json=? WHERE id=?", bundle.toString(), id);
    int changed =
        jdbc.update(
            "UPDATE rule_release_head SET release_id=?,revision=? WHERE rule_type=?",
            id,
            revision,
            type);
    if (changed == 0)
      jdbc.update(
          "INSERT INTO rule_release_head(rule_type,release_id,revision) VALUES(?,?,?)",
          type,
          id,
          revision);
    return bundle;
  }

  private JsonNode parse(String json) {
    try {
      return mapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored release", e);
    }
  }
}
