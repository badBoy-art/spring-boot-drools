# 业务接入

业务使用规则类型编码，不能按单据编码执行所有规则。一个单据可以绑定多个类型，各类型拥有独立发布记录和运行实例。响应中的 `releaseId`、`revision` 用于定位实际版本。

## 单据执行

中心：`POST /rule/evaluate?ruleType=ORDER_CHECK`，请求体是注册的单据 JSON，返回 `decision/data/ext/messages/fired` 及发布版本。SDK：`sdk.evaluate("ORDER_CHECK", data)`，使用相同冻结单据 schema 和输出树。

运营登记单据/对象/字段，然后创建规则类型、配置步骤及返回结构、修改参数并发布。参数、模板和元数据是草稿；上线结果来自发布快照。原生类型可不绑定单据、不配置步骤与返回树。

## 原生资源发布

1. `POST /rule/type/native`：`{"ruleType":"NATIVE_CHECK","typeName":"原生检查"}`；创建是 insert-only。
2. `GET /rule/releases/NATIVE_CHECK/draft`：读取 `draftVersion` 和当前 `revision`。
3. `PUT /rule/releases/NATIVE_CHECK/draft`：`{"expectedDraftVersion":0,"configuration":{"eventProcessingMode":"stream","resources":[{"path":"src/main/resources/check.drl","content":"package checks; rule R when then end"}]}}`。
4. `POST /rule/releases/NATIVE_CHECK`：`{"expectedRevision":0,"expectedDraftVersion":1,"remark":"首次发布"}`。
5. `GET /rule/releases/NATIVE_CHECK` 查历史；`POST /rule/releases/NATIVE_CHECK/rollback`：`{"releaseId":旧版本ID,"expectedRevision":当前修订,"remark":"回滚原因"}`。回滚新增修订，不覆盖历史。

写管理接口使用编辑账号和 `/rule/csrf` 提供的 header/token，同时保留 CSRF cookie。规则级发布/状态/删除必须带数值 `If-Match: 当前规则version`；冲突返回409，刷新后重新编辑。

Java 源码可用 `src/main/java/包路径/类名.java` 与 DRL 同包发布，由原生 KieBuilder 编译。resources 每项支持 `content` 或 `base64`、可选 `resourceType`。决策表：`resourceType=DTABLE`、`inputType=CSV` 或 `XLS`、可选 `worksheetName`。其他包属性：`kmoduleXml`、`pomXml`、`kieBaseName`、`executableModel`；导入 `kjarBase64` 或 `artifact:{groupId,artifactId,version}` 与源码模式二选一。Maven 依赖与业务类需能从节点 classpath/仓库解析。

## 原生 HTTP 执行

`POST /rule/native/evaluate?ruleType=NATIVE_CHECK` 请求示例：

```json
{
  "facts":[{"package":"checks","type":"Input","values":{"amount":100},"entryPoint":"Events"}],
  "globals":{},
  "agendaGroups":["validation"],
  "queries":[{"name":"Results","args":[]}],
  "clockType":"pseudo",
  "advanceTimeMs":1000
}
```

`maxFirings` 和 `ruleNames` 可显式限制这一次点火；不传时使用 native fireAllRules。`data` 可用于绑定单据。该入口仅桥接 declared facts 与 JSON globals；任意 Java 类/回调使用 SDK。

DMN：`POST /rule/dmn/evaluate?ruleType=编码`，body `{namespace,modelName,data}`。PMML：`POST /rule/pmml/evaluate?ruleType=编码`，body `{modelName,data}`。这两类模型与 DRL 共用发布快照。

## 业务可靠性

同步执行不替业务写业务数据库。规则中调用外部系统可能产生不可回滚副作用；优先把结果作为决策返回业务，再由业务事务/outbox 执行。HTTP 超时不能证明规则没执行。长会话命令的幂等与持久化见 Worker 文档。

不兼容的事实类/规则结构修改应创建新会话。恢复严格匹配创建该会话的 bundle、KIE 版本、运行配置与序列化策略，拒绝拿最新规则强行读取旧 snapshot。
