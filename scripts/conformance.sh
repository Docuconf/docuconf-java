#!/usr/bin/env bash
# Runs only the docuconf-go-facing tests against a given docuconf-go checkout:
# the shared conformance suite (ConformanceTest) and the tests that `cue vet`
# exported contracts against its meta-schema (ContractCoreTest,
# DocuconfProcessorTest, GoldenContractTest, OverlayTest). Not the full suite.
#
#   DOCUCONF_GO_DIR=/path/to/docuconf-go scripts/conformance.sh
#
# Needs JDK 17+, Maven 3.9+ and cue on PATH. Extra Maven arguments go in
# MAVEN_ARGS (e.g. -Dspring-boot.version=4.1.1). docuconf-go's downstream
# workflow and this repository's CI both call it.
set -euo pipefail

: "${DOCUCONF_GO_DIR:?set DOCUCONF_GO_DIR to a docuconf-go checkout}"
DOCUCONF_GO_DIR="$(cd "$DOCUCONF_GO_DIR" && pwd)"
export DOCUCONF_GO_DIR
export DOCUCONF_CONFORMANCE="${DOCUCONF_CONFORMANCE:-$DOCUCONF_GO_DIR/conformance/cases.json}"
export DOCUCONF_SPEC_CUE="${DOCUCONF_SPEC_CUE:-$DOCUCONF_GO_DIR/spec/cue}"
export DOCUCONF_REQUIRE_CONFORMANCE=1
export DOCUCONF_REQUIRE_VET=1

cd "$(dirname "$0")/.."
# verify, not test: the processor, Spring and sample modules use docuconf-core's
# test-jar. Nothing is installed or deployed.
mvn -B -ntp verify \
  -pl docuconf-core,docuconf-processor,docuconf-spring,docuconf-sample \
  -Dtest='ConformanceTest,ContractCoreTest,DocuconfProcessorTest,GoldenContractTest,OverlayTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
