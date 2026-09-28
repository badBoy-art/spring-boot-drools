# 业务系统接入规则引擎（对接说明）

规则引擎只做三件事：**单据注册、规则注册、执行并输出决策**。外部接口调用由业务自己处理。

## 一、DRL 是怎么来的（规则配置 → 可执行规则）

```
① 单据注册（页面）        单据 → 对象 → 字段（含派生表达式 expr、决策输出字段标记）
② 规则类型（页面）        步骤链：条件 + 动作（写单据字段）→ 保存时生成 DRL 模板体（带 ${参数}）→ rule_template
③ 规则配置（页面）        新建规则 = 给这条规则填参数 → 「发布」这一步做两件事：
                            DrlGenerator 把 ${参数} 替换成实际值 → 完整 DRL 存 rule_definition.drl_content
                            engine.refresh() 把库里所有已发布 DRL + 组合规则表 DRL 编译进 KieBase（原子替换缓存）
④ 业务调用                 POST /rule/evaluate?docCode=XXX  → 决策
```
**"发布"就是页面上「规则配置」里那条规则的「发布」按钮**（也可以在类型详情页的"该类型下的规则"里点发布）。
发布即生效，不用改 Java、不用重启；页面「预览 DRL / 看 DRL」能看到生成的原文。

## 二、业务怎么调（就两件事）

1. `POST /rule/evaluate?docCode=<单据编码>`，body = 按**注册字段**组织的 JSON（字段名用 fieldKey，派生字段不用传）
2. 读返回里的 **`decision`** 决定业务动作

```bash
curl -X POST 'http://rule-engine:8080/rule/evaluate?docCode=SKU' \
     -H 'Content-Type: application/json' \
     -d '{"bizId":"SKU-9001","skuCode":"SKU-9001","skuName":"羽绒服","price":100,"cost":65}'
```

## 三、入参 / 出参

**入参**：外层就是注册单据的字段结构（可嵌套/数组）
- 字段名 = ①单据注册里的 `fieldKey`
- **派生字段不用传**（如 `毛利率 = (price - cost) / price`，引擎用 MVEL 现算）
- `bizId` 建议传（进事实、进消息、便于排查）

**出参**（真实响应）：

```json
{
  "decision": { "approvalLevel": "L2" },
  "evalId": "6bbbaf77b9fb",
  "evaluatedAt": "2026-09-28T19:25:58",
  "docCode": "SKU",
  "bizId": "SKU-9001",
  "data": { "skuCode": "SKU-9001", "price": 100, "cost": 65, "毛利率": 0.35, "approvalLevel": null },
  "ext": { "SKU_DECISION_L2_step1_done": true, "approvalLevel": "L2" },
  "messages": ["第1步[判级别]写入 approvalLevel=L2"],
  "fired": 1
}
```

| 字段 | 用途 | 业务怎么用 |
|---|---|---|
| **`decision`** | **决策区（契约）**：单据注册里勾了「决策输出」的字段 | **只读这一块**：按 decision 里的值走分支 |
| `messages` | 过程消息（每步写了什么） | 审计留痕 / 排查 |
| `ext` | 决策区全量（含内部幂等标记） | 排错用，别当契约 |
| `data` | 入参回显（含引擎算好的派生字段） | 对账 |
| `fired` | 命中规则条数；`0` = 没有规则命中 | 必须有兜底策略 |
| `evalId` / `evaluatedAt` | 本次评估的追踪号与时间 | 出问题拿这两项找规则侧定位 |

**返回结果在哪儿注册**：`①单据注册 → 单据详情 → 字段表最右一列「决策输出」勾选`（勾上即进 `decision`）。
这列勾出来的字段清单 = 业务方的决策契约，规则里用「写单据字段」动作写这些字段。

## 四、Java 调用示例

见 `docs/samples/RuleEngineClient.java`（纯 JDK，无任何依赖，可直接 javac/java 跑）：

```bash
javac RuleEngineClient.java
java RuleEngineClient                      # 默认 SKU 单据
java RuleEngineClient ORDER '{"orderId":"SO-1","totalAmount":30000}'
# 换引擎地址：java -Drule.engine.base=http://rule-engine:8080 RuleEngineClient
```

Spring 项目里更简单（用你们已有的 RestTemplate/Jackson）：

```java
Map<String, Object> fact = new LinkedHashMap<>();
fact.put("bizId", "SKU-9001");
fact.put("skuCode", "SKU-9001");
fact.put("price", 100);
fact.put("cost", 65);

ResponseEntity<Map> resp = restTemplate.postForEntity(
        "http://rule-engine:8080/rule/evaluate?docCode=SKU", fact, Map.class);
Map<String, Object> decision = (Map<String, Object>) resp.getBody().get("decision");
if ("L2".equals(decision.get("approvalLevel"))) { /* 走 +2 级审批 */ }
```

## 五、生产接入前需要补的（现状说明，别当已有）

- **鉴权**：目前是内网裸接口，没有 token/网关保护
- **超时与降级**：引擎不可用时业务要有兜底（建议：默认放行 + 落一条待人工复核记录）
- **幂等**：同一 `bizId` 重复评估结果一致（规则是幂等的），但业务侧的下游动作要自己保证幂等
- **批量/异步**：目前一次一笔单据，没有批量评估
- **规则版本**：能追溯 `evalId`/`evaluatedAt`，但没有把"参与评估的规则版本号"放进响应
