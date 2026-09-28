import io, re
ROOT = "/Users/admin/Projects/spring-boot-drools/src/main/java/com/example/drools/"

def load(p): return io.open(ROOT + p, encoding="utf-8").read()
def save(p, s): io.open(ROOT + p, "w", encoding="utf-8").write(s)
def rep(path, pairs):
    s = load(path)
    for old, new in pairs:
        if old not in s: print("!! 未命中 %s :: %s" % (path, old[:70].replace("\n", "\\n")))
        else: s = s.replace(old, new)
    save(path, s); print("ok", path)

def cut(path, start_marker, end_marker, replacement=""):
    s = load(path)
    i = s.find(start_marker)
    if i < 0: print("!! 找不到起点 %s :: %s" % (path, start_marker[:50])); return
    j = s.find(end_marker, i)
    if j < 0: print("!! 找不到终点 %s :: %s" % (path, end_marker[:50])); return
    s = s[:i] + replacement + s[j:]
    save(path, s); print("ok cut", path)

# 1) RuleStepBuilder：删掉「调接口」动作与相关依赖
rep("service/RuleStepBuilder.java", [
    ("import com.example.drools.dao.RuleHttpActionDao;\n", ""),
    ("    private final RuleHttpActionDao actionDao;\n\n    public RuleStepBuilder(RuleHttpActionDao actionDao) {\n        this.actionDao = actionDao;\n    }\n", ""),
    ("                + \"global com.example.drools.http.HttpActionGateway httpActionGateway;\\n\"\n", ""),
])
cut("service/RuleStepBuilder.java",
    '            if ("CALL".equals(actionType)) {',
    "\n            } else if (",
    '            if ("CALL".equals(actionType)) {\n'
    '                throw new IllegalArgumentException("第 " + no + " 步选了「调接口」：接口调用已改由业务系统处理"\n'
    '                        + "（引擎只输出决策：写单据字段 / 打标 / 加消息），请把这一步改成写字段或加消息");\n')
# 删掉 returnFields / checkActionExists 两个方法
s = load("service/RuleStepBuilder.java")
for name in ["public List<String> returnFields(", "private void checkActionExists("]:
    i = s.find(name)
    if i >= 0:
        k = s.rfind("/**", 0, i)
        if k < 0 or i - k > 400: k = s.rfind("\n", 0, i)
        j = s.find("\n    }\n", i)
        s = s[:k] + "\n" + s[j + len("\n    }\n"):]
        print("ok 删方法", name)
    else:
        print("!! 没找到", name)
save("service/RuleStepBuilder.java", s)

# 2) RuleTypeController：删掉单步试算与 HTTP 依赖
rep("controller/RuleTypeController.java", [
    ("import com.example.drools.dao.RuleHttpActionDao;\n", ""),
    ("    private final RuleHttpActionDao actionDao;\n", ""),
    ("    private final com.example.drools.http.HttpActionGateway httpActionGateway;\n", ""),
    ("                              RuleTypeBuilder builder, RuleStepBuilder stepBuilder, RuleStepDao stepDao,\n                              com.example.drools.http.HttpActionGateway httpActionGateway) {",
     "                              RuleTypeBuilder builder, RuleStepBuilder stepBuilder, RuleStepDao stepDao) {"),
    ("        this.actionDao = actionDao;\n", ""),
    ("        this.httpActionGateway = httpActionGateway;\n", ""),
    ("RuleTypeMetaDao metaDao, RuleDocumentDao documentDao, RuleHttpActionDao actionDao,\n",
     "RuleTypeMetaDao metaDao, RuleDocumentDao documentDao,\n"),
    ("&& actionDao.findByCode(actionCode) == null", "&& false"),
])
cut("controller/RuleTypeController.java",
    "    /**\n     * 单步试算",
    "    /** 全部规则类型（含参数定义与模板体，页面列表/编辑用） */")

# 3) AssetRefDao：不再查已删除的接口表
rep("dao/AssetRefDao.java", [
    ('        List<Map<String, Object>> actions = jdbc.queryForList(\n                "SELECT action_code, action_name, IFNULL(doc_code,\'\') doc_code FROM rule_http_action ORDER BY action_code");',
     "        List<Map<String, Object>> actions = new java.util.ArrayList<Map<String, Object>>();"),
    ('        List<Map<String, Object>> scopes = jdbc.queryForList(\n                "SELECT action_code, doc_code FROM rule_http_action_scope");',
     "        List<Map<String, Object>> scopes = new java.util.ArrayList<Map<String, Object>>();"),
])

# 4) AssetRefController：接口相关端点改为明确报错
rep("controller/AssetRefController.java", [
    ('        jdbc.update("DELETE FROM rule_http_action_return WHERE action_code = ?", actionCode);\n'
     '        jdbc.update("DELETE FROM rule_http_action_scope WHERE action_code = ?", actionCode);\n'
     '        jdbc.update("DELETE FROM rule_http_action WHERE action_code = ?", actionCode);',
     '        throw new IllegalStateException("接口注册功能已移除：引擎不再保存外部接口配置（调接口由业务系统处理）");'),
])
