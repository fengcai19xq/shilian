#!/usr/bin/env bash
# 测试环境冒烟（BFF 统一入口）：健康检查 + 会话注入后的检索链路。
#
# 权限矩阵（越权部门/项目/密级必须 total=0）由 services/bff/tests 与现场验证脚本覆盖；
# 本脚本在测试环境使用 BFF 的服务端固定主体，无法在一次运行里切换部门。
#
# 用法：./smoke.sh [web_base]
set -euo pipefail
BASE="${1:-http://127.0.0.1:${WEB_PORT:-8088}}"
ok() { printf '  [ok] %s\n' "$1"; }

curl -fsS "$BASE/api/healthz" >/dev/null && ok "bff"

search() {
  curl -fsS "$BASE/api/retrieval/search" -H 'Content-Type: application/json' -d "$1"
}
Q="${SMOKE_QUERY:-授信}"
fin=$(search "{\"query\":\"$Q\",\"kb_scope\":[\"kb_finance\"],\"mode\":\"probe\"}")
total=$(echo "$fin" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("total", -1))')
[ "$total" -ge 0 ] && ok "BFF 注入会话后检索可用（total=$total）" || { echo "  [FAIL] 检索返回异常"; exit 1; }
