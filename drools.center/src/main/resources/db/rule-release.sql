-- Immutable publication snapshots. Apply alongside document-registration.sql and rule-type.sql.
CREATE TABLE IF NOT EXISTS rule_bundle_draft (
 rule_type VARCHAR(64) PRIMARY KEY COMMENT '规则类型编码，关联 rule_type_meta',
 draft_version BIGINT NOT NULL COMMENT '资源包草稿版本，用于乐观并发校验',
 config_json LONGTEXT NOT NULL COMMENT '原生资源、KJAR、编译和运行选项配置 JSON',
 update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '草稿最后修改时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则类型的原生资源包配置草稿';
CREATE TABLE IF NOT EXISTS rule_release (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '不可变发布记录主键',
 rule_type VARCHAR(64) NOT NULL COMMENT '所属规则类型编码',
 revision BIGINT NOT NULL COMMENT '类型内单调递增发布修订号，回滚也生成新修订',
 bundle_json LONGTEXT NOT NULL COMMENT '冻结规则资源、单据字段及返回结构的完整发布包 JSON',
 content_hash CHAR(64) NOT NULL COMMENT '发布包内容的 SHA-256 摘要',
 published_by VARCHAR(128) NOT NULL COMMENT '执行发布操作的认证账号',
 remark VARCHAR(512) COMMENT '发布说明或回滚原因',
 create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
 UNIQUE KEY uk_release_revision(rule_type,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按规则类型归档的不可变发布快照';
CREATE TABLE IF NOT EXISTS rule_release_head (
 rule_type VARCHAR(64) PRIMARY KEY COMMENT '规则类型编码，每种类型一个生效指针',
 release_id BIGINT NOT NULL COMMENT '当前生效发布记录 ID，关联 rule_release.id',
 revision BIGINT NOT NULL COMMENT '当前生效修订号，用于发布并发校验'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则类型当前生效的发布版本指针';
