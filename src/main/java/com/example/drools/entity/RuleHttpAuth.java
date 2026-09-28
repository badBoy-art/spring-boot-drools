package com.example.drools.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * HTTP 动作的认证配置（对应 rule_http_auth）。
 * 注意：所有密钥字段都是【引用名】(xxx_ref)，真实密钥在配置里（rule-http.secrets.<ref>），不入库。
 */
@Data
public class RuleHttpAuth {

    private Long id;
    private String authCode;
    private String authName;
    /** NONE / BEARER / BASIC / HEADER / JWT_HS256 / OAUTH2_CC */
    private String authType;

    /** BEARER 静态 token 的配置键 */
    private String tokenRef;

    /** BASIC */
    private String username;
    private String passwordRef;

    /** HEADER 自定义头 */
    private String headerName;
    private String headerValueRef;

    /** JWT_HS256 */
    private String jwtSecretRef;
    private String jwtIssuer;
    private String jwtSubject;
    private String jwtAudience;
    private Integer jwtTtlSeconds;
    private String jwtClaimsJson;

    /** OAUTH2 client_credentials */
    private String oauthTokenDomainKey;
    private String oauthTokenPath;
    private String oauthClientId;
    private String oauthClientSecretRef;
    private String oauthScope;

    private Integer status;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
