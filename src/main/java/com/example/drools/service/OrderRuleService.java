package com.example.drools.service;

import com.example.drools.domain.Order;
import com.example.drools.domain.OrderItem;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Service;

/**
 * 订单规则服务：预处理数据 -> 从动态引擎取 KieSession 执行规则 -> 汇总金额。
 */
@Service
public class OrderRuleService {

    private final DynamicRuleEngine ruleEngine;

    public OrderRuleService(DynamicRuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    public Order evaluate(Order order) {
        // ---- 数据预处理（非规则逻辑） ----
        double total = 0;
        for (OrderItem item : order.getItems()) {
            double subtotal = item.getProduct().getPrice() * item.getQuantity();
            item.setSubtotal(subtotal);
            total += subtotal;
        }
        order.setTotalAmount(total);
        order.setDiscount(0);
        order.setShippingFee(0);
        order.setFinalAmount(0);
        order.setRejected(false);
        order.getMessages().clear();

        // ---- 插入事实并执行规则 ----
        KieSession session = ruleEngine.newKieSession();
        try {
            session.insert(order);
            session.insert(order.getCustomer());
            for (OrderItem item : order.getItems()) {
                session.insert(item);
                session.insert(item.getProduct());
            }
            // 走引擎的带上限点火：规则写错不收敛时只会告警，不会把线程挂死
            ruleEngine.fireAllRules(session);
        } finally {
            session.dispose();
        }

        // ---- 金额汇总（规则只累加优惠/运费，最终金额由服务层计算） ----
        if (!order.isRejected()) {
            order.setFinalAmount(order.getTotalAmount() - order.getDiscount() + order.getShippingFee());
        } else {
            order.setFinalAmount(0);
        }
        return order;
    }
}
