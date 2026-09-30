# 数据库表与 DDL

项目主 DDL 共定义 20 张表：Center 14 张、SDK 会话持久化 2 张、Worker 4 张。`drools.contract` 和 `drools.runtime` 不定义数据库表。测试资源目录中的 schema 仅用于测试，不是部署 DDL。

## 单据注册：3 张表

DDL：[document-registration.sql](../drools.center/src/main/resources/db/document-registration.sql)。

| 表 | 用途 | 主要标识/关联 |
| --- | --- | --- |
| `rule_document` | 注册接入单据，保存名称、说明和启停状态 | `doc_code` 唯一 |
| `rule_document_object` | 注册单据中的嵌套对象和集合结构 | `(doc_code, object_key)` 唯一；父对象由 `parent_object_key` 关联 |
| `rule_document_field` | 注册字段、类型、示例及派生表达式 | `(doc_code, field_key)` 唯一；`object_key` 关联对象 |

## 规则类型与运营配置：8 张表

DDL：[rule-type.sql](../drools.center/src/main/resources/db/rule-type.sql)。

| 表 | 用途 | 主要标识/关联 |
| --- | --- | --- |
| `rule_type_meta` | 注册规则类型编码、分组、说明和绑定单据 | `rule_type` 主键；`doc_code` 关联单据 |
| `rule_type_field` | 运营页面的规则参数、默认值和校验约束 | `rule_type` 关联规则类型 |
| `rule_definition` | 规则实例、参数 JSON、DRL、状态和版本 | `id` 主键；`rule_name` 全局唯一 |
| `rule_publish_event` | 规则发布事件日志，用于审计和发布代次追踪 | 自增 `id`；可按 `rule_type` 查询 |
| `rule_step` | 规则步骤、条件、集合作用范围和动作 | `(rule_type, step_no)` 唯一 |
| `rule_step_output` | 每个步骤的多字段输出赋值配置 | `(rule_type, step_no, output_key)` 唯一 |
| `rule_output_field` | 嵌套返回结构与字段来源映射 | `(rule_type, output_path)` 唯一 |
| `rule_template` | 规则类型的 DRL 模板 | 每个 `rule_type` 一个模板 |

## 草稿与发布：3 张表

DDL：[rule-release.sql](../drools.center/src/main/resources/db/rule-release.sql)。

| 表 | 用途 | 主要标识/关联 |
| --- | --- | --- |
| `rule_bundle_draft` | 原生资源包配置草稿及并发修改版本 | `rule_type` 主键；`draft_version` 控制并发 |
| `rule_release` | 不可变发布快照，包含资源包、哈希、发布人和备注 | `(rule_type, revision)` 唯一 |
| `rule_release_head` | 规则类型当前生效的发布指针 | `rule_type` 主键；`release_id` 指向发布记录 |

发布快照是运行版本依据；只创建表不会把已有规则转换成发布快照。旧规则需要按规则类型显式发布，生成 `rule_release` 和 `rule_release_head`。

## SDK 会话持久化：2 张表

DDL：[kie-session-store.sql](../drools.sdk/src/main/resources/db/kie-session-store.sql)。Worker 也使用这两张表。业务方使用 SDK 持久化扩展时，在自己的业务数据库部署这两张表；仅使用远程同步执行接口不需要在业务数据库建表。

| 表 | 用途 | 主要标识/关联 |
| --- | --- | --- |
| `kie_session_snapshot` | 保存原生 KieSession 二进制检查点及对应规则包、指纹 | `session_id` 主键 |
| `kie_session_lease` | 会话独占归属、租约有效期及防止旧节点写入的 fencing token | `session_id` 主键；`owner_id` 标识所属节点 |

## Worker 运行管理：4 张表

DDL：[kie-worker-store.sql](../drools.worker/src/main/resources/db/kie-worker-store.sql)。与 SDK 两张持久化表部署在同一个 Worker 数据库，保障检查点、globals 和命令记录能参与同一事务。

| 表 | 用途 | 主要标识/关联 |
| --- | --- | --- |
| `kie_worker_node` | Worker 节点地址与心跳目录 | `node_id` 主键；对应租约 `owner_id` |
| `kie_worker_session` | Stateful 会话恢复目录，记录时钟、运行模式和状态 | `session_id` 主键 |
| `kie_worker_global` | 保存恢复会话所需的 JSON globals | `(session_id, global_name)` 主键 |
| `kie_worker_command` | 保存幂等命令请求摘要与响应 | `(session_id, command_id)` 主键 |

这些关联当前由应用维护，DDL 没有声明外键。Drools 的自定义 Java/JPA 持久化扩展所用的业务表不在上述清单内，需按接入应用配置另行部署。

## 建表顺序

在目标数据库中依次执行上述五个 DDL 文件：单据注册、规则类型、发布、SDK 持久化、Worker 管理。所有语句均为 `CREATE TABLE IF NOT EXISTS`，不插入样例或业务数据，也不会自动升级已有表结构。应用配置未开启自动建表。

## 本地 test 库重建记录（2026-09-30）

按用户要求，已先备份再删除、重建上述全部 20 张项目表，以仓库最新 DDL 为准。旧结构和旧数据已清除，之前发现的字段长度、缺列及缺索引差异不再保留。库中的 `user`、`user_01`、`user_02` 不属于本项目，未删除或修改。

重建后已核对所有项目表使用 InnoDB / utf8mb4，20 张表及全部 153 个字段均有说明（本轮删除了 5 个旧字段）。Worker global/command 两张表也显式声明引擎与字符集。

备份：`/Users/admin/.local/share/drools/backups/20260930-135146/project-before-rebuild.sql`，文件权限 600，目录权限 700。该备份保存重建前的项目表结构与数据，可用于人工恢复；恢复会替换当前项目数据，不能与示例数据 SQL 混用。

实际服务为 MySQL `5.7.15-log`。新建表已在该服务实际执行；商品示例会通过真实 Center HTTP 接口注册、生成、编译和发布。此前完整原生资源及 Worker 故障恢复验收基线为 MySQL 8，本次商品示例验收不替代全部生产场景验证。

示例数据单独提供：[product-approval-data.sql](../drools.center/src/main/resources/db/demo/product-approval-data.sql)。仅向空的项目管理表初始化商品单据、三步审批、模板和不可变发布，不包含 Worker 会话状态。商品请求/响应及规则说明见 [商品多层审批](product-approval.md)。

## 冗余字段清理（2026-09-30）

已在本地 test 库执行 [单次迁移](../drools.center/src/main/resources/db/migrations/20260930-remove-legacy-output-columns.sql)：移除 `rule_type_meta.output_fields`、`rule_document_field.is_output` 和 `rule_step.ext_field/ext_value/ext_value_type`。输出统一由 `rule_output_field` 定义结构、`rule_step_output` 定义每步赋值。执行前已确认旧字段没有有效配置，20 张表均保留。

迁移前备份：`/Users/admin/.local/share/drools/backups/20260930-164620/project-before-cleanup.sql`（目录 700 / 文件 600）。3 个发布快照、内容哈希和生效指针均保持不变；运行时兼容快照中已退休的单据配置字段。已有数据库执行迁移，新库直接执行最新五份主 DDL，不能对新库重复执行这份迁移。
