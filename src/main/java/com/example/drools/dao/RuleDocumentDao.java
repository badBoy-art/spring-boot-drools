package com.example.drools.dao;

import com.example.drools.entity.RuleDocument;
import com.example.drools.entity.RuleDocumentField;
import com.example.drools.entity.RuleDocumentObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 单据注册（单据 + 对象 + 字段）访问。
 */
@Repository
public class RuleDocumentDao {

    private final JdbcTemplate jdbc;

    public RuleDocumentDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RuleDocument> findDocuments() {
        return jdbc.query("SELECT * FROM rule_document ORDER BY id", docMapper());
    }

    public RuleDocument findDocument(String docCode) {
        List<RuleDocument> list = jdbc.query("SELECT * FROM rule_document WHERE doc_code = ?", docMapper(), docCode);
        return list.isEmpty() ? null : list.get(0);
    }

    public int insertDocument(String docCode, String docName, String remark) {
        return jdbc.update("INSERT INTO rule_document (doc_code, doc_name, remark, status) VALUES (?, ?, ?, 1)",
                docCode, docName, remark);
    }

    public int updateDocument(String docCode, String docName, String remark) {
        return jdbc.update("UPDATE rule_document SET doc_name = ?, remark = ? WHERE doc_code = ?",
                docName, remark, docCode);
    }

    /** 单据对象（含嵌套） */
    public List<RuleDocumentObject> findObjects(String docCode) {
        return jdbc.query("SELECT * FROM rule_document_object WHERE doc_code = ? ORDER BY sort_order, id",
                objectMapper(), docCode);
    }

    /** 注册/覆盖一个单据对象 */
    public int upsertObject(RuleDocumentObject object) {
        return jdbc.update(
                "INSERT INTO rule_document_object (doc_code, object_key, object_name, parent_object_key, "
                        + "is_collection, value_path, remark, sort_order, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1) "
                        + "ON DUPLICATE KEY UPDATE object_name = VALUES(object_name), "
                        + "parent_object_key = VALUES(parent_object_key), is_collection = VALUES(is_collection), "
                        + "value_path = VALUES(value_path), remark = VALUES(remark), sort_order = VALUES(sort_order)",
                object.getDocCode(), object.getObjectKey(), object.getObjectName(), object.getParentObjectKey(),
                object.getIsCollection() == null ? 0 : object.getIsCollection(), object.getValuePath(),
                object.getRemark(), object.getSortOrder() == null ? 1 : object.getSortOrder());
    }

    /** 某单据登记的全部字段（页面配接口参数时挑它） */
    public List<RuleDocumentField> findFields(String docCode) {
        return jdbc.query("SELECT * FROM rule_document_field WHERE doc_code = ? ORDER BY sort_order, id",
                fieldMapper(), docCode);
    }

    public RuleDocumentField findField(String docCode, String fieldKey) {
        List<RuleDocumentField> list = jdbc.query(
                "SELECT * FROM rule_document_field WHERE doc_code = ? AND field_key = ?", fieldMapper(), docCode, fieldKey);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 注册/覆盖一个单据字段（所属对象 + 中文名 + fieldName + 说明 + 可选的派生表达式） */
    public int upsertField(RuleDocumentField field) {
        return jdbc.update(
                "INSERT INTO rule_document_field (doc_code, object_key, field_name, field_key, field_type, "
                        + "example_value, field_desc, expr, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE object_key = VALUES(object_key), field_name = VALUES(field_name), "
                        + "field_type = VALUES(field_type), example_value = VALUES(example_value), "
                        + "field_desc = VALUES(field_desc), expr = VALUES(expr), sort_order = VALUES(sort_order)",
                field.getDocCode(), field.getObjectKey(), field.getFieldName(), field.getFieldKey(),
                field.getFieldType(), field.getExampleValue(), field.getFieldDesc(), field.getExpr(),
                field.getSortOrder() == null ? 1 : field.getSortOrder());
    }

    public int deleteField(String docCode, String fieldKey) {
        return jdbc.update("DELETE FROM rule_document_field WHERE doc_code = ? AND field_key = ?", docCode, fieldKey);
    }

    private RowMapper<RuleDocument> docMapper() {
        return (rs, n) -> {
            RuleDocument d = new RuleDocument();
            d.setId(rs.getLong("id"));
            d.setDocCode(rs.getString("doc_code"));
            d.setDocName(rs.getString("doc_name"));
            d.setRemark(rs.getString("remark"));
            d.setStatus(rs.getInt("status"));
            return d;
        };
    }

    private RowMapper<RuleDocumentObject> objectMapper() {
        return (rs, n) -> {
            RuleDocumentObject o = new RuleDocumentObject();
            o.setId(rs.getLong("id"));
            o.setDocCode(rs.getString("doc_code"));
            o.setObjectKey(rs.getString("object_key"));
            o.setObjectName(rs.getString("object_name"));
            o.setParentObjectKey(rs.getString("parent_object_key"));
            o.setIsCollection(rs.getInt("is_collection"));
            o.setValuePath(rs.getString("value_path"));
            o.setRemark(rs.getString("remark"));
            o.setSortOrder(rs.getInt("sort_order"));
            o.setStatus(rs.getInt("status"));
            return o;
        };
    }

    private RowMapper<RuleDocumentField> fieldMapper() {
        return (rs, n) -> {
            RuleDocumentField f = new RuleDocumentField();
            f.setId(rs.getLong("id"));
            f.setDocCode(rs.getString("doc_code"));
            f.setObjectKey(rs.getString("object_key"));
            f.setFieldName(rs.getString("field_name"));
            f.setFieldKey(rs.getString("field_key"));
            f.setFieldType(rs.getString("field_type"));
            f.setExampleValue(rs.getString("example_value"));
            f.setFieldDesc(rs.getString("field_desc"));
            f.setExpr(rs.getString("expr"));
            f.setSortOrder(rs.getInt("sort_order"));
            return f;
        };
    }
}
