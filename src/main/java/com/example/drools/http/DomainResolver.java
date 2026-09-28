package com.example.drools.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 域名解析：不同环境域名不同，所以域名按「域名键 + 环境」存库，当前环境由 rule-http.env 指定。
 * 解析优先级：
 *   1) 接口注册里域名键本身就是完整 URL（http:// 或 https:// 开头）→ 直接用（临时对接/联调用）
 *   2) 库 rule_http_domain(domain_key, 当前 env) 的 base_url
 *   3) application.yml 的 rule-http.domains.<domainKey>（兜底，兼容老配置）
 */
@Service
public class DomainResolver {

    private final JdbcTemplate jdbc;
    private final Map<String, String> ymlFallback = new LinkedHashMap<String, String>();

    @Value("${rule-http.env:dev}")
    private String currentEnv = "dev";

    public DomainResolver(JdbcTemplate jdbc, RuleHttpProperties properties) {
        this.jdbc = jdbc;
        if (properties != null && properties.getDomains() != null) {
            ymlFallback.putAll(properties.getDomains());
        }
    }

    public String currentEnv() {
        return currentEnv;
    }

    /** 供启动时把 yml 的域名灌进来当兜底（如需要） */
    public void setYmlFallback(Map<String, String> domains) {
        if (domains != null) {
            ymlFallback.putAll(domains);
        }
    }

    /** 解析域名键 → 真实域名（不含路径） */
    public String resolve(String domainKey) {
        if (domainKey == null || domainKey.trim().isEmpty()) {
            throw new HttpConfigException("域名未填：请在「② 接口注册」里填域名键，或在「环境域名」里维护各环境真值");
        }
        String key = domainKey.trim();
        if (key.startsWith("http://") || key.startsWith("https://")) {
            return trimSlash(key);
        }
        List<String> rows = jdbc == null ? java.util.Collections.<String>emptyList()
                : jdbc.queryForList(
                        "SELECT base_url FROM rule_http_domain WHERE domain_key = ? AND env = ?", String.class, key, currentEnv);
        if (!rows.isEmpty() && rows.get(0) != null && !rows.get(0).trim().isEmpty()) {
            return trimSlash(rows.get(0).trim());
        }
        String fallback = ymlFallback.get(key);
        if (fallback != null && !fallback.trim().isEmpty()) {
            return trimSlash(fallback.trim());
        }
        throw new HttpConfigException("域名未配置：域名键[" + key + "] 在环境[" + currentEnv + "]下没有真值"
                + "（请在「② 接口注册 → 环境域名」里补上该环境的域名；当前环境由 rule-http.env 决定）");
    }

    /** 域名键 × 环境的全量列表（页面维护用） */
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT domain_key, env, base_url, remark FROM rule_http_domain ORDER BY domain_key, env");
    }

    /** 覆盖保存某个「域名键 + 环境」的真值 */
    public void save(String domainKey, String env, String baseUrl, String remark) {
        if (domainKey == null || domainKey.trim().isEmpty()) throw new HttpConfigException("域名键必填");
        if (env == null || env.trim().isEmpty()) throw new HttpConfigException("环境必填（如 dev/test/prod）");
        if (baseUrl == null || baseUrl.trim().isEmpty()) throw new HttpConfigException("域名必填（如 https://api-test.xxx.com）");
        String url = baseUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new HttpConfigException("域名要以 http:// 或 https:// 开头，当前填的是：" + url);
        }
        jdbc.update("INSERT INTO rule_http_domain (domain_key, env, base_url, remark) VALUES (?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE base_url = VALUES(base_url), remark = VALUES(remark)",
                domainKey.trim(), env.trim(), trimSlash(url), remark);
    }

    private String trimSlash(String s) {
        String v = s;
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
