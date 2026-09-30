package com.example.drools.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** 规则 DRL 模板（对应表 rule_template）。 模板体含 ${paramKey} 占位符，由 DrlGenerator 渲染成最终 DRL。 */
@Data
public class RuleTemplate {

  private Long id;

  /** 规则模板类型 */
  private String ruleType;

  /** DRL 规则体（when/then，含 ${paramKey} 占位符） */
  private String templateBody;

  private LocalDateTime createTime;

  private LocalDateTime updateTime;
}
