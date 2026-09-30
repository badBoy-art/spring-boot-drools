# 调研发现

- 项目数据库 SQL 已整理为纯 `CREATE TABLE`；规则类型、步骤、返回结构通过管理页面/API 持久化。
- 此任务要求的计算包含 SPU 单对象和两个不同集合的逐项计算；当前返回结构中的 `EXPR/DATA` 能映射输入值，但需核实规则动作写入值是否逐明细隔离，避免 SKU/区域结果互相覆盖。
- 用户提供过的数据库密码不应写入文件或再次用于连接。需要从本地应用配置中确认目标是开发库，并使用不会泄露口令的连接方式。
- 已核实项目配置的 JDBC 主机为 `127.0.0.1`、schema 为 `test`，不会把配置密码输出到终端。
- `RuleStepBuilder` 目前一个步骤仅写一个 `extField`；`EACH` 用 `accumulate/collectList` 收集匹配行后写主事实 ext。要求的 SKU/区域逐项决策需要 item-local ext 和 step outputs 子表。
- `ReturnStructureService.mapElement` 可递归返回数组叶子，但没有关联每个 DocItem 的规则输出；需按 collection key + index 关联输入元素和 DocItem 决策结果。
- 本机 `test` 库盘点：rule_type_meta=7（全部 builtin=0），rule_type_field=9，rule_template=7，rule_step=10，rule_output_field=0，rule_definition=0。
- 唯一商品单据是 `SKU`（名称“商品”），现有单对象 `sku`、9 个平铺字段。将保留 docCode 和单据主记录，只替换此单据的对象/字段注册为 SPU + 两个集合；无已存在规则引用这些类型需要保留。

## 原生 Drools 能力中台调研（2026-09-29）

- 用户给出的 Apache KIE 仓库说明 Drools 同时覆盖 DRL 规则、DMN 决策模型和 CEP 事件处理；仓库包含独立的 Decision Tables 模块。引用：[apache/incubator-kie README](https://github.com/apache/incubator-kie)。
- Apache KIE 当前 DRL Language Reference 记录 agenda/ruleflow group、timer/calendar、entry-point、accumulate 等原生语言和运行时机制；timer 语义取决于 passive `fireAllRules()` / active `fireUntilHalt()`，dispose session 会结束 timer。引用：[Drools Language Reference](https://kie.apache.org/docs/10.2.x/drools/drools/language-reference/index.html)。该文档是当前版本，工程自身仍使用 Drools 7.73 / Java 8，落地 API 必须以项目依赖版本为准。
- 用户给出的博客列出 DRL `package/import/global/function/query/rule`、多个 pattern、规则属性（salience、dialect、enabled、date-effective/expires、activation-group、agenda-group、timer、auto-focus、no-loop、lock-on-active）、规则过滤器等能力。引用：[Drools 规则引擎应用](https://www.cnblogs.com/ityml/p/15993391.html)。文章示例年代较早，不能替代项目版本官方 API。
- 当前工程只依赖 `kie-api`、`kie-internal`、`drools-core/compiler/mvel` 7.73；未依赖 Decision Tables/DMN。`DrlGenerator` 自动写死 dialect/import，并把配置翻译成 DocFact 模板；因此这是便利 DSL，不是原生 DRL 全能力入口。
- 当前 `RuleDefinitionService.create/publish` 总是按模板生成 DRL，虽 `rule_definition.drl_content` 是 TEXT、`DynamicRuleEngine.refresh()` 已能直接编译非空 DRL，但页面/服务不会保留用户编写的原生 DRL。`RuleController.evaluate()` 每次只建临时 KieSession、插入 DocFact/DocItem、调用 `fireAllRules()` 后 dispose；无 globals、额外事实、entry point、查询、agenda focus 或跨 HTTP 会话生命周期接口。
- 建议边界：运营友好的“配置模式”继续作为快捷子集；并列提供完整 DRL 原文模式，以原生 KIE 编译器编译、不经 `DrlSyntax`/模板改写；HTTP 使用同一 docCode 的单据载荷，且需显式描述能通过一请求完成的 KIE 会话能力。要承诺包括长期 CEP、定时器等所有运行时功能，还需要跨请求有状态 session 管理与可控生命周期，不能只靠当前 stateless evaluate。
- 用户确认范围：只要求 DRL，不要求 DMN/Excel 决策表；长生命周期会话由业务侧 Java SDK 持有，SDK 通过 HTTP 查询规则类型配置即可。
- 新增 `GET /rule/type/runtime?ruleType=...` 返回规则类型字段、返回结构、docCode 和已发布 DRL；独立 `sdk/` Maven 工程按类型拉取并编译本地 KieBase，以 UUID 会话管理 stateful KieSession，直接暴露 KIE 原生 API，并提供 active `fireUntilHalt` start/halt/close。
- SDK KieBase 使用 STREAM 事件模式；session 支持 realtime/pseudo 时钟。配置页面明确结构化编辑器是常用子集，完整 DRL 编辑器是语法能力兜底，避免运营表单被误认为覆盖整个 DRL 语言。
- SDK 集成测试通过本机临时 HTTP mock：同一 `fireUntilHalt` session 跨两次业务请求插入事实且两条规则分别触发；pseudo clock 下声明事件的 1 秒 timer 也触发。测试首次在 sandbox 运行时受限于 localhost bind；改为获得审批后运行成功。项目 `mvn test`、SDK `mvn -f drools.sdk/pom.xml test` 和页面语法校验通过。
- 生命周期边界：SDK 会话是业务进程内存态，必须按业务实例显式 close，不在 JVM 间共享，不提供进程故障后的 session snapshot/HA 恢复；`refresh` 仅影响新建会话，存量会话继续使用旧规则版本。
- 用户要求 SDK 清晰简单，调用中台提供的 Feign 接口而非 SDK 自行维护 HTTP 实现；运行时代码集中至 `com.example.drools.sdk.document`，Feign 合约为 `com.example.drools.sdk.document.feign.RuleTypeConfigFeignClient`，通过 `DocumentRuleSdkConfiguration` 启用并创建 SDK Bean。旧 `RuleCenterHttpClient` 已移除。
- Spring Cloud 版本按 Spring 官方兼容表选用 2021.0.9 / OpenFeign 3.1.9，对应现工程 Boot 2.7.18；官方 Spring Cloud 项目说明 2021.0.x 兼容 Boot 2.7（自 2021.0.3 起），2021.0.9 与 Boot 2.7.18 相容。引用：[Spring Cloud release train compatibility](https://spring.io/projects/spring-cloud/)、[2021.0.9 release](https://spring.io/blog/2023/12/20/spring-cloud-2021-0-9-aka-jubilee-is-now-available)。
- 重构后 SDK 和中台完整回归均通过；SDK 会话、active 点火与伪时钟 timer 集成测试已改为 stub Feign 接口，不需要测试自行启动 HTTP 服务器。

## Timer 验证范围补充（2026-09-29）

- 对照项目依赖相近版本 Drools 7.74.1 参考手册（工程实际为 7.73.0）：timer 支持 interval `int`、cron、expression timer，interval/expr 支持 start/end/repeat-limit；calendar 名称需注册到 KieSession；passive `fireAllRules` 默认仅在调用时推进 timed consequence，active `fireUntilHalt` 持续处理，dispose 终止 timers。
- 官方说明 event-processing 有 CLOUD/STREAM 两种。当前 SDK 把 KieBase 固定为 STREAM，利于 CEP/windows，但目前没有向业务 SDK 暴露切换 CLOUD 或其它 KieBase/KieSession 配置的参数；该项应继续列作未完成，不应把“直出 KieSession”误称为所有运行配置已由 SDK覆盖。
- 官方参考：[Drools 7.74.1 timer/calendar/active-passive/event mode 文档](https://docs.drools.org/7.74.1.Final/drools-docs/html_single/)。
- 新增 SDK timer matrix 测试：pseudo clock interval/repeat-limit、expression timer、cron timer；realtime clock interval timer；passive `fireAllRules` 在 pseudo-clock 推进后再次点火；匹配事实 retract 后撤销 timer activation。Drools 7.73 SDK 5 项测试通过。
- 已完成后续补齐：`RuleRuntimeOptions` 支持 CLOUD/STREAM、任意 KieBase/KieSession 属性和 typed options（含 `TimedRuleExecutionOption.FILTERED`）、命名 KIE Calendar；DRL timer 的 start/end/repeat-limit 原样走 Drools 编译器。
- 新增业务侧可选 `KieSessionStore` SPI 和 `JdbcKieSessionStore`，`drools.sdk/src/main/resources/db/kie-session-store.sql` 仅创建业务应用存储表。protobuf marshaller 的跨 SDK 重启测试证明 fact state 和待触发 timer 可恢复；snapshot 归档了该 session 使用的 DRL bundle 和 runtime option SHA-256 指纹，因此发布新规则后旧 session 仍能恢复。
- KIE session globals/服务对象不由快照管理，恢复后应用必须重新注入。事实对象需支持 marshalling strategy；单会话必须由一个节点独占，JDBC snapshot 不等同 active-active 集群会话。应用需显式 checkpoint/delete。
- Drools 7.73 文档确认 passive timer 在初次 `fireAllRules()` 建立激活后，`TimedRuleExecutionOption.YES` 可免后续轮询自动执行；官方 timer/calendar 说明：[Drools 7.73 rule language](https://docs.drools.org/7.73.0.Final/drools-docs/html_single/)。

## 多节点状态与恢复边界（2026-09-29）

- JDBC owner lease 按数据库时间判断失效，fencing token 单调递增；恢复前先争抢 lease，存储层只接受仍持有效 token 的 checkpoint/delete。入口层可以用 `sessionOwner(id)` 将请求路由给 owner。
- lease 不等价于共享 KieSession，也不复制内存中的即时状态。节点故障后恢复点是最后一次成功 checkpoint；未 checkpoint 的状态/事件必然丢失，timer 时间推进行为应按真实时钟和恢复延迟单独验证。
- 原生 session 引用和规则 consequence 可以执行 SDK 无法代理的调用，故不能仅凭数据库租约实现任意外部系统的 fencing。业务 RHS 若产生外部副作用，应使用幂等键/outbox/带 fencing epoch 的下游写入，并避免绕开 owner 路由。
- 当前集成验证使用 H2 模拟 JDBC 并发/接管，不证明实际 MySQL 版本、复制拓扑、隔离级别、网络分区和生产吞吐。上生产前须以目标 MySQL 集群做多节点争抢、进程 kill、DB 短暂不可用、快照恢复和长 timer/CEP 场景压测。

## Worker 与横向扩容设计依据（2026-09-29）

- 当前 `RuleDefinitionService.publish*()` 只调用本 JVM 内的 `DynamicRuleEngine.refresh()`；`KieBase/KieContainer` 是本地 volatile 字段，规则中台多副本会出现实例间缓存版本不一致。运行时配置包则从共享 DAO 查询已发布规则，版本传播应使用持久化 publication generation，并在每实例轮询/通知后编译、原子切换和上报生效 generation。
- SDK 在业务 JVM 内运行，现有 lease/fencing/snapshot 能作 Worker 底层，但没有可调用的 HTTP session API、节点地址目录或错误路由响应。独立 Worker 可依赖已发布的 SDK artifact，提供会话命令 HTTP API；以 sessionId 做单 owner 路由，故障后从最近快照恢复，而非宣称 active-active。
- Worker JSON 注入 native DRL 的普通 Java Fact 必须限制类型 allowlist，或将 fact 类型 jar 部署在 Worker classpath。仅用任意 JSON Map 不足以满足 DRL pattern 的 Java 类型解析；DRL consequence 可执行 Java 副作用，Worker/作者权限必须按可信代码处理。
- `SpringBootDroolsApplication.main()` 端口冲突分支运行 `lsof | ... | kill -9` 并反射式重启容器端口，属于危险、不可预测的部署行为，需改为标准 `server.port` 配置与启动失败。
- Drools 7.73 官方说明 stateful KIE session 保存运行时状态；active `fireUntilHalt` 会阻塞调用线程，建议使用专用线程，active mutation 可用 `KieSession.submit()` 保证原子性；passive timers 可通过 `TimedRuleExecutionOption` 自动执行但与 active mode 语义不同。参考：[Drools 7.73 session/active mode/timers](https://docs.drools.org/7.73.0.Final/drools-docs/html_single/)。
- Drools 7.73 KIE Server 提供容器与 BRM 扩展以及 REST/JMS、自定义扩展端点，但标准的命令式 HTTP 执行不自动等同长生命周期 `fireUntilHalt` Worker；若用 KIE Server，仍需实现或确认服务端会话驻留、路由和 timer 恢复策略。参考：[Drools 7.73 KIE Server extensions](https://docs.drools.org/7.73.0.Final/drools-docs/html_single/)。
- 本机 `brooks-audit` 技能的 `../_shared/common.md` 等共享文件路径缺失；可用 `architecture-guide.md` 已阅读并按其依赖图/分层风险框架审计。
- Worker 当前 HTTP contract 覆盖 session 生命周期、事实 insert/update/delete、entry point、全量或限量 fire、active fireUntilHalt/halt、查询、globals、agenda focus、pseudo clock 和快照。它不是对任意 `KieSession` 方法的 JSON RPC；渠道、复杂 Java global、listener、work-item handler 和版本特定 KIE 扩展通过 Worker 内 `KieSessionLifecycle` bean 安装，若需要不经 Worker 插件暴露任意原生 API，应选 Java SDK 直接持有 `KieSession`。
- Worker H2 验证包括空 session baseline checkpoint、声明类型 JSON 输入、跨 SDK 重建恢复/点火，另有完整 Spring Boot 上下文下认证/health 路由测试。H2 并未验证真实 MySQL 并发事务、k8s 多副本、网络分区和高负载下 lease margin；此为上线验收不可省略项。

## 2026-09-30 当前重构基线
- 审计确认默认 marshaller 对 DocFact 抛 NotSerializableException。Worker 默认入口句柄扫描不含 named entry points。typed options 指纹缺少实际值（IDENTITY/EQUALITY 同指纹）。
- 发布在事务内切内存；规则中台全量编译、SDK按类型编译；发布包需要成为唯一一致性边界。
- 当前测试：center 6/sdk 11/worker 2 通过，但不覆盖上述缺陷。生产 MySQL 集群尚未验收，不能宣称已证明可靠。
- 本次用户要求完整能力，覆盖此前只做 DRL/排除 DMN、Excel 的旧范围决定。

- 原生 PMML 7.73 的 kie-pmml-trusty 是 parent POM；实装 regression/tree/scorecard/mining/clustering 模型 compiler/evaluator。Rule units/JPA/KIE-CI依赖已加入。
- 发布语义采用不可变版本固定旧会话，提供NativeRuntimeLease供业务调用完整KieContainer API（包括原地版本更新/Scanner）；应用修改原生container后的快照归档语义须自行选择持久化实现。
- 主库发布历史采用新表，不自动执行DDL，不清理现有数据；旧库需应用新建表并逐类型首次发布。

## 2026-09-30 最终设计与验收发现

- 新 reactor：contract / runtime / sdk / center / worker；中心与 SDK 共用原生编译和冻结单据运行时，移除重复事实类。
- 发布以类型为边界：完整资源编译、同事务 immutable bundle + head、CAS、历史与回滚；在途运行代保留。Maven 导入实际 KJAR 及 KIE 依赖归档。
- 原生入口已验证 DRL/跨文件/query/TMS、DSL/DSLR、CSV 与 Excel 决策表、DMN/FEEL、PMML regression、KJAR、executable model、命名 base/session，以及包内 Java 类编译。完整 KIE API 与 Environment/factory/marshalling SPI 可接业务扩展。
- Worker 默认写后 checkpoint，insert/update/fire 有命令幂等；named entry point 的 handle 按 entry point/ID 匹配；JSON globals 在检查点中冻结，在 unmarshalling 前通过 Environment.GLOBALS 恢复。
- 真实 HTTP 暴露的额外问题：无状态认证清除 CSRF cookie；SimpleJdbcInsert 写 null 时间列违反 MySQL NOT NULL；过早 ConditionalOnBean 导致 JDBC store 不创建。均已修复并加回归。
- 相同工作区持续构建会覆盖正在运行的 jar，验收改用固定 /tmp jar 副本；该现象属于验收启动方式，生产应使用不可变镜像/制品。
- 独立 MySQL8.4 上真实 DDL 与事务回归通过；从未修改用户原业务数据库。
- 浏览器无可用连接且 Computer Use permissions 未授予，未宣称截图验收通过。已做 JS 语法与运行时视图渲染验证，并通过 HTTP 读取运营页面。
- 完整原生能力基于 Drools7.73。公司 SSO、多租户不可信代码隔离、任意副作用 exactly-once、目标生产容量与容灾不应作为本地回归已证明的结论；部署文档单独列明。

## 商品审批初始化设计

已有 RuleStepBuilder 支持 DOC/EACH 逐项输出与每行独立 ext。新增显式类型参数供 RHS 的阈值占位符使用，保留现有步骤条件参数。金额使用 BigDecimal 比较差额与成本*阈值，返回毛利率保留8位 HALF_UP，避免用舍入后的结果做阈值判断。

- 真实MySQL5.7的新非空DDL揭示RuleDefinitionDao的SimpleJdbcInsert默认纳入所有列，未提供的create_time/update_time显式NULL；限定usingColumns到8个业务字段，让数据库默认时间生效。
- 实际sample原生DRL使用BigDecimal差额与成本*阈值比较，判定不受输出8位毛利率舍入影响。输出实例字段直接在三个作用域的独立ext中写入。
- test库旧项目数据已按用户最新授权备份并清除，早期“未操作用户业务库”的历史记录仅适用于之前验收阶段，不代表本次无数据库写入。

清理发现（先列出后分类）：DocFactBuilder/ReturnStructureService无生产调用；DynamicRuleEngine仅做单文件校验却伪装全局引擎并提供空refresh/恒null错误；runtime.document实现散落在center相同service包；output_fields/is_output/单输出ext字段被新版层级输出取代，页面仍显示错误的即时生效说明；4个单行验收壳脚本重复accept-all；AssetRefDao扫描源码代替doc_code关系导致原生类型绑定漏检。每项均为已确认局部清理或修复，不据此删除完整原生能力。brooks-debt共享指南缺失，使用可用debt-guide与调用关系证据。

## 2026-09-30 最新答疑与清理结论

Drools Fact是insert到KieSession的对象，不要求业务提供特定Facts类；单据HTTP自动构建DocFact和DocItem。运营表单生成DRL，原生资源入口保留任意DRL及函数。no-loop防规则自更新导致自身再次激活，lock-on-active在组活跃期间抑制所有来源新激活，salience为候选优先级。

旧输出5字段实际均未配置，迁移不损失当前配置。历史发布包JSON仍有作者字段isOutput，运行时对冻结单据字段配置忽略未知字段以兼容旧快照；不重写bundle_json/hash。20张表均有运行用途，本轮删除字段而非必要表。完整40测试及旧发布真实HTTP通过。
