package com.example.drools.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** HTTP 接口动作配置（对应 rule_http_action） */
@Data
public class RuleHttpAction {

    private Long id;
    private String actionCode;
    private String actionName;
    /** 接口分类：单据接口 / 流程引擎接口 / 风控接口 / 其它 */
    private String actionCategory;
    /** 作用于哪种单据，如 ORDER */
    private String docCode;
    /** GET/POST/PUT/DELETE */
    private String method;
    /** 域名配置键：rule-http.domains.<domainKey>，域名在 application.yml / Nacos 里配 */
    private String domainKey;
    private String path;
    private String headersJson;
    /** 请求体模板：常量 + ${字段} + ${表达式} */
    private String bodyTemplate;
    private Integer timeoutMs;
    private String paramIn;
    private Integer status;

    /** 认证配置码（rule_http_auth.auth_code）：Bearer / Basic / 自定义头 / JWT / OAuth2 */
    private String authCode;
    /** 请求体加密：NONE / AES_CBC / AES_GCM */
    private String reqEncrypt;
    private String reqEncryptKeyRef;
    /** 固定 IV 的引用名；为空则用随机 IV 前置到密文 */
    private String reqEncryptIvRef;
    /** 响应体解密：NONE / AES_CBC / AES_GCM */
    private String respDecrypt;
    private String respDecryptKeyRef;
    private String respDecryptIvRef;
    /** 签名：NONE / HMAC_SHA256 / MD5 */
    private String signType;
    private String signKeyRef;
    /** 签名位置：HEADER（默认头名 X-Sign，可用 signField 指定）/ BODY（signField 是 JSON 字段名） */
    private String signPlace;
    private String signField;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
