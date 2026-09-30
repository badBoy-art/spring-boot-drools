#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
args=(verify)
if [[ -n "${DROOLS_TEST_MYSQL_URL:-}" ]]; then
  : "${DROOLS_TEST_MYSQL_USERNAME:?Set a dedicated test database user}"
  : "${DROOLS_TEST_MYSQL_PASSWORD:?Set the test database password}"
  args+=("-Ddrools.test.mysql.url=$DROOLS_TEST_MYSQL_URL" "-Ddrools.test.mysql.username=$DROOLS_TEST_MYSQL_USERNAME" "-Ddrools.test.mysql.password=$DROOLS_TEST_MYSQL_PASSWORD")
fi
exec mvn "${args[@]}"
