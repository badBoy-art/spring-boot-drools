package com.example.drools.dao;

import com.example.drools.entity.RuleCombinationTable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 组合规则表访问。 */
@Repository
public class RuleCombinationTableDao {

    private final JdbcTemplate jdbc;

    public RuleCombinationTableDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RuleCombinationTable> findAll() {
        return jdbc.query("SELECT * FROM rule_combination_table ORDER BY status DESC, id", mapper());
    }

    /** 引擎加载用：只取生效的 */
    public List<RuleCombinationTable> findByStatus(int status) {
        return jdbc.query("SELECT * FROM rule_combination_table WHERE status = ? ORDER BY id", mapper(), status);
    }

    public RuleCombinationTable findByKey(String assetKey) {
        if (assetKey == null || assetKey.trim().isEmpty()) {
            return null;
        }
        List<RuleCombinationTable> list = jdbc.query(
                "SELECT * FROM rule_combination_table WHERE asset_key = ?", mapper(), assetKey);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 保存定义（不改状态/version/DRL，那些由发布流程写） */
    public int upsertDefinition(RuleCombinationTable t) {
        return jdbc.update(
                "INSERT INTO rule_combination_table (asset_key, asset_name, doc_code, fact_class, salience, "
                        + "columns_json, action_json, rows_json, row_count, status, updated_by, remark) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE asset_name = VALUES(asset_name), doc_code = VALUES(doc_code), "
                        + "fact_class = VALUES(fact_class), salience = VALUES(salience), "
                        + "columns_json = VALUES(columns_json), action_json = VALUES(action_json), "
                        + "rows_json = VALUES(rows_json), row_count = VALUES(row_count), remark = VALUES(remark), "
                        + "updated_by = VALUES(updated_by)",
                t.getAssetKey(), t.getAssetName(), t.getDocCode(),
                t.getFactClass() == null ? "Order" : t.getFactClass(),
                t.getSalience() == null ? -20 : t.getSalience(),
                t.getColumnsJson(), t.getActionJson(), t.getRowsJson(),
                t.getRowCount() == null ? 0 : t.getRowCount(), t.getUpdatedBy(), t.getRemark());
    }

    /** 发布：写 DRL + 版本+1 + 状态置生效 */
    public int publish(String assetKey, String drl, int version, int rowCount, String updatedBy) {
        return jdbc.update(
                "UPDATE rule_combination_table SET drl_content = ?, version = ?, row_count = ?, status = 1, "
                        + "updated_by = ? WHERE asset_key = ?",
                drl, version, rowCount, updatedBy, assetKey);
    }

    public int updateStatus(String assetKey, int status) {
        return jdbc.update("UPDATE rule_combination_table SET status = ? WHERE asset_key = ?", status, assetKey);
    }

    public int delete(String assetKey) {
        return jdbc.update("DELETE FROM rule_combination_table WHERE asset_key = ?", assetKey);
    }

    private RowMapper<RuleCombinationTable> mapper() {
        return (rs, n) -> {
            RuleCombinationTable t = new RuleCombinationTable();
            t.setId(rs.getLong("id"));
            t.setAssetKey(rs.getString("asset_key"));
            t.setAssetName(rs.getString("asset_name"));
            t.setDocCode(rs.getString("doc_code"));
            t.setFactClass(rs.getString("fact_class"));
            t.setSalience(rs.getInt("salience"));
            t.setColumnsJson(rs.getString("columns_json"));
            t.setActionJson(rs.getString("action_json"));
            t.setRowsJson(rs.getString("rows_json"));
            t.setDrlContent(rs.getString("drl_content"));
            t.setRowCount(rs.getInt("row_count"));
            t.setVersion(rs.getInt("version"));
            t.setStatus(rs.getInt("status"));
            t.setUpdatedBy(rs.getString("updated_by"));
            t.setRemark(rs.getString("remark"));
            t.setCreateTime(rs.getTimestamp("create_time") == null ? null : rs.getTimestamp("create_time").toLocalDateTime());
            t.setUpdateTime(rs.getTimestamp("update_time") == null ? null : rs.getTimestamp("update_time").toLocalDateTime());
            return t;
        };
    }
}
