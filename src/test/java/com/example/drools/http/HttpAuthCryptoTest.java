package com.example.drools.http;

import com.example.drools.dao.RuleHttpActionDao;
import com.example.drools.dao.RuleHttpAuthDao;
import com.example.drools.domain.Customer;
import com.example.drools.domain.Order;
import com.example.drools.domain.OrderItem;
import com.example.drools.domain.Product;
import com.example.drools.entity.RuleHttpAction;
import com.example.drools.entity.RuleHttpActionReturn;
import com.example.drools.entity.RuleHttpAuth;
import com.example.drools.entity.RuleHttpCallLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 认证（Bearer / Basic / 自定义头 / JWT-HS256 / OAuth2）与报文加解密签名（AES-CBC / AES-GCM / HMAC）的验证。
 * 全程本机 HttpServer 起桩，桩里做真实验签/解密，密钥用测试密钥库（等价 application.yml 的 rule-http.secrets）。
 */
class HttpAuthCryptoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SEEN_AUTH = new CopyOnWriteArrayList<String>();
    private static final List<String> SEEN_BODY = new CopyOnWriteArrayList<String>();
    private static final AtomicInteger TOKEN_CALLS = new AtomicInteger();

    private static HttpServer server;
    private static final String TOKEN = "oauth2-access-token-abc";

    /** 从测试用的密钥库里取（和 HttpActionFlowTest.secrets() 保持一致） */
    private static String secret(String ref) {
        return HttpActionFlowTest.secrets().get(ref);
    }

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            String body = readAllQuiet(exchange.getRequestBody());
            SEEN_AUTH.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            SEEN_AUTH.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Api-Key")));
            SEEN_BODY.add(body);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String apiKey = exchange.getRequestHeaders().getFirst("X-Api-Key");

            // JWT 场景：桩端真实验签+校验 iss/aud
            if (auth != null && auth.startsWith("Bearer ") && !auth.contains(TOKEN)
                    && auth.indexOf('.') > 0) {
                try {
                    Map<String, Object> claims = JwtSupport.verify(secret("jwtSecret"), auth.substring(7));
                    if (!"rule-engine".equals(claims.get("iss")) || !"risk-sys".equals(claims.get("aud"))
                            || !"demo".equals(claims.get("tenant"))) {
                        respond(exchange, 401, "{\"code\":401,\"msg\":\"claims 不符\"}");
                        return;
                    }
                    respond(exchange, 200, "{\"code\":0,\"data\":{\"riskLevel\":\"HIGH\",\"jwtOk\":true}}");
                    return;
                } catch (Exception e) {
                    respond(exchange, 401, "{\"code\":401,\"msg\":\"验签失败\"}");
                    return;
                }
            }
            if (auth != null && auth.startsWith("Basic ")) {
                String decoded = new String(java.util.Base64.getDecoder().decode(auth.substring(6)),
                        StandardCharsets.UTF_8);
                respond(exchange, 200, "{\"code\":0,\"data\":{\"riskLevel\":\"LOW\",\"basicUser\":\"" + decoded + "\"}}");
                return;
            }
            if (apiKey != null) {
                respond(exchange, 200, "{\"code\":0,\"data\":{\"riskLevel\":\"MEDIUM\",\"apiKey\":\"" + apiKey + "\"}}");
                return;
            }
            if (auth != null && auth.endsWith(TOKEN)) {
                respond(exchange, 200, "{\"code\":0,\"data\":{\"riskLevel\":\"LOW\",\"oauth\":true}}");
                return;
            }
            respond(exchange, 200, "{\"code\":0,\"data\":{\"riskLevel\":\"LOW\"}}");
        });

        // 需要验签 + 解密请求 + 加密响应的接口
        server.createContext("/secure2", exchange -> {
            String rawBody = readAllQuiet(exchange.getRequestBody());
            SEEN_BODY.add(rawBody);
            SEEN_AUTH.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Sign")));
            String sign = exchange.getRequestHeaders().getFirst("X-Sign");
            String algo = exchange.getRequestHeaders().getFirst("X-Algo");
            try {
                String expected = CryptoSupport.hmacSha256Hex(CryptoSupport.resolveKey(secret("hmacKey")), rawBody);
                if (sign == null || !expected.equalsIgnoreCase(sign)) {
                    respond(exchange, 401, "{\"code\":401,\"msg\":\"签名不匹配\"}");
                    return;
                }
                // 解密（GCM 与 CBC 都支持；IV 都是随机前置）
                String cipher = MAPPER.readTree(rawBody).get("data").asText();
                String plain = new String(CryptoSupport.decrypt(algo, CryptoSupport.resolveKey(secret("aesKey")),
                        null, cipher), StandardCharsets.UTF_8);
                SEEN_BODY.add(plain);
                String response = "{\"code\":0,\"data\":{\"riskLevel\":\"HIGH\",\"amount\":" + MAPPER.readTree(plain).get("amount") + "}}";
                String encrypted = CryptoSupport.encrypt(algo, CryptoSupport.resolveKey(secret("aesKey")), null,
                        response.getBytes(StandardCharsets.UTF_8));
                respond(exchange, 200, "{\"data\":\"" + encrypted + "\"}");
            } catch (Exception e) {
                respond(exchange, 500, "{\"code\":500,\"msg\":\"" + e.getMessage() + "\"}");
            }
        });

        // OAuth2 token 端点：计数用，验证"取一次就缓存"
        server.createContext("/oauth/token", exchange -> {
            TOKEN_CALLS.incrementAndGet();
            readAllQuiet(exchange.getRequestBody());
            respond(exchange, 200, "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        });
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static int port() {
        return server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readAllQuiet(InputStream is) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private RuleHttpProperties properties() {
        RuleHttpProperties props = new RuleHttpProperties();
        props.setDomains(Collections.singletonMap("local", "http://127.0.0.1:" + port()));
        props.setSecrets(HttpActionFlowTest.secrets());
        props.setDefaultTimeoutMs(2000);
        props.setFailFast(false);
        return props;
    }

    private HttpActionGateway gateway(RuleHttpAction action, RuleHttpAuth auth) {
        // 动作通过 auth_code 引用认证配置 —— 测试里把两者关联起来
        action.setAuthCode(auth == null ? null : auth.getAuthCode());
        RuleHttpProperties props = properties();
        RuleHttpActionDao actionDao = new RuleHttpActionDao(null) {
            @Override
            public RuleHttpAction findByCode(String code) {
                return code.equals(action.getActionCode()) ? action : null;
            }

            @Override
            public List<RuleHttpActionReturn> findReturns(String code) {
                return returnsFor(action);
            }

            @Override
            public void insertLog(RuleHttpCallLog log) {
            }
        };
        RuleHttpAuthDao authDao = HttpActionFlowTest.authDao(auth);
        return new HttpActionGateway(actionDao, props, new HttpAuthSupport(authDao, props), new com.example.drools.http.DomainResolver(null, props));
    }

    private List<RuleHttpActionReturn> returnsFor(RuleHttpAction action) {
        List<RuleHttpActionReturn> list = new ArrayList<RuleHttpActionReturn>();
        RuleHttpActionReturn level = new RuleHttpActionReturn();
        level.setRespPath("data.riskLevel");
        level.setTargetField("riskLevel");
        level.setTargetType("STRING");
        list.add(level);
        return list;
    }

    private RuleHttpAction action(String path, String bodyTemplate) {
        RuleHttpAction a = new RuleHttpAction();
        a.setActionCode("ACT");
        a.setActionName("认证/加密演示");
        a.setDocCode("ORDER");
        a.setMethod("POST");
        a.setDomainKey("local");
        a.setPath(path);
        a.setBodyTemplate(bodyTemplate);
        a.setTimeoutMs(3000);
        a.setStatus(1);
        return a;
    }

    private RuleHttpAuth auth(String type) {
        RuleHttpAuth auth = new RuleHttpAuth();
        auth.setAuthCode("AUTH");
        auth.setAuthName(type);
        auth.setAuthType(type);
        auth.setStatus(1);
        return auth;
    }

    private Order order(double amount) {
        Order order = new Order();
        order.setOrderId("AUTH-1001");
        order.setCustomer(new Customer(1L, "张三", "VIP", "北京", 30, true));
        order.setItems(Collections.singletonList(
                new OrderItem(new Product(1L, "iPhone", "电子产品", amount, 10, true), 1)));
        order.setTotalAmount(amount);
        return order;
    }

    @Test
    @DisplayName("BEARER 静态 token -> Authorization: Bearer <token>")
    void bearerStatic() {
        SEEN_AUTH.clear();
        RuleHttpAuth auth = auth("BEARER");
        auth.setTokenRef("staticToken");
        Map<String, Object> mapped = gateway(action("/echo", "{\"bizId\":\"${orderId}\"}"), auth)
                .invoke("ACT", order(1000));
        assertEquals("LOW", mapped.get("riskLevel"));
        assertTrue(SEEN_AUTH.contains("Bearer test-static-token"), SEEN_AUTH.toString());
    }

    @Test
    @DisplayName("BASIC 用户名密码 -> Authorization: Basic base64(user:pass)")
    void basic() {
        SEEN_AUTH.clear();
        RuleHttpAuth auth = auth("BASIC");
        auth.setUsername("ctp");
        auth.setPasswordRef("password");
        gateway(action("/echo", "{}"), auth).invoke("ACT", order(1000));
        assertTrue(SEEN_AUTH.stream().anyMatch(s -> s.startsWith("Basic ") && !s.endsWith("null")), SEEN_AUTH.toString());
        String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("ctp:pwd-123".getBytes(StandardCharsets.UTF_8));
        assertTrue(SEEN_AUTH.contains(expected), SEEN_AUTH.toString());
    }

    @Test
    @DisplayName("自定义头 -> X-Api-Key: <value>")
    void customHeader() {
        SEEN_AUTH.clear();
        RuleHttpAuth auth = auth("HEADER");
        auth.setHeaderName("X-Api-Key");
        auth.setHeaderValueRef("staticToken");
        gateway(action("/echo", "{}"), auth).invoke("ACT", order(1000));
        assertTrue(SEEN_AUTH.contains("test-static-token"), SEEN_AUTH.toString());
    }

    @Test
    @DisplayName("JWT_HS256 自签 -> 桩端验签通过，且 iss/aud/自定义 claim 都对")
    void jwtHs256() {
        SEEN_AUTH.clear();
        RuleHttpAuth auth = auth("JWT_HS256");
        auth.setJwtSecretRef("jwtSecret");
        auth.setJwtIssuer("rule-engine");
        auth.setJwtAudience("risk-sys");
        auth.setJwtTtlSeconds(120);
        auth.setJwtClaimsJson("{\"tenant\":\"demo\"}");
        Map<String, Object> mapped = gateway(action("/echo", "{\"bizId\":\"${orderId}\"}"), auth)
                .invoke("ACT", order(6000));

        assertEquals("HIGH", mapped.get("riskLevel"), "桩端验签通过才会返回 HIGH");
        String authorization = SEEN_AUTH.stream().filter(s -> s.startsWith("Bearer ") && s.indexOf('.') > 0)
                .findFirst().orElse(null);
        assertNotNull(authorization, "应该带上自签 JWT: " + SEEN_AUTH);
        String jwt = authorization.substring(7);
        assertEquals(3, jwt.split("\\.").length);
    }

    @Test
    @DisplayName("OAuth2 client_credentials -> 取 token 并缓存（第二次调用不再取）")
    void oauth2ClientCredentials() {
        SEEN_AUTH.clear();
        TOKEN_CALLS.set(0);
        RuleHttpAuth auth = auth("OAUTH2_CC");
        auth.setOauthTokenDomainKey("local");
        auth.setOauthTokenPath("/oauth/token");
        auth.setOauthClientId("ctp-client");
        auth.setOauthClientSecretRef("password");
        auth.setOauthScope("risk.read");
        HttpActionGateway gateway = gateway(action("/echo", "{}"), auth);

        gateway.invoke("ACT", order(1000));
        gateway.invoke("ACT", order(1000));

        assertEquals(1, TOKEN_CALLS.get(), "token 应该缓存复用，只取一次");
        assertTrue(SEEN_AUTH.contains("Bearer " + TOKEN), SEEN_AUTH.toString());
    }

    @Test
    @DisplayName("AES_CBC + HMAC 签名：请求加密且签名可被桩端验证，响应解密后回填")
    void aesCbcWithHmac() {
        SEEN_BODY.clear();
        SEEN_AUTH.clear();
        RuleHttpAction action = action("/secure2", "{\"bizId\":\"${orderId}\",\"amount\":${totalAmount}}");
        action.setReqEncrypt("AES_CBC");
        action.setReqEncryptKeyRef("aesKey");
        action.setRespDecrypt("AES_CBC");
        action.setRespDecryptKeyRef("aesKey");
        action.setSignType("HMAC_SHA256");
        action.setSignKeyRef("hmacKey");
        action.setSignPlace("HEADER");
        action.setHeadersJson("{\"X-Algo\":\"AES_CBC\"}");

        Map<String, Object> mapped = gateway(action, null).invoke("ACT", order(6000));

        // 1) 发出去的确实是密文（不是明文 JSON）
        assertTrue(SEEN_BODY.get(0).contains("\"data\":\""), SEEN_BODY.toString());
        assertTrue(!SEEN_BODY.get(0).contains("AUTH-1001"), "请求体不该出现明文");
        // 2) 桩端用同一密钥解密成功，看到的是渲染后的明文
        assertTrue(SEEN_BODY.get(1).contains("\"amount\":6000.0"), SEEN_BODY.toString());
        // 3) 响应被解密并回填
        assertEquals("HIGH", mapped.get("riskLevel"));
    }

    @Test
    @DisplayName("AES_GCM 同样可用（随机 IV 前置）")
    void aesGcm() {
        SEEN_BODY.clear();
        RuleHttpAction action = action("/secure2", "{\"amount\":${totalAmount}}");
        action.setReqEncrypt("AES_GCM");
        action.setReqEncryptKeyRef("aesKey");
        action.setRespDecrypt("AES_GCM");
        action.setRespDecryptKeyRef("aesKey");
        action.setSignType("HMAC_SHA256");
        action.setSignKeyRef("hmacKey");
        action.setSignPlace("HEADER");
        action.setHeadersJson("{\"X-Algo\":\"AES_GCM\"}");

        Map<String, Object> mapped = gateway(action, null).invoke("ACT", order(6000));
        assertEquals("HIGH", mapped.get("riskLevel"));
        assertTrue(SEEN_BODY.get(1).contains("\"amount\":6000.0"), SEEN_BODY.toString());
    }

    @Test
    @DisplayName("签名位置=BODY：签名进 JSON 字段，且签的是不含签名字段的 body")
    void signInBody() {
        SEEN_BODY.clear();
        RuleHttpAction action = action("/echo", "{\"amount\":${totalAmount}}");
        action.setSignType("MD5_SALT");
        action.setSignKeyRef("hmacKey");
        action.setSignPlace("BODY");
        action.setSignField("sign");

        gateway(action, null).invoke("ACT", order(1000));
        String body = SEEN_BODY.get(0);
        assertTrue(body.contains("\"sign\":\""), body);
        String expected = null;
        try {
            expected = CryptoSupport.md5Hex("{\"amount\":1000.0}" + secret("hmacKey"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertTrue(body.contains(expected), "MD5_SALT 签名不匹配: " + body);
    }

    @Test
    @DisplayName("密钥缺失 / 认证类型非法 / 算法非法 -> 抛配置异常（不受 fail-fast 影响）")
    void configErrors() {
        // 密钥缺失：即使 fail-fast=false，也必须抛，不能静默
        RuleHttpAuth auth = auth("BEARER");
        auth.setTokenRef("notConfiguredRef");
        HttpConfigException e1 = assertThrows(HttpConfigException.class,
                () -> gateway(action("/echo", "{}"), auth).invoke("ACT", order(1000)));
        assertTrue(e1.getMessage().contains("密钥未配置"), e1.getMessage());

        // 认证类型非法
        RuleHttpAuth bad = auth("SOMETHING_NEW");
        HttpConfigException e2 = assertThrows(HttpConfigException.class,
                () -> gateway(action("/echo", "{}"), bad).invoke("ACT", order(1000)));
        assertTrue(e2.getMessage().contains("不支持的认证类型"), e2.getMessage());

        // 加密算法非法（比如有人想配 ECB）
        RuleHttpAction ecb = action("/secure2", "{}");
        ecb.setReqEncrypt("AES_ECB");
        ecb.setReqEncryptKeyRef("aesKey");
        HttpConfigException e3 = assertThrows(HttpConfigException.class,
                () -> gateway(ecb, null).invoke("ACT", order(1000)));
        assertTrue(e3.getMessage().contains("不支持的加密算法"), e3.getMessage());
    }

    @Test
    @DisplayName("JWT 内容可自校验：verify 出来的 claims 与配置一致")
    void jwtClaims() throws Exception {
        Map<String, Object> claims = JwtSupport.claims("rule-engine", "order", "risk-sys", 60, "{\"tenant\":\"demo\"}");
        String jwt = JwtSupport.sign(secret("jwtSecret"), claims);
        Map<String, Object> parsed = JwtSupport.verify(secret("jwtSecret"), jwt);
        assertEquals("rule-engine", parsed.get("iss"));
        assertEquals("risk-sys", parsed.get("aud"));
        assertEquals("demo", parsed.get("tenant"));
        JsonNode ignored = MAPPER.readTree("{}");
        assertNotNull(ignored);
        // 换一个密钥应该验不过
        assertThrows(IllegalArgumentException.class, () -> JwtSupport.verify("another-secret", jwt));
    }
}
