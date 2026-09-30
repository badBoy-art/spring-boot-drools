-- Worker directory and session recovery catalog. Apply beside drools.sdk/src/main/resources/db/kie-session-store.sql.
CREATE TABLE IF NOT EXISTS kie_worker_node (
  node_id VARCHAR(128) NOT NULL COMMENT '唯一 Worker 实例 ID，需与会话 lease owner_id 一致',
  base_url VARCHAR(512) NOT NULL COMMENT '该 Worker 可被集群内访问的 HTTP 地址',
  heartbeat_time DATETIME NOT NULL COMMENT '最近一次心跳时间',
  PRIMARY KEY (node_id),
  KEY idx_kie_worker_heartbeat (heartbeat_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Drools Runtime Worker 节点目录';

CREATE TABLE IF NOT EXISTS kie_worker_session (
  session_id VARCHAR(64) NOT NULL COMMENT 'SDK/KIE 会话 ID',
  rule_type VARCHAR(64) NOT NULL COMMENT '固定使用的规则类型',
  clock_type VARCHAR(16) NOT NULL COMMENT 'realtime 或 pseudo',
  active_mode TINYINT NOT NULL DEFAULT 0 COMMENT '恢复后是否自动启动 fireUntilHalt',
  timed_auto TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用 passive TimedRuleExecutionOption',
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '会话登记时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '会话状态更新时间',
  PRIMARY KEY (session_id),
  KEY idx_kie_worker_session_status (status, update_time),
  KEY idx_kie_worker_session_type (rule_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Worker Stateful KieSession 恢复目录';

CREATE TABLE IF NOT EXISTS kie_worker_global (
 session_id VARCHAR(64) NOT NULL COMMENT '所属会话 ID，关联 kie_worker_session',
 global_name VARCHAR(128) NOT NULL COMMENT 'DRL 中声明的 global 变量名称',
 value_json TEXT NOT NULL COMMENT 'global 值的 JSON，用于检查点恢复前重绑定',
 PRIMARY KEY(session_id,global_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Worker 会话 global 值持久化';

CREATE TABLE IF NOT EXISTS kie_worker_command (
 session_id VARCHAR(64) NOT NULL COMMENT '所属会话 ID，关联 kie_worker_session',
 command_id VARCHAR(128) NOT NULL COMMENT '调用方提供的幂等命令标识 Idempotency-Key',
 request_hash CHAR(64) NOT NULL COMMENT '命令及请求参数的 SHA-256 摘要，防止同键不同请求',
 response_json TEXT NOT NULL COMMENT '命令成功响应 JSON，重复请求返回此响应',
 PRIMARY KEY(session_id,command_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Worker 已完成命令的幂等响应记录';
