package com.example.drools.dao;

import com.example.drools.entity.RuleDefinition;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/** 规则定义表访问（JdbcTemplate）。 */
@Repository
public class RuleDefinitionDao {

  private final JdbcTemplate jdbc;
  private final SimpleJdbcInsert inserter;

  public RuleDefinitionDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.inserter =
        new SimpleJdbcInsert(jdbc)
            .withTableName("rule_definition")
            .usingGeneratedKeyColumns("id")
            .usingColumns(
                "rule_group", "rule_type", "rule_name", "rule_params", "drl_content", "status",
                "version", "remark");
  }

  public List<RuleDefinition> findByStatus(int status) {
    return jdbc.query(
        "SELECT * FROM rule_definition WHERE status = ? ORDER BY id", rowMapper(), status);
  }

  public List<RuleDefinition> findByTypeAndStatus(String ruleType, int status) {
    return jdbc.query(
        "SELECT * FROM rule_definition WHERE rule_type = ? AND status = ? ORDER BY id",
        rowMapper(),
        ruleType,
        status);
  }

  public List<RuleDefinition> findAll() {
    return jdbc.query("SELECT * FROM rule_definition ORDER BY rule_group, id", rowMapper());
  }

  public RuleDefinition findById(Long id) {
    List<RuleDefinition> list =
        jdbc.query("SELECT * FROM rule_definition WHERE id = ?", rowMapper(), id);
    return list.isEmpty() ? null : list.get(0);
  }

  /** 插入并回填自增主键 */
  public void insert(RuleDefinition r) {
    Map<String, Object> params = new HashMap<>();
    params.put("rule_group", r.getRuleGroup());
    params.put("rule_type", r.getRuleType());
    params.put("rule_name", r.getRuleName());
    params.put("rule_params", r.getRuleParams());
    params.put("drl_content", r.getDrlContent());
    params.put("status", r.getStatus());
    params.put("version", r.getVersion());
    params.put("remark", r.getRemark());
    Number key = inserter.executeAndReturnKey(params);
    r.setId(key.longValue());
  }

  public int updateDrlAndParams(RuleDefinition r) {
    return jdbc.update(
        "UPDATE rule_definition SET rule_params = ?, drl_content = ?, remark = ?, version = version"
            + " + 1 WHERE id = ?",
        r.getRuleParams(),
        r.getDrlContent(),
        r.getRemark(),
        r.getId());
  }

  public void bumpVersion(Long id) {
    jdbc.update("UPDATE rule_definition SET version=version+1 WHERE id=?", id);
  }

  public int updateStatus(Long id, int status) {
    return jdbc.update("UPDATE rule_definition SET status = ? WHERE id = ?", status, id);
  }

  public int delete(Long id) {
    return jdbc.update("DELETE FROM rule_definition WHERE id = ?", id);
  }

  public void recordPublishEvent(String ruleType, String action) {
    jdbc.update(
        "INSERT INTO rule_publish_event(rule_type, action) VALUES (?, ?)", ruleType, action);
  }

  private RowMapper<RuleDefinition> rowMapper() {
    return (rs, n) -> {
      RuleDefinition r = new RuleDefinition();
      r.setId(rs.getLong("id"));
      r.setRuleGroup(rs.getString("rule_group"));
      r.setRuleType(rs.getString("rule_type"));
      r.setRuleName(rs.getString("rule_name"));
      r.setRuleParams(rs.getString("rule_params"));
      r.setDrlContent(rs.getString("drl_content"));
      r.setStatus(rs.getInt("status"));
      r.setVersion(rs.getInt("version"));
      r.setRemark(rs.getString("remark"));
      Timestamp ct = rs.getTimestamp("create_time");
      Timestamp ut = rs.getTimestamp("update_time");
      r.setCreateTime(ct == null ? null : ct.toLocalDateTime());
      r.setUpdateTime(ut == null ? null : ut.toLocalDateTime());
      return r;
    };
  }
}
