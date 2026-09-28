package com.example.drools.service;

import com.example.drools.domain.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.runtime.KieSession;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * "调用方要不要把'跑哪些规则'当参数传进来" —— 不用传规则清单，但可以用 agenda-group 按业务聚焦。
 *
 * 结论（本测试验证）：同一份规则集里，带 agenda-group 的规则只有在该组被 setFocus 后才会点火；
 * 所以"商品业务只跑商品规则、订单业务只跑订单规则"可以做成：
 *   模板里写一行 agenda-group "${bizGroup}"（= 规则类型/参数的一部分，运营可配）
 *   调用方 session.getAgenda().getAgendaGroup("order").setFocus()
 * 不需要把规则名/规则类型列表当参数传给引擎。
 */
class AgendaGroupScopeTest {

    private static final String DRL =
            "package com.example.drools.scope;\n" +
            "dialect \"mvel\"\n" +
            "import com.example.drools.domain.Order;\n" +
            "rule \"order-rule\"\n" +
            "    agenda-group \"order\"\n" +
            "    when\n" +
            "        $o : Order( rejected == false )\n" +
            "    then\n" +
            "        $o.addMessage(\"订单业务规则命中\");\n" +
            "end\n" +
            "rule \"product-rule\"\n" +
            "    agenda-group \"product\"\n" +
            "    when\n" +
            "        $o : Order( rejected == false )\n" +
            "    then\n" +
            "        $o.addMessage(\"商品业务规则命中\");\n" +
            "end\n";

    private KieBase build() {
        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        kfs.write("src/main/resources/rules/scope.drl", DRL);
        KieBuilder builder = ks.newKieBuilder(kfs);
        builder.buildAll();
        if (builder.getResults().hasMessages(Message.Level.ERROR)) {
            throw new IllegalStateException(builder.getResults().getMessages().toString());
        }
        return ks.newKieContainer(ks.getRepository().getDefaultReleaseId()).getKieBase();
    }

    private Order fire(KieBase base, String focusGroup) {
        KieSession session = base.newKieSession();
        Order order = new Order();
        order.setOrderId("SCOPE-1");
        try {
            session.insert(order);
            if (focusGroup != null) {
                session.getAgenda().getAgendaGroup(focusGroup).setFocus();
            }
            session.fireAllRules(50);
        } finally {
            session.dispose();
        }
        return order;
    }

    @Test
    @DisplayName("不聚焦任何组：两条 group 规则都不点火")
    void withoutFocusNothingFires() {
        assertEquals(0, fire(build(), null).getMessages().size());
    }

    @Test
    @DisplayName("聚焦 order 组：只有订单业务规则点火（商品业务规则被隔离）")
    void focusOrderOnly() {
        Order order = fire(build(), "order");
        assertEquals(1, order.getMessages().size());
        assertEquals("订单业务规则命中", order.getMessages().get(0));
    }

    @Test
    @DisplayName("聚焦 product 组：只有商品业务规则点火")
    void focusProductOnly() {
        Order order = fire(build(), "product");
        assertEquals(1, order.getMessages().size());
        assertEquals("商品业务规则命中", order.getMessages().get(0));
    }
}
