#!/usr/bin/env zsh
# 跑 armada 真库 DbTest:从 armada-api/.env(gitignored)注入 DB creds 到 mvn 进程,creds 不回显、不入仓。
# 用法: armada-api/dbtest.sh <TestClass#method 或 模式> [额外 mvn 参数...]
set -e
HERE="${0:A:h}"
TEST="${1:?用法: dbtest.sh <TestClass#method 或 模式>}"
shift || true
set -a; source "$HERE/.env"; set +a
cd "$HERE"
exec mvn -q -Dtest="$TEST" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test "$@"
