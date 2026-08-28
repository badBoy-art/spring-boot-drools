package com.example.drools.controller;

import com.example.drools.domain.Customer;
import com.example.drools.domain.Order;
import com.example.drools.domain.OrderItem;
import com.example.drools.domain.Product;
import com.example.drools.service.OrderRuleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Collections;

/**
 * 订单规则演示接口
 */
@RestController
@RequestMapping("/order")
public class OrderController {

    private final OrderRuleService orderRuleService;

    public OrderController(OrderRuleService orderRuleService) {
        this.orderRuleService = orderRuleService;
    }

    /**
     * 演示用例：VIP 新客、新疆收货、购买电子产品 + 服装，命中全部 5 类规则。
     * GET /order/demo
     */
    @GetMapping("/demo")
    public Order demo() {
        Customer customer = new Customer(1L, "张三", "VIP", "新疆", 30, true);

        Product iphone = new Product(1L, "iPhone 15 Pro", "电子产品", 5000, 100, true);
        Product tshirt = new Product(2L, "纯棉T恤", "服装", 200, 5, true);

        Order order = new Order();
        order.setOrderId("SO-20260827-0001");
        order.setCustomer(customer);
        order.setItems(Arrays.asList(
                new OrderItem(iphone, 1),
                new OrderItem(tshirt, 3)
        ));
        return orderRuleService.evaluate(order);
    }

    /**
     * 演示用例：库存不足被拒绝。
     * GET /order/demo-rejected
     */
    @GetMapping("/demo-rejected")
    public Order demoRejected() {
        Customer customer = new Customer(2L, "李四", "NORMAL", "北京", 25, false);

        Product macbook = new Product(3L, "MacBook Pro", "电子产品", 15000, 2, true);

        Order order = new Order();
        order.setOrderId("SO-20260827-0002");
        order.setCustomer(customer);
        order.setItems(Collections.singletonList(new OrderItem(macbook, 5))); // 库存 2，买 5 -> 库存不足
        return orderRuleService.evaluate(order);
    }

    /**
     * 通用评估接口：传入完整订单 JSON，返回规则执行结果。
     * POST /order/evaluate
     */
    @PostMapping("/evaluate")
    public Order evaluate(@RequestBody Order order) {
        return orderRuleService.evaluate(order);
    }
}
