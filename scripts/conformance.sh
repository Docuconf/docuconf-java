#!/usr/bin/env bash
# Runs only the docuconf-go-facing tests against a given docuconf-go checkout:
# the shared conformance suite (ConformanceTest), which runs every case and
# fails if any is skipped; the shared export check (ExportConformanceTest),
# which runs `docuconf conformance export` on the export fixture; and the
# tests that `cue vet` exported contracts against its meta-schema
# (ContractCoreTest, DocuconfProcessorTest, GoldenContractTest, OverlayTest).
# Not the full suite.
#
#   DOCUCONF_GO_DIR=/path/to/docuconf-go scripts/conformance.sh
#
# Needs JDK 17+, Maven 3.9+ and cue on PATH, and the docuconf CLI: DOCUCONF_CLI,
# else docuconf on PATH, else it is built from $DOCUCONF_GO_DIR with go. Extra
# Maven arguments go in MAVEN_ARGS (e.g. -Dspring-boot.version=4.1.1).
# docuconf-go's downstream workflow and this repository's CI both call it.
set -euo pipefail

: "${DOCUCONF_GO_DIR:?set DOCUCONF_GO_DIR to a docuconf-go checkout}"
DOCUCONF_GO_DIR="$(cd "$DOCUCONF_GO_DIR" && pwd)"
export DOCUCONF_GO_DIR
export DOCUCONF_CONFORMANCE="${DOCUCONF_CONFORMANCE:-$DOCUCONF_GO_DIR/conformance/cases.json}"
export DOCUCONF_SPEC_CUE="${DOCUCONF_SPEC_CUE:-$DOCUCONF_GO_DIR/spec/cue}"
export DOCUCONF_REQUIRE_CONFORMANCE=1
export DOCUCONF_REQUIRE_VET=1

if [ -z "${DOCUCONF_CLI:-}" ]; then
  if command -v docuconf >/dev/null 2>&1; then
    DOCUCONF_CLI="$(command -v docuconf)"
  else
    cli_dir="$(mktemp -d)"
    trap 'rm -rf "$cli_dir"' EXIT
    (cd "$DOCUCONF_GO_DIR/cmd/docuconf" && go build -o "$cli_dir/docuconf" .)
    DOCUCONF_CLI="$cli_dir/docuconf"
  fi
fi
export DOCUCONF_CLI

cd "$(dirname "$0")/.."
# verify, not test: the processor, Spring and sample modules use docuconf-core's
# test-jar. Nothing is installed or deployed.
mvn -B -ntp verify \
  -pl docuconf-core,docuconf-processor,docuconf-spring,docuconf-sample \
  -Dtest='ConformanceTest,ExportConformanceTest,ContractCoreTest,DocuconfProcessorTest,GoldenContractTest,OverlayTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
