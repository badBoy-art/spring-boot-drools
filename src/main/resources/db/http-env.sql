-- =====================================================================================
-- 接口注册增强：环境域名 / 接口分类 / 参数位置
--   1) 不同环境域名不同 → rule_http_domain(domain_key, env, base_url)，当前环境由 rule-http.env 决定
--   2) 接口分类可自助添加 → rule_http_category
--   3) GET/DELETE 参数走 query，POST/PUT 走 body（可显式指定）→ rule_http_action.param_in
-- =====================================================================================

CREATE TABLE IF NOT EXISTS rule_http_domain (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  domain_key VARCHAR(64)  NOT NULL COMMENT '域名配置键（接口注册里填这个；也可直接填完整 URL 作为键）',
  env        VARCHAR(16)  NOT NULL COMMENT '环境：dev / test / prod（可自定义）',
  base_url   VARCHAR(255) NOT NULL COMMENT '该环境下的真实域名，如 https://api-test.xxx.com',
  remark     VARCHAR(255)          DEFAULT NULL,
  update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_key_env (domain_key, env)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口域名（按环境）';

-- 演示域名：mock 在三个环境都指向本机（真接入时改成各环境真实域名即可）
INSERT IGNORE INTO rule_http_domain (domain_key, env, base_url, remark) VALUES
  ('mock', 'dev',  'http://localhost:8080/mock', '本地联调'),
  ('mock', 'test', 'http://localhost:8080/mock', '测试环境（演示同本机）'),
  ('mock', 'prod', 'http://localhost:8080/mock', '生产环境（演示同本机）');

CREATE TABLE IF NOT EXISTS rule_http_category (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  category_name VARCHAR(64) NOT NULL COMMENT '分类名（接口注册页下拉，可自助添加）',
  sort_order    INT         DEFAULT 0,
  create_time   DATETIME    DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_category (category_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口分类（可自助添加）';

INSERT IGNORE INTO rule_http_category (category_name, sort_order) VALUES
  ('单据接口', 10), ('流程引擎接口', 20), ('风控接口', 30), ('消息接口', 40), ('其它', 90);

-- 参数位置：AUTO（GET/DELETE=query，POST/PUT=body）/ QUERY / BODY
SET @sql := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE rule_http_action ADD COLUMN param_in VARCHAR(8) NOT NULL DEFAULT ''AUTO'' COMMENT ''参数位置：AUTO/QUERY/BODY''',
  'SELECT ''param_in 列已存在，跳过''')
  FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rule_http_action' AND column_name = 'param_in');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT d.domain_key AS 域名键, MAX(IF(d.env='dev', d.base_url, '')) AS dev,
       MAX(IF(d.env='test', d.base_url, '')) AS test, MAX(IF(d.env='prod', d.base_url, '')) AS prod
FROM rule_http_domain d GROUP BY d.domain_key;
SELECT GROUP_CONCAT(category_name ORDER BY sort_order SEPARATOR ' / ') AS 接口分类 FROM rule_http_category;
