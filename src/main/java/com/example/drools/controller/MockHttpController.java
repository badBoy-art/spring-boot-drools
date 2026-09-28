package com.example.drools.controller;

import com.example.drools.http.CryptoSupport;
import com.example.drools.http.JwtSupport;
import com.example.drools.http.RuleHttpProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示用的"外部系统"。除了最简的 /risk、/points，还提供了 /secure/risk 用来演示
 * 【Bearer/JWT 认证 + HMAC 签名校验 + 请求 AES 解密 + 响应 AES 加密】的真实双向联调：
 * 规则里配的认证/加密/签名配置，在这里被真实验证（验签失败、解密失败都会 4xx）。
 *
 * 它读的是和规则引擎同一份密钥配置（rule-http.secrets.*），真实项目里对方用他们自己的密钥。
 */
@RestController
@RequestMapping("/mock")
public class MockHttpController {

    private static final List<String> CALLS = Collections.synchronizedList(new ArrayList<String>());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RuleHttpProperties properties;

    public MockHttpController(RuleHttpProperties properties) {
        this.properties = properties;
    }

    @PostMapping("/risk")
    public Map<String, Object> risk(@RequestBody Map<String, Object> body) {
        CALLS.add("POST /mock/risk <- " + body);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("riskLevel", riskLevel(body.get("amount")));
        data.put("score", score(body.get("amount")));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("code", 0);
        result.put("msg", "ok");
        result.put("data", data);
        return result;
    }

    /**
     * 需要认证 + 签名 + 双向加密的接口：
     *   1. Authorization: Bearer <JWT(HS256)> —— 验签，并检查 iss/aud
     *   2. X-Sign: HMAC_SHA256(密钥, 原始请求体) —— 校验签名（签名对象是加密后的 body）
     *   3. 请求体 {"data":"<Base64>"} —— AES 解密（随机 IV 前置）
     *   4. 响应体也用 AES 加密返回 {"data":"<Base64>"}
     */
    @PostMapping("/secure/risk")
    public ResponseEntity<Map<String, Object>> secureRisk(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Sign", required = false) String sign,
            @RequestBody String rawBody) throws Exception {
        try {
            // 1) JWT 校验
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("缺少 Bearer token"));
            }
            String jwt = authorization.substring("Bearer ".length()).trim();
            Map<String, Object> claims = JwtSupport.verify(secret("jwtSecret"), jwt);
            // 2) 签名校验（对原始 body 验签）
            if (sign == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("缺少 X-Sign"));
            }
            String expected = CryptoSupport.hmacSha256Hex(CryptoSupport.resolveKey(secret("hmacKey")), rawBody);
            if (!expected.equalsIgnoreCase(sign)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("签名不匹配"));
            }
            // 3) 请求体解密（随机 IV 前置）
            JsonNode wrapped = MAPPER.readTree(rawBody);
            String cipher = wrapped.has("data") ? wrapped.get("data").asText() : rawBody.trim();
            byte[] plain = CryptoSupport.decrypt("AES_CBC", CryptoSupport.resolveKey(secret("aesKey")), null, cipher);
            JsonNode payload = MAPPER.readTree(new String(plain, StandardCharsets.UTF_8));
            CALLS.add("POST /mock/secure/risk <- 验签通过(JWT sub=" + claims.get("iss") + ") 解密后=" + payload);

            // 4) 组织业务结果并加密返回
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("riskLevel", riskLevel(payload.get("amount")));
            data.put("score", score(payload.get("amount")));
            data.put("verifiedBy", "jwt+sign+aes");
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("code", 0);
            result.put("data", data);

            String encrypted = CryptoSupport.encrypt("AES_CBC", CryptoSupport.resolveKey(secret("aesKey")), null,
                    MAPPER.writeValueAsString(result).getBytes(StandardCharsets.UTF_8));
            Map<String, Object> encryptedResponse = new LinkedHashMap<String, Object>();
            encryptedResponse.put("data", encrypted);
            return ResponseEntity.ok(encryptedResponse);
        } catch (IllegalArgumentException e) {
            CALLS.add("POST /mock/secure/risk <- 拒绝: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(e.getMessage()));
        }
    }

    /** OAuth2 client_credentials 的 token 端点（演示取 token + 缓存） */
    @PostMapping("/oauth/token")
    public Map<String, Object> oauthToken(@RequestParam Map<String, String> form) {
        CALLS.add("POST /mock/oauth/token <- client_id=" + form.get("client_id") + ", scope=" + form.get("scope"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("access_token", "mock-access-token-" + System.currentTimeMillis());
        result.put("token_type", "Bearer");
        result.put("expires_in", 3600);
        return result;
    }

    /** 单据接口：商品同步。演示"业务对象可配置"—— 换单据、换接口，规则侧不用改 */
    @PostMapping("/sku/sync")
    public Map<String, Object> skuSync(@RequestBody Map<String, Object> body) {
        CALLS.add("POST /mock/sku/sync <- " + body);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("skuId", "SKU-ID-" + Math.abs(String.valueOf(body.get("skuCode")).hashCode() % 10000));
        data.put("synced", true);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("code", 0);
        result.put("msg", "sku synced");
        result.put("data", data);
        return result;
    }

    /**
     * 通用回显：既用来验证「GET/DELETE 参数走 query、POST/PUT 参数走 body」，
     * 也把 X- 开头的请求头记进调用记录，用来验证「请求头在调用时可动态注入」。
     */
    @GetMapping("/echo")
    public Map<String, Object> echoGet(@RequestParam Map<String, String> query, javax.servlet.http.HttpServletRequest req) {
        CALLS.add("GET /mock/echo <- query=" + query + headerTrace(req));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("code", 0);
        out.put("data", query);
        out.put("msg", "echo");
        return out;
    }

    @PostMapping("/echo")
    public Map<String, Object> echoPost(@RequestBody(required = false) String body, javax.servlet.http.HttpServletRequest req) {
        CALLS.add("POST /mock/echo <- body=" + body + headerTrace(req));
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("echoBody", body);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("code", 0);
        out.put("data", data);
        out.put("msg", "echo");
        return out;
    }

    /** 记录 X- 开头的请求头（动态请求头验证据此断言） */
    private String headerTrace(javax.servlet.http.HttpServletRequest req) {
        if (req == null) return "";
        StringBuilder sb = new StringBuilder();
        java.util.Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String n = names.nextElement();
            if (n != null && n.toLowerCase().startsWith("x-")) {
                sb.append(" [").append(n).append("=").append(req.getHeader(n)).append("]");
            }
        }
        return sb.toString();
    }

    /**
     * 审批链路演示（一）：查审批人。
     * 入参 level=L2/L1，返回对应级别的审批人 —— 这就是"流程中调接口查数据"的场景。
     * 演示口径：L2 = +2 级领导，L1 = 直属负责人。
     */
    @PostMapping("/flow/approver")
    public Map<String, Object> queryApprover(@RequestBody(required = false) Map<String, Object> body) {
        String level = body == null || body.get("level") == null ? "L1" : String.valueOf(body.get("level"));
        CALLS.add("POST /mock/flow/approver <- " + body);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        if ("L2".equalsIgnoreCase(level)) {
            data.put("approverId", "U-L2-001");
            data.put("approverName", "王总（+2 级领导）");
            data.put("approverLevel", "L2");
        } else {
            data.put("approverId", "U-L1-001");
            data.put("approverName", "李经理（商品负责人）");
            data.put("approverLevel", "L1");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("code", 0);
        result.put("msg", "OK");
        result.put("data", data);
        return result;
    }

    /**
     * 审批链路演示（二）：发起审批流。
     * 入参带着上一步查回来的 approverId —— 这就是"流程中调接口写数据"的场景。
     */
    @PostMapping("/flow/start")
    public Map<String, Object> startFlow(@RequestBody(required = false) Map<String, Object> body) {
        CALLS.add("POST /mock/flow/start <- " + body);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("procInstId", "WF-" + (10000 + CALLS.size()));
        data.put("status", "RUNNING");
        data.put("flowKey", body == null ? null : body.get("flowKey"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("code", 0);
        result.put("msg", "审批流已发起");
        result.put("data", data);
        return result;
    }

    /** 流程引擎接口：启动流程。演示"业务对象可配置"—— 换成流程实例单据、换成流程引擎接口，规则侧代码不变 */
    @PostMapping("/workflow/start")
    public Map<String, Object> workflowStart(@RequestBody Map<String, Object> body) {
        CALLS.add("POST /mock/workflow/start <- " + body);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("procInstId", "WF-" + System.currentTimeMillis() % 100000);
        data.put("status", "RUNNING");
        // 金额大的流程自动跳到"风控复核"节点
        double amount = toDouble(body.get("amount"));
        data.put("approveNode", amount >= 10000 ? "风控复核" : "部门审批");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("code", 0);
        result.put("msg", "workflow started");
        result.put("data", data);
        return result;
    }

    /** GET 方式演示：/mock/points?amount=1000&level=VIP */
    @GetMapping("/points")
    public Map<String, Object> points(@RequestParam Map<String, String> query) {
        CALLS.add("GET /mock/points <- " + query);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("points", (long) (toDouble(query.get("amount")) * 0.1));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("code", 0);
        result.put("data", data);
        return result;
    }

    /** 回读调用记录，用来核对"规则到底调没调、带没带认证/签名" */
    @GetMapping("/calls")
    public List<String> calls() {
        return new ArrayList<String>(CALLS);
    }

    @DeleteMapping("/calls")
    public String clear() {
        CALLS.clear();
        return "ok";
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("code", 401);
        map.put("msg", message);
        return map;
    }

    private String secret(String ref) {
        String value = properties.getSecrets() == null ? null : properties.getSecrets().get(ref);
        if (value == null) {
            throw new IllegalArgumentException("mock 端缺少密钥配置 rule-http.secrets." + ref);
        }
        return value;
    }

    private String riskLevel(Object amount) {
        double value = toDouble(amount);
        return value >= 5000 ? "HIGH" : (value >= 2000 ? "MEDIUM" : "LOW");
    }

    private long score(Object amount) {
        double value = toDouble(amount);
        return value >= 5000 ? 88 : (value >= 2000 ? 66 : 30);
    }

    private double toDouble(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
