import io
B = "/Users/admin/Projects/spring-boot-drools/src/main/java/com/example/drools/"
# 1) AssetRefDao：删掉 actionScope 相关的两个方法
p = B + "dao/AssetRefDao.java"
s = io.open(p, encoding="utf-8").read()
i = s.find("    public Map<String, Object> actionRefs(")
if i < 0: i = s.find("    public List<String> docCodesOfAction(")
j = s.find("\n    /** ", i + 10)
if i >= 0 and j > i:
    s = s[:i] + s[j + 1:]
    print("ok 删 actionRefs/saveScopes 段")
else:
    print("!! 定位失败 i=%s j=%s" % (i, j))
io.open(p, "w", encoding="utf-8").write(s)
print("AssetRefDao 剩余 rule_http:", s.count("rule_http"))

# 2) AssetRefController：删掉 /refs/action 端点
p = B + "controller/AssetRefController.java"
s = io.open(p, encoding="utf-8").read()
i = s.find('    @GetMapping("/refs/action/{actionCode}")')
j = s.find('    @GetMapping("/refs/doc/', i + 10)
if i >= 0 and j > i:
    s = s[:i] + s[j:]
    print("ok 删 /refs/action 端点")
else:
    print("!! 定位失败 i=%s j=%s" % (i, j))
io.open(p, "w", encoding="utf-8").write(s)
print("AssetRefController 剩余 actionRefs:", s.count("actionRefs"))
