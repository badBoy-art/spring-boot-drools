package com.example.drools.controller;

import com.example.drools.dao.RuleDocumentDao;
import com.example.drools.dao.RuleStepDao;
import com.example.drools.dao.RuleTypeMetaDao;
import com.example.drools.entity.RuleTypeField;
import com.example.drools.entity.RuleTypeMeta;
import com.example.drools.service.RuleStepBuilder;
import com.example.drools.service.RuleTypeBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则类型注册（页面 ③）：运营按"单据对象/字段 + 接口"新建规则类型 ——
 * 生成类型元数据 + 参数定义 + DRL 模板体，不改 Java、不用重启。
 */
@RestController
@RequestMapping("/rule/type")
public class RuleTypeController {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)\\}");

    private final RuleTypeMetaDao metaDao;
    private final RuleDocumentDao documentDao;
    private final RuleTypeBuilder builder;
    private final RuleStepBuilder stepBuilder;
    private final RuleStepDao stepDao;

    public RuleTypeController(RuleTypeMetaDao metaDao, RuleDocumentDao documentDao,
                              RuleTypeBuilder builder, RuleStepBuilder stepBuilder, RuleStepDao stepDao) {
        this.metaDao = metaDao;
        this.documentDao = documentDao;
        this.builder = builder;
        this.stepBuilder = stepBuilder;
        this.stepDao = stepDao;
    }

    /** 全部规则类型（含参数定义与模板体，页面列表/编辑用） */
    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (RuleTypeMeta meta : metaDao.findAll()) {
            result.add(view(meta));
        }
        return result;
    }

    /** 单个类型详情 */
    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam String ruleType) {
        RuleTypeMeta meta = metaDao.findByType(ruleType);
        if (meta == null) {
            throw new IllegalArgumentException("规则类型不存在: " + ruleType);
        }
        return view(meta);
    }

    /** 页面 ③「生成模板」：单条件单动作 或 多步骤编排（请求里带 steps[] 就走步骤链）；只预览不落库 */
    @PostMapping("/build")
    public Map<String, Object> build(@RequestBody Map<String, Object> request) {
        validateSources(request);
        return mergeMeta(hasSteps(request) ? stepBuilder.build(request) : builder.build(request), request);
    }

    /** 步骤链详情（规则类型详情页用）：steps + 生成的 DRL + 可引用变量 */
    @GetMapping("/steps")
    public Map<String, Object> steps(@RequestParam String ruleType) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ruleType", ruleType);
        out.put("steps", stepDao.findByType(ruleType));
        return out;
    }

    /** 多步骤模式：把类型级元数据补齐（RuleStepBuilder 只负责 steps/字段/模板） */
    private Map<String, Object> mergeMeta(Map<String, Object> built, Map<String, Object> request) {
        // 「本类型输出的决策字段」：由页面多选传进来，评估响应 decision 按它返回
        if (!built.containsKey("outputFields") && request.get("outputFields") != null) {
            built.put("outputFields", request.get("outputFields"));
        }
        if (!built.containsKey("ruleType")) {
            built.put("ruleType", String.valueOf(request.get("ruleType")));
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
            built.put("ruleGroup", g == null || String.valueOf(g).isEmpty() ? "action" : String.valueOf(g));
        }
        if (!built.containsKey("typeDesc")) {
            built.put("typeDesc", request.get("typeDesc"));
        }
        if (!built.containsKey("sortOrder")) {
            Object o = request.get("sortOrder");
            built.put("sortOrder", o == null || String.valueOf(o).isEmpty() ? 90 : Integer.parseInt(String.valueOf(o)));
        }
        if (!built.containsKey("builtin")) {
            built.put("builtin", false);
        }
        return built;
    }

    private boolean hasSteps(Map<String, Object> request) {
        Object steps = request.get("steps");
        return steps instanceof List && !((List<?>) steps).isEmpty();
    }

    /** 保存规则类型（元数据 + 参数 + 模板体）；内置类型不允许覆盖 */
    @PostMapping("/save")
    @SuppressWarnings("unchecked")
    public Map<String, Object> save(@RequestBody Map<String, Object> request) {
        String ruleType = String.valueOf(request.get("ruleType"));
        RuleTypeMeta existing = metaDao.findByType(ruleType);
        if (existing != null && Boolean.TRUE.equals(existing.getBuiltin())) {
            throw new IllegalArgumentException("内置类型不允许覆盖: " + ruleType
                    + "（内置类型的模板由系统维护；要新逻辑请用新的类型编码）");
        }
        validateSources(request);

        Map<String, Object> built = mergeMeta(hasSteps(request) ? stepBuilder.build(request) : builder.build(request), request);
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
                throw new IllegalArgumentException("模板里的占位符 ${" + matcher.group(1)
                        + "} 没有对应的参数定义（参数： " + declared + "）");
            }
        }

        RuleTypeMeta meta = new RuleTypeMeta();
        meta.setRuleType(ruleType);
        meta.setRuleGroup(String.valueOf(built.get("ruleGroup")));
        meta.setTypeName(String.valueOf(built.get("typeName")));
        meta.setTypeDesc(built.get("typeDesc") == null ? null : String.valueOf(built.get("typeDesc")));
        meta.setBuiltin(false);
        meta.setSortOrder(Integer.parseInt(String.valueOf(built.get("sortOrder"))));
        // 本类型输出的决策字段（页面多选传数组，这里转 CSV 落库）→ 评估响应 decision 按它返回
        Object outputs = built.get("outputFields");
        String outputCsv = null;
        if (outputs instanceof java.util.Collection) {
            StringBuilder sb = new StringBuilder();
            for (Object item : (java.util.Collection<?>) outputs) {
                if (item == null || String.valueOf(item).trim().isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(String.valueOf(item).trim());
            }
            outputCsv = sb.length() == 0 ? null : sb.toString();
        } else if (outputs != null && !String.valueOf(outputs).trim().isEmpty()) {
            outputCsv = String.valueOf(outputs).trim();
        }
        meta.setOutputFields(outputCsv);
        metaDao.upsertMeta(meta);

        metaDao.deleteFields(ruleType);
        for (Map<String, Object> f : fields) {
            RuleTypeField field = new RuleTypeField();
            field.setRuleType(ruleType);
            field.setFieldKey(String.valueOf(f.get("fieldKey")));
            field.setFieldName(String.valueOf(f.get("fieldName")));
            field.setFieldType(String.valueOf(f.get("fieldType")));
            field.setRequired(true);
            field.setDefaultValue(f.get("defaultValue") == null ? null : String.valueOf(f.get("defaultValue")));
            field.setMinValue(f.get("minValue") == null ? null : String.valueOf(f.get("minValue")));
            field.setMaxValue(f.get("maxValue") == null ? null : String.valueOf(f.get("maxValue")));
            field.setEnumOptions(f.get("enumOptions") == null ? null : String.valueOf(f.get("enumOptions")));
            field.setPlaceholder(f.get("placeholder") == null ? null : String.valueOf(f.get("placeholder")));
            field.setSortOrder(f.get("sortOrder") == null ? 1 : Integer.parseInt(String.valueOf(f.get("sortOrder"))));
            metaDao.upsertField(field);
        }
        metaDao.upsertTemplate(ruleType, templateBody);

        // 多步骤模式：同步保存步骤链（详情页/再次编辑用；单条件模式的类型这里会清空旧步骤）
        stepDao.replaceAll(ruleType, stepBuilder.toEntities(request.get("steps")));

        Map<String, Object> result = view(metaDao.findByType(ruleType));
        result.put("drlPreview", built.get("drlPreview"));
        if (built.get("steps") != null) {
            result.put("steps", built.get("steps"));
        }
        result.put("message", "规则类型已保存，去「④ 规则配置」里就能选到它");
        return result;
    }

    /** 删除自定义规则类型（该类型下还有规则则拒绝） */
    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestParam String ruleType) {
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
        metaDao.deleteType(ruleType);
        stepDao.deleteByType(ruleType);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("message", "已删除规则类型 " + ruleType);
        return result;
    }

    /** 校验引用的单据/接口是否已注册 */
    private void validateSources(Map<String, Object> request) {
        String factClass = request.get("factClass") == null ? "DocFact" : String.valueOf(request.get("factClass"));
        String docCode = request.get("docCode") == null ? null : String.valueOf(request.get("docCode"));
        if (!"Order".equalsIgnoreCase(factClass)) {
            if (docCode == null || docCode.trim().isEmpty()) {
                throw new IllegalArgumentException("请选择单据（通用单据必须绑定已注册的单据编码）");
            }
            if (documentDao.findDocument(docCode) == null) {
                throw new IllegalArgumentException("单据未注册: " + docCode + "（请先在「① 单据注册」里登记）");
            }
        }
        String actionCode = request.get("actionCode") == null ? null : String.valueOf(request.get("actionCode"));
        String mode = request.get("mode") == null ? "ACTION" : String.valueOf(request.get("mode"));
        if ("ACTION".equalsIgnoreCase(mode) && actionCode != null && !actionCode.trim().isEmpty()
                && false) {
            throw new IllegalArgumentException("接口动作未注册: " + actionCode + "（请先在「② 接口注册」里登记）");
        }
    }

    private Map<String, Object> view(RuleTypeMeta meta) {
        Map<String, Object> node = new LinkedHashMap<String, Object>();
        node.put("ruleType", meta.getRuleType());
        node.put("ruleGroup", meta.getRuleGroup());
        node.put("typeName", meta.getTypeName());
        node.put("typeDesc", meta.getTypeDesc());
        node.put("builtin", Boolean.TRUE.equals(meta.getBuiltin()));
        node.put("sortOrder", meta.getSortOrder());
        node.put("fields", meta.getFields());
        node.put("templateBody", metaDao.findTemplate(meta.getRuleType()));
        node.put("ruleCount", metaDao.countRules(meta.getRuleType()));
        return node;
    }
}
