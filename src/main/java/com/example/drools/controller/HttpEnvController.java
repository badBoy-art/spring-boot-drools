package com.example.drools.controller;

import com.example.drools.dao.RuleHttpActionDao;
import com.example.drools.http.DomainResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接口注册的配套配置：环境域名 / 接口分类 / 入参模板占位符。
 *   1) 不同环境域名不同 → 域名键 × 环境 的真值表，页面直接维护；当前环境由 rule-http.env 决定
 *   2) 接口分类可自助添加 → 下拉从库里取
 *   3) 入参模板的占位符（{"key":"${value}"}）→ 规则/步骤里逐项绑定"这个参数取规则的哪个上下文值"
 */
@RestController
@RequestMapping("/rule/http")
public class HttpEnvController {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.\\u4e00-\\u9fa5]+)}");

    private final DomainResolver domainResolver;
    private final RuleHttpActionDao actionDao;
    private final JdbcTemplate jdbc;

    public HttpEnvController(DomainResolver domainResolver, RuleHttpActionDao actionDao, JdbcTemplate jdbc) {
        this.domainResolver = domainResolver;
        this.actionDao = actionDao;
        this.jdbc = jdbc;
    }

    // ---------------- 环境域名 ----------------

    /** 域名键 × 环境的全量表 + 当前环境 */
    @GetMapping("/domains")
    public Map<String, Object> domains() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("currentEnv", domainResolver.currentEnv());
        out.put("domains", domainResolver.list());
        return out;
    }

    /** 保存某个「域名键 + 环境」的真值 */
    @PostMapping("/domain/save")
    public ResponseEntity<?> saveDomain(@RequestBody Map<String, Object> body) {
        domainResolver.save(str(body.get("domainKey")), str(body.get("env")), str(body.get("baseUrl")), str(body.get("remark")));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("message", "域名已保存：" + str(body.get("domainKey")) + " @ " + str(body.get("env")) + " → " + str(body.get("baseUrl")));
        out.put("domains", domainResolver.list());
        return ResponseEntity.ok(out);
    }

    // ---------------- 接口分类 ----------------

    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        return jdbc.queryForList("SELECT category_name, sort_order FROM rule_http_category ORDER BY sort_order, id");
    }

    @PostMapping("/category/save")
    public ResponseEntity<?> saveCategory(@RequestBody Map<String, Object> body) {
        String name = str(body.get("categoryName"));
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(simple("分类名必填"));
        }
        int sort = 50;
        try {
            if (body.get("sortOrder") != null) sort = Integer.parseInt(str(body.get("sortOrder")));
        } catch (NumberFormatException ignore) {
            // 排序填错就按默认
        }
        jdbc.update("INSERT INTO rule_http_category (category_name, sort_order) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE sort_order = VALUES(sort_order)", name, sort);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("message", "分类已保存：" + name);
        out.put("categories", categories());
        return ResponseEntity.ok(out);
    }

    // ---------------- 入参模板占位符 ----------------

    /**
     * 这个接口的入参模板里有哪些占位符（如 {"level":"${level}","bizId":"${bizId}"} → [level, bizId]）。
     * 规则/步骤里就按这些占位符逐个指定"取规则的哪个上下文值"。
     */
    @GetMapping("/action/{actionCode}/params")
    public ResponseEntity<?> params(@PathVariable String actionCode) {
        com.example.drools.entity.RuleHttpAction action = actionDao.findByCode(actionCode);
        if (action == null) {
            return ResponseEntity.badRequest().body(simple("接口不存在: " + actionCode));
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Matcher m = PLACEHOLDER.matcher(action.getBodyTemplate() == null ? "" : action.getBodyTemplate());
        java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
        while (m.find()) {
            String key = m.group(1);
            if (key.startsWith("ext.") || !seen.add(key)) continue;   // ${ext.x} 是上游回填引用，不用规则再绑
            Map<String, Object> one = new LinkedHashMap<String, Object>();
            one.put("name", key);
            one.put("suggest", suggest(key));
            out.add(one);
        }
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        res.put("actionCode", actionCode);
        res.put("method", action.getMethod());
        res.put("paramIn", action.getParamIn() == null ? "AUTO" : action.getParamIn());
        res.put("bodyTemplate", action.getBodyTemplate());
        res.put("headersJson", action.getHeadersJson());
        res.put("placeholders", out);
        return ResponseEntity.ok(res);
    }

    /** 给个常见取值的建议：同名单据字段直接用它，bizId/orderId 之类取 bizId */
    private String suggest(String name) {
        if ("bizId".equalsIgnoreCase(name) || "id".equalsIgnoreCase(name)) return "${bizId}";
        if ("docCode".equalsIgnoreCase(name)) return "${docCode}";
        return "${" + name + "}";
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private Map<String, Object> simple(String msg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("error", "参数/规则校验失败");
        m.put("message", msg);
        return m;
    }
}
