package com.example.drools.http;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT(HS256) 自签 / 校验（只用 JDK，不引 jjwt）。
 * 典型场景：对方要求 Authorization: Bearer <你自己签的 JWT>。
 */
public final class JwtSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JwtSupport() {
    }

    /** 用密钥签一个 HS256 JWT */
    public static String sign(String secret, Map<String, Object> claims) throws Exception {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url(MAPPER.writeValueAsString(claims));
        String data = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        return data + "." + signature;
    }

    /** 校验签名并返回 payload（失败抛异常）—— 被调方和单测用它 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> verify(String secret, String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("JWT 格式错误");
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("JWT 签名校验失败");
        }
        return MAPPER.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
    }

    /** 生成 claim 集合：标准字段 + 额外 claims（额外 claims 覆盖标准字段） */
    public static Map<String, Object> claims(String issuer, String subject, String audience, int ttlSeconds,
                                             String extraClaimsJson) throws Exception {
        Map<String, Object> claims = new LinkedHashMap<String, Object>();
        long now = System.currentTimeMillis() / 1000L;
        claims.put("iat", now);
        claims.put("exp", now + Math.max(ttlSeconds, 1));
        if (issuer != null && !issuer.isEmpty()) {
            claims.put("iss", issuer);
        }
        if (subject != null && !subject.isEmpty()) {
            claims.put("sub", subject);
        }
        if (audience != null && !audience.isEmpty()) {
            claims.put("aud", audience);
        }
        if (extraClaimsJson != null && !extraClaimsJson.trim().isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> extra = MAPPER.readValue(extraClaimsJson, Map.class);
            claims.putAll(extra);
        }
        return claims;
    }

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
