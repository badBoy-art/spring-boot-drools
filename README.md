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

## 目录结构

```
src/main/java/com/example/drools/
├── SpringBootDroolsApplication.java
├── entity/                             # RuleDefinition / RuleTypeMeta / RuleTypeField / RuleTemplate / RuleCombinationTable
├── dao/                                # RuleDefinitionDao / RuleTypeMetaDao / RuleTemplateDao / RuleCombinationTableDao (JdbcTemplate)
├── domain/                             # Product / Customer / OrderItem / Order / RuleFact / DocFact
├── http/                               # 规则动作：可配置 HTTP 调用
│   ├── RuleHttpProperties.java         #   域名表 + 密钥库(rule-http.secrets.*) + 超时 + fail-fast
│   ├── HttpActionGateway.java          #   入参渲染 + 认证/签名/加解密串联 + 返回值回填 + 日志
│   ├── HttpAuthSupport.java            #   Bearer/Basic/自定义头/JWT 自签/OAuth2 取 token(带缓存)
│   ├── JwtSupport.java                 #   HS256 签 JWT / 验签（只用 JDK）
│   ├── CryptoSupport.java              #   AES-CBC/GCM 加解密 + HMAC/MD5 签名（算法白名单）
│   └── HttpConfigException.java        #   配置类错误（密钥没配/算法非法）一律抛出，不被 fail-fast 吞
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
    ├── HttpActionController.java       # /rule/http/* 动作配置/试调用/日志
    ├── MockHttpController.java         # /mock/* 演示用的"外部系统"
    └── GlobalExceptionHandler.java     # 校验错误 -> 400 + message

src/main/resources/
├── application.yml
├── static/rule-admin.html               # 管理页面（①~⑥：单据/接口/类型/规则/试算/组合表，零 Excel）
└── db/
    ├── init.sql                        # rule_definition + 10 条种子规则
    ├── meta.sql                        # rule_type_meta / rule_type_field 元数据(11 类型)
    ├── template.sql                    # rule_template 模板体(11 条)
    ├── combination-table.sql           # rule_combination_table（组合规则表，幂等 DDL + 播种）
    ├── http-action.sql                 # 单据注册 + HTTP 动作 + 返回值映射（幂等 DDL + 播种）
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
# 1.2 单据注册 + HTTP 动作 + 返回值映射表（幂等，可重复执行）
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

管理页面：<http://localhost:8080/rule-admin.html>（①单据注册 ②接口注册 ③规则类型 ④规则配置 ⑤试算 ⑥组合规则表 —— 全部页面配置，零 Excel）

## 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /order/demo | 演示用例（VIP 新客 + 新疆 + 电子产品 + 服装） |
| GET | /order/demo-rejected | 库存不足拒单用例 |
| POST | /order/evaluate | 通用订单评估（JSON 入参） |
| GET | /rule/type/meta | 规则类型元数据（前端渲染配置表单） |
| GET | /rule/list | 全部规则 |
| GET | /rule/engine/info | 当前生效规则数 |
| POST | /rule/create | 新增规则（草稿，校验 + 试编译通过才入库） |
| POST | /rule/publish/{id} | 更新参数并发布（校验 + 重生成 DRL + 刷新缓存） |
| POST | /rule/status/{id} | 启用/禁用 `{"status":1}` |
| DELETE | /rule/{id} | 删除 |
| POST | /rule/refresh | 手动重载全部规则 |
| GET | /rule/type/list | 规则类型清单（含参数与模板，③ 页面用） |
| POST | /rule/type/build | 按"单据字段 + 接口"**生成**规则类型（只预览 DRL + 参数定义） |
| POST | /rule/type/save | 保存规则类型（元数据 + 参数 + 模板体落 3 张表） |
| POST | /rule/type/delete | 删除自定义规则类型（内置类型与在用类型会拒） |
| GET | /rule/ct/assets | 组合规则表清单（条件列/动作/行数/版本/状态） |
| GET | /rule/ct/detail | 组合规则表详情（含行数据，页面载入编辑用） |
| POST | /rule/ct/preview | 校验 + 生成 DRL（不落库） |
| POST | /rule/ct/save | 保存组合表定义（不动状态） |
| POST | /rule/ct/publish | 发布生效：校验 → 生成 DRL → 试编译 → 落库 → 重建引擎缓存 |
| POST | /rule/ct/status | 启停组合表（停用即从引擎移除该表展开的规则） |
| POST | /rule/ct/delete | 删除组合表 |
| GET | /rule/doc/list | 单据 + 字段注册清单（中文名 + fieldName） |
| POST | /rule/doc/save | 注册单据 |
| POST | /rule/doc/field/save | 注册/覆盖单据字段 |
| GET | /rule/http/actions | HTTP 动作 + 返回值映射 |
| POST | /rule/http/action/save | 新增/修改动作（域名 key、路径、入参模板、超时） |
| POST | /rule/http/action/{code}/returns | 覆盖返回值映射 |
| POST | /rule/http/action/{code}/test | 试调用（看渲染后的入参 + 回填结果） |
| GET | /rule/http/logs | 调用日志（URL/请求/响应/耗时/成败） |

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

## 审批链路场景（商品毛利率 → 审批级别 → 查审批人 → 发起审批流）

这是"规则引擎做中台"的完整样板：**商品单据和订单单据都接入，规则决定审批级别，审核时按级别调接口查审批人、再调接口发起审批流**。
配置全部在页面（①~④），代码只提供通用网关 —— 没有为商品/订单写任何 Java 类。

### 分层怎么切

```
① 单据注册            商品 SKU / 订单 ORDER（单据 → 对象 → 字段，含"派生字段"表达式）
   │                       毛利率 = (price - cost) * 1.0 / price   ← 口径变化只改这一行配置
   ▼
③ 规则类型            按「单据字段 + 动作」生成类型，页面可见生成的 DRL
   │                       商品：毛利率 > ${marginThreshold} → ext[approvalLevel]=L2（+2 级领导）
   │                       商品：毛利率 <= ${marginThreshold} → ext[approvalLevel]=L1（商品负责人）
   │                       订单：totalAmount >= ${amountThreshold} → L2 / 否则 L1
   ▼
④ 规则配置            填参数（阈值）→ 发布；改阈值不用动代码
   ▼
⑤ 试算 / 业务系统调用  POST /rule/evaluate?docCode=SKU|ORDER  → 规则命中后：
   │                       1) 判定级别写进单据 ext：approvalLevel
   │                       2) 【读接口】按级别查审批人：APPROVER_QUERY → ext[approverId/approverName]
   │                       3) 【写接口】发起审批流：APPROVAL_START（入参带上第 2 步查到的审批人）
   ▼
审计                  rule_http_call_log：查/写两次调用的 URL、入参、响应、耗时、成败
```

平台级的两条动作规则（`FLOW_QUERY_APPROVER` / `FLOW_START_APPROVAL`）**不绑定单据**：任何单据只要 ext 里出现 `approvalLevel` 就会自动走
"查审批人 → 发起审批流"。这就是中台的复用点 —— 新接一个单据，只配 ①③④，动作链路白送。

### 三个关键设计点（都是踩过坑总结的）

1. **顺序靠"链式条件"，不靠规则名/顺序**
   第 2 步要求 `ext["approvalLevel"] != null`，第 3 步要求 `ext["approverId"] != null` —— 数据没到位就不点火，天然串行、无竞争。
   （salience 只是让先后更确定，不承担正确性）
2. **调接口的幂等守卫必须看"尝试过的标记"，不能看"成功回填的字段"**
   反例（实测踩到）：守卫写 `ext["procInstId"] == null`，写接口失败时 procInstId 永远不回填 → 规则反复点火，
   刷出 **594 次调用**直到 `fireAllRules(200)` 上限。正确做法：RHS 先 `ext["startFlowTried"]=true` + `update()`，**再**调接口 → at-most-once。
3. **"查数据 → 写数据"要传参链**
   入参模板支持 `${ext.approverId}`（ext 也进渲染上下文）与 MVEL 表达式，例如
   `"flowKey":"${docCode}_FLOW_${ext.approvalLevel}"` → 商品得到 SKU_FLOW_L2、订单得到 ORDER_FLOW_L2，不用为每种单据配一个接口。

### 实测（scripts/approval-flow-acceptance.sh，34 项断言全过）

```
① 商品 SKU 注册，毛利率是派生字段（"expr":"(price - cost) * 1.0 / price"）
③ 生成 4 个类型：getNumber("毛利率") > ${marginThreshold} → getExt().put("approvalLevel","L2")
④ 发布 4 条规则，引擎规则数 17 → 21
商品毛利率 35% → ext.approvalLevel=L2、approvalLevel written、查回 approverId=U-L2-001（王总 +2 级领导）
              → 发起审批流 flowKey=SKU_FLOW_L2、procInstId=WF-10002、procStatus=RUNNING
商品毛利率 20% → ext.approvalLevel=L1、查回李经理（商品负责人）、flowKey=SKU_FLOW_L1
订单金额 150000 → ext.approvalLevel=L2、approverId=U-L2-001、flowKey=ORDER_FLOW_L2（同一套动作链路复用）
幂等：同一单据只调一次查审批人（grep -c flow/approver == 1）
审计：rule_http_call_log 里 APPROVER_QUERY 与 APPROVAL_START 都在
可逆：脚本删掉 4 条规则 + 4 个类型，引擎回到 17
```

DDL/种子：`src/main/resources/db/approval-flow-demo.sql`（幂等，可重复执行）

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

- `mvn test` → **Tests run: 26, Failures: 0**（DrlGenerator 3 + agenda-group 3 + HTTP 动作 8 + 认证/加解密 10 + DocFact 2）
- 组合规则表 `scripts/combination-table-acceptance.sh` → **PASS=24 FAIL=0**（守门/预览/发布/试算/改值重发/停用/回滚，见下节）
- 页面全链路 `scripts/page-flow-acceptance.sh` → **PASS=21 FAIL=0**（①~⑥：注册单据/接口 → 生成类型 → 配规则 → 试算 → 清理）
- 重复条件：新建与 FULL_REDUCTION 同参数的规则再发布 → 400 `已存在相同条件的已发布规则[FULL_REDUCTION]`
- 页面 `http://localhost:8080/rule-admin.html` 正常加载 15 种规则类型（含自定义）、15 条生效规则、组合规则表与试算

## 单据注册 + HTTP 接口动作（返回值回链）

三个能力，全部数据驱动，Java 只提供通用网关：

### 1) 单据注册（中文名 + fieldName）

`rule_document` / `rule_document_field`：登记"哪种单据接入规则引擎"以及它的字段。
fieldName 支持 `a.b.c` 取值路径，也支持表达式（`items.size()`、`totalAmount * 0.1`），因为运行期就是**用 MVEL 拿它取值**。

```
GET  /rule/doc/list                     单据 + 字段清单（页面 ⑤ 就是这个）
POST /rule/doc/save                     {"docCode":"SKU","docName":"商品"}
POST /rule/doc/field/save                {"docCode":"ORDER","fieldName":"订单金额","fieldKey":"totalAmount","fieldType":"NUMBER"}
```

### 2) 命中规则调 HTTP 接口（域名可配置，入参=常量/属性/表达式）

`rule_http_action` 配接口：`domain_key`（域名走配置，**不写进规则参数**）+ `path` + `method` + `headers` + `body_template` + `timeout`.
`rule_http_action_return` 配返回值映射：响应 JSON 路径 → 回填字段 + 类型 + 是否写消息。

入参模板三种写法（`${}` 里是 MVEL 表达式，可以互相混用）：

| 写法 | 模板片段 | 渲染结果 |
|---|---|---|
| 常量 | `"channel":"规则引擎"` | `"channel":"规则引擎"` |
| 对象属性 | `"bizId":"${orderId}"`、`"level":"${customer.level}"` | `"bizId":"SO-1"`、`"level":"VIP"` |
| 计算值 | `"score":${totalAmount * 0.01}`、`"itemCount":${items.size()}` | `"score":60.0`、`"itemCount":1` |

> 引号内按字符串输出，引号外按 JSON 字面量输出（数字/布尔/数组/对象都可以）。

域名配置在 `application.yml`（各环境不同，真实项目放 Nacos）：

```yaml
rule-http:
  domains:
    risk: https://risk.example.com
    mock: http://localhost:8080/mock
  default-timeout-ms: 2000
  fail-fast: false      # true=接口失败直接抛（下单失败）；false=记失败+写单据消息，规则继续跑
```

```
GET  /rule/http/actions                 动作 + 返回值映射（页面 ⑥）
POST /rule/http/action/save             {"actionCode":"RISK_CHECK","method":"POST","domainKey":"risk","path":"/audit","bodyTemplate":"...","timeoutMs":2000}
POST /rule/http/action/{code}/returns   全量覆盖返回值映射
POST /rule/http/action/{code}/test      试调用（不经过规则，先确认接口通）：body 就是渲染上下文
GET  /rule/http/logs?limit=20           调用日志（URL/请求/响应/耗时/成败）
```

### 3) 返回值回填后继续参与规则

规则模板一行调用网关：`httpActionGateway.invoke("RISK_CHECK", $o);`

网关会：渲染入参 → 发请求（JDK HttpURLConnection，**不引任何 HTTP 客户端、不引业务方 SDK**）→ 按映射把返回值写进 `Order.ext` → 写调用标记 `ext[actionCode]` → 落调用日志。

回填的值立刻能被后续规则当条件用：

```drl
rule "HTTP_RISK_5000"                    // 经典类型 HTTP_ACTION：命中条件 → 调接口
    when $o : Order( rejected == false, ext["RISK_CHECK"] == null, totalAmount >= 10000 )
    then httpActionGateway.invoke("RISK_CHECK", $o); update($o); end
    ↑ 已迁移：风控审核改为步骤链类型 ORDER_RISK_CHECK（门槛是规则参数、8 个入参由规则绑定），
      见 db/risk-check-step.sql 与 scripts/migrate-risk-check-step.sh。经典类型仍由 HTTP_SECURE_RISK_10000 演示。

rule "HTTP_RISK_HIGH_BLOCK"              // 类型 HTTP_RESULT_GUARD：用接口返回值继续判定
    when $o : Order( rejected == false, ext["riskLevel"] == "HIGH" )
    then $o.setRejected(true); update($o); end
```

**`ext["RISK_CHECK"] == null` 这个状态守卫很关键**：`update($o)` 后规则会重新评估，没有守卫就会反复调接口；
有守卫（+ 网关写的调用标记）则同一单据同一动作**只调一次**。测试用例专门断言了"只调一次"。

### 实测（真实跑过，JDK8 + MySQL）

```
下单 6000 → 规则命中 → 调 http://localhost:8080/mock/risk
  被调方收到: {bizId=FLOW-6000, amount=6000.0, level=VIP, region=北京, riskScore=60.0, itemCount=1, channel=规则引擎, ruleConstant=固定常量}
  ext 回填   : {riskLevel=HIGH, riskScore=88, RISK_CHECK=OK}
  后续规则   : ext["riskLevel"]=="HIGH" → 订单被拦截（rejected=true）
  调用日志   : #2 RISK_CHECK OK 13ms  URL/请求/响应全落库
下单 1000 → 未达阈值：不调接口、不拦截，/mock/calls 为空
试调用接口 → 渲染后的入参 + 回填结果 + 耗时（页面 ⑥ 的「试调用」按钮）
```

### 为什么不用 Feign

Feign 需要给每个业务线的接口引它的 SDK / 维护 client 包，接口一多就很难受。
现在只需在 `rule_http_action` 里配一行（域名 key + 路径 + 入参模板 + 返回值映射），
新增接口对 Java 零改动，也不用等对方提供 jar。规则侧永远只有 `httpActionGateway.invoke(...)` 一行。

## 认证（Bearer / JWT / OAuth2 …）与报文加解密、签名

对接第三方接口最常被问的三件事，全部做成**配置项**，密钥**不落库**：

### 认证：`rule_http_auth`（动作通过 auth_code 引用）

| auth_type | 效果 | 需要配什么 |
|---|---|---|
| NONE | 不加认证 | - |
| BEARER | `Authorization: Bearer <token>` | token_ref（密钥引用名） |
| BASIC | `Authorization: Basic base64(user:pass)` | username + password_ref |
| HEADER | 自定义头，如 `X-Api-Key: xxx` | header_name + header_value_ref |
| JWT_HS256 | 用密钥自签 JWT 再 Bearer | jwt_secret_ref + iss/aud/ttl/额外 claims |
| OAUTH2_CC | client_credentials 取 token（**带缓存，过期自动重取**） | token 域名键+路径+clientId+client_secret_ref+scope |

```
GET  /rule/http/auths            认证配置列表（只返回引用名，永不返回密钥）
POST /rule/http/auth/save        {"authCode":"JWT_X","authType":"JWT_HS256","jwtSecretRef":"jwtSecret","jwtIssuer":"rule-engine","jwtAudience":"risk-sys","jwtTtlSeconds":300,"jwtClaimsJson":"{\"tenant\":\"ctp\"}"}
```

### 报文加解密 / 签名（在动作上配）

| 配置 | 可选值 | 说明 |
|---|---|---|
| req_encrypt | AES_CBC / AES_GCM | 请求体整体加密，body 变成 `{"data":"<Base64密文>"}` |
| resp_decrypt | AES_CBC / AES_GCM | 响应解密（兼容 `{"data":"密文"}` 与整段密文两种返回约定） |
| sign_type | HMAC_SHA256 / MD5 / MD5_SALT | 签名对象 = **最终发出的 body**（若开了加密，签的是密文） |
| sign_place | HEADER / BODY | HEADER：`X-Sign`（可用 sign_field 改名）；BODY：插进 JSON 字段（签的是不含签名字段的 body） |

**IV 约定**：配了 `*_iv_ref` → 用固定 IV（兼容对方约定，密文=纯密文的 Base64）；没配 → 随机 IV 并前置到密文（`Base64(iv||密文||tag)`），这是安全默认。

**密钥库**：`rule-http.secrets.<ref>`，取值支持 `hex:` / `base64:` / 明文；库里只存引用名。生产放 Nacos 加密配置 / K8s Secret / 环境变量。

**算法白名单**：算法在代码里枚举（`CryptoSupport`），配置里写 AES_ECB 之类会直接报错 —— 避免有人配出弱算法。

**配置错误不会被静默吞掉**：密钥没配、认证不存在、算法非法 → 抛 `HttpConfigException`（400 + 中文原因），
不受 `rule-http.fail-fast` 影响；只有"网络抖动/接口 5xx"这类运行期失败才由 fail-fast 决定抛还是记日志。

### 实测（真实双向联调，不是描述）

```
试调用 RISK_CHECK_SECURE（JWT + AES-CBC 双向 + HMAC 签名）:
  渲染后的明文入参: {"bizId":"SEC-9001","amount":6000,"level":"VIP","riskScore":60.0}
  被调方记录      : 验签通过(JWT sub=rule-engine) 解密后={"bizId":"SEC-9001","amount":6000,...}
  回填结果        : {riskLevel=HIGH, riskScore=88}   耗时 192ms
下单 12000 → 规则命中 → 走上面这套认证+加密+签名 → ext 回填 → 后续规则按 riskLevel=HIGH 拦截
调用日志: #5 请求体是密文 {"data":"Ddx06cQxneJpqCXisn88..."}，响应落库的是解密后的明文（便于排错）
密钥未配: 400 {"message":"密钥未配置：rule-http.secrets.notExistedKey（库里只存引用名…）"}
测试: 10 个认证/加解密用例全绿（Bearer/Basic/自定义头/JWT 验签/OAuth2 取 token 且缓存只取一次/AES-CBC/AES-GCM/签名入 BODY/密钥缺失）
```

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

`rule_http_action.action_category`：单据接口 / 流程引擎接口 / 风控接口 / 其它 —— 运营按业务场景找接口。
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

## 配置台界面：列表页 → 详情页（新的主入口，`/rule-console.html`）

| 页面 | 地址 |
|---|---|
| 单据列表 | `/rule-console.html#/docs` ｜ 详情 `#/docs/SKU` |
| 接口列表 | `/rule-console.html#/actions` ｜ 详情 `#/actions/APPROVER_QUERY` |
| 规则类型列表 | `/rule-console.html#/types` ｜ 详情 `#/types/CODE` ｜ 新建（步骤编排）`#/types/new` |

约定：**所有「+ 新建」都在列表页右上角**；点列表行进入详情页；详情页里再挂"该对象下的新建"（如单据详情里每个对象的「+ 新建字段」）。

- **单据详情**：单据类型（编码/名称/说明）+ 对象结构（可嵌套/数组）+ 每个对象的字段表（类型、示例值、**计算表达式**=派生指标）+ 被引用情况
- **接口详情**：请求（方法、域名键+路径、超时、请求头、入参模板）｜认证与安全（认证、请求加密、响应解密、签名 + 密钥引用名）｜返回值 → 单据 ext 映射 ｜试调用
- **规则类型详情**：步骤链 + 该类型下的规则（新建规则时**接口从下拉选**）+ 试算（直接看命中/回填/消息）

### 迁移记录：风控审核改成「接口声明参数 + 规则绑值」（方案①）

| 项 | 迁移前 | 迁移后 |
|---|---|---|
| 接口 RISK_CHECK 的入参模板 | `{"bizId":"${orderId}","amount":${totalAmount},...}`（来源写死） | `{"bizId":"${bizId}","amount":${amount},...}`（8 个逻辑参数名） |
| 调它的规则 | 经典类型 `HTTP_ACTION` · `HTTP_RISK_5000`（阈值写死在规则参数里） | 步骤链类型 `ORDER_RISK_CHECK` · `ORDER_RISK_20000`（一步：门槛 + 8 个参数绑定） |
| 报文 | `{bizId=SO-BIG-9001, amount=30000.0, level=VIP, region=华东, riskScore=300.0, itemCount=1, channel=规则引擎, ruleConstant=固定常量}` | **逐字节一致** |

- 迁移做法：`bash scripts/migrate-risk-check-step.sh`（23 项断言）。它会先用一支**对照接口** `RISK_CHECK_CTX`（保持老写法）跑出基线报文，
  迁移后跑同一笔大额订单，逐字段比对；最后清理对照资产。可重复执行。
- 新库种子：`db/risk-check-step.sql`（改模板 + 建类型/字段/步骤链/规则；规则先落成"未发布"，发布一次即可）。
- 经典类型 `HTTP_ACTION` 并没有废弃 —— 它仍由 `HTTP_SECURE_RISK_10000`（认证+加密+签名那条）在演示。

### 参数模板契约：接口写「逻辑参数名」，规则写「参数名 = 上下文取值」

| 位置 | 写法 | 说明 |
|---|---|---|
| ② 接口注册 · 入参模板 | `{"bizId":"${bizId}","amount":${amount},"channel":"${channel}"}` | **只写逻辑参数名**；数字写成裸的（不加引号），字符串加引号。接口侧不关心调用方有什么字段 |
| ③④ 规则 · 步骤「参数绑定」 | `bizId = ${orderId}` / `amount = ${totalAmount}` / `riskScore = ${totalAmount * 0.01}` / `itemCount = ${items.size()}` / `channel = 规则引擎` | 每行一条 `参数名 = 取值`；取值支持 字段路径 / 嵌套路径 / 表达式 / 集合方法 / 常量 |
| 实跑结果（`scripts/param-binding-acceptance.sh` 23 项全过） | `{"bizId":"SO-20260827-0001","amount":5600.0,"level":"VIP","region":"新疆","riskScore":56.0,"itemCount":2,"channel":"规则引擎","ruleConstant":"固定常量"}` | 数字仍是数字（没被变成字符串） |

实现口径：`mergeOverrides` 必须把 `${参数名}` **文本替换**成规则侧表达式文本，再由 `renderTemplate` 用规则上下文求值。
不能"解析 JSON 后按 key 塞值" —— 那样裸写的 `"amount":${amount}` 会变成字符串 `"5600.0"`，数字类型就废了。
`GET /rule/http/action/{code}/params` 会把模板里的 `${参数名}` 抽出来，页面在步骤里直接列给你绑（`${ext.x}` 自动跳过，那是上游回填）。

⚠ **两种写法不能混用**：接口模板写成 `{"bizId":"${orderId}"}`（来源已写死）之后，规则侧再写 `bizId = ${orderId}` 是**静默失效**的 ——
模板里没有 `${bizId}`，绑定不报错也不生效。页面会把"模板里不存在的参数名"标红警示（`uselessBindings()`）。
实测：接口用 `${orderId}`、规则绑 `amount = 999`，被调方收到仍是 `amount=5600.0`，999 被忽略。
只用其中一种：① 接口写逻辑名 + 规则绑值（推荐）；② 接口直接写上下文路径 + 规则不绑。

### 步骤的四个动作（各自打到哪、值从哪来）

| 动作 | 生成的 DRL | 落点 | 取值/参数 |
|---|---|---|---|
| 调接口 CALL | `httpActionGateway.invoke("${stepNAction}", $d [, "覆写入参"])` | 调外部 HTTP，返回值按 ② 的映射回填 ext | 入参 = ② 的模板（常量/单据字段/表达式/上游 `${ext.x}`）；**规则上可覆写同名参数** |
| 写单据字段 SET_EXT | `$d.getExt().put("字段", "取值")` | 单据的 **ext 回填区**（不改原始报文） | 字段从注册字段下拉选（多级对象）；取值可参数化 |
| 打标 MARK | `$d.getExt().put("字段", true)` | ext，布尔 true | 作后续规则/步骤的开关（也是幂等标记的同一机制） |
| 只加消息 MSG | `$d.addRuleMessage("...")` | `DocFact.messages`，随 `/rule/evaluate` 返回 | **不主动外发**；要真发消息就调消息中心接口（CALL）或在服务层消费 messages |

幂等标记按**规则名**隔离（`@RULE@_stepN_done` → 发布时替换成 `SKU_FLOW_3STEP_HIGH_step1_done`）——
否则同单据上两条不同类型的规则会互相把标记占掉，后一条永远不执行（实测踩过）。

### 规则 = 步骤链（"每一步要不要调接口、返回值怎么进后续计算"的答案）

`db/rule-step.sql`（`rule_step` 表）+ `RuleStepBuilder`。一个类型下可以有 N 个有序步骤，每步：

```
条件（可选：单据字段 运算符 取值） → 动作（调接口 / 写单据字段 / 打标 / 只加消息）
```

1. **调哪个接口从接口列表选**（下拉，不用记动作码）；每步还生成一个可配参数 `stepNAction`——同一个类型下的不同规则可以挂不同接口
2. **参数就在规则上下配**：条件取值 → 参数 `stepNValue`；接口 → 参数 `stepNAction`（④ 里填/改）
3. **接口返回值怎么进后续计算**：② 接口注册的返回值映射把响应写进单据 ext（如 `data.approverId → ext[approverId]`）；
   后续步骤只要在条件取值或入参模板里写 `${ext.approverId}` 就能拿到 —— 编辑器会在每一步下面列出"这一步可以引用前面步骤的产物"
4. **生成 DRL 的口径**（`RuleStepBuilder`）：每步一条规则
   - `salience = 100 - 5n`：保证从上到下执行
   - LHS：`本步未执行(ext[stepN_done] == null)` + `本步引用到的上游产物非空(ext[approverId] != null)` + 条件 → **数据没到位就不点火，天然串行**
   - RHS：**先** `ext[stepN_done] = true` + `update()`，**再**调接口 → at-most-once（接口失败也不会反复点火把 `fireAllRules` 上限烧满，这是本项目踩过的坑）
   - 规则名注入 `@RULE@` → 发布时替换成规则名，形如 `SKU_FLOW_3STEP_HIGH#2 查审批人`

实测（`scripts/rule-step-acceptance.sh`，27 项）：商品「判定级别 → 查审批人 → 发起审批流」三步链，
`ext` 依次出现 `approvalLevel=L2` → `approverId=U-L2-001` → `procInstId=WF-10004`，第 3 步入参带上了第 2 步查回的审批人；把第 2 步的接口换成 `PRICE_SYNC` 重新发布即生效。

## 资产复用：一处注册，多处调用（单据 / 接口都是独立资产）

**目标**：新建单据和新建接口各自独立、互不依赖；一个接口注册一次，商品单据和订单单据的规则都能调它；一个单据注册一次，被多套业务逻辑引用。

**怎么落地**（`db/asset-reuse.sql` + `AssetRefDao` + `AssetRefController`）：

1. **两个独立注册入口**：接入配置台顶部两个页签「① 单据注册」「② 接口注册」，互不依赖 —— 建单据不需要先有接口，建接口也不需要先有单据。
2. **接口不再绑定单个单据**：原来 `rule_http_action.doc_code` 是"作用于单据"的单值必填 → 违背复用。
   现在新增 N:N 表 `rule_http_action_scope(action_code, doc_code)`，"适用单据"变成**多选、可空**（空 = 所有单据通用）；
   `doc_code` 降级为"主适用单据（可空）"仅作展示。**调用关系只在"使用点"建立**：③ 规则类型选单据+接口、④ 规则填动作码、⑥ 组合表选接口。
3. **引用看得见**：`GET /rule/refs` 一次返回全部引用关系，页面上两个列表都多一列"被引用"：
   - 单据行：`类型 SKU_PRICE_SYNC`、`规则 SKU_PRICE_SYNC_500`、`接口 PRICE_SYNC`，或"未被引用（可安全删除）"
   - 接口行：`通用（所有单据）` / `仅 SKU` + `单据 SKU`、`类型 …`、`规则 …`
4. **删除护栏**：删除被引用的资产返回 **409** 并说清被谁引用 —— 避免悄悄打断别处的调用链；
   没人引用的资产才允许删除（删单据会连带清掉它的对象/字段）。
5. **注册 → 复用 → 下线 的闭环**：删掉引用它的规则/类型后，引用计数归零，接口就能正常删除。

**实跑证据**（`scripts/asset-reuse-acceptance.sh`，25 项断言全过）：

```
① 新建 PRICE_SYNC（不选适用单据）→ 适用范围 = 通用（所有单据）
② 商品单据建类型 SKU_PRICE_SYNC（绑定 SKU + PRICE_SYNC）→ 规则 SKU_PRICE_SYNC_500
③ 订单单据建类型 ORDER_PRICE_SYNC（绑定 ORDER + **同一个** PRICE_SYNC）→ 规则 ORDER_PRICE_SYNC_100000
④ 真跑：SKU price=800 命中 → 回填真实 syncId；ORDER totalAmount=900000 命中 → 同样回填；调用日志 2 条（同一接口被两个单据各调一次）
⑤ 反查 PRICE_SYNC：单据 [ORDER, SKU] + 类型 2 个 + 规则 2 条，refCount=6
⑥ 反查 SKU：类型/规则/接口都在列
⑦ 护栏：删 PRICE_SYNC → 409「正被引用，不能删除：2 个规则类型[…]；2 条规则[…]」；删 SKU → 409
⑧ 闭环：删规则+类型后 refCount=0 → 再删接口 → 200 成功；引擎规则数回到 17
```

> 说明：引用扫描在控制台量级直接扫文本（规则参数 JSON / DRL / 组合表动作 JSON / 类型模板字面量）。
> 类型与单据的绑定有两种落库方式（`rule_type_field` 的 `docCode` 参数、或模板里的 `docCode == "SKU"` 字面量），两种都认。

## 两个页面（按角色拆分，同一套后端）

| 页面 | 地址 | 给谁用 | 包含 |
|---|---|---|---|
| **接入配置台** | `/rule-admin-dev.html` | 研发 | ① 单据注册（单据/对象/字段/派生表达式）、② 接口注册（域名/入参模板/返回值映射/认证/加解密/签名、试调用、调用日志） |
| **规则运营台** | `/rule-admin.html` | 产品 / 运营 | ③ 规则类型、④ 规则配置、⑤ 试算、⑥ 组合规则表 |

两页顶部互相链跳，共用同一个后端和同一套引擎。

**为什么按角色拆、而不是拆成三页（单据/接口/规则各一页）**：
- 拆分的本质是**变更爆炸半径**不同：①② 是"技术契约"（字段路径、密钥引用、算法白名单改错会让所有规则崩），③④ 是"业务取值"（改错只影响一条规则）；
- ③ 和 ④ 是一条连续工作流（选类型 → 填参数 → 发布），拆成两页会让运营在两个页面之间来回跳，所以合成一页；
- ⑥ 组合规则表、⑤ 试算都是运营自助验证的环节，跟 ③④ 同页最顺。

**"③ 由研发初始化、后续由运营维护"怎么落地**：
- 类型的生成器只允许白名单动作（判定字段/运算符是下拉、动作从枚举里选），**模板体由系统拼装、页面只读** —— 运营改不了代码，只能改取值；要新动作或自定义 DRL 得研发来做（在接入配置台侧）；
- 运营能改的：类型名称/说明、参数默认值、枚举选项、启停；
- 真正的权限控制要接你们自己的 SSO/网关（本工程没做租户与鉴权）；现在是用"物理分页 + 页面职责声明"把边界显性化。

## 页面结构（①~⑥ 顺序与职责）

顺序即"接入一个单据/一条规则"的操作顺序（① ② 在接入配置台，③~⑥ 在规则运营台）：

| 序号 | 模块 | 干什么 | 关键接口 |
|---|---|---|---|
| ① | 单据注册 | 注册**单据对象信息**：单据 → 单据对象(可嵌套) → 对象字段（中文名 + fieldName + 类型 + 说明 + 示例值）。三张表：`rule_document` / `rule_document_object` / `rule_document_field` | `/rule/doc/tree`、`/rule/doc/save`、`/rule/doc/object/save`、`/rule/doc/field/save` |
| ② | 接口注册 | 注册**规则要调的 HTTP 接口**：动作码 / 分类（单据接口·流程引擎接口·风控接口·其它）/ 域名键 + 路径 + 方法 + 入参模板 / 返回值映射 / 认证 / 加解密 / 签名。含**接口列表（按分类）**、认证列表、试调用、调用日志 | `/rule/http/actions`、`/rule/http/action/save`、`/rule/http/action/{code}/returns`、`/rule/http/action/{code}/test`、`/rule/http/auth/save`、`/rule/http/logs` |
| ③ | 规则类型 | 按「① 的对象字段 + ② 的接口」**生成规则类型**（元数据 + 参数 + DRL 模板），页面能看生成的 DRL；保存后 ④ 里立刻可选。内置类型只读，不允许覆盖/删除 | `/rule/type/list`、`/rule/type/build`（预览）、`/rule/type/save`、`/rule/type/delete` |
| ④ | 规则配置 | 在规则类型下**加规则**：选类型 → 填参数（下拉/输入框由类型元数据渲染）→ 新建并发布 → 看生成的 DRL；右侧是规则列表（编辑/启停/删除，改完立即重建缓存） | `/rule/type/meta`、`/rule/create`、`/rule/publish/{id}`、`/rule/status/{id}`、`/rule/{id}` |
| ⑤ | 试算 | 用一单数据跑一遍规则：订单试算（看命中消息 / 金额 / 被拒 / 接口回填 ext）+ **通用单据评估**（任何注册单据，`POST /rule/evaluate?docCode=XXX`） | `/order/evaluate`、`/rule/evaluate` |
| ⑥ | **组合规则表** | 多条件组合在页面上填表格：N 个条件列 + 1 个动作 + N 行组合 → 一次发布生成 N 条规则。**数据存库、引擎直接编译 DRL，全程无 Excel** | `/rule/ct/assets`、`/rule/ct/detail`、`/rule/ct/preview`、`/rule/ct/save`、`/rule/ct/publish`、`/rule/ct/status` |

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

### 实测（scripts/combination-table-acceptance.sh，24 项断言全过）

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
