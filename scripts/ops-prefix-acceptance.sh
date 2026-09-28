#!/bin/bash
# D) 前缀算子 startsWith —— 从"干净状态"重建（历史脏类型：模板残留 endsWith 语义、steps 未落库）
B=http://localhost:8080
J='Content-Type: application/json'
M="mysql -h127.0.0.1 -uroot -pzhaoZ1230 -N --default-character-set=utf8mb4 test"
post() { curl -s -m 20 -X POST -H "$J" -d @"$2" "$B$1"; }
ok()   { echo "  ✅ $1"; }
bad()  { echo "  ❌ $1"; }
has()  { echo "$2" | grep -qF -- "$3" && ok "$1" || bad "$1（实际：$(echo "$2" | head -c 240)）"; }

echo "===== D) 前缀算子 startsWith（清理脏类型 → 重存 → 发布 → 断言） ====="
OLD=$($M -e "select id from rule_definition where rule_name='SKU_OPS_2_1' order by id desc limit 1" 2>/dev/null | tr -d ' ')
[ -n "$OLD" ] && curl -s -m 20 -X DELETE "$B/rule/$OLD" > /dev/null && echo "  已删旧规则 id=$OLD"
curl -s -m 20 -X POST "$B/rule/type/delete?ruleType=SKU_OPS_2" > /dev/null && echo "  已删旧类型 SKU_OPS_2"

printf '%s' '{"ruleType":"SKU_OPS_2","typeName":"前缀判断","ruleGroup":"demo","sortOrder":71,"factClass":"DocFact","docCode":"SKU","outputFields":["codeHit"],"steps":[{"stepName":"编码前缀","condField":"商品编码","condOp":"startsWith","condType":"STRING","condValue":"${step1Value}","actionType":"SET_EXT","extField":"codeHit","extValue":"Y"}]}' > /tmp/t2.json
R=$(post /rule/type/save /tmp/t2.json)
has "类型已保存（含 1 步）" "$R" '"ruleType":"SKU_OPS_2"'
has "步骤已落库" "$($M -e "select count(*) from rule_step where rule_type='SKU_OPS_2'" 2>/dev/null | tr -d ' ')" "1"
TPL=$($M -e "select template_body from rule_template where rule_type='SKU_OPS_2'" 2>/dev/null)
has "模板是【前缀】语义 matches \"^...\"" "$TPL" 'matches "^${step1Value}.*"'

printf '%s' '{"ruleName":"SKU_OPS_2_1","ruleType":"SKU_OPS_2","ruleParams":"{\"step1Value\":\"SKU\"}"}' > /tmp/r2.json
R=$(post /rule/create /tmp/r2.json)
ID=$(echo "$R" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
[ -z "$ID" ] && ID=$($M -e "select id from rule_definition where rule_name='SKU_OPS_2_1' order by id desc limit 1" 2>/dev/null | tr -d ' ')
has "create 成功(id=$ID)" "$ID" ""
printf '%s' '{"step1Value":"SKU"}' > /tmp/p2.json
has "发布成功(status=1)" "$(post "/rule/publish/$ID" /tmp/p2.json)" '"status":1'
DRL=$($M -e "select drl_content from rule_definition where id=$ID" 2>/dev/null)
has "DRL 里是前缀匹配（整串匹配所以要带 .*）" "$DRL" 'matches "^SKU.*"'

echo "  命中用例（编码 SKU-9003 以 SKU 开头）:"
R=$(curl -s -m 15 -X POST -H "$J" -d '{"bizId":"SKU-9003","skuCode":"SKU-9003","skuName":"羊毛衫","price":100,"cost":65,"品类":"家居"}' "$B/rule/evaluate?docCode=SKU")
has "codeHit=Y" "$R" '"codeHit":"Y"'
echo "  不命中用例（编码 X-9A01 不以 SKU 开头）:"
R=$(curl -s -m 15 -X POST -H "$J" -d '{"bizId":"X-1","skuCode":"X-9A01","skuName":"羊毛衫","price":100,"cost":65,"品类":"家居"}' "$B/rule/evaluate?docCode=SKU")
has "codeHit 不出现" "$(echo "$R" | grep -c 'codeHit')" "0"
echo "  引擎: $(curl -s -m 10 $B/rule/engine/info)"
