package com.example.drools.dao;

import com.example.drools.entity.RuleOutputField;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 返回值结构（rule_output_field）读写。 */
@Repository
public class RuleOutputDao {

  private final JdbcTemplate jdbc;

  public RuleOutputDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private final RowMapper<RuleOutputField> mapper =
      (rs, n) -> {
        RuleOutputField o = new RuleOutputField();
        o.setId(rs.getLong("id"));
        o.setRuleType(rs.getString("rule_type"));
        o.setOutputPath(rs.getString("output_path"));
        o.setParentPath(rs.getString("parent_path"));
        o.setLabel(rs.getString("label"));
        o.setNodeKind(rs.getString("node_kind"));
        o.setValueType(rs.getString("value_type"));
        o.setSource(rs.getString("source"));
        o.setSourceValue(rs.getString("source_value"));
        o.setArrayFrom(rs.getString("array_from"));
        o.setSortOrder(rs.getInt("sort_order"));
        return o;
      };

  public List<RuleOutputField> findByType(String ruleType) {
    return jdbc.query(
        "SELECT * FROM rule_output_field WHERE rule_type = ? ORDER BY sort_order, id",
        mapper,
        ruleType);
  }

  public int deleteByType(String ruleType) {
    return jdbc.update("DELETE FROM rule_output_field WHERE rule_type = ?", ruleType);
  }

  public int insert(RuleOutputField o) {
    return jdbc.update(
        "INSERT INTO rule_output_field (rule_type, output_path, parent_path, label, node_kind,"
            + " value_type, source, source_value, array_from, sort_order) VALUES"
            + " (?,?,?,?,?,?,?,?,?,?)",
        o.getRuleType(),
        o.getOutputPath(),
        o.getParentPath(),
        o.getLabel(),
        o.getNodeKind(),
        o.getValueType(),
        o.getSource(),
        o.getSourceValue(),
        o.getArrayFrom(),
        o.getSortOrder() == null ? 1 : o.getSortOrder());
  }

  /** 覆盖式保存某类型的返回结构 */
  public void replaceAll(String ruleType, List<RuleOutputField> fields) {
    deleteByType(ruleType);
    if (fields == null) return;
    for (RuleOutputField f : fields) {
      f.setRuleType(ruleType);
      insert(f);
    }
  }
}
