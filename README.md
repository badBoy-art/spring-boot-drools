# spring-boot-drools

Spring Boot + Drools 规则引擎 Demo，实现「运营配置 → 元数据校验 → 模板渲染 → 生成 DRL → 存 MySQL → 动态编译 → 缓存刷新」的完整闭环，覆盖 5 类业务规则（商品/价格/区域/库存/客户）。

**全链路数据驱动**：规则类型、参数字段、DRL 模板全部入库（rule_type_meta / rule_type_field / rule_template 三张表）。**新增一种规则类型不需要改任何 Java 代码**——只要在 3 张表里各插一行，管理后台的表单、后端参数校验、DRL 生成、规则执行全自动跟上。

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 1.8 | 需求指定 |
| Spring Boot | 2.7.18 | 2.x 最后支持 JDK8 的版本 |
| Drools | 7.73.0.Final | 7.x 最后支持 JDK8 的版本 |
| MySQL | 5.7 | 规则/元数据/模板持久化 |
| 持久层 | JdbcTemplate | spring-boot-starter-jdbc，零 ORM 依赖 |

## 核心闭环

```
运营配置参数(JSON)
   │  RuleParamValidator 用 rule_type_field 元数据校验(必填/类型/范围/枚举)
   ▼
DrlGenerator 读 rule_template 模板体，替换 ${paramKey} 占位符
   │  试编译校验(KieBuilder.buildAll 无 ERROR)
   ▼
rule_definition 表(drl_content + version)
   │  DynamicRuleEngine.refresh() 整体编译
   ▼
KieBase 缓存(原子切换 + dispose 旧容器)
   │  newKieSession()
   ▼
订单事实执行规则
```

规则变更（发布/禁用/删除）都会重新编译并原子切换缓存，**无需重启应用**。

## 引擎职责与决策输出（现行架构）

规则引擎**只负责三件事**：单据注册、规则注册、执行并输出决策。**外部接口调用由业务系统自己处理。**

```
业务系统 --(按注册单据结构传参)--> POST /rule/evaluate?docCode=XXX
引擎: 装配事实(含派生表达式 expr) → 命中规则(库里的 DRL) → 输出决策
业务系统 <--(决策: ext 字段 / messages / 结果字段)-- 引擎
业务系统: 读决策 → 自己决定调哪个接口 → 调完把结果作为新的单据字段**再调一次引擎**继续算
```

- 引擎代码里**没有任何** HTTP 客户端、域名解析、认证/加解密/签名、接口配置表和密钥引用（`HttpActionGateway` 等已整体移除）
- 规则的动作只有三种：**写单据字段（ext 决策字段）/ 打标（布尔）/ 只加消息（判定说明）**
- 一键验收：`bash scripts/decision-acceptance.sh`（15 项：引擎健康 → 单据注册 → 规则注册 → 一笔单子进决策出 → 代码/表里已无接口残留 → 组合规则表可用）

## 规则 = 步骤链（多步骤判定）

规则类型可以由多条**步骤**组成，一步 = 条件（可空）+ 动作，动作只有三种：

| 动作 | 落点 | 说明 |
|---|---|---|
| 写单据字段 | 单据 `ext` 决策字段（如 `approvalLevel=L2`） | 业务读响应里的 `ext` 即可拿到决策 |
| 打标 | `ext` 里写布尔 `true` | 给后续步骤/后续规则当开关（也用于幂等标记） |
| 只加消息 | `messages` 列表 | 判定说明，给业务和人工看的，不向外发送 |

每一步的幂等标记按规则名隔离（`<规则名>_stepN_done`），保证接口/节点只被处理一次；
每步执行的顺序由 `salience` 递减保证。**引擎不调任何外部接口** —— 需要外部数据时，由业务系统
先调好自己的接口，把结果作为单据字段传进引擎（或调完后再调一次引擎）。

## 目录结构

```
src/main/java/com/example/drools/
├── SpringBootDroolsApplication.java
├── entity/                             # RuleDefinition / RuleTypeMeta / RuleTypeField / RuleTemplate / RuleCombinationTable
├── dao/                                # RuleDefinitionDao / RuleTypeMetaDao / RuleTemplateDao / RuleCombinationTableDao (JdbcTemplate)
├── domain/                             # Product / Customer / OrderItem / Order / RuleFact / DocFact
├── service/
│   ├── DrlGenerator.java               # 读模板 + 占位符替换 -> DRL（数据驱动）
│   ├── RuleParamValidator.java         # 用元数据校验参数
│   ├── DynamicRuleEngine.java          # DB 编译（规则 DRL + 组合规则表 DRL）+ 缓存 + 刷新 + 点火上限 + 启动容错
│   ├── RuleDefinitionService.java      # 运营 CRUD/发布 + 重复条件拦截 + 动作码校验
│   ├── RuleTypeBuilder.java            # 按"单据字段 + 接口"生成规则类型（元数据 + 参数 + 模板体）
│   ├── CombinationTableService.java    # 组合规则表：校验/生成 DRL/试编译/发布/停用
│   └── OrderRuleService.java           # 订单规则执行
└── controller/
    ├── OrderController.java            # /order/* 演示接口
    ├── RuleController.java             # /rule/* 运营接口
    ├── RuleTypeController.java         # /rule/type/* 规则类型注册与生成
    ├── CombinationTableController.java # /rule/ct/* 组合规则表
    ├── RuleDocumentController.java     # /rule/doc/* 单据与字段注册
    └── GlobalExceptionHandler.java     # 校验错误 -> 400 + message

src/main/resources/
├── application.yml
├── static/rule-admin.html               # 管理页面（①~⑥：单据/接口/类型/规则/试算/组合表，零 Excel）
└── db/
    ├── init.sql                        # rule_definition + 10 条种子规则
    ├── meta.sql                        # rule_type_meta / rule_type_field 元数据(11 类型)
    ├── template.sql                    # rule_template 模板体(11 条)
    ├── combination-table.sql           # rule_combination_table（组合规则表，幂等 DDL + 播种）
    └── http-auth.sql / document-tree.sql  # 认证配置 / 单据对象树（幂等 DDL）
```

## 数据模型

`rule_definition`（规则实例）：

| 字段 | 说明 |
|------|------|
| rule_group | 规则组：product/price/region/inventory/customer |
| rule_type | 规则模板类型 |
| rule_name | 规则名（唯一，同时是 drl 的 rule 名） |
| rule_params | 规则参数 JSON，如 `{"threshold":1000,"reduction":100}` |
| drl_content | 生成的 DRL 全文 |
| status | 0 草稿 / 1 已发布 / 2 已禁用 |
| version | 版本号（每次发布 +1） |

`rule_type_meta`（类型元数据）+ `rule_type_field`（字段元数据）+ `rule_template`（DRL 模板体）——三张表构成可扩展 schema：

- rule_type_meta：类型名、说明、规则组、排序、是否内置
- rule_type_field：字段 key、中文名、类型、必填、默认值、min/max、枚举选项、占位提示
- rule_template：DRL 规则体（when/then），用 `${paramKey}` 占位符引用参数

字段类型（field_type）5 种：

| 类型 | 含义 | 校验规则 |
|------|------|----------|
| STRING | 字符串 | 必填时非空 |
| NUMBER | 整数/数值 | 数字 + min/max 范围 |
| DECIMAL | 0~1 小数（折扣率） | 数字 + [0,1] |
| ENUM | 枚举单选 | 值 ∈ enum_options |
| CSV | 逗号分隔多值列表 | 非空 |

## 占位符约定（rule_template 模板体）

模板体用 `${paramKey}` 引用 rule_params 参数，替换规则：

- NUMBER / DECIMAL / STRING / ENUM：直接替换为原始值（**字符串参数需在模板里自行加引号**，如 `category == "${category}"`）
- CSV：自动转成带引号列表，如 `${regions}` -> `"新疆", "西藏", "内蒙古", "青海"`

## 数据驱动新增规则类型（零 Java 改动）

以 `BIG_ORDER_TAG`（大额订单标记）为例，只需 3 条 SQL：

```sql
-- 1. 类型元数据
INSERT INTO rule_type_meta (rule_type, rule_group, type_name, type_desc, builtin, sort_order)
VALUES ('BIG_ORDER_TAG','price','大额订单标记','订单金额达到阈值时打标提示',0,99);

-- 2. 参数字段定义
INSERT INTO rule_type_field (rule_type, field_key, field_name, field_type, required, default_value, min_value, placeholder, sort_order)
VALUES ('BIG_ORDER_TAG','threshold','金额阈值','NUMBER',1,'5000','0','订单金额达到该值触发',1);

-- 3. DRL 模板体
INSERT INTO rule_template (rule_type, template_body)
VALUES ('BIG_ORDER_TAG',
'    when
        $o : Order( rejected == false, totalAmount >= ${threshold} )
    then
        $o.addMessage("大额订单，需风控审核");');
```

之后即可通过 `/rule/create` + `/rule/publish/{id}` 创建并发布该类型规则，无需改任何 Java 代码。

## 内置规则模板（10 种）

| rule_type | 组 | 参数字段 |
|-----------|-----|----------|
| PRODUCT_OFF_SHELF | 商品 | （固定逻辑） |
| PRODUCT_CATEGORY_MARK | 商品 | category, message |
| FULL_REDUCTION | 价格 | threshold, reduction |
| BATCH_DISCOUNT | 价格 | minQuantity, discountRate |
| VIP_DISCOUNT | 客户 | level(ENUM), discountRate |
| NEW_CUSTOMER_REDUCTION | 客户 | reduction |
| REGION_FREIGHT | 区域 | regions(CSV), fee |
| REGION_BLOCK | 区域 | regions(CSV) |
| STOCK_CHECK | 库存 | （固定逻辑） |
| STOCK_WARNING | 库存 | threshold |

## 运行

```bash
# 1. 初始化数据库（规则表 + 种子规则 + 元数据 + 模板）
mysql -h127.0.0.1 -uroot -pzhaoZ1230 --default-character-set=utf8mb4 test < src/main/resources/db/init.sql
mysql -h127.0.0.1 -uroot -pzhaoZ1230 --default-character-set=utf8mb4 test < src/main/resources/db/meta.sql
mysql -h127.0.0.1 -uroot -pzhaoZ1230 --default-character-set=utf8mb4 test < src/main/resources/db/template.sql
# 1.1 组合规则表（页面版决策表；幂等，可重复执行）
mysql -h127.0.0.1 -uroot -pzhaoZ1230 --default-character-set=utf8mb4 test < src/main/resources/db/combination-table.sql
mysql -h127.0.0.1 -uroot -pzhaoZ1230 --default-character-set=utf8mb4 test < src/main/resources/db/http-action.sql
# 1.3 认证配置表 + 动作的认证/加解密/签名字段（幂等；ALTER 重复执行会报 Duplicate column，可忽略）
mysql -h127.0.0.1 -uroot -pzhaoZ1230 --default-character-set=utf8mb4 test < src/main/resources/db/http-auth.sql
# 1.4 单据对象树（可嵌套/数组）+ 字段说明 + 接口分类（幂等）
mysql -h127.0.0.1 -uroot -pzhaoZ1230 --default-character-set=utf8mb4 test < src/main/resources/db/document-tree.sql

# 2. 编译 + 测试（都是纯单元测试，不连库）
mvn clean test

# 3. 启动
mvn spring-boot:run
```

管理页面：`/rule-console.html`（单据注册 单据→对象→字段 + 规则类型/步骤编排）、`/rule-admin.html`（规则运营台：规则配置 / 试算 / 组合规则表）—— 全部页面配置，零 Excel

## 接口（引擎只做注册 + 执行，不做外部调用）

| 用途 | 端点 |
|---|---|
| 单据注册 | `GET /rule/doc/tree`（单据→对象→字段树）、`POST /rule/doc/save`、`POST /rule/doc/object/save`、`POST /rule/doc/field/save` |
| 规则类型 | `GET /rule/type/list`、`POST /rule/type/build`（预览 DRL）、`POST /rule/type/save` |
| 规则注册 | `POST /rule/create`、`POST /rule/publish/{id}`（改参数重新发布即生效）、`POST /rule/status/{id}`、`GET /rule/list`、`DELETE /rule/{id}` |
| 组合规则表 | `GET /rule/ct/assets`、`POST /rule/ct/preview`、`POST /rule/ct/save`、`POST /rule/ct/publish/{key}`、`POST /rule/ct/disable/{key}` |
| 执行（业务传参） | `POST /rule/evaluate?docCode=XXX`（body = 按注册字段组织的单据参数）→ 返回决策：`ext` 决策字段、`messages` 消息、命中的规则结果 |
| 资产引用反查 | `GET /rule/refs`、`/rule/refs/doc/{code}`（删除护栏据此返回 409 + 引用清单） |
| 引擎状态 | `GET /rule/engine/info`（ruleCount / lastRefreshError / publishedRuleCount） |

## 动态验证示例

```bash
# 改满减阈值 1000 -> 2000，减 100 -> 150，发布后立即生效
curl -X POST http://localhost:8080/rule/publish/3 \
     -H 'Content-Type: application/json' \
     -d '{"threshold":2000,"reduction":150}'
curl http://localhost:8080/order/demo   # discount 从 460 变为 510

# 非法参数被后端拦截(400)
curl -X POST http://localhost:8080/rule/publish/3 \
     -H 'Content-Type: application/json' -d '{"threshold":-5,"reduction":100}'
# -> {"error":"参数/规则校验失败","message":"参数 满减阈值(threshold) 不能小于 0"}
```

## 为什么不用 Excel 决策表（历史结论，已整体移除）

工程里曾经有一条 Excel 决策表通道（从 spring-cloud-eureka 移植：`SpreadsheetCompiler` + `ResourceType.DTABLE` + POI 守门），
已经**整体删除**（代码、表 `rule_decision_table`、poM 依赖 `drools-decisiontables`、页面入口），因为：

- **动作写死**：决策表的动作在模板里固定（`$o.setDiscount(...)`），单元格只能填数值 → **不能调接口**，与本项目"接口全可配置"的主线冲突；
- **单元格 = 可执行 RHS**：允许上传 xlsx 等于允许执行任意 Java，守门再严也不如"页面受控表格"；
- **7.x 拼接器脆**：决策表是"模式行 + 取值模板行"直接拼接，模式行自带括号会拼出非法语法（`Parser returned a null Package`）；
- **多条件组合不必依赖它**：⑥ 组合规则表在页面上填表格，一行组合 = 一条规则，能力更强（可控动作白名单 + 调接口 + 逐格校验 + 报错定位到行列），且**数据在库里、引擎直接编译 DRL**，全程没有 Excel 文件。

顺带记录（当时核实的 Drools 原生事实，避免以后重复纠结）：
- 决策表是 Drools **官方模块**（`org.drools:drools-decisiontables`，支持 XLS/XLSX/CSV；`DecisionTableInputType` = XLS/XLSX/CSV）；
- 编译入口只吃 **字节流**（`SpreadsheetCompiler.compile(InputStream / Resource / 路径, InputType)`），也就是"Excel"只是载体，不是必须落盘的文件；XLS 与 XLSX 在 7.x 走同一个 POI 解析器（`getInputTypeFromDecisionTableInputType` 映射）；10.x 起官方入口换成 `META-INF/services/org.drools.drl.extensions.DecisionTableProvider` SPI（`SpreadsheetCompiler` 类仍在）；
- Drools **不自带运营页面**（KIE Workbench 的 Guided Decision Table 随 Workbench 停更在 7.x），所以页面化本来就得自己做 —— 那就没必要再让 Excel 当载体。

## 引擎加固（同样从 spring-cloud-eureka 移植）

| 加固点 | 说明 |
|---|---|
| 点火上限 | `DynamicRuleEngine.fireAllRules(session)` 上限 200 次并告警。mvel 方言下 RHS 里出现非 setter 调用（如 `addMessage`）时 `update()` 会退化成全量更新，可能让同一条规则反复点火把线程挂死；`OrderRuleService` 已改走这个入口 |
| 多来源并入编译 | `refresh()` 同时加载 `rule_definition`(status=1) 的 DRL 与 `rule_combination_table`(status=1) 的 DRL，一次编译、原子换版、dispose 旧容器 |
| 规则数语义 | `/rule/engine/info` 里 `publishedRuleCount` = 库里 status=1 的单条规则数；`ruleCount` = KieBase 内规则总数（含组合规则表展开的规则） |
| 重复条件拦截 | `publish` 时若存在"同类型 + 同参数"的另一条已发布规则，直接 400 提示改那条，而不是新建重复规则（避免两条 LHS 相同的规则抢点火顺序） |
| 管理页面 | `src/main/resources/static/rule-admin.html`：①~⑥ 全部页面配置（规则表单由元数据渲染、组合表可加列加行） |

## 实测（真实跑过）

- 组合规则表 `scripts/decision-acceptance.sh` → **PASS=24 FAIL=0**（守门/预览/发布/试算/改值重发/停用/回滚，见下节）
- 页面全链路 `scripts/page-flow-acceptance.sh` → **PASS=21 FAIL=0**（①~⑥：注册单据/接口 → 生成类型 → 配规则 → 试算 → 清理）
- 重复条件：新建与 FULL_REDUCTION 同参数的规则再发布 → 400 `已存在相同条件的已发布规则[FULL_REDUCTION]`
- 页面 `http://localhost:8080/rule-admin.html` 正常加载 15 种规则类型（含自定义）、15 条生效规则、组合规则表与试算

## 单据接入中台：单据 → 单据对象(可嵌套) → 字段

接入一个业务单据 = 往 3 张表登记元数据，**Java 一行不改**：

```
rule_document         单据本身（ORDER 订单 / WF 流程实例 / SKU 商品 …）
rule_document_object  单据对象，可嵌套（订单 → 客户 / 订单明细 → 明细商品 / 流程变量）
rule_document_field   对象字段：所属对象 + 中文名 + fieldName 取值路径 + 类型 + 说明 + 示例值
```

fieldName 支持三种写法，运行期由 MVEL 统一求值：
- 对象属性：`totalAmount`、`customer.level`
- 数组对象：`items[0].product.category`（注册时把对象标成 is_collection=1）
- 方法调用：`items.size()`

接口（页面 ⑤ 是渲染好的对象树，点字段即复制 `${fieldName}`）：
```
GET  /rule/doc/tree                 单据 → 对象(嵌套) → 字段 全树
POST /rule/doc/save                 {"docCode":"SKU","docName":"商品"}
POST /rule/doc/object/save          {"docCode":"ORDER","objectKey":"items","objectName":"订单明细","parentObjectKey":"order","isCollection":1,"valuePath":"items[0]"}
POST /rule/doc/field/save           {"docCode":"ORDER","objectKey":"items","fieldName":"明细行数","fieldKey":"items.size()","fieldType":"NUMBER","exampleValue":"2"}
POST /rule/doc/field/delete
```

### 通用单据也能进规则引擎（不写 Java 类）

`DocFact` = Map 载体 + `RuleFact` 实现，任何注册单据都能直接跑规则：

```
POST /rule/evaluate?docCode=WF      body = 注册的那些字段（可嵌套）
→ {"docCode":"WF","bizId":"WF-2024","data":{...},"ext":{接口回填},"messages":[规则过程],"fired":2}
```

规则模板里用它（DOC_HTTP_ACTION / DOC_EXT_GUARD 两种类型已内置）：
```drl
when
    $d : DocFact( docCode == "WF", ext["WORKFLOW_START"] == null, getNumber("variables.amount") >= 10000 )
then
    httpActionGateway.invoke("WORKFLOW_START", $d);
    update($d);
    $d.addRuleMessage("单据[" + $d.getDocCode() + "]命中，已调用接口 WORKFLOW_START");
```
`getNumber/getString/getBool` 三个取值辅助方法专给 Drools 约束用（避免 Integer 与数值字面量比较时类型飘）；`ext["xxx"] == null` 是**状态守卫**，防止 `update()` 后反复调接口。

### 接口分类（页面 ⑥ 按分类分组）

已内置演示：`RISK_CHECK`（风控）、`RISK_CHECK_SECURE`（风控 + JWT + AES 双向 + HMAC）、`WORKFLOW_START`（流程引擎-启动流程）。

### 实测（流程实例单据，全链路）

```
单据树   : 流程实例(WF) → wf / variables / starter 三个对象，7 个字段（含 variables.amount、starter.name）
试调用   : 渲染 {"processKey":"order_approve","bizId":"WF-1001","starter":"张三","amount":12000,"node":"risk_review"}
           回填 {wfProcInstId:WF-51391, wfStatus:RUNNING, wfApproveNode:风控复核}
评估 12000：fired=2，ext={wfProcInstId,wfStatus,wfApproveNode,WORKFLOW_START:OK,marked_wfApproveNode,requires_risk_review:true}
           被调方真实收到 POST /mock/workflow/start（只收到一次）
评估 3000 ：fired=0，不调接口
未注册单据：400 {"message":"单据未注册: NOPE（请先在 ⑤ 单据注册里登记单据/对象/字段）"}
```

### 踩坑记录（这次抓到的真 bug）

**返回值判定规则必须带状态守卫**，否则在 mvel 方言下 `update()` 会让规则反复点火：
第一版 DOC_EXT_GUARD 的 LHS 只有 `ext["wfApproveNode"] == "风控复核"`，RHS 里写 ext + `update($d)`，
结果同一个单据上这条规则点火了 **200 次**（撞满 fireAllRules 上限才停，fired=200、消息刷屏）。
修法：LHS 增加 `ext["marked_${extField}"] == null`，RHS 先写标记再 update → 收敛（fired=2）。
单测里也加了"点火次数 ≤ 3"的断言，防止这类不收敛再溜回来。

## 组合规则表（页面表格配置，零 Excel）

### 定位

- **多条件组合**（"会员等级 × 商品品类 → 折扣率"这类二维以上组合）用它在页面上填表格，一行组合 = 一条规则，整张表一次发布；
- 单条件 + 调接口的规则用 ③④ 的模板通道；两者编译进同一个 KieBase，同时生效；
- **没有 Excel 参与**：页面表格 → 服务端校验 → 生成 DRL 文本存库（`rule_combination_table.drl_content`）→ `DynamicRuleEngine.refresh()` 直接编译 DRL。

数据模型 `rule_combination_table`：`asset_key / fact_class / doc_code / salience / columns_json / action_json / rows_json / drl_content / row_count / version / status`。

### 生成的 DRL 语义（每行一条规则）

```drl
dialect "java"                       // 关键：java 方言避免 mvel 方言 update() 退化成全量更新→反复点火
rule "CT_MEMBER_CATEGORY_RATE_1"
    salience -20
    when
        $o : Order( rejected == false, customer.level == "VIP" )    // 同变量多约束合并进同一模式
        $i : OrderItem( product.category == "电子产品" )             // 明细另起模式（每个 item 触发一次）
    then
        $o.setDiscount($o.getDiscount() + $i.getSubtotal() * 0.05);
        update($o);
        $o.addMessage("折扣率(按明细小计) 0.05");
end
```

- 主事实（Order/DocFact）的多个约束**合并到同一个模式**；`OrderItem`/`Product` 各起一个模式。
- `factClass=DocFact` 时用 `getNumber/getString/getBool("字段路径")` 取数，只允许打标/加消息/调接口（没有 setter）。
- 动作白名单（代码里枚举）：`SET_DISCOUNT_RATE`（按明细小计×折扣率）、`SET_DISCOUNT_AMOUNT`、`SET_SHIPPING_FEE`、`ADD_MESSAGE`、`REJECT`、`HTTP_ACTION`（调 ② 注册的接口，自动带 `ext["<actionCode>"] == null` 守卫防重复调用）。
- 守门：ENUM 必须命中白名单、NUMBER 走数字正则、文本禁止 `"'\\{}();$` 等能闭合字符串的字符，报错带 `row/column`；
- 发布链路：校验 → 生成 DRL → **Drools 试编译** → 入库（版本+1）→ `engine.refresh()` 原子替换。

### 实测（scripts/decision-acceptance.sh，24 项断言全过）

```
守门   : 枚举越界（军火）→ 第1行 商品品类；动作取值 abc / 注入式 0.1); Runtime.exec(...) → 第2行 动作取值（必须是数字）
预览   : 4 行 → 4 条规则；dialect "java"；$o : Order( rejected == false, customer.level == "VIP" )；$i : OrderItem( product.category == "电子产品" )
发布   : 引擎规则数 15 → 19（4 行）
试算   : VIP × 电子产品 1000 元 → 折扣增量正好 50.0（0.05 × 1000），消息里出现动作列标签
改值   : 0.05 → 0.20 重新发布 → 增量变 200.0，规则数仍是 19（无残留旧规则，原子替换）
停用   : 规则数回到 15，试算回到基线；启用 → 19
可逆   : 脚本末尾把组合表恢复成 4 行原值 + 停用状态，可重复执行
```

### 规则类型注册（③）的安全与校验

- 判定**字段和运算符是下拉选出来的**，直接写进模板（不给手打路径的机会）；只有阈值/取值与接口动作码做成运营可填参数。
- 保存前校验：模板里的每个 `${xxx}` 必须有参数定义（否则发布时会报"未替换的占位符"）；单据必须已注册；接口必须已存在；类型编码格式 `[A-Z][A-Z0-9_]{2,31}`。
- 内置类型（`builtin=1`）不允许覆盖或删除；自定义类型下还有规则时不允许删除类型。

### 一键验收（覆盖 ①~⑥ 全链路）

```bash
bash scripts/page-flow-acceptance.sh      # 21 项断言：注册单据/对象/字段 → 注册接口+返回值 → 生成并保存规则类型
                                          # → 建规则+发布 → 通用单据评估（真发 HTTP 并回填）→ 清理回种子状态
```

## 关键设计

- **全链路数据驱动**：规则类型、字段、DRL 模板都在数据库，新增类型零 Java 改动；前后端共用同一份元数据做表单渲染与参数校验。
- **安全（白名单模板）**：DrlGenerator 只做占位符替换，运营只能改参数、不能写任意 RHS 代码；入库前先试编译，语法错误直接拒绝发布。
- **缓存与内存**：整组规则编译成单个 KieBase 缓存，变更时原子切换并 `dispose()` 旧 KieContainer，避免 Metaspace 泄漏。
- **金额汇总在服务层**：规则只负责累加优惠/运费、打标/拒单，最终 `finalAmount = total - discount + fee` 由 OrderRuleService 在 fireAllRules 后计算。
- **拒单生效**：校验类规则（下架/库存不足/境外）用 `salience 100` 先执行，且修改 `rejected` 后必须 `update($o)`，否则引擎不重估其它规则，会导致拒单后仍算优惠。
