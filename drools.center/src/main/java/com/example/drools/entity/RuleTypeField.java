package com.example.drools.entity;

import lombok.Data;

/** 规则类型字段元数据（对应表 rule_type_field）。 描述某种规则类型需要配置的参数字段，管理后台据此渲染表单，后端据此校验参数。 */
@Data
public class RuleTypeField {

  private Long id;
  private String ruleType;

  /** 参数 key，如 threshold / regions */
  private String fieldKey;

  /** 字段中文名 */
  private String fieldName;

  /** 字段类型：STRING / NUMBER / DECIMAL / ENUM / CSV */
  private String fieldType;

  /** 是否必填 */
  private Boolean required;

  /** 默认值 */
  private String defaultValue;

  /** 最小值（NUMBER / DECIMAL） */
  private String minValue;

  /** 最大值 */
  private String maxValue;

  /** 枚举选项，逗号分隔（ENUM） */
  private String enumOptions;

  /** 占位提示 */
  private String placeholder;

  private Integer sortOrder;

  private String remark;
}
