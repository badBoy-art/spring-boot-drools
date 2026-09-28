package com.example.drools.controller;

import com.example.drools.dao.AssetRefDao;
import com.example.drools.dao.RuleHttpActionDao;
import com.example.drools.dao.RuleHttpAuthDao;
import com.example.drools.entity.RuleHttpAction;
import com.example.drools.entity.RuleHttpActionReturn;
import com.example.drools.entity.RuleHttpAuth;
import com.example.drools.entity.RuleHttpCallLog;
import com.example.drools.http.CryptoSupport;
import com.example.drools.http.HttpActionGateway;
import com.example.drools.http.HttpAuthSupport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 动作配置接口（页面"HTTP 动作"那一块）：
 *   配域名键/路径/入参模板/返回值映射 -> 试调用（不经过规则，先确认接口通）-> 规则里引用 action_code
 *
 * 入参模板支持三种写法（满足"常量 / 对象属性 / 计算值"）：
 *   {"channel":"规则引擎"}                    常量
 *   {"bizId":"${orderId}"}                    对象属性（引号内 → 字符串）
 *   {"score":${totalAmount * 0.01}}           计算结果（表达式，按 JSON 数字输出）
 */
@RestController
@RequestMapping("/rule/http")
public class HttpActionController {

    private final RuleHttpActionDao dao;
    private final RuleHttpAuthDao authDao;
    private final HttpActionGateway gateway;
    private final HttpAuthSupport authSupport;
    private final AssetRefDao refDao;

    public HttpActionController(RuleHttpActionDao dao, RuleHttpAuthDao authDao, HttpActionGateway gateway,
                               HttpAuthSupport authSupport, AssetRefDao refDao) {
        this.dao = dao;
        this.authDao = authDao;
        this.gateway = gateway;
        this.authSupport = authSupport;
        this.refDao = refDao;
    }

    /** 动作列表（含返回值映射，页面直接用） */
    @GetMapping("/actions")
    public List<Map<String, Object>> actions() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (RuleHttpAction action : dao.findAll()) {
            result.add(toView(action));
        }
        return result;
    }

    private Map<String, Object> toView(RuleHttpAction action) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("actionCode", action.getActionCode());
        view.put("actionName", action.getActionName());
        view.put("actionCategory", action.getActionCategory());
        view.put("docCode", action.getDocCode());
        view.put("method", action.getMethod());
        view.put("domainKey", action.getDomainKey());
        view.put("path", action.getPath());
        view.put("headersJson", action.getHeadersJson());
        view.put("bodyTemplate", action.getBodyTemplate());
        view.put("timeoutMs", action.getTimeoutMs());
        view.put("status", action.getStatus());
        view.put("authCode", action.getAuthCode());
        view.put("reqEncrypt", action.getReqEncrypt());
        view.put("respDecrypt", action.getRespDecrypt());
        view.put("signType", action.getSignType());
        view.put("signPlace", action.getSignPlace());
        view.put("signField", action.getSignField());
        view.put("returns", dao.findReturns(action.getActionCode()));
        return view;
    }

    @GetMapping("/action/{actionCode}")
    public Map<String, Object> action(@PathVariable String actionCode) {
        RuleHttpAction action = dao.findByCode(actionCode);
        if (action == null) {
            throw new IllegalArgumentException("动作不存在: " + actionCode);
        }
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("action", action);
        view.put("returns", dao.findReturns(actionCode));
        return view;
    }

    /** 新增/修改动作配置 */
    @PostMapping("/action/save")
    public Object save(@RequestBody RuleHttpAction action) {
        if (action.getActionCode() == null || action.getActionCode().trim().isEmpty()) {
            throw new IllegalArgumentException("动作码(actionCode)必填");
        }
        if (action.getPath() == null || action.getPath().trim().isEmpty()) {
            throw new IllegalArgumentException("接口路径(path)必填");
        }
        if (action.getDomainKey() == null || action.getDomainKey().trim().isEmpty()) {
            throw new IllegalArgumentException("域名配置键(domainKey)必填，域名本身配在 rule-http.domains 下");
        }
        // 认证配置必须存在
        if (action.getAuthCode() != null && !action.getAuthCode().trim().isEmpty()
                && authDao.findByCode(action.getAuthCode()) == null) {
            throw new IllegalArgumentException("认证配置不存在: " + action.getAuthCode()
                    + "（请先在 rule_http_auth 里配置，或改用 /rule/http/auth/save）");
        }
        // 算法白名单：不允许把算法配成任意值（避免 ECB 等弱算法）
        if (!CryptoSupport.isSupportedCrypto(action.getReqEncrypt())) {
            throw new IllegalArgumentException("不支持的请求加密算法: " + action.getReqEncrypt() + "（AES_CBC / AES_GCM / NONE）");
        }
        if (!CryptoSupport.isSupportedCrypto(action.getRespDecrypt())) {
            throw new IllegalArgumentException("不支持的响应解密算法: " + action.getRespDecrypt() + "（AES_CBC / AES_GCM / NONE）");
        }
        if (!CryptoSupport.isSupportedSign(action.getSignType())
                && !"MD5_SALT".equalsIgnoreCase(action.getSignType())) {
            throw new IllegalArgumentException("不支持的签名算法: " + action.getSignType() + "（HMAC_SHA256 / MD5 / MD5_SALT / NONE）");
        }
        dao.upsert(action);
        return dao.findByCode(action.getActionCode());
    }

    /** 认证配置列表（不返回任何密钥，只有引用名） */
    @GetMapping("/auths")
    public List<Map<String, Object>> auths() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (RuleHttpAuth auth : authDao.findAll()) {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("authCode", auth.getAuthCode());
            view.put("authName", auth.getAuthName());
            view.put("authType", auth.getAuthType());
            view.put("tokenRef", auth.getTokenRef());
            view.put("username", auth.getUsername());
            view.put("passwordRef", auth.getPasswordRef());
            view.put("headerName", auth.getHeaderName());
            view.put("headerValueRef", auth.getHeaderValueRef());
            view.put("jwtSecretRef", auth.getJwtSecretRef());
            view.put("jwtIssuer", auth.getJwtIssuer());
            view.put("jwtAudience", auth.getJwtAudience());
            view.put("jwtTtlSeconds", auth.getJwtTtlSeconds());
            view.put("oauthTokenDomainKey", auth.getOauthTokenDomainKey());
            view.put("oauthTokenPath", auth.getOauthTokenPath());
            view.put("oauthClientId", auth.getOauthClientId());
            view.put("oauthClientSecretRef", auth.getOauthClientSecretRef());
            view.put("oauthScope", auth.getOauthScope());
            view.put("status", auth.getStatus());
            view.put("remark", auth.getRemark());
            result.add(view);
        }
        return result;
    }

    /** 新增/修改认证配置（只填密钥【引用名】，真值放 rule-http.secrets） */
    @PostMapping("/auth/save")
    public Object saveAuth(@RequestBody RuleHttpAuth auth) {
        if (auth.getAuthCode() == null || auth.getAuthCode().trim().isEmpty()) {
            throw new IllegalArgumentException("认证码(authCode)必填");
        }
        String type = auth.getAuthType() == null ? "" : auth.getAuthType().toUpperCase();
        if (!Arrays.asList("NONE", "BEARER", "BASIC", "HEADER", "JWT_HS256", "OAUTH2_CC").contains(type)) {
            throw new IllegalArgumentException("不支持的认证类型: " + auth.getAuthType()
                    + "（NONE/BEARER/BASIC/HEADER/JWT_HS256/OAUTH2_CC）");
        }
        auth.setAuthType(type);
        authDao.upsert(auth);
        authSupport.clearTokenCache();
        return authDao.findByCode(auth.getAuthCode());
    }

    @PostMapping("/action/{actionCode}/scopes")
    public ResponseEntity<?> saveScopes(@PathVariable String actionCode, @RequestBody List<String> docCodes) {
        if (dao.findByCode(actionCode) == null) {
            return ResponseEntity.badRequest().body(java.util.Collections.singletonMap("message", "接口不存在: " + actionCode));
        }
        refDao.saveScopes(actionCode, docCodes);
        Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        out.put("actionCode", actionCode);
        out.put("scope", refDao.findScopes(actionCode));
        out.put("message", docCodes == null || docCodes.isEmpty()
                ? "已改为「所有单据通用」（一个接口可被任意单据的规则调用）"
                : "适用范围已更新：" + refDao.findScopes(actionCode));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/action/{actionCode}/scopes")
    public List<String> scopes(@PathVariable String actionCode) {
        return refDao.findScopes(actionCode);
    }

    @PostMapping("/action/{actionCode}/status/{status}")
    public String changeStatus(@PathVariable String actionCode, @PathVariable int status) {
        dao.updateStatus(actionCode, status);
        return "ok";
    }

    /** 覆盖某动作的返回值映射（全量替换） */
    @PostMapping("/action/{actionCode}/returns")
    public Object saveReturns(@PathVariable String actionCode, @RequestBody List<RuleHttpActionReturn> returns) {
        if (dao.findByCode(actionCode) == null) {
            throw new IllegalArgumentException("动作不存在: " + actionCode);
        }
        dao.deleteReturns(actionCode);
        if (returns != null) {
            for (RuleHttpActionReturn mapping : returns) {
                mapping.setActionCode(actionCode);
                dao.upsertReturn(mapping);
            }
        }
        return dao.findReturns(actionCode);
    }

    /**
     * 试调用：直接用一份上下文调接口，看请求/响应/回填结果（不经过规则引擎）。
     * body 就是要渲染的上下文，例如 {"orderId":"T-1","totalAmount":6000,"customer":{"level":"VIP"}}
     */
    @PostMapping("/action/{actionCode}/test")
    public Map<String, Object> test(@PathVariable String actionCode, @RequestBody Map<String, Object> ctx) {
        long start = System.currentTimeMillis();
        Map<String, Object> mapped = gateway.invokeWithContext(actionCode, ctx, null);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("actionCode", actionCode);
        result.put("renderedBody", gateway.renderTemplate(dao.findByCode(actionCode).getBodyTemplate(), ctx));
        result.put("mapped", mapped);
        result.put("costMs", System.currentTimeMillis() - start);
        return result;
    }

    /** 调用日志（规则里真实调的记录） */
    @GetMapping("/logs")
    public List<RuleHttpCallLog> logs(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        return dao.findLogs(limit);
    }
}
