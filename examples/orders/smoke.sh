#!/usr/bin/env bash
# Smoke test of the orders example: a valid start serves /healthz and a redacted /config, and an invalid one
# exits non-zero naming every problem. Build the jar first (see README.md).
set -euo pipefail

cd "$(dirname "$0")"
jar=${1:-target/orders.jar}
port=${SMOKE_PORT:-18080}
secret='smoke-test-password'
log=$(mktemp)
pid=
trap '[ -n "$pid" ] && kill "$pid" 2>/dev/null; rm -f "$log"' EXIT

echo "== valid configuration"
ORDERS_PORT=$port ORDERS_DATABASEURL="postgres://orders:$secret@localhost:5432/orders" \
  java -jar "$jar" >"$log" 2>&1 &
pid=$!
for _ in $(seq 1 60); do
  if health=$(curl -sf "http://localhost:$port/healthz"); then break; fi
  kill -0 "$pid" 2>/dev/null || { cat "$log"; echo "FAIL: the app exited"; exit 1; }
  sleep 1
done
[ "${health:-}" = ok ] || { cat "$log"; echo "FAIL: /healthz returned '${health:-}'"; exit 1; }
config=$(curl -sf "http://localhost:$port/config")
echo "/config: $config"
if grep -q "$secret" <<<"$config"; then echo "FAIL: /config shows the secret"; exit 1; fi
kill "$pid"
wait "$pid" 2>/dev/null || true
pid=

echo "== PORT=0 and no DATABASE_URL"
set +e
env -u ORDERS_DATABASEURL ORDERS_PORT=0 java -jar "$jar" >"$log" 2>&1
status=$?
set -e
[ "$status" -ne 0 ] || { cat "$log"; echo "FAIL: the app started"; exit 1; }
for code in missing_required out_of_range; do
  grep -q "\[$code\]" "$log" || { cat "$log"; echo "FAIL: no $code in the output"; exit 1; }
done
grep '^    \[' "$log"
echo "PASS"
