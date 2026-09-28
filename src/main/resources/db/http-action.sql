-- =====================================================================================
-- 规则引擎升级：单据注册 + 可配置 HTTP 接口动作 + 返回值回填
-- 执行：mysql -h127.0.0.1 -uroot -pzhaoZ1230 --default-character-set=utf8mb4 test < http-action.sql
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE（播种数据带唯一键）
-- 卸载：见文件末尾
-- =====================================================================================

-- 1) 单据注册：业务上要接规则引擎的"单据"（订单、SKU、工单…）
CREATE TABLE IF NOT EXISTS rule_document (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  doc_code    VARCHAR(32) NOT NULL COMMENT '单据码，如 ORDER / SKU',
  doc_name    VARCHAR(64) NOT NULL COMMENT '单据中文名，如 订单 / 商品',
  remark      VARCHAR(255) NULL,
  status      TINYINT     NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_code (doc_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单据注册';

-- 2) 单据字段注册：中文名 + 对象 fieldName（取值路径），页面配接口参数时从这里挑
CREATE TABLE IF NOT EXISTS rule_document_field (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  doc_code    VARCHAR(32) NOT NULL,
  field_name  VARCHAR(64) NOT NULL COMMENT '中文字段名，如 订单金额',
  field_key   VARCHAR(128) NOT NULL COMMENT '取值路径(fieldName)，如 totalAmount / customer.level，也可写表达式',
  field_type  VARCHAR(16) NOT NULL DEFAULT 'STRING' COMMENT 'STRING/NUMBER/DECIMAL/BOOL/DATETIME/OBJECT',
  field_desc  VARCHAR(255) NULL,
  sort_order  INT         NOT NULL DEFAULT 1,
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_field (doc_code, field_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单据字段注册（中文名 + fieldName）';

-- 3) HTTP 接口动作：域名可配置（domain_key 指向 application.yml 里的地址），入参用模板+表达式
CREATE TABLE IF NOT EXISTS rule_http_action (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  action_code   VARCHAR(64)  NOT NULL COMMENT '动作码，规则里引用它',
  action_name   VARCHAR(128) NOT NULL,
  doc_code      VARCHAR(32)  NOT NULL COMMENT '作用于哪种单据',
  method        VARCHAR(8)   NOT NULL DEFAULT 'POST' COMMENT 'GET/POST/PUT/DELETE',
  domain_key    VARCHAR(32)  NOT NULL COMMENT '域名配置键：rule-http.domains.<key>',
  path          VARCHAR(255) NOT NULL COMMENT '接口路径，如 /risk/audit',
  headers_json  VARCHAR(512) NULL COMMENT '附加请求头 JSON',
  body_template TEXT         NULL COMMENT '请求体模板：{"bizId":"${orderId}","score":${totalAmount*0.01}}；GET 时按 query 拼',
  timeout_ms    INT          NOT NULL DEFAULT 2000,
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_action_code (action_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HTTP 接口动作配置';

-- 4) 返回值映射：响应 JSON 路径 -> 回填到单据的字段（可继续被后续规则使用）
CREATE TABLE IF NOT EXISTS rule_http_action_return (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  action_code  VARCHAR(64) NOT NULL,
  resp_path    VARCHAR(128) NOT NULL COMMENT '响应 JSON 路径，如 data.riskLevel；空串表示整个响应',
  target_field VARCHAR(64) NOT NULL COMMENT '回填字段名（写入单据 ext，后续规则可读 ext["xxx"]）',
  target_type  VARCHAR(16) NOT NULL DEFAULT 'STRING' COMMENT 'STRING/NUMBER/DECIMAL/BOOL',
  as_message   TINYINT     NOT NULL DEFAULT 0 COMMENT '1=同时写一条订单过程消息',
  sort_order   INT         NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uk_action_resp (action_code, target_field)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HTTP 动作返回值映射';

-- 5) 调用日志：规则里调了什么、请求/响应是什么，页面可回看
CREATE TABLE IF NOT EXISTS rule_http_call_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  action_code   VARCHAR(64)  NOT NULL,
  doc_code      VARCHAR(32)  NULL,
  biz_id        VARCHAR(64)  NULL,
  request_url   VARCHAR(512) NULL,
  request_body  TEXT         NULL,
  response_body TEXT         NULL,
  success       TINYINT      NOT NULL DEFAULT 0,
  error         VARCHAR(512) NULL,
  cost_ms       BIGINT       NOT NULL DEFAULT 0,
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_action (action_code, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HTTP 动作调用日志';

-- ============================ 播种：订单单据 + 其字段 ============================
INSERT IGNORE INTO rule_document (doc_code, doc_name, remark) VALUES
  ('ORDER', '订单', '订单主单：客户、明细、金额均在规则执行前装配好');

INSERT IGNORE INTO rule_document_field (doc_code, field_name, field_key, field_type, field_desc, sort_order) VALUES
  ('ORDER', '订单号',     'orderId',              'STRING',  '业务单号',                 1),
  ('ORDER', '商品总额',   'totalAmount',          'NUMBER',  '明细小计之和',             2),
  ('ORDER', '优惠金额',   'discount',             'NUMBER',  '各优惠规则累加',           3),
  ('ORDER', '运费',       'shippingFee',          'NUMBER',  '区域规则累加',             4),
  ('ORDER', '应付金额',   'finalAmount',          'NUMBER',  '总额-优惠+运费',           5),
  ('ORDER', '客户等级',   'customer.level',       'STRING',  'VIP/GOLD/NORMAL',          6),
  ('ORDER', '收货区域',   'customer.region',      'STRING',  '如 新疆 / 境外',           7),
  ('ORDER', '客户年龄',   'customer.age',         'NUMBER',  '用于年龄类规则',           8),
  ('ORDER', '是否新客',   'customer.newCustomer', 'BOOL',    '新客立减用',               9),
  ('ORDER', '明细行数',   'items.size()',         'NUMBER',  '表达式示例：明细条数',     10),
  ('ORDER', '已回填字段', 'ext',                  'OBJECT',  '接口返回值回填区，后续规则用 ext["xxx"] 读', 11);

-- ============================ 播种：一个示例 HTTP 动作 ============================
-- 入参模板写「逻辑参数名」：接口只声明我需要哪些参数，具体取哪个值由规则侧绑定
--   （规则/步骤里写 bizId = ${orderId}、amount = ${totalAmount}、channel = 规则引擎 …）
-- 数字参数写成裸的（不加引号）以便保持数字类型；字符串参数加引号。
INSERT IGNORE INTO rule_http_action
  (action_code, action_name, doc_code, method, domain_key, path, headers_json, body_template, timeout_ms) VALUES
  ('RISK_CHECK', '风控审核查询', 'ORDER', 'POST', 'mock', '/risk',
   '{"X-Tenant":"demo"}',
   '{"bizId":"${bizId}","amount":${amount},"level":"${level}","region":"${region}","riskScore":${riskScore},"itemCount":${itemCount},"channel":"${channel}","ruleConstant":"${ruleConstant}"}',
   2000);

INSERT IGNORE INTO rule_http_action_return (action_code, resp_path, target_field, target_type, as_message, sort_order) VALUES
  ('RISK_CHECK', 'data.riskLevel', 'riskLevel', 'STRING', 1, 1),
  ('RISK_CHECK', 'data.score',     'riskScore', 'NUMBER', 0, 2);

-- ============================ 播种：两类规则类型 ============================
-- 类型一：命中条件后调 HTTP 动作（动作码、门槛都可配）
INSERT IGNORE INTO rule_type_meta (rule_type, rule_group, type_name, type_desc, builtin, sort_order) VALUES
  ('HTTP_ACTION', 'action', '调用HTTP接口', '满足条件时按配置调用 HTTP 接口，并把返回值回填到单据', 0, 95);

INSERT IGNORE INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, min_value, enum_options, placeholder, sort_order) VALUES
  ('HTTP_ACTION', 'actionCode', '接口动作码', 'STRING', 1, 'RISK_CHECK', NULL, NULL, 'rule_http_action.action_code', 1),
  ('HTTP_ACTION', 'threshold',  '触发金额阈', 'NUMBER', 1, '5000', '0', NULL, '如 5000', 2);

INSERT IGNORE INTO rule_template (rule_type, template_body) VALUES ('HTTP_ACTION',
'    when
        $o : Order( rejected == false, ext["${actionCode}"] == null, totalAmount >= ${threshold} )
    then
        httpActionGateway.invoke("${actionCode}", $o);
        update($o);
        $o.addMessage("已调用接口[${actionCode}]，金额 " + $o.getTotalAmount());
');

-- 类型二：用接口回填的返回值继续参与规则（ext["xxx"]）
INSERT IGNORE INTO rule_type_meta (rule_type, rule_group, type_name, type_desc, builtin, sort_order) VALUES
  ('HTTP_RESULT_GUARD', 'action', '接口返回值拦截', '读取 HTTP 动作回填的 ext 字段做二次判定', 0, 94);

INSERT IGNORE INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, enum_options, sort_order) VALUES
  ('HTTP_RESULT_GUARD', 'extField', '返回字段名', 'STRING', 1, 'riskLevel', NULL, 1),
  ('HTTP_RESULT_GUARD', 'extValue', '拦截取值',   'ENUM',   1, 'HIGH', 'HIGH,MEDIUM,LOW', 2);

INSERT IGNORE INTO rule_template (rule_type, template_body) VALUES ('HTTP_RESULT_GUARD',
'    when
        $o : Order( rejected == false, ext["${extField}"] == "${extValue}" )
    then
        $o.setRejected(true);
        update($o);
        $o.addMessage("接口返回 ${extField}=${extValue}，订单被拦截");
');

-- 卸载用：
-- DELETE FROM rule_definition WHERE rule_type IN ('HTTP_ACTION','HTTP_RESULT_GUARD');
-- DELETE FROM rule_template WHERE rule_type IN ('HTTP_ACTION','HTTP_RESULT_GUARD');
-- DELETE FROM rule_type_field WHERE rule_type IN ('HTTP_ACTION','HTTP_RESULT_GUARD');
-- DELETE FROM rule_type_meta WHERE rule_type IN ('HTTP_ACTION','HTTP_RESULT_GUARD');
-- DROP TABLE IF EXISTS rule_http_call_log, rule_http_action_return, rule_http_action, rule_document_field, rule_document;
