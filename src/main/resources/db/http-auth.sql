-- =====================================================================================
-- HTTP 动作的认证 + 报文加解密/签名（Bearer / Basic / 自定义头 / JWT(HS256) / OAuth2 client_credentials
--                                    + AES-CBC/GCM 加解密 + HMAC-SHA256/MD5 签名）
--
-- 安全约定（重要）：
--   * 库里只放【密钥引用名】(xxx_ref)，真实密钥放 application.yml / Nacos / K8s Secret / 环境变量；
--     rule-http.secrets.<ref> 里取值，从不落库、不进规则参数、日志里也不打印。
--   * 算法走代码白名单枚举（不允许配置任意算法/填充，避免被配成 ECB 等弱算法）。
--
-- 执行：mysql -h127.0.0.1 -uroot -pzhaoZ1230 --default-character-set=utf8mb4 test < http-auth.sql
-- 注意：ALTER 部分重复执行会报 "Duplicate column name"，可忽略（表示已升级过）。
-- =====================================================================================

-- 1) 认证配置（一个动作可引用一个认证，多个动作可共用）
CREATE TABLE IF NOT EXISTS rule_http_auth (
  id                     BIGINT       NOT NULL AUTO_INCREMENT,
  auth_code              VARCHAR(64)  NOT NULL COMMENT '认证码，动作里引用',
  auth_name              VARCHAR(128) NOT NULL,
  auth_type              VARCHAR(24)  NOT NULL COMMENT 'NONE/BEARER/BASIC/HEADER/JWT_HS256/OAUTH2_CC',
  token_ref              VARCHAR(64)  NULL COMMENT 'BEARER：静态 token 的配置键 rule-http.secrets.<ref>',
  username               VARCHAR(64)  NULL COMMENT 'BASIC：用户名',
  password_ref           VARCHAR(64)  NULL COMMENT 'BASIC：密码的配置键',
  header_name            VARCHAR(64)  NULL COMMENT 'HEADER：自定义头名，如 X-Api-Key',
  header_value_ref       VARCHAR(64)  NULL COMMENT 'HEADER：自定义头值的配置键',
  jwt_secret_ref         VARCHAR(64)  NULL COMMENT 'JWT_HS256：签名密钥的配置键',
  jwt_issuer             VARCHAR(128) NULL,
  jwt_subject            VARCHAR(128) NULL,
  jwt_audience           VARCHAR(128) NULL,
  jwt_ttl_seconds        INT          NULL DEFAULT 300,
  jwt_claims_json        VARCHAR(512) NULL COMMENT '额外 claims JSON，如 {"tenant":"ctp"}',
  oauth_token_domain_key VARCHAR(32)  NULL COMMENT 'OAUTH2_CC：取 token 的域名键',
  oauth_token_path       VARCHAR(255) NULL COMMENT 'OAUTH2_CC：取 token 的路径，如 /oauth/token',
  oauth_client_id        VARCHAR(128) NULL,
  oauth_client_secret_ref VARCHAR(64) NULL,
  oauth_scope            VARCHAR(128) NULL,
  status                 TINYINT      NOT NULL DEFAULT 1,
  remark                 VARCHAR(255) NULL,
  create_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_auth_code (auth_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HTTP 动作认证配置（密钥只存引用名）';

-- 2) 动作表补充认证/加解密/签名字段
ALTER TABLE rule_http_action
  ADD COLUMN auth_code            VARCHAR(64)  NULL COMMENT '引用 rule_http_auth.auth_code',
  ADD COLUMN req_encrypt          VARCHAR(16)  NULL COMMENT '请求体加密：NONE/AES_CBC/AES_GCM',
  ADD COLUMN req_encrypt_key_ref  VARCHAR(64)  NULL,
  ADD COLUMN req_encrypt_iv_ref   VARCHAR(64)  NULL COMMENT '为空则用随机 IV 并前置到密文',
  ADD COLUMN resp_decrypt         VARCHAR(16)  NULL COMMENT '响应体解密：NONE/AES_CBC/AES_GCM',
  ADD COLUMN resp_decrypt_key_ref VARCHAR(64)  NULL,
  ADD COLUMN resp_decrypt_iv_ref  VARCHAR(64)  NULL,
  ADD COLUMN sign_type            VARCHAR(16)  NULL COMMENT '签名：NONE/HMAC_SHA256/MD5',
  ADD COLUMN sign_key_ref         VARCHAR(64)  NULL,
  ADD COLUMN sign_place           VARCHAR(16)  NULL COMMENT 'HEADER/BODY',
  ADD COLUMN sign_field           VARCHAR(64)  NULL COMMENT 'HEADER 时是头名（默认 X-Sign）；BODY 时是 JSON 字段名';

-- 3) 播种：JWT(HS256) 认证 + 带签名/双向加密的动作
INSERT IGNORE INTO rule_http_auth
  (auth_code, auth_name, auth_type, jwt_secret_ref, jwt_issuer, jwt_audience, jwt_ttl_seconds, jwt_claims_json, remark)
VALUES
  ('JWT_HS256_DEMO', '演示JWT(HS256自签)', 'JWT_HS256', 'jwtSecret', 'rule-engine', 'risk-sys', 300,
   '{"tenant":"demo"}', '密钥在 rule-http.secrets.jwtSecret，库里只存引用名');

INSERT IGNORE INTO rule_http_auth
  (auth_code, auth_name, auth_type, token_ref, remark)
VALUES
  ('BEARER_STATIC_DEMO', '演示固定Bearer Token', 'BEARER', 'staticToken', '适合对方直接给长期 token 的场景');

-- 带 JWT 认证 + 请求 AES-CBC 加密 + HMAC 签名 + 响应 AES-CBC 解密的动作
INSERT INTO rule_http_action
  (action_code, action_name, doc_code, method, domain_key, path, headers_json, body_template, timeout_ms, status,
   auth_code, req_encrypt, req_encrypt_key_ref, resp_decrypt, resp_decrypt_key_ref,
   sign_type, sign_key_ref, sign_place, sign_field)
VALUES
  ('RISK_CHECK_SECURE', '风控审核查询(认证+加密+签名)', 'ORDER', 'POST', 'mock', '/secure/risk',
   '{"X-Tenant":"demo"}',
   '{"bizId":"${orderId}","amount":${totalAmount},"level":"${customer.level}","riskScore":${totalAmount * 0.01}}',
   3000, 1, 'JWT_HS256_DEMO', 'AES_CBC', 'aesKey', 'AES_CBC', 'aesKey',
   'HMAC_SHA256', 'hmacKey', 'HEADER', 'X-Sign')
ON DUPLICATE KEY UPDATE
  auth_code = VALUES(auth_code), req_encrypt = VALUES(req_encrypt), req_encrypt_key_ref = VALUES(req_encrypt_key_ref),
  resp_decrypt = VALUES(resp_decrypt), resp_decrypt_key_ref = VALUES(resp_decrypt_key_ref),
  sign_type = VALUES(sign_type), sign_key_ref = VALUES(sign_key_ref), sign_place = VALUES(sign_place),
  sign_field = VALUES(sign_field), body_template = VALUES(body_template);

INSERT IGNORE INTO rule_http_action_return (action_code, resp_path, target_field, target_type, as_message, sort_order) VALUES
  ('RISK_CHECK_SECURE', 'data.riskLevel', 'riskLevel',   'STRING', 1, 1),
  ('RISK_CHECK_SECURE', 'data.score',     'riskScore',   'NUMBER', 0, 2);

-- 卸载：
-- DELETE FROM rule_http_action_return WHERE action_code='RISK_CHECK_SECURE';
-- DELETE FROM rule_http_action WHERE action_code='RISK_CHECK_SECURE';
-- DELETE FROM rule_http_auth WHERE auth_code IN ('JWT_HS256_DEMO','BEARER_STATIC_DEMO');
