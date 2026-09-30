-- 单据注册 DDL：仅定义单据、对象层级和字段元数据，不包含初始化数据。

CREATE TABLE IF NOT EXISTS rule_document (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  doc_code VARCHAR(64) NOT NULL COMMENT '单据编码，作为接入单据的唯一标识',
  doc_name VARCHAR(128) NOT NULL COMMENT '单据名称',
  remark VARCHAR(255) NULL COMMENT '单据说明',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0停用',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_code (doc_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则引擎接入单据注册表';

CREATE TABLE IF NOT EXISTS rule_document_object (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  doc_code VARCHAR(64) NOT NULL COMMENT '所属单据编码',
  object_key VARCHAR(64) NOT NULL COMMENT '对象标识，在单据内唯一',
  object_name VARCHAR(128) NOT NULL COMMENT '对象名称',
  parent_object_key VARCHAR(64) NULL COMMENT '父对象标识；根对象为空',
  is_collection TINYINT NOT NULL DEFAULT 0 COMMENT '是否集合：1是，0否',
  value_path VARCHAR(255) NULL COMMENT '对象在报文中的点分隔路径',
  remark VARCHAR(255) NULL COMMENT '对象说明',
  sort_order INT NOT NULL DEFAULT 1 COMMENT '同级显示顺序',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0停用',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_object (doc_code, object_key),
  KEY idx_doc_parent (doc_code, parent_object_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单据对象结构注册表，支持嵌套对象和集合';

CREATE TABLE IF NOT EXISTS rule_document_field (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  doc_code VARCHAR(64) NOT NULL COMMENT '所属单据编码',
  object_key VARCHAR(64) NOT NULL COMMENT '所属对象标识',
  field_name VARCHAR(128) NOT NULL COMMENT '字段显示名称',
  field_key VARCHAR(255) NOT NULL COMMENT '字段路径/标识，用于规则取值',
  field_type VARCHAR(32) NOT NULL DEFAULT 'STRING' COMMENT '字段类型，如 STRING/NUMBER/DECIMAL/INT/BOOLEAN/DATE',
  example_value VARCHAR(512) NULL COMMENT '字段示例值',
  field_desc VARCHAR(255) NULL COMMENT '字段业务说明',
  expr VARCHAR(1024) NULL COMMENT '可选派生字段表达式',
  sort_order INT NOT NULL DEFAULT 1 COMMENT '对象内显示顺序',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_field (doc_code, field_key),
  KEY idx_doc_object (doc_code, object_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单据对象字段注册表及可选派生表达式';
