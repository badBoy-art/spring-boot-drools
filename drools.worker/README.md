# 长会话 Worker

运行 `drools.worker/target/drools.worker-1.0.0.jar`。数据库、认证、中心执行账号和唯一节点 ID 配置见 [生产部署](../docs/production.md)。执行 SDK session store 与 Worker store 两个 CREATE 脚本，数据库默认使用指定的本地连接配置；Worker 接口认证仍需显式配置。

| HTTP | 操作 |
| --- | --- |
| POST /worker/sessions | 创建 `{ruleType,clockType:"pseudo"/"realtime",active,timedRuleExecution}` |
| POST /worker/sessions/{id}/facts | 插入 `{type:"包.类",fact:{...},entryPoint}` |
| PUT 同上 | 更新 `{factHandle,type,fact}` |
| DELETE 同上?factHandle=... | 删除事实 |
| GET 同上 | 列出全部 entry point 的事实和 handle |
| POST /worker/sessions/{id}/fire | fireAllRules，可传 `{max}` |
| POST /worker/sessions/{id}/queries | `{name,arguments:[]}` |
| POST /worker/sessions/{id}/globals | `{name,value}`，JSON sidecar 恢复 |
| POST /worker/sessions/{id}/agenda/{group}/focus | 设置 agenda focus |
| POST /worker/sessions/{id}/clock/advance | pseudo clock `{amount,unit:"MILLISECONDS"}` |
| POST /worker/sessions/{id}/fire-until-halt | active mode |
| POST /worker/sessions/{id}/halt | 停止 active mode |
| POST /worker/sessions/{id}/checkpoint | 保存检查点 |
| GET /worker/sessions/{id}/owner | 查询归属节点地址 |
| DELETE /worker/sessions/{id} | 关闭会话与清理 snapshot |

全部 Worker 接口需要 Worker账号。非 owner 返回409 SESSION_NOT_OWNED 及 ownerUrl，客户端按 owner 路由；不要无上限重试。declared facts 自动从 KieBase 查类型，Java facts 需在 classpath 并明确配置 allowed-fact-types。

默认每次写操作提交检查点后应答。insert/update/fire 可带 `Idempotency-Key`，同会话同key返回已提交响应，参数改变返回409。检查点与命令日志使用同源事务。自动timer/active执行与外部系统副作用的恢复语义见生产文档；不能保证任意副作用 exactly-once。

HTTP 不承载任意 Java listener、live query回调或自定义 native command；这些通过 SDK 原生 KIE API 使用，不通过削减 Drools 能力来简化 HTTP 模型。
