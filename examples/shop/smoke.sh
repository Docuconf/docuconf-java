#!/usr/bin/env bash
# Smoke test of the shop example: runs the real jar for cases 1, 2 and 6 of the README's matrix, each in a clean
# environment with a new, empty file root, and checks standard output, standard error and the exit status. Build the
# jar first (see README.md). Needs java.
set -euo pipefail

cd "$(dirname "$0")"
jar=${1:-target/shop.jar}
db='postgres://shop:smoke-test-password@db:5432/shop'
root=$(mktemp -d)
out=$(mktemp)
err=$(mktemp)
trap 'rm -rf "$root" "$out" "$err"' EXIT

# run <expected status> <expected stdout> VAR=value...: runs the app with the base environment plus these variables.
run() {
  local want_status=$1 want_out=$2 status=0
  shift 2
  env -i PATH="$PATH" DOCUCONF_FILE_ROOT="$root" DATABASE_URL="$db" "$@" java -jar "$jar" >"$out" 2>"$err" ||
    status=$?
  if [ "$status" -ne "$want_status" ] || [ "$(cat "$out")" != "$want_out" ]; then
    echo "stdout:"; cat "$out"; echo "stderr:"; cat "$err"
    echo "FAIL: exit status $status (want $want_status), stdout above (want '$want_out')"; exit 1
  fi
  if grep -q smoke-test-password "$out" "$err"; then echo "FAIL: the output shows the database password"; exit 1; fi
}

echo "== 1. web"
run 0 'shop web: port 8080, origins https://shop.example' DOCUCONF_ROLE=web ALLOWED_ORIGINS=https://shop.example
cat "$out"

echo "== 2. worker, which needs no ALLOWED_ORIGINS"
run 0 'shop worker: queues default,mail, webhooks off' DOCUCONF_ROLE=worker QUEUES=default,mail
cat "$out"

echo "== 6. JSON logs with no LOG_SCHEMA_URL"
run 1 '' DOCUCONF_ROLE=worker QUEUES=default LOG_FORMAT=json
grep -qx 'docuconf: 1 configuration problem:' "$err" || { cat "$err"; echo "FAIL: no summary line"; exit 1; }
grep -qx '  \[missing_required\] LOG_SCHEMA_URL: is required when LOG_FORMAT is "json"' "$err" ||
  { cat "$err"; echo "FAIL: no missing_required for LOG_SCHEMA_URL"; exit 1; }
if grep -q '^\s*at ' "$err"; then cat "$err"; echo "FAIL: a stack trace was printed"; exit 1; fi
cat "$err"
echo "PASS"
