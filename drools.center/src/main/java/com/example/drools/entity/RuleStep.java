package com.example.drools.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 规则类型下的一个步骤：条件（可空）+ 动作（写单据字段）；校验范围支持 单据级 / 明细·每条 / 明细·第N条 */
@Data
public class RuleStep {

  private Long id;
  private String ruleType;
  private Integer stepNo;
  private String stepName;

  /** 条件：取值路径 + 运算符 + 取值类型 + 取值（成为该步骤的参数默认值） */
  private String condField;

  private String condOp;
  private String condType;
  private String condValue;

  /** 校验范围：DOC 单据级（默认）/ EACH 明细·每条 / NTH 明细·第N条 */
  private String condScope;

  /** 明细对象 key（EACH/NTH 时必填，如 items） */
  private String collectionPath;

  /** 第 N 条下标（NTH 时用，0-based） */
  private Integer nthIndex;

  /** 动作：SET_EXT 写单据字段 */
  private String actionType;


  private String message;

  /** 当前 when/then 命中时可同时赋值的多个字段。 */
  private List<RuleStepOutput> outputs = new ArrayList<RuleStepOutput>();

  private LocalDateTime createTime;
}
