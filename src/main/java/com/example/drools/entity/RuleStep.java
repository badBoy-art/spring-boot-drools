package com.example.drools.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 规则类型下的一个步骤：条件（可空）+ 动作（调接口 / 写单据字段 / 打标 / 只加消息） */
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

    /** 动作：CALL 调接口 / SET_EXT 写单据字段 / MARK 打标 / MSG 只加消息 */
    private String actionType;
    private String actionCode;
    private String extField;
    private String extValue;
    /** 动作写值的类型：STRING / NUMBER / DECIMAL / BOOLEAN / EXPR（EXPR = 写多字段算式的结果） */
    private String extValueType;
    private String message;
    /** 本步入参覆写（JSON，如 {"level":"${ext.approvalLevel}"}）：留空=用接口注册的入参模板 */
    private String paramJson;

    private LocalDateTime createTime;
}
