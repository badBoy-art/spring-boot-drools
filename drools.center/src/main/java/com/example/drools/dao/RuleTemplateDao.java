package com.example.drools.dao;

import com.example.drools.entity.RuleTemplate;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 规则模板访问。 */
@Repository
public class RuleTemplateDao {

  private final JdbcTemplate jdbc;

  public RuleTemplateDao(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public RuleTemplate findByType(String ruleType) {
    List<RuleTemplate> list =
        jdbc.query(
            "SELECT * FROM rule_template WHERE rule_type = ?",
            (rs, n) -> {
              RuleTemplate t = new RuleTemplate();
              t.setId(rs.getLong("id"));
              t.setRuleType(rs.getString("rule_type"));
              t.setTemplateBody(rs.getString("template_body"));
              Timestamp ct = rs.getTimestamp("create_time");
              Timestamp ut = rs.getTimestamp("update_time");
              t.setCreateTime(ct == null ? null : ct.toLocalDateTime());
              t.setUpdateTime(ut == null ? null : ut.toLocalDateTime());
              return t;
            },
            ruleType);
    return list.isEmpty() ? null : list.get(0);
  }
}
