package com.example.drools.http;

import com.example.drools.dao.RuleHttpAuthDao;
import com.example.drools.entity.RuleHttpAuth;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证支持：按 rule_http_auth 的配置给请求加认证头。
 *
 * 支持（覆盖对接第三方最常见的几种）：
 *   BEARER      静态 token（对方给长期 token）    → Authorization: Bearer <token>
 *   BASIC       用户名 + 密码                     → Authorization: Basic base64(user:pass)
 *   HEADER      自定义头（如 X-Api-Key/X-Token）  → <headerName>: <value>
 *   JWT_HS256   用密钥自签 JWT（每次都新签）      → Authorization: Bearer <jwt>
 *   OAUTH2_CC   client_credentials 取 token       → Authorization: Bearer <access_token>（带缓存/过期重取）
 *
 * 密钥一律通过 rule-http.secrets.<ref> 取，不落库、不进规则参数、不打印到日志。
 */
@Component
public class HttpAuthSupport {

    private static final Logger log = LoggerFactory.getLogger(HttpAuthSupport.class);

    private final RuleHttpAuthDao authDao;
    private final RuleHttpProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    /** OAuth2 token 缓存：authCode -> 缓存的 token */
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<String, CachedToken>();

    public HttpAuthSupport(RuleHttpAuthDao authDao, RuleHttpProperties properties) {
        this.authDao = authDao;
        this.properties = properties;
    }

    /** 给连接加认证；返回人可读的认证描述（不含密钥），写进调用日志方便排错 */
    public String apply(HttpURLConnection conn, String authCode) throws Exception {
        if (authCode == null || authCode.trim().isEmpty()) {
            return null;
        }
        RuleHttpAuth auth = authDao.findByCode(authCode);
        if (auth == null) {
            throw new HttpConfigException("认证配置不存在: " + authCode);
        }
        if (auth.getStatus() != null && auth.getStatus() != 1) {
            throw new HttpConfigException("认证配置已停用: " + authCode);
        }
        String type = auth.getAuthType() == null ? "NONE" : auth.getAuthType().toUpperCase();
        switch (type) {
            case "NONE":
                return "NONE";
            case "BEARER": {
                String token = secret(auth.getTokenRef());
                conn.setRequestProperty("Authorization", "Bearer " + token);
                return "BEARER(静态token)";
            }
            case "BASIC": {
                String raw = auth.getUsername() + ":" + secret(auth.getPasswordRef());
                conn.setRequestProperty("Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
                return "BASIC(" + auth.getUsername() + ")";
            }
            case "HEADER": {
                conn.setRequestProperty(auth.getHeaderName(), secret(auth.getHeaderValueRef()));
                return "HEADER(" + auth.getHeaderName() + ")";
            }
            case "JWT_HS256": {
                String jwt = JwtSupport.sign(secret(auth.getJwtSecretRef()),
                        JwtSupport.claims(auth.getJwtIssuer(), auth.getJwtSubject(), auth.getJwtAudience(),
                                auth.getJwtTtlSeconds() == null ? 300 : auth.getJwtTtlSeconds(),
                                auth.getJwtClaimsJson()));
                conn.setRequestProperty("Authorization", "Bearer " + jwt);
                return "JWT_HS256(自签)";
            }
            case "OAUTH2_CC": {
                String token = accessToken(auth);
                conn.setRequestProperty("Authorization", "Bearer " + token);
                return "OAUTH2_CC(client_credentials)";
            }
            default:
                throw new HttpConfigException("不支持的认证类型: " + type
                        + "（白名单：NONE/BEARER/BASIC/HEADER/JWT_HS256/OAUTH2_CC）");
        }
    }

    /** OAuth2 client_credentials 取 token（带缓存，快到期的重新取） */
    private String accessToken(RuleHttpAuth auth) throws Exception {
        CachedToken cached = tokenCache.get(auth.getAuthCode());
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            return cached.token;
        }
        String base = domain(auth.getOauthTokenDomainKey());
        String url = base + (auth.getOauthTokenPath().startsWith("/") ? "" : "/") + auth.getOauthTokenPath();
        String clientSecret = secret(auth.getOauthClientSecretRef());

        StringBuilder form = new StringBuilder();
        form.append("grant_type=client_credentials");
        form.append("&client_id=").append(enc(auth.getOauthClientId()));
        form.append("&client_secret=").append(enc(clientSecret));
        if (auth.getOauthScope() != null && !auth.getOauthScope().isEmpty()) {
            form.append("&scope=").append(enc(auth.getOauthScope()));
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(properties.getDefaultTimeoutMs());
        conn.setReadTimeout(properties.getDefaultTimeoutMs());
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(form.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = is == null ? "" : readAll(is);
        conn.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("OAuth2 取 token 失败 HTTP " + status + ": " + body);
        }
        JsonNode node = mapper.readTree(body);
        JsonNode tokenNode = node.get("access_token");
        if (tokenNode == null && node.has("data")) {
            tokenNode = node.get("data").get("access_token");
        }
        if (tokenNode == null || tokenNode.asText().isEmpty()) {
            throw new IllegalStateException("OAuth2 响应里没有 access_token: " + body);
        }
        int expiresIn = node.has("expires_in") ? node.get("expires_in").asInt(300) : 300;
        long expiresAt = System.currentTimeMillis() + Math.max(expiresIn - 30, 10) * 1000L;
        tokenCache.put(auth.getAuthCode(), new CachedToken(tokenNode.asText(), expiresAt));
        log.info("OAuth2 取到 token 并缓存：auth={} expiresIn={}s", auth.getAuthCode(), expiresIn);
        return tokenNode.asText();
    }

    /** 从密钥库取密钥明文；缺失直接报明确错误（不静默降级） */
    public String secret(String ref) {
        if (ref == null || ref.trim().isEmpty()) {
            throw new HttpConfigException("未配置密钥引用名（请检查认证/加解密配置）");
        }
        String value = properties.getSecrets() == null ? null : properties.getSecrets().get(ref.trim());
        if (value == null || value.isEmpty()) {
            throw new HttpConfigException("密钥未配置：rule-http.secrets." + ref.trim()
                    + "（库里只存引用名，真值放 application.yml / Nacos / K8s Secret）");
        }
        return value;
    }

    private String domain(String domainKey) {
        String base = properties.getDomains() == null ? null : properties.getDomains().get(domainKey);
        if (base == null || base.isEmpty()) {
            throw new IllegalStateException("域名未配置：rule-http.domains." + domainKey);
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 让外部（配置变更时）能清缓存 */
    public void clearTokenCache() {
        tokenCache.clear();
    }

    private static class CachedToken {
        private final String token;
        private final long expiresAt;

        private CachedToken(String token, long expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }
}
