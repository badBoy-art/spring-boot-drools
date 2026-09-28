#!/bin/bash
# 把「运算符/类型全集」演示发布跑通：发布草稿规则 → 校验引擎 → 评估看决策
B=http://localhost:8080
J='Content-Type: application/json'
M="mysql -h127.0.0.1 -uroot -pzhaoZ1230 -N --default-character-set=utf8mb4 test"
post() { curl -s -m 20 -X POST -H "$J" -d "$2" "$B$1"; }
postf() { curl -s -m 20 -X POST -H "$J" -d @"$2" "$B$1"; }
ok()   { echo "  ✅ $1"; }
bad()  { echo "  ❌ $1"; }
has()  { echo "$2" | grep -qF -- "$3" && ok "$1" || bad "$1（实际：$(echo "$2" | head -c 260)）"; }
ruleId() { $M -e "select id from rule_definition where rule_name='$1' order by id desc limit 1" 2>/dev/null | tr -d ' '; }

mkrule() { # $1=文件 $2=规则名 $3=类型 $4=参数JSON
  printf '%s' "{\"ruleName\":\"$2\",\"ruleType\":\"$3\",\"ruleParams\":\"$(echo "$4" | sed 's/"/\\"/g')\"}" > "$1"
}
mkparams() { printf '%s' "$1" > "$2"; }

echo "===== A) 发布 SKU_OPS_DEMO（>= 0.3 / in(CSV) / contains） ====="
P='{"step1Value":0.3,"step2Value":"服装,数码","step3Value":"羽绒"}'
mkparams "$P" /tmp/ops1.params
mkrule /tmp/ops1.rule "SKU_OPS_DEMO_1" "SKU_OPS_DEMO" "$P"
R=$(postf /rule/create /tmp/ops1.rule)
ID=$(echo "$R" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
[ -z "$ID" ] && ID=$(ruleId "SKU_OPS_DEMO_1")
echo "  规则 id=$ID"
has "发布成功(status=1)" "$(postf "/rule/publish/$ID" /tmp/ops1.params)" '"status":1'
echo "  引擎: $(curl -s -m 10 $B/rule/engine/info)"

echo "===== B) 评估：in / contains 命中，决策字段来自【规则类型注册】 ====="
R=$(post '/rule/evaluate?docCode=SKU' '{"bizId":"SKU-9001","skuCode":"SKU-9001","skuName":"羽绒服","price":100,"cost":65,"品类":"服装"}')
echo "  decision: $(echo "$R" | tr ',' '\n' | grep -A8 '"decision"' | tr '\n' ' ')"
has "decision 含 approvalLevel（>= 命中）" "$R" '"approvalLevel":"L2"'
has "decision 含 categoryHit（in 命中：服装 ∈ 服装,数码）" "$R" '"categoryHit":"Y"'
has "decision 含 nameHit（contains 命中：羽绒服 含 羽绒）" "$R" '"nameHit":"Y"'

echo "===== C) 反向：不满足 → 对应决策字段不出现 ====="
R=$(post '/rule/evaluate?docCode=SKU' '{"bizId":"SKU-9002","skuCode":"SKU-9002","skuName":"羊毛衫","price":100,"cost":65,"品类":"家居"}')
has "categoryHit 不出现（in 未命中）" "$(echo "$R" | grep -c 'categoryHit')" "0"
has "nameHit 不出现（contains 未命中）" "$(echo "$R" | grep -c 'nameHit')" "0"

echo "===== D) 前缀算子 startsWith（商品编码以 SKU 开头） ====="
P2='{"step1Value":"SKU"}'
mkparams "$P2" /tmp/ops2.params
mkrule /tmp/ops2.rule "SKU_OPS_2_1" "SKU_OPS_2" "$P2"
R=$(postf /rule/create /tmp/ops2.rule)
ID2=$(echo "$R" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
[ -z "$ID2" ] && ID2=$(ruleId "SKU_OPS_2_1")
echo "  规则 id=$ID2"
has "发布成功" "$(postf "/rule/publish/$ID2" /tmp/ops2.params)" '"status":1'
R=$(post '/rule/evaluate?docCode=SKU' '{"bizId":"SKU-9003","skuCode":"SKU-9003","skuName":"羊毛衫","price":100,"cost":65,"品类":"家居"}')
has "codeHit=Y（编码以 SKU 开头）" "$R" '"codeHit":"Y"'
echo "  DRL: $(curl -s -m 10 $B/rule/list | tr ',' '\n' | grep -o 'matches \\\\"[^\\\\]*' | head -2 | tr '\n' ' ')"

echo "===== E) 为空 / 不为空（不生成取值参数） ====="
R=$(curl -s -m 15 "$B/rule/evaluate?docCode=SKU" -X POST -H "$J" -d '{"bizId":"SKU-9004","skuCode":"SKU-9004","skuName":"羊毛衫","price":100,"cost":65,"品类":"家居"}')
has "namePresent=Y（名称不为空命中，规则已发布）" "$R" '"namePresent":"Y"'
echo "  DRL 空值断言: $(curl -s -m 10 $B/rule/list | tr ',' '\n' | grep -o 'getString(..商品名称..) [!=]= null' | head -2 | tr '\n' ' ')"

echo
echo "引擎终态: $(curl -s -m 10 $B/rule/engine/info)"
echo "已发布规则："; $M -e "select rule_name, rule_type, status from rule_definition where rule_type like 'SKU_OPS%' or rule_type='SKU_DECISION'" 2>/dev/null
