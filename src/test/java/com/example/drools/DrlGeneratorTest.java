package com.example.drools;

import com.example.drools.dao.RuleTemplateDao;
import com.example.drools.dao.RuleTypeMetaDao;
import com.example.drools.domain.Order;
import com.example.drools.entity.RuleTemplate;
import com.example.drools.entity.RuleTypeField;
import com.example.drools.service.DrlGenerator;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DrlGenerator 纯单元测试（mock 模板/元数据 DAO，不连数据库）。
 */
class DrlGeneratorTest {

    @Test
    void templateRenderAndCompile() {
        RuleTemplateDao templateDao = mock(RuleTemplateDao.class);
        RuleTypeMetaDao metaDao = mock(RuleTypeMetaDao.class);

        RuleTemplate tpl = new RuleTemplate();
        tpl.setTemplateBody(
                "    when\n" +
                "        $o : Order( rejected == false, totalAmount >= ${threshold} )\n" +
                "    then\n" +
                "        $o.setDiscount($o.getDiscount() + ${reduction});\n" +
                "        $o.addMessage(\"满 ${threshold} 减 ${reduction}\");\n");
        when(templateDao.findByType("FULL_REDUCTION")).thenReturn(tpl);
        when(metaDao.findFields("FULL_REDUCTION")).thenReturn(Arrays.asList(
                field("threshold", "NUMBER"), field("reduction", "NUMBER")));

        DrlGenerator g = new DrlGenerator(templateDao, metaDao);
        String drl = g.generate("FULL_REDUCTION", "FULL_REDUCTION", "{\"threshold\":1000,\"reduction\":100}");

        assertFalse(drl.contains("${"));
        assertTrue(drl.contains("totalAmount >= 1000"));
        assertTrue(drl.contains("setDiscount($o.getDiscount() + 100)"));

        // 编译并执行，验证规则生效
        KieHelper helper = new KieHelper();
        helper.addContent(drl, ResourceType.DRL);
        KieBase base = helper.build();

        Order order = new Order();
        order.setOrderId("T");
        order.setTotalAmount(1500);
        KieSession session = base.newKieSession();
        try {
            session.insert(order);
            session.fireAllRules();
        } finally {
            session.dispose();
        }
        assertEquals(100.0, order.getDiscount(), 0.001);
    }

    @Test
    void csvParamConvertedToList() {
        RuleTemplateDao templateDao = mock(RuleTemplateDao.class);
        RuleTypeMetaDao metaDao = mock(RuleTypeMetaDao.class);

        RuleTemplate tpl = new RuleTemplate();
        tpl.setTemplateBody("    when\n" +
                "        $o : Order( rejected == false, customer.region in (${regions}) )\n" +
                "    then\n" +
                "        $o.setShippingFee($o.getShippingFee() + ${fee});\n");
        when(templateDao.findByType("REGION_FREIGHT")).thenReturn(tpl);
        when(metaDao.findFields("REGION_FREIGHT")).thenReturn(Arrays.asList(
                field("regions", "CSV"), field("fee", "NUMBER")));

        DrlGenerator g = new DrlGenerator(templateDao, metaDao);
        String drl = g.generate("REGION_FREIGHT", "REGION_FREIGHT", "{\"regions\":\"新疆,西藏\",\"fee\":30}");
        assertTrue(drl.contains("in (\"新疆\", \"西藏\")"));
    }

    @Test
    void missingTemplateThrows() {
        RuleTemplateDao templateDao = mock(RuleTemplateDao.class);
        RuleTypeMetaDao metaDao = mock(RuleTypeMetaDao.class);
        when(templateDao.findByType("NO_SUCH")).thenReturn(null);

        DrlGenerator g = new DrlGenerator(templateDao, metaDao);
        assertThrows(IllegalArgumentException.class,
                () -> g.generate("NO_SUCH", "X", "{}"));
    }

    private RuleTypeField field(String key, String type) {
        RuleTypeField f = new RuleTypeField();
        f.setFieldKey(key);
        f.setFieldType(type);
        return f;
    }
}
