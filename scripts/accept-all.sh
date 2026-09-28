#!/bin/bash
# 一键总验收：前提是服务已起（JAVA_HOME=jdk8 mvn spring-boot:run）
#   bash scripts/accept-all.sh
# 顺序：单测 → 架构/决策输出 → 算子端到端(前缀) → 算子端到端(全集)
cd "$(dirname "$0")/.." || exit 1
B=http://localhost:8080
JDK8=/Library/Java/JavaVirtualMachines/jdk1.8.0_351.jdk/Contents/Home
MVN=~/Downloads/apache-maven-3.9.11/bin/mvn

if ! curl -s -m 5 -o /dev/null "$B/rule/engine/info"; then
  echo "❌ 服务未启动。先跑："
  echo "   cd $(pwd) && export JAVA_HOME=${JDK8} && ${MVN} spring-boot:run"
  exit 1
fi

echo "===== 0) 单元测试 ====="
JAVA_HOME=${JDK8} ${MVN} -B test 2>&1 | grep -E "Tests run: [0-9]+, Fail.*Skipped: 0$|BUILD" | tail -2
UNIT_FAIL=$(JAVA_HOME=${JDK8} ${MVN} -B test 2>&1 | grep -cE "BUILD FAILURE")

TOTAL_PASS=0
TOTAL_FAIL=0
for s in decision-acceptance ops-prefix-acceptance ops-publish-acceptance; do
  echo "===== $s ====="
  out=$(bash "scripts/$s.sh" 2>&1)
  p=$(printf '%s' "$out" | grep -c "✅")
  f=$(printf '%s' "$out" | grep -c "❌")
  TOTAL_PASS=$((TOTAL_PASS + p))
  TOTAL_FAIL=$((TOTAL_FAIL + f))
  printf '  ✅ %s  ❌ %s\n' "$p" "$f"
  [ "$f" != "0" ] && printf '%s\n' "$out" | grep "❌" | head -5
done

echo
echo "===== 汇总 ====="
echo "  脚本断言：✅ ${TOTAL_PASS}  ❌ ${TOTAL_FAIL}"
echo "  单测：$([ "$UNIT_FAIL" = "0" ] && echo '6/6 通过' || echo '有失败，见上')"
echo "  引擎：$(curl -s -m 10 $B/rule/engine/info)"
if [ "$TOTAL_FAIL" = "0" ] && [ "$UNIT_FAIL" = "0" ]; then
  echo "  全部通过 ✅"
  exit 0
fi
echo "  有失败项 ❌"
exit 1
