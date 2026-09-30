# 生产部署与验收

## 环境配置

兼容基线为 Java 8 / Boot 2.7.18 / Drools 7.73.0.Final。升级 JDK、Boot 或 KIE 是独立兼容迁移，应先验证规则语义与已有 session snapshot；本次没有未经验证地混用新版依赖。

中心需设置 README 所列数据库与两个账号。Worker 设置 `WORKER_DB_URL/USERNAME/PASSWORD`、`RULE_CENTER_URL`、中心执行账号、`DROOLS_WORKER_USERNAME/PASSWORD`、唯一 `DROOLS_WORKER_NODE_ID`、可供调用方访问的 `DROOLS_WORKER_BASE_URL`。允许通过 HTTP 映射的 Java 类须配置 `DROOLS_WORKER_ALLOWED_FACT_TYPES` 并安装 jar；SDK 原生对象不受 JSON 映射列表限制。

使用 TLS 终止代理，不把数据库与 Worker 任意暴露公网；编辑账号应接公司身份与角色体系。当前提供 UserDetailsService 可替换点，内置账号仅是最小可部署认证实现。设置 secret manager、轮转、审计留存与数据库最小权限属于部署配置。

多个 Center 共享发布库，各节点按数据库 committed head 选择版本，不依赖某一个 JVM 广播。多个 Worker 共享会话库，以租约和 fencing 保护检查点；每个 node id 必须唯一。调用到非 owner 时返回409与 owner 地址，客户端按地址路由并有限重试。不能把普通负载均衡当作状态会话路由。

## 初始化和迁移

CREATE 脚本不自动删除/灌入业务数据。新库按 README 顺序执行。规则类型编码统一为 VARCHAR(64)。旧库先备份并核对表结构，只对差异做受控迁移，再增加 release、snapshot、lease、worker catalog、command 和 global 表。旧 status=1 规则需要按类型显式发布一次，才能生成可靠线上 head。升级后的执行 API 以 ruleType 取代旧 docCode 调用。旧工程 root `src` 已迁入 `drools.center/src`；启动 jar 路径随之变化。

中心与 SDK 必须使用相同版本的 contract/runtime/KIE 依赖。Maven Java 依赖必须不可覆盖、版本固定；归档的 KJAR 不替代应用类库部署。snapshot 还原需要兼容的类加载器、marshaller、Environment 与 calendar 版本。

## 持久化语义

Worker 默认 durable writes=true：变更事实/点火命令在成功应答前保存检查点；检查点、JSON globals 与命令日志使用同一个数据源事务。insert/update/fire 支持 `Idempotency-Key`；同 session 同 key 同命令返回原响应，不同参数返回409。没有 key 的重试可能重复执行。创建 session 也不能假设网络重试只创建一个。

active mode/timer 自动点火在两次 checkpoint 之间的状态可能随进程崩溃丢失或重放；不会承诺任意外部副作用 exactly-once。业务需要幂等/outbox；需要零丢失事件时还须持久化入口事件并在恢复后重放。租约保护持久化归属，不能撤销一个已经进入外部系统的调用。

租约默认30秒，maintenance默认5秒，自动 checkpoint默认60秒，snapshot上限默认64MiB。设置需根据 GC 停顿、状态大小、规则耗时和可接受恢复窗口确定。durable writes 每次 marshaling 会增加延迟，容量验收必须包含真实事实规模。

## 观测与验收

中心 /actuator/health 提供健康探针，/actuator/metrics 提供 JVM/HTTP 和规则运行类型、编译时间、失败数、获取次数。Worker 提供 checkpoint 体积/耗时、restore 耗时/计数与失租计数；维护与恢复失败有会话 ID 日志。发布记录包含 revision、contentHash、actor、remark、create_time。

自动化：`scripts/accept-all.sh` 执行 reactor verify；MySQL 集成测试只创建随机 `drools_audit_*` 临时库，关闭时只删除该临时库。专用验收数据库与应用账号由环境变量传入，禁止用生产库运行测试脚本。`scripts/platform-smoke.py` 在明确配置的测试环境创建带随机编码的文档/规则类型与会话。

本次验证包括原生 DRL/DSL/决策表/DMN/PMML/KJAR/executable model，发布失败不切换、并发冲突、类型隔离、冻结 schema、旧版本在途保留、鉴权/CSRF、session marshaling、named entry point、幂等与多 owner 恢复，并在独立 MySQL8.4 校验 DDL/事务。

上线前还需要在目标环境完成：实际业务规则回归、峰值与长时间压力、GC/大 snapshot、强制杀进程与网络分区、主库故障切换、备份恢复、证书和账号轮转、告警与操作手册验收。本地测试无法证明这些部署条件已经满足；本项目未提供公司级 SSO、多租户沙箱或任意外部副作用 exactly-once 的现成实现。
