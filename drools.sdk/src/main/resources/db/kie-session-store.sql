-- Optional business-side SDK persistence table. Apply in the consuming business database.
CREATE TABLE IF NOT EXISTS kie_session_snapshot (
  session_id VARCHAR(64) NOT NULL COMMENT 'SDK 或 Worker 的持久化会话唯一标识',
  rule_type VARCHAR(64) NOT NULL COMMENT '会话绑定的规则类型编码',
  rule_fingerprint CHAR(64) NOT NULL COMMENT '归档规则包与运行配置的 SHA-256 指纹',
  rule_bundle LONGTEXT NOT NULL COMMENT '构建会话 KieBase 所用的完整冻结规则包 JSON',
  owner_id VARCHAR(128) NULL COMMENT '最近写入检查点的节点 ID',
  fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '检查点写入者的租约代次，防止旧持有者覆盖',
  session_payload LONGBLOB NOT NULL COMMENT '原生 KieMarshaller 生成的会话二进制快照',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照首次创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次成功检查点时间',
  PRIMARY KEY (session_id),
  KEY idx_kie_session_rule_type (rule_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SDK 和 Worker 的原生 Drools 会话检查点';

CREATE TABLE IF NOT EXISTS kie_session_lease (
  session_id VARCHAR(64) NOT NULL COMMENT 'SDK 或 Worker 的持久化会话唯一标识',
  owner_id VARCHAR(128) NULL COMMENT '独占会话的节点 ID；NULL 表示无持有者',
  lease_until DATETIME(6) NOT NULL COMMENT '按数据库时钟计算的租约到期时间，微秒精度',
  fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '单调递增隔离令牌，防止失租节点写入快照',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '租约记录首次创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '租约最近修改时间',
  PRIMARY KEY (session_id),
  KEY idx_kie_session_owner (owner_id, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多节点会话独占归属租约及写入隔离令牌';
