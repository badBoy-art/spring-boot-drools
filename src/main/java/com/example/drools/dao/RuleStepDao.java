package com.example.drools.dao;

import com.example.drools.entity.RuleStep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** 规则类型的步骤链读写 */
@Repository
public class RuleStepDao {

    private final JdbcTemplate jdbc;

    public RuleStepDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS =
            "id, rule_type, step_no, step_name, cond_field, cond_op, cond_type, cond_value, " +
            "action_type, action_code, ext_field, ext_value, ext_value_type, message, param_json, create_time";

    private final RowMapper<RuleStep> mapper = new RowMapper<RuleStep>() {
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
            s.setActionType(rs.getString("action_type"));
            s.setActionCode(rs.getString("action_code"));
            s.setExtField(rs.getString("ext_field"));
            s.setExtValue(rs.getString("ext_value"));
            s.setExtValueType(rs.getString("ext_value_type"));
            s.setMessage(rs.getString("message"));
            s.setParamJson(rs.getString("param_json"));
            return s;
        }
    };

    public List<RuleStep> findByType(String ruleType) {
        return jdbc.query("SELECT " + COLS + " FROM rule_step WHERE rule_type = ? ORDER BY step_no", mapper, ruleType);
    }

    public int deleteByType(String ruleType) {
        return jdbc.update("DELETE FROM rule_step WHERE rule_type = ?", ruleType);
    }

    public int insert(RuleStep s) {
        return jdbc.update("INSERT INTO rule_step (rule_type, step_no, step_name, cond_field, cond_op, cond_type, cond_value, "
                        + "action_type, action_code, ext_field, ext_value, ext_value_type, message, param_json) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                s.getRuleType(), s.getStepNo(), s.getStepName(), s.getCondField(), s.getCondOp(), s.getCondType(),
                s.getCondValue(), s.getActionType(), s.getActionCode(), s.getExtField(), s.getExtValue(),
                s.getExtValueType() == null ? "STRING" : s.getExtValueType(), s.getMessage(),
                s.getParamJson());
    }

    /** 覆盖式保存某类型的步骤链 */
    public void replaceAll(String ruleType, List<RuleStep> steps) {
        deleteByType(ruleType);
        if (steps == null) return;
        for (RuleStep s : steps) {
            s.setRuleType(ruleType);
            insert(s);
        }
    }
}
