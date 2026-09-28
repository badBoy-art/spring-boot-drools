#!/bin/bash
# 组合规则表（页面版决策表）验收：注入守门 → 预览 DRL → 发布 → 试算命中 → 改值重发 → 停用 → 导出 → 恢复种子
# 注意：试算用「小额订单(1000元)」，避免触发风控拦截规则影响折扣比对
B=http://localhost:8080
J='Content-Type: application/json'
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✅ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ❌ $1"; }
has() { echo "$2" | grep -qF -- "$3" && ok "$1" || bad "$1 （实际：$(echo "$2" | head -c 260)）"; }
PY=/tmp/kie-probe/venv/bin/python

COLS='[{"key":"level","label":"会员等级","factVar":"$o","factType":"Order","fieldPath":"customer.level","op":"==","valueType":"ENUM","enumOptions":"VIP,GOLD,NORMAL"},{"key":"category","label":"商品品类","factVar":"$i","factType":"OrderItem","fieldPath":"product.category","op":"==","valueType":"ENUM","enumOptions":"电子产品,服装,食品,生鲜"}]'
ACT='{"type":"SET_DISCOUNT_RATE","label":"折扣率(按明细小计)","paramKey":"value","paramType":"NUMBER"}'
ROWS_SEED='[{"level":"VIP","category":"电子产品","value":"0.05"},{"level":"VIP","category":"服装","value":"0.08"},{"level":"NORMAL","category":"食品","value":"0"},{"level":"GOLD","category":"电子产品","value":"0.03"}]'
ROWS_BAD='[{"level":"VIP","category":"军火","value":"0.05"},{"level":"VIP","category":"服装","value":"abc"}]'
ROWS_INJECT='[{"level":"VIP","category":"电子产品","value":"0.1); Runtime.getRuntime().exec(\"x\"); //"}]'
ROWS_NEW='[{"level":"VIP","category":"电子产品","value":"0.20"},{"level":"VIP","category":"服装","value":"0.08"},{"level":"NORMAL","category":"食品","value":"0"},{"level":"GOLD","category":"电子产品","value":"0.03"}]'

req() { echo "{\"assetKey\":\"MEMBER_CATEGORY_RATE\",\"assetName\":\"会员×品类折扣率（组合表示例）\",\"factClass\":\"Order\",\"salience\":-20,\"columns\":$COLS,\"action\":$ACT,\"rows\":$1}"; }
order() { echo "{\"orderId\":\"CT-$RANDOM\",\"customer\":{\"level\":\"VIP\",\"region\":\"上海\",\"age\":30,\"newCustomer\":false},\"items\":[{\"product\":{\"name\":\"组合表测试\",\"category\":\"电子产品\",\"price\":1000,\"stock\":50,\"onShelf\":true},\"quantity\":1}]}"; }
discount() { curl -s -m 20 -X POST -H "$J" -d "$(order)" $B/order/evaluate | grep -o '"discount":[0-9.]*' | head -1 | cut -d: -f2; }
delta() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.1f", a-b}'; }

echo "===== 0) 基线（组合表此时应为停用/草稿态） ====="
BASE=$(discount)
BASE_RULES=$(curl -s -m 10 $B/rule/engine/info | grep -o '"ruleCount":[0-9]*' | cut -d: -f2)
RULES_ON=$((BASE_RULES + 4))
echo "  基线 discount=${BASE}｜引擎规则数=${BASE_RULES}（发布 4 行组合后应为 ${RULES_ON}）"

echo "===== 1) 守门：枚举越界 / 非数字 / 注入字符 三类都要被拦 ====="
R=$(curl -s -m 20 -X POST -H "$J" -d "$(req "$ROWS_BAD")" $B/rule/ct/preview)
has "枚举越界被拦（第1行 商品品类）" "$R" '"row":1,"column":"商品品类"'
has "非数字的动作取值被拦（第2行）" "$R" '"row":2,"column":"动作取值"'
has "报错文案说明原因" "$R" '必须是数字'
R=$(curl -s -m 20 -X POST -H "$J" -d "$(req "$ROWS_INJECT")" $B/rule/ct/preview)
has "注入式取值（0.1); Runtime.exec…）被拦" "$R" '必须是数字'
R=$(curl -s -m 20 -X POST -H "$J" -d "$(req "$ROWS_BAD")" $B/rule/ct/save)
has "有错时保存被拒" "$R" '组合规则表校验失败'

echo "===== 2) 预览：4 行 → 4 条规则，DRL 结构正确 ====="
R=$(curl -s -m 20 -X POST -H "$J" -d "$(req "$ROWS_SEED")" $B/rule/ct/preview)
has "4 行 = 4 条规则" "$R" '"ruleCount":4'
has "用 java 方言（避免 mvel update() 反复点火）" "$R" 'dialect \"java\"'
has "主事实约束合并进同一模式" "$R" '$o : Order( rejected == false, customer.level == \"VIP\" )'
has "明细另起模式" "$R" '$i : OrderItem( product.category == \"电子产品\" )'
has "动作按明细小计累加" "$R" 'setDiscount($o.getDiscount() + $i.getSubtotal() * 0.05)'

echo "===== 3) 保存 + 发布生效 ====="
R=$(curl -s -m 20 -X POST -H "$J" -d "$(req "$ROWS_SEED")" $B/rule/ct/save)
has "定义已保存" "$R" '"saved":true'
R=$(curl -s -m 20 -X POST "$B/rule/ct/publish?assetKey=MEMBER_CATEGORY_RATE")
has "发布成功（返回版本号）" "$R" '"version":'
V1=$(echo "$R" | grep -o '"version":[0-9]*' | head -1 | cut -d: -f2)
has "引擎规则数 ${BASE_RULES} → ${RULES_ON}（4 行组合进引擎）" "$(curl -s -m 10 $B/rule/engine/info)" "\"ruleCount\":$RULES_ON"

echo "===== 4) 试算：VIP×电子产品 命中组合表（折扣应 +0.05×1000=50） ====="
AFTER=$(discount)
D=$(delta "$AFTER" "$BASE")
echo "  发布后 discount=${AFTER}（基线 ${BASE}），增量 ${D}"
[ "${D}" = "50.0" ] && ok "折扣增量正好 50（0.05 × 1000）" || bad "折扣增量不是 50：${BASE} → ${AFTER}"
R=$(curl -s -m 20 -X POST -H "$J" -d "$(order)" $B/order/evaluate)
has "命中消息带动作列标签" "$R" '折扣率(按明细小计) 0.05'

echo "===== 5) 改一行取值 → 重新发布（行数不变，原子替换） ====="
curl -s -m 20 -o /dev/null -X POST -H "$J" -d "$(req "$ROWS_NEW")" $B/rule/ct/save
R=$(curl -s -m 20 -X POST "$B/rule/ct/publish?assetKey=MEMBER_CATEGORY_RATE")
V2=$(echo "$R" | grep -o '"version":[0-9]*' | head -1 | cut -d: -f2)
[ -n "${V2}" ] && [ "${V2}" -gt "${V1}" ] && ok "重新发布（版本号递增 v${V1} → v${V2}）" || bad "版本号未递增：${V1} → ${V2}"
has "规则数仍是 ${RULES_ON}（4 行，无残留旧规则）" "$(curl -s -m 10 $B/rule/engine/info)" "\"ruleCount\":$RULES_ON"
AFTER2=$(discount)
D2=$(delta "$AFTER2" "$BASE")
echo "  改值后 discount=${AFTER2}（基线 ${BASE}），增量 ${D2}"
[ "${D2}" = "200.0" ] && ok "折扣增量变成 200（0.20 × 1000）" || bad "改值后增量不对：${BASE} → ${AFTER2}"

echo "===== 6) 停用 / 启用 ====="
R=$(curl -s -m 20 -X POST "$B/rule/ct/status?assetKey=MEMBER_CATEGORY_RATE&status=2")
has "停用后规则回到 ${BASE_RULES}" "$R" "\"ruleCount\":$BASE_RULES"
has "停用后试算回到基线" "$(discount)" "${BASE}"
R=$(curl -s -m 20 -X POST "$B/rule/ct/status?assetKey=MEMBER_CATEGORY_RATE&status=1")
has "启用后规则回到 ${RULES_ON}" "$R" "\"ruleCount\":$RULES_ON"

echo "===== 7) 恢复种子状态（原 4 行 + 停用），保证可重跑 ====="
curl -s -m 20 -o /dev/null -X POST -H "$J" -d "$(req "$ROWS_SEED")" $B/rule/ct/save
curl -s -m 20 -o /dev/null -X POST "$B/rule/ct/status?assetKey=MEMBER_CATEGORY_RATE&status=2"
FINAL=$(discount)
echo "  恢复后 discount=${FINAL}（应等于基线 ${BASE}）｜$(curl -s -m 10 $B/rule/engine/info)"
[ "${FINAL}" = "${BASE}" ] && ok "状态已回滚到基线" || bad "状态未回滚：${BASE} → ${FINAL}"

echo
echo "===== 结果：PASS=${PASS}  FAIL=${FAIL} ====="
[ "${FAIL}" = "0" ] && echo "全部通过" || echo "有失败项，请看上面 ❌"
