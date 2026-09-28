package com.example.drools.service;

import com.example.drools.dao.RuleCombinationTableDao;
import com.example.drools.dao.RuleDocumentDao;
import com.example.drools.entity.RuleCombinationTable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 组合规则表（页面版决策表）：校验 → 生成 DRL（一行一条规则）→ 试编译 → 发布/停用。
 *
 * 全程不涉及 Excel：页面表格 → 校验 → 生成 DRL 文本存库 → 引擎直接编译 DRL。
 *
 * 安全边界（值会直接进 DRL，所以每一格都要过闸）：
 *   · 条件列/动作类型都是代码里的白名单枚举；
 *   · 每格取值按类型做正则/枚举校验（绝不允许引号/反斜杠/大括号/分号/$ 这类能闭合字符串的字符）；
 *   · 生成完还要过一遍 Drools 试编译，语法问题在发布时就挡住。
 */
@Service
public class CombinationTableService {

    private static final Logger log = LoggerFactory.getLogger(CombinationTableService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 动作白名单（Order 主事实可用） */
    private static final Set<String> ORDER_ACTIONS = new LinkedHashSet<String>(Arrays.asList(
            "SET_DISCOUNT_RATE", "SET_DISCOUNT_AMOUNT", "SET_SHIPPING_FEE", "ADD_MESSAGE", "REJECT", "HTTP_ACTION"));
    /** 动作白名单（DocFact 通用单据：没有 setter，只能打标/加消息/调接口） */
    private static final Set<String> DOC_ACTIONS = new LinkedHashSet<String>(Arrays.asList(
            "ADD_MESSAGE", "REJECT", "HTTP_ACTION"));

    private static final Set<String> OPS = new LinkedHashSet<String>(Arrays.asList("==", "!=", ">=", "<=", ">", "<"));
    private static final Set<String> VALUE_TYPES = new LinkedHashSet<String>(Arrays.asList("ENUM", "STRING", "NUMBER", "BOOL"));
    private static final Set<String> FACT_TYPES = new LinkedHashSet<String>(Arrays.asList("Order", "OrderItem", "Product"));

    private static final Pattern SAFE_TEXT = Pattern.compile("^[^\"'\\\\{}();$]{1,64}$");
    private static final Pattern SAFE_NUMBER = Pattern.compile("^-?\\d{1,10}(\\.\\d{1,4})?$");
    private static final Pattern ASSET_KEY = Pattern.compile("^[A-Z][A-Z0-9_]{2,31}$");

    private final RuleCombinationTableDao dao;
    private final RuleDocumentDao documentDao;
    private final DynamicRuleEngine engine;

    public CombinationTableService(RuleCombinationTableDao dao, RuleDocumentDao documentDao,
                                   DynamicRuleEngine engine) {
        this.dao = dao;
        this.documentDao = documentDao;
        this.engine = engine;
    }

    // ============================================================ 校验

    /** 校验整张表：返回逐行逐列的报错（空列表 = 通过） */
    public List<Map<String, Object>> validate(JsonNode req) {
        List<Map<String, Object>> errors = new ArrayList<Map<String, Object>>();
        String assetKey = text(req, "assetKey");
        String assetName = text(req, "assetName");
        String factClass = defaultIfEmpty(text(req, "factClass"), "Order");
        String docCode = text(req, "docCode");

        if (assetKey == null || !ASSET_KEY.matcher(assetKey).matches()) {
            errors.add(error(0, "资产编码", "大写字母/数字/下划线，3~32 位且以字母开头，如 MEMBER_CATEGORY_RATE"));
        }
        if (assetName == null || assetName.trim().isEmpty()) {
            errors.add(error(0, "资产名称", "必填"));
        }
        if ("DocFact".equals(factClass)) {
            if (docCode == null || docCode.trim().isEmpty()) {
                errors.add(error(0, "绑定单据", "事实对象选 DocFact 时必须选单据"));
            } else if (documentDao.findDocument(docCode) == null) {
                errors.add(error(0, "绑定单据", "单据未注册: " + docCode + "（先去「① 单据注册」登记）"));
            }
        }

        JsonNode columns = req.get("columns");
        if (columns == null || !columns.isArray() || columns.size() == 0) {
            errors.add(error(0, "条件列", "至少要有 1 个条件列"));
        }
        Set<String> columnKeys = new LinkedHashSet<String>();
        if (columns != null && columns.isArray()) {
            for (int i = 0; i < columns.size(); i++) {
                JsonNode c = columns.get(i);
                String key = text(c, "key");
                String label = text(c, "label");
                String factType = defaultIfEmpty(text(c, "factType"), "Order");
                String fieldPath = text(c, "fieldPath");
                String op = defaultIfEmpty(text(c, "op"), "==");
                String valueType = defaultIfEmpty(text(c, "valueType"), "ENUM");
                if (key == null || !key.matches("[a-zA-Z][a-zA-Z0-9_]{0,31}")) {
                    errors.add(error(0, "第" + (i + 1) + "列", "列标识必须是字母开头的字母/数字/下划线"));
                    continue;
                }
                if (!columnKeys.add(key)) {
                    errors.add(error(0, "第" + (i + 1) + "列", "列标识重复: " + key));
                }
                if (label == null || label.trim().isEmpty()) {
                    errors.add(error(0, "第" + (i + 1) + "列", "列名必填"));
                }
                if (!OPS.contains(op)) {
                    errors.add(error(0, "第" + (i + 1) + "列", "运算符只能 " + OPS));
                }
                if (!VALUE_TYPES.contains(valueType)) {
                    errors.add(error(0, "第" + (i + 1) + "列", "取值类型只能 " + VALUE_TYPES));
                }
                if (fieldPath == null || fieldPath.trim().isEmpty()) {
                    errors.add(error(0, "第" + (i + 1) + "列", "字段路径必填（可从单据注册里复制字段）"));
                }
                if (!"DocFact".equals(factClass) && !FACT_TYPES.contains(factType)) {
                    errors.add(error(0, "第" + (i + 1) + "列", "事实对象只能 " + FACT_TYPES));
                }
            }
        }

        JsonNode action = req.get("action");
        String actionType = action == null ? null : text(action, "type");
        if (actionType == null || actionType.trim().isEmpty()) {
            errors.add(error(0, "动作", "必选一个动作"));
        } else {
            Set<String> allowed = "DocFact".equals(factClass) ? DOC_ACTIONS : ORDER_ACTIONS;
            if (!allowed.contains(actionType)) {
                errors.add(error(0, "动作", "「" + factClass + "」可用的动作只有 " + allowed));
            }
            if ("HTTP_ACTION".equals(actionType)) {
                errors.add(error(0, "动作·接口", "「调接口」动作已移除：调接口由业务系统处理，请改用 写字段/打标/加消息"));
            }
        }

        JsonNode rows = req.get("rows");
        if (rows == null || !rows.isArray() || rows.size() == 0) {
            errors.add(error(0, "组合行", "至少要有 1 行组合"));
        } else if (rows.size() > 200) {
            errors.add(error(0, "组合行", "一张表最多 200 行（当前 " + rows.size() + "）"));
        } else {
            for (int i = 0; i < rows.size(); i++) {
                JsonNode row = rows.get(i);
                int rowNo = i + 1;
                if (columns == null || !columns.isArray()) {
                    break;
                }
                for (JsonNode c : columns) {
                    String key = text(c, "key");
                    String label = defaultIfEmpty(text(c, "label"), key);
                    String valueType = defaultIfEmpty(text(c, "valueType"), "ENUM");
                    String value = text(row, key);
                    boolean cellEmpty = value == null || value.trim().isEmpty();
                    boolean boolType = "BOOL".equals(valueType);
                    if (cellEmpty) {
                        // BOOL 列允许留空 = 不限定
                        if (!boolType) {
                            errors.add(error(rowNo, label, "请选择/填写取值"));
                        }
                        continue;
                    }
                    value = value.trim();
                    if ("ENUM".equals(valueType)) {
                        String options = text(c, "enumOptions");
                        if (options == null || options.trim().isEmpty()) {
                            errors.add(error(rowNo, label, "ENUM 列必须配置枚举选项"));
                        } else if (!Arrays.asList(options.split(",")).contains(value)) {
                            // 取出所有行的越界值一起提示，运营好改
                            errors.add(error(rowNo, label, "取值不在枚举范围内: " + value + "（可选 " + options + "）"));
                        }
                    } else if ("NUMBER".equals(valueType)) {
                        if (!SAFE_NUMBER.matcher(value).matches()) {
                            errors.add(error(rowNo, label, "必须是数字（且长度受限）：" + value));
                        }
                    } else if ("BOOL".equals(valueType)) {
                        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                            errors.add(error(rowNo, label, "只能是 true / false"));
                        }
                    } else if (!SAFE_TEXT.matcher(value).matches()) {
                        errors.add(error(rowNo, label, "取值含非法字符（不允许引号/反斜杠/大括号/分号/括号/$）：" + value));
                    }
                }
                // 动作取值
                String value = text(row, "value");
                String paramType = action == null ? "NUMBER" : defaultIfEmpty(text(action, "paramType"), "NUMBER");
                boolean valueRequired = actionType != null && !"REJECT".equals(actionType) && !"HTTP_ACTION".equals(actionType);
                if (value == null || value.trim().isEmpty()) {
                    if (valueRequired) {
                        errors.add(error(rowNo, action == null ? "动作取值" : defaultIfEmpty(text(action, "label"), "动作取值"), "必填"));
                    }
                } else if ("NUMBER".equals(paramType)) {
                    if (!SAFE_NUMBER.matcher(value.trim()).matches()) {
                        errors.add(error(rowNo, "动作取值", "必须是数字：" + value));
                    }
                } else if (!SAFE_TEXT.matcher(value.trim()).matches()) {
                    errors.add(error(rowNo, "动作取值", "取值含非法字符：" + value));
                }
            }
        }
        return errors;
    }

    // ============================================================ 生成 DRL

    /** 一行组合 → 一条规则；整张表一个 DRL 文本 */
    public String generateDrl(JsonNode req) {
        String assetKey = text(req, "assetKey");
        String factClass = defaultIfEmpty(text(req, "factClass"), "Order");
        String docCode = text(req, "docCode");
        int salience = req.hasNonNull("salience") ? req.get("salience").asInt(-20) : -20;
        JsonNode columns = req.get("columns");
        JsonNode action = req.get("action");
        JsonNode rows = req.get("rows");

        StringBuilder drl = new StringBuilder();
        drl.append("package com.example.drools.dynamic;\n\n");
        // java 方言：避免 mvel 方言下 update() 退化成全量更新导致反复点火
        drl.append("dialect \"java\"\n\n");
        drl.append("import com.example.drools.domain.Order;\n");
        drl.append("import com.example.drools.domain.OrderItem;\n");
        drl.append("import com.example.drools.domain.Product;\n");
        drl.append("import com.example.drools.domain.DocFact;\n\n");

        boolean orderFact = !"DocFact".equals(factClass);
        boolean hasItem = false;
        for (JsonNode c : columns) {
            if ("OrderItem".equals(text(c, "factType"))) {
                hasItem = true;
            }
        }

        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            drl.append("rule \"CT_").append(assetKey).append("_").append(i + 1).append("\"\n");
            drl.append("    salience ").append(salience).append("\n");
            drl.append("    when\n");

            if (orderFact) {
                // 主事实 Order：把 factType=Order 的约束合并进同一模式（同变量多约束，不能用两个 Order 模式）
                StringBuilder orderConstraints = new StringBuilder("rejected == false");
                for (JsonNode c : columns) {
                    if ("Order".equals(defaultIfEmpty(text(c, "factType"), "Order"))) {
                        String one = constraint(c, row);
                        if (one != null) {
                            orderConstraints.append(", ").append(one);
                        }
                    }
                }
                drl.append("        $o : Order( ").append(orderConstraints).append(" )\n");
                // 明细/商品的约束各自成模式
                for (String factType : new String[]{"OrderItem", "Product"}) {
                    StringBuilder constraints = new StringBuilder();
                    for (JsonNode c : columns) {
                        if (factType.equals(text(c, "factType"))) {
                            String one = constraint(c, row);
                            if (one == null) {
                                continue;
                            }
                            if (constraints.length() > 0) {
                                constraints.append(", ");
                            }
                            constraints.append(one);
                        }
                    }
                    if (constraints.length() > 0) {
                        String var = "OrderItem".equals(factType) ? "$i" : "$p";
                        drl.append("        ").append(var).append(" : ").append(factType)
                                .append("( ").append(constraints).append(" )\n");
                    }
                }
                if ("HTTP_ACTION".equals(text(action, "type"))) {
                    String actionCode = text(action, "actionCode");
                    drl.append("        Order( ext[\"").append(actionCode).append("\"] == null )\n");
                }
            } else {
                StringBuilder constraints = new StringBuilder("docCode == \"").append(docCode).append("\"");
                for (JsonNode c : columns) {
                    String one = docFactConstraint(c, row);
                    if (one != null) {
                        constraints.append(", ").append(one);
                    }
                }
                if ("HTTP_ACTION".equals(text(action, "type"))) {
                    constraints.append(", ext[\"").append(text(action, "actionCode")).append("\"] == null");
                }
                drl.append("        $d : DocFact( ").append(constraints).append(" )\n");
            }

            drl.append("    then\n");
            for (String line : actionLines(action, orderFact, hasItem, row)) {
                drl.append("        ").append(line).append("\n");
            }
            drl.append("end\n\n");
        }
        return drl.toString();
    }

    /** 条件列 → DRL 约束（强类型事实用属性名）；返回 null 表示该格留空、该条约束不生成 */
    private String constraint(JsonNode column, JsonNode row) {
        String fieldPath = text(column, "fieldPath");
        String op = defaultIfEmpty(text(column, "op"), "==");
        String valueType = defaultIfEmpty(text(column, "valueType"), "ENUM");
        String value = text(row, text(column, "key"));
        if (value == null || value.trim().isEmpty()) {
            // BOOL 列留空 = 该列不限定
            return null;
        }
        value = value.trim();
        if ("NUMBER".equals(valueType) || "BOOL".equals(valueType)) {
            return fieldPath + " " + op + " " + value;
        }
        return fieldPath + " " + op + " \"" + value + "\"";
    }

    /** 条件列 → DocFact 约束（用取值辅助方法，避免类型比较踩坑）；返回 null 表示该格留空 */
    private String docFactConstraint(JsonNode column, JsonNode row) {
        String fieldPath = text(column, "fieldPath");
        String op = defaultIfEmpty(text(column, "op"), "==");
        String valueType = defaultIfEmpty(text(column, "valueType"), "ENUM");
        String value = text(row, text(column, "key"));
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        value = value.trim();
        if ("NUMBER".equals(valueType)) {
            return "getNumber(\"" + fieldPath + "\") " + op + " " + value;
        }
        if ("BOOL".equals(valueType)) {
            return "getBool(\"" + fieldPath + "\") " + op + " " + value;
        }
        return "getString(\"" + fieldPath + "\") " + op + " \"" + value + "\"";
    }

    /** 动作 → RHS 各行（白名单模板，value 只做参数代入） */
    private List<String> actionLines(JsonNode action, boolean orderFact, boolean hasItem, JsonNode row) {
        String type = text(action, "type");
        String value = text(row, "value");
        String valueLiteral = value == null ? "" : value.trim();
        String fact = orderFact ? "$o" : "$d";
        String base = hasItem ? "$i.getSubtotal()" : "$o.getTotalAmount()";
        List<String> lines = new ArrayList<String>();
        switch (type) {
            case "SET_DISCOUNT_RATE":
                lines.add("$o.setDiscount($o.getDiscount() + " + base + " * " + valueLiteral + ");");
                lines.add("update($o);");
                lines.add("$o.addMessage(\"" + baseLabel(action) + " " + valueLiteral + "\");");
                break;
            case "SET_DISCOUNT_AMOUNT":
                lines.add("$o.setDiscount($o.getDiscount() + " + valueLiteral + ");");
                lines.add("update($o);");
                lines.add("$o.addMessage(\"" + baseLabel(action) + " 减免 " + valueLiteral + " 元\");");
                break;
            case "SET_SHIPPING_FEE":
                lines.add("$o.setShippingFee($o.getShippingFee() + " + valueLiteral + ");");
                lines.add("update($o);");
                lines.add("$o.addMessage(\"" + baseLabel(action) + " 运费 +" + valueLiteral + "\");");
                break;
            case "ADD_MESSAGE":
                lines.add(fact + (orderFact ? ".addMessage" : ".addRuleMessage") + "(\"" + valueLiteral + "\");");
                break;
            case "REJECT":
                if (orderFact) {
                    lines.add("$o.setRejected(true);");
                } else {
                    lines.add("$d.getExt().put(\"rejected\", true);");
                }
                lines.add("update(" + fact + ");");
                if (!valueLiteral.isEmpty()) {
                    lines.add(fact + (orderFact ? ".addMessage" : ".addRuleMessage") + "(\"" + valueLiteral + "\");");
                }
                break;
            default:
                lines.add("// 未知动作类型: " + type);
        }
        return lines;
    }

    private String baseLabel(JsonNode action) {
        String label = text(action, "label");
        return label == null || label.trim().isEmpty() ? "组合规则" : label.trim().replace("\"", "'");
    }

    // ============================================================ 保存 / 发布 / 停用

    public Map<String, Object> preview(JsonNode req) {
        List<Map<String, Object>> errors = validate(req);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("errors", errors);
        result.put("errorCount", errors.size());
        if (errors.isEmpty()) {
            String drl = generateDrl(req);
            engine.validate(drl);   // 试编译：语法问题提前暴露
            result.put("drl", drl);
            result.put("ruleCount", req.get("rows").size());
        }
        return result;
    }

    public Map<String, Object> save(JsonNode req, String updatedBy) {
        List<Map<String, Object>> errors = validate(req);
        if (!errors.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("errors", errors);
            result.put("errorCount", errors.size());
            throw new IllegalArgumentException("组合规则表校验失败（" + errors.size() + " 处）："
                    + errors.get(0).get("row") + " 行 " + errors.get(0).get("column") + " " + errors.get(0).get("message"));
        }
        RuleCombinationTable t = toEntity(req);
        t.setUpdatedBy(updatedBy);
        dao.upsertDefinition(t);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("saved", true);
        result.put("assetKey", t.getAssetKey());
        result.put("ruleCount", t.getRowCount());
        result.put("message", "定义已保存（还没生效，点「发布生效」才会进引擎）");
        return result;
    }

    public Map<String, Object> publish(String assetKey, String updatedBy) {
        RuleCombinationTable entity = dao.findByKey(assetKey);
        if (entity == null) {
            throw new IllegalArgumentException("组合规则表不存在: " + assetKey);
        }
        JsonNode req = MAPPER.valueToTree(fromEntity(entity));
        List<Map<String, Object>> errors = validate(req);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("组合规则表校验失败（" + errors.size() + " 处）："
                    + errors.get(0).get("row") + " 行 " + errors.get(0).get("column") + " " + errors.get(0).get("message"));
        }
        String drl = generateDrl(req);
        engine.validate(drl);   // 试编译
        int version = (entity.getVersion() == null ? 0 : entity.getVersion()) + 1;
        dao.publish(assetKey, drl, version, entity.getRowCount() == null ? 0 : entity.getRowCount(), updatedBy);
        engine.refresh();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("assetKey", assetKey);
        result.put("version", version);
        result.put("rowCount", entity.getRowCount());
        result.put("ruleCount", engine.getRuleCount());
        result.put("drl", drl);
        result.put("message", "已发布：" + entity.getRowCount() + " 行组合 → " + entity.getRowCount() + " 条规则");
        return result;
    }

    public Map<String, Object> changeStatus(String assetKey, int status) {
        RuleCombinationTable entity = dao.findByKey(assetKey);
        if (entity == null) {
            throw new IllegalArgumentException("组合规则表不存在: " + assetKey);
        }
        dao.updateStatus(assetKey, status);
        engine.refresh();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("assetKey", assetKey);
        result.put("status", status);
        result.put("ruleCount", engine.getRuleCount());
        result.put("message", status == 1 ? "已启用" : "已停用（规则从引擎移除）");
        return result;
    }

    public Map<String, Object> delete(String assetKey) {
        dao.delete(assetKey);
        engine.refresh();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("message", "已删除组合规则表 " + assetKey);
        return result;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (RuleCombinationTable t : dao.findAll()) {
            result.add(fromEntity(t));
        }
        return result;
    }

    public Map<String, Object> detail(String assetKey) {
        RuleCombinationTable t = dao.findByKey(assetKey);
        if (t == null) {
            throw new IllegalArgumentException("组合规则表不存在: " + assetKey);
        }
        return fromEntity(t);
    }

    // ============================================================ 存取映射

    private RuleCombinationTable toEntity(JsonNode req) {
        RuleCombinationTable t = new RuleCombinationTable();
        t.setAssetKey(text(req, "assetKey"));
        t.setAssetName(text(req, "assetName"));
        t.setDocCode(text(req, "docCode"));
        t.setFactClass(defaultIfEmpty(text(req, "factClass"), "Order"));
        t.setSalience(req.hasNonNull("salience") ? req.get("salience").asInt(-20) : -20);
        t.setColumnsJson(req.get("columns").toString());
        t.setActionJson(req.get("action").toString());
        t.setRowsJson(req.get("rows").toString());
        t.setRowCount(req.get("rows").size());
        t.setRemark(text(req, "remark"));
        return t;
    }

    /** 实体 → 页面用的结构（columns/action/rows 解成对象） */
    private Map<String, Object> fromEntity(RuleCombinationTable t) {
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("assetKey", t.getAssetKey());
        node.put("assetName", t.getAssetName());
        node.put("docCode", t.getDocCode());
        node.put("factClass", t.getFactClass());
        node.put("salience", t.getSalience());
        node.put("version", t.getVersion());
        node.put("status", t.getStatus());
        node.put("rowCount", t.getRowCount());
        node.put("ruleCount", t.getRowCount());
        node.put("updatedBy", t.getUpdatedBy());
        node.put("remark", t.getRemark());
        node.put("updateTime", t.getUpdateTime() == null ? null : t.getUpdateTime().toString());
        try {
            node.put("columns", MAPPER.readTree(t.getColumnsJson()));
            node.put("action", MAPPER.readTree(t.getActionJson()));
            node.put("rows", MAPPER.readTree(t.getRowsJson()));
        } catch (Exception e) {
            node.put("columns", new ArrayList<Object>());
            node.put("action", new LinkedHashMap<String, Object>());
            node.put("rows", new ArrayList<Object>());
        }
        node.put("drlContent", t.getDrlContent());
        return node;
    }

    // ============================================================ 工具

    private Map<String, Object> error(int row, String column, String message) {
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("row", row);
        e.put("column", column);
        e.put("message", message);
        return e;
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        return value.isNull() ? null : value.asText();
    }

    private String defaultIfEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
