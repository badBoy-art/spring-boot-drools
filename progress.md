# 进度日志

## 2026-09-29

- 新任务：用户将“保留原生 Drools 全能力，同时提供运营页面及 HTTP 接入”设为首要目标。已阅读规划技能并研究用户指定 Apache KIE 仓库、Drools 语言文档与博客；当前项目的步骤生成器和单次 DocFact 会话确属功能子集。新计划已将工作转为双模式架构，先开放原生 DRL 原文入口与 HTTP 执行验证。
- 原生规则后端：`/rule/native/validate|create|publish/{id}` 已加入；原生源码只附加一行合法 DRL 注释标识模式，发布时直接送 KIE 编译，不走模板、固定 dialect 或 `DrlSyntax` 翻译；普通参数发布会拒绝覆盖原生规则。
- 原生规则页面：规则类型详情新增“原生 DRL”编辑器，可创建和修改源码，列表标记原生模式；对原生 RHS 执行代码的信任边界已有页面提示。
- HTTP 原生执行：新增 `/rule/native/evaluate?docCode=...`，可插入单据/明细、DRL `declare` 类型事实及 entry point，设置 JSON global、agenda group、指定规则名过滤，执行 named queries；KieBase 开启 STREAM 模式，支持 realtime/pseudo clock 和事件时间推进；未传 maxFirings 时遵循 KIE 原生不限次数点火。
- 新增纯 KIE 测试覆盖原生 function/global/query/agenda/entry point/declare；服务测试确认源码保真及配置发布流程不会重写。`mvn clean test -q` 通过 6 项，页面脚本语法及 `git diff --check` 通过。
- 第一阶段限制（已由后续 SDK 扩展处理长会话）：单次 HTTP 评估仍会 dispose session；长期运行改走 SDK，不包含 DMN/Excel Decision Table。

## 2026-09-29（SDK 扩展）

- 根据用户澄清：只要求 DRL，不做 DMN/Excel；长会话由业务侧 SDK 管理，配置经 HTTP 查询。新增 `GET /rule/type/runtime?ruleType=`，输出该类型配置、返回结构和已发布 DRL 原文（配置生成 DRL 为空时按现模板生成）。
- 新增独立 `sdk/` Java 8 / Drools 7.73 SDK：HTTP 拉取并编译到本地 STREAM KieBase；业务代码通过 session id 持有 KieSession，SDK 管理生命周期并提供后台 `fireUntilHalt`、halt、close，底层 KIE API 完全可访问。SDK 包含配置规则依赖的通用 DocFact/DocItem 类型。
- 页面将入口明确命名为「结构化快捷配置」和「完整 DRL」，说明结构化输入无法枚举所有表达式，任意 DRL 语法通过源码模式保证。README 与业务接入文档新增安装和跨请求示例。
- 集成测试模拟 SDK HTTP 拉规则后，用同一 session 在两次独立插入中触发不同规则，验证跨调用事实状态与 active 模式；另以 pseudo clock 验证声明事件上的 1 秒 timer 触发。`mvn -f drools.sdk/pom.xml test` 与根工程 `mvn test` 均通过；localhost mock 测试需要授权沙箱外执行。页面 JS 语法和 `git diff --check` 通过。
- 明示会话保持在一个业务 JVM 内，业务要管理业务键与 session id 并关闭会话；refresh 只作用于新会话，存量会话沿用原规则版本；没有进程故障后的 session 恢复/跨 JVM 复制。

## 2026-09-29（单据 SDK / Feign 收敛）

- 将引擎调用与会话类移到 `com.example.drools.sdk.document`，Feign 契约独立放入 `com.example.drools.sdk.document.feign`；移除 SDK 自维护 URL/HttpURLConnection 的客户端实现。
- 新增 `DocumentRuleSdkConfiguration`，通过 `@EnableFeignClients` 注册中台 `GET /rule/type/runtime` Feign 客户端，并装配 SDK Bean；业务侧设置 `drools.rule-center.url` 后可直接注入。
- SDK POM 引入 Spring Cloud 2021.0.9 BOM / OpenFeign 3.1.9，匹配 Boot 2.7.18 与 Java 8。依据 Spring 官方兼容说明确定版本。
- 测试使用 stub Feign 接口返回规则包，继续验证跨业务请求 stateful session、`fireUntilHalt` 和 pseudo clock timer。`mvn -f drools.sdk/pom.xml test`、根工程 `mvn test`、`git diff --check` 均通过。

## 2026-09-29（Timer 场景核验）

- 依据 Drools 7.74.1 参考手册补齐测试：pseudo-clock interval/repeat-limit、expression timer、cron timer；realtime interval timer；passive 模式推进 pseudo clock 后再次 `fireAllRules()`；retract 事实后 timer activation 取消。
- SDK 5 项测试通过。`task_plan.md` 补充三个真实缺项：calendar/start/end/被动 timed execution option 还未逐项验证，KieBase/KieSession 配置仍固定 STREAM+默认值；持久化/跨节点 session 恢复延后处理。

## 2026-09-29（会话恢复与多节点单所有者）

- 新增 `KieSessionLifecycle`：在创建和快照恢复后、会话开放使用前调用，供业务绑定 globals/listeners/channels/work-item handlers；重启恢复测试验证 callback 能重绑不可序列化的 global。
- JDBC 快照表增加 owner/fencing 元数据，并新增会话租约表及 `JdbcKieSessionCoordinator`。每会话同一时刻仅一个节点取得 lease；过期后可接管；SDK 定时续租、失租关闭本地句柄，checkpoint/delete 由数据库校验 fencing token。
- 增加 `sessionOwner()` 供入口路由；配置 node id、lease 时长、快照体积上限；`KieSessionSnapshotObserver` 暴露快照/恢复大小和耗时、租约丢失通知。
- 新增 H2 集成测试覆盖节点竞争、租约到期接管、旧 token 无法覆盖新快照、恢复必须由当前 owner 执行；SDK 11 项通过，主工程 `mvn test -q` 通过，`git diff --check` 通过。
- 说明限制：对外仍暴露原生 KieSession，因此只能提供共享状态存储的单 owner failover，不能承诺 active-active；应用需路由请求且不得继续使用失租前保存的原始 KieSession 引用。fencing 只约束 SDK JDBC 快照，不拦截 RHS 已发出的任意外部副作用。
- 生产级真实 MySQL 集群的并发/故障注入、吞吐容量和备份恢复尚未执行，列入 task_plan 第 16 项待部署环境验证；文档明确不宣称该项已完成。

## 2026-09-30（规则同步与 Worker 服务实现中）

- 用户要求将缺口补齐并允许增加 Worker；新增阶段 17–20，目标为规则发布跨副本同步、独立 HTTP runtime-worker、会话 owner/自动恢复，以及清理危险端口抢占启动代码。
- 首批代码：新增 `rule_publish_event` DDL；发布/启停/删除在事务内写事件；规则中台各副本每 3 秒轮询持久化代次并刷新本地 KieBase；启动类移除端口冲突时执行 `kill -9` 的 shell/反射逻辑，改为标准 Spring Boot 启动。
- Worker 从独立 `kie_worker_session` 恢复目录加载候选会话，再按 ID 读取 snapshot，避免扫描所有 LONGBLOB payload；没有在 SDK store 增加未使用的全量 payload API。
- 新增独立 `worker/` Boot 服务脚手架、节点/会话目录 DDL、HTTP Basic 保护、事实与 session 命令 API、自动 checkpoint/恢复扫描和 Worker 接入文档；目前尚在首轮编译修正，不能视作已验收。
- 首次 `mvn -f drools.sdk/pom.xml install -q` 测试阶段运行结束后，在 `maven-install-plugin:2.5.2` 下载 `maven-shared-utils:0.4` 描述文件时失败；SDK测试 XML 检查后再使用新版本插件/授权网络方式构建 Worker。
- 安装旧版 install plugin 报错“packaging plugin did not assign file”，原因是直接调用 install goal 未先执行 package；修正为执行 `package` 后调用 3.1.4 install plugin，SDK 安装成功。
- Worker 首次构建因项目用户 Maven 设置把 Java release 强制成 11、当前编译器仅支持 Java 8 而失败；在 worker POM 显式配置 source/target 1.8 后，`mvn -f drools.worker/pom.xml package -DskipTests` 成功。
- 已调整 worker 维护循环：检查 owner 变化并清除失效本地索引，租约过期时允许重新恢复；active 模式不允许再调用被忽略的 `fireAllRules`。
- Worker 第一轮测试编译发现测试 import 了未依赖的 JUnit 4；根据 Boot 2.7 测试 starter 改为 JUnit 5，加入 typed fact 创建、checkpoint 与重启恢复集成测试。
- 集成测试首次运行发现 Drools KieBuilder 的动态类加载器无法解析 test-scope Java Fact；切换为原生 DRL `declare` type，直接验证 Worker 动态事实映射以及 session 恢复。
- Worker 集成测试现已通过：创建会话、从 DRL declare 结构插入事实、checkpoint、模拟 JVM 关闭、扫描目录恢复并 fireAllRules。
- 加强 `fireUntilHalt` checkpoint 停止超时处理：若不能确认 active firing 已停止则 checkpoint 失败并失租关闭，不再忽略超时后继续序列化潜在并发变动中的 session。
- 清理未被 Worker 使用的 SDK snapshot catalog SPI；Worker 以自身轻量 session 目录加载 session id，只有获得 owner lease 后才取单个快照 payload。
- 主工程 `mvn test -q` 已通过；待以最新 SDK 重跑 SDK/Worker/根工程全套回归。
- 全部最新回归完成：根工程 `mvn test -q`、SDK `mvn -f drools.sdk/pom.xml package org.apache.maven.plugins:maven-install-plugin:3.1.4:install -q`、Worker `mvn -f drools.worker/pom.xml test -q` 均成功；SDK 11 项、Worker service 恢复测试 1 项、Worker HTTP/Security Boot 测试 1 项均无失败；已执行 staged/unstaged `git diff --check`。
- 新增 Worker HTTP working-memory facts 查询；README 补充规则中台多副本和 Worker 构建/部署入口。17–20 阶段完成；phase 16 留待目标 MySQL 集群故障注入、真实多副本容量压测与灾备演练。
- 增强 Worker 多节点 H2 集成场景：节点 B 对节点 A 所有会话返回 owner node/base URL；A 关闭后，B 恢复 checkpoint 并继续 fire。测试发现 H2 不支持 `DATE_SUB`，节点目录改用 DB 当前时间推导 cutoff 后参数查询，MySQL/H2 均可用；目标 MySQL 集群测试仍待部署环境执行。
- `/rule/engine/info` 新增 `observedPublishRevision`，可通过负载均衡逐实例检查规则缓存是否追平已提交发布代次。
- 最终打包 `mvn -f drools.worker/pom.xml package -q` 成功，生成 `drools.worker/target/drools.worker-1.0.0.jar`；staged 与 unstaged diff whitespace 检查通过。

- 已读取 `planning-with-files-zh` 与 `local-database` 操作规范。
- 已按请求清理本机 test 库中的旧规则类型并创建新的三层商品规则类型；既有 SQL 清理工作区改动保留，SQL 中无初始化 DML。
- 检查了规则链/事实/返回服务：确认现有 EACH 聚合结果不足以隔离多条目赋值；项目连接配置指向本机 test 库，密码未输出。
- 添加了 `RuleStepOutput` 多赋值模型、明细事实独立 ext、按路径关联数组行事实；`rule-step.sql` 加入输出子表结构。
- 第一次 `mvn test -q` 编译发现 itemAssignments 变量声明顺序问题，已修复，待重跑。
- 后续 DRL 编译测试发现 EXPR 里字符串常量会被字段翻译器错误改写，已修复 `DrlSyntax.toFactExpression` 并加入字符串回归断言；另修正一个测试样例毛利率/预期不一致。
- 本机 test 库只读清点完成：7 个自定义类型、0 条规则；SKU 商品单据只有一个平铺对象和 9 个字段，确认可保留单据主记录并仅替换对象树/字段。
- 三步多对象 DRL/返回结构单元测试已通过；前端脚本语法和 `git diff --check` 通过。
- 已在事务中删除 7 个旧自定义类型及关联配置（原本无规则数据），保留 SKU 商品单据主记录并改成 spu / skus[] / regionPrices[]；新建 `SKU_MULTI_SCOPE` 类型及发布规则 `SKU_MULTI_SCOPE_DEFAULT`。
- 本机 DB 端到端调用命中 5 次：SPU 1 次、两个 SKU、两个区域价格；验证了毛利率阈值产生 T+2/T，并返回各层级字段。
- 页面试算示例已对齐 SKU 单据字段。一次性 DB 引导测试已删除；`mvn clean test -q`、SQL DML 扫描与 `git diff --check` 通过。

## 2026-09-29（运行配置与会话持久化补齐）

- 新增 `RuleRuntimeOptions`：CLOUD/STREAM、任意原生 KieBase/KieSession properties 和 typed options、timer calendars；默认仍 STREAM，原始 KieSession API 无阉割。规则 generation 和 bundle 由 SDK 本地 map 缓存，refresh 只影响新会话。
- timer 验证增加 interval start/end/repeat-limit、命名 calendar 与 `TimedRuleExecutionOption.YES` 被动自动执行；初始 `fireAllRules()` 建立 timer activation 后验证后续自动执行。
- 加入 protobuf KIE marshalling 依赖；新增 `KieSessionStore` SPI、JDBC 实现、业务侧 DDL 和 Spring DataSource 自动装配。快照可 checkpoint，重启后按相同 DRL/运行选项指纹恢复；测试还验证恢复未到期的 pseudo-clock timer。
- 快照行一并保存会话所用规则包原文；验证即使中台已发布新规则，也可从归档规则构建独立 KieBase 恢复旧 session，不影响新规则 generation。
- 业务 globals 在恢复后重新注入；不支持同一会话多节点并行恢复/操作，应用须单 owner 路由，并在工作流完成时删除快照。checkpoint 会暂时 halt active worker，业务需同步避免并发 fact 修改。
- 验证：SDK 9 项测试通过（H2 JDBC restart restore）；根工程 `mvn test -q` 与 `git diff --check` 通过。

## 2026-09-30 生产中台重构开始
- 已读取规划技能和现有历史，保留工作区改动；按新需求增加 21–26 阶段。
- 计划统一 5 个模块：contract、runtime、sdk、center、worker；已有运营表单保留并接入发布包。

- 已统一 Maven reactor（contract/runtime/sdk/center/worker），公共事实支持 Serializable，单据派生与输出组装移入纯运行时。
- 已实现按类型不可变发布、头指针、历史、回滚及版本冲突；单次评估使用带引用计数的代次，事务内不再切 JVM 缓存。
- SDK/中台共用原生多资源编译器；已通过 DRL 多文件/TMS、DSL/DSLR、CSV 决策表、DMN/FEEL、kmodule/executable model 共5项实测。
- SDK增加容器/base/runtime访问与引用租约、Environment/SessionFactory/自定义marshalling；typed option指纹纳入值；Worker named entry point查找/更新/删除修复。
- 新增中心 Basic角色权限、发布CSRF保护、Feign仅针对中心的凭据注入、环境变量配置；运营参数编辑与整包发布/资源上传/历史回滚页面已接入。
- 首轮发布事务测试4项中2项仅因Jackson LongNode/IntNode断言失败，改为数值断言；业务事务/隔离行为按预期。
- Maven PMML parent不是jar；改为org.kie的evaluator assembler与该版本支持的模型compiler/evaluator。

## 2026-09-30 重构收尾

已完成共享模块、完整原生资源路径、类型发布快照/CAS/回滚、运营页面发布、SDK 原生 API 与恢复、Worker durable/idempotent 命令、认证与 CSRF、metrics、部署和迁移说明。新增容器配置仅做部署入口，不冒充已做镜像部署验收。

回归曾通过33项（独立 MySQL），增加 Java事实/Excel/Boot JDBC自动配置后37项通过。当前执行最终 clean install + MySQL回归与稳定制品HTTP验收。原有 staged/unstaged改动保留，未提交git。

最终结果：clean install + 独立 MySQL回归37项全通过；完整真实HTTP验收通过；强制终止Worker后由另一node ID接管并恢复named entry point事实，继续fire/query成功。脚本语法、compose config、共享生产类重复检查、diff空白检查通过。详情见 docs/verification.md。验收应用与一次性容器完成后关闭。

## 2026-09-30 数据库连接与模块命名调整

按用户要求将 Center/Worker 默认数据库统一为本机3306端口的test库、root账号及指定本地密码（不在记录中复述）。保留环境变量覆盖。五个模块使用独立com.example.drools.<module>命名空间，并使目录、groupId、POM name相同；artifactId保留，父POM、跨模块依赖、脚本、Dockerfile与文档路径同步调整。进行完整clean install回归，测试使用隔离数据源，不操作本地业务库。

调整后完整 Maven clean install 成功；37项测试通过（失败0，错误0）。模块身份一致性、旧路径引用与diff空白检查通过。

## 2026-09-30 简化模块名并完全统一Maven身份

上一轮保留旧artifactId，导致IDE/Maven展示与新目录及groupId不一致。按最新反馈改为drools.contract/runtime/sdk/center/worker短名，目录、groupId、artifactId、name四者相同；父工程坐标及name统一为drools.platform。更新跨模块依赖、脚本选择器、SDK接入坐标和部署jar路径。数据库配置保持上一轮指定值。

短名统一后clean install成功；37项测试通过（失败0，错误0）。已检查五模块四项身份逐字一致，部署与接入无旧坐标或jar路径引用。

## 2026-09-30 最终模块命名规则澄清

按用户最新要求，仅目录与artifactId必须一致，保持drools.<module>短名；父工程及五模块groupId统一为com.example.drools，更新所有父工程/跨模块依赖和SDK接入文档。POM name不作为命名校验要求。数据库配置不变。

## 2026-09-30 用户授权重建项目表与初始化商品审批

用户明确授权删除旧项目表并以新结构重建。范围限定 DDL 列出的 20 张项目表，备份在仓库外保存，保留 user/user_01/user_02。设计返回 spu、sku、regionPrice；严格 >0.3 为 T+2，其余正常值为 T，成本<=0 返回 INVALID_COST。

真实 MySQL 首次初始化在 /rule/create 暴露 SimpleJdbcInsert 包含空 create_time/update_time 的问题；已限定显式写入列并添加使用真实部署 DDL 的回归测试。已注册元数据未发布，接下来仅恢复本次未完成的初始化，不重新删库。

新 DAO 回归首次加载整个 MySQL DDL 遇到 H2 的跨表索引名全局唯一限制（MySQL允许重名）；测试改为直接提取真实部署DDL中的 rule_definition 建表语句，仅验证对应表的非空默认时间字段。生产DDL未因此修改。

商品初始化及验收完成：127.0.0.1:3306/test 的20张项目表按新DDL重建，158个字段与所有表有注释，全部InnoDB/utf8mb4。备份路径 /Users/admin/.local/share/drools/backups/20260930-135146/project-before-rebuild.sql（600权限）。保留非项目表。

PRODUCT_SKU：1张单据、4个对象、14个字段。PRODUCT_MULTI_APPROVAL：1个参数、1条规则、3步、6个步骤输出、22个返回结构节点、1个模板、1个资源草稿、3个发布快照/事件，head revision=3、阈值0.3。真实HTTP确认SPU/SKU/区域价格逐项输出、严格阈值、精确小数边界、成本0/负数、空数组、修改阈值0.5后恢复0.3，以及负阈值拒绝且版本不变。临时Center已停止。

初始化SQL独立保存为 drools.center/src/main/resources/db/demo/product-approval-data.sql，JSON注册/配置/请求/实际响应保存 docs/samples/product-approval，API初始化脚本 scripts/product-approval-init.py。新自定义参数在运营类型编辑页面可维护，避免编辑类型时丢失参数。修复RuleDefinitionDao显式空时间字段插入问题并使用部署DDL添加回归。

最终测试统计40项，失败0、错误0、跳过0；完整reactor39项通过后新增DAO修复及对应回归，针对受影响的center/runtime/contract重跑通过，累计40项。静态JS语法、文档链接与git diff --check通过。导出核对初次因按字符串比较JSON空格不同而中断，改为JSON结构比较通过，未改变数据库。用户提示“现在重置了”后再次读取目标库，20表和已初始化数据均仍在，没有重复写入。

清理回归中旧NTH测试断言仍读取主事实ext，实际统一多字段输出已写入目标行ext；调整为断言目标行结果且主事实不污染。新增样例测试兼容清理前冻结单据字段中的isOutput属性。

## 2026-09-30 冗余清理完成

移除3个无效/未使用封装，独立DrlValidator，删除空刷新和错误引擎信息接口；迁移运行时 document 包；多字段输出及返回树统一，删除5个旧字段及4份旧验收脚本、临时DRL探针。原生资源、完整KIE SDK和Worker能力保留。

完整 `mvn -o clean install` 40项测试全部通过。MySQL迁移前备份：`/Users/admin/.local/share/drools/backups/20260930-164620/project-before-cleanup.sql`；现有20张表/153字段全部有说明，所有发布快照哈希/head保持不变。原revision3真实HTTP仍命中7次，边界、空集合、22输出节点和1/2/3赋值验证通过；引用保护409；临时Center已停止。同步14张Center配置表初始化SQL，并通过字段一致性及JS语法/视图验证。
