#!/bin/bash
# 多步骤规则验收：一条规则 = 步骤链（判定级别 → 查审批人 → 发起审批流），
#   重点验证"接口返回结果如何参与后续计算"：第 3 步的入参引用第 2 步查回来的 ${ext.approverId}
B=http://localhost:8080
J='Content-Type: application/json'
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✅ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ❌ $1"; }
has() { echo "$2" | grep -qF -- "$3" && ok "$1" || bad "$1 （实际：$(echo "$2" | head -c 300)）"; }
post() { curl -s -m 20 -X POST -H "$J" -d "$2" "$B$1"; }

TYPE='SKU_APPROVAL_3STEP'
STEPS='[
 {"stepName":"判定审批级别","condField":"毛利率","condOp":">=","condType":"NUMBER","condValue":"0.30",
  "actionType":"SET_EXT","extField":"approvalLevel","extValue":"L2","message":"毛利率达 ${step1Value}，走 +2 级领导"},
 {"stepName":"查审批人","condField":"","actionType":"CALL","actionCode":"APPROVER_QUERY",
  "paramJson":"{\"level\":\"${ext.approvalLevel}\"}"},
 {"stepName":"发起审批流","condField":"","actionType":"CALL","actionCode":"APPROVAL_START",
  "paramJson":"{\"flowKey\":\"${docCode}_FLOW_${ext.approvalLevel}\",\"approverId\":\"${ext.approverId}\"}"}
]'
BODY="{\"ruleType\":\"$TYPE\",\"typeName\":\"商品审批链路(三步)\",\"ruleGroup\":\"approval\",\"sortOrder\":60,\"factClass\":\"DocFact\",\"docCode\":\"SKU\",\"steps\":$STEPS}"

echo "===== 0) 基线 ====="
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
BASE=$(curl -s -m 10 $B/rule/engine/info | grep -o '"ruleCount":[0-9]*' | cut -d: -f2)
echo "  引擎规则数=${BASE}"

echo "===== 1) 预览：步骤链编译成 3 条规则，且第 3 步等第 2 步的产物 ====="
R=$(post /rule/type/build "$BODY")
has "生成了 3 条规则（@RULE@#1/#2/#3，规则名在发布时注入）" "$R" '@RULE@#3'
has "第 1 步：条件 毛利率 >= 参数" "$R" 'getNumber(\"毛利率\") >= ${step1Value}'
has "第 1 步：写审批级别" "$R" 'getExt().put(\"approvalLevel\", \"L2\")'
has "第 2 步：调接口（接口码是参数，可在④换）" "$R" 'invoke(\"${step2Action}\"'
has "第 3 步声明它依赖上一步的产物（upstreamRefs）" "$R" '"upstreamRefs":["approvalLevel","approverId"]'
has "第 3 步 LHS 自动加'上游产物就绪'守卫" "$R" 'ext[\"approverId\"] != null'
has "每步先写 stepN_done 再动外部（at-most-once，标记按规则名隔离）" "$R" 'put(\"@RULE@_step2_done\", true)'
has "salience 递减（100-5n）保证先后" "$R" 'salience 85'
has "参数定义含三步的可配项" "$R" 'step3Action'
ACT=$(curl -s -m 10 $B/rule/http/actions)
has "第 2 步查回的审批人，由接口的返回值映射回填 ext" "$ACT" 'data.approverId'
has "第 3 步的入参模板引用 ext.approverId（上一步的返回值）" "$ACT" '${ext.approverId}'

echo "===== 2) 保存类型：步骤链落库、可再编辑 ====="
R=$(post /rule/type/save "$BODY")
has "类型已保存" "$R" '规则类型已保存'
R=$(curl -s -m 10 "$B/rule/type/steps?ruleType=$TYPE")
has "步骤链读回 3 步" "$R" '"stepNo":3'
has "第 3 步记录了它调的接口" "$R" 'APPROVAL_START'

echo "===== 3) ④ 配规则并发布（参数：阈值 + 两步各调哪个接口） ====="
R=$(post /rule/create "{\"ruleName\":\"SKU_FLOW_3STEP_HIGH\",\"ruleType\":\"$TYPE\",\"ruleParams\":\"{\\\"step1Value\\\":0.30,\\\"step2Action\\\":\\\"APPROVER_QUERY\\\",\\\"step3Action\\\":\\\"APPROVAL_START\\\"}\"}")
ID=$(echo "$R" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
R=$(post "/rule/publish/$ID" '{"step1Value":0.30,"step2Action":"APPROVER_QUERY","step3Action":"APPROVAL_START"}')
has "规则已发布" "$R" '"status":1'
has "引擎规则数 ${BASE} → $((BASE+3))（一条规则=3 条 DRL）" "$(curl -s -m 10 $B/rule/engine/info)" "\"ruleCount\":$((BASE+3))"
has "发布的 DRL 里规则名已注入（SKU_FLOW_3STEP_HIGH#1）" "$(curl -s -m 10 $B/rule/list)" 'SKU_FLOW_3STEP_HIGH#1'

echo "===== 4) 试算：三步依次跑通，接口返回值进入后续步骤 ====="
R=$(post "/rule/evaluate?docCode=SKU" '{"skuCode":"SKU-9001","skuName":"羽绒服","price":100,"cost":65}')
echo "  $(echo "$R" | head -c 420)"
has "第 1 步判定出审批级别 L2" "$R" '"approvalLevel":"L2"'
has "第 2 步查到 +2 级审批人" "$R" '"approverId":"U-L2-001"'
has "第 3 步用上一步的审批人发起了流程" "$R" '"procInstId":"WF-'
has "三步的消息都在" "$R" '第3步[发起审批流]'
CALLS=$(curl -s -m 10 $B/mock/calls)
has "第 3 步的入参带上了第 2 步查回的审批人" "$CALLS" 'approverId=U-L2-001'
has "流程 key 也带了上一步的级别" "$CALLS" 'flowKey=SKU_FLOW_L2'
cnt=$(echo "$CALLS" | grep -c 'flow/approver')
[ "$cnt" = "1" ] && ok "查审批人只调一次（step_done 守卫幂等）" || bad "查审批人调用次数异常：${cnt}"

echo "===== 5) 可配性：把第 2 步换成另一个接口（从接口列表选；用种子接口 RISK_CHECK 避免依赖别的脚本） ====="
R=$(post "/rule/publish/$ID" '{"step1Value":0.30,"step2Action":"RISK_CHECK","step3Action":"APPROVAL_START"}')
has "重新发布成功（第 2 步改用 RISK_CHECK）" "$R" '"status":1'
has "生成的 DRL 里第 2 步换了接口" "$R" 'RISK_CHECK'
R=$(post "/rule/publish/$ID" '{"step1Value":0.30,"step2Action":"NO_SUCH_ACTION","step3Action":"APPROVAL_START"}')
has "换成不存在的接口 → 发布就被拦下（不会等到运行期才炸）" "$R" '接口动作不存在'
R=$(post "/rule/publish/$ID" '{"step1Value":0.30,"step2Action":"APPROVER_QUERY","step3Action":"APPROVAL_START"}')
has "换回 APPROVER_QUERY 恢复" "$R" '"status":1'

echo "===== 5.5) 调接口的参数怎么传 + 打标打到哪儿 ====="
OV='SKU_STEP_PARAM'
STEPS2='[{"stepName":"打标需复核","actionType":"MARK","extField":"needL2Review","message":"已打标 needL2Review"},
         {"stepName":"查审批人(带规则上配的入参)","actionType":"CALL","actionCode":"APPROVER_QUERY","paramJson":"{\"bizId\":\"${skuCode}\",\"channel\":\"规则引擎覆盖\"}"}]'
BODY2="{\"ruleType\":\"$OV\",\"typeName\":\"参数与打标演示\",\"ruleGroup\":\"approval\",\"sortOrder\":61,\"factClass\":\"DocFact\",\"docCode\":\"SKU\",\"steps\":$STEPS2}"
R=$(post /rule/type/build "$BODY2")
has "带入参覆写时生成三参 invoke（接口码 + 单据 + 规则上配的入参）" "$R" 'invoke(\"${step2Action}\", $d, \"'
has "覆写的入参写进了 DRL" "$R" '规则引擎覆盖'
has "打标动作 = 往单据 ext 写 true" "$R" 'getExt().put(\"needL2Review\", true)'
post /rule/type/save "$BODY2" > /dev/null
R=$(post /rule/create "{\"ruleName\":\"SKU_PARAM_DEMO\",\"ruleType\":\"$OV\",\"ruleParams\":\"{\\\"step2Action\\\":\\\"APPROVER_QUERY\\\"}\"}")
ID2=$(echo "$R" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
post "/rule/publish/$ID2" '{"step2Action":"APPROVER_QUERY"}' > /dev/null
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
R=$(post "/rule/evaluate?docCode=SKU" '{"skuCode":"SKU-7001","price":100,"cost":65}')
has "打标落到单据 ext（ext.needL2Review = true）" "$R" '"needL2Review":true'
has "接口收到的参数是规则上绑定的（bizId ← 规则上下文的 \${skuCode}）" "$(curl -s -m 10 $B/mock/calls)" 'bizId=SKU-7001'
echo "$(curl -s -m 10 $B/mock/calls)" | grep -qF 'channel=规则引擎覆盖' \
  && bad "接口没声明的参数名不该被塞进报文" || ok "接口没声明的参数名不会被塞进报文（契约只认模板里的 \${参数名}）"
curl -s -m 10 -X DELETE "$B/rule/$ID2" > /dev/null
post "/rule/type/delete?ruleType=$OV" '' > /dev/null

echo "===== 6) 清理 ====="
curl -s -m 10 -X DELETE "$B/rule/$ID" > /dev/null
post "/rule/type/delete?ruleType=$TYPE" '' > /dev/null
has "类型已删除（步骤链一并清理）" "$(curl -s -m 10 "$B/rule/type/steps?ruleType=$TYPE")" '"steps":[]'
curl -s -m 10 -X DELETE $B/mock/calls > /dev/null
echo "  引擎: $(curl -s -m 10 $B/rule/engine/info)"

echo
echo "===== 结果：PASS=${PASS}  FAIL=${FAIL} ====="
[ "${FAIL}" = "0" ] && echo "全部通过" || echo "有失败项，请看上面 ❌"
