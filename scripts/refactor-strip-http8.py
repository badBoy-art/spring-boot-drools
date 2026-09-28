import io
p = "/Users/admin/Projects/spring-boot-drools/README.md"
lines = io.open(p, encoding="utf-8").read().split("\n")
STALE = ["RuleHttpProperties.java", "HttpAuthSupport.java", "JwtSupport.java", "CryptoSupport.java",
         "DomainResolver.java", "HttpConfigException.java", "MockHttpController.java", "RuleHttpAction",
         "RuleHttpCallLog", "RuleHttpAuth", "rule_http_action", "rule_http_auth", "rule_http_call_log",
         "http/                               # 规则动作", "可配置 HTTP 调用", "HTTP 动作"]
out = []
for l in lines:
    if any(k in l for k in STALE):
        print("删行:", l[:100]); continue
    out.append(l)
text = "\n".join(out)
text = text.replace(
    "管理页面：<http://localhost:8080/rule-admin.html>（①单据注册 ②接口注册 ③规则类型 ④规则配置 ⑤试算 ⑥组合规则表 —— 全部页面配置，零 Excel）",
    "管理页面：`/rule-console.html`（单据注册 单据→对象→字段 + 规则类型/步骤编排）、`/rule-admin.html`（规则运营台：规则配置 / 试算 / 组合规则表）—— 全部页面配置，零 Excel")
io.open(p, "w", encoding="utf-8").write(text)
print("剩余 http/接口 提及:", text.count("HttpActionGateway") + text.count("/rule/http") + text.count("接口注册"))
for i, l in enumerate(text.split("\n")):
    if "HttpActionGateway" in l or "/rule/http" in l or "接口注册" in l:
        print("  余 %d: %s" % (i + 1, l[:100]))
