package com.example.drools.service;

import com.example.drools.entity.RuleStep;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多步骤规则生成器：把「步骤链」编译成 DRL。
 *
 * 设计要点（这就是"接口返回结果如何进入后续计算"的答案）：
 *  1) 一个步骤 = 一条 DRL 规则，salience 递减（100 - step*5）保证先后；
 *  2) 每步 RHS 第一件事是写 "stepN_done" 标记 + update()，**然后**才调接口 —— at-most-once，
 *     接口失败也不会被反复点火（本项目实测过：用"成功字段"当守卫会刷到 fireAllRules 上限）；
 *  3) LHS 守卫 = 「本步未执行」+「本步引用到的上游产物已就绪」：
 *     如果某步的条件取值或接口入参里出现 ${ext.xxx}，就自动加 ext["xxx"] != null —— 数据没到位不点火，天然串行；
 *  4) 接口的返回值由「② 接口注册」的返回值映射负责回填 ext（如 data.approverId → ext[approverId]），
 *     后续步骤只要引用 ${ext.approverId} 就能拿到上一步查回来的数据参与判断/传参；
 *  5) 每步可配参数：条件取值（stepNValue）、接口动作码（stepNAction）——运营在 ④ 里可以把"这一步调哪个接口"换成别的接口。
 */
@Service
public class RuleStepBuilder {

    private static final Pattern EXT_REF = Pattern.compile("\\$\\{ext\\.([A-Za-z0-9_\\u4e00-\\u9fa5]+)}");


    /** 生成步骤链 DRL：返回 {fields, templateBody, drlPreview, steps(带生成的规则名), variables} */
    public Map<String, Object> build(Map<String, Object> request) {
        String ruleType = str(request.get("ruleType"));
        String docCode = str(request.get("docCode"));
        boolean orderFact = "Order".equalsIgnoreCase(str(request.get("factClass")));
        List<Map<String, Object>> steps = steps(request.get("steps"));

        if (ruleType.isEmpty()) throw new IllegalArgumentException("类型编码(ruleType)必填");
        if (steps.isEmpty()) throw new IllegalArgumentException("至少要有一个步骤");
        if (!orderFact && docCode.isEmpty()) throw new IllegalArgumentException("单据编码(docCode)必填");

        List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>();
        Set<String> availableExt = new LinkedHashSet<String>();   // 上游已回填、后续可引用
        StringBuilder rules = new StringBuilder();
        List<Map<String, Object>> stepViews = new ArrayList<Map<String, Object>>();

        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> raw = steps.get(i);
            int no = i + 1;
            String stepName = defaultIfEmpty(str(raw.get("stepName")), "步骤" + no);
            String condField = str(raw.get("condField"));
            String condOp = defaultIfEmpty(str(raw.get("condOp")), ">=");
            String condType = defaultIfEmpty(str(raw.get("condType")), "NUMBER").toUpperCase();
            String condValue = str(raw.get("condValue"));
            String actionType = defaultIfEmpty(str(raw.get("actionType")), "MSG").toUpperCase();
            String actionCode = str(raw.get("actionCode"));
            String extField = str(raw.get("extField"));
            String extValue = str(raw.get("extValue"));
            String message = str(raw.get("message"));

            String valueKey = "step" + no + "Value";
            String actionKey = "step" + no + "Action";
            String marker;

            // ---------- LHS ----------
            List<String> conds = new ArrayList<String>();
            if (!orderFact) conds.add("docCode == \"" + docCode + "\"");
            // 注意：幂等只看"本步是否执行过"（stepN_done），不要用"接口是否已回填"当守卫 ——
            // 否则同一个接口被两步调用时，第二步会因为 ext[ACTION] != null 而永远不点火。
            Set<String> upstream = new LinkedHashSet<String>();
            Matcher m = EXT_REF.matcher(condValue + "|" + str(raw.get("paramJson")));
            while (m.find()) upstream.add(m.group(1));
            for (String up : upstream) conds.add("ext[\"" + up + "\"] != null");
            if (!condField.isEmpty() && DrlSyntax.needsValue(condOp)) {
                // 条件字段既可以是「已注册字段名」（或 object.field 路径），
                // 也可以**直接写多字段算式**： (售价 - 成本) / 成本  →  (getNumber("售价") - getNumber("成本")) / getNumber("成本")
                String left;
                if (orderFact) {
                    left = DrlSyntax.toFactExpression(condField, true);
                } else if (DrlSyntax.looksLikeExpression(condField) || DrlSyntax.isNumeric(condType)
                        || "NUMBER".equalsIgnoreCase(condType)) {
                    left = DrlSyntax.toFactExpression(condField, false);
                } else {
                    left = "getString(\"" + condField + "\")";
                }
                conds.add(DrlSyntax.render(left, condOp, "${" + valueKey + "}", condType));
            }
            // 幂等标记必须按「规则」隔离：不同规则/不同类型可能作用在同一个单据上，
            // 若都用 step1_done，先点火的规则会把标记占掉，后一条永远不执行（实测踩到）。
            // @RULE@ 在发布时被替换成规则名，于是标记形如 SKU_FLOW_3STEP_HIGH_step1_done。
            marker = "@RULE@_step" + no + "_done";
            conds.add("ext[\"" + marker + "\"] == null");
            String factVar = orderFact ? "$o" : "$d";
            String factType = orderFact ? "Order" : "DocFact";

            // ---------- RHS（顺序很重要：先打标 + update，再动外部世界） ----------
            StringBuilder rhs = new StringBuilder();
            rhs.append("        ").append(factVar).append(".getExt().put(\"").append(marker).append("\", true);\n");
            rhs.append("        update(").append(factVar).append(");\n");
            String defaultMsg;
            if (!"SET_EXT".equals(actionType)) {
                throw new IllegalArgumentException("第 " + no + " 步的动作[" + actionType + "]暂不支持："
                        + "引擎只输出决策，步骤动作目前只有「写单据字段」（打标 / 只加消息 先不开放）");
            }
            if (extField.isEmpty()) throw new IllegalArgumentException("第 " + no + " 步选了「写单据字段」但没填字段名");
            // 写值的类型决定落库形态：字符串加引号、数字裸写（Map<String,Object> 里就是数字，不再是 "121"）
            String valueType = defaultIfEmpty(str(raw.get("extValueType")), "STRING").toUpperCase();
            String literal;
            if ("NUMBER".equals(valueType) || "DOUBLE".equals(valueType)) {
                literal = DrlSyntax.literal(DrlSyntax.normalizePercent(extValue), "DOUBLE");
            } else if ("INT".equals(valueType) || "LONG".equals(valueType)) {
                literal = DrlSyntax.literal(extValue, "INT");
            } else if ("DECIMAL".equals(valueType)) {
                literal = DrlSyntax.literal(extValue, "DECIMAL");
            } else if ("BOOLEAN".equals(valueType)) {
                literal = DrlSyntax.literal(extValue, "BOOLEAN");
            } else if ("EXPR".equals(valueType)) {
                // 写"多字段算式"的结果，如 instantid = (售价 - 成本) * 100
                literal = DrlSyntax.toFactExpression(extValue, orderFact);
                // RHS 里逐字段取值必须带事实变量前缀（$d.getNumber("x")）：裸的 getNumber 只在 LHS 约束里能解析
                // —— 实测踩到：裸写会报 Unable to Analyse Expression（MVEL 解析不出 getNumber）
                if (!orderFact) {
                    literal = literal.replace("getNumber(\"", factVar + ".getNumber(\"")
                            .replace("getString(\"", factVar + ".getString(\"");
                }
            } else {
                literal = "\"" + escape(extValue) + "\"";
            }
            rhs.append("        ").append(factVar).append(".getExt().put(\"").append(extField).append("\", ")
                    .append(literal).append(");\n");
            defaultMsg = "第" + no + "步[" + stepName + "]写入 " + extField + "=" + extValue;
            availableExt.add(extField);
            rhs.append("        ").append(factVar).append(".addRuleMessage(\"")
                    .append(escape(defaultIfEmpty(message, defaultMsg))).append("\");\n");

            if (!condField.isEmpty() && DrlSyntax.needsValue(condOp)) {
                fields.add(field(valueKey, "第" + no + "步 条件取值（" + condField + " " + condOp + "）",
                        // 保留页面声明的类型：CSV 会被 DrlGenerator 转成 DRL 列表（in/not in 要用）
                        defaultIfEmpty(condType, DrlSyntax.isNumeric(condType) ? "NUMBER" : "STRING"),
                        condValue, null, null, null, null, no * 10 + 1));
            }

            rules.append("\nrule \"@RULE@#").append(no).append(" ").append(escape(stepName)).append("\"\n")
                    .append("    salience ").append(100 - no * 5).append("\n")
                    .append("    when\n        ").append(factVar).append(" : ").append(factType).append("( ")
                    .append(String.join(", ", conds)).append(" )\n")
                    .append("    then\n").append(rhs).append("    end\n");

            Map<String, Object> sv = new LinkedHashMap<String, Object>();
            sv.put("stepNo", no);
            sv.put("stepName", stepName);
            sv.put("ruleName", ruleType + "#" + no + " " + stepName);
            sv.put("salience", 100 - no * 5);
            sv.put("conditions", conds);
            sv.put("actionType", actionType);
            sv.put("actionCode", actionCode);
            sv.put("upstreamRefs", new ArrayList<String>(upstream));
            sv.put("message", defaultIfEmpty(message, defaultMsg));
            sv.put("extValueType", defaultIfEmpty(str(raw.get("extValueType")), "STRING"));
            stepViews.add(sv);
        }

        String templateBody = rules.toString();

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("fields", fields);
        out.put("templateBody", templateBody);
        out.put("steps", stepViews);
        out.put("availableExt", new ArrayList<String>(availableExt));
        out.put("drlPreview", preview(ruleType, templateBody));
        return out;
    }

    

    

    private String preview(String ruleType, String body) {
        return "package com.example.drools.dynamic;\n\n"
                + "dialect \"java\"\n\n"
                + "import com.example.drools.domain.DocFact;\n"
                + "import com.example.drools.domain.Order;\n\n"
                + body;
    }

    private Map<String, Object> field(String key, String name, String type, String defaultValue, String minValue,
                                      String maxValue, String enumOptions, String placeholder, int sortOrder) {
        Map<String, Object> f = new LinkedHashMap<String, Object>();
        f.put("fieldKey", key);
        f.put("fieldName", name);
        f.put("fieldType", type);
        f.put("required", true);
        f.put("defaultValue", defaultValue);
        f.put("minValue", minValue);
        f.put("maxValue", maxValue);
        f.put("enumOptions", enumOptions);
        f.put("placeholder", placeholder);
        f.put("sortOrder", sortOrder);
        return f;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> steps(Object raw) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<Object>) raw) {
            if (o instanceof Map) out.add((Map<String, Object>) o);
        }
        return out;
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\'");
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private String defaultIfEmpty(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** 供控制器把页面传来的步骤 JSON 落库 */
    @SuppressWarnings("unchecked")
    public List<RuleStep> toEntities(Object rawSteps) {
        List<RuleStep> out = new ArrayList<RuleStep>();
        List<Map<String, Object>> list = steps(rawSteps);
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> m = list.get(i);
            RuleStep s = new RuleStep();
            s.setStepNo(i + 1);
            s.setStepName(str(m.get("stepName")));
            s.setCondField(str(m.get("condField")));
            s.setCondOp(str(m.get("condOp")));
            s.setCondType(defaultIfEmpty(str(m.get("condType")), "NUMBER"));
            s.setCondValue(str(m.get("condValue")));
            s.setActionType(defaultIfEmpty(str(m.get("actionType")), "MSG").toUpperCase());
            s.setActionCode(str(m.get("actionCode")));
            s.setExtField(str(m.get("extField")));
            s.setExtValue(str(m.get("extValue")));
            s.setExtValueType(defaultIfEmpty(str(m.get("extValueType")), "STRING"));
            s.setMessage(str(m.get("message")));
            s.setParamJson(str(m.get("paramJson")));
            out.add(s);
        }
        return out;
    }
}
