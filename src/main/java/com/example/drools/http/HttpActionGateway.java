package com.example.drools.http;

import com.example.drools.dao.RuleHttpActionDao;
import com.example.drools.domain.DocFact;
import com.example.drools.domain.RuleFact;
import com.example.drools.entity.RuleHttpAction;
import com.example.drools.entity.RuleHttpActionReturn;
import com.example.drools.entity.RuleHttpCallLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mvel2.MVEL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则里的 HTTP 动作网关（替代原先的 Feign 方案：不再需要引各业务线的 SDK）。
 *
 * 规则模板里一行就能调：
 *     httpActionGateway.invoke("RISK_CHECK", $o);
 *
 * 它负责四件事：
 *   1. 入参渲染：body_template 支持 常量 / ${字段} / ${表达式}（MVEL 求值）
 *        - 写在引号里 → 按字符串输出：  "level":"${customer.level}"
 *        - 不写引号   → 按 JSON 字面量输出（数字/布尔/对象/数组）： "amount":${totalAmount}, "score":${totalAmount * 0.01}
 *   2. 发请求：域名取自配置（rule-http.domains.<domainKey>，各环境不同），域名不落在规则参数里；
 *      GET 把渲染结果拼成 query，其余方法作为 JSON body。
 *   3. 返回值回填：按 rule_http_action_return 把响应 JSON 路径写进单据 ext（后续规则用 ext["xxx"] 读），
 *      并写一个调用标记 ext[actionCode]（模板用它做状态守卫，保证同一单据同一动作只调一次、避免死循环）。
 *   4. 落调用日志（request/response/耗时/成败），页面可回看。
 */
@Component
public class HttpActionGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpActionGateway.class);

    /** ${表达式}：模板里唯一允许的动态语法 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");

    private final RuleHttpActionDao actionDao;
    private final RuleHttpProperties properties;
    private final HttpAuthSupport authSupport;
    private final DomainResolver domainResolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpActionGateway(RuleHttpActionDao actionDao, RuleHttpProperties properties, HttpAuthSupport authSupport,
                             DomainResolver domainResolver) {
        this.actionDao = actionDao;
        this.properties = properties;
        this.authSupport = authSupport;
        this.domainResolver = domainResolver;
    }

    /** 规则 RHS 调用的入口：fact 是当前单据对象（Order 等，实现 RuleFact） */
    public Map<String, Object> invoke(String actionCode, Object fact) {
        return invoke(actionCode, fact, null);
    }

    /**
     * 带「步骤级入参覆写」的调用：规则/步骤上配的参数直接盖掉接口注册里的同名字段 ——
     * 运营在规则上下就能给这一步的接口传参，不用回②改模板。
     * overridesJson 例：{"level":"${ext.approvalLevel}","bizId":"${orderId}"}（值支持 ${...} 占位符与表达式）
     */
    public Map<String, Object> invoke(String actionCode, Object fact, String overridesJson) {
        Map<String, Object> ctx = toContext(fact);
        return invokeWithContext(actionCode, ctx, fact instanceof RuleFact ? (RuleFact) fact : null, overridesJson);
    }

    /**
     * 带上下文的调用：页面"试调用"和单元测试走这里（可以直接给一份上下文，不一定有单据对象）。
     * 返回：回填到 ext 的字段集合（键 = target_field）。
     */
    public Map<String, Object> invokeWithContext(String actionCode, Map<String, Object> ctx, RuleFact fact) {
        return invokeWithContext(actionCode, ctx, fact, null);
    }

    public Map<String, Object> invokeWithContext(String actionCode, Map<String, Object> ctx, RuleFact fact, String overridesJson) {
        RuleHttpAction action = actionDao.findByCode(actionCode);
        if (action == null) {
            throw new HttpConfigException("HTTP 动作不存在: " + actionCode);
        }
        if (action.getStatus() != null && action.getStatus() != 1) {
            throw new HttpConfigException("HTTP 动作已停用: " + actionCode);
        }

        String url = buildUrl(action);
        String requestBody = null;
        String signHeader = null;
        Map<String, Object> mapped = new LinkedHashMap<String, Object>();
        long start = System.currentTimeMillis();
        RuleHttpCallLog callLog = new RuleHttpCallLog();
        callLog.setActionCode(actionCode);
        callLog.setDocCode(action.getDocCode());
        callLog.setBizId(asText(ctx.get("orderId")));

        try {
            requestBody = renderTemplate(mergeOverrides(action.getBodyTemplate(), overridesJson), ctx);
            String method = action.getMethod() == null ? "POST" : action.getMethod().toUpperCase();
            // 参数位置：AUTO（GET/DELETE=query、POST/PUT=body）/ QUERY（一律拼 URL）/ BODY（一律放请求体）
            boolean toQuery = "QUERY".equalsIgnoreCase(paramIn(action))
                    || ("AUTO".equalsIgnoreCase(paramIn(action)) && ("GET".equals(method) || "DELETE".equals(method)));
            if (toQuery && requestBody != null && !requestBody.trim().isEmpty()) {
                url = url + (url.contains("?") ? "&" : "?") + toQueryString(requestBody);
                requestBody = null;
            } else {
                // 请求体：先按需加密，再按需签名（签名对象是最终发出的 body）
                ProtectedBody protectedBody = protectRequestBody(action, requestBody);
                requestBody = protectedBody.body;
                signHeader = protectedBody.signHeader;
            }
            callLog.setRequestUrl(url);
            callLog.setRequestBody(requestBody);

            String headers = renderTemplate(action.getHeadersJson(), ctx);            // 请求头也支持 ${...}（调用时用规则上下文注入）
            String headerOverride = headerOverrides(overridesJson);                   // 步骤里可用 _headers 覆写/追加请求头
            if (headerOverride != null) {
                headers = mergeRawJson(headers, renderOverrides(headerOverride, ctx));
            }
            HttpResult result = send(action, url, method, requestBody, signHeader, headers);
            callLog.setResponseBody(result.body);
            callLog.setSuccess(result.success);
            if (!result.success) {
                callLog.setError("HTTP " + result.status);
                // fail-fast 打开时，非 2xx 也要抛（不只是连不上/超时）
                if (properties.isFailFast()) {
                    throw new IllegalStateException("HTTP 动作[" + actionCode + "]返回 HTTP " + result.status
                            + "：" + abbreviate(result.body));
                }
            }

            // 响应体：按需解密后再解析/回填（日志里存解密后的内容，方便排错）
            String plainResponse = unprotectResponseBody(action, result.body);
            if (plainResponse != null && !plainResponse.equals(result.body)) {
                callLog.setResponseBody(plainResponse);
            }
            mapped = applyReturns(actionCode, plainResponse, fact);
            markCalled(actionCode, fact, "OK", mapped);
            if (fact != null && !result.success) {
                fact.addRuleMessage("接口[" + actionCode + "]返回 HTTP " + result.status);
            }
            return mapped;
        } catch (Exception e) {
            callLog.setSuccess(false);
            callLog.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
            markCalled(actionCode, fact, "FAIL", mapped);
            if (e instanceof HttpConfigException) {
                // 配置类错误（密钥没配/算法非法/认证不存在）：无论 fail-fast 怎么配都抛出去
                log.error("HTTP 动作[{}]配置错误: {}", actionCode, e.getMessage());
                throw (HttpConfigException) e;
            }
            if (properties.isFailFast()) {
                throw new IllegalStateException("HTTP 动作[" + actionCode + "]调用失败: " + e.getMessage(), e);
            }
            log.warn("HTTP 动作[{}]调用失败: {}", actionCode, e.getMessage());
            if (fact != null) {
                fact.addRuleMessage("接口[" + actionCode + "]调用失败：" + e.getMessage());
            }
            return mapped;
        } finally {
            callLog.setCostMs(System.currentTimeMillis() - start);
            try {
                actionDao.insertLog(callLog);
            } catch (Exception e) {
                log.warn("写 HTTP 调用日志失败: {}", e.getMessage());
            }
        }
    }

    /** 单据对象 -> 表达式上下文：属性全部铺平（totalAmount / customer.level / items.size() 都能直接写） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toContext(Object fact) {
        if (fact == null) {
            return new LinkedHashMap<String, Object>();
        }
        Map<String, Object> ctx;
        if (fact instanceof DocFact) {
            // 通用单据：直接用注册的字段值当上下文（${variables.amount} / ${starter.name} 直接写）
            DocFact docFact = (DocFact) fact;
            ctx = new LinkedHashMap<String, Object>(docFact.getData());
            ctx.put("docCode", docFact.getDocCode());
            ctx.put("bizId", docFact.getBizId());
            // 接口回填的 ext 也要能进下一个接口的入参（典型链路：先查审批人 → 再发起审批流带上审批人）
            ctx.put("ext", docFact.getExt());
        } else {
            ctx = mapper.convertValue(fact, Map.class);
        }
        ctx.put("fact", fact);
        return ctx;
    }

    /** 步骤入参里的保留键 _headers：值是一个 JSON 对象，用来在调用时覆写/追加请求头（如动态 token） */
    private String headerOverrides(String overridesJson) {
        if (overridesJson == null || overridesJson.trim().isEmpty()) return null;
        try {
            JsonNode ov = mapper.readTree(overridesJson.replace("$\\{", "${"));
            JsonNode h = ov.get("_headers");
            return (h != null && h.isObject()) ? mapper.writeValueAsString(h) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 渲染一段 JSON 里所有文本值的 ${...}（请求头覆写用；值支持单据字段/ext/表达式） */
    private String renderOverrides(String json, Map<String, Object> ctx) {
        try {
            JsonNode node = mapper.readTree(json);
            com.fasterxml.jackson.databind.node.ObjectNode out = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String raw = e.getValue().asText();
                // 包一层引号再渲染，让 ${...} 按"字符串"输出，然后去掉外层引号
                String v = renderTemplate("\"" + (raw == null ? "" : raw) + "\"", ctx);
                if (v != null && v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1);
                }
                out.put(e.getKey(), v);
            }
            return mapper.writeValueAsString(out);
        } catch (Exception ex) {
            return json;
        }
    }

    /** 两个 JSON 对象合并（后者覆盖同名 key） */
    private String mergeRawJson(String base, String add) {
        if (base == null || base.trim().isEmpty()) return add;
        if (add == null || add.trim().isEmpty()) return base;
        try {
            com.fasterxml.jackson.databind.node.ObjectNode b =
                    (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(base);
            JsonNode a = mapper.readTree(add);
            if (a.isObject()) {
                Iterator<String> names = a.fieldNames();
                while (names.hasNext()) {
                    String k = names.next();
                    b.set(k, a.get(k));
                }
            }
            return mapper.writeValueAsString(b);
        } catch (Exception e) {
            return base;
        }
    }

    /**
     * 把「步骤/规则级的参数绑定」合并进接口的入参模板。
     *
     * 口径（重要）：接口模板里写的是**逻辑参数名** {"bizId":"${bizId}","amount":${amount}}，
     * 规则里只写 ${参数名} = 规则的上下文取值/表达式（如 amount = ${totalAmount * 0.01}）。
     * 合并方式必须是**文本替换**：把 ${参数名} 原样换成规则侧的表达式文本，再由 renderTemplate 用
     * 规则上下文求值。这样模板里的引号/裸写结构保持不变 ——
     *   模板 {"amount":${amount}} + 规则 amount=${totalAmount * 0.01} → ${totalAmount * 0.01} 裸写 → 数字 460.0
     *   （若改成"解析 JSON 后按 key 塞值"，amount 会变成字符串 "460.0"，类型就废了 —— 实测踩过）
     */
    private String mergeOverrides(String template, String overridesJson) {
        if (overridesJson == null || overridesJson.trim().isEmpty() || template == null || template.trim().isEmpty()) {
            return template;
        }
        String json = overridesJson.replace("$\\{", "${");
        try {
            JsonNode ov = mapper.readTree(json);
            if (!ov.isObject()) return template;
            String out = template;
            Iterator<Map.Entry<String, JsonNode>> it = ov.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if ("_headers".equals(e.getKey())) continue;   // 保留键：请求头覆写
                String value = e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString();
                out = out.replace("${" + e.getKey() + "}", value);
            }
            return out;
        } catch (Exception e) {
            return template;
        }
    }

    public String renderTemplate(String template, Map<String, Object> ctx) {
        if (template == null || template.trim().isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        Matcher matcher = PLACEHOLDER.matcher(template);
        int last = 0;
        boolean first = true;
        while (matcher.find()) {
            String before = template.substring(last, matcher.start());
            out.append(before);
            String expr = matcher.group(1).trim();
            Object value = expr.isEmpty() ? null : MVEL.eval(expr, ctx);
            boolean insideQuotes = insideJsonString(template, matcher.start(), first || last > 0);
            out.append(toJsonFragment(value, insideQuotes));
            last = matcher.end();
            first = false;
        }
        out.append(template.substring(last));
        return out.toString();
    }

    /**
     * 判断占位符是否处在 JSON 字符串里（即模板写作 "key":"${x}"）。
     *
     * 注意：只看"当前位置是否在引号内"，不能要求前一个字符恰好是引号 ——
     * 否则 {@code "flowKey":"${docCode}_FLOW_${level}"} 里第二个占位符前面是 '_'，
     * 会被误判成"不在字符串里"，于是按 JSON 片段渲染，拼出 {@code "ORDER_FLOW_"L2""} 这种非法 JSON。
     */
    private boolean insideJsonString(String template, int start, boolean ignore) {
        boolean inString = false;
        for (int i = 0; i < start; i++) {
            char c = template.charAt(i);
            if (c == '"' && (i == 0 || template.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
        }
        return inString;
    }

    private String toJsonFragment(Object value, boolean insideQuotes) throws RuntimeException {
        if (insideQuotes) {
            return value == null ? "" : String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"");
        }
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof CharSequence || value instanceof Character) {
            return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "\"" + String.valueOf(value) + "\"";
        }
    }

    /** GET：渲染出的 JSON -> query 串 */
    private String toQuery(String json) {
        return toQueryString(json);
    }

    /** 参数位置：AUTO / QUERY / BODY（老数据没有该列时按 AUTO） */
    private String paramIn(RuleHttpAction action) {
        String v = action.getParamIn();
        return (v == null || v.trim().isEmpty()) ? "AUTO" : v.trim().toUpperCase();
    }

    /** 请求体 JSON → query string（GET/DELETE 用） */
    private String toQueryString(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            StringBuilder sb = new StringBuilder();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (sb.length() > 0) {
                    sb.append("&");
                }
                sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append("=")
                        .append(URLEncoder.encode(e.getValue().asText(), "UTF-8"));
            }
            return sb.toString();
        } catch (Exception e) {
            try {
                return URLEncoder.encode(json, "UTF-8");
            } catch (Exception ignored) {
                return json;
            }
        }
    }

    private String buildUrl(RuleHttpAction action) {
        String base = domainResolver.resolve(action.getDomainKey());
        String path = action.getPath() == null ? "" : action.getPath();
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return base + path;
    }

    /**
     * 请求体保护（顺序：加密 → 签名）。
     *   - req_encrypt 开启：整体加密，body 变成 {"data":"<Base64 密文>"}
     *   - sign_type 开启：签名对象 = 加密后的 body；HEADER 时签名进请求头（默认 X-Sign），BODY 时插进 JSON 里
     *     （签的是"不含签名字段"的 body，避免自引用）
     */
    private ProtectedBody protectRequestBody(RuleHttpAction action, String body) throws Exception {
        String working = body;
        String signHeader = null;
        if (working == null) {
            return new ProtectedBody(null, null);
        }
        String reqEncrypt = action.getReqEncrypt();
        if (reqEncrypt != null && !reqEncrypt.trim().isEmpty() && !"NONE".equalsIgnoreCase(reqEncrypt)) {
            String cipher = encryptBody(reqEncrypt, action.getReqEncryptKeyRef(), action.getReqEncryptIvRef(), working);
            Map<String, Object> wrapped = new LinkedHashMap<String, Object>();
            wrapped.put("data", cipher);
            working = mapper.writeValueAsString(wrapped);
        }
        String signType = action.getSignType();
        if (signType != null && !signType.trim().isEmpty() && !"NONE".equalsIgnoreCase(signType)) {
            String signedContent = working;
            if ("BODY".equalsIgnoreCase(action.getSignPlace())) {
                // 先对原 body 算签名，再把签名塞进去
                String sign = sign(signType, action.getSignKeyRef(), signedContent);
                Map<String, Object> node = mapper.readValue(working, Map.class);
                node.put(action.getSignField() == null ? "sign" : action.getSignField(), sign);
                working = mapper.writeValueAsString(node);
            } else {
                signHeader = sign(signType, action.getSignKeyRef(), signedContent);
            }
        }
        return new ProtectedBody(working, signHeader);
    }

    /** 响应体保护：配了 resp_decrypt 就解密（支持 {"data":"密文"} 或整段密文两种返回约定） */
    private String unprotectResponseBody(RuleHttpAction action, String responseBody) throws Exception {
        String respDecrypt = action.getRespDecrypt();
        if (respDecrypt == null || respDecrypt.trim().isEmpty() || "NONE".equalsIgnoreCase(respDecrypt)
                || responseBody == null || responseBody.trim().isEmpty()) {
            return responseBody;
        }
        String cipher = responseBody.trim();
        JsonNode node = null;
        try {
            node = mapper.readTree(cipher);
        } catch (Exception ignored) {
            // 不是 JSON，就按整段密文处理
        }
        if (node != null && node.has("data") && node.get("data").isTextual()) {
            cipher = node.get("data").asText();
        }
        return decryptBody(respDecrypt, action.getRespDecryptKeyRef(), action.getRespDecryptIvRef(), cipher);
    }

    /** 加密（把算法/密钥类错误统一转成 HttpConfigException，配置错了不许静默吞掉） */
    private String encryptBody(String algorithm, String keyRef, String ivRef, String plain) {
        try {
            byte[] key = CryptoSupport.resolveKey(authSupport.secret(keyRef));
            return CryptoSupport.encrypt(algorithm, key, fixedIv(ivRef), plain.getBytes(StandardCharsets.UTF_8));
        } catch (HttpConfigException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new HttpConfigException("加密配置错误: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new HttpConfigException("加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密 */
    private String decryptBody(String algorithm, String keyRef, String ivRef, String cipher) {
        try {
            byte[] key = CryptoSupport.resolveKey(authSupport.secret(keyRef));
            return new String(CryptoSupport.decrypt(algorithm, key, fixedIv(ivRef), cipher), StandardCharsets.UTF_8);
        } catch (HttpConfigException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new HttpConfigException("解密配置错误: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new HttpConfigException("解密失败: " + e.getMessage(), e);
        }
    }

    private byte[] fixedIv(String ivRef) {
        if (ivRef == null || ivRef.trim().isEmpty()) {
            return null;   // 用随机 IV 并前置（安全默认）
        }
        return CryptoSupport.resolveKey(authSupport.secret(ivRef));
    }

    /** 签名：HMAC_SHA256 / MD5 */
    private String sign(String signType, String keyRef, String content) throws Exception {
        if ("HMAC_SHA256".equalsIgnoreCase(signType)) {
            return CryptoSupport.hmacSha256Hex(CryptoSupport.resolveKey(authSupport.secret(keyRef)), content);
        }
        if ("MD5".equalsIgnoreCase(signType)) {
            return CryptoSupport.md5Hex(content);
        }
        if ("MD5_SALT".equalsIgnoreCase(signType)) {
            return CryptoSupport.md5Hex(content + authSupport.secret(keyRef));
        }
        throw new HttpConfigException("不支持的签名算法: " + signType + "（白名单：HMAC_SHA256 / MD5 / MD5_SALT）");
    }

    /** JDK 原生 HttpURLConnection：不引任何 HTTP 客户端依赖，也不引业务方 SDK */
    private HttpResult send(RuleHttpAction action, String url, String method, String body, String signHeader, String headersJson)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        int timeout = action.getTimeoutMs() == null || action.getTimeoutMs() <= 0
                ? properties.getDefaultTimeoutMs() : action.getTimeoutMs();
        conn.setConnectTimeout(timeout);
        conn.setReadTimeout(timeout);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        conn.setRequestProperty("Accept", "application/json");
        if (headersJson != null && !headersJson.trim().isEmpty()) {
            JsonNode headers = mapper.readTree(headersJson);
            Iterator<Map.Entry<String, JsonNode>> it = headers.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                conn.setRequestProperty(e.getKey(), e.getValue().asText());
            }
        }
        if (signHeader != null) {
            // HEADER 位置的签名：头名默认 X-Sign，可用 sign_field 指定
            String headerName = action.getSignField() == null || action.getSignField().trim().isEmpty()
                    || "HEADER".equalsIgnoreCase(action.getSignField())
                    ? "X-Sign" : action.getSignField();
            conn.setRequestProperty(headerName, signHeader);
        }
        // 认证：Bearer / Basic / 自定义头 / JWT 自签 / OAuth2 取 token
        authSupport.apply(conn, action.getAuthCode());

        if (body != null) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = conn.getResponseCode();
        InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String response = is == null ? "" : readAll(is);
        conn.disconnect();
        return new HttpResult(status >= 200 && status < 300, status, response);
    }

    private String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 按配置把响应写回单据 ext，并写过程消息（as_message=1） */
    private Map<String, Object> applyReturns(String actionCode, String responseBody, RuleFact fact) {
        Map<String, Object> mapped = new LinkedHashMap<String, Object>();
        List<RuleHttpActionReturn> mappings = actionDao.findReturns(actionCode);
        if (mappings.isEmpty() || responseBody == null || responseBody.trim().isEmpty()) {
            return mapped;
        }
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (Exception e) {
            log.warn("HTTP 动作[{}]响应不是 JSON，跳过返回值回填: {}", actionCode, e.getMessage());
            return mapped;
        }
        for (RuleHttpActionReturn mapping : mappings) {
            JsonNode node = extract(root, mapping.getRespPath());
            Object value = coerce(node, mapping.getTargetType());
            mapped.put(mapping.getTargetField(), value);
            if (fact != null) {
                fact.getExt().put(mapping.getTargetField(), value);
                if (mapping.getAsMessage() != null && mapping.getAsMessage() == 1) {
                    fact.addRuleMessage("接口返回 " + mapping.getTargetField() + "=" + value);
                }
            }
        }
        return mapped;
    }

    /** 调用标记：ext[actionCode] = OK/FAIL。模板用它做状态守卫（也防重复调用/死循环） */
    private void markCalled(String actionCode, RuleFact fact, String status, Map<String, Object> mapped) {
        if (fact == null) {
            return;
        }
        fact.getExt().put(actionCode, status);
    }

    /** 简单点号路径取值：data.riskLevel；支持数组下标 data.items[0].level */
    public JsonNode extract(JsonNode root, String path) {
        if (path == null || path.trim().isEmpty()) {
            return root;
        }
        JsonNode current = root;
        for (String segment : path.trim().split("\\.")) {
            if (current == null || current.isNull()) {
                return null;
            }
            String name = segment;
            Integer index = null;
            int bracket = segment.indexOf('[');
            if (bracket >= 0 && segment.endsWith("]")) {
                name = segment.substring(0, bracket);
                try {
                    index = Integer.valueOf(segment.substring(bracket + 1, segment.length() - 1));
                } catch (NumberFormatException ignored) {
                    index = null;
                }
            }
            current = name.isEmpty() ? current : current.get(name);
            if (index != null && current != null && current.isArray()) {
                current = current.get(index);
            }
        }
        return current;
    }

    private Object coerce(JsonNode node, String targetType) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String type = targetType == null ? "STRING" : targetType.toUpperCase();
        switch (type) {
            case "NUMBER":
                return node.isIntegralNumber() ? (Object) node.asLong() : (Object) node.asDouble();
            case "DECIMAL":
                return node.asDouble();
            case "BOOL":
                return node.asBoolean();
            default:
                return node.isTextual() ? node.asText() : node.toString();
        }
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private static class HttpResult {
        private final boolean success;
        private final int status;
        private final String body;

        private HttpResult(boolean success, int status, String body) {
            this.success = success;
            this.status = status;
            this.body = body;
        }
    }

    /** 请求体 + 签名头（签名位置为 HEADER 时才有值） */
    private static class ProtectedBody {
        private final String body;
        private final String signHeader;

        private ProtectedBody(String body, String signHeader) {
            this.body = body;
            this.signHeader = signHeader;
        }
    }
}
