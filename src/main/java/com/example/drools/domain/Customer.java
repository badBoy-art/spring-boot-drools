package com.example.drools.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 客户
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Customer {

    /** 客户 ID */
    private Long id;

    /** 客户姓名 */
    private String name;

    /** 客户等级：NORMAL / GOLD / VIP */
    private String level;

    /** 收货区域：北京 / 上海 / 新疆 / 西藏 / 内蒙古 / 境外 ... */
    private String region;

    /** 年龄 */
    private int age;

    /** 是否新客 */
    private boolean newCustomer;
}
