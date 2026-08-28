package com.example.drools.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规则定义（对应表 rule_definition）。
 * 运营在管理后台配置规则参数，代码据此生成 DRL 全文并持久化到这里。
 */
@Data
public class RuleDefinition {

    /** 主键 */
    private Long id;

    /** 规则组：product / price / region / inventory / customer */
    private String ruleGroup;

    /** 规则模板类型（见 RuleTypeEnum） */
    private String ruleType;

    /** 规则名称（唯一，同时作为 drl 的 rule 名） */
    private String ruleName;

    /** 规则参数 JSON，如 {"threshold":1000,"reduction":100} */
    private String ruleParams;

    /** 生成的 DRL 全文 */
    private String drlContent;

    /** 状态：0 草稿 / 1 已发布 / 2 已禁用 */
    private Integer status;

    /** 版本号 */
    private Integer version;

    /** 备注 */
    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
