-- =====================================================================================
-- 资产复用：接口/单据"一处注册，多处调用"
--
-- 问题：原来 rule_http_action.doc_code 是"作用于单据"的单值绑定 —— 一个接口只能挂一个单据，
--       违背"同一接口被多个单据、多条规则调用"的诉求。
-- 解决：新增适用范围表 rule_http_action_scope（N:N），doc_code 退化为"主适用单据（可空）"仅作展示；
--       调用关系在"使用点"建立（③ 规则类型选单据+接口、④ 填动作码、⑥ 组合表选接口），注册点不再强制绑定。
-- =====================================================================================

CREATE TABLE IF NOT EXISTS rule_http_action_scope (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  action_code VARCHAR(64)  NOT NULL COMMENT '接口动作码（引用 rule_http_action.action_code）',
  doc_code    VARCHAR(64)  NOT NULL COMMENT '适用单据编码（引用 rule_document.doc_code）',
  create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_action_doc (action_code, doc_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口适用范围（N:N：一个接口可服务多个单据；无记录=所有单据通用）';

-- 把历史单值绑定回填成适用范围（幂等）
INSERT IGNORE INTO rule_http_action_scope (action_code, doc_code)
SELECT action_code, doc_code FROM rule_http_action WHERE doc_code IS NOT NULL AND doc_code <> '';

-- 单据/接口删除护栏需要的引用视图（示例：被引用的单据清单）
-- 说明：引用扫描在 AssetRefDao 里做（规则参数、DRL 文本、组合表动作 JSON 都要看），这里只建表
SELECT a.action_code AS 接口, IF(COUNT(s.doc_code) = 0, '通用（所有单据）', GROUP_CONCAT(s.doc_code)) AS 适用范围
FROM rule_http_action a LEFT JOIN rule_http_action_scope s ON s.action_code = a.action_code
GROUP BY a.action_code ORDER BY a.action_code;
