package com.example.drools.domain;

import java.util.Map;

/**
 * 能参与规则引擎的"单据"需要实现它：
 *   getExt() —— HTTP 动作调用后，返回值回填到这里；后续规则用 ext["xxx"] 读取（链式规则的数据通道）
 *   addRuleMessage() —— 写过程消息，便于观察命中情况
 * 订单（Order）已实现；商品或其它单据接入时实现同一接口即可。
 */
public interface RuleFact {

    /** 动态字段区（接口返回值回填区） */
    Map<String, Object> getExt();

    /** 写一条规则执行过程消息 */
    void addRuleMessage(String message);
}
