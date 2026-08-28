package com.example.drools.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单明细项
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderItem {

    /** 商品 */
    private Product product;

    /** 购买数量 */
    private int quantity;

    /** 小计 = 单价 * 数量（由 Service 在规则执行前计算） */
    private double subtotal;

    public OrderItem(Product product, int quantity) {
        this.product = product;
        this.quantity = quantity;
    }
}
