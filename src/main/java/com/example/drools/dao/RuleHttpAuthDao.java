package com.example.drools.dao;

import com.example.drools.entity.RuleHttpAuth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 认证配置访问。 */
@Repository
public class RuleHttpAuthDao {

    private final JdbcTemplate jdbc;

    public RuleHttpAuthDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RuleHttpAuth> findAll() {
        return jdbc.query("SELECT * FROM rule_http_auth ORDER BY id", mapper());
    }

    public RuleHttpAuth findByCode(String authCode) {
        if (authCode == null || authCode.trim().isEmpty()) {
            return null;
        }
        List<RuleHttpAuth> list = jdbc.query(
                "SELECT * FROM rule_http_auth WHERE auth_code = ?", mapper(), authCode);
        return list.isEmpty() ? null : list.get(0);
    }

    public int upsert(RuleHttpAuth auth) {
        return jdbc.update(
                "INSERT INTO rule_http_auth (auth_code, auth_name, auth_type, token_ref, username, password_ref, "
                        + "header_name, header_value_ref, jwt_secret_ref, jwt_issuer, jwt_subject, jwt_audience, "
                        + "jwt_ttl_seconds, jwt_claims_json, oauth_token_domain_key, oauth_token_path, oauth_client_id, "
                        + "oauth_client_secret_ref, oauth_scope, status, remark) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE auth_name = VALUES(auth_name), auth_type = VALUES(auth_type), "
                        + "token_ref = VALUES(token_ref), username = VALUES(username), password_ref = VALUES(password_ref), "
                        + "header_name = VALUES(header_name), header_value_ref = VALUES(header_value_ref), "
                        + "jwt_secret_ref = VALUES(jwt_secret_ref), jwt_issuer = VALUES(jwt_issuer), "
                        + "jwt_subject = VALUES(jwt_subject), jwt_audience = VALUES(jwt_audience), "
                        + "jwt_ttl_seconds = VALUES(jwt_ttl_seconds), jwt_claims_json = VALUES(jwt_claims_json), "
                        + "oauth_token_domain_key = VALUES(oauth_token_domain_key), oauth_token_path = VALUES(oauth_token_path), "
                        + "oauth_client_id = VALUES(oauth_client_id), oauth_client_secret_ref = VALUES(oauth_client_secret_ref), "
                        + "oauth_scope = VALUES(oauth_scope), status = VALUES(status), remark = VALUES(remark)",
                auth.getAuthCode(), auth.getAuthName(), auth.getAuthType(), auth.getTokenRef(), auth.getUsername(),
                auth.getPasswordRef(), auth.getHeaderName(), auth.getHeaderValueRef(), auth.getJwtSecretRef(),
                auth.getJwtIssuer(), auth.getJwtSubject(), auth.getJwtAudience(),
                auth.getJwtTtlSeconds() == null ? 300 : auth.getJwtTtlSeconds(), auth.getJwtClaimsJson(),
                auth.getOauthTokenDomainKey(), auth.getOauthTokenPath(), auth.getOauthClientId(),
                auth.getOauthClientSecretRef(), auth.getOauthScope(),
                auth.getStatus() == null ? 1 : auth.getStatus(), auth.getRemark());
    }

    public int updateStatus(String authCode, int status) {
        return jdbc.update("UPDATE rule_http_auth SET status = ? WHERE auth_code = ?", status, authCode);
    }

    private RowMapper<RuleHttpAuth> mapper() {
        return (rs, n) -> {
            RuleHttpAuth a = new RuleHttpAuth();
            a.setId(rs.getLong("id"));
            a.setAuthCode(rs.getString("auth_code"));
            a.setAuthName(rs.getString("auth_name"));
            a.setAuthType(rs.getString("auth_type"));
            a.setTokenRef(rs.getString("token_ref"));
            a.setUsername(rs.getString("username"));
            a.setPasswordRef(rs.getString("password_ref"));
            a.setHeaderName(rs.getString("header_name"));
            a.setHeaderValueRef(rs.getString("header_value_ref"));
            a.setJwtSecretRef(rs.getString("jwt_secret_ref"));
            a.setJwtIssuer(rs.getString("jwt_issuer"));
            a.setJwtSubject(rs.getString("jwt_subject"));
            a.setJwtAudience(rs.getString("jwt_audience"));
            a.setJwtTtlSeconds(rs.getInt("jwt_ttl_seconds"));
            a.setJwtClaimsJson(rs.getString("jwt_claims_json"));
            a.setOauthTokenDomainKey(rs.getString("oauth_token_domain_key"));
            a.setOauthTokenPath(rs.getString("oauth_token_path"));
            a.setOauthClientId(rs.getString("oauth_client_id"));
            a.setOauthClientSecretRef(rs.getString("oauth_client_secret_ref"));
            a.setOauthScope(rs.getString("oauth_scope"));
            a.setStatus(rs.getInt("status"));
            a.setRemark(rs.getString("remark"));
            return a;
        };
    }
}
