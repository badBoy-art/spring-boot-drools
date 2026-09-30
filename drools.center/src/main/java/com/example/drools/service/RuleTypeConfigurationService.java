package com.example.drools.service;

import com.example.drools.dao.RuleDefinitionDao;
import com.example.drools.dao.RuleDocumentDao;
import com.example.drools.dao.RuleStepDao;
import com.example.drools.dao.RuleTypeMetaDao;
import com.example.drools.entity.RuleTypeField;
import com.example.drools.entity.RuleTypeMeta;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;

/** 规则类型注册（页面 ③）：运营按"单据对象/字段 + 接口"新建规则类型 —— 生成类型元数据 + 参数定义 + DRL 模板体，不改 Java、不用重启。 */
@org.springframework.stereotype.Service
public class RuleTypeConfigurationService {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)\\}");

  private final RuleTypeMetaDao metaDao;
  private final RuleDocumentDao documentDao;
  private final RuleStepBuilder stepBuilder;
  private final RuleStepDao stepDao;
  private final com.example.drools.dao.RuleOutputDao outputDao;
  private final RuleDefinitionDao ruleDefinitionDao;
  private final DrlGenerator drlGenerator;
  private final com.example.drools.release.RuleReleaseService releases;

  public RuleTypeConfigurationService(
      RuleTypeMetaDao metaDao,
      RuleDocumentDao documentDao,
      RuleStepBuilder stepBuilder,
      RuleStepDao stepDao,
      com.example.drools.dao.RuleOutputDao outputDao,
      RuleDefinitionDao ruleDefinitionDao,
      DrlGenerator drlGenerator,
      com.example.drools.release.RuleReleaseService releases) {
    this.metaDao = metaDao;
    this.documentDao = documentDao;
    this.stepBuilder = stepBuilder;
    this.stepDao = stepDao;
    this.outputDao = outputDao;
    this.ruleDefinitionDao = ruleDefinitionDao;
    this.drlGenerator = drlGenerator;
    this.releases = releases;
  }

  /** Native types do not require a document, a form template, or an output tree. */
  @Transactional
  public RuleTypeMeta registerNative(RuleTypeMeta meta) {
    if (meta.getRuleType() == null || !meta.getRuleType().matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
      throw new IllegalArgumentException(
          "ruleType must be a stable identifier (letters, digits, underscore)");
    if (meta.getTypeName() == null || meta.getTypeName().trim().isEmpty())
      throw new IllegalArgumentException("typeName is required");
    if (meta.getDocCode() != null
        && !meta.getDocCode().trim().isEmpty()
        && documentDao.findDocument(meta.getDocCode()) == null)
      throw new IllegalArgumentException("Document is not registered");
    if (metaDao.findByType(meta.getRuleType()) != null)
      throw new com.example.drools.release.ReleaseConflictException("Rule type already exists");
    meta.setBuiltin(false);
    metaDao.insertMeta(meta);
    return metaDao.findByType(meta.getRuleType());
  }

  /** 全部规则类型（含参数定义与模板体，页面列表/编辑用） */
  public List<Map<String, Object>> list() {
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    for (RuleTypeMeta meta : metaDao.findAll()) {
      result.add(view(meta));
    }
    return result;
  }

  /** 语法清单：页面下拉的【运算符 + 取值类型】全部来自 DrlSyntax（单一真源，不在前端重写一份） */
  public Map<String, Object> syntax() {
    Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
    out.put("operators", com.example.drools.service.DrlSyntax.operators());
    out.put("types", com.example.drools.service.DrlSyntax.types());
    out.put("valueHint", valueHints());
    return out;
  }

  /** SDK 拉取指定类型的配置和已发布 DRL；DRL 原文不做模板转换。 */
  public com.fasterxml.jackson.databind.JsonNode runtimeBundle(String ruleType) {
    return releases.current(ruleType);
  }

  /** 每种运算符对"取值"的填法提示（页面输入框旁边显示） */
  private Map<String, String> valueHints() {
    Map<String, String> hints = new java.util.LinkedHashMap<String, String>();
    hints.put("in", "逗号分隔，如 服装,数码");
    hints.put("not in", "逗号分隔，如 服装,数码");
    hints.put("memberOf", "集合表达式，如 ${ext.allowedRegions}");
    hints.put("not memberOf", "集合表达式");
    hints.put("matches", "Java 正则可写，如 ^SKU.*");
    hints.put("not matches", "Java 正则");
    hints.put("startsWith", "前缀文本，如 SKU");
    hints.put("endsWith", "后缀文本，如 .pdf");
    hints.put("== null", "（不需要填）");
    hints.put("!= null", "（不需要填）");
    return hints;
  }

  /** 返回值结构（可嵌套 + 计算赋值）—— 详情页读当前树 */
  public List<com.example.drools.entity.RuleOutputField> outputTree(String ruleType) {
    return outputDao.findByType(ruleType);
  }

  /**
   * 保存返回值结构（覆盖式）：body = {ruleType,
   * fields:[{outputPath,parentPath,label,nodeKind,valueType,source,sourceValue,arrayFrom,sortOrder}]}
   */
  @Transactional
  public Map<String, Object> saveOutputTree(Map<String, Object> body) {
    String ruleType = str(body.get("ruleType"));
    if (ruleType.isEmpty()) {
      throw new IllegalArgumentException("ruleType 必填");
    }
    if (metaDao.findByType(ruleType) == null) {
      throw new IllegalArgumentException("规则类型不存在: " + ruleType);
    }
    releases.lockType(ruleType);
    List<com.example.drools.entity.RuleOutputField> fields =
        parseOutputFields(ruleType, body.get("fields"));
    outputDao.replaceAll(ruleType, fields);
    Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
    out.put("ruleType", ruleType);
    out.put("fields", outputDao.findByType(ruleType));
    out.put("message", "已保存返回值结构草稿（" + fields.size() + " 个节点），重新发布规则类型后 decision 按它组装");
    return out;
  }

  private List<com.example.drools.entity.RuleOutputField> parseOutputFields(
      String ruleType, Object raw) {
    List<com.example.drools.entity.RuleOutputField> fields =
        new java.util.ArrayList<com.example.drools.entity.RuleOutputField>();
    if (raw instanceof java.util.Collection) {
      int order = 1;
      for (Object item : (java.util.Collection<?>) raw) {
        if (!(item instanceof Map)) throw new IllegalArgumentException("返回结构的每个节点都必须是 JSON 对象");
        Map<?, ?> m = (Map<?, ?>) item;
        com.example.drools.entity.RuleOutputField f =
            new com.example.drools.entity.RuleOutputField();
        f.setRuleType(ruleType);
        f.setOutputPath(str(m.get("outputPath")));
        f.setParentPath(emptyToNull(m.get("parentPath")));
        f.setLabel(defaultLabel(m.get("label"), f.getOutputPath()));
        String nodeKind = str(m.get("nodeKind"));
        f.setNodeKind((nodeKind.isEmpty() ? "LEAF" : nodeKind).toUpperCase());
        f.setValueType(emptyToNull(m.get("valueType")));
        String source = emptyToNull(m.get("source"));
        f.setSource(source == null ? null : source.toUpperCase());
        f.setSourceValue(emptyToNull(m.get("sourceValue")));
        f.setArrayFrom(emptyToNull(m.get("arrayFrom")));
        f.setSortOrder(
            m.get("sortOrder") == null
                ? order
                : Integer.parseInt(String.valueOf(m.get("sortOrder"))));
        order++;
        if (f.getOutputPath().isEmpty())
          throw new IllegalArgumentException("返回结构里每个节点都要有 outputPath");
        fields.add(f);
      }
    } else if (raw != null) {
      throw new IllegalArgumentException("返回结构必须是 JSON 数组");
    }
    validateOutputTree(fields);
    return fields;
  }

  /** 保存前校验 JSON 结构，确保每个路径都能组成一棵有类型的输出树。 */
  private void validateOutputTree(List<com.example.drools.entity.RuleOutputField> fields) {
    Map<String, com.example.drools.entity.RuleOutputField> byPath =
        new LinkedHashMap<String, com.example.drools.entity.RuleOutputField>();
    for (com.example.drools.entity.RuleOutputField f : fields) {
      String path = f.getOutputPath();
      if (!path.matches(
          "[A-Za-z_\\u4e00-\\u9fa5][A-Za-z0-9_\\u4e00-\\u9fa5]*(\\.[A-Za-z_\\u4e00-\\u9fa5][A-Za-z0-9_\\u4e00-\\u9fa5]*)*")) {
        throw new IllegalArgumentException("返回字段路径格式无效: " + path);
      }
      if (byPath.put(path, f) != null) {
        throw new IllegalArgumentException("返回字段路径重复: " + path);
      }
      String kind = f.getNodeKind();
      if (!"OBJECT".equals(kind) && !"ARRAY".equals(kind) && !"LEAF".equals(kind)) {
        throw new IllegalArgumentException("返回字段节点类型只支持 OBJECT / ARRAY / LEAF: " + path);
      }
      if ("LEAF".equals(kind)) {
        String source = f.getSource();
        if (source == null
            || !("EXT".equals(source)
                || "DATA".equals(source)
                || "EXPR".equals(source)
                || "CONST".equals(source))) {
          throw new IllegalArgumentException("叶子字段取值来源只支持 EXT / DATA / EXPR / CONST: " + path);
        }
        if (f.getSourceValue() == null || f.getSourceValue().trim().isEmpty()) {
          throw new IllegalArgumentException("叶子字段必须填写 sourceValue: " + path);
        }
      }
      if ("ARRAY".equals(kind) && (f.getArrayFrom() == null || f.getArrayFrom().trim().isEmpty())) {
        throw new IllegalArgumentException(
            "数组节点必须填写 arrayFrom（例如 data.items 或 ext.badItems）: " + path);
      }
    }
    for (com.example.drools.entity.RuleOutputField f : fields) {
      String parent = f.getParentPath();
      if (parent == null || parent.trim().isEmpty()) {
        if (f.getOutputPath().contains(".")) {
          throw new IllegalArgumentException("根节点 outputPath 不能包含父路径: " + f.getOutputPath());
        }
        continue;
      }
      if (!f.getOutputPath().startsWith(parent + ".")
          || f.getOutputPath().lastIndexOf('.') != parent.length()) {
        throw new IllegalArgumentException(
            "outputPath 必须是 parentPath 的直接子节点: " + f.getOutputPath());
      }
      com.example.drools.entity.RuleOutputField p = byPath.get(parent);
      if (p == null)
        throw new IllegalArgumentException("父节点不存在: " + parent + "（字段 " + f.getOutputPath() + "）");
      if (!"OBJECT".equals(p.getNodeKind()) && !"ARRAY".equals(p.getNodeKind())) {
        throw new IllegalArgumentException("父节点必须为 OBJECT 或 ARRAY: " + parent);
      }
      if (parent.equals(f.getOutputPath()) || parent.startsWith(f.getOutputPath() + ".")) {
        throw new IllegalArgumentException("返回结构不能形成循环引用: " + f.getOutputPath());
      }
      Set<String> chain = new LinkedHashSet<String>();
      com.example.drools.entity.RuleOutputField cursor = f;
      while (cursor != null && cursor.getParentPath() != null) {
        if (!chain.add(cursor.getOutputPath())) {
          throw new IllegalArgumentException("返回结构不能形成循环引用: " + cursor.getOutputPath());
        }
        cursor = byPath.get(cursor.getParentPath());
      }
    }
  }

  private String defaultLabel(Object label, String path) {
    String l = str(label);
    return l.isEmpty() ? (path == null ? "" : path.substring(path.lastIndexOf('.') + 1)) : l;
  }

  private String emptyToNull(Object o) {
    String s = str(o);
    return s.isEmpty() ? null : s;
  }

  /** 单个类型详情 */
  public Map<String, Object> detail(String ruleType) {
    RuleTypeMeta meta = metaDao.findByType(ruleType);
    if (meta == null) {
      throw new IllegalArgumentException("规则类型不存在: " + ruleType);
    }
    return view(meta);
  }

  /** 页面 ③「生成模板」：多步骤编排（请求里带 steps[]）；只预览不落库 */
  public Map<String, Object> build(Map<String, Object> request) {
    validateSources(request);
    return mergeMeta(stepBuilder.build(request), request);
  }

  /** 步骤链详情（规则类型详情页用）：steps + 生成的 DRL + 可引用变量 */
  public Map<String, Object> steps(String ruleType) {
    Map<String, Object> out = new LinkedHashMap<String, Object>();
    out.put("ruleType", ruleType);
    out.put("steps", stepDao.findByType(ruleType));
    return out;
  }

  /** 取值转字符串（null 安全） */
  private String str(Object o) {
    return o == null ? "" : String.valueOf(o).trim();
  }

  /** 多步骤模式：把类型级元数据补齐（RuleStepBuilder 只负责 steps/字段/模板） */
  private Map<String, Object> mergeMeta(Map<String, Object> built, Map<String, Object> request) {
    if (!built.containsKey("ruleType")) {
      built.put("ruleType", String.valueOf(request.get("ruleType")));
    }
    if (!built.containsKey("docCode")) {
      built.put("docCode", request.get("docCode"));
    }
    if (!built.containsKey("typeName")) {
      Object name = request.get("typeName");
      if (name == null || String.valueOf(name).trim().isEmpty()) {
        throw new IllegalArgumentException("类型名称(typeName)必填");
      }
      built.put("typeName", String.valueOf(name));
    }
    if (!built.containsKey("ruleGroup")) {
      Object g = request.get("ruleGroup");
      built.put(
          "ruleGroup", g == null || String.valueOf(g).isEmpty() ? "action" : String.valueOf(g));
    }
    if (!built.containsKey("typeDesc")) {
      built.put("typeDesc", request.get("typeDesc"));
    }
    if (!built.containsKey("sortOrder")) {
      Object o = request.get("sortOrder");
      built.put(
          "sortOrder",
          o == null || String.valueOf(o).isEmpty() ? 90 : Integer.parseInt(String.valueOf(o)));
    }
    if (!built.containsKey("builtin")) {
      built.put("builtin", false);
    }
    return built;
  }

  /** 保存规则类型（元数据 + 参数 + 模板体）；内置类型不允许覆盖 */
  @SuppressWarnings("unchecked")
  @Transactional
  public Map<String, Object> save(Map<String, Object> request) {
    String ruleType = String.valueOf(request.get("ruleType"));
    if (!ruleType.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
      throw new IllegalArgumentException("Invalid rule type code");
    RuleTypeMeta existing = metaDao.findByType(ruleType);
    if (existing != null && Boolean.TRUE.equals(existing.getBuiltin())) {
      throw new IllegalArgumentException("内置类型不允许覆盖: " + ruleType + "（内置类型的模板由系统维护；要新逻辑请用新的类型编码）");
    }
    if (existing != null) releases.lockType(ruleType);
    validateSources(request);

    List<com.example.drools.entity.RuleOutputField> outputFields =
        parseOutputFields(ruleType, request.get("outputStructure"));
    if (outputFields.isEmpty()) throw new IllegalArgumentException("规则类型必须注册至少一个返回结构 JSON 节点");

    Map<String, Object> built = mergeMeta(stepBuilder.build(request), request);
    List<Map<String, Object>> fields = (List<Map<String, Object>>) built.get("fields");
    String templateBody = String.valueOf(built.get("templateBody"));

    // 校验：模板里用到的每个 ${xxx} 都必须有参数定义（否则发布规则时会报"未替换的占位符"）
    Set<String> declared = new LinkedHashSet<String>();
    for (Map<String, Object> f : fields) {
      declared.add(String.valueOf(f.get("fieldKey")));
    }
    Matcher matcher = PLACEHOLDER.matcher(templateBody);
    while (matcher.find()) {
      if (!declared.contains(matcher.group(1))) {
        throw new IllegalArgumentException(
            "模板里的占位符 ${" + matcher.group(1) + "} 没有对应的参数定义（参数： " + declared + "）");
      }
    }

    RuleTypeMeta meta = new RuleTypeMeta();
    meta.setRuleType(ruleType);
    meta.setRuleGroup(String.valueOf(built.get("ruleGroup")));
    meta.setTypeName(String.valueOf(built.get("typeName")));
    meta.setTypeDesc(built.get("typeDesc") == null ? null : String.valueOf(built.get("typeDesc")));
    meta.setDocCode(String.valueOf(built.get("docCode")));
    meta.setBuiltin(false);
    meta.setSortOrder(Integer.parseInt(String.valueOf(built.get("sortOrder"))));
    metaDao.upsertMeta(meta);

    metaDao.deleteFields(ruleType);
    for (Map<String, Object> f : fields) {
      RuleTypeField field = new RuleTypeField();
      field.setRuleType(ruleType);
      field.setFieldKey(String.valueOf(f.get("fieldKey")));
      field.setFieldName(String.valueOf(f.get("fieldName")));
      field.setFieldType(String.valueOf(f.get("fieldType")));
      field.setRequired(true);
      field.setDefaultValue(
          f.get("defaultValue") == null ? null : String.valueOf(f.get("defaultValue")));
      field.setMinValue(f.get("minValue") == null ? null : String.valueOf(f.get("minValue")));
      field.setMaxValue(f.get("maxValue") == null ? null : String.valueOf(f.get("maxValue")));
      field.setEnumOptions(
          f.get("enumOptions") == null ? null : String.valueOf(f.get("enumOptions")));
      field.setPlaceholder(
          f.get("placeholder") == null ? null : String.valueOf(f.get("placeholder")));
      field.setSortOrder(
          f.get("sortOrder") == null ? 1 : Integer.parseInt(String.valueOf(f.get("sortOrder"))));
      metaDao.upsertField(field);
    }
    metaDao.upsertTemplate(ruleType, templateBody);

    // 多步骤模式：同步保存步骤链（详情页/再次编辑用；单条件模式的类型这里会清空旧步骤）
    stepDao.replaceAll(ruleType, stepBuilder.toEntities(request.get("steps")));
    outputDao.replaceAll(ruleType, outputFields);

    Map<String, Object> result = view(metaDao.findByType(ruleType));
    result.put("drlPreview", built.get("drlPreview"));
    if (built.get("steps") != null) {
      result.put("steps", built.get("steps"));
    }
    result.put("message", "规则类型已保存，去「④ 规则配置」里就能选到它");
    return result;
  }

  /** 删除自定义规则类型（该类型下还有规则则拒绝） */
  @Transactional
  public Map<String, Object> delete(String ruleType) {
    RuleTypeMeta meta = metaDao.findByType(ruleType);
    if (meta == null) {
      throw new IllegalArgumentException("规则类型不存在: " + ruleType);
    }
    if (Boolean.TRUE.equals(meta.getBuiltin())) {
      throw new IllegalArgumentException("内置类型不允许删除: " + ruleType);
    }
    int rules = metaDao.countRules(ruleType);
    if (rules > 0) {
      throw new IllegalArgumentException("该类型下还有 " + rules + " 条规则，请先删除规则再删类型");
    }
    releases.lockType(ruleType);
    if (!releases.history(ruleType).isEmpty())
      throw new IllegalArgumentException(
          "Published type codes cannot be deleted or reused; publish an empty bundle to"
              + " deactivate");
    metaDao.deleteType(ruleType);
    stepDao.deleteByType(ruleType);
    outputDao.deleteByType(ruleType);
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("message", "已删除规则类型 " + ruleType);
    return result;
  }

  /** 校验绑定的单据是否已注册 */
  private void validateSources(Map<String, Object> request) {
    String docCode = request.get("docCode") == null ? null : String.valueOf(request.get("docCode"));
    if (docCode == null || docCode.trim().isEmpty()) {
      throw new IllegalArgumentException("请选择单据（规则类型必须绑定已注册的单据编码）");
    }
    if (documentDao.findDocument(docCode) == null) {
      throw new IllegalArgumentException("单据未注册: " + docCode + "（请先在「① 单据注册」里登记）");
    }
  }

  private Map<String, Object> view(RuleTypeMeta meta) {
    Map<String, Object> node = new LinkedHashMap<String, Object>();
    node.put("ruleType", meta.getRuleType());
    node.put("ruleGroup", meta.getRuleGroup());
    node.put("typeName", meta.getTypeName());
    node.put("typeDesc", meta.getTypeDesc());
    node.put("docCode", meta.getDocCode());
    node.put("builtin", Boolean.TRUE.equals(meta.getBuiltin()));
    node.put("sortOrder", meta.getSortOrder());
    node.put("outputNodeCount", outputDao.findByType(meta.getRuleType()).size());
    node.put("fields", meta.getFields());
    node.put("templateBody", metaDao.findTemplate(meta.getRuleType()));
    node.put("ruleCount", metaDao.countRules(meta.getRuleType()));
    return node;
  }
}
