-- =====================================================================================
-- 迁移固化：订单风控审核 从「经典类型 HTTP_ACTION + 接口模板写上下文路径」
--            改成「步骤链类型 ORDER_RISK_CHECK + 接口声明逻辑参数名 + 规则绑值」（方案①）
--
-- 为什么改：老写法里接口模板把取值写死成 ${orderId}/${totalAmount}，接口无法复用，
--          且规则侧若再写绑定会静默失效（模板里找不到那个参数名）。
-- 现状：RISK_CHECK 的模板改成 8 个逻辑参数名；规则 HTTP_RISK_5000 由 ORDER_RISK_20000 取代。
-- 幂等：可重复执行。
-- =====================================================================================

-- 1) RISK_CHECK：入参模板改成逻辑参数名（数字裸写、字符串加引号）
UPDATE rule_http_action
   SET body_template = '{"bizId":"${bizId}","amount":${amount},"level":"${level}","region":"${region}","riskScore":${riskScore},"itemCount":${itemCount},"channel":"${channel}","ruleConstant":"${ruleConstant}"}'
 WHERE action_code = 'RISK_CHECK';

DELETE FROM rule_http_action_return WHERE action_code = 'RISK_CHECK';
INSERT INTO rule_http_action_return (action_code, resp_path, target_field, target_type, as_message, sort_order) VALUES
  ('RISK_CHECK', 'data.riskLevel', 'riskLevel', 'STRING', 1, 1),
  ('RISK_CHECK', 'data.score',     'riskScore', 'NUMBER', 0, 2);

-- 2) 新的步骤链类型：一步 = 金额门槛 + 8 个参数绑定
DELETE FROM rule_type_field WHERE rule_type = 'ORDER_RISK_CHECK';
INSERT INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, placeholder, sort_order) VALUES
  ('ORDER_RISK_CHECK', 'step1Action', '第1步 调用的接口', 'STRING', 1, 'RISK_CHECK', 'rule_http_action.action_code（规则里可换）', 10),
  ('ORDER_RISK_CHECK', 'step1Value',  '第1步 条件取值（totalAmount >=）', 'NUMBER', 1, '${step1Value}', NULL, 11);

INSERT INTO rule_type_meta (rule_type, rule_group, type_name, type_desc, builtin, sort_order)
VALUES ('ORDER_RISK_CHECK', 'action', '订单风控审核（门槛→查风控）', '金额达门槛 → 调风控接口（入参由规则绑定）', 0, 30)
ON DUPLICATE KEY UPDATE type_name = VALUES(type_name), type_desc = VALUES(type_desc), sort_order = VALUES(sort_order);

DELETE FROM rule_step WHERE rule_type = 'ORDER_RISK_CHECK';
INSERT INTO rule_step (rule_type, step_no, step_name, cond_field, cond_op, cond_type, cond_value,
                       action_type, action_code, message, param_json) VALUES
  ('ORDER_RISK_CHECK', 1, '风控审核查询', 'totalAmount', '>=', 'NUMBER', '${step1Value}',
   'CALL', 'RISK_CHECK', '风控审核查询完成（金额门槛 ${step1Value}）',
   '{"bizId":"${orderId}","amount":"${totalAmount}","level":"${customer.level}","region":"${customer.region}","riskScore":"${totalAmount * 0.01}","itemCount":"${items.size()}","channel":"规则引擎","ruleConstant":"固定常量"}');

-- 3) 规则：门槛 20000 是规则参数（可在页面改）；老的 HTTP_RISK_5000 退场
--    注意：drl_content 由引擎在发布时生成，这里先插成"未发布"，请在「③ 规则类型 → ORDER_RISK_CHECK → + 新建规则」
--    或调 POST /rule/create + /rule/publish 发布一次（一键做法：bash scripts/migrate-risk-check-step.sh）
DELETE FROM rule_definition WHERE rule_name = 'HTTP_RISK_5000';
DELETE FROM rule_definition WHERE rule_name = 'ORDER_RISK_20000';
INSERT INTO rule_definition (rule_group, rule_type, rule_name, rule_params, drl_content, status, version) VALUES
  ('action', 'ORDER_RISK_CHECK', 'ORDER_RISK_20000',
   '{"step1Value":20000,"step1Action":"RISK_CHECK"}', '', 0, 1);

-- 4) 自检
SELECT a.action_code AS 接口, a.body_template AS 入参模板 FROM rule_http_action a WHERE a.action_code = 'RISK_CHECK';
SELECT s.rule_type AS 类型, s.step_no AS 步, s.cond_field AS 条件字段, s.action_code AS 调用的接口,
       JSON_LENGTH(s.param_json) AS 绑定参数数
FROM rule_step s WHERE s.rule_type = 'ORDER_RISK_CHECK';
SELECT r.rule_name AS 规则, r.rule_type AS 类型, r.rule_params AS 规则参数
FROM rule_definition r WHERE r.rule_name IN ('ORDER_RISK_20000', 'HTTP_RISK_5000');
-- 执行完记得让引擎重编译：POST /rule/engine/refresh（或重启服务）
