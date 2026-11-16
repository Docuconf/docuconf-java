#!/usr/bin/env bash
# Checks the shop example's contract against the meta-schema of a docuconf-go checkout: `cue vet -c` of contract.cue
# and of contract.json, and that the two are the same contract once the meta-schema's defaults are applied (the app
# loads contract.json, so an edit to one file only fails here). Needs cue and jq.
#
#   examples/shop/check-contract.sh <docuconf-go>/spec/cue
set -euo pipefail

spec=$(cd "${1:?usage: check-contract.sh <docuconf-go>/spec/cue}" && pwd)
cd "$(dirname "$0")"
here=$(pwd)
cue=${CUE:-cue}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp -r "$spec/cue.mod" "$spec/contract" "$work"/
mkdir "$work/svc"
cp contract.cue "$work/svc/"
cd "$work"
"$cue" vet -c ./svc
"$cue" vet -c -d '#Contract' ./contract "$here/contract.json"
diff -u <("$cue" export ./svc | jq -S .) <("$cue" export -d '#Contract' ./contract "$here/contract.json" | jq -S .) ||
  { echo "FAIL: contract.cue and contract.json are different contracts"; exit 1; }
echo "contract.cue and contract.json: vetted, and the same contract"
