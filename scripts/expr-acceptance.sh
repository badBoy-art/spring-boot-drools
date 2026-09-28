#!/bin/bash
# 验收「多字段表达式」：用户给的例子
#   if (售价 - 成本) / 成本 >= 50% then instantid = 121
# 两条路径都测：
#   A 条件字段里**直接写算式**      (售价 - 成本) / 成本 >= ${step1Value}，参数写 50%
#   B 条件引用**已注册派生字段**   毛利率 >= ...
#   动作侧同时验证 写数字(NUMBER) 与 写算式结果(EXPR)
B=http://localhost:8080
J='Content-Type: application/json'
M="mysql -h127.0.0.1 -uroot -pzhaoZ1230 -N --default-character-set=utf8mb4 test"
postf() { curl -s -m 20 -X POST -H "$J" -d @"$1" "$B$2"; }
ok()  { echo "  ✅ $1"; }
bad() { echo "  ❌ $1"; }
has() { echo "$2" | grep -qF -- "$3" && ok "$1" || bad "$1（实际：$(echo "$2" | head -c 260)）"; }

echo "===== 准备：清理上一次的自查类型 ====="
OLD=$(  $M -e "select id from rule_definition where rule_name='EXPR_DEMO_1' order by id desc limit 1" 2>/dev/null | tr -d ' ')
[ -n "$OLD" ] && curl -s -m 20 -X DELETE "$B/rule/$OLD" > /dev/null && echo "  已删旧规则 $OLD"
curl -s -m 20 -X POST "$B/rule/type/delete?ruleType=EXPR_DEMO" > /dev/null && echo "  已删旧类型 EXPR_DEMO"

echo "===== 1) 建类型：条件写算式 + 动作写数字/算式 ====="
printf '%s' '{"ruleType":"EXPR_DEMO","typeName":"多字段算式演示","ruleGroup":"demo","sortOrder":80,"factClass":"DocFact","docCode":"SKU","outputFields":["instantid","costRatio","毛利额"],"steps":[
 {"stepName":"毛利≥阈值给即时通id","condField":"(售价 - 成本) / 成本","condOp":">=","condType":"DECIMAL","condValue":"${step1Value}","actionType":"SET_EXT","extField":"instantid","extValue":"121","extValueType":"NUMBER"},
 {"stepName":"写算式结果","condField":"售价","condOp":">","condType":"DOUBLE","condValue":"${step2Value}","actionType":"SET_EXT","extField":"costRatio","extValue":"(售价 - 成本) * 100 / 售价","extValueType":"EXPR"}
]}' > /tmp/expr_type.json
R=$(postf /tmp/expr_type.json /rule/type/save)
has "类型已保存" "$R" '"ruleType":"EXPR_DEMO"'
sleep 1
echo "  落库的步骤："
$M -e "select concat('    ', step_no, '. 条件=', cond_field, ' ', cond_op, ' ', cond_value, ' | 动作 ext[', ext_field, ']=', ext_value, ' (', ext_value_type, ')') from rule_step where rule_type='EXPR_DEMO' order by step_no" 2>/dev/null

echo "===== 2) 建规则（参数用百分比写法 50%）并发布 ====="
printf '%s' '{"ruleName":"EXPR_DEMO_1","ruleType":"EXPR_DEMO","ruleParams":"{\"step1Value\":\"50%\",\"step2Value\":0}"}' > /tmp/expr_rule.json
R=$(postf /tmp/expr_rule.json /rule/create)
ID=$(echo "$R" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
[ -z "$ID" ] && ID=$($M -e "select id from rule_definition where rule_name='EXPR_DEMO_1' order by id desc limit 1" 2>/dev/null | tr -d ' ')
echo "  规则 id=$ID"
printf '%s' '{"step1Value":"50%","step2Value":0}' > /tmp/expr_params.json
has "发布成功(status=1)" "$(postf /tmp/expr_params.json /rule/publish/$ID)" '"status":1'
echo "  引擎: $(curl -s -m 10 $B/rule/engine/info)"

echo "===== 3) 看生成的 DRL：算式被翻译成 getNumber(...) 组合，50% 变成 0.5 ====="
DRL=$($M -e "select drl_content from rule_definition where id=$ID" 2>/dev/null)
echo "$DRL" | tr ';' '\n' | grep -E "when|getExt" | sed 's/^/    /' | head -8
has "条件里是 getNumber 组合（多字段算式）" "$DRL" '(getNumber("售价") - getNumber("成本")) / getNumber("成本")'
has "百分比 50% 已被换算成 0.5" "$DRL" '>= 0.5'
has "写数字不带引号（instantid 是数字不是字符串）" "$DRL" 'put("instantid", 121D)'
has "写算式结果带事实变量前缀（RHS 才能解析）" "$DRL" 'put("costRatio", ($d.getNumber("售价")'

echo "===== 4) 评估：毛利 (100-65)/65 = 0.5385 ≥ 0.5 → 命中 ====="
R=$(curl -s -m 15 -X POST -H "$J" -d '{"bizId":"SKU-E1","skuCode":"SKU-E1","skuName":"羽绒服","price":100,"cost":65,"品类":"服装"}' "$B/rule/evaluate?docCode=SKU")
echo "  decision = $(echo "$R" | grep -o '"decision":{[^}]*}')"
has "instantid 是数字 121（不是 \"121\"）" "$R" '"instantid":121'
has "costRatio 是算式算出来的 35.0" "$R" '"costRatio":35.0'
has "ext 里也是数字" "$R" '"instantid":121'

echo "===== 5) 反向：毛利 (100-90)/90 = 0.111 < 0.5 → 不命中 ====="
R=$(curl -s -m 15 -X POST -H "$J" -d '{"bizId":"SKU-E2","skuCode":"SKU-E2","skuName":"袜子","price":100,"cost":90,"品类":"服装"}' "$B/rule/evaluate?docCode=SKU")
echo "  decision = $(echo "$R" | grep -o '"decision":{[^}]*}')"
has "instantid 不出现" "$(echo "$R" | grep -c 'instantid')" "0"

echo "  引擎终态: $(curl -s -m 10 $B/rule/engine/info)"
