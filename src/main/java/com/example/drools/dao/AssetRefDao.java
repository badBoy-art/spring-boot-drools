package com.example.drools.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资产引用反查：单据（①）和接口（②）都是"一处注册、多处调用"的独立资产，
 * 这个 DAO 回答两件事：
 *   1) 这个单据被哪些规则类型 / 规则 / 组合规则表 / 接口引用？
 *   2) 某类资产被哪些单据 / 规则类型 / 规则 / 组合规则表引用？
 * 同时给删除护栏提供"被引用了就别删"的依据。
 *
 * 真实表结构（踩过坑）：单据/接口与规则类型的绑定不是独立列，而是 rule_type_field 里的参数
 *   - field_key = 'docCode'    → default_value 即该类型服务的单据
 *   - field_key = 'actionCode' → default_value 即该类型调用的接口
 * 模板体在 rule_template.template_body（里面是 invoke("ACTION_CODE")）；
 * 规则级绑定在 rule_definition.rule_params（{"docCode":"X","actionCode":"Y"}）；
 * 组合规则表在 rule_combination_table.doc_code + action_json。
 */
@Repository
public class AssetRefDao {

    private final JdbcTemplate jdbc;

    public AssetRefDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全量引用关系：{"docs":{docCode:{...}}, "actions":{actionCode:{...}}} */
    public Map<String, Object> refs() {
        List<Map<String, Object>> docs = jdbc.queryForList(
                "SELECT doc_code, doc_name FROM rule_document ORDER BY doc_code");
        List<Map<String, Object>> types = jdbc.queryForList(
                "SELECT rule_type, type_name FROM rule_type_meta");
        List<Map<String, Object>> tfields = jdbc.queryForList(
                "SELECT rule_type, field_key, IFNULL(default_value,'') dv FROM rule_type_field");
        List<Map<String, Object>> ttpls = jdbc.queryForList(
                "SELECT rule_type, IFNULL(template_body,'') tb FROM rule_template");
        List<Map<String, Object>> rules = jdbc.queryForList(
                "SELECT rule_name, rule_type, IFNULL(rule_params,'') rule_params, IFNULL(drl_content,'') drl_content, status FROM rule_definition");
        List<Map<String, Object>> tables = jdbc.queryForList(
                "SELECT asset_key, asset_name, IFNULL(doc_code,'') doc_code, IFNULL(action_json,'') action_json FROM rule_combination_table");
        List<Map<String, Object>> actions = new java.util.ArrayList<Map<String, Object>>();
        List<Map<String, Object>> scopes = new java.util.ArrayList<Map<String, Object>>();

        Map<String, Set<String>> scopeByAction = new LinkedHashMap<String, Set<String>>();
        for (Map<String, Object> s : scopes) {
            scopeByAction.computeIfAbsent(str(s.get("action_code")), k -> new LinkedHashSet<String>()).add(str(s.get("doc_code")));
        }
        Map<String, Set<String>> docsOfType = new LinkedHashMap<String, Set<String>>();
        Map<String, Set<String>> actionsOfType = new LinkedHashMap<String, Set<String>>();
        Map<String, String> tplOfType = new LinkedHashMap<String, String>();
        for (Map<String, Object> f : tfields) {
            String type = str(f.get("rule_type"));
            String key = str(f.get("field_key"));
            String dv = str(f.get("dv"));
            if (dv.isEmpty()) continue;
            if ("docCode".equalsIgnoreCase(key)) {
                docsOfType.computeIfAbsent(type, k -> new LinkedHashSet<String>()).add(dv);
            } else if ("actionCode".equalsIgnoreCase(key)) {
                actionsOfType.computeIfAbsent(type, k -> new LinkedHashSet<String>()).add(dv);
            }
        }
        for (Map<String, Object> t : ttpls) {
            tplOfType.put(str(t.get("rule_type")), str(t.get("tb")));
        }
        // 单据绑定有两种落库方式，都要认：
        //   a) 参数字段 docCode（通用类型，如 DOC_HTTP_ACTION，模板里写 docCode == "${docCode}"）
        //   b) 模板字面量 docCode == "SKU"（③ 生成器按具体单据生成类型时就是这么落库的）
        for (Map<String, Object> t : ttpls) {
            String type = str(t.get("rule_type"));
            String tb = str(t.get("tb"));
            for (Map<String, Object> d : docs) {
                String doc = str(d.get("doc_code"));
                if (!doc.isEmpty() && tb.contains("docCode == \"" + doc + "\"")) {
                    docsOfType.computeIfAbsent(type, k -> new LinkedHashSet<String>()).add(doc);
                }
            }
        }

        // ---------- 每个接口：适用范围 + 被谁引用 ----------
        Map<String, Object> actionOut = new LinkedHashMap<String, Object>();
        for (Map<String, Object> a : actions) {
            String code = str(a.get("action_code"));
            String mainDoc = str(a.get("doc_code"));
            Set<String> scope = new LinkedHashSet<String>(scopeByAction.getOrDefault(code, new LinkedHashSet<String>()));
            if (!mainDoc.isEmpty()) scope.add(mainDoc);   // 兼容历史单值绑定

            Set<String> usedTypes = new LinkedHashSet<String>();
            Set<String> usedRules = new LinkedHashSet<String>();
            Set<String> usedTables = new LinkedHashSet<String>();
            Set<String> usedDocs = new LinkedHashSet<String>(scope);

            for (Map<String, Object> t : types) {
                String type = str(t.get("rule_type"));
                boolean hit = actionsOfType.getOrDefault(type, new LinkedHashSet<String>()).contains(code)
                        || tplOfType.getOrDefault(type, "").contains("invoke(\"" + code + "\"");
                if (hit) {
                    usedTypes.add(type);
                    usedDocs.addAll(docsOfType.getOrDefault(type, new LinkedHashSet<String>()));
                }
            }
            for (Map<String, Object> r : rules) {
                if (refAction(r, code)) {
                    usedRules.add(str(r.get("rule_name")));
                    String t = str(r.get("rule_type"));
                    if (!t.isEmpty()) {
                        usedTypes.add(t);
                        usedDocs.addAll(docsOfType.getOrDefault(t, new LinkedHashSet<String>()));
                    }
                }
            }
            for (Map<String, Object> tb : tables) {
                if (str(tb.get("action_json")).contains("\"" + code + "\"")) {
                    usedTables.add(str(tb.get("asset_key")));
                    String d = str(tb.get("doc_code"));
                    if (!d.isEmpty()) usedDocs.add(d);
                }
            }

            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("actionCode", code);
            item.put("actionName", str(a.get("action_name")));
            item.put("universal", scope.isEmpty());
            item.put("scope", new ArrayList<String>(scope));
            item.put("docs", new ArrayList<String>(usedDocs));
            item.put("types", new ArrayList<String>(usedTypes));
            item.put("rules", new ArrayList<String>(usedRules));
            item.put("tables", new ArrayList<String>(usedTables));
            item.put("refCount", usedDocs.size() + usedTypes.size() + usedRules.size() + usedTables.size());
            actionOut.put(code, item);
        }

        // ---------- 每个单据：被谁引用 ----------
        Map<String, Object> docOut = new LinkedHashMap<String, Object>();
        for (Map<String, Object> d : docs) {
            String doc = str(d.get("doc_code"));
            Set<String> usedTypes = new LinkedHashSet<String>();
            Set<String> usedRules = new LinkedHashSet<String>();
            Set<String> usedTables = new LinkedHashSet<String>();
            Set<String> usedActions = new LinkedHashSet<String>();

            for (Map<String, Object> t : types) {
                if (docsOfType.getOrDefault(str(t.get("rule_type")), new LinkedHashSet<String>()).contains(doc)) {
                    usedTypes.add(str(t.get("rule_type")));
                }
            }
            for (Map<String, Object> r : rules) {
                if (refDocRule(r, doc, usedTypes)) usedRules.add(str(r.get("rule_name")));
            }
            for (Map<String, Object> tb : tables) {
                if (doc.equals(str(tb.get("doc_code")))) usedTables.add(str(tb.get("asset_key")));
            }
            for (Map<String, Object> a : actions) {
                String code = str(a.get("action_code"));
                boolean hit = doc.equals(str(a.get("doc_code")))
                        || scopeByAction.getOrDefault(code, new LinkedHashSet<String>()).contains(doc);
                if (!hit) {
                    for (Map<String, Object> t : types) {
                        String type = str(t.get("rule_type"));
                        if (usedTypes.contains(type) && (actionsOfType.getOrDefault(type, new LinkedHashSet<String>()).contains(code)
                                || tplOfType.getOrDefault(type, "").contains("invoke(\"" + code + "\""))) {
                            hit = true;
                            break;
                        }
                    }
                }
                if (!hit) {
                    for (Map<String, Object> r : rules) {
                        if (usedRules.contains(str(r.get("rule_name"))) && refAction(r, code)) {
                            hit = true;
                            break;
                        }
                    }
                }
                if (!hit) {
                    for (Map<String, Object> tb : tables) {
                        if (usedTables.contains(str(tb.get("asset_key"))) && str(tb.get("action_json")).contains("\"" + code + "\"")) {
                            hit = true;
                            break;
                        }
                    }
                }
                if (hit) usedActions.add(code);
            }

            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("docCode", doc);
            item.put("docName", str(d.get("doc_name")));
            item.put("types", new ArrayList<String>(usedTypes));
            item.put("rules", new ArrayList<String>(usedRules));
            item.put("tables", new ArrayList<String>(usedTables));
            item.put("actions", new ArrayList<String>(usedActions));
            item.put("refCount", usedTypes.size() + usedRules.size() + usedTables.size() + usedActions.size());
            docOut.put(doc, item);
        }

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("docs", docOut);
        out.put("actions", actionOut);
        return out;
    }

    /** 规则是否引用了某个接口：参数里带 actionCode，或 DRL 里 invoke("X") */
    private boolean refAction(Map<String, Object> rule, String code) {
        return str(rule.get("rule_params")).contains("\"actionCode\":\"" + code + "\"")
                || str(rule.get("drl_content")).contains("invoke(\"" + code + "\"");
    }

    /** 规则是否引用了某个单据：参数里带 docCode，或所属类型属于该单据，或 DRL 里判定该单据 */
    private boolean refDocRule(Map<String, Object> rule, String doc, Set<String> docTypes) {
        return str(rule.get("rule_params")).contains("\"docCode\":\"" + doc + "\"")
                || docTypes.contains(str(rule.get("rule_type")))
                || str(rule.get("drl_content")).contains("docCode == \"" + doc + "\"");
    }

    /** 删除护栏用：某单据的引用者清单（null=单据不存在） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> docRefs(String docCode) {
        Object item = ((Map<String, Object>) refs().get("docs")).get(docCode);
        return item instanceof Map ? (Map<String, Object>) item : null;
    }

    /** 接口的适用范围（N:N）：空 = 所有单据通用 */
    public List<String> findScopes(String actionCode) {
        return jdbc.queryForList("SELECT doc_code FROM rule_http_action_scope WHERE action_code = ? ORDER BY doc_code",
                String.class, actionCode);
    }

    /** 覆盖写接口适用范围（传空列表 = 改成通用） */
    public void saveScopes(String actionCode, List<String> docCodes) {
        jdbc.update("DELETE FROM rule_http_action_scope WHERE action_code = ?", actionCode);
        if (docCodes == null) return;
        for (String doc : docCodes) {
            if (doc == null || doc.trim().isEmpty()) continue;
            jdbc.update("INSERT IGNORE INTO rule_http_action_scope (action_code, doc_code) VALUES (?, ?)",
                    actionCode, doc.trim());
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
