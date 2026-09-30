# 本轮验证结果

验证日期：2026-09-30。基线 Java8 / Boot2.7.18 / Drools7.73.0.Final。

最终完整 reactor `mvn clean install` 成功，37 项测试全部通过，失败0、错误0；产物及 SDK 已安装到本地 Maven 仓库。本轮实际 MySQL 使用独立 mysql:8.4 容器与随机测试库，未读写用户业务库。

| 模块 | 数量 | 主要内容 |
| --- | ---: | --- |
| runtime | 9 | 跨文件/query/TMS、DSL/DSLR、CSV与Excel决策表、DMN/FEEL、PMML、KJAR、命名session/executable model、规则包Java类 |
| sdk | 14 | 原生会话、timer/active、序列化恢复、运行选项指纹、生命周期钩子、租约与fencing、DocFact/DocItem恢复、JDBC自动配置有/无数据源 |
| center | 12 | 运营生成器与范围、原生发布、类型隔离、在途版本保留、失败事务回滚、版本冲突、回滚、新发布KJAR归档、Basic/CSRF |
| worker | 2 | 完整命令服务与多owner恢复、named entry point更新删除、幂等、JSON globals恢复、HTTP认证 |

额外验收：

- 实际 CREATE 脚本在 MySQL8.4 建表成功。
- 独立 Center + Worker 的真实 HTTP：单据/对象/字段注册、原生类型注册、返回树、资源发布、单据执行、修订冲突、历史回滚、原生声明事实与查询、运营页面获取，以及 Worker Feign认证、创建会话、幂等重试、named entry point更新/删除、query、checkpoint、关闭。
- 强制结束 Worker 进程后，以不同 node ID 启动新进程：租约接管、自动恢复事实及 entry point、继续 fire/query 成功。该验收只有本轮启动的测试进程和数据库参与。
- 页面 JavaScript 语法与视图函数运行验证通过。浏览器连接不可用、Computer Use权限未授予，未做实际浏览器视觉及完整点击流程验收。
- shell/Python验收脚本语法、compose配置校验、重复生产Java类检查与 git diff --check 通过。

没有验证：目标生产业务容量和长时间压力、GC长停顿、网络分区、数据库主从故障切换、真实生产备份恢复、公司SSO、多租户不可信代码隔离。容器入口配置已提供且解析通过，本轮没有构建和运行应用镜像。见 [生产部署](production.md) 的上线验收条件。

可重跑的 HTTP 验收入口为 `scripts/platform-smoke.py`，需要明确设置测试环境标志及地址、凭据。本轮测试服务和一次性MySQL容器在完成后关闭；随机测试元数据随容器删除。


## 2026-09-30 用户指定本地 test 库商品审批验收

在 127.0.0.1:3306/test（MySQL5.7.15）按用户明确要求备份、删除并重建20张项目表，保留无关表；information_schema确认20张表、158个字段全部有注释且全部InnoDB/utf8mb4。

通过临时Center真实HTTP注册PRODUCT_SKU及PRODUCT_MULTI_APPROVAL，生成/校验/发布表单DRL。验证SPU赋值1字段、SKU逐项赋值2字段、区域价格逐项赋值3字段、原数组顺序/每行独立输出、0.3精确边界、略高/略低阈值、成本0/负值、空集合和低于成本价格。参数改为0.5再次发布生效，恢复0.3再次发布；负阈值被拒且revision保持3。共3个不可变发布和3个RELEASE事件。实际请求响应及可重放初始化SQL已归档，临时服务已停止。

新增2个商品配置测试与1个真实部署DDL的规则插入默认时间回归，累计40项测试通过（无失败/错误/跳过）。真实MySQL发现并修复RuleDefinitionDao显式NULL时间列插入问题。JavaScript语法及文档链接验证通过；本轮未进行浏览器视觉验收或全生产负载/容灾验证。

## 2026-09-30 冗余清理回归

- 完整 `mvn -o clean install` 通过：40 项测试，0 失败、0 错误、0 跳过；原生跨文件函数、集合逐项输出、旧发布字段兼容、SDK 及 Worker 回归包含在内。
- 删除 5 个旧输出字段后，实际 MySQL test 库保留 20 张表、153 个字段，表/字段注释完整；迁移前私有备份已保存，3 个不可变发布内容与 head 未改变。
- 临时 Center 通过真实 HTTP 读取原发布 revision=3，样例仍命中 7 次；逐项输出、精确阈值、零/负成本、空集合通过。运营配置返回 22 个输出节点及 1/2/3 个步骤赋值；被引用商品单据删除返回 409。临时服务已停止。
- 控制台 JavaScript 语法及原生类型/详情/单据视图渲染检查通过，示例 SQL 的 INSERT 字段与最新 14 张 Center 表结构一致。

清理内容见 [清理记录](cleanup.md)，调用方式与规则属性见 [Facts / DRL 指南](rule-engine-guide.md)。
