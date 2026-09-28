-- =====================================================================================
-- 组合规则表（页面版决策表）：一行组合 = 一条规则，整张表一次发布
--
-- 与 Excel 决策表的区别：
--   * 数据在页面表格里维护（每格是下拉/输入框，取值有白名单与类型校验，报错定位到行列）
--   * 改完直接发布生效，不用上传文件；可导出 xlsx 供业务方线下评审
--   * 动作白名单比决策表宽：除改金额/打标外，还能"调接口"（决策表的动作是写死在代码里的）
-- 执行：mysql -h127.0.0.1 -uroot -pzhaoZ1230 --default-character-set=utf8mb4 test < combination-table.sql
-- =====================================================================================

CREATE TABLE IF NOT EXISTS rule_combination_table (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  asset_key     VARCHAR(64)  NOT NULL COMMENT '资产编码，规则名前缀',
  asset_name    VARCHAR(128) NOT NULL,
  doc_code      VARCHAR(64)  NULL COMMENT '绑定单据（fact_class=DocFact 时必填）',
  fact_class    VARCHAR(16)  NOT NULL DEFAULT 'Order' COMMENT '主事实：Order / DocFact',
  salience      INT          NOT NULL DEFAULT -20 COMMENT '点火优先级（数值大的先跑）',
  columns_json  TEXT         NOT NULL COMMENT '条件列定义 JSON',
  action_json   TEXT         NOT NULL COMMENT '动作定义 JSON',
  rows_json     TEXT         NOT NULL COMMENT '行数据 JSON（每行=一条组合）',
  drl_content   TEXT         NULL COMMENT '生成出来的 DRL（发布时写入）',
  row_count     INT          NOT NULL DEFAULT 0,
  version       INT          NOT NULL DEFAULT 0,
  status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0草稿 1生效 2停用',
  updated_by    VARCHAR(64)  NULL,
  remark        VARCHAR(255) NULL,
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ct_asset (asset_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组合规则表（页面版决策表）';

-- 播种一张示例（停用状态，不影响现有演示基线；页面上点「发布生效」即可试）
INSERT IGNORE INTO rule_combination_table
  (asset_key, asset_name, doc_code, fact_class, salience, columns_json, action_json, rows_json, row_count, status, remark)
VALUES
  ('MEMBER_CATEGORY_RATE', '会员×品类折扣率（组合表示例）', NULL, 'Order', -20,
   '[{"key":"level","label":"会员等级","factVar":"$o","factType":"Order","fieldPath":"customer.level","op":"==","valueType":"ENUM","enumOptions":"VIP,GOLD,NORMAL"},{"key":"category","label":"商品品类","factVar":"$i","factType":"OrderItem","fieldPath":"product.category","op":"==","valueType":"ENUM","enumOptions":"电子产品,服装,食品,生鲜"}]',
   '{"type":"SET_DISCOUNT_RATE","label":"折扣率(按明细小计)","paramKey":"value","paramType":"NUMBER"}',
   '[{"level":"VIP","category":"电子产品","value":"0.05"},{"level":"VIP","category":"服装","value":"0.08"},{"level":"NORMAL","category":"食品","value":"0"},{"level":"GOLD","category":"电子产品","value":"0.03"}]',
   4, 0, '页面版决策表示例：4 行组合 → 4 条规则，一次发布');

-- 卸载：DROP TABLE rule_combination_table;
