# 冗余清理记录

本轮按实际调用和数据关系清理代码，保留原生 DRL、完整资源包编译、KIE SDK、Worker 会话、发布版本和回滚功能。

- 删除无运行用途的 `DynamicRuleEngine`、`DocFactBuilder`、`ReturnStructureService`；DRL 校验独立为 `DrlValidator`。删除无效的刷新/引擎信息接口，页面统计直接读取规则列表。
- 事实构建、表达式处理和返回组装归入 `drools.runtime` 的 `runtime.document` 包；Center 不再保留依赖数据库的重复封装。
- 删除旧单字段输出及扁平输出 CSV 的实体、DAO、API、页面路径和 5 个数据库字段。多字段赋值统一使用 `rule_step_output`，返回树统一使用 `rule_output_field`。
- 删除临时 DRL 探针及四份重叠旧验收脚本；保留 `accept-all.sh`、`platform-smoke.py`、`product-approval-init.py`。测试专用 schema 仍保留。
- 单据引用查询改为规则类型实际绑定关系，原生规则类型也能阻止误删其单据。

20 张表各有独立用途，没有删除发布事件、不可变快照、会话持久化或 Worker 幂等记录。旧版本快照仍按原内容保存；运行时读取旧单据配置时兼容已删除的作者字段。

完整 reactor `mvn -o clean install` 通过，共 40 项测试；覆盖集合逐项输出、旧发布字段兼容、原生多文件函数及资源包、SDK 和 Worker。真实 HTTP 确认旧 revision=3 仍执行、输出节点 22 个、步骤赋值 1/2/3 个，商品单据引用删除保护返回 409。控制台语法及视图渲染通过。数据库迁移及验证见 [表说明](database.md)，Facts 和 DRL 答疑见 [规则指南](rule-engine-guide.md)。

剩余维护边界：Center 的运营单据入口继续使用原有 `com.example.drools.service/dao/controller` 包，发布管理使用 `center.*` 包。二者职责不同，本轮不为统一命名大面积迁移兼容接口；HTTP JSON 与原生 Java KIE 接口的能力表达边界见 [架构](architecture.md)。
