package com.example.drools.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单：规则执行的聚合上下文对象。
 * 各类规则（商品/价格/区域/库存/客户/接口动作）都通过修改该对象来表达结果。
 */
@Data
@NoArgsConstructor
public class Order implements RuleFact {

    /** 订单号 */
    private String orderId;

    /** 下单客户 */
    private Customer customer;

    /** 订单明细 */
    private List<OrderItem> items = new ArrayList<>();

    /** 商品总额（规则执行前由 Service 计算） */
    private double totalAmount;

    /** 优惠总金额（各类优惠规则累加） */
    private double discount;

    /** 运费（区域规则累加） */
    private double shippingFee;

    /** 最终应付 = 总额 - 优惠 + 运费 */
    private double finalAmount;

    /** 是否被规则拒绝下单 */
    private boolean rejected;

    /** 规则执行过程消息（用于观察每条规则的命中情况） */
    private List<String> messages = new ArrayList<>();

    /**
     * 动态字段区：HTTP 动作的返回值按配置回填到这里（ext["riskLevel"] 等），
     * 后续规则可以直接用 ext["xxx"] 作为条件继续执行。规则里用 $o.getExt().put(...) 也能自己写。
     */
    private Map<String, Object> ext = new LinkedHashMap<>();

    public void addMessage(String message) {
        messages.add(message);
    }

    @Override
    public void addRuleMessage(String message) {
        addMessage(message);
    }
}
