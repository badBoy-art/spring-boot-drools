# 单据事实、规则配置与 DRL

## 单据如何成为 Facts

业务通过 `POST /rule/evaluate?ruleType=PRODUCT_MULTI_APPROVAL` 传入商品 JSON，报文包含 `spu` 对象、`sku` 和 `regionPrice` 数组。业务无需传入中台内部 DocFact 类。

中心按 ruleType 找到生效发布包，然后按该包冻结的单据对象和字段配置执行：

1. `FrozenDocumentRuntime.insert` 复制请求数据，创建一个 `DocFact`。SPU 属性通过 `get("spu.salePrice")` 等路径读取。
2. `DocumentFactBuilder.buildItems` 按集合对象的 `valuePath` 获取数组，逐项创建 `DocItem`，保留 `collection` 和 `index`。
3. 将主事实和所有行事实分别 `session.insert(...)`，执行原生 `fireAllRules()`。
4. `DocumentOutputAssembler` 按发布时冻结的返回结构读取各事实的 ext，并按原数组顺序组装 decision。

样例包含 3 个 SKU、3 条区域价格，因此会插入 1 个 DocFact 和 6 个 DocItem。单据注册用于提供结构与路径，不会自动生成名为 Sku 的强类型 Java 类；类型转换也不是通过强制转换传入 JSON 完成。

如果业务需要强类型 Java Fact，可以使用 Java SDK 的原生 KieSession 插入业务 POJO，并部署该类所在依赖。原生 HTTP 接口支持 DRL `declare` 的事实类型；Worker 还支持部署并允许映射的 Java Fact 类。这些入口与普通单据接口共用原生 KIE 能力。

## 三个常见规则属性

| 属性 | 含义 | 适用边界 |
| --- | --- | --- |
| `no-loop true` | 当前规则的动作更新事实后，避免该规则因此重新激活 | 不是每个会话只执行一次；别的规则更新事实仍可能使它再匹配 |
| `lock-on-active true` | agenda-group 获得焦点或 ruleflow-group 活跃期间，抑制该规则的新激活，不论更新来自哪条规则 | 组失去焦点/退出活跃后可重新激活；使用不当可能抑制后续步骤 |
| `salience 1` | 设置候选激活的优先级；数值越大优先级越高，默认 0 | 不是执行次数，也不是独立的步骤序号；相同优先级不应依赖书写顺序 |

规则先满足条件才有候选激活。salience 不会使未满足条件的规则执行，也不会跨越 agenda-group 的焦点约束。商品示例采用 95、90、85 区分三步，并使用每个事实独立的完成标记避免重复执行；没有统一强加 no-loop 或 lock-on-active，原生 DRL 可自行配置。

语义来源：[Drools 7.73 官方规则语言文档](https://docs.drools.org/7.73.0.Final/drools-docs/html_single/index.html)。

## 运营页面与规则库

表单模式的流程为：注册单据 → 注册规则类型/参数/步骤/返回结构 → 保存模板 → 创建规则实例并填写参数 → 生成 DRL → 编译完整类型资源包 → 发布不可变快照 → 切换生效版本。

`rule_step` 和 `rule_step_output` 保存编排配置，`rule_template` 保存模板，`rule_definition` 保存规则实例、参数和生成 DRL。`rule_release` / `rule_release_head` 才是线上执行版本依据。修改步骤、返回结构或原生资源草稿后需要重新发布；仅保存配置不会改动旧发布快照。

原生模式可直接编辑 DRL、上传多个 DRL/DSL/决策表/DMN/PMML 等资源或导入 KJAR，不经过表单语法转换。表单是运营便利入口，原生规则语言和 KIE API 保持开放。

## 自定义函数

DRL 可以声明函数，也可以使用 `import function` 导入 Java 静态方法。函数及调用它的规则要在同一个类型资源包中编译，外部 Java 函数所在类必须在运行类路径或已部署依赖中。

```drl
package product.functions;
import java.math.BigDecimal;

function BigDecimal margin(BigDecimal sale, BigDecimal cost) {
    if (cost.signum() <= 0) {
        throw new IllegalArgumentException("cost must be positive");
    }
    return sale.subtract(cost).divide(cost, 8, java.math.RoundingMode.HALF_UP);
}

rule "calculate margin"
when
    $price : BigDecimal()
then
    BigDecimal rate = margin($price, new BigDecimal("100"));
end
```

多个 DRL 文件可在同一个 package 下共用函数；不同 package 使用对应函数导入。页面的原生资源包入口保留这种能力，完整发布时会一起验证。单文件校验只校验提供的 DRL，跨资源依赖以整个类型发布包的编译结果为准。

来源：[Drools 7.73 Functions in DRL](https://docs.drools.org/7.73.0.Final/drools-docs/html_single/index.html)。
