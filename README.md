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
├── entity/                             # RuleDefinition / RuleTypeMeta / RuleTypeField / RuleTemplate
├── dao/                                # RuleDefinitionDao / RuleTypeMetaDao / RuleTemplateDao (JdbcTemplate)
├── domain/                             # Product / Customer / OrderItem / Order
├── service/
│   ├── DrlGenerator.java               # 读模板 + 占位符替换 -> DRL（数据驱动）
│   ├── RuleParamValidator.java         # 用元数据校验参数
│   ├── DynamicRuleEngine.java          # DB 编译 + 缓存 + 刷新
│   ├── RuleDefinitionService.java      # 运营 CRUD/发布
│   └── OrderRuleService.java           # 订单规则执行
└── controller/
    ├── OrderController.java            # /order/* 演示接口
    ├── RuleController.java             # /rule/* 运营接口
    └── GlobalExceptionHandler.java     # 校验错误 -> 400 + message

src/main/resources/
├── application.yml
└── db/
    ├── init.sql                        # rule_definition + 10 条种子规则
    ├── meta.sql                        # rule_type_meta / rule_type_field 元数据(11 类型)
    └── template.sql                    # rule_template 模板体(11 条)
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

# 2. 编译 + 测试（DrlGeneratorTest 为纯单元测试，mock DAO 不连库）
mvn clean test

# 3. 启动
mvn spring-boot:run
```

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

## 关键设计

- **全链路数据驱动**：规则类型、字段、DRL 模板都在数据库，新增类型零 Java 改动；前后端共用同一份元数据做表单渲染与参数校验。
- **安全（白名单模板）**：DrlGenerator 只做占位符替换，运营只能改参数、不能写任意 RHS 代码；入库前先试编译，语法错误直接拒绝发布。
- **缓存与内存**：整组规则编译成单个 KieBase 缓存，变更时原子切换并 `dispose()` 旧 KieContainer，避免 Metaspace 泄漏。
- **金额汇总在服务层**：规则只负责累加优惠/运费、打标/拒单，最终 `finalAmount = total - discount + fee` 由 OrderRuleService 在 fireAllRules 后计算。
- **拒单生效**：校验类规则（下架/库存不足/境外）用 `salience 100` 先执行，且修改 `rejected` 后必须 `update($o)`，否则引擎不重估其它规则，会导致拒单后仍算优惠。
