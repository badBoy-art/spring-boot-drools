#!/bin/bash
# 页面 ①~⑥ 全链路验收：单据注册 → 接口注册 → 规则类型 → 规则配置 → 试算 → 清理
B=http://localhost:8080
J='Content-Type: application/json'
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ✅ $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ❌ $1"; }
has()  { echo "$2" | grep -qF -- "$3" && ok "$1" || bad "$1 （实际：$(echo "$2" | head -c 200)）"; }

echo "===== ① 单据注册：新建单据 SKU + 对象 + 字段 ====="
R=$(curl -s -m 15 -X POST -H "$J" -d '{"docCode":"TESTDOC","docName":"商品","remark":"商品主数据（验收脚本创建）"}' $B/rule/doc/save)
has "新建单据 SKU" "$R" '"docCode":"TESTDOC"'
R=$(curl -s -m 15 -X POST -H "$J" -d '{"docCode":"TESTDOC","objectKey":"sku","objectName":"商品","valuePath":""}' $B/rule/doc/object/save)
has "新建对象 sku" "$R" '"objectKey":"sku"'
R=$(curl -s -m 15 -X POST -H "$J" -d '{"docCode":"TESTDOC","objectKey":"sku","fieldName":"商品编码","fieldKey":"skuCode","fieldType":"STRING","exampleValue":"SKU-1"}' $B/rule/doc/field/save)
has "新建字段 skuCode" "$R" '"fieldKey":"skuCode"'
R=$(curl -s -m 15 -X POST -H "$J" -d '{"docCode":"TESTDOC","objectKey":"sku","fieldName":"售价","fieldKey":"price","fieldType":"NUMBER","exampleValue":"800"}' $B/rule/doc/field/save)
has "新建字段 price" "$R" '"fieldKey":"price"'
R=$(curl -s -m 15 -X POST -H "$J" -d '{"docCode":"TESTDOC","objectKey":"不存在的对象","fieldName":"x","fieldKey":"x"}' $B/rule/doc/field/save)
has "字段挂到不存在的对象 → 拦下" "$R" '没有对象'

echo "===== ② 接口注册：新建'单据接口'动作 ====="
R=$(curl -s -m 15 -X POST -H "$J" -d '{"actionCode":"SKU_SYNC","actionName":"商品同步","actionCategory":"单据接口","docCode":"TESTDOC","method":"POST","domainKey":"mock","path":"/sku/sync","bodyTemplate":"{\"skuCode\":\"${skuCode}\",\"price\":${price}}","timeoutMs":2000}' $B/rule/http/action/save)
has "新建接口 SKU_SYNC" "$R" '"actionCode":"SKU_SYNC"'
R=$(curl -s -m 15 -X POST -H "$J" -d '[{"respPath":"data.skuId","targetField":"skuId","targetType":"STRING","asMessage":1,"sortOrder":1}]' $B/rule/http/action/SKU_SYNC/returns)
has "配返回值映射" "$R" 'data.skuId'
R=$(curl -s -m 15 -X POST -H "$J" -d '{"actionCode":"BAD_ALGO","actionName":"非法算法","docCode":"TESTDOC","domainKey":"mock","path":"/x","reqEncrypt":"AES_ECB"}' $B/rule/http/action/save)
has "加密算法非白名单 → 拦下" "$R" '不支持的请求加密算法'
R=$(curl -s -m 15 -X POST -H "$J" -d '{"actionCode":"BAD_AUTH","actionName":"认证不存在","docCode":"TESTDOC","domainKey":"mock","path":"/x","authCode":"NOPE"}' $B/rule/http/action/save)
has "认证不存在 → 拦下" "$R" '认证配置不存在'

echo "===== ③ 规则类型：按 SKU.price + SKU_SYNC 生成 ====="
R=$(curl -s -m 15 -X POST -H "$J" -d '{"ruleType":"SKU_SYNC_PUSH","typeName":"高价商品同步","ruleGroup":"action","sortOrder":91,"factClass":"DocFact","docCode":"TESTDOC","mode":"ACTION","fieldPath":"price","operator":">=","valueType":"NUMBER","valueParamKey":"threshold","defaultValue":"500","actionCode":"SKU_SYNC"}' $B/rule/type/build)
has "生成模板（判定字段写进 DRL）" "$R" 'getNumber(\"price\")'
has "生成的参数含 actionCode/threshold" "$R" '"fieldKey":"threshold"'
R=$(curl -s -m 15 -X POST -H "$J" -d '{"ruleType":"SKU_SYNC_PUSH","typeName":"高价商品同步","ruleGroup":"action","sortOrder":91,"factClass":"DocFact","docCode":"TESTDOC","mode":"ACTION","fieldPath":"price","operator":">=","valueType":"NUMBER","valueParamKey":"threshold","defaultValue":"500","actionCode":"SKU_SYNC"}' $B/rule/type/save)
has "保存规则类型" "$R" '"ruleType":"SKU_SYNC_PUSH"'
R=$(curl -s -m 15 -X POST -H "$J" -d '{"ruleType":"VIP_DISCOUNT","typeName":"改内置","factClass":"DocFact","docCode":"TESTDOC","mode":"ACTION","fieldPath":"price","actionCode":"SKU_SYNC"}' $B/rule/type/save)
has "内置类型不允许覆盖 → 拦下" "$R" '内置类型不允许覆盖'
R=$(curl -s -m 15 -X POST -H "$J" -d '{"ruleType":"BAD_DOC_TYPE","typeName":"单据未注册","factClass":"DocFact","docCode":"NOT_EXIST","mode":"ACTION","fieldPath":"x","actionCode":"SKU_SYNC"}' $B/rule/type/build)
has "单据未注册 → 拦下" "$R" '单据未注册'

echo "===== ④ 规则配置：新类型下建规则并发布 ====="
R=$(curl -s -m 15 -X POST -H "$J" -d '{"ruleName":"SKU_SYNC_PUSH_500","ruleType":"SKU_SYNC_PUSH","ruleParams":"{\"actionCode\":\"SKU_SYNC\",\"threshold\":500}"}' $B/rule/create)
RID=$(echo "$R" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
has "新建规则" "$R" '"ruleType":"SKU_SYNC_PUSH"'
R=$(curl -s -m 15 -X POST -H "$J" -d '{"actionCode":"SKU_SYNC","threshold":500}' $B/rule/publish/$RID)
has "发布规则（生成 DRL）" "$R" 'getNumber(\"price\") >= 500'
echo "  引擎: $(curl -s -m 10 $B/rule/engine/info)"

echo "===== ⑤ 试算：SKU 单据走规则 → 调接口 → 回填 ====="
curl -s -m 15 -X DELETE $B/mock/calls > /dev/null
R=$(curl -s -m 15 -X POST -H "$J" -d '{"skuCode":"SKU-1","price":800}' "$B/rule/evaluate?docCode=TESTDOC")
has "单据 SKU 点火" "$R" '"fired":'
has "接口真被调用并回填 skuId（真实值，不是 null）" "$R" '"skuId":"SKU-ID-'
echo "  被调方: $(curl -s -m 10 $B/mock/calls | head -c 200)"
R=$(curl -s -m 15 -X POST -H "$J" -d '{"skuCode":"SKU-2","price":300}' "$B/rule/evaluate?docCode=TESTDOC")
has "未达阈值不点火" "$R" '"fired":0'

echo "===== 清理（删规则 → 删类型 → 停用接口/单据留在库里便于查看） ====="
R=$(curl -s -m 15 -X POST "$B/rule/type/delete?ruleType=SKU_SYNC_PUSH")
has "有规则时不允许删类型" "$R" '还有 1 条规则'
curl -s -m 15 -o /dev/null -X DELETE $B/rule/$RID
R=$(curl -s -m 15 -X POST "$B/rule/type/delete?ruleType=SKU_SYNC_PUSH")
has "删规则后删类型成功" "$R" '已删除规则类型'
mysql -h127.0.0.1 -uroot -pzhaoZ1230 -N -e "delete from test.rule_document_field where doc_code='TESTDOC'; delete from test.rule_document_object where doc_code='TESTDOC'; delete from test.rule_document where doc_code='TESTDOC'; delete from test.rule_http_action_return where action_code='SKU_SYNC'; delete from test.rule_http_action where action_code='SKU_SYNC'; delete from test.rule_http_action where action_code in ('BAD_ALGO','BAD_AUTH');" 2>/dev/null
echo "  清理完成；引擎: $(curl -s -m 10 $B/rule/engine/info)"

echo
echo "===== 结果：PASS=$PASS  FAIL=$FAIL ====="
[ "$FAIL" = "0" ] && echo "全部通过" || echo "有失败项，请看上面 ❌"
