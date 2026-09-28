import io, re
ROOT = "/Users/admin/Projects/spring-boot-drools/"

# ---------- 1) AssetRefDao：删掉引用已删表 rule_http_action_scope 的两个死方法 ----------
p = ROOT + "src/main/java/com/example/drools/dao/AssetRefDao.java"
s = io.open(p, encoding="utf-8").read()
i = s.find('    /** 接口的适用范围（N:N）：空 = 所有单据通用 */')
j = s.find("    private static String str(Object o) {")
if i >= 0 and j > i:
    s = s[:i] + s[j:]
    io.open(p, "w", encoding="utf-8").write(s)
    print("ok AssetRefDao 删 findScopes/saveScopes；剩余 rule_http 引用:", s.count("rule_http"))
else:
    print("!! AssetRefDao 定位失败", i, j)

# ---------- 2) README：删掉 HTTP/接口注册相关章节，补一段现行架构 ----------
p = ROOT + "README.md"
lines = io.open(p, encoding="utf-8").read().split("\n")
# 按 "## " 二级标题切块（### 归入其所属二级块）
blocks, cur = [], []
for ln in lines:
    if ln.startswith("## "):
        if cur: blocks.append(cur)
        cur = [ln]
    else:
        cur.append(ln)
if cur: blocks.append(cur)

DROP = ["审批链路场景", "单据注册 + HTTP 接口动作", "认证（Bearer", "资产复用", "两个页面",
        "页面结构", "配置台界面", "为什么不用 Feign"]
NEW_API = """## 接口（引擎只做注册 + 执行，不做外部调用）

| 用途 | 端点 |
|---|---|
| 单据注册 | `GET /rule/doc/tree`（单据→对象→字段树）、`POST /rule/doc/save`、`POST /rule/doc/object/save`、`POST /rule/doc/field/save` |
| 规则类型 | `GET /rule/type/list`、`POST /rule/type/build`（预览 DRL）、`POST /rule/type/save` |
| 规则注册 | `POST /rule/create`、`POST /rule/publish/{id}`（改参数重新发布即生效）、`POST /rule/status/{id}`、`GET /rule/list`、`DELETE /rule/{id}` |
| 组合规则表 | `GET /rule/ct/assets`、`POST /rule/ct/preview`、`POST /rule/ct/save`、`POST /rule/ct/publish/{key}`、`POST /rule/ct/disable/{key}` |
| 执行（业务传参） | `POST /rule/evaluate?docCode=XXX`（body = 按注册字段组织的单据参数）→ 返回决策：`ext` 决策字段、`messages` 消息、命中的规则结果 |
| 资产引用反查 | `GET /rule/refs`、`/rule/refs/doc/{code}`（删除护栏据此返回 409 + 引用清单） |
| 引擎状态 | `GET /rule/engine/info`（ruleCount / lastRefreshError / publishedRuleCount） |
"""
NEW_ARCH = """## 引擎职责与决策输出（现行架构）

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
"""
out = []
for b in blocks:
    head = b[0].strip("# ").strip()
    if head == "接口":
        out.append(NEW_API.split("\n"))
        continue
    if any(k in head for k in DROP):
        print("drop 章节:", head)
        continue
    out.append(b)
if not any(blk and blk[0].startswith("## 引擎职责与决策输出") for blk in out):
    # 插到「核心闭环」之后
    idx = next((i for i, blk in enumerate(out) if blk and blk[0].startswith("## 核心闭环")), 0)
    out.insert(idx + 1, NEW_ARCH.split("\n"))
text = "\n".join("\n".join(b) for b in out)
text = text.replace("scripts/combination-table-acceptance.sh", "scripts/decision-acceptance.sh")
io.open(p, "w", encoding="utf-8").write(text)
print("ok README 重写；剩余 rule_http 提及:", text.count("rule_http"))
print("剩余 接口注册 提及:", text.count("接口注册"))
