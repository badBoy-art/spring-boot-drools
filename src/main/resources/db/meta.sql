-- =====================================================================
-- 规则类型元数据 schema（管理后台据此自动渲染配置表单，后端据此校验参数）
-- 数据库: test
-- =====================================================================

DROP TABLE IF EXISTS rule_type_meta;
CREATE TABLE rule_type_meta (
  rule_type  VARCHAR(32) PRIMARY KEY COMMENT '规则模板类型(与 RuleTypeEnum 一致)',
  rule_group VARCHAR(32) NOT NULL COMMENT '规则组: product/price/region/inventory/customer',
  type_name  VARCHAR(64) NOT NULL COMMENT '类型中文名',
  type_desc  VARCHAR(255) COMMENT '类型说明',
  builtin    TINYINT NOT NULL DEFAULT 1 COMMENT '是否内置(0自定义)',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '排序'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则类型元数据';

DROP TABLE IF EXISTS rule_type_field;
CREATE TABLE rule_type_field (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_type     VARCHAR(32) NOT NULL COMMENT '所属规则类型',
  field_key     VARCHAR(64) NOT NULL COMMENT '参数 key',
  field_name    VARCHAR(64) NOT NULL COMMENT '字段中文名',
  field_type    VARCHAR(16) NOT NULL COMMENT 'STRING/NUMBER/DECIMAL/ENUM/CSV',
  required      TINYINT NOT NULL DEFAULT 1 COMMENT '是否必填',
  default_value VARCHAR(64) COMMENT '默认值',
  min_value     VARCHAR(64) COMMENT '最小值(NUMBER/DECIMAL)',
  max_value     VARCHAR(64) COMMENT '最大值',
  enum_options  VARCHAR(255) COMMENT '枚举选项,逗号分隔(ENUM)',
  placeholder   VARCHAR(128) COMMENT '占位提示',
  sort_order    INT NOT NULL DEFAULT 0,
  remark        VARCHAR(255),
  KEY idx_type (rule_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则类型字段元数据';

-- =====================================================================
-- 类型元数据（10 种）
-- =====================================================================
INSERT INTO rule_type_meta (rule_type, rule_group, type_name, type_desc, builtin, sort_order) VALUES
('PRODUCT_OFF_SHELF','product','商品下架校验','商品已下架时禁止下单',1,1),
('PRODUCT_CATEGORY_MARK','product','商品分类标记','对指定分类商品追加提示消息',1,2),
('FULL_REDUCTION','price','满减','订单总额达到阈值减固定金额',1,3),
('BATCH_DISCOUNT','price','批量折扣','单商品购买达到件数享折扣',1,4),
('VIP_DISCOUNT','customer','会员折扣','指定等级会员享折扣',1,5),
('NEW_CUSTOMER_REDUCTION','customer','新客立减','新客下单立减固定金额',1,6),
('REGION_FREIGHT','region','区域运费','指定区域加收运费',1,7),
('REGION_BLOCK','region','区域禁售','指定区域禁止配送',1,8),
('STOCK_CHECK','inventory','库存不足校验','购买数量超库存时拒单',1,9),
('STOCK_WARNING','inventory','库存预警','库存低于阈值时提示',1,10);

-- =====================================================================
-- 字段元数据（各类型参数字段；PRODUCT_OFF_SHELF / STOCK_CHECK 为固定逻辑，无参数）
-- =====================================================================
-- 商品分类标记
INSERT INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, min_value, max_value, enum_options, placeholder, sort_order) VALUES
('PRODUCT_CATEGORY_MARK','category','商品分类','STRING',1,'电子产品',NULL,NULL,NULL,'如 电子产品/生鲜/服装',1),
('PRODUCT_CATEGORY_MARK','message','提示消息','STRING',1,'属于高价值电子产品，需特殊配送',NULL,NULL,NULL,'下单时追加的消息',2);

-- 满减
INSERT INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, min_value, max_value, enum_options, placeholder, sort_order) VALUES
('FULL_REDUCTION','threshold','满减阈值','NUMBER',1,'1000','0',NULL,NULL,'订单总额达到该金额触发',1),
('FULL_REDUCTION','reduction','减免金额','NUMBER',1,'100','0',NULL,NULL,'减免的金额',2);

-- 批量折扣
INSERT INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, min_value, max_value, enum_options, placeholder, sort_order) VALUES
('BATCH_DISCOUNT','minQuantity','最低件数','NUMBER',1,'3','1',NULL,NULL,'单商品购买达到该件数触发',1),
('BATCH_DISCOUNT','discountRate','折扣率','DECIMAL',1,'0.1','0','1',NULL,'如 0.1 表示 9 折',2);

-- 会员折扣
INSERT INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, min_value, max_value, enum_options, placeholder, sort_order) VALUES
('VIP_DISCOUNT','level','会员等级','ENUM',1,'VIP',NULL,NULL,'VIP,GOLD,NORMAL','会员等级',1),
('VIP_DISCOUNT','discountRate','折扣率','DECIMAL',1,'0.05','0','1',NULL,'如 0.05 表示 95 折',2);

-- 新客立减
INSERT INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, min_value, max_value, enum_options, placeholder, sort_order) VALUES
('NEW_CUSTOMER_REDUCTION','reduction','减免金额','NUMBER',1,'20','0',NULL,NULL,'新客立减金额',1);

-- 区域运费
INSERT INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, min_value, max_value, enum_options, placeholder, sort_order) VALUES
('REGION_FREIGHT','regions','区域列表','CSV',1,'新疆,西藏,内蒙古,青海',NULL,NULL,NULL,'逗号分隔的区域',1),
('REGION_FREIGHT','fee','运费','NUMBER',1,'30','0',NULL,NULL,'加收的运费',2);

-- 区域禁售
INSERT INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, min_value, max_value, enum_options, placeholder, sort_order) VALUES
('REGION_BLOCK','regions','禁售区域','CSV',1,'境外',NULL,NULL,NULL,'逗号分隔的区域',1);

-- 库存预警
INSERT INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, min_value, max_value, enum_options, placeholder, sort_order) VALUES
('STOCK_WARNING','threshold','库存阈值','NUMBER',1,'10','1',NULL,NULL,'库存低于该值触发预警',1);

-- =====================================================================
-- 自定义类型示例（builtin=0）：BIG_ORDER_TAG 纯数据驱动新增，无需改 Java
-- =====================================================================
INSERT INTO rule_type_meta (rule_type, rule_group, type_name, type_desc, builtin, sort_order) VALUES
('BIG_ORDER_TAG','price','大额订单标记','订单金额达到阈值时打标提示',0,99);

INSERT INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, min_value, max_value, enum_options, placeholder, sort_order) VALUES
('BIG_ORDER_TAG','threshold','金额阈值','NUMBER',1,'5000','0',NULL,NULL,'订单金额达到该值触发',1);
