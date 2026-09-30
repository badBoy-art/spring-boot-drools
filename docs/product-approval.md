# 商品多层审批示例

单据编码：`PRODUCT_SKU`。规则类型编码：`PRODUCT_MULTI_APPROVAL`。规则实例：`PRODUCT_MULTI_APPROVAL_DEFAULT`。

单据包含 `spu` 对象、`sku` 数组（List<Sku>）和 `regionPrice` 数组（List<RegionPrice>）。两个集合位于单据根节点，区域价格通过 `skuCode` 关联 SKU。单据注册保存结构元数据和字段示例；商品业务数据由调用方提供，不在规则中台维护商品主数据表。

## 三个步骤

| 顺序 | 输入作用域 | 规则写入的结果字段 |
| --- | --- | --- |
| 1 | `spu` 属性，DOC 主事实 | `spu.instanceId` |
| 2 | 遍历 `sku`，EACH 每个 SKU 独立执行 | `sku[].instanceId`、`sku[].marginRate` |
| 3 | 遍历 `regionPrice`，EACH 每条区域价格独立执行 | `regionPrice[].instanceId`、`regionPrice[].marginRate`、`regionPrice[].priceDecision` |

步骤按 salience 95、90、85 执行。SKU 和区域价格的每个元素都有独立的事实和输出上下文，并按原始数组顺序组装返回，互不覆盖。输入编码、名称、价格、库存等字段通过 DATA 映射透传；表中列出的结果使用 EXT 映射读取规则计算值。

## 多字段计算与参数

毛利率按指定口径计算：`(salePrice - costPrice) / costPrice`。运营规则参数 `marginThreshold` 默认 `0.3`，在规则配置页可修改并重新发布。

- 毛利率严格大于阈值：`instanceId = "T+2"`。
- 毛利率小于或等于阈值：`instanceId = "T"`。
- 成本价小于或等于 0：`instanceId = "INVALID_COST"`，不返回无效的 `marginRate`。
- 区域销售价不高于市场参考价：`priceDecision = "STANDARD"`；高于参考价：`"REVIEW"`；成本无效：`"INVALID_COST"`。

阈值判断用 BigDecimal 精确比较 `salePrice - costPrice` 与 `costPrice * marginThreshold`，与正成本下的毛利率比较等价。输出毛利率保留 8 位小数、HALF_UP；审批判断使用未舍入值。例如销售价 `0.13`、成本价 `0.10` 恰好等于阈值，返回 T；销售价 `0.130000001` 即使展示毛利率舍入为 0.3，仍返回 T+2。

成本价及销售价需为有效数字，缺失或非数字不能完成该示例计算。`spu` 为必需对象；两个集合可为空数组。

## 初始化资料

- [单据及字段注册数据](samples/product-approval/registration.json)
- [参数、三步规则及完整返回结构配置](samples/product-approval/configuration.json)
- [商品请求样例](samples/product-approval/request.json)
- [实际 HTTP 响应](samples/product-approval/response.json)
- [API 初始化与验收脚本](../scripts/product-approval-init.py)
- [数据库初始化数据 SQL](../drools.center/src/main/resources/db/demo/product-approval-data.sql)

DDL 与示例数据分开维护。新空库先执行 [数据库说明](database.md) 的五个 DDL，再选择 API 初始化或示例数据 SQL 之一。数据 SQL 包含规则参数、规则实例、步骤、步骤输出、返回结构、模板、资源草稿、发布快照、生效指针和发布事件；它用于空项目表，不覆盖已有配置。

API 初始化使用环境变量中现有的 Center 地址及编辑/执行账号，不内置凭据：

```sh
export RULE_CENTER_URL=http://127.0.0.1:8080
# 配置 RULE_CENTER_AUTHOR_USERNAME/PASSWORD 和 RULE_CENTER_RUNTIME_USERNAME/PASSWORD
DROOLS_PRODUCT_INIT=true python3 scripts/product-approval-init.py
```

脚本发现已有相同单据或规则类型时默认拒绝覆盖。仅当本次初始化中断、同类型尚无规则实例且未发布时，可使用 `DROOLS_PRODUCT_RESUME=true` 恢复；已发布类型请通过运营页面修改。初始化验收会发布 0.3、临时改为 0.5 验证参数生效，再恢复 0.3，最终保留三条发布快照和三条发布事件。

## 业务调用

```sh
curl --user "$RULE_CENTER_RUNTIME_USERNAME:$RULE_CENTER_RUNTIME_PASSWORD" \
  -H 'Content-Type: application/json' \
  --data-binary @docs/samples/product-approval/request.json \
  'http://127.0.0.1:8080/rule/evaluate?ruleType=PRODUCT_MULTI_APPROVAL'
```

业务返回结构在响应的 `decision` 中：

```json
{
  "spu": {"spuCode": "SPU-001", "instanceId": "T+2"},
  "sku": [
    {"skuCode": "SKU-001", "instanceId": "T+2", "marginRate": 0.4},
    {"skuCode": "SKU-002", "instanceId": "T", "marginRate": 0.2},
    {"skuCode": "SKU-003", "instanceId": "T", "marginRate": 0.3}
  ],
  "regionPrice": [
    {"skuCode": "SKU-001", "regionCode": "EAST", "instanceId": "T+2", "marginRate": 0.4, "priceDecision": "STANDARD"},
    {"skuCode": "SKU-002", "regionCode": "WEST", "instanceId": "T", "marginRate": 0.2, "priceDecision": "REVIEW"},
    {"skuCode": "SKU-003", "regionCode": "NORTH", "instanceId": "T", "marginRate": 0.3, "priceDecision": "STANDARD"}
  ]
}
```

以上省略输入透传字段；完整结果见实际响应文件。正常样例点火 7 次：SPU 1 次、3 个 SKU 各 1 次、3 条区域价格各 1 次。
