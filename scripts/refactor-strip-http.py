import io, os, re, sys

ROOT = "/Users/admin/Projects/spring-boot-drools/src/main/java/com/example/drools/"
def edit(path, pairs, required=True):
    p = ROOT + path
    s = io.open(p, encoding="utf-8").read()
    for old, new in pairs:
        if old not in s:
            if required:
                print("!! 未命中 %s :: %s" % (path, old[:60].replace("\n", "\\n")))
            continue
        s = s.replace(old, new)
    io.open(p, "w", encoding="utf-8").write(s)
    print("ok %s" % path)

# ---------- 1) DynamicRuleEngine：去掉 HTTP 网关 global ----------
edit("service/DynamicRuleEngine.java", [
    ("import com.example.drools.http.HttpActionGateway;\n", ""),
    ("    /** 规则 RHS 里要用的动作网关（HTTP 调用）：以 Drools global 的形式注入每个会话 */\n", ""),
    ("                             DrlGenerator drlGenerator, HttpActionGateway httpActionGateway) {",
     "                             DrlGenerator drlGenerator) {"),
    ("        this.httpActionGateway = httpActionGateway;\n", ""),
    ('        session.setGlobal("httpActionGateway", httpActionGateway);\n', ""),
])

# ---------- 2) DrlGenerator：头部不再声明 global ----------
edit("service/DrlGenerator.java", [
    ("            // 模板里直接写 httpActionGateway.invoke(\"RISK_CHECK\", $o); 即可 —— 接口地址/入参/返回值全在库里配。\n", ""),
    ("            \"global com.example.drools.http.HttpActionGateway httpActionGateway;\\n\\n\";\n", ""),
])

# ---------- 3) RuleTypeBuilder：删掉「调接口」模式 ----------
edit("service/RuleTypeBuilder.java", [
    ("                    \"rule_http_action.action_code\", 1));\n", ""),
    ("                    + \"        httpActionGateway.invoke(\\\"${actionCode}\\\", \" + factVar + \");\\n\"\n",
     "                    + \"        \" + factVar + \".getExt().put(\\\"decision\\\", \\\"命中，由业务按规则结果处理\\\");\\n\""),
    ("+ defaultIfEmpty(message,\n                    \"单据[\" + factVar + \"]命中，已调用接口 ${actionCode}\").replace(\"\\\"\", \"'\") + \"\\\");\\n\";",
     "+ defaultIfEmpty(message,\n                    \"单据[\" + factVar + \"]命中（接口调用已移交业务系统处理）\").replace(\"\\\"\", \"'\") + \"\\\");\\n\";"),
    ("                + \"global com.example.drools.http.HttpActionGateway httpActionGateway;\\n\\n\"\n", ""),
])

# ---------- 4) CombinationTableService：去掉 HTTP 动作 ----------
edit("service/CombinationTableService.java", [
    ("import com.example.drools.dao.RuleHttpActionDao;\n", ""),
    ("    private final RuleHttpActionDao actionDao;\n", ""),
    ("                                   RuleHttpActionDao actionDao, DynamicRuleEngine engine) {",
     "                                   DynamicRuleEngine engine) {"),
    ("        this.actionDao = actionDao;\n", ""),
    ("        drl.append(\"global com.example.drools.http.HttpActionGateway httpActionGateway;\\n\\n\");\n", ""),
    ("            case \"HTTP_ACTION\":\n"
     "                lines.add(\"httpActionGateway.invoke(\\\"\" + text(action, \"actionCode\") + \"\\\", \" + fact + \");\");\n"
     "                lines.add(\"update(\" + fact + \");\");\n"
     "                lines.add(fact + (orderFact ? \".addMessage\" : \".addRuleMessage\")\n"
     "                        + \"(\\\"命中组合规则，已调用接口 \" + text(action, \"actionCode\") + \"\\\");\");\n"
     "                break;\n", ""),
])

# ---------- 5) RuleDefinitionService：去掉接口存在性校验 ----------
edit("service/RuleDefinitionService.java", [
    ("import com.example.drools.dao.RuleHttpActionDao;\n", ""),
    ("    private final RuleHttpActionDao httpActionDao;\n", ""),
    ("                                 RuleParamValidator validator, RuleHttpActionDao httpActionDao) {",
     "                                 RuleParamValidator validator) {"),
    ("        this.httpActionDao = httpActionDao;\n", ""),
    ("        validateActionCode(r.getRuleType(), ruleParamsJson); // 引用的接口（含步骤链的 stepNAction）必须在册\n", ""),
])
# 删掉 validateActionCode 方法体（从注释开始到方法结束）
p = ROOT + "service/RuleDefinitionService.java"
s = io.open(p, encoding="utf-8").read()
i = s.find("private void validateActionCode(")
if i >= 0:
    j = s.find("\n    }\n", i)
    # 连同上一行注释一起删
    k = s.rfind("\n", 0, i)
    s = s[:k] + "\n" + s[j + len("\n    }\n"):]
    io.open(p, "w", encoding="utf-8").write(s)
    print("ok RuleDefinitionService.validateActionCode 已删")
else:
    print("！！没找到 validateActionCode")
