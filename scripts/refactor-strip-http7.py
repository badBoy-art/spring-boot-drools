import io
p = "/Users/admin/Projects/spring-boot-drools/README.md"
lines = io.open(p, encoding="utf-8").read().split("\n")
blocks, cur = [], []
for ln in lines:
    if ln.startswith("## "):
        if cur: blocks.append(cur)
        cur = [ln]
    else:
        cur.append(ln)
if cur: blocks.append(cur)

DROP = ["迁移记录", "参数模板契约", "步骤的四个动作", "规则 = 步骤链"]
STEP = """## 规则 = 步骤链（多步骤判定）

规则类型可以由多条**步骤**组成，一步 = 条件（可空）+ 动作，动作只有三种：

| 动作 | 落点 | 说明 |
|---|---|---|
| 写单据字段 | 单据 `ext` 决策字段（如 `approvalLevel=L2`） | 业务读响应里的 `ext` 即可拿到决策 |
| 打标 | `ext` 里写布尔 `true` | 给后续步骤/后续规则当开关（也用于幂等标记） |
| 只加消息 | `messages` 列表 | 判定说明，给业务和人工看的，不向外发送 |

每一步的幂等标记按规则名隔离（`<规则名>_stepN_done`），保证接口/节点只被处理一次；
每步执行的顺序由 `salience` 递减保证。**引擎不调任何外部接口** —— 需要外部数据时，由业务系统
先调好自己的接口，把结果作为单据字段传进引擎（或调完后再调一次引擎）。
"""
out = []
for b in blocks:
    head = b[0].strip("# ").strip()
    if any(k in head for k in DROP):
        print("drop:", head)
        continue
    out.append(b)
idx = next((i for i, blk in enumerate(out) if blk and blk[0].startswith("## 引擎职责与决策输出")), len(out) - 1)
out.insert(idx + 1, STEP.split("\n"))
text = "\n".join("\n".join(b) for b in out)
# 清掉数据模型/目录结构里对已删表与包的提及
text = "\n".join(l for l in text.split("\n") if "rule_http_" not in l)
text = text.replace("| `http/` | HTTP 动作网关（域名/入参模板/认证/加解密/签名/调用日志） |\n", "")
text = text.replace("`rule_http_action` / `rule_http_auth` 等", "")
io.open(p, "w", encoding="utf-8").write(text)
print("剩余 HttpActionGateway:", text.count("HttpActionGateway"), "｜rule/http:", text.count("/rule/http"), "｜接口注册:", text.count("接口注册"))
for i, l in enumerate(text.split("\n")):
    if "HttpActionGateway" in l or "/rule/http" in l or "接口注册" in l:
        print("  残留 %d: %s" % (i + 1, l[:110]))
