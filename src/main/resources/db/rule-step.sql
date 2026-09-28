-- =====================================================================================
-- 规则 = 步骤链（多步骤编排）
--
-- 一条规则的业务形态常常是"先查数据、再判断、再写数据"，而且**前一步接口的返回值要参与后一步的计算**。
-- 原来③一个类型只能配"一个条件 + 一个动作"，表达不了这个；现在类型下可以挂 N 个有序步骤：
--   step 1: 条件(毛利率>阈值) → 调接口(查审批人)      → 返回值回填 ext[approverId]
--   step 2: 条件(ext[approverId] 非空，参数引用 ${ext.approverId}) → 调接口(发起审批流) → 回填 procInstId
-- 生成器把每个步骤编成一条 DRL 规则：salience 递减 + 「上一步产物就绪 + 本步未执行」双守卫（at-most-once）。
-- =====================================================================================

-- 步骤级入参覆写
CREATE TABLE IF NOT EXISTS rule_step (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  rule_type     VARCHAR(64)  NOT NULL COMMENT '所属规则类型',
  step_no       INT          NOT NULL COMMENT '步骤序号（从 1 开始，决定先后）',
  step_name     VARCHAR(64)           DEFAULT NULL COMMENT '步骤名（如 查审批人 / 发起审批流）',
  cond_field    VARCHAR(128)          DEFAULT NULL COMMENT '条件字段（取值路径，可空=无条件）',
  cond_op       VARCHAR(8)            DEFAULT NULL COMMENT '运算符 > >= < <= == !=',
  cond_type     VARCHAR(16)           DEFAULT 'NUMBER' COMMENT 'NUMBER / ENUM / STRING',
  cond_value    VARCHAR(255)          DEFAULT NULL COMMENT '条件取值（成为该步骤的参数默认值）',
  action_type   VARCHAR(16)  NOT NULL DEFAULT 'MSG' COMMENT 'CALL 调接口 / SET_EXT 写单据字段 / MARK 打标 / MSG 只加消息',
  action_code   VARCHAR(64)           DEFAULT NULL COMMENT '调接口时的接口动作码（从接口列表选）',
  ext_field     VARCHAR(64)           DEFAULT NULL COMMENT 'SET_EXT/MARK 写进 ext 的字段名',
  ext_value     VARCHAR(255)          DEFAULT NULL COMMENT 'SET_EXT 写入的取值',
  message       VARCHAR(255)          DEFAULT NULL COMMENT '本步骤自定义消息（可空，自动生成）',
  create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_type_step (rule_type, step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则类型的步骤链（每步=条件+动作；前一步写进 ext 的产物可被后续步骤引用）';

SELECT rule_type AS 类型, step_no AS 步, step_name AS 步骤, action_type AS 动作, IFNULL(action_code,'') AS 接口
FROM rule_step ORDER BY rule_type, step_no;

-- 步骤级入参覆写（运营在规则上直接给这一步的接口传参，不用回②改模板；MySQL 5.7 没有 ADD COLUMN IF NOT EXISTS，走动态 DDL）
SET @sql := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE rule_step ADD COLUMN param_json VARCHAR(1000) NULL COMMENT ''本步入参覆写 key=取值；留空=用接口注册的入参模板''',
  'SELECT ''param_json 列已存在，跳过''')
  FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'rule_step' AND column_name = 'param_json');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
