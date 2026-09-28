package com.example.drools.entity;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则类型元数据（对应表 rule_type_meta），内含字段定义列表。
 */
@Data
public class RuleTypeMeta {

    private String ruleType;
    private String ruleGroup;
    private String typeName;
    private String typeDesc;
    private Boolean builtin;
    private Integer sortOrder;
    /**
     * 本类型输出的决策字段（fieldKey 逗号分隔）——在「规则类型」页注册，
     * 评估响应的 decision 块会按这个清单返回，业务方据此对接决策契约。
     */
    private String outputFields;

    /** 该类型的参数字段定义 */
    private List<RuleTypeField> fields = new ArrayList<>();
}
