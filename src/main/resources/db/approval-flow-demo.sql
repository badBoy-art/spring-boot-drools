-- =====================================================================================
-- 审批链路演示（商品毛利率 → 审批级别 → 查审批人 → 发起审批流）
-- 覆盖两个诉求：
--   1) 商品单据、订单单据都接入规则引擎（通用 DocFact，不用写 Java 类）
--   2) 流程中既有"调接口查数据"（查 +2 级审批人）也有"调接口写数据"（发起审批流）
--
-- 设计要点：
--   · 派生字段：毛利率不是报文原始字段，注册时给一个表达式 (price - cost) / price，
--     入口层（DocFactBuilder）在单据进引擎前用 MVEL 算好放进 data；
--   · 决策规则（按单据）：毛利率 > 30% → ext[approvalLevel]=L2（+2 级领导复核），否则 L1（商品负责人）
--   · 动作规则（与单据解耦，全平台通用）：读 ext 里的级别 → 查审批人 → 发起审批流，
--     用 ext 标记做链式条件 + 幂等守卫，保证"一个单据只调一次、且顺序正确"。
--
-- 执行：mysql -h127.0.0.1 -uroot -pzhaoZ1230 --default-character-set=utf8mb4 test < approval-flow-demo.sql
-- =====================================================================================

-- 1) 字段注册表加"派生表达式"列（MySQL 没有 ADD COLUMN IF NOT EXISTS，用 information_schema 判存在再动态执行）
SET @ddl := IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'rule_document_field' AND column_name = 'expr') > 0,
  'SELECT ''expr 列已存在，跳过'' AS note',
  'ALTER TABLE rule_document_field ADD COLUMN expr VARCHAR(255) NULL COMMENT ''派生字段计算表达式（可空）'' AFTER field_desc');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 商品单据 SKU：单据 → 对象 sku → 字段（含派生字段"毛利率"）
INSERT INTO rule_document (doc_code, doc_name, remark) VALUES
  ('SKU', '商品', '商品主数据：售价/成本/毛利率（毛利率为派生字段）')
ON DUPLICATE KEY UPDATE doc_name = VALUES(doc_name), remark = VALUES(remark);

INSERT INTO rule_document_object (doc_code, object_key, object_name, parent_object_key, is_collection, value_path, remark) VALUES
  ('SKU', 'sku', '商品', NULL, 0, '', '单据根对象')
ON DUPLICATE KEY UPDATE object_name = VALUES(object_name), remark = VALUES(remark);

INSERT INTO rule_document_field (doc_code, object_key, field_name, field_key, field_type, example_value, field_desc, expr, sort_order) VALUES
  ('SKU', 'sku', '商品编码', 'skuCode', 'STRING', 'SKU-1001', '商品唯一编码', NULL, 1),
  ('SKU', 'sku', '商品名称', 'skuName', 'STRING', '羽绒服', NULL, NULL, 2),
  ('SKU', 'sku', '售价', 'price', 'NUMBER', '1000', '单位：元', NULL, 3),
  ('SKU', 'sku', '成本', 'cost', 'NUMBER', '650', '单位：元', NULL, 4),
  ('SKU', 'sku', '毛利率', '毛利率', 'NUMBER', '0.35', '派生字段：(售价 - 成本) / 售价（乘 1.0 避免整数除法）', '(price - cost) * 1.0 / price', 5),
  ('SKU', 'sku', '品类', 'category', 'STRING', '服装', NULL, NULL, 6)
ON DUPLICATE KEY UPDATE object_key = VALUES(object_key), field_name = VALUES(field_name),
  field_type = VALUES(field_type), example_value = VALUES(example_value),
  field_desc = VALUES(field_desc), expr = VALUES(expr), sort_order = VALUES(sort_order);

-- 3) 两个审批链路接口（分类=流程引擎接口）
INSERT INTO rule_http_action (action_code, action_name, action_category, doc_code, method, domain_key, path,
                              headers_json, body_template, timeout_ms, status) VALUES
  ('APPROVER_QUERY', '查审批人（按审批级别）', '流程引擎接口', '', 'POST', 'mock', '/flow/approver',
   NULL, '{"level":"${ext.approvalLevel}","bizId":"${bizId}"}', 3000, 1),
  ('APPROVAL_START', '发起审批流', '流程引擎接口', '', 'POST', 'mock', '/flow/start',
   NULL, '{"flowKey":"${docCode}_FLOW_${ext.approvalLevel}","bizId":"${bizId}","approverId":"${ext.approverId}","approverName":"${ext.approverName}","level":"${ext.approvalLevel}"}', 3000, 1)
ON DUPLICATE KEY UPDATE action_name = VALUES(action_name), action_category = VALUES(action_category),
  method = VALUES(method), domain_key = VALUES(domain_key), path = VALUES(path),
  body_template = VALUES(body_template), timeout_ms = VALUES(timeout_ms), status = VALUES(status);

-- 返回值回填：查审批人 → ext；发起审批流 → ext
DELETE FROM rule_http_action_return WHERE action_code IN ('APPROVER_QUERY', 'APPROVAL_START');
INSERT INTO rule_http_action_return (action_code, resp_path, target_field, target_type, as_message, sort_order) VALUES
  ('APPROVER_QUERY', 'data.approverId',    'approverId',    'STRING', 0, 1),
  ('APPROVER_QUERY', 'data.approverName',  'approverName',  'STRING', 1, 2),
  ('APPROVER_QUERY', 'data.approverLevel', 'approverLevel', 'STRING', 0, 3),
  ('APPROVAL_START', 'data.procInstId',    'procInstId',    'STRING', 1, 1),
  ('APPROVAL_START', 'data.status',        'procStatus',    'STRING', 0, 2);

-- 4) 平台级动作规则（与单据解耦）：任何单据只要 ext 里出现 approvalLevel，就会走"查审批人 → 发起审批流"
--    顺序保证：靠 ext 标记做链式条件（第一步写 approvalLevel，第二步要求 approverId，第三步要求 attempted 标记）
--    幂等保证（重要）：守卫必须看"尝试过的标记"而不是"成功的标记"——
--      写接口失败时返回值不会回填，如果守卫看的是"成功回填"，同一条规则会反复点火直到 fireAllRules 上限（实测刷出 594 次调用）。
--      正确写法：RHS 先写尝试标记 + update，再调接口 → 语义是 at-most-once。
INSERT INTO rule_definition (rule_name, rule_group, rule_type, rule_params, drl_content, status, version, remark) VALUES
  ('FLOW_QUERY_APPROVER', 'platform', 'DOC_HTTP_ACTION', '{}',
   'package com.example.drools.dynamic;\n\ndialect "java"\n\nimport com.example.drools.domain.DocFact;\n\nglobal com.example.drools.http.HttpActionGateway httpActionGateway;\n\nrule "FLOW_QUERY_APPROVER"\n    salience 10\n    when\n        $d : DocFact( ext["approvalLevel"] != null, ext["queryApproverTried"] == null )\n    then\n        $d.getExt().put("queryApproverTried", true);\n        update($d);\n        httpActionGateway.invoke("APPROVER_QUERY", $d);\n    end\n',
   1, 1, '平台级：按审批级别查审批人（调接口·查数据；尝试标记做幂等守卫）'),
  ('FLOW_START_APPROVAL', 'platform', 'DOC_HTTP_ACTION', '{}',
   'package com.example.drools.dynamic;\n\ndialect "java"\n\nimport com.example.drools.domain.DocFact;\n\nglobal com.example.drools.http.HttpActionGateway httpActionGateway;\n\nrule "FLOW_START_APPROVAL"\n    salience 5\n    when\n        $d : DocFact( ext["approverId"] != null, ext["startFlowTried"] == null )\n    then\n        $d.getExt().put("startFlowTried", true);\n        update($d);\n        httpActionGateway.invoke("APPROVAL_START", $d);\n        $d.addRuleMessage("已按审批级别发起流程，审批人=" + $d.getExtValue("approverName"));\n    end\n',
   1, 1, '平台级：发起审批流（调接口·写数据，入参带上查到的审批人；attempted 标记防失败重试）')
ON DUPLICATE KEY UPDATE rule_type = VALUES(rule_type), drl_content = VALUES(drl_content),
  status = VALUES(status), remark = VALUES(remark);

-- 清理（想完全还原）：
-- DELETE FROM rule_definition WHERE rule_name IN ('FLOW_QUERY_APPROVER','FLOW_START_APPROVAL');
-- DELETE FROM rule_http_action_return WHERE action_code IN ('APPROVER_QUERY','APPROVAL_START');
-- DELETE FROM rule_http_action WHERE action_code IN ('APPROVER_QUERY','APPROVAL_START');
-- DELETE FROM rule_document_field WHERE doc_code='SKU';
-- DELETE FROM rule_document_object WHERE doc_code='SKU';
-- DELETE FROM rule_document WHERE doc_code='SKU';
