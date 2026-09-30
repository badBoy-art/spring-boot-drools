# 原生 Drools 能力开放与 HTTP 接入

## 目标

在运营配置便捷性与原生 Drools 能力之间建立不互相限制的双模式规则中台：结构化页面作为常用配置快捷入口，原生 DRL 编辑器提供完整语法兜底；业务接入侧使用 SDK 通过 HTTP 拉取规则类型/DRL，在业务进程本地执行并持有 stateful KieSession，使跨请求状态、active fireUntilHalt、长生命周期 timer/CEP 使用原生 KIE 生命周期。明确不做 DMN/Excel 决策表。

## 阶段

1. [complete] 阅读用户指定资料并审计现有生成、编译、执行和 HTTP 能力边界。
2. [complete] 增加运营配置模式之外的原生 DRL 规则校验、保存、发布入口，保证原文不被改写。
3. [complete] 页面提供可用的原生 DRL 编辑/发布入口，并说明与配置模式的区别。
4. [complete] 扩充 HTTP 规则执行的原生运行上下文，验证规则函数/查询/属性和输入事实。
5. [complete] 记录仍需跨请求有状态生命周期或独立引擎模块的能力边界，完成构建和回归验证。
6. [complete] 提供规则类型运行时 HTTP 配置包接口和独立 Java SDK：按需拉取已发布规则/配置，编译 DRL，创建并管理长生命周期 KieSession，暴露完整原生会话及 `fireUntilHalt` 生命周期。
7. [complete] 调整页面文案/入口，让结构化规则编辑与原生 DRL 明确并列，并说明任意 DRL 表达能力通过原生模式保证。
8. [complete] 验证跨请求事实状态和持续点火/停止，开放 realtime/pseudo session clock 原生 API，并更新 SDK 接入文档。
9. [complete] 将 SDK 类集中至 document 包，改为依赖中台提供的 Spring Cloud OpenFeign 配置查询接口；更新 SDK 装配与业务接入文档并验证。
10. [complete] 补充 timer 验证矩阵：pseudo/realtime clocks、interval/cron/expression timers、repeat-limit、active `fireUntilHalt`、passive `fireAllRules` 再调用、条件事实撤回后取消。
11. [complete] 开放 timer calendar/start/end 和被动 TimedRuleExecutionOption；以 Drools 7.73 测试 interval、cron、expr、calendar、start/end/repeat-limit、passive auto 和 active 模式。
12. [complete] 通过 RuleRuntimeOptions 开放 CLOUD/STREAM、KieBase/KieSession 原生 typed options 与属性透传；配置 named calendars 并验证 CLOUD/STREAM 创建会话。
13. [complete] 在业务侧 SDK 增加 KieSession marshalling JDBC 持久化/恢复、DRL/选项 SHA-256 一致性校验；测试 SDK 重建后恢复事实及待触发 timer。
14. [complete] 增加 KIE 会话生命周期恢复钩子，使业务重新绑定 globals、listeners、channels、work-item handlers 等非序列化运行依赖，并测试调用时序。
15. [complete] 增加共享数据库会话 owner 租约、单所有者路由信息、fencing token 和失租关闭保护；测试多节点争抢及节点失效接管。
16. [in_progress] 加强持久化的并发写保护、快照版本/体积/耗时观测及重启故障测试，记录生产部署约束和 KIE 原生能力边界；本轮已完成独立 MySQL 事务及跨进程节点接管验收；目标生产容量/网络分区/主库容灾仍待部署环境验证。
17. [complete] 为规则中台多副本增加持久化规则发布版本/节点刷新机制，确保发布变更传播、实例报告生效版本并支持失败回退。
18. [complete] 新增独立 runtime-worker 微服务，提供基于 HTTP 的 Stateful KieSession 创建、事实操作、fireAllRules、fireUntilHalt、halt、checkpoint、恢复、关闭及 owner 路由响应。
19. [complete] 设计 worker 自动恢复/会话目录/规则版本固定策略，覆盖节点注册、owner 存活探测、故障接管和快照恢复；为 Java Fact 定义安全 allowlist/JSON 映射边界。
20. [complete] 修复不安全的端口抢占启动逻辑，补充 Worker/规则中台部署、安全、故障恢复文档和 H2 集成测试；执行 SDK/Worker/主工程回归，并记录生产 MySQL 多节点和规模压测仍未验证。

## 安全边界

- 初始化 SQL 继续保持纯建表，不加入规则类型/示例数据 DML。
- 仅操作项目配置确认的本地开发数据库；不复用对话中暴露的口令，不打印凭据。
- 清理规则类型数据前先查询数量/引用关系，写入限制到规则类型相关配置表，不清空规则执行历史或其他数据。
- 保留工作区中与本任务无关的既有改动。
- 已确认 application.yml 指向 `127.0.0.1:3306/test`；配置口令不输出、不重用对话中暴露的值。
- 原生 DRL 是避免能力阉割的关键入口；配置化步骤 UI 只能是便捷子集，不能被当作 Drools 语法完整实现。
- 运行时会话由业务方 SDK 进程持有，不保存在中台 HTTP 节点内；因此 session 生命周期与扩容/故障恢复由业务部署实例管理。规则类型和已发布 DRL 仍由中台 HTTP 提供。

## 遇到的错误

| 错误 | 尝试次数 | 解决方案 |
|---|---:|---|
| RuleStepBuilder 的 itemAssignments 在 each/nth 声明前引用变量，编译失败 | 1 | 将范围布尔变量提前初始化 |
| 新范围集成测试误把 50% 区域毛利预期为 T | 1 | 将测试输入改为 20% 毛利；检查分支断言 |
| EXPR 翻译将字符串常量 T 也当作字段，DRL 编译失败 | 1 | 改为逐字符翻译标识符并跳过引号字符串，添加回归断言 |
| 临时库存测试中 JdbcTemplate.query lambda 重载不明确 | 1 | 显式强制转换为 RowCallbackHandler |
| 原生 DRL 测试使用保留关键字 `native` 作为 Java package 片段 | 1 | 将测试 package 改为 `advanced.sample`，原生编译与执行通过 |
| Feign mock lambda 不能抛 Jackson checked exception | 1 | 测试先解析 JsonNode，再从 Feign 接口 stub 返回已解析对象 |
| SDK `mvn install` 因 maven-install-plugin 2.5.2 的 maven-shared-utils 依赖描述不可用而失败 | 1 | 在获得依赖下载许可后用 package + Maven 安装插件 3.1.4 成功安装 |
| Worker 编译目标被本机 Maven settings 强制到 Java 11，Java 8 编译器报无效目标 | 1 | worker POM 显式配置 compiler source/target 1.8，重新构建成功 |
| Worker 测试使用 SDK 工程的 JUnit 4 import，但 Worker Boot 测试依赖仅提供 JUnit 5 | 1 | 测试改用 `org.junit.jupiter.api.Test` 与 JUnit Jupiter assertions |
| Drools 测试编译器无法从测试类路径解析 test-scope Java fact POJO | 1 | 改用 DRL declare 类型验证 Worker 的动态 FactType JSON 映射与序列化恢复路径 |
| H2 不支持 MySQL `DATE_SUB` 函数，阻断 Worker owner URL 多节点测试 | 1 | 使用数据库 `CURRENT_TIMESTAMP` 计算 heartbeat cutoff，再以参数化时间比较，兼容 MySQL/H2 |

## 2026-09-30 生产中台重构（当前任务，替代旧能力范围）

用户要求：单据/类型/运营规则页面；业务按 ruleType 执行；原生 Drools 能力不可阉割；可重新设计架构。既有未提交工作保留，不操作实际业务库。引擎基线先统一 7.73/Java 8，完整能力通过原生资源、容器和运行时 SPI 开放，不能把 HTTP JSON 的限制视为引擎限制。

21. [complete] 统一 reactor、公共事实契约和规则资源编译器，记录设计与能力验收矩阵。
22. [complete] 按类型不可变发布包、乐观锁、历史/回滚、提交后加载；统一中台与 SDK 的规则包边界。
23. [complete] 按类型单据/原生/DMN 执行；开放多资源/决策表/DSL/KJAR 与原生容器扩展。
24. [complete] 修复 SDK 序列化、配置指纹、会话并发/生命周期；Worker entry point 操作及恢复维护。
25. [complete] 运营发布页面、权限与可观测性；部署/迁移文档及真实故障场景自动测试。
26. [complete] 完整 reactor 回归、页面验证、最终架构复核与未验证生产环境事项记录。

## 2026-09-30 本地数据库重建与商品审批示例

27. [complete] 为全部 20 张表/字段补齐说明，备份并按新 DDL 重建 test 库项目表。
28. [complete] 注册 SKU 商品单据、SPU/SKU/区域价格三步审批、参数、输出、模板并真实发布。
29. [complete] 执行阈值/零成本/逐项隔离验收，保存可重复初始化脚本与请求响应示例。

## 2026-09-30 Facts / DRL 答疑及冗余清理

30. [complete] 清理无调用封装、虚假引擎刷新接口、旧输出路径、重复脚本及运行时包层级。
31. [complete] 备份并迁移5个冗余字段，保持20张必要表、旧发布快照和商品配置可执行。
32. [complete] 完整40项测试（含原生多文件函数）、真实HTTP旧发布/配置验收，更新答疑、DDL和示例SQL。
