package com.example.drools.dao;

import com.example.drools.entity.RuleHttpAction;
import com.example.drools.entity.RuleHttpActionReturn;
import com.example.drools.entity.RuleHttpCallLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * HTTP 动作配置 + 返回值映射 + 调用日志访问。
 */
@Repository
public class RuleHttpActionDao {

    private final JdbcTemplate jdbc;

    public RuleHttpActionDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RuleHttpAction> findAll() {
        return jdbc.query("SELECT * FROM rule_http_action ORDER BY id", actionMapper());
    }

    public RuleHttpAction findByCode(String actionCode) {
        List<RuleHttpAction> list = jdbc.query(
                "SELECT * FROM rule_http_action WHERE action_code = ?", actionMapper(), actionCode);
        return list.isEmpty() ? null : list.get(0);
    }

    public int upsert(RuleHttpAction action) {
        return jdbc.update(
                "INSERT INTO rule_http_action (action_code, action_name, action_category, doc_code, method, domain_key, "
                        + "path, headers_json, body_template, timeout_ms, param_in, status, auth_code, req_encrypt, "
                        + "req_encrypt_key_ref, req_encrypt_iv_ref, resp_decrypt, resp_decrypt_key_ref, "
                        + "resp_decrypt_iv_ref, sign_type, sign_key_ref, sign_place, sign_field) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE action_name = VALUES(action_name), "
                        + "action_category = VALUES(action_category), doc_code = VALUES(doc_code), "
                        + "method = VALUES(method), domain_key = VALUES(domain_key), path = VALUES(path), "
                        + "headers_json = VALUES(headers_json), body_template = VALUES(body_template), "
                        + "timeout_ms = VALUES(timeout_ms), param_in = VALUES(param_in), auth_code = VALUES(auth_code), "
                        + "req_encrypt = VALUES(req_encrypt), req_encrypt_key_ref = VALUES(req_encrypt_key_ref), "
                        + "req_encrypt_iv_ref = VALUES(req_encrypt_iv_ref), resp_decrypt = VALUES(resp_decrypt), "
                        + "resp_decrypt_key_ref = VALUES(resp_decrypt_key_ref), resp_decrypt_iv_ref = VALUES(resp_decrypt_iv_ref), "
                        + "sign_type = VALUES(sign_type), sign_key_ref = VALUES(sign_key_ref), "
                        + "sign_place = VALUES(sign_place), sign_field = VALUES(sign_field)",
                action.getActionCode(), action.getActionName(), action.getActionCategory(), action.getDocCode(),
                action.getMethod() == null ? "POST" : action.getMethod().toUpperCase(), action.getDomainKey(),
                action.getPath(), action.getHeadersJson(), action.getBodyTemplate(),
                action.getTimeoutMs() == null ? 2000 : action.getTimeoutMs(),
                action.getParamIn() == null || action.getParamIn().trim().isEmpty() ? "AUTO" : action.getParamIn().toUpperCase(),
                action.getAuthCode(), action.getReqEncrypt(), action.getReqEncryptKeyRef(), action.getReqEncryptIvRef(),
                action.getRespDecrypt(), action.getRespDecryptKeyRef(), action.getRespDecryptIvRef(),
                action.getSignType(), action.getSignKeyRef(), action.getSignPlace(), action.getSignField());
    }

    public int updateStatus(String actionCode, int status) {
        return jdbc.update("UPDATE rule_http_action SET status = ? WHERE action_code = ?", status, actionCode);
    }

    public List<RuleHttpActionReturn> findReturns(String actionCode) {
        return jdbc.query("SELECT * FROM rule_http_action_return WHERE action_code = ? ORDER BY sort_order, id",
                returnMapper(), actionCode);
    }

    public int upsertReturn(RuleHttpActionReturn mapping) {
        return jdbc.update(
                "INSERT INTO rule_http_action_return (action_code, resp_path, target_field, target_type, as_message, sort_order) "
                        + "VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE resp_path = VALUES(resp_path), target_type = VALUES(target_type), "
                        + "as_message = VALUES(as_message), sort_order = VALUES(sort_order)",
                mapping.getActionCode(), mapping.getRespPath(), mapping.getTargetField(),
                mapping.getTargetType() == null ? "STRING" : mapping.getTargetType(),
                mapping.getAsMessage() == null ? 0 : mapping.getAsMessage(),
                mapping.getSortOrder() == null ? 1 : mapping.getSortOrder());
    }

    public int deleteReturns(String actionCode) {
        return jdbc.update("DELETE FROM rule_http_action_return WHERE action_code = ?", actionCode);
    }

    public void insertLog(RuleHttpCallLog log) {
        jdbc.update("INSERT INTO rule_http_call_log (action_code, doc_code, biz_id, request_url, request_body, "
                        + "response_body, success, error, cost_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                log.getActionCode(), log.getDocCode(), log.getBizId(), log.getRequestUrl(), log.getRequestBody(),
                log.getResponseBody(), log.getSuccess() != null && log.getSuccess() ? 1 : 0, log.getError(),
                log.getCostMs() == null ? 0 : log.getCostMs());
    }

    public List<RuleHttpCallLog> findLogs(int limit) {
        return jdbc.query("SELECT * FROM rule_http_call_log ORDER BY id DESC LIMIT ?", logMapper(), limit);
    }

    private RowMapper<RuleHttpAction> actionMapper() {
        return (rs, n) -> {
            RuleHttpAction a = new RuleHttpAction();
            a.setId(rs.getLong("id"));
            a.setActionCode(rs.getString("action_code"));
            a.setActionName(rs.getString("action_name"));
            a.setActionCategory(rs.getString("action_category"));
            a.setDocCode(rs.getString("doc_code"));
            a.setMethod(rs.getString("method"));
            a.setDomainKey(rs.getString("domain_key"));
            a.setPath(rs.getString("path"));
            a.setHeadersJson(rs.getString("headers_json"));
            a.setBodyTemplate(rs.getString("body_template"));
            a.setTimeoutMs(rs.getInt("timeout_ms"));
            a.setParamIn(rs.getString("param_in"));
            a.setStatus(rs.getInt("status"));
            a.setAuthCode(rs.getString("auth_code"));
            a.setReqEncrypt(rs.getString("req_encrypt"));
            a.setReqEncryptKeyRef(rs.getString("req_encrypt_key_ref"));
            a.setReqEncryptIvRef(rs.getString("req_encrypt_iv_ref"));
            a.setRespDecrypt(rs.getString("resp_decrypt"));
            a.setRespDecryptKeyRef(rs.getString("resp_decrypt_key_ref"));
            a.setRespDecryptIvRef(rs.getString("resp_decrypt_iv_ref"));
            a.setSignType(rs.getString("sign_type"));
            a.setSignKeyRef(rs.getString("sign_key_ref"));
            a.setSignPlace(rs.getString("sign_place"));
            a.setSignField(rs.getString("sign_field"));
            return a;
        };
    }

    private RowMapper<RuleHttpActionReturn> returnMapper() {
        return (rs, n) -> {
            RuleHttpActionReturn r = new RuleHttpActionReturn();
            r.setId(rs.getLong("id"));
            r.setActionCode(rs.getString("action_code"));
            r.setRespPath(rs.getString("resp_path"));
            r.setTargetField(rs.getString("target_field"));
            r.setTargetType(rs.getString("target_type"));
            r.setAsMessage(rs.getInt("as_message"));
            r.setSortOrder(rs.getInt("sort_order"));
            return r;
        };
    }

    private RowMapper<RuleHttpCallLog> logMapper() {
        return (rs, n) -> {
            RuleHttpCallLog l = new RuleHttpCallLog();
            l.setId(rs.getLong("id"));
            l.setActionCode(rs.getString("action_code"));
            l.setDocCode(rs.getString("doc_code"));
            l.setBizId(rs.getString("biz_id"));
            l.setRequestUrl(rs.getString("request_url"));
            l.setRequestBody(rs.getString("request_body"));
            l.setResponseBody(rs.getString("response_body"));
            l.setSuccess(rs.getInt("success") == 1);
            l.setError(rs.getString("error"));
            l.setCostMs(rs.getLong("cost_ms"));
            Timestamp ct = rs.getTimestamp("create_time");
            l.setCreateTime(ct == null ? null : ct.toLocalDateTime());
            return l;
        };
    }
}
