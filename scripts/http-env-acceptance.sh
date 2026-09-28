#!/bin/bash
# 接口注册增强验收：环境域名 / 接口分类 / 参数位置(GET-DELETE走query、POST-PUT走body) / 请求头动态注入 / 参数模板占位符
B=http://localhost:8080
J='Content-Type: application/json'
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✅ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ❌ $1"; }
has() { echo "$2" | grep -qF -- "$3" && ok "$1" || bad "$1 （实际：$(echo "$2" | head -c 300)）"; }
post() { curl -s -m 20 -X POST -H "$J" -d "$2" "$B$1"; }

echo "===== 1) 环境域名：域名键 × 环境，当前环境由 rule-http.env 决定 ====="
R=$(curl -s -m 10 $B/rule/http/domains)
has "返回当前环境" "$R" '"currentEnv":"dev"'
has "mock 有 dev 环境真值" "$R" '"env":"dev","base_url":"http://localhost:8080/mock"'
has "mock 有 test/prod（不同环境可配不同域名）" "$R" '"env":"test"'
R=$(post /rule/http/domain/save '{"domainKey":"echo-test","env":"dev","baseUrl":"http://localhost:8080/mock","remark":"验收脚本"}')
has "新建域名（域名键 + 环境 + 真值）" "$R" '域名已保存'
R=$(post /rule/http/domain/save '{"domainKey":"bad","env":"dev","baseUrl":"api.xxx.com"}')
has "域名必须以 http(s):// 开头 → 拦下" "$R" '要以 http:// 或 https:// 开头'

echo "===== 2) 接口分类：可自助添加，下拉从库里取 ====="
R=$(curl -s -m 10 $B/rule/http/categories)
has "分类列表里有「消息接口」" "$R" '消息接口'
R=$(post /rule/http/category/save '{"categoryName":"验收分类","sortOrder":55}')
has "新增分类成功" "$R" '分类已保存：验收分类'
has "新分类出现在列表" "$(curl -s -m 10 $B/rule/http/categories)" '验收分类'

echo "===== 3) 参数位置：GET 走 query、POST 走 body ====="
post /rule/http/action/save '{"actionCode":"ECHO_GET","actionName":"回显(GET)","actionCategory":"验收分类","docCode":"","method":"GET","domainKey":"echo-test","path":"/echo","headersJson":"{\"X-Rule-Biz\":\"${skuCode}\",\"X-Const\":\"abc\"}","bodyTemplate":"{\"skuCode\":\"${skuCode}\",\"price\":${price}}","timeoutMs":3000}' > /dev/null
post /rule/http/action/ECHO_GET/returns '[{"respPath":"data.skuCode","targetField":"echoSku","targetType":"STRING","asMessage":1,"sortOrder":1}]' > /dev/null
post /rule/http/action/save '{"actionCode":"ECHO_POST","actionName":"回显(POST)","actionCategory":"验收分类","docCode":"","method":"POST","domainKey":"echo-test","path":"/echo","bodyTemplate":"{\"skuCode\":\"${skuCode}\",\"price\":${price}}","timeoutMs":3000}' > /dev/null
post /rule/http/action/save '{"actionCode":"ECHO_GET_BODY","actionName":"回显(GET但参数放body)","actionCategory":"验收分类","docCode":"","method":"GET","domainKey":"echo-test","path":"/echo","paramIn":"BODY","bodyTemplate":"{\"skuCode\":\"${skuCode}\"}","timeoutMs":3000}' > /dev/null
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
R=$(post /rule/http/action/ECHO_GET/test '{"skuCode":"SKU-1","price":88}')
has "GET 调用成功（渲染出参数）" "$R" '"renderedBody":"{\"skuCode\":\"SKU-1\"'
R=$(post /rule/http/action/ECHO_POST/test '{"skuCode":"SKU-2","price":99}')
has "POST 调用成功" "$R" '"actionCode":"ECHO_POST"'
CALLS=$(curl -s -m 10 $B/mock/calls)
has "GET 参数在 URL query 里（走 /echo?skuCode=...）" "$CALLS" 'GET /mock/echo <- query={'
has "POST 参数在请求体里" "$CALLS" 'POST /mock/echo <- body='
LOGS=$(curl -s -m 10 "$B/rule/http/logs?limit=10")
has "调用日志：GET 的 requestUrl 带 ?skuCode=" "$LOGS" 'echo?skuCode=SKU-1'
has "调用日志：POST 的 requestUrl 不带 query" "$(echo "$LOGS" | grep -o 'http://localhost:8080/mock/echo' | head -1)" 'http://localhost:8080/mock/echo'
R=$(post /rule/http/action/ECHO_GET_BODY/test '{"skuCode":"SKU-3"}')
has "GET 也可以显式指定参数放 body" "$R" '"actionCode":"ECHO_GET_BODY"'
CALLS=$(curl -s -m 10 $B/mock/calls)
has "显式 BODY：GET 的请求体里有参数" "$CALLS" 'body={\"skuCode\":\"SKU-3\"}'

echo "===== 4) 请求头：模板化（调用时用规则上下文注入） ====="
has "请求头里的 \${skuCode} 被渲染成真实值" "$CALLS" '[x-rule-biz=SKU-1]'
has "常量请求头照常发送" "$CALLS" '[x-const=abc]'

echo "===== 5) 参数模板占位符：规则里只需要把 ${key} 绑到上下文 ====="
R=$(curl -s -m 10 $B/rule/http/action/ECHO_GET/params)
has "列出接口入参模板的占位符（skuCode / price）" "$R" '"name":"skuCode"'
has "占位符带取值建议" "$R" '"suggest":"${skuCode}"'
has "回显接口的替换逻辑（写成 {\"key\":${value}} 的会被抽出来）" "$R" '"name":"price"'

echo "===== 6) 步骤里绑定参数 + 覆写请求头（_headers 保留键） ====="
TYPE='SKU_ECHO_STEP'
STEPS='[{"stepName":"查价","actionType":"CALL","actionCode":"ECHO_GET",
  "paramJson":"{\"skuCode\":\"${skuCode}\",\"price\":123,\"_headers\":{\"X-From-Rule\":\"${skuCode}\"}}"}]'
BODY="{\"ruleType\":\"$TYPE\",\"typeName\":\"回显步骤演示\",\"ruleGroup\":\"action\",\"sortOrder\":70,\"factClass\":\"DocFact\",\"docCode\":\"SKU\",\"steps\":$STEPS}"
R=$(post /rule/type/build "$BODY")
has "步骤入参绑定到规则上下文（${skuCode}）" "$R" 'skuCode'
has "请求头覆写（_headers）也进了 DRL" "$R" '_headers'
post /rule/type/save "$BODY" > /dev/null
R=$(post /rule/create "{\"ruleName\":\"SKU_ECHO_ONE\",\"ruleType\":\"$TYPE\",\"ruleParams\":\"{\\\"step1Action\\\":\\\"ECHO_GET\\\"}\"}")
ID=$(echo "$R" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
post "/rule/publish/$ID" '{"step1Action":"ECHO_GET"}' > /dev/null
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
R=$(post "/rule/evaluate?docCode=SKU" '{"skuCode":"SKU-RULE-9","price":777}')
has "规则跑通" "$R" '"echoSku"'
has "被调方收到规则上下文的值（skuCode=SKU-RULE-9）" "$(curl -s -m 10 $B/mock/calls)" 'SKU-RULE-9'
has "被调方收到规则覆写的请求头 X-From-Rule" "$(curl -s -m 10 $B/mock/calls)" '[x-from-rule=SKU-RULE-9]'

echo "===== 7) 清理 ====="
curl -s -m 10 -X DELETE "$B/rule/$ID" > /dev/null
post "/rule/type/delete?ruleType=$TYPE" '' > /dev/null
for a in ECHO_GET ECHO_POST ECHO_GET_BODY; do curl -s -m 10 -X DELETE "$B/rule/http/action/$a" > /dev/null; done
mysql -h127.0.0.1 -uroot -pzhaoZ1230 -N -e "delete from test.rule_http_domain where domain_key='echo-test'; delete from test.rule_http_category where category_name in ('验收分类');" 2>/dev/null
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
echo "  引擎: $(curl -s -m 10 $B/rule/engine/info)"

echo
echo "===== 结果：PASS=${PASS}  FAIL=${FAIL} ====="
[ "${FAIL}" = "0" ] && echo "全部通过" || echo "有失败项，请看上面 ❌"
