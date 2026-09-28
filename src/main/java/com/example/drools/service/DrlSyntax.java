package com.example.drools.service;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * DRL 语法真源：把页面上的「运算符 + 取值类型 + 取值」翻译成 Drools 能编译的约束文本。
 *
 * 设计目的：运算符与类型的支持范围只在这一个类里定义，步骤链（RuleStepBuilder）、
 * 经典类型（RuleTypeBuilder）都调这里，避免两处各写一套导致"页面上有、编译不过"。
 *
 * 支持范围（对齐 Drools 约束语法）：
 *   比较：== != > >= < <=
 *   空值：为空(== null) / 不为空(!= null)
 *   集合：in / not in（值写 CSV）；memberOf / not memberOf（值给集合表达式）
 *   字符串：contains / not contains / matches / not matches / soundslike
 *   便利：以...开头 / 以...结尾 → 自动翻译成 matches 正则（DRL 没有 startsWith 关键字）
 */
public final class DrlSyntax {

    private DrlSyntax() {
    }

    /** 运算符目录：canonical(DRL 写法) → 中文标签（页面下拉直接用） */
    private static final LinkedHashMap<String, String> OPS = new LinkedHashMap<String, String>();

    static {
        OPS.put("==", "等于");
        OPS.put("!=", "不等于");
        OPS.put(">", "大于");
        OPS.put(">=", "大于等于");
        OPS.put("<", "小于");
        OPS.put("<=", "小于等于");
        OPS.put("== null", "为空");
        OPS.put("!= null", "不为空");
        OPS.put("in", "在列表中（值填逗号分隔）");
        OPS.put("not in", "不在列表中");
        OPS.put("contains", "包含");
        OPS.put("not contains", "不包含");
        OPS.put("matches", "匹配正则");
        OPS.put("not matches", "不匹配正则");
        OPS.put("soundslike", "发音相似");
        OPS.put("startsWith", "以…开头（自动转正则）");
        OPS.put("endsWith", "以…结尾（自动转正则）");
        OPS.put("not startsWith", "不以…开头");
        OPS.put("not endsWith", "不以…结尾");
        OPS.put("memberOf", "属于集合（值给集合表达式）");
        OPS.put("not memberOf", "不属于集合");
    }

    /** 中文/别名 → canonical */
    private static final Map<String, String> ALIAS = new LinkedHashMap<String, String>();

    static {
        ALIAS.put("等于", "==");
        ALIAS.put("equal", "==");
        ALIAS.put("=", "==");
        ALIAS.put("不等于", "!=");
        ALIAS.put("notEqual", "!=");
        ALIAS.put("大于", ">");
        ALIAS.put("greaterThan", ">");
        ALIAS.put("大于等于", ">=");
        ALIAS.put(">=", ">=");
        ALIAS.put("小于", "<");
        ALIAS.put("lessThan", "<");
        ALIAS.put("小于等于", "<=");
        ALIAS.put("为空", "== null");
        ALIAS.put("is null", "== null");
        ALIAS.put("== null", "== null");
        ALIAS.put("不为空", "!= null");
        ALIAS.put("is not null", "!= null");
        ALIAS.put("在列表中", "in");
        ALIAS.put("不在列表中", "not in");
        ALIAS.put("包含", "contains");
        ALIAS.put("不包含", "not contains");
        ALIAS.put("匹配正则", "matches");
        ALIAS.put("不匹配正则", "not matches");
        ALIAS.put("发音相似", "soundslike");
        ALIAS.put("以…开头", "startsWith");
        ALIAS.put("以…结尾", "endsWith");
    }

    /** 支持的取值类型（对齐 Drools/Java 类型） */
    private static final LinkedHashMap<String, String> TYPES = new LinkedHashMap<String, String>();

    static {
        TYPES.put("STRING", "字符串");
        TYPES.put("TEXT", "长文本");
        TYPES.put("INT", "整数 int");
        TYPES.put("LONG", "长整数 long");
        TYPES.put("DOUBLE", "小数 double");
        TYPES.put("FLOAT", "小数 float");
        TYPES.put("DECIMAL", "精确小数 BigDecimal");
        TYPES.put("NUMBER", "数字（等同 DOUBLE，兼容旧配置）");
        TYPES.put("BOOLEAN", "布尔");
        TYPES.put("DATE", "日期时间");
        TYPES.put("ENUM", "枚举（按字符串比较）");
        TYPES.put("CSV", "列表（逗号分隔，用于 in/not in）");
        TYPES.put("LIST", "列表（同 CSV）");
    }

    public static LinkedHashMap<String, String> operators() {
        return OPS;
    }

    public static LinkedHashMap<String, String> types() {
        return TYPES;
    }

    /** 该类型按数字处理（聚合取值走 getNumber、字面量不加引号） */
    public static boolean isNumeric(String type) {
        String t = normalizeType(type);
        return "INT".equals(t) || "LONG".equals(t) || "DOUBLE".equals(t) || "FLOAT".equals(t)
                || "DECIMAL".equals(t) || "NUMBER".equals(t);
    }

    public static String canonicalOp(String raw) {
        if (raw == null) {
            return "==";
        }
        String v = raw.trim();
        if (OPS.containsKey(v)) {
            return v;
        }
        if (ALIAS.containsKey(v)) {
            return ALIAS.get(v);
        }
        String lower = v.toLowerCase();
        if (ALIAS.containsKey(lower)) {
            return ALIAS.get(lower);
        }
        if ("startsWith".equalsIgnoreCase(v) || "not startsWith".equalsIgnoreCase(v)) {
            return v;
        }
        // 未知运算符：原样返回（DRL 编译会报错，页面侧要给软提示）
        return v;
    }

    /** 该运算符右侧要不要取值（为空/不为空 不需要） */
    public static boolean needsValue(String rawOp) {
        String op = canonicalOp(rawOp);
        return !("== null".equals(op) || "!= null".equals(op));
    }

    /** 该运算符右侧是列表 */
    public static boolean isListOp(String rawOp) {
        String op = canonicalOp(rawOp);
        return "in".equals(op) || "not in".equals(op);
    }

    /**
     * 渲染一条 DRL 约束。
     *
     * @param fieldExpr 左侧字段表达式（DocFact：getNumber("字段")/getString("字段")；Order：属性路径 item.subtotal，支持中文名）
     * @param rawOp     运算符（英文/中文都行）
     * @param valueText 右侧取值文本：发布前是 ${参数名} 占位符，发布后被替换；也可能是写死的字面量
     * @param type      取值类型（见 TYPES）
     * @return 可直接进 DRL 的约束文本，如 getNumber("售價") >= ${step1Value} / getString("品类") in (${step1Value})
     */
    public static String render(String fieldExpr, String rawOp, String valueText, String type) {
        String op = canonicalOp(rawOp);
        String t = normalizeType(type);
        String value = valueText == null ? "" : valueText.trim();

        if ("== null".equals(op) || "!= null".equals(op)) {
            return fieldExpr + " " + op;
        }
        if ("startsWith".equalsIgnoreCase(op) || "endsWith".equalsIgnoreCase(op)
                || "not startsWith".equalsIgnoreCase(op) || "not endsWith".equalsIgnoreCase(op)) {
            boolean negate = op.toLowerCase().startsWith("not");
            boolean start = op.toLowerCase().endsWith("startsWith");
            // 取值是 ${参数名} 占位符时**不能**做正则转义：否则发布时替换不上（实测踩到：
            // 占位符被转义成 \$\{step1Value\} → DrlGenerator 认不出 → DRL 编译报 illegal escape sequence）
            String core = value.startsWith("${") ? value : escapeRegex(trimQuotes(value));
            String regex = start ? "^" + core : core + "$";
            return fieldExpr + (negate ? " not matches " : " matches ") + "\"" + regex + "\"";
        }
        if (isListOp(op)) {
            // in (v1, v2)：CSV/占位符整段放括号里
            return fieldExpr + " " + op + " (" + listSide(value, t) + ")";
        }
        if ("memberOf".equals(op) || "not memberOf".equals(op)) {
            // memberOf 右侧是集合表达式（如 ${ext.allowedRegions} 或 ["A","B"]），原样给，不加工
            return fieldExpr + " " + op + " " + value;
        }
        // 比较 / 匹配 / 包含 / 发音
        return fieldExpr + " " + op + " " + literal(value, t);
    }

    /** 右侧列表：占位符（形如 ${x}）由 DrlGenerator 用 CSV→列表 转换；字面量在这里拆 */
    private static String listSide(String value, String type) {
        if (value.startsWith("${")) {
            return value; // DrlGenerator 会把它换成 "a", "b"
        }
        String[] parts = value.split(",");
        return Arrays.stream(parts).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> literal(s, "CSV".equals(type) ? "STRING" : type))
                .collect(Collectors.joining(", "));
    }

    /** 取值字面量（按类型渲染成 DRL 合法写法）；${...} 占位符按类型只做引号与否处理 */
    public static String literal(String rawValue, String type) {
        String t = normalizeType(type);
        String v = rawValue == null ? "" : rawValue.trim();
        boolean placeholder = v.startsWith("${");
        String body = placeholder ? v : v;
        if ("INT".equals(t) || "NUMBER_INT".equals(t)) {
            return placeholder ? body : (v.isEmpty() ? "0" : String.valueOf(parseLong(v)));
        }
        if ("LONG".equals(t)) {
            return placeholder ? body : (v.isEmpty() ? "0L" : parseLong(v) + "L");
        }
        if ("DOUBLE".equals(t) || "NUMBER".equals(t)) {
            return placeholder ? body : (v.isEmpty() ? "0.0" : parseDouble(v) + "D");
        }
        if ("FLOAT".equals(t)) {
            return placeholder ? body : (v.isEmpty() ? "0.0F" : parseDouble(v) + "F");
        }
        if ("DECIMAL".equals(t)) {
            // BigDecimal：DRL 支持 1.50B 字面量
            return placeholder ? body : (v.isEmpty() ? "0B" : new java.math.BigDecimal(v).toPlainString() + "B");
        }
        if ("BOOLEAN".equals(t)) {
            if (placeholder) {
                return body;
            }
            return ("true".equalsIgnoreCase(v) || "1".equals(v) || "是".equals(v)) ? "true" : "false";
        }
        if ("DATE".equals(t)) {
            if (placeholder) {
                return body;
            }
            return "new java.util.Date(" + epochMillis(v) + "L)";
        }
        if ("CSV".equals(t) || "LIST".equals(t)) {
            // 列表类型单值场景（如 contains）：按字符串处理
            return quote(v);
        }
        // STRING / TEXT / ENUM 以及未知类型：按字符串
        return quote(v);
    }

    private static String normalizeType(String type) {
        String t = type == null ? "STRING" : type.trim().toUpperCase();
        if (t.isEmpty()) {
            return "STRING";
        }
        if ("LIST".equals(t)) {
            return "CSV";
        }
        return t;
    }

    private static String quote(String v) {
        String s = v;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s; // 已经带引号的（含 ${x} 被模板作者写了引号）原样
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String trimQuotes(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String escapeRegex(String v) {
        StringBuilder sb = new StringBuilder();
        for (char c : v.toCharArray()) {
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static long parseLong(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return (long) Double.parseDouble(v.trim());
        }
    }

    private static String parseDouble(String v) {
        return java.math.BigDecimal.valueOf(Double.parseDouble(v.trim())).stripTrailingZeros().toPlainString();
    }

    private static long epochMillis(String v) {
        String s = v.trim();
        if (s.matches("\\d{10,}")) {
            return Long.parseLong(s);
        }
        String[] patterns = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p);
                f.setLenient(false);
                return f.parse(s).getTime();
            } catch (Exception ignore) {
                // 换下一个格式
            }
        }
        throw new IllegalArgumentException("日期取值无法解析（支持 yyyy-MM-dd / yyyy-MM-dd HH:mm:ss / 毫秒时间戳）: " + v);
    }
}
