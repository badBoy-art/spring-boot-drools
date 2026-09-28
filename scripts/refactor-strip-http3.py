import io, os
S = "/Users/admin/Projects/spring-boot-drools/src/main/resources/static/"
def load(p): return io.open(S + p, encoding="utf-8").read()
def save(p, s): io.open(S + p, "w", encoding="utf-8").write(s)

# ---------- rule-console.html ----------
p = "rule-console.html"
s = load(p)
def one(old, new, note):
    global s
    if old not in s: print("!! 未命中 %s :: %s" % (note, old[:60].replace("\n", "\\n")))
    else: s = s.replace(old, new, 1); print("ok", note)

one('<a href="#/actions" id="tab-actions">接口注册</a>', '', '导航去掉 接口注册')
one("""    api('rule/doc/tree'), api('rule/http/actions'), api('rule/type/list'),
    api('rule/list'), api('rule/engine/info'), api('rule/refs'),
    api('rule/http/categories'), api('rule/http/domains')
  ]);""",
    """    api('rule/doc/tree'), api('rule/type/list'),
    api('rule/list'), api('rule/engine/info'), api('rule/refs')
  ]);""", 'loadCommon 去掉接口/分类/域名请求')
one("const [d, a, t, r, info, refs, cats, dom] = await Promise.all([",
    "const [d, t, r, info, refs] = await Promise.all([", '解构参数对齐')
one("  DOCS = d; ACTIONS = a; TYPES = t; RULES = r; REFS = refs; CATEGORIES = cats; DOMAINS = dom;",
    "  DOCS = d; TYPES = t; RULES = r; REFS = refs;", '赋值对齐')
one("['docs','actions','types'].forEach", "['docs','types'].forEach", '页签高亮去掉 actions')
# 路由：#/actions 分支
for frag in ["  else if (top === 'actions' && h[1]) view.innerHTML = actionDetail(decodeURIComponent(h[1]));\n",
             "  else if (top === 'actions') { view.innerHTML = actionsList(); fillAuthSelect(); fillCatSelect(''); }\n",
             "  else if (top === 'actions') { view.innerHTML = actionsList(); fillAuthSelect(); }\n"]:
    if frag in s: s = s.replace(frag, ""); print("ok 路由去掉 actions 分支")
# 步骤编辑器：调接口动作 + CALL 相关 UI
one("const ACTION_KINDS = [['CALL','调接口'],['SET_EXT','写单据字段'],['MARK','打标（true）'],['MSG','只加消息']];",
    "const ACTION_KINDS = [['SET_EXT','写单据字段'],['MARK','打标（true）'],['MSG','只加消息']];", '动作下拉去掉 调接口')
# CALL 的两块 UI 用 false 短路（保留代码但不渲染）
n = s.count("${s.actionType === 'CALL'")
s = s.replace("${s.actionType === 'CALL'", "${false")
print("ok CALL UI 短路 %d 处" % n)
s = s.replace("<button onclick=\"probeStep(${i})\">试算这一步</button>", "")
print("ok 去掉 试算这一步 按钮")
# ② 接口注册 函数块整段删（从注释标记到 ③ 标记）
i = s.find("/* ============ ② 接口注册")
j = s.find("/* ============ ③ 规则类型")
if i >= 0 and j > i:
    s = s[:i] + s[j:]; print("ok 删除 ② 接口注册 函数块")
else:
    print("!! 找不到 ②/③ 注释标记")
save(p, s)

# ---------- rule-admin.html：删掉 ② 接口注册 段 ----------
p = "rule-admin.html"
s = load(p)
i = s.find("/* ================= ② 接口注册")
j = s.find("/* ================= ③", i) if i >= 0 else -1
if i >= 0 and j > i:
    s = s[:i] + s[j:]; print("ok rule-admin 删除 ② 接口注册 段")
else:
    print("!! rule-admin 未找到 ② 段")
s = s.replace("技术侧契约（单据注册 / 接口注册 / 域名 / 密钥引用 / 加解密算法）在「接入配置台」，由研发维护 —— 那边的改动会影响本页所有规则，运营只碰这里的取值。",
              "单据注册（单据 → 对象 → 字段，含派生表达式）在「接入配置台」，由研发维护；运营只碰本页的规则取值。引擎只输出决策，外部接口调用由业务系统自行处理。")
save(p, s)
print("done")
