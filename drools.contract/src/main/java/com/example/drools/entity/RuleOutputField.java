package com.example.drools.entity;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 返回值结构（rule_output_field）：规则类型注册的「返回结果结构」是一棵可嵌套、可计算赋值的树， 评估响应的 decision 由它组装。
 *
 * <p>节点三种类型： OBJECT —— 容器（子节点挂 parent_path） ARRAY —— 数组（array_from 指定从 ext/data 的哪个 List 取整段） LEAF
 * —— 叶子，source 决定取值方式： EXT 取 ext[source_value]（规则写进去的决策值） DATA 取 data[source_value]（单据原值/派生值） EXPR
 * 用 MVEL 计算 source_value（多字段算式，引擎算好再放进去） CONST 字面量（source_value 按 value_type 转成对应类型）
 */
@Data
public class RuleOutputField {

  private Long id;
  private String ruleType;

  /** 节点路径，点分隔，如 approval / approval.level / badItems */
  private String outputPath;

  /** 父节点路径；空=根 */
  private String parentPath;

  /** 显示名 */
  private String label;

  /** OBJECT / ARRAY / LEAF */
  private String nodeKind;

  /** LEAF 的取值类型：STRING/NUMBER/DECIMAL/INT/BOOLEAN */
  private String valueType;

  /** LEAF 的取值来源：EXT / DATA / EXPR / CONST */
  private String source;

  /** LEAF 的取值（字段名 / 路径 / 算式 / 字面量） */
  private String sourceValue;

  /** ARRAY 的数据来源：ext.badItems / data.items */
  private String arrayFrom;

  private Integer sortOrder;
  private LocalDateTime createTime;
}
