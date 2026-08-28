package com.example.drools.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商品（SKU）
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Product {

    /** 商品 ID */
    private Long id;

    /** 商品名称 */
    private String name;

    /** 商品分类：电子产品 / 服装 / 食品 / 生鲜 ... */
    private String category;

    /** 单价 */
    private double price;

    /** 当前库存 */
    private int stock;

    /** 是否上架 */
    private boolean onShelf;
}
