-- 规则类型 DDL：规则类型、参数、规则定义、步骤、模板及结构化返回元数据。
-- 本文件仅建表，不写入规则类型、模板或规则等初始化数据。

CREATE TABLE IF NOT EXISTS rule_type_meta (
  rule_type VARCHAR(64) NOT NULL COMMENT '规则类型编码，主键',
  rule_group VARCHAR(32) NOT NULL COMMENT '规则分组编码',
  type_name VARCHAR(64) NOT NULL COMMENT '规则类型名称',
  type_desc VARCHAR(255) NULL COMMENT '规则类型说明',
  doc_code VARCHAR(64) NULL COMMENT '绑定的单据编码',
  builtin TINYINT NOT NULL DEFAULT 1 COMMENT '是否内置类型：1是，0自定义',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '列表显示顺序',
  PRIMARY KEY (rule_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则类型元数据';

CREATE TABLE IF NOT EXISTS rule_type_field (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  rule_type VARCHAR(64) NOT NULL COMMENT '所属规则类型编码',
  field_key VARCHAR(64) NOT NULL COMMENT '参数字段标识',
  field_name VARCHAR(64) NOT NULL COMMENT '参数显示名称',
  field_type VARCHAR(16) NOT NULL COMMENT '参数类型：STRING/NUMBER/DECIMAL/ENUM/CSV',
  required TINYINT NOT NULL DEFAULT 1 COMMENT '是否必填：1是，0否',
  default_value VARCHAR(64) NULL COMMENT '参数默认值',
  min_value VARCHAR(64) NULL COMMENT '数值参数最小值',
  max_value VARCHAR(64) NULL COMMENT '数值参数最大值',
  enum_options VARCHAR(255) NULL COMMENT '枚举参数可选项',
  placeholder VARCHAR(128) NULL COMMENT '页面输入提示',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '参数显示顺序',
  remark VARCHAR(255) NULL COMMENT '参数说明',
  PRIMARY KEY (id),
  KEY idx_type (rule_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则类型参数字段定义';

CREATE TABLE IF NOT EXISTS rule_definition (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  rule_group VARCHAR(32) NOT NULL COMMENT '规则分组编码',
  rule_type VARCHAR(64) NOT NULL COMMENT '所属注册规则类型编码',
  rule_name VARCHAR(64) NOT NULL COMMENT '规则名称',
  rule_params VARCHAR(512) NOT NULL DEFAULT '{}' COMMENT '本规则参数 JSON',
  drl_content TEXT NULL COMMENT '生成并保存的 DRL 全文',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0草稿，1已发布，2已禁用',
  version INT NOT NULL DEFAULT 1 COMMENT '规则版本号',
  remark VARCHAR(255) NULL COMMENT '规则说明',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_name (rule_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可发布执行的规则定义';

CREATE TABLE IF NOT EXISTS rule_publish_event (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '单调递增的规则集发布代次',
  rule_type VARCHAR(64) NULL COMMENT '发生变更的规则类型；NULL 表示全局规则集变更',
  action VARCHAR(16) NOT NULL COMMENT '变更动作；RELEASE 表示不可变版本发布，兼容 PUBLISH/ENABLE/DISABLE/DELETE',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布事件创建时间',
  PRIMARY KEY (id),
  KEY idx_rule_publish_type_id (rule_type, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则类型发布事件日志，用于审计和发布代次追踪';

CREATE TABLE IF NOT EXISTS rule_step (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  rule_type VARCHAR(64) NOT NULL COMMENT '所属规则类型编码',
  step_no INT NOT NULL COMMENT '步骤序号，从1开始并决定执行顺序',
  step_name VARCHAR(64) NULL COMMENT '步骤名称',
  cond_field VARCHAR(128) NULL COMMENT '条件字段或取值路径；空表示无条件',
  cond_op VARCHAR(32) NULL COMMENT '条件运算符，如 ==、!=、>、in、contains、matches',
  cond_type VARCHAR(32) NULL DEFAULT 'NUMBER' COMMENT '条件取值类型',
  cond_value VARCHAR(255) NULL COMMENT '条件值或参数默认值',
  cond_scope VARCHAR(16) NOT NULL DEFAULT 'DOC' COMMENT '执行范围：DOC单据级/EACH集合逐项/NTH集合指定项',
  collection_path VARCHAR(64) NULL COMMENT '集合对象标识；EACH/NTH 范围使用',
  nth_index INT NULL COMMENT '目标集合下标；NTH 范围使用，0起始',
  action_type VARCHAR(32) NOT NULL DEFAULT 'SET_EXT' COMMENT '动作类型；SET_EXT 写入规则结果上下文',
  message VARCHAR(255) NULL COMMENT '步骤消息；为空时可由引擎生成',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_type_step (rule_type, step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则类型的有序步骤及作用范围配置';

CREATE TABLE IF NOT EXISTS rule_step_output (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  rule_type VARCHAR(64) NOT NULL COMMENT '所属规则类型编码',
  step_no INT NOT NULL COMMENT '所属步骤序号',
  output_key VARCHAR(128) NOT NULL COMMENT '本步骤赋值的结果字段标识',
  value_type VARCHAR(16) NOT NULL DEFAULT 'STRING' COMMENT '结果值类型：STRING/NUMBER/DECIMAL/INT/BOOLEAN/EXPR',
  expression VARCHAR(1024) NOT NULL COMMENT '常量或表达式；可引用多个单据字段',
  sort_order INT NOT NULL DEFAULT 1 COMMENT '本步骤结果字段赋值顺序',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_type_step_output (rule_type, step_no, output_key),
  KEY idx_type_step (rule_type, step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则步骤的多字段输出赋值配置';

CREATE TABLE IF NOT EXISTS rule_output_field (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  rule_type VARCHAR(64) NOT NULL COMMENT '所属规则类型编码',
  output_path VARCHAR(128) NOT NULL COMMENT '返回节点路径，使用点分隔层级',
  parent_path VARCHAR(128) NULL COMMENT '父节点路径；根节点为空',
  label VARCHAR(64) NOT NULL COMMENT '返回字段显示名称',
  node_kind VARCHAR(8) NOT NULL DEFAULT 'LEAF' COMMENT '节点类型：OBJECT对象/ARRAY数组/LEAF叶子字段',
  value_type VARCHAR(16) NULL COMMENT '叶子值类型：STRING/NUMBER/DECIMAL/INT/BOOLEAN',
  source VARCHAR(8) NULL COMMENT '叶子取值来源：EXT规则赋值/DATA入参/EXPR表达式/CONST常量',
  source_value VARCHAR(255) NULL COMMENT '取值字段名、入参路径、表达式或常量',
  array_from VARCHAR(128) NULL COMMENT '数组来源路径，可指向单据集合或结果集合',
  sort_order INT NOT NULL DEFAULT 1 COMMENT '同级返回节点顺序',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_type_path (rule_type, output_path),
  KEY idx_type (rule_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则类型的嵌套返回结构及字段映射';

CREATE TABLE IF NOT EXISTS rule_template (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  rule_type VARCHAR(64) NOT NULL COMMENT '规则类型编码，每种类型唯一模板',
  template_body TEXT NOT NULL COMMENT 'DRL 规则体或完整多步骤 DRL 模板',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_rule_type (rule_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则类型的 DRL 模板配置';
