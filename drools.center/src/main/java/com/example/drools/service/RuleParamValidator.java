package com.example.drools.service;

import com.example.drools.dao.RuleTypeMetaDao;
import com.example.drools.entity.RuleTypeField;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 规则参数校验器：用 rule_type_field 元数据校验规则参数， 让「前端表单校验」与「后端发布前校验」共用同一份 schema。 */
@Component
public class RuleParamValidator {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final RuleTypeMetaDao metaDao;

  public RuleParamValidator(RuleTypeMetaDao metaDao) {
    this.metaDao = metaDao;
  }

  /** 校验规则参数 JSON，不合法抛 IllegalArgumentException */
  public void validate(String ruleType, String ruleParamsJson) {
    Map<String, Object> params = parse(ruleParamsJson);
    List<RuleTypeField> fields = metaDao.findFields(ruleType);
    for (RuleTypeField f : fields) {
      Object v = params.get(f.getFieldKey());
      String name = f.getFieldName() + "(" + f.getFieldKey() + ")";

      if (Boolean.TRUE.equals(f.getRequired())
          && (v == null || String.valueOf(v).trim().isEmpty())) {
        throw new IllegalArgumentException("参数 " + name + " 必填");
      }
      if (v == null) {
        continue;
      }

      switch (f.getFieldType()) {
        case "NUMBER":
        case "DECIMAL":
          // 数字参数接受三种写法：数字、数字字符串、"50%"（百分比会在生成 DRL 时换算成 0.5）
          Double num = toNumber(String.valueOf(v));
          if (num == null) {
            throw new IllegalArgumentException("参数 " + name + " 必须是数字（可写 0.5 或 50%）");
          }
          checkRange(f, name, num);
          break;
        case "ENUM":
          String sv = String.valueOf(v);
          if (f.getEnumOptions() != null
              && !Arrays.asList(f.getEnumOptions().split(",")).contains(sv)) {
            throw new IllegalArgumentException("参数 " + name + " 必须是以下之一: " + f.getEnumOptions());
          }
          break;
        case "CSV":
        case "STRING":
          if (String.valueOf(v).trim().isEmpty()) {
            throw new IllegalArgumentException("参数 " + name + " 不能为空");
          }
          break;
        default:
          // 未知类型不校验
      }
    }
  }

  /** 数字写法兼容：数字 / "0.5" / "50%" → double；解析不了返回 null */
  private Double toNumber(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return null;
    }
    String v = DrlSyntax.normalizePercent(raw.trim());
    try {
      return Double.valueOf(v);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private void checkRange(RuleTypeField f, String name, double value) {
    if (f.getMinValue() != null && value < Double.parseDouble(f.getMinValue())) {
      throw new IllegalArgumentException("参数 " + name + " 不能小于 " + f.getMinValue());
    }
    if (f.getMaxValue() != null && value > Double.parseDouble(f.getMaxValue())) {
      throw new IllegalArgumentException("参数 " + name + " 不能大于 " + f.getMaxValue());
    }
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
