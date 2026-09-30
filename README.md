# Drools 规则引擎中台

单据注册 → 规则类型配置 → 运营修改规则 → 校验发布 → 业务按规则类型编码执行。表单配置与原生规则资产共用同一个发布与运行机制。

当前兼容基线：Java 8、Spring Boot 2.7.18、Drools 7.73.0.Final。这里的“完整原生能力”以该 Drools 版本为基线，不表示实现更新版本新增的功能或复刻 Business Central 的所有编辑器。

| 模块 | 职责 |
| --- | --- |
| `drools.contract` | 单据事实及共享字段模型，避免中心和 SDK 各维护一份同名类 |
| `drools.runtime` | 原生 KIE 多资源编译、单据事实构建、冻结返回结构组装；不依赖 Spring 或数据库 |
| `drools.center` | 单据与规则类型注册、运营页面、规则草稿、不可变发布、权限、同步执行 |
| `drools.sdk` | 拉取指定类型发布包，提供完整 KIE API、本地执行、持久化和租约扩展 |
| `drools.worker` | 长生命周期 KieSession 服务、事实操作、CEP/计时器、检查点、恢复、幂等命令 |

模块目录与 `artifactId` 一致，采用 `drools.<职责>` 短名；父工程及所有模块的 `groupId` 统一为 `com.example.drools`。POM `<name>` 不作一致性要求。业务 SDK 新坐标为 `com.example.drools:drools.sdk:1.0.0`。

构建和回归：

```sh
mvn clean verify
# 安装给外部业务项目引用
mvn install
```

部署前，在专用 MySQL 8 数据库执行以下 CREATE 脚本（不包含业务样例数据）：

完整的 20 张表用途、关联、DDL 链接及本地建表记录见 [数据库表说明](docs/database.md)。

[Facts 转换、规则属性和自定义函数](docs/rule-engine-guide.md) 说明业务接入及运营配置如何生成和发布 DRL。

[商品多层审批示例](docs/product-approval.md) 提供 SPU、SKU 列表和区域价格列表的三步规则、阈值参数、初始化数据及真实请求响应。

1. `drools.center/src/main/resources/db/document-registration.sql`
2. `drools.center/src/main/resources/db/rule-type.sql`
3. `drools.center/src/main/resources/db/rule-release.sql`
4. Worker 数据库：`drools.sdk/src/main/resources/db/kie-session-store.sql` 和 `drools.worker/src/main/resources/db/kie-worker-store.sql`

Center 和 Worker 默认连接本地 `jdbc:mysql://127.0.0.1:3306/test`，用户名 `root`，密码使用已指定的本地配置；部署时可以通过各自的数据库环境变量覆盖。中心必须配置 `RULE_CENTER_AUTHOR_USERNAME/PASSWORD`、`RULE_CENTER_RUNTIME_USERNAME/PASSWORD`。编辑账号和执行账号必须不同，没有默认密码。凭据应由部署环境注入；HTTP Basic 需要部署在 TLS 后面。

```sh
java -jar drools.center/target/drools.center-1.0.0.jar
```

运营页面：`/rule-console.html`。编辑写请求需要 CSRF token。执行账号可拉取 `/rule/type/runtime?ruleType=编码`，以及执行下述入口。

```sh
curl --user "$RULE_CENTER_RUNTIME_USERNAME:$RULE_CENTER_RUNTIME_PASSWORD" \
  -H 'Content-Type: application/json' \
  -d '{"bizId":"order-1","amount":100}' \
  'http://127.0.0.1:8080/rule/evaluate?ruleType=ORDER_CHECK'
```

原生能力有独立入口：DRL、多文件函数与声明、DSL/DSLR、Excel/CSV 决策表、DMN/FEEL、PMML、KJAR、Maven 依赖和 executable model。资源由原生 KIE assembler 处理；页面表单的运算符范围不约束原生规则。原生 Java 事实、监听器、live query、rule unit、stateless/named session、JPA/JTA 和自定义 marshaller 等通过 SDK 的 KIE 对象及扩展点使用。HTTP JSON 是便捷接口，不能表达任意 Java 对象或回调。

发布以规则类型为边界，编译完整资源包后同事务写入发布记录与 head。失败不切换版本；回滚生成新修订；并发修改通过 `If-Match` 和发布草稿版本检查。发布冻结单据及返回结构，编辑元数据不会直接改变线上结果。

详见 [架构及能力边界](docs/architecture.md)、[业务接入](docs/business-integration.md)、[生产部署与验收](docs/production.md)、[容器部署入口](deploy/README.md)、[SDK](drools.sdk/README.md)、[Worker](drools.worker/README.md)。

[冗余清理记录](docs/cleanup.md) 列出本次删除的代码、脚本、接口和字段。

[本轮验证结果](docs/verification.md)：40项测试及真实HTTP/进程故障恢复验收通过。当前自动化验收覆盖原生资源、发布失败回滚、类型隔离、旧版本在途保留、鉴权/CSRF、长会话恢复与 named entry point。通过本地验收不等于已完成目标生产环境的容量、容灾和运维验收，具体边界见生产文档。
