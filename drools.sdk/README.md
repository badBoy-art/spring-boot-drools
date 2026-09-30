# 业务 Java SDK

在根目录 `mvn install`，业务引入 `com.example.drools:drools.sdk:1.0.0`，导入 `DocumentRuleSdkConfiguration`，配置 `drools.rule-center.url/username/password`。中心执行账号的 Basic 认证仅作用于规则中心 Feign client。

单据执行：`sdk.evaluate(ruleType, data)`；每次按类型读取已提交发布，并使用冻结单据和返回 schema。

完整原生路径：

```java
sdk.refresh("NATIVE_CHECK");
try (NativeRuntimeLease lease = sdk.retainRuntime("NATIVE_CHECK")) {
    KieSession session = lease.base().newKieSession();
    try {
        session.insert(businessFact);
        session.fireAllRules();
    } finally {
        session.dispose();
    }
    // lease.container() 提供 named/stateless session、session pool 等 native API。
}
```

长期对象必须持有 `NativeRuntimeLease` 并在释放 lease 前关闭其子 session/pool/runtime；`kieBase/kieContainer/runtime` 是便捷访问，跨 refresh 持有时使用 lease。

SDK 托管会话：`newSession(type, options)` → `session(id).kieSession()` → `checkpointSession(id)` / `restoreSession(id,type,options)` → `closeSession(id)`。托管会话保留创建时发布代，不随新版本刷新改变。ManagedKieSession 支持 fireUntilHalt/halt，提供 fireFailure 查询。

RuleRuntimeOptions 支持 KieBase/KieSession native options、properties、calendar、Environment、自定义 marshalling 策略和 NativeSessionFactory。native persistence/JPA/JTA 可通过 factory 与 Environment 接入；该项目不替业务建立 JTA/persistence-unit。配置版本必须在恢复时一致。

提供 JDBC snapshot store、租约 coordinator。启用前执行 `src/main/resources/db/kie-session-store.sql`；配置 `drools.session.node-id`（集群唯一）、lease-millis、max-snapshot-bytes。store 和 coordinator 也可替换；不配置 store 时仍可使用完整内存 KIE，不支持跨进程恢复。

序列化恢复依赖相同事实类/KIE/classpath。DocFact/DocItem 已共享且可序列化。任意 global 与外部连接并非 Drools marshaller 自动持久化的事实；应用应在恢复后重新绑定，或选择自定义策略。Worker 的 JSON global sidecar 只是 JSON 可表达场景的实现。
