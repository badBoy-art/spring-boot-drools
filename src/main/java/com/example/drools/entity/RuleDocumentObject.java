package com.example.drools.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单据对象（对应 rule_document_object）：支持嵌套。
 * 例：订单(order) → 客户(customer) → 订单明细(items，数组) → 明细商品(items.product)
 */
@Data
public class RuleDocumentObject {

    private Long id;
    private String docCode;
    /** 对象标识：order / customer / items / variables */
    private String objectKey;
    /** 对象中文名：订单 / 客户 / 订单明细 */
    private String objectName;
    /** 父对象标识；空表示单据根对象 */
    private String parentObjectKey;
    /** 1=数组对象（规则/表达式里按 items[0] 取） */
    private Integer isCollection;
    /** 该对象在单据里的取值路径，如 customer / items[0] */
    private String valuePath;
    private String remark;
    private Integer sortOrder;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
