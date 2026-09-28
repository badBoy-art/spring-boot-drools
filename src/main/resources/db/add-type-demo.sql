-- 演示：纯 SQL 新增一个规则类型（Java 一行不改、应用不重启）
-- 一个"规则类型"= 3 张表各一行/几行：rule_type_meta（类型本身）+ rule_type_field（参数字段）+ rule_template（DRL 模板体）
-- 用法：mysql -h127.0.0.1 -uroot -pzhaoZ1230 --default-character-set=utf8mb4 test < add-type-demo.sql

-- 1) 类型本身
INSERT INTO rule_type_meta (rule_type, rule_group, type_name, type_desc, builtin, sort_order)
VALUES ('AGE_DISCOUNT', 'customer', '年龄优惠', '年长客户下单按比例优惠', 0, 98);

-- 2) 参数字段（页面表单的控件、校验范围都由这两行决定）
INSERT INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, min_value, max_value, placeholder, sort_order)
VALUES ('AGE_DISCOUNT', 'minAge', '年龄下限', 'NUMBER', 1, '60', '0', NULL, '如 60', 1);

INSERT INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, min_value, max_value, placeholder, sort_order)
VALUES ('AGE_DISCOUNT', 'discountRate', '折扣率', 'DECIMAL', 1, '0.05', '0', '1', '0.05 表示 95 折', 2);

-- 3) DRL 模板体：${fieldKey} 是占位符；只能引用 DrlGenerator HEADER 里已 import 的类
--    （com.example.drools.domain.{Order,OrderItem,Product,Customer}）
INSERT INTO rule_template (rule_type, template_body) VALUES ('AGE_DISCOUNT',
'    when
        $o : Order( rejected == false, customer.age >= ${minAge} )
    then
        double saving = $o.getTotalAmount() * ${discountRate};
        $o.setDiscount($o.getDiscount() + saving);
        $o.addMessage("年长客户（满 ${minAge} 岁）优惠 " + saving + " 元");');
