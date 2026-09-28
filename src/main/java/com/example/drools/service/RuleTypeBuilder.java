package com.example.drools.service;

import com.example.drools.entity.RuleTypeField;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则类型生成器：按"单据对象/字段 + 接口"拼出一套可直接保存的规则类型
 * （类型元数据 + 参数定义 + DRL 模板体），运营在页面 ③ 里选完就能看到生成的 DRL。
 *
 * 支持四种组合：
 *   factClass=DocFact|Order  ×  mode=ACTION（命中调接口）|GUARD（按接口返回值判定）
 *
 * 安全点：判定字段/运算符是**下拉选出来的**，直接写进模板（不给运营手打路径的机会）；
 * 只有业务可调的值（阈值/取值）和动作码做成规则参数。
 */
@Service
public class RuleTypeBuilder {

    /** 生成结果：类型定义 + 参数 + 模板 + 预览 DRL */
    public Map<String, Object> build(Map<String, Object> request) {
        String ruleType = str(request.get("ruleType"));
        String factClass = defaultIfEmpty(str(request.get("factClass")), "DocFact");
        String mode = defaultIfEmpty(str(request.get("mode")), "ACTION");
        String docCode = str(request.get("docCode"));
        String fieldPath = str(request.get("fieldPath"));
        String operator = defaultIfEmpty(str(request.get("operator")), ">=");
        String valueType = defaultIfEmpty(str(request.get("valueType")), "NUMBER");
        String actionCode = str(request.get("actionCode"));
        String guardField = str(request.get("guardField"));
        String guardValue = str(request.get("guardValue"));
        String markField = defaultIfEmpty(str(request.get("markField")), "requires_risk_review");
        String message = str(request.get("message"));
        boolean exposeFieldUnit = Boolean.TRUE.equals(request.get("exposeFieldAsParam"));

        require(ruleType, "类型编码(ruleType)必填");
        if (!ruleType.matches("[A-Z][A-Z0-9_]{2,31}")) {
            throw new IllegalArgumentException("类型编码只能是大写字母/数字/下划线，3~32 位，如 ORDER_RISK_PUSH");
        }
        require(fieldPath, "判定字段(fieldPath)必填（从单据注册的字段里选）");
        require(operator, "运算符(operator)必填");

        boolean orderFact = "Order".equalsIgnoreCase(factClass);
        if (!orderFact) {
            require(docCode, "单据编码(docCode)必填（通用单据用 DocFact 进引擎）");
        }
        List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>();

        // ---------- 参数：业务可调项 ----------
        String valueParamKey;
        if ("NUMBER".equalsIgnoreCase(valueType)) {
            valueParamKey = defaultIfEmpty(str(request.get("valueParamKey")), "threshold");
            fields.add(field(valueParamKey, "触发阈值", "NUMBER",
                    defaultIfEmpty(str(request.get("defaultValue")), "10000"), null, null, null, "如 10000", 2));
        } else if ("ENUM".equalsIgnoreCase(valueType)) {
            valueParamKey = defaultIfEmpty(str(request.get("valueParamKey")), "guardValue");
            fields.add(field(valueParamKey, "判定取值", "ENUM",
                    defaultIfEmpty(str(request.get("defaultValue")), firstEnum(request.get("enumOptions"))),
                    null, null, str(request.get("enumOptions")), "枚举取值", 2));
        } else {
            valueParamKey = defaultIfEmpty(str(request.get("valueParamKey")), "value");
            fields.add(field(valueParamKey, "判定取值", "STRING",
                    defaultIfEmpty(str(request.get("defaultValue")), "X"), null, null, null, "字符串取值", 2));
        }

        if ("ACTION".equalsIgnoreCase(mode)) {
            require(actionCode, "调接口模式必须选接口(actionCode)");
            fields.add(0, field("actionCode", "接口动作码", "STRING", actionCode, null, null, null,
                    "rule_http_action.action_code", 1));
        }
        if (exposeFieldUnit) {
            fields.add(field("fieldPath", "判定字段路径", "STRING", fieldPath, null, null, null,
                    "如 variables.amount", fields.size() + 1));
            if (!orderFact) {
                fields.add(field("docCode", "单据编码", "STRING", docCode, null, null, null,
                        "rule_document.doc_code", fields.size() + 1));
            }
        }

        // ---------- 模板体 ----------
        String body;
        if ("GUARD".equalsIgnoreCase(mode)) {
            require(guardField, "返回值判定模式必须填返回值字段名(guardField)");
            require(guardValue, "返回值判定模式必须填判定取值(guardValue)");
            String fact = orderFact ? "$o : Order( rejected == false" : "$d : DocFact( docCode == \"" + docCode + "\"";
            body = "    when\n"
                    + "        " + fact + ", ext[\"" + guardField + "\"] == \"${" + valueParamKey + "}\""
                    + ", ext[\"marked_" + guardField + "\"] == null )\n"
                    + "    then\n"
                    + "        " + (orderFact ? "$o" : "$d") + ".getExt().put(\"marked_" + guardField + "\", true);\n"
                    + "        " + (orderFact ? "$o" : "$d") + ".getExt().put(\"" + markField + "\", true);\n"
                    + "        " + (orderFact ? "$o" : "$d") + ".addRuleMessage(\"" + defaultIfEmpty(message,
                    "接口返回值 " + guardField + "=${" + valueParamKey + "} → 打标 " + markField).replace("\"", "'") + "\");\n"
                    + "        update(" + (orderFact ? "$o" : "$d") + ");\n";
        } else if ("SET_EXT".equalsIgnoreCase(mode)) {
            // 决策类规则的通用动作：命中就把「判定结果」写进单据的 ext，供后续链路（查审批人/发起流程/打标）读取
            String extField = str(request.get("extField"));
            String extValue = str(request.get("extValue"));
            require(extField, "写单据字段模式必须填 ext 字段名(extField)，如 approvalLevel");
            require(extValue, "写单据字段模式必须填 ext 取值(extValue)，如 L2");
            String factVar = orderFact ? "$o" : "$d";
            String compare = "NUMBER".equalsIgnoreCase(valueType)
                    ? "getNumber(\"" + fieldPath + "\") " + operator + " ${" + valueParamKey + "}"
                    : "getString(\"" + fieldPath + "\") " + operator + " \"${" + valueParamKey + "}\"";
            if (orderFact) {
                compare = "NUMBER".equalsIgnoreCase(valueType)
                        ? fieldPath + " " + operator + " ${" + valueParamKey + "}"
                        : fieldPath + " " + operator + " \"${" + valueParamKey + "}\"";
            }
            String when = orderFact
                    ? "$o : Order( rejected == false, " + compare + " )"
                    : "$d : DocFact( docCode == \"" + docCode + "\", " + compare + " )";
            body = "    when\n"
                    + "        " + when + "\n"
                    + "    then\n"
                    + "        " + factVar + ".getExt().put(\"" + extField + "\", \"" + extValue + "\");\n"
                    + "        " + factVar + ".getExt().put(\"marked_" + extField + "\", true);\n"
                    + "        " + factVar + ".addRuleMessage(\""
                    + defaultIfEmpty(message, fieldPath + " " + operator + " ${" + valueParamKey + "} → "
                    + extField + "=" + extValue).replace("\"", "'") + "\");\n"
                    + "        update(" + factVar + ");\n";
        } else {
            String fieldExpr = exposeFieldUnit ? "\"${fieldPath}\"" : "\"" + fieldPath + "\"";
            String docExpr = exposeFieldUnit ? "\"${docCode}\"" : "\"" + docCode + "\"";
            String compare = "NUMBER".equalsIgnoreCase(valueType)
                    ? "getNumber(" + fieldExpr + ") " + operator + " ${" + valueParamKey + "}"
                    : "getString(" + fieldExpr + ") " + operator + " \"${" + valueParamKey + "}\"";
            if (orderFact) {
                compare = "NUMBER".equalsIgnoreCase(valueType)
                        ? fieldPath + " " + operator + " ${" + valueParamKey + "}"
                        : fieldPath + " " + operator + " \"${" + valueParamKey + "}\"";
            }
            String when = orderFact
                    ? "$o : Order( rejected == false, ext[\"${actionCode}\"] == null, " + compare + " )"
                    : "$d : DocFact( docCode == " + docExpr + ", ext[\"${actionCode}\"] == null, " + compare + " )";
            String factVar = orderFact ? "$o" : "$d";
            body = "    when\n"
                    + "        " + when + "\n"
                    + "    then\n"
                    + "        httpActionGateway.invoke(\"${actionCode}\", " + factVar + ");\n"
                    + "        update(" + factVar + ");\n"
                    + "        " + factVar + (orderFact ? ".addMessage" : ".addRuleMessage")
                    + "(\"" + defaultIfEmpty(message,
                    "单据[" + factVar + "]命中，已调用接口 ${actionCode}").replace("\"", "'") + "\");\n";
        }

        // ---------- 组装结果 ----------
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ruleType", ruleType);
        result.put("ruleGroup", defaultIfEmpty(str(request.get("ruleGroup")), "custom"));
        result.put("typeName", require(str(request.get("typeName")), "类型名称(typeName)必填"));
        result.put("typeDesc", str(request.get("typeDesc")));
        result.put("sortOrder", request.get("sortOrder") == null ? 90 : request.get("sortOrder"));
        result.put("builtin", false);
        result.put("fields", fields);
        result.put("templateBody", body);
        result.put("drlPreview", preview(ruleType, body, fields));
        result.put("docCode", docCode);
        result.put("mode", mode.toUpperCase());
        result.put("factClass", orderFact ? "Order" : "DocFact");
        return result;
    }

    /** 用参数的默认值渲染一遍，让运营看到"最终生成的 DRL 长什么样" */
    private String preview(String ruleType, String body, List<Map<String, Object>> fields) {
        String rendered = body;
        for (Map<String, Object> f : fields) {
            rendered = rendered.replace("${" + f.get("fieldKey") + "}", String.valueOf(f.get("defaultValue")));
        }
        return "package com.example.drools.dynamic;\n\ndialect \"mvel\"\n\n"
                + "import com.example.drools.domain.Order;\nimport com.example.drools.domain.DocFact;\n\n"
                + "global com.example.drools.http.HttpActionGateway httpActionGateway;\n\n"
                + "rule \"" + ruleType + "_示例\"\n" + rendered + "\nend\n";
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

    private String firstEnum(Object enumOptions) {
        String options = str(enumOptions);
        if (options == null || options.isEmpty()) {
            return "";
        }
        int comma = options.indexOf(',');
        return comma > 0 ? options.substring(0, comma) : options;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String defaultIfEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private String require(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
