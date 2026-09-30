package com.example.drools.dao;

import com.example.drools.entity.RuleStep;
import com.example.drools.entity.RuleStepOutput;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 规则类型的步骤链读写 */
@Repository
public class RuleStepDao {

  private final JdbcTemplate jdbc;

  public RuleStepDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final String COLS =
      "id, rule_type, step_no, step_name, cond_field, cond_op, cond_type, cond_value, "
          + "cond_scope, collection_path, nth_index, "
          + "action_type, message, create_time";

  private final RowMapper<RuleStep> mapper =
      new RowMapper<RuleStep>() {
        @Override
        public RuleStep mapRow(ResultSet rs, int rowNum) throws SQLException {
          RuleStep s = new RuleStep();
          s.setId(rs.getLong("id"));
          s.setRuleType(rs.getString("rule_type"));
          s.setStepNo(rs.getInt("step_no"));
          s.setStepName(rs.getString("step_name"));
          s.setCondField(rs.getString("cond_field"));
          s.setCondOp(rs.getString("cond_op"));
          s.setCondType(rs.getString("cond_type"));
          s.setCondValue(rs.getString("cond_value"));
          s.setCondScope(rs.getString("cond_scope"));
          s.setCollectionPath(rs.getString("collection_path"));
          Object nth = rs.getObject("nth_index");
          s.setNthIndex(nth == null ? null : ((Number) nth).intValue());
          s.setActionType(rs.getString("action_type"));
          s.setMessage(rs.getString("message"));
          return s;
        }
      };

  public List<RuleStep> findByType(String ruleType) {
    List<RuleStep> steps =
        jdbc.query(
            "SELECT " + COLS + " FROM rule_step WHERE rule_type = ? ORDER BY step_no",
            mapper,
            ruleType);
    Map<Integer, RuleStep> byNo = new LinkedHashMap<Integer, RuleStep>();
    for (RuleStep step : steps) byNo.put(step.getStepNo(), step);
    jdbc.query(
        "SELECT id, rule_type, step_no, output_key, value_type, expression, sort_order, create_time"
            + " FROM rule_step_output WHERE rule_type = ? ORDER BY step_no, sort_order, id",
        rs -> {
          RuleStep step = byNo.get(rs.getInt("step_no"));
          if (step != null) {
            RuleStepOutput output = new RuleStepOutput();
            output.setId(rs.getLong("id"));
            output.setRuleType(rs.getString("rule_type"));
            output.setStepNo(rs.getInt("step_no"));
            output.setOutputKey(rs.getString("output_key"));
            output.setValueType(rs.getString("value_type"));
            output.setExpression(rs.getString("expression"));
            output.setSortOrder(rs.getInt("sort_order"));
            step.getOutputs().add(output);
          }
        },
        ruleType);
    return steps;
  }

  public int deleteByType(String ruleType) {
    jdbc.update("DELETE FROM rule_step_output WHERE rule_type = ?", ruleType);
    return jdbc.update("DELETE FROM rule_step WHERE rule_type = ?", ruleType);
  }

  public int insert(RuleStep s) {
    return jdbc.update(
        "INSERT INTO rule_step (rule_type, step_no, step_name, cond_field, cond_op, cond_type,"
            + " cond_value, cond_scope, collection_path, nth_index, action_type,"
            + " message) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        s.getRuleType(),
        s.getStepNo(),
        s.getStepName(),
        s.getCondField(),
        s.getCondOp(),
        s.getCondType(),
        s.getCondValue(),
        s.getCondScope() == null ? "DOC" : s.getCondScope(),
        s.getCollectionPath(),
        s.getNthIndex(),
        s.getActionType(),
        s.getMessage());
  }

  /** 覆盖式保存某类型的步骤链 */
  public void replaceAll(String ruleType, List<RuleStep> steps) {
    deleteByType(ruleType);
    if (steps == null) return;
    for (RuleStep s : steps) {
      s.setRuleType(ruleType);
      insert(s);
      if (s.getOutputs() != null) {
        int order = 1;
        for (RuleStepOutput output : s.getOutputs()) {
          jdbc.update(
              "INSERT INTO rule_step_output (rule_type, step_no, output_key, value_type,"
                  + " expression, sort_order) VALUES (?, ?, ?, ?, ?, ?)",
              ruleType,
              s.getStepNo(),
              output.getOutputKey(),
              output.getValueType() == null ? "STRING" : output.getValueType(),
              output.getExpression(),
              output.getSortOrder() == null ? order : output.getSortOrder());
          order++;
        }
      }
    }
  }
}
