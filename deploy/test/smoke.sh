#!/usr/bin/env bash
# 测试环境冒烟：各服务健康 + 权限前置过滤（无权部门必须 total=0）。用法：./smoke.sh [web_base]
set -euo pipefail
BASE="${1:-http://127.0.0.1:${WEB_PORT:-8088}}"
ok() { printf '  [ok] %s\n' "$1"; }

curl -fsS "$BASE/api/decide/healthz" >/dev/null && ok "decide"
curl -fsS "$BASE/api/gateway/healthz" >/dev/null && ok "gateway"
curl -fsS "$BASE/api/ingest/documents" >/dev/null && ok "ingest"

search() {
  curl -fsS "$BASE/api/retrieval/search" -H 'Content-Type: application/json' -d "$1"
}
P_FIN='{"user_id":"u_fin","dept":["融资部"],"roles":[],"max_sensitivity":"L2","projects":[]}'
P_HR='{"user_id":"u_hr","dept":["人事部"],"roles":[],"max_sensitivity":"L2","projects":[]}'
Q="${SMOKE_QUERY:-授信}"
fin=$(search "{\"query\":\"$Q\",\"principal\":$P_FIN,\"kb_scope\":[\"kb_finance\"],\"mode\":\"probe\"}")
hr=$(search "{\"query\":\"$Q\",\"principal\":$P_HR,\"kb_scope\":[\"kb_finance\"],\"mode\":\"probe\"}")
echo "  融资部 total=$(echo "$fin" | python3 -c 'import sys,json;print(json.load(sys.stdin)["total"])')"
hr_total=$(echo "$hr" | python3 -c 'import sys,json;print(json.load(sys.stdin)["total"])')
[ "$hr_total" = "0" ] && ok "人事部越权检索 total=0" || { echo "  [FAIL] 人事部检索到 $hr_total 条"; exit 1; }
