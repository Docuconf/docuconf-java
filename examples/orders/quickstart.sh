#!/usr/bin/env bash
# Runs the commands of the SDK README's quickstart as written, in this example. CI runs it, and
# ReadmeSnippetsTest checks that every command in the README appears here or in .github/workflows/ci.yml.
# The SDK must be installed first (mvn install -DskipTests from the repository root).
set -euo pipefail
cd "$(dirname "$0")"
export ORDERS_PORT=${SMOKE_PORT:-18081}
log=$(mktemp)
pid=
trap '[ -n "$pid" ] && kill "$pid" 2>/dev/null; rm -f "$log"' EXIT

echo "== Run"
mvn package
ORDERS_DATABASEURL='postgres://orders:secret@localhost:5432/orders' java -jar target/orders.jar >"$log" 2>&1 &
pid=$!
for _ in $(seq 1 60); do
  curl -sf "http://localhost:$ORDERS_PORT/healthz" >/dev/null && break
  kill -0 "$pid" 2>/dev/null || { cat "$log"; echo "FAIL: the app exited"; exit 1; }
  sleep 1
done
kill "$pid"
wait "$pid" 2>/dev/null || true
pid=

echo "== See an error"
status=0
ORDERS_PORT=0 java -jar target/orders.jar >"$log" 2>&1 || status=$?
[ "$status" -eq 1 ] || { cat "$log"; echo "FAIL: exit status $status, not 1"; exit 1; }
grep -q '^docuconf: 2 configuration problems:$' "$log" || { cat "$log"; echo "FAIL: no summary line"; exit 1; }
if grep -q '^\s*at ' "$log"; then cat "$log"; echo "FAIL: a stack trace was printed"; exit 1; fi

echo "== Export"
cp contract.cue "$log"
mvn docuconf:export
diff -u "$log" contract.cue || { echo "FAIL: the committed contract.cue was not the exported one"; exit 1; }
mvn verify
echo "PASS"
