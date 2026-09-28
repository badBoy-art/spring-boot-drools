#!/bin/bash
# 审批链路验收：商品毛利率 → 审批级别 → 查审批人（读接口）→ 发起审批流（写接口）；订单复用同一套动作链路
# 覆盖：多单据接入 / 派生字段 / 调接口查数据 / 调接口写数据 / 链式顺序 / 幂等 / 审计日志
B=http://localhost:8080
J='Content-Type: application/json'
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✅ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ❌ $1"; }
has() { echo "$2" | grep -qF -- "$3" && ok "$1" || bad "$1 （实际：$(echo "$2" | head -c 320)）"; }
post() { curl -s -m 20 -X POST -H "$J" -d "$2" "$B$1"; }
eq()  { [ "$2" = "$3" ] && ok "$1" || bad "$1（期望 $3，实际 $2）"; }

echo "===== 0) 重置演示状态 ====="
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
BASE_RULES=$(curl -s -m 10 $B/rule/engine/info | grep -o '"ruleCount":[0-9]*' | cut -d: -f2)
RULES_ON=$((BASE_RULES + 4))
echo "  引擎规则数=${BASE_RULES}（发布 4 条审批规则后应为 ${RULES_ON}）"

echo "===== 1) ① 单据注册：商品 SKU 已接入，毛利率是派生字段 ====="
T=$(curl -s -m 10 "$B/rule/doc/tree")
has "商品单据已注册" "$T" '"docCode":"SKU"'
has "售价/成本字段在册" "$T" '"fieldKey":"cost"'
has "毛利率是派生字段（带计算表达式）" "$T" '"expr":"(price - cost) * 1.0 / price"'

echo "===== 2) ③ 生成规则类型：毛利率/金额 阈值 → 审批级别 ====="
R=$(post /rule/type/build '{"ruleType":"SKU_MARGIN_L2","typeName":"商品毛利率高于阈值→+2级领导","ruleGroup":"approval","sortOrder":80,"factClass":"DocFact","docCode":"SKU","mode":"SET_EXT","fieldPath":"毛利率","operator":">","valueType":"NUMBER","valueParamKey":"marginThreshold","defaultValue":"0.30","extField":"approvalLevel","extValue":"L2","message":"毛利率超过 ${marginThreshold}，需 +2 级领导审批"}')
has "生成类型：毛利率 > 阈值（NUMBER 比较）" "$R" 'getNumber(\"毛利率\") > ${marginThreshold}'
has "生成动作：审批级别写进单据 ext" "$R" 'getExt().put(\"approvalLevel\", \"L2\")'
post /rule/type/save '{"ruleType":"SKU_MARGIN_L2","typeName":"商品毛利率高于阈值→+2级领导","ruleGroup":"approval","sortOrder":80,"factClass":"DocFact","docCode":"SKU","mode":"SET_EXT","fieldPath":"毛利率","operator":">","valueType":"NUMBER","valueParamKey":"marginThreshold","defaultValue":"0.30","extField":"approvalLevel","extValue":"L2","message":"毛利率超过 ${marginThreshold}，需 +2 级领导审批"}' > /dev/null
ok "类型 SKU_MARGIN_L2 已保存"
post /rule/type/save '{"ruleType":"SKU_MARGIN_L1","typeName":"商品毛利率不高于阈值→商品负责人","ruleGroup":"approval","sortOrder":81,"factClass":"DocFact","docCode":"SKU","mode":"SET_EXT","fieldPath":"毛利率","operator":"<=","valueType":"NUMBER","valueParamKey":"marginThreshold","defaultValue":"0.30","extField":"approvalLevel","extValue":"L1","message":"毛利率在 ${marginThreshold} 以内，只需商品负责人审批"}' > /dev/null
ok "类型 SKU_MARGIN_L1 已保存"
post /rule/type/save '{"ruleType":"ORDER_AMOUNT_L2","typeName":"订单金额高于阈值→+2级领导","ruleGroup":"approval","sortOrder":82,"factClass":"DocFact","docCode":"ORDER","mode":"SET_EXT","fieldPath":"totalAmount","operator":">=","valueType":"NUMBER","valueParamKey":"amountThreshold","defaultValue":"100000","extField":"approvalLevel","extValue":"L2","message":"订单金额达 ${amountThreshold}，需 +2 级领导审批"}' > /dev/null
ok "类型 ORDER_AMOUNT_L2 已保存"
post /rule/type/save '{"ruleType":"ORDER_AMOUNT_L1","typeName":"订单金额未达阈值→普通审批","ruleGroup":"approval","sortOrder":83,"factClass":"DocFact","docCode":"ORDER","mode":"SET_EXT","fieldPath":"totalAmount","operator":"<","valueType":"NUMBER","valueParamKey":"amountThreshold","defaultValue":"100000","extField":"approvalLevel","extValue":"L1","message":"订单金额未达 ${amountThreshold}，走普通审批"}' > /dev/null
ok "类型 ORDER_AMOUNT_L1 已保存"

echo "===== 3) ④ 配置并发布 4 条规则 ====="
IDS=""
create_rule() {  # $1=规则名 $2=类型 $3=参数名 $4=参数值
  local R ID
  R=$(post /rule/create "{\"ruleName\":\"$1\",\"ruleType\":\"$2\",\"ruleParams\":\"{\\\"$3\\\":$4}\"}")
  ID=$(echo "$R" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
  if [ -z "$ID" ]; then bad "规则 $1 创建失败：$(echo $R | head -c 200)"; return 1; fi
  IDS="$IDS $ID"
  R=$(post "/rule/publish/$ID" "{\"$3\":$4}")
  has "规则 $1 已发布（生成 DRL）" "$R" '"status":1'
  return 0
}
create_rule SKU_APPROVAL_L2   SKU_MARGIN_L2   marginThreshold 0.30
create_rule SKU_APPROVAL_L1   SKU_MARGIN_L1   marginThreshold 0.30
create_rule ORDER_APPROVAL_L2 ORDER_AMOUNT_L2 amountThreshold 100000
create_rule ORDER_APPROVAL_L1 ORDER_AMOUNT_L1 amountThreshold 100000
has "引擎规则数 ${BASE_RULES} → ${RULES_ON}（4 条审批规则进引擎）" "$(curl -s -m 10 $B/rule/engine/info)" "\"ruleCount\":$RULES_ON"

echo "===== 4) 商品试算：毛利率 35% → 需 +2 级领导（调接口查数据 + 调接口写数据） ====="
R=$(post "/rule/evaluate?docCode=SKU" '{"skuCode":"SKU-1001","skuName":"羽绒服","price":100,"cost":65}')
echo "  返回：$(echo "$R" | head -c 500)"
has "派生字段毛利率算出 0.35" "$R" '"毛利率":0.35'
has "命中 +2 级审批（approvalLevel=L2）" "$R" '"approvalLevel":"L2"'
has "查接口回填 +2 级审批人姓名" "$R" '"approverName":"王总（+2 级领导）"'
has "审批流已发起（回填 procInstId）" "$R" '"procInstId":"WF-'
has "流程状态回填 procStatus" "$R" '"procStatus":"RUNNING"'
has "过程消息带上审批人" "$R" '已按审批级别发起流程，审批人='
CALLS=$(curl -s -m 10 $B/mock/calls)
has "被调方收到「查审批人」入参 level=L2" "$CALLS" 'level=L2'
has "被调方收到「发起审批流」且带着查到的审批人" "$CALLS" 'approverId=U-L2-001'
has "审批流 flowKey = SKU_FLOW_L2（单据+级别拼出）" "$CALLS" 'flowKey=SKU_FLOW_L2'
eq "同一单据只调一次查审批人（幂等守卫）" "$(echo "$CALLS" | grep -c 'flow/approver')" "1"

echo "===== 5) 商品试算：毛利率 20% → 只需商品负责人（规则同类型、只是参数不同） ====="
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
R=$(post "/rule/evaluate?docCode=SKU" '{"skuCode":"SKU-1002","skuName":"棉袜","price":100,"cost":80}')
has "派生字段毛利率算出 0.2" "$R" '"毛利率":0.2'
has "命中普通审批（approvalLevel=L1）" "$R" '"approvalLevel":"L1"'
has "查接口回填商品负责人" "$R" '"approverName":"李经理（商品负责人）"'
has "flowKey = SKU_FLOW_L1" "$(curl -s -m 10 $B/mock/calls)" 'flowKey=SKU_FLOW_L1'

echo "===== 6) 订单试算：金额 150000 → 走同一套中台（单据不同、规则不同、动作链路复用） ====="
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
R=$(post "/rule/evaluate?docCode=ORDER" '{"orderId":"SO-9001","procInstId":"SO-9001","totalAmount":150000,"customer":{"level":"NORMAL","region":"上海"},"items":[{"product":{"name":"设备","category":"食品","price":150000,"stock":5,"onShelf":true},"quantity":1}]}')
has "订单命中 +2 级审批" "$R" '"approvalLevel":"L2"'
has "订单也查到了 +2 级审批人" "$R" '"approverId":"U-L2-001"'
has "订单也发起了审批流" "$R" '"procInstId":"WF-'
has "订单 flowKey = ORDER_FLOW_L2" "$(curl -s -m 10 $B/mock/calls)" 'flowKey=ORDER_FLOW_L2'

echo "===== 7) 审计：查/写两次调用都留日志 ====="
L=$(curl -s -m 10 "$B/rule/http/logs?limit=30")
has "日志里有 APPROVER_QUERY（查数据）" "$L" 'APPROVER_QUERY'
has "日志里有 APPROVAL_START（写数据）" "$L" 'APPROVAL_START'

echo "===== 8) 清理（删 4 条规则 + 4 个类型；SKU 单据/两个接口/平台级动作规则保留为种子） ====="
for ID in $IDS; do curl -s -m 10 -X DELETE "$B/rule/$ID" > /dev/null; done
for t in SKU_MARGIN_L2 SKU_MARGIN_L1 ORDER_AMOUNT_L2 ORDER_AMOUNT_L1; do
  curl -s -m 10 -X POST "$B/rule/type/delete?ruleType=$t" > /dev/null
done
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
echo "  清理后引擎: $(curl -s -m 10 $B/rule/engine/info)"

echo
echo "===== 结果：PASS=${PASS}  FAIL=${FAIL} ====="
[ "${FAIL}" = "0" ] && echo "全部通过" || echo "有失败项，请看上面 ❌"
