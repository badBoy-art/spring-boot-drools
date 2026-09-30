package com.example.drools.service;

import com.example.drools.entity.RuleStep;
import com.example.drools.entity.RuleStepOutput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 多步骤规则生成器：把「步骤链」编译成 DRL。
 *
 * <p>DOC 操作单据主事实，EACH/NTH 的多字段赋值操作当前行事实。salience 按步骤递减，
 * 独立完成标记避免 update 后重复执行。EACH 未配置输出时使用 collectList 聚合命中行，
 * 将 badItems/itemFailCount 写到单据级结果。
 */
@Service
public class RuleStepBuilder {

  private static final Pattern EXT_REF =
      Pattern.compile("\\$\\{ext\\.([A-Za-z0-9_\\u4e00-\\u9fa5]+)}");

  /** 生成步骤链 DRL：返回 {fields, templateBody, drlPreview, steps(带生成的规则名), variables} */
  public Map<String, Object> build(Map<String, Object> request) {
    String ruleType = str(request.get("ruleType"));
    String docCode = str(request.get("docCode"));
    List<Map<String, Object>> steps = steps(request.get("steps"));

    if (ruleType.isEmpty()) throw new IllegalArgumentException("类型编码(ruleType)必填");
    if (steps.isEmpty()) throw new IllegalArgumentException("至少要有一个步骤");
    if (docCode.isEmpty()) throw new IllegalArgumentException("单据编码(docCode)必填");

    List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>();
    // Explicit parameters can be referenced by output expressions, even for unconditional steps.
    Object rawParameters = request.get("parameters");
    if (rawParameters != null && !(rawParameters instanceof List))
      throw new IllegalArgumentException("自定义参数定义必须是 JSON 数组");
    if (rawParameters instanceof List)
      for (Object parameter : (List<?>) rawParameters)
        if (!(parameter instanceof Map))
          throw new IllegalArgumentException("自定义参数定义的每一项必须是 JSON 对象");
    for (Map<String, Object> parameter : steps(request.get("parameters"))) {
      String key = str(parameter.get("fieldKey"));
      if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || key.matches("step[0-9]+Value"))
        throw new IllegalArgumentException("自定义参数编码无效或与步骤参数冲突: " + key);
      for (Map<String, Object> existing : fields)
        if (key.equals(existing.get("fieldKey")))
          throw new IllegalArgumentException("自定义参数编码重复: " + key);
      String type = defaultIfEmpty(str(parameter.get("fieldType")), "STRING").toUpperCase();
      if (!DrlSyntax.types().containsKey(type))
        throw new IllegalArgumentException("自定义参数类型无效: " + type);
      fields.add(
          field(
              key,
              defaultIfEmpty(str(parameter.get("fieldName")), key),
              type,
              str(parameter.get("defaultValue")),
              str(parameter.get("minValue")).isEmpty() ? null : str(parameter.get("minValue")),
              str(parameter.get("maxValue")).isEmpty() ? null : str(parameter.get("maxValue")),
              str(parameter.get("enumOptions")).isEmpty() ? null : str(parameter.get("enumOptions")),
              str(parameter.get("placeholder")),
              fields.size() + 1));
    }
    Set<String> availableExt = new LinkedHashSet<String>();
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
      String condScope = defaultIfEmpty(str(raw.get("condScope")), "DOC").toUpperCase();
      String collectionPath = str(raw.get("collectionPath"));
      int nthIndex = parseInt(raw.get("nthIndex"), -1);
      boolean each = "EACH".equals(condScope);
      boolean nth = "NTH".equals(condScope);
      String actionType = defaultIfEmpty(str(raw.get("actionType")), "MSG").toUpperCase();
      String message = str(raw.get("message"));
      List<Map<String, Object>> outputs = outputs(raw.get("outputs"));
      boolean itemAssignments = !outputs.isEmpty() && (each || nth);

      String valueKey = "step" + no + "Value";
      String marker = "@RULE@_step" + no + "_done";

      if ((each || nth) && collectionPath.isEmpty()) {
        throw new IllegalArgumentException(
            "第 " + no + " 步选了明细行校验，必须填「明细对象」(collectionPath，如 items)");
      }
      if (nth && nthIndex < 0) {
        throw new IllegalArgumentException("第 " + no + " 步选了「第 N 条」校验，必须填「第几条」(nthIndex，从 0 开始)");
      }

      // ---------- 左侧取值表达式 ----------
      String left = null;
      if (!condField.isEmpty()) {
        if (!DrlSyntax.needsValue(condOp)) {
          left = "get(\"" + condField.replace("\\", "\\\\").replace("\"", "\\\"") + "\")";
        } else if (DrlSyntax.looksLikeExpression(condField)
            || DrlSyntax.isNumeric(condType)
            || "NUMBER".equalsIgnoreCase(condType)) {
          left = DrlSyntax.toFactExpression(condField);
        } else {
          left = "getString(\"" + condField + "\")";
        }
      }

      // ---------- 主事实 LHS ----------
      List<String> conds = new ArrayList<String>();
      conds.add("docCode == \"" + docCode + "\"");
      Set<String> upstream = new LinkedHashSet<String>();
      Matcher m = EXT_REF.matcher(condValue);
      while (m.find()) upstream.add(m.group(1));
      if (!itemAssignments) {
        for (String up : upstream) conds.add("ext[\"" + up + "\"] != null");
        // DOC 和旧式集合步骤的标记存在主事实上。
        conds.add("ext[\"" + marker + "\"] == null");
      }

      // ---------- 明细行 LHS ----------
      String linePattern = null;
      if (nth) {
        List<String> lineConds = new ArrayList<String>();
        lineConds.add("docCode == \"" + docCode + "\"");
        lineConds.add("collection == \"" + collectionPath + "\"");
        lineConds.add("index == " + nthIndex);
        if (left != null)
          lineConds.add(DrlSyntax.render(left, condOp, "${" + valueKey + "}", condType));
        linePattern = "$i : DocItem( " + String.join(", ", lineConds) + " )";
        if (itemAssignments) linePattern += itemGuards(marker, upstream);
      } else if (each) {
        List<String> lineConds = new ArrayList<String>();
        lineConds.add("docCode == \"" + docCode + "\"");
        lineConds.add("collection == \"" + collectionPath + "\"");
        if (left != null)
          lineConds.add(DrlSyntax.render(left, condOp, "${" + valueKey + "}", condType));
        if (itemAssignments) {
          linePattern =
              "$i : DocItem( " + String.join(", ", lineConds) + " )" + itemGuards(marker, upstream);
        } else {
          // 旧式 EACH 未配置逐项输出时继续聚合违规行，兼容现有明细校验。
          linePattern =
              "$bad : List( size > 0 ) from accumulate( $i : DocItem( "
                  + String.join(", ", lineConds)
                  + " ), collectList($i) )";
        }
      } else if (left != null) {
        conds.add(DrlSyntax.render(left, condOp, "${" + valueKey + "}", condType));
      }

      String factVar = "$d";
      String factType = "DocFact";

      // ---------- RHS ----------
      StringBuilder rhs = new StringBuilder();
      if (!itemAssignments) {
        rhs.append("        ")
            .append(factVar)
            .append(".getExt().put(\"")
            .append(marker)
            .append("\", true);\n");
        rhs.append("        update(").append(factVar).append(");\n");
      }
      if (each && !itemAssignments) {
        // 每条校验：把违规行列表 + 计数写进 ext，供返回结构/后续步骤引用
        String listKey = "badItems";
        rhs.append("        ")
            .append(factVar)
            .append(".getExt().put(\"")
            .append(listKey)
            .append("\", $bad);\n");
        rhs.append("        ")
            .append(factVar)
            .append(".getExt().put(\"itemFailCount\", $bad.size());\n");
        String eachMsg = message.isEmpty() ? "有 \" + $bad.size() + \" 条明细校验失败" : escape(message);
        rhs.append("        ")
            .append(factVar)
            .append(".addRuleMessage(\"")
            .append(eachMsg)
            .append("\");\n");
        availableExt.add("itemFailCount");
        availableExt.add(listKey);
      } else {
        String defaultMsg;
        if (!"SET_EXT".equals(actionType)) {
          throw new IllegalArgumentException(
              "第 "
                  + no
                  + " 步的动作["
                  + actionType
                  + "]暂不支持："
                  + "引擎只输出决策，步骤动作目前只有「写单据字段」（打标 / 只加消息 先不开放）");
        }
        if (outputs.isEmpty()) throw new IllegalArgumentException("第 " + no + " 步至少配置一个输出字段");
        String target = itemAssignments ? "$i" : factVar;
        for (Map<String, Object> output : outputs) {
          String outputKey = str(output.get("outputKey"));
          String outputValue = str(output.get("expression"));
          String outputType = defaultIfEmpty(str(output.get("valueType")), "STRING").toUpperCase();
          if (outputKey.isEmpty()) throw new IllegalArgumentException("第 " + no + " 步存在未填写字段名的输出");
          String literal = outputLiteral(outputValue, outputType, target);
          rhs.append("        ")
              .append(target)
              .append(".getExt().put(\"")
              .append(escape(outputKey))
              .append("\", ")
              .append(literal)
              .append(");\n");
          availableExt.add(outputKey);
        }
        if (itemAssignments) {
          rhs.append("        $i.getExt().put(\"")
              .append(marker)
              .append("\", true);\n")
              .append("        update($i);\n");
        } else {
          rhs.append("        ")
              .append(factVar)
              .append(".getExt().put(\"")
              .append(marker)
              .append("\", true);\n")
              .append("        update(")
              .append(factVar)
              .append(");\n");
        }
        defaultMsg = "第" + no + "步[" + stepName + "]写入 " + outputs.size() + " 个返回字段";
        rhs.append("        ")
            .append(factVar)
            .append(".addRuleMessage(\"")
            .append(escape(defaultIfEmpty(message, defaultMsg)))
            .append("\");\n");
      }

      if (!condField.isEmpty() && DrlSyntax.needsValue(condOp)) {
        fields.add(
            field(
                valueKey,
                "第" + no + "步 条件取值（" + condField + " " + condOp + "）",
                defaultIfEmpty(condType, DrlSyntax.isNumeric(condType) ? "NUMBER" : "STRING"),
                condValue,
                null,
                null,
                null,
                null,
                no * 10 + 1));
      }

      // when 子句：主事实 +（可选）明细行
      StringBuilder when = new StringBuilder();
      when.append("        ")
          .append(factVar)
          .append(" : ")
          .append(factType)
          .append("( ")
          .append(String.join(", ", conds))
          .append(" )");
      if (linePattern != null) {
        when.append("\n        ").append(linePattern);
      }

      rules
          .append("\nrule \"@RULE@#")
          .append(no)
          .append(" ")
          .append(escape(stepName))
          .append("\"\n")
          .append("    salience ")
          .append(100 - no * 5)
          .append("\n")
          .append("    when\n")
          .append(when)
          .append("\n")
          .append("    then\n")
          .append(rhs)
          .append("    end\n");

      Map<String, Object> sv = new LinkedHashMap<String, Object>();
      sv.put("stepNo", no);
      sv.put("stepName", stepName);
      sv.put("ruleName", ruleType + "#" + no + " " + stepName);
      sv.put("salience", 100 - no * 5);
      sv.put("conditions", conds);
      sv.put("condScope", condScope);
      sv.put("collectionPath", collectionPath);
      sv.put("nthIndex", nthIndex);
      sv.put("actionType", actionType);
      sv.put("upstreamRefs", new ArrayList<String>(upstream));
      sv.put("message", defaultIfEmpty(message, ""));
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
        + "dialect \"mvel\"\n\n"
        + "import java.util.List;\n"
        + "import java.util.ArrayList;\n"
        + "import com.example.drools.domain.DocFact;\n"
        + "import com.example.drools.domain.DocItem;\n"
        + body;
  }

  private Map<String, Object> field(
      String key,
      String name,
      String type,
      String defaultValue,
      String minValue,
      String maxValue,
      String enumOptions,
      String placeholder,
      int sortOrder) {
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

  private int parseInt(Object o, int def) {
    if (o == null) return def;
    String s = String.valueOf(o).trim();
    if (s.isEmpty()) return def;
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return def;
    }
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
      s.setCondScope(defaultIfEmpty(str(m.get("condScope")), "DOC").toUpperCase());
      s.setCollectionPath(str(m.get("collectionPath")));
      Object nthRaw = m.get("nthIndex");
      s.setNthIndex(
          nthRaw == null || String.valueOf(nthRaw).trim().isEmpty()
              ? null
              : Integer.parseInt(String.valueOf(nthRaw).trim()));
      s.setActionType(defaultIfEmpty(str(m.get("actionType")), "MSG").toUpperCase());
      s.setMessage(str(m.get("message")));
      List<Map<String, Object>> outputMaps = outputs(m.get("outputs"));
      List<RuleStepOutput> parsedOutputs = new ArrayList<RuleStepOutput>();
      int outputOrder = 1;
      for (Map<String, Object> outputMap : outputMaps) {
        RuleStepOutput output = new RuleStepOutput();
        output.setOutputKey(str(outputMap.get("outputKey")));
        output.setValueType(
            defaultIfEmpty(str(outputMap.get("valueType")), "STRING").toUpperCase());
        output.setExpression(str(outputMap.get("expression")));
        output.setSortOrder(parseInt(outputMap.get("sortOrder"), outputOrder++));
        parsedOutputs.add(output);
      }
      s.setOutputs(parsedOutputs);
      out.add(s);
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> outputs(Object raw) {
    List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
    if (!(raw instanceof List)) return out;
    for (Object item : (List<Object>) raw)
      if (item instanceof Map) out.add((Map<String, Object>) item);
    return out;
  }

  private String itemGuards(String marker, Set<String> upstream) {
    StringBuilder guards =
        new StringBuilder("\n        eval($i.getExt().get(\"")
            .append(marker)
            .append("\") == null)");
    for (String up : upstream) {
      guards.append("\n        eval($i.getExt().get(\"").append(up).append("\") != null)");
    }
    return guards.toString();
  }

  private String outputLiteral(String expression, String valueType, String target) {
    if ("EXPR".equals(valueType)) {
      return DrlSyntax.toFactExpression(expression)
          .replace("getNumber(\"", target + ".getNumber(\"")
          .replace("getString(\"", target + ".getString(\"");
    }
    if ("NUMBER".equals(valueType) || "DOUBLE".equals(valueType))
      return DrlSyntax.literal(DrlSyntax.normalizePercent(expression), "DOUBLE");
    if ("INT".equals(valueType) || "LONG".equals(valueType))
      return DrlSyntax.literal(expression, "INT");
    if ("DECIMAL".equals(valueType)) return DrlSyntax.literal(expression, "DECIMAL");
    if ("BOOLEAN".equals(valueType)) return DrlSyntax.literal(expression, "BOOLEAN");
    return "\"" + escape(expression) + "\"";
  }
}
