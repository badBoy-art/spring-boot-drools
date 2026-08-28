-- =====================================================================
-- Spring Boot + Drools 动态规则闭环 —— 初始化脚本
-- 数据库: test   (MySQL 5.7)
-- =====================================================================

DROP TABLE IF EXISTS rule_definition;
CREATE TABLE rule_definition (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  rule_group  VARCHAR(32)  NOT NULL COMMENT '规则组: product/price/region/inventory/customer',
  rule_type   VARCHAR(32)  NOT NULL COMMENT '规则模板类型',
  rule_name   VARCHAR(64)  NOT NULL COMMENT '规则名称(对应drl的rule名)',
  rule_params VARCHAR(512) NOT NULL DEFAULT '{}' COMMENT '规则参数JSON',
  drl_content TEXT COMMENT '生成的DRL全文',
  status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0草稿 1已发布 2已禁用',
  version     INT          NOT NULL DEFAULT 1 COMMENT '版本号',
  remark      VARCHAR(255) DEFAULT NULL COMMENT '备注',
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_name (rule_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则定义表';

-- =====================================================================
-- 种子规则（10 条，覆盖 5 类），status=1 已发布，drl_content 为最终生成的 DRL 全文
-- =====================================================================

-- 【商品规则】商品下架校验
INSERT INTO rule_definition (rule_group, rule_type, rule_name, rule_params, drl_content, status, remark) VALUES
('product','PRODUCT_OFF_SHELF','OFF_SHELF_CHECK','{}',
'package com.example.drools.dynamic;

dialect "mvel"

import com.example.drools.domain.Order;
import com.example.drools.domain.OrderItem;
import com.example.drools.domain.Product;
import com.example.drools.domain.Customer;

rule "OFF_SHELF_CHECK"
    salience 100
    when
        $o : Order( rejected == false )
        $item : OrderItem( product.onShelf == false, $p : product )
    then
        $o.setRejected(true);
        update($o);
        $o.addMessage("商品[" + $p.getName() + "] 已下架，无法下单");
end', 1, '商品下架禁售');

-- 【商品规则】电子产品特殊配送标记
INSERT INTO rule_definition (rule_group, rule_type, rule_name, rule_params, drl_content, status, remark) VALUES
('product','PRODUCT_CATEGORY_MARK','CATEGORY_MARK','{"category":"电子产品","message":"属于高价值电子产品，需特殊配送"}',
'package com.example.drools.dynamic;

dialect "mvel"

import com.example.drools.domain.Order;
import com.example.drools.domain.OrderItem;
import com.example.drools.domain.Product;
import com.example.drools.domain.Customer;

rule "CATEGORY_MARK"
    when
        $o : Order( rejected == false )
        $p : Product( category == "电子产品" )
    then
        $o.addMessage("商品[" + $p.getName() + "] 属于高价值电子产品，需特殊配送");
end', 1, '电子产品标记');

-- 【价格规则】满减
INSERT INTO rule_definition (rule_group, rule_type, rule_name, rule_params, drl_content, status, remark) VALUES
('price','FULL_REDUCTION','FULL_REDUCTION','{"threshold":1000,"reduction":100}',
'package com.example.drools.dynamic;

dialect "mvel"

import com.example.drools.domain.Order;
import com.example.drools.domain.OrderItem;
import com.example.drools.domain.Product;
import com.example.drools.domain.Customer;

rule "FULL_REDUCTION"
    when
        $o : Order( rejected == false, totalAmount >= 1000 )
    then
        $o.setDiscount($o.getDiscount() + 100);
        $o.addMessage("满 1000 减 100，优惠 100 元");
end', 1, '满1000减100');

-- 【价格规则】批量折扣
INSERT INTO rule_definition (rule_group, rule_type, rule_name, rule_params, drl_content, status, remark) VALUES
('price','BATCH_DISCOUNT','BATCH_DISCOUNT','{"minQuantity":3,"discountRate":0.1}',
'package com.example.drools.dynamic;

dialect "mvel"

import com.example.drools.domain.Order;
import com.example.drools.domain.OrderItem;
import com.example.drools.domain.Product;
import com.example.drools.domain.Customer;

rule "BATCH_DISCOUNT"
    when
        $o : Order( rejected == false )
        $item : OrderItem( quantity >= 3, $p : product )
    then
        double saving = $item.getSubtotal() * 0.1;
        $o.setDiscount($o.getDiscount() + saving);
        $o.addMessage("商品[" + $p.getName() + "] 购买 " + $item.getQuantity() + " 件，享 9 折，省 " + saving + " 元");
end', 1, '单商品>=3件享9折');

-- 【客户规则】VIP 折扣
INSERT INTO rule_definition (rule_group, rule_type, rule_name, rule_params, drl_content, status, remark) VALUES
('customer','VIP_DISCOUNT','VIP_DISCOUNT','{"level":"VIP","discountRate":0.05}',
'package com.example.drools.dynamic;

dialect "mvel"

import com.example.drools.domain.Order;
import com.example.drools.domain.OrderItem;
import com.example.drools.domain.Product;
import com.example.drools.domain.Customer;

rule "VIP_DISCOUNT"
    when
        $o : Order( rejected == false, customer.level == "VIP" )
    then
        double vipSaving = $o.getTotalAmount() * 0.05;
        $o.setDiscount($o.getDiscount() + vipSaving);
        $o.addMessage("VIP 会员享 95 折，优惠 " + vipSaving + " 元");
end', 1, 'VIP会员95折');

-- 【客户规则】新客立减
INSERT INTO rule_definition (rule_group, rule_type, rule_name, rule_params, drl_content, status, remark) VALUES
('customer','NEW_CUSTOMER_REDUCTION','NEW_CUSTOMER_REDUCTION','{"reduction":20}',
'package com.example.drools.dynamic;

dialect "mvel"

import com.example.drools.domain.Order;
import com.example.drools.domain.OrderItem;
import com.example.drools.domain.Product;
import com.example.drools.domain.Customer;

rule "NEW_CUSTOMER_REDUCTION"
    when
        $o : Order( rejected == false, customer.newCustomer == true )
    then
        $o.setDiscount($o.getDiscount() + 20);
        $o.addMessage("新客专享立减 20 元");
end', 1, '新客立减20');

-- 【区域规则】偏远地区运费
INSERT INTO rule_definition (rule_group, rule_type, rule_name, rule_params, drl_content, status, remark) VALUES
('region','REGION_FREIGHT','REGION_FREIGHT','{"regions":"新疆,西藏,内蒙古,青海","fee":30}',
'package com.example.drools.dynamic;

dialect "mvel"

import com.example.drools.domain.Order;
import com.example.drools.domain.OrderItem;
import com.example.drools.domain.Product;
import com.example.drools.domain.Customer;

rule "REGION_FREIGHT"
    when
        $o : Order( rejected == false, customer.region in ("新疆", "西藏", "内蒙古", "青海") )
    then
        $o.setShippingFee($o.getShippingFee() + 30);
        $o.addMessage("偏远地区[" + $o.getCustomer().getRegion() + "] 加收运费 30 元");
end', 1, '偏远地区加运费30');

-- 【区域规则】境外禁售
INSERT INTO rule_definition (rule_group, rule_type, rule_name, rule_params, drl_content, status, remark) VALUES
('region','REGION_BLOCK','REGION_BLOCK','{"regions":"境外"}',
'package com.example.drools.dynamic;

dialect "mvel"

import com.example.drools.domain.Order;
import com.example.drools.domain.OrderItem;
import com.example.drools.domain.Product;
import com.example.drools.domain.Customer;

rule "REGION_BLOCK"
    salience 100
    when
        $o : Order( rejected == false, customer.region in ("境外") )
    then
        $o.setRejected(true);
        update($o);
        $o.addMessage("当前区域[" + $o.getCustomer().getRegion() + "] 不支持配送");
end', 1, '境外禁售');

-- 【库存规则】库存不足校验
INSERT INTO rule_definition (rule_group, rule_type, rule_name, rule_params, drl_content, status, remark) VALUES
('inventory','STOCK_CHECK','STOCK_CHECK','{}',
'package com.example.drools.dynamic;

dialect "mvel"

import com.example.drools.domain.Order;
import com.example.drools.domain.OrderItem;
import com.example.drools.domain.Product;
import com.example.drools.domain.Customer;

rule "STOCK_CHECK"
    salience 100
    when
        $o : Order( rejected == false )
        $item : OrderItem( quantity > product.stock, $p : product )
    then
        $o.setRejected(true);
        update($o);
        $o.addMessage("商品[" + $p.getName() + "] 库存不足：当前库存 " + $p.getStock() + "，购买 " + $item.getQuantity());
end', 1, '库存不足拒单');

-- 【库存规则】库存预警
INSERT INTO rule_definition (rule_group, rule_type, rule_name, rule_params, drl_content, status, remark) VALUES
('inventory','STOCK_WARNING','STOCK_WARNING','{"threshold":10}',
'package com.example.drools.dynamic;

dialect "mvel"

import com.example.drools.domain.Order;
import com.example.drools.domain.OrderItem;
import com.example.drools.domain.Product;
import com.example.drools.domain.Customer;

rule "STOCK_WARNING"
    when
        $o : Order( rejected == false )
        $item : OrderItem( $p : product, product.stock < 10 )
    then
        $o.addMessage("商品[" + $p.getName() + "] 库存紧张（剩余 " + $p.getStock() + " 件）");
end', 1, '库存紧张预警');
