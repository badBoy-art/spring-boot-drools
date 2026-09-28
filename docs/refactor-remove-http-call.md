# 重构：拆掉「接口注册 / HTTP 调用」，引擎回归「单据注册 + 规则注册」

> 结论定调（用户 2026-09-28）：规则引擎不做外部调用；**调接口由业务系统自己做**，
> 业务把入参按注册单据的字段传进来，引擎只负责 单据注册 / 规则注册 / 规则执行并给出决策结果。
> 动手前的快照：`git commit 8e3bcaa`（含接口注册的全部实现与 209 项验收），回滚 `git reset --hard HEAD~1`。

## 一、引擎改造后的职责边界

| 能力 | 归属 | 说明 |
|---|---|---|
| 单据注册（单据→对象→字段，含派生表达式 expr） | **引擎** | 保留：业务按注册结构把参数传进来 |
| 规则注册（规则类型 / 规则 / 组合规则表） | **引擎** | 保留：条件 + 动作（写字段 / 打标 / 加消息） |
| 规则执行与决策输出 | **引擎** | 保留：入参 → 命中结果（ext 字段、消息、放行/拦截） |
| 外部 HTTP 调用（域名、认证、加解密、签名、超时、重试） | **业务系统** | 引擎**不再**保存任何接口配置，也不发起任何请求 |
| 步骤链 | 引擎（形态简化） | 见下"保留 vs 删除" |

## 二、要删的东西（清单，逐项可核对）

**Java**
- `src/main/java/com/example/drools/http/` 整包 7 个类：HttpActionGateway / HttpAuthSupport / CryptoSupport / JwtSupport / RuleHttpProperties / DomainResolver / HttpConfigException
- `controller/HttpActionController.java`、`controller/HttpEnvController.java`、`controller/MockHttpController.java`
- `entity/`：RuleHttpAction、RuleHttpActionReturn、RuleHttpAuth、RuleHttpCallLog
- `dao/`：RuleHttpActionDao、RuleHttpAuthDao
- 改造：`RuleStepBuilder`（去掉 CALL 动作与参数绑定）、`RuleTypeController`（去掉 step/test 调接口试算、去掉 HTTP 相关注入）、`AssetRefDao/AssetRefController`（引用反查只保留"单据 ↔ 规则类型/规则/组合表"）、`RuleDefinitionService.validateActionCode`（整段删）、`DynamicRuleEngine` 的 global `httpActionGateway` 注入、`GlobalExceptionHandler` 中 HttpConfigException 分支

**资源/数据库**
- `static/rule-admin.html` / `rule-console.html` 里的「② 接口注册」页签、环境域名面板、接口分类、入参模板/参数模板占位符提示、返回值映射、`#/actions/**` 全部路由
- `src/main/resources/db/`：http-action.sql、http-auth.sql、http-env.sql、asset-reuse.sql（接口 scope 部分）、risk-check-step.sql
- **表**：`rule_http_action`、`rule_http_action_return`、`rule_http_action_scope`、`rule_http_auth`、`rule_http_call_log`、`rule_http_category`、`rule_http_domain`（7 张，DROP）

**测试/脚本/文档**
- 测试：`http/HttpActionFlowTest`（8）、`http/HttpAuthCryptoTest`（10）删除；`http/DocFactRuleTest`（2）改造成不依赖网关的纯规则执行测试
- 脚本：`http-env-acceptance.sh`、`param-binding-acceptance.sh`、`migrate-risk-check-step.sh`、`asset-reuse-acceptance.sh`（重写为"单据复用"）、`approval-flow-acceptance.sh`（改成"决策链"= 判定级别→写字段→输出决策）、`rule-step-acceptance.sh`（去掉 CALL 断言）
- README：所有 HTTP 动作/接口配置/参数模板契约/迁移记录章节

## 三、保留 vs 删除的步骤链

规则类型仍可多步骤（步骤 = 条件 + 动作），**动作白名单收敛为 3 个**：
1. 写单据字段（ext 决策字段，如 approvalLevel=L2）
2. 打标（ext 布尔）
3. 只加消息（人工可读的判定说明）

`调接口（CALL）` 与「参数绑定 / 返回值映射 / 上游 ext 依赖」随接口注册一起删除。

## 四、待用户拍板的一个口径（决定引擎输出形状）

业务做完外部调用后，怎么让引擎继续算后续步骤？
- **口径 A（最纯粹）**：引擎只输出决策（ext 字段 + 消息）。业务读响应自己决定调什么接口，调完把结果作为新的单据字段**再调一次引擎**。引擎里完全没有"要调哪个接口"的概念。
- **口径 B（保留接力能力）**：规则步骤里可以声明一个「业务动作」（动作名 + 入参映射，如 `RISK_CHECK` + `{bizId:${orderId}}`）；引擎**不求值调用**，只在响应里返回 `actions:[{stepNo, action, params, message}]`；业务照着调，再把结果按约定传回继续后续步骤。

## 五、验收口径（改造后必须仍可验证）

- 单据注册：三层结构（单据→对象→字段）+ 派生 expr，业务按结构传参 → 引擎构建事实
- 规则注册：类型/规则/组合规则表，页面可点、发布即生效
- 执行与决策：一笔单子进 → 命中若干规则 → 响应里给出 ext 决策字段 + 消息（+ 可选 actions）
- 一键验收脚本重写：`scripts/decision-acceptance.sh`（单价 5 折链路：毛利率→审批级别→决策输出，断言 ext 与消息）
