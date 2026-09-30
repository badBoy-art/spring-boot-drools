package com.example.drools.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** 单个规则步骤的一个输出赋值；支持一个 when 对多个结果字段赋值。 */
@Data
public class RuleStepOutput {
  private Long id;
  private String ruleType;
  private Integer stepNo;
  private String outputKey;
  private String valueType;
  private String expression;
  private Integer sortOrder;
  private LocalDateTime createTime;
}
