package com.example.drools.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单据字段（对应 rule_document_field）：所属对象 + 中文名 + fieldName 取值路径 + 说明。
 * 页面配规则参数 / HTTP 接口入参时从这里挑；运行期由 MVEL 按 field_key 取值。
 */
@Data
public class RuleDocumentField {

    private Long id;
    private String docCode;
    /** 所属对象（rule_document_object.object_key），如 order / customer / items */
    private String objectKey;
    /** 字段中文名 */
    private String fieldName;
    /** 取值路径：支持 a.b.c、数组下标 items[0].sku、方法 items.size() */
    private String fieldKey;
    private String fieldType;
    /** 试调用时可一键填入的示例值 */
    private String exampleValue;
    private Integer sortOrder;
    /** 字段说明 */
    private String fieldDesc;
    /**
     * 派生字段的计算表达式（可空）：非空表示这个字段不是报文原样带的，而是算出来的，
     * 例如 毛利率 = (price - cost) / price。入口层用 MVEL 按表达式算好后放进单据 data。
     */
    private String expr;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
