package com.example.drools.entity;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 规则类型元数据（对应表 rule_type_meta），内含字段定义列表。 */
@Data
public class RuleTypeMeta {

  private String ruleType;
  private String ruleGroup;
  private String typeName;
  private String typeDesc;
  private String docCode;
  private Boolean builtin;
  private Integer sortOrder;


  /** 该类型的参数字段定义 */
  private List<RuleTypeField> fields = new ArrayList<>();
}
