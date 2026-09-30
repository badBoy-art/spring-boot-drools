CREATE TABLE kie_session_snapshot (
  session_id VARCHAR(64) PRIMARY KEY,
  rule_type VARCHAR(64) NOT NULL,
  rule_fingerprint CHAR(64) NOT NULL,
  rule_bundle CLOB NOT NULL,
  owner_id VARCHAR(128),
  fencing_token BIGINT DEFAULT 0 NOT NULL,
  session_payload BLOB NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE kie_session_lease (
  session_id VARCHAR(64) PRIMARY KEY,
  owner_id VARCHAR(128),
  lease_until TIMESTAMP(6) NOT NULL,
  fencing_token BIGINT DEFAULT 0 NOT NULL,
  create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
