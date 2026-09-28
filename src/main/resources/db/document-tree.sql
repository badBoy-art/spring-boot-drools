-- =====================================================================================
-- 单据注册升级为【对象 + 字段】的层级结构，并补接口分类
--
-- 单据接入中台的完整模型：
--   rule_document          单据本身（订单 / 商品 / 流程实例）
--   rule_document_object   单据对象（可嵌套：订单 → 客户 / 订单明细 → 商品）
--   rule_document_field    对象字段（中文名 + fieldName 取值路径 + 类型 + 说明）
--   rule_http_action       可调用接口（按 action_category 分：单据接口 / 流程引擎接口 / 风控 …）
--
-- 执行：mysql -h127.0.0.1 -uroot -pzhaoZ1230 --default-character-set=utf8mb4 test < document-tree.sql
-- ALTER 重复执行会报 Duplicate column name，可忽略。
-- =====================================================================================

-- 1) 单据对象表（支持嵌套：parent_object_key 指向父对象）
CREATE TABLE IF NOT EXISTS rule_document_object (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  doc_code          VARCHAR(64)  NOT NULL COMMENT '所属单据',
  object_key        VARCHAR(64)  NOT NULL COMMENT '对象标识，如 order / customer / variables',
  object_name       VARCHAR(64)  NOT NULL COMMENT '对象中文名，如 订单 / 客户 / 流程变量',
  parent_object_key VARCHAR(64)  NULL COMMENT '父对象标识（NULL=单据根对象）',
  is_collection     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否数组：1=列表（规则里按 items[0] 取）',
  value_path        VARCHAR(128) NULL COMMENT '该对象在单据了解里的取值路径，如 customer / items[0]',
  remark            VARCHAR(255) NULL,
  sort_order        INT          NOT NULL DEFAULT 0,
  status            TINYINT      NOT NULL DEFAULT 1,
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_object (doc_code, object_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单据对象（可嵌套）注册';

-- 2) 字段挂到对象上（幂等：先查 information_schema 再决定要不要 ALTER）
SET @ddl := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE rule_document_field ADD COLUMN object_key VARCHAR(64) NULL COMMENT ''所属对象''',
  'SELECT ''object_key 已存在''' )
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'rule_document_field' AND column_name = 'object_key');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @ddl := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE rule_document_field ADD COLUMN example_value VARCHAR(128) NULL COMMENT ''示例值''',
  'SELECT ''example_value 已存在''' )
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'rule_document_field' AND column_name = 'example_value');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 3) 接口分类（单据接口 / 流程引擎接口 / 风控 / 其它），便于页面分组与运营挑选
SET @ddl := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE rule_http_action ADD COLUMN action_category VARCHAR(32) NULL COMMENT ''接口分类''',
  'SELECT ''action_category 已存在''' )
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'rule_http_action' AND column_name = 'action_category');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 4) 把已有的 ORDER 字段挂到对象下（幂等）
INSERT IGNORE INTO rule_document_object (doc_code, object_key, object_name, parent_object_key, is_collection, value_path, remark, sort_order) VALUES
  ('ORDER', 'order',    '订单',   NULL, 0, '',        '单据根对象', 1),
  ('ORDER', 'customer', '客户',   'order', 0, 'customer', '嵌套对象（customer）', 2),
  ('ORDER', 'items',    '订单明细', 'order', 1, 'items',   '数组对象（items[0]）', 3),
  ('ORDER', 'items.product', '明细商品', 'items', 0, 'items[0].product', '嵌套在明细里的商品', 4);

UPDATE rule_document_field SET object_key = 'order'
 WHERE doc_code = 'ORDER' AND (object_key IS NULL OR object_key = '') AND field_key NOT LIKE 'customer.%' AND field_key NOT LIKE 'items%';
UPDATE rule_document_field SET object_key = 'customer'
 WHERE doc_code = 'ORDER' AND field_key LIKE 'customer.%';
UPDATE rule_document_field SET object_key = 'items'
 WHERE doc_code = 'ORDER' AND field_key LIKE 'items%';

-- 5) 第二个单据：流程实例（演示"业务对象可配置"—— 换一个单据换个对象，Java 零改动）
INSERT IGNORE INTO rule_document (doc_code, doc_name, remark, status) VALUES
  ('WF', '流程实例', '流程引擎里的流程实例，规则可在流程节点上做判断与调用', 1);
INSERT IGNORE INTO rule_document_object (doc_code, object_key, object_name, parent_object_key, is_collection, value_path, remark, sort_order) VALUES
  ('WF', 'wf',        '流程实例', NULL,   0, '',          '单据根对象', 1),
  ('WF', 'variables', '流程变量', 'wf',   0, 'variables', '流程变量（Map）', 2),
  ('WF', 'starter',   '发起人',   'wf',   0, 'starter',   '发起人对象', 3);
INSERT IGNORE INTO rule_document_field (doc_code, object_key, field_name, field_key, field_type, sort_order, field_desc, example_value) VALUES
  ('WF', 'wf',        '流程实例ID',   'procInstId',        'STRING',  1, '流程实例唯一标识', 'WF-1001'),
  ('WF', 'wf',        '流程定义KEY',  'processKey',        'STRING',  2, '如 order_approve',  'order_approve'),
  ('WF', 'wf',        '当前节点',     'currentNode',       'STRING',  3, '当前审批节点',      'risk_review'),
  ('WF', 'wf',        '单据金额',     'variables.amount',  'NUMBER',  4, '流程变量里的金额',  '12000'),
  ('WF', 'wf',        '业务类型',     'variables.bizType', 'STRING',  5, '如 ORDER/REFUND',   'ORDER'),
  ('WF', 'starter',   '发起人',       'starter.name',      'STRING',  1, '发起人姓名',        '张三'),
  ('WF', 'starter',   '发起部门',     'starter.dept',      'STRING',  2, '发起部门',          '采购部');

-- 6) 给 ORDER 的字段补示例值（试调用时一键填）
UPDATE rule_document_field SET example_value = 'SO-1001'   WHERE doc_code='ORDER' AND field_key='orderId';
UPDATE rule_document_field SET example_value = '6000'      WHERE doc_code='ORDER' AND field_key='totalAmount';
UPDATE rule_document_field SET example_value = 'VIP'       WHERE doc_code='ORDER' AND field_key='customer.level';
UPDATE rule_document_field SET example_value = '上海'      WHERE doc_code='ORDER' AND field_key='customer.region';

-- 7) 接口分类回填 + 新增一个"流程引擎接口"动作
UPDATE rule_http_action SET action_category = '风控接口' WHERE action_category IS NULL AND action_code IN ('RISK_CHECK','RISK_CHECK_SECURE');
UPDATE rule_http_action SET action_category = '单据接口' WHERE action_category IS NULL AND action_code IN ('POINTS_QUERY');

INSERT INTO rule_http_action
  (action_code, action_name, action_category, doc_code, method, domain_key, path, headers_json, body_template, timeout_ms, status)
VALUES
  ('WORKFLOW_START', '流程引擎-启动流程', '流程引擎接口', 'WF', 'POST', 'mock', '/workflow/start',
   '{"X-Tenant":"demo"}',
   '{"processKey":"${processKey}","bizId":"${procInstId}","starter":"${starter.name}","amount":${variables.amount},"node":"${currentNode}"}',
   3000, 1)
ON DUPLICATE KEY UPDATE action_name = VALUES(action_name), action_category = VALUES(action_category),
  body_template = VALUES(body_template), path = VALUES(path), doc_code = VALUES(doc_code);

INSERT IGNORE INTO rule_http_action_return (action_code, resp_path, target_field, target_type, as_message, sort_order) VALUES
  ('WORKFLOW_START', 'data.procInstId', 'wfProcInstId', 'STRING', 1, 1),
  ('WORKFLOW_START', 'data.status',     'wfStatus',     'STRING', 0, 2),
  ('WORKFLOW_START', 'data.approveNode','wfApproveNode','STRING', 1, 3);

-- 8) 通用单据（DocFact）的规则类型：任何注册单据都能这么配，不需要写 Java 类
INSERT IGNORE INTO rule_type_meta (rule_type, rule_group, type_name, type_desc, builtin, sort_order) VALUES
  ('DOC_HTTP_ACTION', 'action', '通用单据-调接口', '任意注册单据（DocFact）满足条件时按配置调 HTTP 接口，返回值回填 ext', 0, 93);

INSERT IGNORE INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, enum_options, placeholder, sort_order) VALUES
  ('DOC_HTTP_ACTION', 'docCode',      '单据编码',   'STRING', 1, 'WF', NULL, 'rule_document.doc_code', 1),
  ('DOC_HTTP_ACTION', 'actionCode',   '接口动作码', 'STRING', 1, 'WORKFLOW_START', NULL, 'rule_http_action.action_code', 2),
  ('DOC_HTTP_ACTION', 'fieldPath',    '判定字段',   'STRING', 1, 'variables.amount', NULL, '如 variables.amount / totalAmount', 3),
  ('DOC_HTTP_ACTION', 'threshold',    '阈值',       'NUMBER', 1, '10000', '0', '如 10000', 4);

INSERT IGNORE INTO rule_template (rule_type, template_body) VALUES ('DOC_HTTP_ACTION',
'    when
        $d : DocFact( docCode == "${docCode}", ext["${actionCode}"] == null, getNumber("${fieldPath}") >= ${threshold} )
    then
        httpActionGateway.invoke("${actionCode}", $d);
        update($d);
        $d.addRuleMessage("单据[" + $d.getDocCode() + "]命中，已调用接口 ${actionCode}");
');

INSERT IGNORE INTO rule_type_meta (rule_type, rule_group, type_name, type_desc, builtin, sort_order) VALUES
  ('DOC_EXT_GUARD', 'action', '通用单据-返回值判定', '读取接口回填的 ext，按返回值给单据打标/拦截（继续走规则引擎）', 0, 92);

INSERT IGNORE INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, enum_options, sort_order) VALUES
  ('DOC_EXT_GUARD', 'docCode',     '单据编码',   'STRING', 1, 'WF', NULL, 1),
  ('DOC_EXT_GUARD', 'extField',    '返回字段名', 'STRING', 1, 'wfApproveNode', NULL, 2),
  ('DOC_EXT_GUARD', 'extValue',    '取值',       'ENUM',   1, '风控复核', '风控复核,部门审批', 3);

INSERT INTO rule_template (rule_type, template_body) VALUES ('DOC_EXT_GUARD',
'    when
        $d : DocFact( docCode == "${docCode}", ext["${extField}"] == "${extValue}", ext["marked_${extField}"] == null )
    then
        $d.getExt().put("marked_${extField}", true);
        $d.getExt().put("requires_risk_review", true);
        $d.addRuleMessage("接口返回值 ${extField}=${extValue} → 标记需要风控复核");
        update($d);
')
ON DUPLICATE KEY UPDATE template_body = VALUES(template_body);

-- 卸载：
-- DELETE FROM rule_http_action_return WHERE action_code='WORKFLOW_START';
-- DELETE FROM rule_http_action WHERE action_code='WORKFLOW_START';
-- DELETE FROM rule_document_field WHERE doc_code='WF';
-- DELETE FROM rule_document_object WHERE doc_code='WF';
-- DELETE FROM rule_document WHERE doc_code='WF';
