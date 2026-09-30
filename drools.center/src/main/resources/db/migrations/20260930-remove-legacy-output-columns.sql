-- 已有旧结构的迁移：执行前备份项目表；新库直接执行主 DDL，无需此文件。
-- 仅对仍含这些列的库执行一次。当前输出以 rule_output_field 和 rule_step_output 为准。
-- ext_field/ext_value 中若有旧配置，必须先迁移至 rule_step_output，不可直接丢弃。
ALTER TABLE rule_type_meta DROP COLUMN output_fields;
ALTER TABLE rule_document_field DROP COLUMN is_output;
ALTER TABLE rule_step DROP COLUMN ext_field, DROP COLUMN ext_value, DROP COLUMN ext_value_type;
ALTER TABLE rule_publish_event COMMENT='规则类型发布事件日志，用于审计和发布代次追踪';
