-- =====================================================================
-- 规则 DRL 模板（数据驱动：新增规则类型无需改 Java 代码）
-- 占位符约定：
--   ${paramKey}  引用 rule_params 里的参数；NUMBER/DECIMAL/STRING/ENUM 直接替换
--                CSV 类型自动转成带引号列表 "v1", "v2", ...
--   STRING/ENUM 参数在模板里需自行加引号，如 category == "${category}"
-- =====================================================================

DROP TABLE IF EXISTS rule_template;
CREATE TABLE rule_template (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_type     VARCHAR(32) NOT NULL UNIQUE COMMENT '规则模板类型(与 rule_type_meta 一致)',
  template_body TEXT NOT NULL COMMENT 'DRL规则体(when/then, ${paramKey}占位符引用参数)',
  create_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则DRL模板';

-- =====================================================================
-- 10 种类型的模板体（DrlGenerator 统一拼 header + rule名 + 模板体 + end）
-- =====================================================================

-- 商品下架校验（固定）
INSERT INTO rule_template (rule_type, template_body) VALUES
('PRODUCT_OFF_SHELF',
'    salience 100
    when
        $o : Order( rejected == false )
        $item : OrderItem( product.onShelf == false, $p : product )
    then
        $o.setRejected(true);
        update($o);
        $o.addMessage("商品[" + $p.getName() + "] 已下架，无法下单");');

-- 商品分类标记
INSERT INTO rule_template (rule_type, template_body) VALUES
('PRODUCT_CATEGORY_MARK',
'    when
        $o : Order( rejected == false )
        $p : Product( category == "${category}" )
    then
        $o.addMessage("商品[" + $p.getName() + "] ${message}");');

-- 满减
INSERT INTO rule_template (rule_type, template_body) VALUES
('FULL_REDUCTION',
'    when
        $o : Order( rejected == false, totalAmount >= ${threshold} )
    then
        $o.setDiscount($o.getDiscount() + ${reduction});
        $o.addMessage("满 ${threshold} 减 ${reduction}，优惠 ${reduction} 元");');

-- 批量折扣
INSERT INTO rule_template (rule_type, template_body) VALUES
('BATCH_DISCOUNT',
'    when
        $o : Order( rejected == false )
        $item : OrderItem( quantity >= ${minQuantity}, $p : product )
    then
        double saving = $item.getSubtotal() * ${discountRate};
        $o.setDiscount($o.getDiscount() + saving);
        $o.addMessage("商品[" + $p.getName() + "] 购买 " + $item.getQuantity() + " 件享批量折扣，省 " + saving + " 元");');

-- 会员折扣
INSERT INTO rule_template (rule_type, template_body) VALUES
('VIP_DISCOUNT',
'    when
        $o : Order( rejected == false, customer.level == "${level}" )
    then
        double saving = $o.getTotalAmount() * ${discountRate};
        $o.setDiscount($o.getDiscount() + saving);
        $o.addMessage("${level} 会员享会员折扣，优惠 " + saving + " 元");');

-- 新客立减
INSERT INTO rule_template (rule_type, template_body) VALUES
('NEW_CUSTOMER_REDUCTION',
'    when
        $o : Order( rejected == false, customer.newCustomer == true )
    then
        $o.setDiscount($o.getDiscount() + ${reduction});
        $o.addMessage("新客专享立减 ${reduction} 元");');

-- 区域运费
INSERT INTO rule_template (rule_type, template_body) VALUES
('REGION_FREIGHT',
'    when
        $o : Order( rejected == false, customer.region in (${regions}) )
    then
        $o.setShippingFee($o.getShippingFee() + ${fee});
        $o.addMessage("偏远地区[" + $o.getCustomer().getRegion() + "] 加收运费 ${fee} 元");');

-- 区域禁售
INSERT INTO rule_template (rule_type, template_body) VALUES
('REGION_BLOCK',
'    salience 100
    when
        $o : Order( rejected == false, customer.region in (${regions}) )
    then
        $o.setRejected(true);
        update($o);
        $o.addMessage("当前区域[" + $o.getCustomer().getRegion() + "] 不支持配送");');

-- 库存不足校验（固定）
INSERT INTO rule_template (rule_type, template_body) VALUES
('STOCK_CHECK',
'    salience 100
    when
        $o : Order( rejected == false )
        $item : OrderItem( quantity > product.stock, $p : product )
    then
        $o.setRejected(true);
        update($o);
        $o.addMessage("商品[" + $p.getName() + "] 库存不足：当前库存 " + $p.getStock() + "，购买 " + $item.getQuantity());');

-- 库存预警
INSERT INTO rule_template (rule_type, template_body) VALUES
('STOCK_WARNING',
'    when
        $o : Order( rejected == false )
        $item : OrderItem( $p : product, product.stock < ${threshold} )
    then
        $o.addMessage("商品[" + $p.getName() + "] 库存紧张（剩余 " + $p.getStock() + " 件）");');

-- =====================================================================
-- 自定义类型示例模板（BIG_ORDER_TAG，纯数据驱动新增）
-- =====================================================================
INSERT INTO rule_template (rule_type, template_body) VALUES
('BIG_ORDER_TAG',
'    when
        $o : Order( rejected == false, totalAmount >= ${threshold} )
    then
        $o.addMessage("大额订单，需风控审核");');
