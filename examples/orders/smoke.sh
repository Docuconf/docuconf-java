#!/usr/bin/env bash
# Smoke test of the orders example: a valid start serves /healthz and a redacted /config, and an invalid one
# exits non-zero naming every problem. Then the webhook key set: an empty key stops startup without printing it,
# and mid-rotation a webhook signed with either key is accepted. Build the jar first (see README.md). Needs java,
# curl and openssl.
set -euo pipefail

cd "$(dirname "$0")"
jar=${1:-target/orders.jar}
port=${SMOKE_PORT:-18080}
secret='smoke-test-password'
# Two webhook keys: the old one and, mid-rotation, the new one.
old_key='old-webhook-key-0123456789abcdef0123'
new_key='new-webhook-key-0123456789abcdef0123'
log=$(mktemp)
pid=
trap '[ -n "$pid" ] && kill "$pid" 2>/dev/null; rm -f "$log"' EXIT

wait_for_healthz() {
  health=
  for _ in $(seq 1 60); do
    if health=$(curl -sf "http://localhost:$port/healthz"); then break; fi
    kill -0 "$pid" 2>/dev/null || { cat "$log"; echo "FAIL: the app exited"; exit 1; }
    sleep 1
  done
  [ "${health:-}" = ok ] || { cat "$log"; echo "FAIL: /healthz returned '${health:-}'"; exit 1; }
}

echo "== valid configuration"
ORDERS_PORT=$port ORDERS_DATABASEURL="postgres://orders:$secret@localhost:5432/orders" \
  WEBHOOK_KEYS="$old_key,$new_key" java -jar "$jar" >"$log" 2>&1 &
pid=$!
wait_for_healthz
config=$(curl -sf "http://localhost:$port/config")
echo "/config: $config"
if grep -q -e "$secret" -e webhook-key <<<"$config" || grep -q -e "$secret" -e webhook-key "$log"; then
  echo "FAIL: a secret shows in /config or the log"; exit 1
fi
grep -q '"webhookKeys":"\*\*\*"' <<<"$config" || { echo "FAIL: /config does not redact webhookKeys"; exit 1; }
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'X-Signature: 00' -d '{}' "http://localhost:$port/webhooks/payments")
[ "$code" = 401 ] || { echo "FAIL: an unsigned webhook got $code, want 401"; exit 1; }
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

echo "== a key set with an empty second key (a trailing comma)"
set +e
ORDERS_PORT=$port ORDERS_DATABASEURL="postgres://orders:$secret@localhost:5432/orders" WEBHOOK_KEYS="$old_key," \
  java -jar "$jar" >"$log" 2>&1
status=$?
set -e
[ "$status" -eq 1 ] || { cat "$log"; echo "FAIL: exit status $status, not 1"; exit 1; }
grep -qx '    \[out_of_range\] WEBHOOK_KEYS: key 2 is empty' "$log" ||
  { cat "$log"; echo "FAIL: no out_of_range for the empty key"; exit 1; }
if grep -q webhook-key "$log"; then echo "FAIL: the output shows a key"; exit 1; fi
grep '^    \[' "$log"

echo "== mid-rotation: either key is accepted, any other rejected"
ORDERS_PORT=$port ORDERS_DATABASEURL="postgres://orders:$secret@localhost:5432/orders" \
  WEBHOOK_KEYS="$old_key,$new_key" java -jar "$jar" >"$log" 2>&1 &
pid=$!
wait_for_healthz
body='{"order":"42","status":"paid"}'
for key in "$old_key" "$new_key" "other-webhook-key-0123456789abcdef"; do
  sig=$(printf '%s' "$body" | openssl dgst -sha256 -hmac "$key" | sed 's/.*= //')
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "X-Signature: $sig" -H 'Content-Type: application/json' \
    -d "$body" "http://localhost:$port/webhooks/payments")
  want=204; [ "${key#other}" != "$key" ] && want=401
  [ "$code" = "$want" ] || { echo "FAIL: webhook signed with the ${key%%-*} key got $code, want $want"; exit 1; }
done
echo "webhooks: old and new key accepted, any other rejected"
kill "$pid"
wait "$pid" 2>/dev/null || true
pid=
echo "PASS"
