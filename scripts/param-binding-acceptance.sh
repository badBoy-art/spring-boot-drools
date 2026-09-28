#!/bin/bash
# 参数模板契约验收：
#   接口注册的入参模板写「逻辑参数名」   {"bizId":"${bizId}","amount":${amount}, ...}
#   规则类型的步骤里只写「参数名 = 规则上下文取值」 bizId = ${orderId} / amount = ${totalAmount} / ...
#   要求：支持 字段路径、嵌套路径、表达式、方法调用、常量；且数字/字符串类型不被改变
B=http://localhost:8080
J='Content-Type: application/json'
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✅ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ❌ $1"; }
has()  { echo "$2" | grep -qF -- "$3" && ok "$1" || bad "$1 （实际：$(echo "$2" | head -c 400)）"; }
hasnt(){ echo "$2" | grep -qF -- "$3" && bad "$1 （不该出现：$3）" || ok "$1"; }
post() { curl -s -m 20 -X POST -H "$J" -d "$2" "$B$1"; }

echo "===== 1) 接口注册：入参模板写逻辑参数名（值不直接写上下文路径） ====="
TPL='{"bizId":"${bizId}","amount":${amount},"level":"${level}","region":"${region}","riskScore":${riskScore},"itemCount":${itemCount},"channel":"${channel}","ruleConstant":"${ruleConstant}"}'
post /rule/http/action/save "{\"actionCode\":\"CONTRACT_ECHO\",\"actionName\":\"契约回显\",\"actionCategory\":\"其它\",\"docCode\":\"\",\"method\":\"POST\",\"domainKey\":\"mock\",\"path\":\"/echo\",\"bodyTemplate\":\"$(echo "$TPL" | sed 's/"/\\"/g')\",\"timeoutMs\":3000,\"paramIn\":\"AUTO\"}" > /dev/null
R=$(curl -s -m 10 $B/rule/http/action/CONTRACT_ECHO/params)
has "模板里的 8 个逻辑参数名被自动抽出来（供规则侧逐个绑值）" "$R" '"name":"bizId"'
has "  · amount" "$R" '"name":"amount"'
has "  · riskScore" "$R" '"name":"riskScore"'
has "  · itemCount" "$R" '"name":"itemCount"'
has "  · ruleConstant（常量也走同一机制）" "$R" '"name":"ruleConstant"'
has "接口自己的模板保持原样（没被规则的值污染）" "$R" '"bodyTemplate":"{\"bizId\":\"${bizId}\"'

echo "===== 2) 规则类型：步骤里只写「参数名 = 规则上下文取值」 ====="
TYPE='ORDER_PARAM_BIND'
STEPS='[{"stepName":"调用契约接口",
  "actionType":"CALL","actionCode":"CONTRACT_ECHO",
  "paramJson":"{\"bizId\":\"${orderId}\",\"amount\":\"${totalAmount}\",\"level\":\"${customer.level}\",\"region\":\"${customer.region}\",\"riskScore\":\"${totalAmount * 0.01}\",\"itemCount\":\"${items.size()}\",\"channel\":\"规则引擎\",\"ruleConstant\":\"固定常量\"}"}]'
BODY="{\"ruleType\":\"$TYPE\",\"typeName\":\"参数绑定演示\",\"ruleGroup\":\"action\",\"sortOrder\":72,\"factClass\":\"Order\",\"docCode\":\"\",\"steps\":$STEPS}"
R=$(post /rule/type/build "$BODY")
has "字段路径取值 bizId = \${orderId}" "$R" 'orderId'
has "嵌套路径取值 level = \${customer.level}" "$R" 'customer.level'
has "表达式取值 riskScore = \${totalAmount * 0.01}" "$R" 'totalAmount * 0.01'
has "方法调用取值 itemCount = \${items.size()}" "$R" 'items.size()'
has "常量取值 channel = 规则引擎" "$R" '规则引擎'
post /rule/type/save "$BODY" > /dev/null
R=$(post /rule/create "{\"ruleName\":\"ORDER_PARAM_ONE\",\"ruleType\":\"$TYPE\",\"ruleParams\":\"{\\\"step1Action\\\":\\\"CONTRACT_ECHO\\\"}\"}")
ID=$(echo "$R" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
has "规则已创建（这一步调哪个接口是规则参数）" "$R" '"id":'
R=$(post "/rule/publish/$ID" '{"step1Action":"CONTRACT_ECHO"}')
has "规则已发布" "$R" '"status":1'

echo "===== 3) 实跑：订单事实 → 接口收到的报文 ====="
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
curl -s -m 15 "$B/order/demo" > /dev/null
CALLS=$(curl -s -m 10 $B/mock/calls)
has "接口被调用" "$CALLS" 'POST /mock/echo <- body='
has "  · bizId ← \${orderId}（字段路径）" "$CALLS" 'bizId\":\"SO-2026'
has "  · amount ← \${totalAmount}（数字，不带引号）" "$CALLS" 'amount\":5600'
hasnt "  · amount 没被变成字符串" "$CALLS" 'amount\":\"5600'
has "  · level ← \${customer.level}（客户对象里的字段）" "$CALLS" 'level\":\"VIP'
has "  · region ← \${customer.region}" "$CALLS" 'region\":\"新疆'
has "  · riskScore ← \${totalAmount * 0.01}（表达式算出 56.0）" "$CALLS" 'riskScore\":56.0'
has "  · itemCount ← \${items.size()}（集合大小=2）" "$CALLS" 'itemCount\":2'
has "  · channel ← 常量「规则引擎」" "$CALLS" 'channel\":\"规则引擎'
has "  · ruleConstant ← 常量「固定常量」" "$CALLS" 'ruleConstant\":\"固定常量'
echo "  实际报文：$(echo "$CALLS" | grep -o 'body={[^[]*' | head -1)"

echo "===== 4) 清理 ====="
curl -s -m 10 -X DELETE "$B/rule/$ID" > /dev/null
post "/rule/type/delete?ruleType=$TYPE" '' > /dev/null
curl -s -m 10 -X DELETE "$B/rule/http/action/CONTRACT_ECHO" > /dev/null
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
echo "  引擎: $(curl -s -m 10 $B/rule/engine/info)"

echo
echo "===== 结果：PASS=${PASS}  FAIL=${FAIL} ====="
[ "${FAIL}" = "0" ] && echo "全部通过" || echo "有失败项，请看上面 ❌"
