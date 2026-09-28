package com.example.drools.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 单据注册（对应 rule_document）：业务上接入规则引擎的单据，如 订单 / 商品 */
@Data
public class RuleDocument {

    private Long id;
    private String docCode;
    private String docName;
    private String remark;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
