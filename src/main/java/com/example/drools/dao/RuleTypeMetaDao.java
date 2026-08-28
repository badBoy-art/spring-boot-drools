package com.example.drools.dao;

import com.example.drools.entity.RuleTypeField;
import com.example.drools.entity.RuleTypeMeta;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则类型元数据访问。
 */
@Repository
public class RuleTypeMetaDao {

    private final JdbcTemplate jdbc;

    public RuleTypeMetaDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全部类型及其字段定义（嵌套结构，供前端渲染表单） */
    public List<RuleTypeMeta> findAll() {
        List<RuleTypeMeta> metas = jdbc.query(
                "SELECT * FROM rule_type_meta ORDER BY sort_order", metaRowMapper());
        List<RuleTypeField> fields = jdbc.query(
                "SELECT * FROM rule_type_field ORDER BY rule_type, sort_order", fieldRowMapper());

        // 按 rule_type 分组组装
        Map<String, List<RuleTypeField>> byType = new LinkedHashMap<>();
        for (RuleTypeField f : fields) {
            byType.computeIfAbsent(f.getRuleType(), k -> new ArrayList<>()).add(f);
        }
        for (RuleTypeMeta m : metas) {
            List<RuleTypeField> fs = byType.get(m.getRuleType());
            if (fs != null) {
                m.setFields(fs);
            }
        }
        return metas;
    }

    /** 某类型的字段定义 */
    public List<RuleTypeField> findFields(String ruleType) {
        return jdbc.query(
                "SELECT * FROM rule_type_field WHERE rule_type = ? ORDER BY sort_order",
                fieldRowMapper(), ruleType);
    }

    /** 按类型查元数据（含字段定义），不存在返回 null */
    public RuleTypeMeta findByType(String ruleType) {
        List<RuleTypeMeta> list = jdbc.query(
                "SELECT * FROM rule_type_meta WHERE rule_type = ?", metaRowMapper(), ruleType);
        if (list.isEmpty()) {
            return null;
        }
        RuleTypeMeta m = list.get(0);
        m.setFields(findFields(ruleType));
        return m;
    }

    private RowMapper<RuleTypeMeta> metaRowMapper() {
        return (rs, n) -> {
            RuleTypeMeta m = new RuleTypeMeta();
            m.setRuleType(rs.getString("rule_type"));
            m.setRuleGroup(rs.getString("rule_group"));
            m.setTypeName(rs.getString("type_name"));
            m.setTypeDesc(rs.getString("type_desc"));
            m.setBuiltin(rs.getInt("builtin") == 1);
            m.setSortOrder(rs.getInt("sort_order"));
            return m;
        };
    }

    private RowMapper<RuleTypeField> fieldRowMapper() {
        return (rs, n) -> {
            RuleTypeField f = new RuleTypeField();
            f.setId(rs.getLong("id"));
            f.setRuleType(rs.getString("rule_type"));
            f.setFieldKey(rs.getString("field_key"));
            f.setFieldName(rs.getString("field_name"));
            f.setFieldType(rs.getString("field_type"));
            f.setRequired(rs.getInt("required") == 1);
            f.setDefaultValue(rs.getString("default_value"));
            f.setMinValue(rs.getString("min_value"));
            f.setMaxValue(rs.getString("max_value"));
            f.setEnumOptions(rs.getString("enum_options"));
            f.setPlaceholder(rs.getString("placeholder"));
            f.setSortOrder(rs.getInt("sort_order"));
            f.setRemark(rs.getString("remark"));
            return f;
        };
    }
}
