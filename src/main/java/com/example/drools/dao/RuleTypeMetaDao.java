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

    /** 某类型的 DRL 模板体 */
    public String findTemplate(String ruleType) {
        List<String> list = jdbc.query("SELECT template_body FROM rule_template WHERE rule_type = ?",
                (rs, n) -> rs.getString("template_body"), ruleType);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 注册/覆盖规则类型元数据（自定义类型 builtin=0） */
    public int upsertMeta(RuleTypeMeta meta) {
        return jdbc.update(
                "INSERT INTO rule_type_meta (rule_type, rule_group, type_name, type_desc, builtin, sort_order) "
                        + "VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE rule_group = VALUES(rule_group), type_name = VALUES(type_name), "
                        + "type_desc = VALUES(type_desc), sort_order = VALUES(sort_order)",
                meta.getRuleType(), meta.getRuleGroup() == null ? "custom" : meta.getRuleGroup(),
                meta.getTypeName(), meta.getTypeDesc(),
                Boolean.TRUE.equals(meta.getBuiltin()) ? 1 : 0,
                meta.getSortOrder() == null || meta.getSortOrder() == 0 ? 90 : meta.getSortOrder());
    }

    /** 注册/覆盖类型的一个参数定义 */
    public int upsertField(RuleTypeField field) {
        return jdbc.update(
                "INSERT INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, "
                        + "min_value, max_value, enum_options, placeholder, sort_order) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE field_name = VALUES(field_name), field_type = VALUES(field_type), "
                        + "required = VALUES(required), default_value = VALUES(default_value), "
                        + "min_value = VALUES(min_value), max_value = VALUES(max_value), "
                        + "enum_options = VALUES(enum_options), placeholder = VALUES(placeholder), "
                        + "sort_order = VALUES(sort_order)",
                field.getRuleType(), field.getFieldKey(), field.getFieldName(), field.getFieldType(),
                Boolean.TRUE.equals(field.getRequired()) ? 1 : 0, field.getDefaultValue(),
                field.getMinValue(), field.getMaxValue(),
                field.getEnumOptions(), field.getPlaceholder(),
                field.getSortOrder() == null ? 1 : field.getSortOrder());
    }

    public int deleteFields(String ruleType) {
        return jdbc.update("DELETE FROM rule_type_field WHERE rule_type = ?", ruleType);
    }

    /** 注册/覆盖类型的 DRL 模板体 */
    public int upsertTemplate(String ruleType, String templateBody) {
        return jdbc.update(
                "INSERT INTO rule_template (rule_type, template_body) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE template_body = VALUES(template_body)", ruleType, templateBody);
    }

    public int deleteType(String ruleType) {
        jdbc.update("DELETE FROM rule_template WHERE rule_type = ?", ruleType);
        jdbc.update("DELETE FROM rule_type_field WHERE rule_type = ?", ruleType);
        return jdbc.update("DELETE FROM rule_type_meta WHERE rule_type = ?", ruleType);
    }

    /** 该类型下已有多少条规则（删除类型前要拦） */
    public int countRules(String ruleType) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rule_definition WHERE rule_type = ?", Integer.class, ruleType);
        return count == null ? 0 : count;
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
