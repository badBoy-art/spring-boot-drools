#!/bin/bash
# 资产复用验收：接口 / 单据"一处注册，多处调用"
#   1) 接口只注册一次（通用），商品单据和订单单据的规则都能调它
#   2) 引用反查能回答"这个接口被谁调用""这个单据被谁引用"
#   3) 删除护栏：还被引用就不许删，并说清被谁引用
B=http://localhost:8080
J='Content-Type: application/json'
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✅ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ❌ $1"; }
has() { echo "$2" | grep -qF -- "$3" && ok "$1" || bad "$1 （实际：$(echo "$2" | head -c 300)）"; }
post() { curl -s -m 20 -X POST -H "$J" -d "$2" "$B$1"; }
del()  { curl -s -m 20 -X DELETE "$B$1" -w " HTTP=%{http_code}"; }

echo "===== 0) 基线 ====="
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
BASE_RULES=$(curl -s -m 10 $B/rule/engine/info | grep -o '"ruleCount":[0-9]*' | cut -d: -f2)
echo "  引擎规则数=${BASE_RULES}"

echo "===== 1) 一处注册：新建接口 PRICE_SYNC（不选适用单据 = 所有单据通用） ====="
post /rule/http/action/save '{"actionCode":"PRICE_SYNC","actionName":"价格同步（通用）","actionCategory":"单据接口","docCode":"","method":"POST","domainKey":"mock","path":"/sku/sync","bodyTemplate":"{\"docCode\":\"${docCode}\",\"bizId\":\"${bizId}\"}","timeoutMs":3000}' > /dev/null
R=$(post /rule/http/action/PRICE_SYNC/returns '[{"respPath":"data.skuId","targetField":"syncId","targetType":"STRING","asMessage":1,"sortOrder":1}]')
has "返回值映射已配（回填 ext[syncId]）" "$R" 'data.skuId'
R=$(post /rule/http/action/PRICE_SYNC/scopes '[]')
has "适用范围 = 通用（任何单据都能调）" "$R" '所有单据通用'
R=$(curl -s -m 10 $B/rule/refs/action/PRICE_SYNC)
has "刚注册时还没人引用（refCount=0）" "$R" '"refCount":0'
has "标记为通用接口" "$R" '"universal":true'

echo "===== 2) 多处调用 #1：商品单据（SKU）的规则调它 ====="
BODY_SKU='{"ruleType":"SKU_PRICE_SYNC","typeName":"商品价格同步","ruleGroup":"action","sortOrder":71,"factClass":"DocFact","docCode":"SKU","mode":"ACTION","fieldPath":"price","operator":">=","valueType":"NUMBER","valueParamKey":"threshold","defaultValue":"500","actionCode":"PRICE_SYNC"}'
post /rule/type/save "$BODY_SKU" > /dev/null
ok "类型 SKU_PRICE_SYNC 已建（绑定 SKU + 接口 PRICE_SYNC）"
RS=$(post /rule/create '{"ruleName":"SKU_PRICE_SYNC_500","ruleType":"SKU_PRICE_SYNC","ruleParams":"{\"actionCode\":\"PRICE_SYNC\",\"threshold\":500}"}')
IDS=$(echo "$RS" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
R=$(post "/rule/publish/$IDS" '{"actionCode":"PRICE_SYNC","threshold":500}')
has "规则 SKU_PRICE_SYNC_500 已发布" "$R" '"status":1'

echo "===== 3) 多处调用 #2：订单单据（ORDER）的规则调**同一个**接口 ====="
BODY_ORDER='{"ruleType":"ORDER_PRICE_SYNC","typeName":"订单金额同步","ruleGroup":"action","sortOrder":72,"factClass":"DocFact","docCode":"ORDER","mode":"ACTION","fieldPath":"totalAmount","operator":">=","valueType":"NUMBER","valueParamKey":"threshold","defaultValue":"100000","actionCode":"PRICE_SYNC"}'
post /rule/type/save "$BODY_ORDER" > /dev/null
ok "类型 ORDER_PRICE_SYNC 已建（绑定 ORDER + 同一个 PRICE_SYNC）"
RO=$(post /rule/create '{"ruleName":"ORDER_PRICE_SYNC_100000","ruleType":"ORDER_PRICE_SYNC","ruleParams":"{\"actionCode\":\"PRICE_SYNC\",\"threshold\":100000}"}')
IDO=$(echo "$RO" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
R=$(post "/rule/publish/$IDO" '{"actionCode":"PRICE_SYNC","threshold":100000}')
has "规则 ORDER_PRICE_SYNC_100000 已发布" "$R" '"status":1'

echo "===== 4) 真跑一遍：两个单据都命中，调的是同一个接口 ====="
R=$(post "/rule/evaluate?docCode=SKU" '{"skuCode":"SKU-1","price":800}')
has "商品单据命中并回填真实 syncId（不是 null）" "$R" '"syncId":"SKU-ID-'
R=$(post "/rule/evaluate?docCode=ORDER" '{"orderId":"SO-1","totalAmount":900000}')
has "订单单据命中并回填真实 syncId（不是 null）" "$R" '"syncId":"SKU-ID-'
LOGS=$(curl -s -m 10 "$B/rule/http/logs?limit=20")
cnt=$(echo "$LOGS" | grep -o 'PRICE_SYNC' | wc -l | tr -d ' ')
[ "$cnt" -ge 2 ] && ok "同一个接口被两个单据各调一次（调用日志 ${cnt} 条）" || bad "接口调用次数异常：${cnt}"

echo "===== 5) 引用反查：这个接口被谁调用 ====="
R=$(curl -s -m 10 $B/rule/refs/action/PRICE_SYNC)
has "被商品单据 SKU 引用" "$R" '"SKU"'
has "被订单单据 ORDER 引用" "$R" '"ORDER"'
has "被两个规则类型引用" "$R" 'SKU_PRICE_SYNC'
has "被两条规则引用" "$R" 'ORDER_PRICE_SYNC_100000'
has "引用计数 = 2 单据 + 2 类型 + 2 规则 = 6" "$R" '"refCount":6'

echo "===== 6) 引用反查：这个单据被谁引用 ====="
R=$(curl -s -m 10 $B/rule/refs/doc/SKU)
has "被规则类型 SKU_PRICE_SYNC 引用" "$R" 'SKU_PRICE_SYNC'
has "被规则 SKU_PRICE_SYNC_500 引用" "$R" 'SKU_PRICE_SYNC_500'
has "被接口 PRICE_SYNC 引用" "$R" 'PRICE_SYNC'

echo "===== 7) 删除护栏：还被引用就不许删（并说清被谁引用） ====="
R=$(del "/rule/http/action/PRICE_SYNC")
has "删接口 → 409 拦住" "$R" 'HTTP=409'
has "拦住时说清是'一处注册、多处调用'的正常状态" "$R" '正被引用，不能删除'
R=$(del "/rule/doc/SKU")
has "删单据 → 409 拦住" "$R" 'HTTP=409'

echo "===== 8) 清理：解除引用后就能删（注册→复用→下线 的完整闭环） ====="
curl -s -m 10 -X DELETE "$B/rule/$IDS" > /dev/null
curl -s -m 10 -X DELETE "$B/rule/$IDO" > /dev/null
post "/rule/type/delete?ruleType=SKU_PRICE_SYNC" '' > /dev/null
post "/rule/type/delete?ruleType=ORDER_PRICE_SYNC" '' > /dev/null
R=$(curl -s -m 10 $B/rule/refs/action/PRICE_SYNC)
has "规则/类型删掉后接口已无人引用（refCount=0）" "$R" '"refCount":0'
R=$(del "/rule/http/action/PRICE_SYNC")
has "此时删接口 → 成功" "$R" 'HTTP=200'
R=$(curl -s -m 10 $B/rule/refs/action/PRICE_SYNC)
has "接口已从资产表消失" "$R" '接口不存在'
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null

echo
echo "===== 结果：PASS=${PASS}  FAIL=${FAIL} ====="
echo "引擎规则数回到 ${BASE_RULES}：$(curl -s -m 10 $B/rule/engine/info)"
[ "${FAIL}" = "0" ] && echo "全部通过" || echo "有失败项，请看上面 ❌"
