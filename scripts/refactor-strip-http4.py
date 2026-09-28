import io
P = "/Users/admin/Projects/spring-boot-drools/src/main/resources/static/rule-console.html"
s = io.open(P, encoding="utf-8").read()

def cut(start, end, note):
    global s
    i = s.find(start)
    if i < 0: print("!! 找不到起点", note); return
    j = s.find(end, i)
    if j < 0: print("!! 找不到终点", note); return
    s = s[:i] + s[j:]
    print("ok 删除", note)

cut("/* ============ 单步试算", "function renderSteps() {", "单步试算(调接口版) 函数块")
cut("const PARAM_CACHE = {};", "function extFieldOptions(", "接口参数读取块")
cut("/* 模板里的占位符分类提示", "function renderSteps() {", "模板占位符提示块")
cut("/* ============ 接口动作 ============ */", "/* ============ ", "接口动作 函数块(分类/认证/域名/保存/删除/试调用)")
s = s.replace("  ED.steps.forEach((s, i) => { if (s.actionType === 'CALL') loadActionParams(i, s.actionCode); });\n", "")
s = s.replace("            <div class=\"sub\" id=\"ap-${i}\">正在读取该接口声明的参数…</div>\n", "")
s = s.replace("oninput=\"setStep(${i},'paramText',this.value);loadActionParams(${i},this.closest('.stepcard')?ED.steps[${i}].actionCode:'')\"",
              "oninput=\"setStep(${i},'paramText',this.value)\"")
io.open(P, "w", encoding="utf-8").write(s)
print("剩余 rule/http 引用:", s.count("rule/http"))
print("剩余 ACTIONS 引用:", s.count("ACTIONS"))
