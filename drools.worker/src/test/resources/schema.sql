CREATE TABLE kie_session_snapshot(session_id VARCHAR(64) PRIMARY KEY, rule_type VARCHAR(64), rule_fingerprint CHAR(64), rule_bundle CLOB, owner_id VARCHAR(128), fencing_token BIGINT DEFAULT 0, session_payload BLOB, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE kie_session_lease(session_id VARCHAR(64) PRIMARY KEY, owner_id VARCHAR(128), lease_until TIMESTAMP(6) NOT NULL, fencing_token BIGINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE kie_worker_node(node_id VARCHAR(128) PRIMARY KEY, base_url VARCHAR(512), heartbeat_time TIMESTAMP);
CREATE TABLE kie_worker_session(session_id VARCHAR(64) PRIMARY KEY, rule_type VARCHAR(64), clock_type VARCHAR(16), active_mode TINYINT, timed_auto TINYINT, status VARCHAR(16), create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP);

CREATE TABLE IF NOT EXISTS kie_worker_global (
 session_id VARCHAR(64) NOT NULL,
 global_name VARCHAR(128) NOT NULL,
 value_json TEXT NOT NULL,
 PRIMARY KEY(session_id,global_name)
);

CREATE TABLE IF NOT EXISTS kie_worker_command (
 session_id VARCHAR(64) NOT NULL,
 command_id VARCHAR(128) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 response_json TEXT NOT NULL,
 PRIMARY KEY(session_id,command_id)
);
