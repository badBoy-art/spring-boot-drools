package com.example.drools.service;

import com.example.drools.dao.RuleTemplateDao;
import com.example.drools.dao.RuleTypeMetaDao;
import com.example.drools.entity.RuleDefinition;
import com.example.drools.entity.RuleTemplate;
import com.example.drools.entity.RuleTypeField;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 规则生成器（数据驱动）：
 * 从 rule_template 表读取 DRL 模板体，用规则参数替换 ${paramKey} 占位符，
 * 再拼接统一的 header + rule 名 + end。新增规则类型只需插模板/元数据，无需改代码。
 *
 * 占位符替换规则（依据 rule_type_field 的 field_type）：
 *   - NUMBER/DECIMAL/STRING/ENUM：直接替换为原始值（字符串参数由模板自行加引号）
 *   - CSV：自动转成带引号列表 "v1", "v2", ...
 */
@Component
public class DrlGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String HEADER =
            "package com.example.drools.dynamic;\n\n" +
            "dialect \"mvel\"\n\n" +
            "import com.example.drools.domain.Order;\n" +
            "import com.example.drools.domain.OrderItem;\n" +
            "import com.example.drools.domain.Product;\n" +
            "import com.example.drools.domain.Customer;\n" +
            "import com.example.drools.domain.DocFact;\n\n";

    private final RuleTemplateDao templateDao;
    private final RuleTypeMetaDao metaDao;

    public DrlGenerator(RuleTemplateDao templateDao, RuleTypeMetaDao metaDao) {
        this.templateDao = templateDao;
        this.metaDao = metaDao;
    }

    public String generate(RuleDefinition rule) {
        return generate(rule.getRuleType(), rule.getRuleName(), rule.getRuleParams());
    }

    public String generate(String ruleType, String ruleName, String ruleParamsJson) {
        RuleTemplate tpl = templateDao.findByType(ruleType);
        if (tpl == null || tpl.getTemplateBody() == null || tpl.getTemplateBody().trim().isEmpty()) {
            throw new IllegalArgumentException("规则类型未配置模板: " + ruleType);
        }

        Map<String, Object> params = parse(ruleParamsJson);
        Map<String, String> fieldTypes = fieldTypeMap(ruleType);
        String body = render(tpl.getTemplateBody(), params, fieldTypes);

        if (body.contains("${")) {
            // ${ext.xxx} 是运行期引用（由网关渲染入参时解析），不算"未替换的占位符"；
            // 其它 ${...} 都必须被参数替换掉，否则说明类型参数没配对。
            java.util.regex.Matcher leftover = java.util.regex.Pattern.compile("\\$\\{(?!ext[.\\[\\-])").matcher(body);
            if (leftover.find()) {
                throw new IllegalArgumentException("模板存在未替换的占位符: " + body);
            }
        }
        // 多步骤模板（③ 的步骤链）本身就是**完整 DRL**（含 rule...end），不能再套外层 rule；
        // 里面的 @RULE@ 会被替换成规则名，保证同类型多条规则时规则名不重复。
        if (body.contains("rule \"")) {
            return HEADER + body.replace("@RULE@", ruleName) + "\n";
        }
        return HEADER + "rule \"" + ruleName + "\"\n" + body + "\nend\n";
    }

    private Map<String, String> fieldTypeMap(String ruleType) {
        Map<String, String> map = new HashMap<>();
        for (RuleTypeField f : metaDao.findFields(ruleType)) {
            map.put(f.getFieldKey(), f.getFieldType());
        }
        return map;
    }

    private String render(String template, Map<String, Object> params, Map<String, String> fieldTypes) {
        String result = template;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String key = e.getKey();
            String type = fieldTypes.getOrDefault(key, "STRING");
            String value = "CSV".equals(type)
                    ? toDrlList(String.valueOf(e.getValue()))
                    : String.valueOf(e.getValue());
            result = result.replace("${" + key + "}", value);
        }
        return result;
    }

    /** 逗号分隔串 -> drl 列表：新疆,西藏 -> "新疆", "西藏" */
    private String toDrlList(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(", "));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        try {
            if (json == null || json.trim().isEmpty()) {
                return new HashMap<>();
            }
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("规则参数不是合法 JSON: " + json, e);
        }
    }
}
