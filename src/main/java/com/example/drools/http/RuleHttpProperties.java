package com.example.drools.http;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 动作的部署配置：域名（按 key 配）+ 密钥库 + 默认超时 + 失败策略。
 * 规则参数里只放 domain_key / 密钥引用名，真实域名与密钥在这里（各环境不同）。
 *
 * application.yml 示例：
 * rule-http:
 *   domains:
 *     risk: https://risk.example.com
 *     mock: http://localhost:8080/mock
 *   secrets:                     # ← 密钥库：库里只存引用名，真值只在这里
 *     staticToken: "对方给的长期token"
 *     jwtSecret: "hex:00112233445566778899aabbccddeeff"     # 支持 hex: / base64: / 明文
 *     aesKey: "hex:00112233445566778899aabbccddeeff"
 *     hmacKey: "hex:0102030405060708090a0b0c0d0e0f10"
 *     oauthClientSecret: "xxx"
 *   default-timeout-ms: 2000
 *   fail-fast: false
 */
@Component
@ConfigurationProperties(prefix = "rule-http")
public class RuleHttpProperties {

    /** 域名表：domain_key -> base url */
    private Map<String, String> domains = new LinkedHashMap<String, String>();

    /**
     * 密钥库：secret_ref -> 真值。库里/规则参数里只存引用名，真值只放这里（或 Nacos / K8s Secret / 环境变量）。
     * 取值支持 "hex:..."、"base64:..." 前缀，其余按明文。
     */
    private Map<String, String> secrets = new LinkedHashMap<String, String>();

    private int defaultTimeoutMs = 2000;

    /** true：接口失败直接抛异常（业务下单一起失败）；false：记录失败+写订单消息，规则继续往下跑 */
    private boolean failFast = false;

    public Map<String, String> getDomains() {
        return domains;
    }

    public void setDomains(Map<String, String> domains) {
        this.domains = domains;
    }

    public Map<String, String> getSecrets() {
        return secrets;
    }

    public void setSecrets(Map<String, String> secrets) {
        this.secrets = secrets;
    }

    public int getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(int defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }
}
