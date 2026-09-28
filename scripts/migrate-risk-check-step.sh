#!/bin/bash
# 迁移：RISK_CHECK 从「接口模板写上下文路径」→「接口声明逻辑参数名 + 规则绑值」（方案①）
#   可重复执行：基线用一支独立对照接口 RISK_CHECK_CTX（保持老写法），迁移前后比对报文必须逐字段一致
B=http://localhost:8080
J='Content-Type: application/json'
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✅ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ❌ $1"; }
has() { echo "$2" | grep -qF -- "$3" && ok "$1" || bad "$1 （实际：$(echo "$2" | head -c 300)）"; }
post() { curl -s -m 20 -X POST -H "$J" -d "$2" "$B$1"; }

# 一笔合法的大额订单（库存/价格齐全，totalAmount=30000 → 命中 20000 门槛）
BIG='{"orderId":"SO-BIG-9001","customer":{"level":"VIP","region":"华东","age":30,"newCustomer":false},"items":[{"product":{"id":1,"name":"服务器","category":"电子产品","price":15000.0,"stock":10,"onShelf":true},"quantity":2,"subtotal":30000.0}]}'
SMALL='{"orderId":"SO-SMALL-1","customer":{"level":"NORMAL","region":"华东","age":30,"newCustomer":false},"items":[{"product":{"id":2,"name":"T恤","category":"服装","price":5600.0,"stock":10,"onShelf":true},"quantity":1,"subtotal":5600.0}]}'
CTX_TPL='{\"bizId\":\"${orderId}\",\"amount\":${totalAmount},\"level\":\"${customer.level}\",\"region\":\"${customer.region}\",\"riskScore\":${totalAmount * 0.01},\"itemCount\":${items.size()},\"channel\":\"规则引擎\",\"ruleConstant\":\"固定常量\"}'
wired() { curl -s -m 10 $B/mock/calls | grep -o 'risk <- {[^}]*}' | tail -1; }
ruleId() { curl -s -m 10 $B/rule/list | tr '}' '\n' | grep "\"ruleName\":\"$1\"" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2; }

echo "===== 0) 迁移前基线：老写法（接口模板直接写 \${orderId}）===="
# 0.1 对照接口（老写法），同一地址/返回值
post /rule/http/action/save "{\"actionCode\":\"RISK_CHECK_CTX\",\"actionName\":\"风控审核查询(对照-上下文取值)\",\"actionCategory\":\"风控接口\",\"docCode\":\"\",\"method\":\"POST\",\"domainKey\":\"mock\",\"path\":\"/risk\",\"paramIn\":\"AUTO\",\"timeoutMs\":2000,\"headersJson\":\"{\\\"X-Tenant\\\":\\\"demo\\\"}\",\"bodyTemplate\":\"$CTX_TPL\"}" > /dev/null
post /rule/http/action/RISK_CHECK_CTX/returns '[{"respPath":"data.riskLevel","targetField":"riskLevel","targetType":"STRING","asMessage":1,"sortOrder":1},{"respPath":"data.score","targetField":"riskScore","targetType":"NUMBER","asMessage":0,"sortOrder":2}]' > /dev/null
# 0.2 对照规则：经典类型 HTTP_ACTION（老写法规则）
OLD=$(ruleId RISK_BASE_20000); [ -n "$OLD" ] && curl -s -m 10 -X DELETE "$B/rule/$OLD" > /dev/null
R=$(post /rule/create '{"ruleName":"RISK_BASE_20000","ruleType":"HTTP_ACTION","ruleParams":"{\"actionCode\":\"RISK_CHECK_CTX\",\"threshold\":20000}"}')
BASE_ID=$(echo "$R" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
post "/rule/publish/$BASE_ID" '{"actionCode":"RISK_CHECK_CTX","threshold":20000}' > /dev/null
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
post /order/evaluate "$BIG" > /dev/null
BEFORE=$(wired)
echo "  老写法报文：${BEFORE}"
echo "$BEFORE" | grep -q 'amount=30000' && ok "老写法命中并调用（接口自带取值）" || bad "老写法没调到：${BEFORE}"

echo "===== 1) 改 RISK_CHECK 的入参模板 → 逻辑参数名（方案①） ===="
R=$(post /rule/http/action/save '{"actionCode":"RISK_CHECK","actionName":"风控审核查询","actionCategory":"风控接口","docCode":"","method":"POST","domainKey":"mock","path":"/risk","paramIn":"AUTO","timeoutMs":2000,"headersJson":"{\"X-Tenant\":\"demo\"}","bodyTemplate":"{\"bizId\":\"${bizId}\",\"amount\":${amount},\"level\":\"${level}\",\"region\":\"${region}\",\"riskScore\":${riskScore},\"itemCount\":${itemCount},\"channel\":\"${channel}\",\"ruleConstant\":\"${ruleConstant}\"}"}')
has "接口模板已改成逻辑参数名" "$R" '"actionCode":"RISK_CHECK"'
post /rule/http/action/RISK_CHECK/returns '[{"respPath":"data.riskLevel","targetField":"riskLevel","targetType":"STRING","asMessage":1,"sortOrder":1},{"respPath":"data.score","targetField":"riskScore","targetType":"NUMBER","asMessage":0,"sortOrder":2}]' > /dev/null
R=$(curl -s -m 10 $B/rule/http/action/RISK_CHECK/params)
has "接口现在声明了 8 个参数（供规则绑值）" "$R" '"name":"bizId"'
has "  · ruleConstant 也在列" "$R" '"name":"ruleConstant"'

echo "===== 2) 建步骤链类型 ORDER_RISK_CHECK：门槛 + 8 个参数绑定 ===="
STEPS='[{"stepName":"风控审核查询","condField":"totalAmount","condOp":">=","condType":"NUMBER","condValue":"${step1Value}",
 "actionType":"CALL","actionCode":"RISK_CHECK",
 "paramJson":"{\"bizId\":\"${orderId}\",\"amount\":\"${totalAmount}\",\"level\":\"${customer.level}\",\"region\":\"${customer.region}\",\"riskScore\":\"${totalAmount * 0.01}\",\"itemCount\":\"${items.size()}\",\"channel\":\"规则引擎\",\"ruleConstant\":\"固定常量\"}",
 "message":"风控审核查询完成（金额 ${step1Value} 以上）"}]'
BODY="{\"ruleType\":\"ORDER_RISK_CHECK\",\"typeName\":\"订单风控审核（门槛→查风控）\",\"ruleGroup\":\"action\",\"sortOrder\":30,\"factClass\":\"Order\",\"docCode\":\"\",\"steps\":$STEPS}"
R=$(post /rule/type/save "$BODY")
has "类型已保存" "$R" 'ORDER_RISK_CHECK'
R=$(post /rule/type/build "$BODY")
has "DRL：门槛是规则参数（规则里可改）" "$R" 'totalAmount >= ${step1Value}'
has "DRL：8 个参数绑定都在" "$R" 'ruleConstant'
OLD=$(ruleId ORDER_RISK_20000); [ -n "$OLD" ] && curl -s -m 10 -X DELETE "$B/rule/$OLD" > /dev/null
R=$(post /rule/create '{"ruleName":"ORDER_RISK_20000","ruleType":"ORDER_RISK_CHECK","ruleParams":"{\"step1Value\":20000,\"step1Action\":\"RISK_CHECK\"}"}')
NEW_ID=$(echo "$R" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
has "新规则已创建" "$R" '"id":'
post "/rule/publish/$NEW_ID" '{"step1Value":20000,"step1Action":"RISK_CHECK"}' > /dev/null
has "新规则已发布" "$(curl -s -m 10 $B/rule/list)" 'ORDER_RISK_20000'

echo "===== 3) 下掉老写法规则（模板已改逻辑名，它再跑会发 null） ===="
LEGACY=$(ruleId HTTP_RISK_5000)
if [ -n "$LEGACY" ]; then curl -s -m 10 -X DELETE "$B/rule/$LEGACY" > /dev/null; ok "HTTP_RISK_5000 已下掉"; else ok "HTTP_RISK_5000 已不存在（幂等）"; fi

echo "===== 4) 迁移后：同一笔大额订单，报文逐字段比对 ===="
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
post /order/evaluate "$BIG" > /dev/null
AFTER=$(wired)
echo "  新写法报文：${AFTER}"
for kv in 'bizId=SO-BIG-9001' 'amount=30000.0' 'level=VIP' 'region=华东' 'riskScore=300.0' 'itemCount=1' 'channel=规则引擎' 'ruleConstant=固定常量'; do
  has "$(echo "$kv" | cut -d= -f1) ← 规则绑定的值（$kv）" "$AFTER" "$kv"
done
[ -n "$BEFORE" ] && { [ "$BEFORE" = "$AFTER" ] && ok "迁移前后报文逐字节一致" || bad "报文有差异：老[${BEFORE}] 新[${AFTER}]"; }
has "返回值也照旧回填（riskLevel 进 ext）" "$(post /order/evaluate "$BIG")" 'riskLevel'

echo "===== 5) 门槛仍是规则参数（不是写死的） ===="
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
post /order/evaluate "$SMALL" > /dev/null
curl -s -m 10 $B/mock/calls | grep -q 'risk <-' && bad "小额订单不该命中（门槛 20000）" || ok "小额订单不命中（门槛 20000）"
R=$(post "/rule/publish/$NEW_ID" '{"step1Value":1000,"step1Action":"RISK_CHECK"}')
has "把门槛改成 1000 后重新发布" "$R" '"status":1'
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
post /order/evaluate "$SMALL" > /dev/null
has "改门槛后小额订单命中（阈值在规则里配）" "$(curl -s -m 10 $B/mock/calls)" 'amount=5600.0'
post "/rule/publish/$NEW_ID" '{"step1Value":20000,"step1Action":"RISK_CHECK"}' > /dev/null

echo "===== 6) 收尾：对照资产清理（老写法演示不再需要） ===="
curl -s -m 10 -X DELETE "$B/rule/$BASE_ID" > /dev/null
curl -s -m 10 -X DELETE "$B/rule/http/action/RISK_CHECK_CTX" > /dev/null
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
echo "  引擎: $(curl -s -m 10 $B/rule/engine/info)"

echo
echo "===== 结果：PASS=${PASS}  FAIL=${FAIL} ====="
[ "${FAIL}" = "0" ] && echo "全部通过" || echo "有失败项，请看上面 ❌"
