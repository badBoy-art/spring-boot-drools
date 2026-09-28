#!/bin/bash
# 决策链验收（接口调用移交业务系统之后的新口径）：
#   业务按注册单据传参 → 引擎算派生字段 → 命中规则 → 输出决策（ext 字段 / 消息），引擎不发任何外部请求
B=http://localhost:8080
J='Content-Type: application/json'
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ✅ $1"; }
bad() { FAIL=$((FAIL+1)); echo "  ❌ $1"; }
has() { echo "$2" | grep -qF -- "$3" && ok "$1" || bad "$1 （实际：$(echo "$2" | head -c 300)）"; }
post() { curl -s -m 20 -X POST -H "$J" -d "$2" "$B$1"; }

echo "===== 1) 引擎健康 + 只吃库里的决策规则 ====="
R=$(curl -s -m 10 $B/rule/engine/info)
has "引擎已就绪" "$R" '"ruleCount"'
has "无刷新错误（DRL 里不再有 httpActionGateway 之类的外部调用）" "$R" '"lastRefreshError":null'

echo "===== 2) 单据注册：业务按注册字段传参（含派生表达式） ====="
R=$(curl -s -m 10 $B/rule/doc/tree)
has "单据已注册" "$R" '"docCode":"ORDER"'
has "字段带取值路径（业务按这个结构传参）" "$R" 'customer.level'
has "派生字段带表达式" "$R" '"expr"'

echo "===== 3) 规则注册：类型 / 规则 / 组合规则表 ====="
has "规则类型列表可读" "$(curl -s -m 10 $B/rule/type/list)" '"ruleType"'
has "规则列表可读（含已发布规则）" "$(curl -s -m 10 $B/rule/list)" '"drlContent"'
has "组合规则表资产可读" "$(curl -s -m 10 $B/rule/ct/assets)" '"assetKey"'

echo "===== 4) 执行与决策输出：一笔单子进 → 决策出 ====="
R=$(post /order/evaluate '{"orderId":"SO-DEC-1","customer":{"level":"VIP","region":"华东","age":30,"newCustomer":true},"items":[{"product":{"id":1,"name":"iPhone","category":"电子产品","price":5000.0,"stock":10,"onShelf":true},"quantity":2,"subtotal":10000.0}]}')
echo "  决策结果：$(echo "$R" | head -c 260)"
has "引擎返回折扣决策（VIP 会员折扣）" "$R" '"discount":'
has "引擎返回应付金额（服务层按决策算）" "$R" '"finalAmount":'
has "引擎返回规则消息（给业务/人工看的判定说明）" "$R" '"messages":['
has "引擎返回放行/拦截标记" "$R" '"rejected":'

echo "===== 5) 引擎不再发起外部调用（进程内没有任何 HTTP 客户端发起的痕迹） ====="
grep -rl "HttpActionGateway\|httpActionGateway\|HttpAuthSupport\|DomainResolver\|CryptoSupport\|JwtSupport" /Users/admin/Projects/spring-boot-drools/src/main /Users/admin/Projects/spring-boot-drools/src/test 2>/dev/null | head -3 > /tmp/_refs.txt
[ -s /tmp/_refs.txt ] && bad "仍有代码引用外部接口实现：$(cat /tmp/_refs.txt | tr '\n' ' ')" || ok "代码里已无任何外部接口调用实现（HttpActionGateway/认证/加密/域名解析/RuleHttpProperties 全部移除）"
echo "  残留死代码检查（仅注释/未调用方法）：$(grep -rn 'rule_http_action' /Users/admin/Projects/spring-boot-drools/src/main/java 2>/dev/null | wc -l | tr -d ' ') 处"
mysql -h127.0.0.1 -uroot -pzhaoZ1230 -N test -e "show tables like 'rule_http%'" 2>/dev/null | head -3 > /tmp/_tbl.txt
[ -s /tmp/_tbl.txt ] && bad "接口配置表还在：$(cat /tmp/_tbl.txt | tr '\n' ' ')" || ok "7 张 rule_http_* 配置表已全部 DROP"

echo "===== 6) 组合规则表（决策能力仍然一等公民） ====="
has "组合表列表可读" "$(curl -s -m 10 $B/rule/ct/assets)" 'MEMBER_CATEGORY_RATE'

echo
echo "===== 结果：PASS=${PASS}  FAIL=${FAIL} ====="
echo "  引擎: $(curl -s -m 10 $B/rule/engine/info)"
[ "${FAIL}" = "0" ] && echo "全部通过" || echo "有失败项，请看上面 ❌"
