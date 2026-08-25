#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SUBJECT="${SCRIPT_DIR}/preflight.sh"
FIXTURE_DEPLOY="${SCRIPT_DIR}/preflight-fixture-deploy.sh"
SAMPLE_MANIFEST="${SCRIPT_DIR}/preflight-manifest.sample.json"
BACKEND_SHA=1111111111111111111111111111111111111111
FRONTEND_SHA=2222222222222222222222222222222222222222
WEB_PROTOCOL_SHA=3333333333333333333333333333333333333333
ANDROID_PROTOCOL_SHA=4444444444444444444444444444444444444444

TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/staging-accept-preflight-test.XXXXXX")"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT
export PREFLIGHT_FIXTURE_LOG="${TEST_ROOT}/deploy.log"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_status() {
  local expected="$1"
  [ "${RUN_STATUS}" -eq "${expected}" ] \
    || fail "expected exit ${expected}, got ${RUN_STATUS}; output=${RUN_OUTPUT}"
}

assert_contains() {
  case "$1" in
    *"$2"*) ;;
    *) fail "expected output to contain '$2'; output=$1" ;;
  esac
}

assert_file_line() {
  local expected="$2"
  local actual=""
  [ -f "$1" ] && actual="$(sed -n '1p' "$1")"
  [ "${actual}" = "${expected}" ] \
    || fail "expected $1 first line '$expected', got '$actual'"
}

run_capture() {
  set +e
  RUN_OUTPUT="$("$@" 2>&1)"
  RUN_STATUS=$?
  set -e
}

manifest="${TEST_ROOT}/manifest.json"
python3 - "${SAMPLE_MANIFEST}" "${manifest}" <<'PY'
import datetime as dt
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    manifest = json.load(source)
manifest["generatedAt"] = dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")
for name, component in manifest["components"].items():
    artifacts = component["artifacts"] if name == "androidProtocol" else [component["artifact"]]
    for artifact in artifacts:
        artifact["observedAt"] = manifest["generatedAt"]
with open(sys.argv[2], "w", encoding="utf-8") as target:
    json.dump(manifest, target)
PY
EXPECTED_ARGS=(
  --backend-sha "${BACKEND_SHA}"
  --frontend-sha "${FRONTEND_SHA}"
  --web-protocol-sha "${WEB_PROTOCOL_SHA}"
  --android-protocol-sha "${ANDROID_PROTOCOL_SHA}"
  --max-age-seconds 300
  --android-role coordinator
  --android-role node-01
  --android-role node-02
  --android-role node-03
)

: >"${PREFLIGHT_FIXTURE_LOG}"
run_capture "${SUBJECT}" all \
  --env test1 \
  --deploy-script "${FIXTURE_DEPLOY}" \
  --manifest "${manifest}" \
  "${EXPECTED_ARGS[@]}"
assert_status 0
assert_contains "${RUN_OUTPUT}" "CHECK deep-check PASS"
assert_contains "${RUN_OUTPUT}" "VERSION backend PASS"
assert_contains "${RUN_OUTPUT}" "VERSION androidProtocol PASS"
assert_contains "${RUN_OUTPUT}" "RESULT PASS"
assert_file_line "${PREFLIGHT_FIXTURE_LOG}" "--env test1 --check"

: >"${PREFLIGHT_FIXTURE_LOG}"
run_capture "${SUBJECT}" deep-check --env perf2 --deploy-script "${FIXTURE_DEPLOY}"
assert_status 0
assert_contains "${RUN_OUTPUT}" "CHECK deep-check PASS"
assert_file_line "${PREFLIGHT_FIXTURE_LOG}" "--env perf2 --check"

: >"${PREFLIGHT_FIXTURE_LOG}"
run_capture env PREFLIGHT_FIXTURE_EXIT_CODE=9 PREFLIGHT_FIXTURE_LOG="${PREFLIGHT_FIXTURE_LOG}" \
  "${SUBJECT}" deep-check --env test1 --deploy-script "${FIXTURE_DEPLOY}"
assert_status 30
assert_contains "${RUN_OUTPUT}" "CHECK deep-check FAIL exitCode=9"
assert_contains "${RUN_OUTPUT}" "RESULT FAIL reason=deep-check"

mismatch_manifest="${TEST_ROOT}/mismatch.json"
python3 - "${manifest}" "${mismatch_manifest}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    manifest = json.load(source)
manifest["components"]["backend"]["artifact"]["observedCommit"] = "a" * 40
with open(sys.argv[2], "w", encoding="utf-8") as target:
    json.dump(manifest, target)
PY
run_capture "${SUBJECT}" versions \
  --env test1 --manifest "${mismatch_manifest}" "${EXPECTED_ARGS[@]}"
assert_status 41
assert_contains "${RUN_OUTPUT}" "VERSION backend FAIL"
assert_contains "${RUN_OUTPUT}" "RESULT FAIL reason=revision-mismatch"

incomplete_manifest="${TEST_ROOT}/incomplete.json"
python3 - "${manifest}" "${incomplete_manifest}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    manifest = json.load(source)
manifest["components"].pop("androidProtocol")
with open(sys.argv[2], "w", encoding="utf-8") as target:
    json.dump(manifest, target)
PY
run_capture "${SUBJECT}" versions \
  --env test1 --manifest "${incomplete_manifest}" "${EXPECTED_ARGS[@]}"
assert_status 40
assert_contains "${RUN_OUTPUT}" "RESULT BLOCKED reason=runtime-evidence"
assert_contains "${RUN_OUTPUT}" "components missing androidProtocol"

invalid_digest_manifest="${TEST_ROOT}/invalid-digest.json"
sed 's/sha256:0000000000000000000000000000000000000000000000000000000000000000/sha256:short/' \
  "${manifest}" >"${invalid_digest_manifest}"
run_capture "${SUBJECT}" versions \
  --env test1 --manifest "${invalid_digest_manifest}" "${EXPECTED_ARGS[@]}"
assert_status 40
assert_contains "${RUN_OUTPUT}" "RESULT BLOCKED reason=runtime-evidence"
assert_contains "${RUN_OUTPUT}" "components.backend.artifact.identity must be sha256"

run_capture "${SUBJECT}" versions \
  --env test1 --manifest "${TEST_ROOT}/missing.json" "${EXPECTED_ARGS[@]}"
assert_status 40
assert_contains "${RUN_OUTPUT}" "MANIFEST BLOCKED reason=unavailable"
assert_contains "${RUN_OUTPUT}" "RESULT BLOCKED reason=runtime-evidence"

run_capture "${SUBJECT}" versions \
  --env test1 --manifest "${manifest}" \
  --backend-sha short \
  --frontend-sha "${FRONTEND_SHA}" \
  --web-protocol-sha "${WEB_PROTOCOL_SHA}" \
  --android-protocol-sha "${ANDROID_PROTOCOL_SHA}" \
  --max-age-seconds 300 \
  --android-role coordinator
assert_status 20
assert_contains "${RUN_OUTPUT}" "backend SHA must be a full 40-character Git commit"

run_capture "${SUBJECT}" deep-check --env production --deploy-script "${FIXTURE_DEPLOY}"
assert_status 20
assert_contains "${RUN_OUTPUT}" "environment must be test1 or perf2"

: >"${PREFLIGHT_FIXTURE_LOG}"
run_capture env PREFLIGHT_FIXTURE_EXIT_CODE=255 PREFLIGHT_FIXTURE_LOG="${PREFLIGHT_FIXTURE_LOG}" \
  "${SUBJECT}" deep-check --env test1 --deploy-script "${FIXTURE_DEPLOY}"
assert_status 40
assert_contains "${RUN_OUTPUT}" "CHECK deep-check BLOCKED exitCode=255 reason=observer-unreachable"
assert_contains "${RUN_OUTPUT}" "RESULT BLOCKED reason=deep-check-observer-unreachable"

: >"${PREFLIGHT_FIXTURE_LOG}"
run_capture env PREFLIGHT_FIXTURE_EXIT_CODE=64 PREFLIGHT_FIXTURE_OUTPUT="Armada backend 未运行" \
  PREFLIGHT_FIXTURE_LOG="${PREFLIGHT_FIXTURE_LOG}" \
  "${SUBJECT}" deep-check --env test1 --deploy-script "${FIXTURE_DEPLOY}"
assert_status 30
assert_contains "${RUN_OUTPUT}" "CHECK deep-check FAIL exitCode=64"

stale_manifest="${TEST_ROOT}/stale.json"
future_manifest="${TEST_ROOT}/future.json"
wrong_roles_manifest="${TEST_ROOT}/wrong-roles.json"
stale_artifact_manifest="${TEST_ROOT}/stale-artifact.json"
future_artifact_manifest="${TEST_ROOT}/future-artifact.json"
python3 - "${manifest}" "${stale_manifest}" "${future_manifest}" "${wrong_roles_manifest}" <<'PY'
import datetime as dt
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    manifest = json.load(source)
now = dt.datetime.now(dt.timezone.utc)
for path, generated_at in (
    (sys.argv[2], now - dt.timedelta(seconds=301)),
    (sys.argv[3], now + dt.timedelta(seconds=31)),
):
    candidate = dict(manifest)
    candidate["generatedAt"] = generated_at.isoformat().replace("+00:00", "Z")
    with open(path, "w", encoding="utf-8") as target:
        json.dump(candidate, target)
wrong_roles = json.loads(json.dumps(manifest))
wrong_roles["components"]["androidProtocol"]["artifacts"].pop()
with open(sys.argv[4], "w", encoding="utf-8") as target:
    json.dump(wrong_roles, target)
PY

python3 - "${manifest}" "${stale_artifact_manifest}" "${future_artifact_manifest}" <<'PY'
import datetime as dt
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    manifest = json.load(source)
now = dt.datetime.now(dt.timezone.utc)
for path, observed_at in (
    (sys.argv[2], now - dt.timedelta(seconds=301)),
    (sys.argv[3], now + dt.timedelta(seconds=31)),
):
    candidate = json.loads(json.dumps(manifest))
    candidate["components"]["backend"]["artifact"]["observedAt"] = (
        observed_at.isoformat().replace("+00:00", "Z")
    )
    with open(path, "w", encoding="utf-8") as target:
        json.dump(candidate, target)
PY

run_capture "${SUBJECT}" versions --env test1 --manifest "${stale_manifest}" "${EXPECTED_ARGS[@]}"
assert_status 40
assert_contains "${RUN_OUTPUT}" "generatedAt is stale"

run_capture "${SUBJECT}" versions --env test1 --manifest "${future_manifest}" "${EXPECTED_ARGS[@]}"
assert_status 40
assert_contains "${RUN_OUTPUT}" "generatedAt is too far in the future"

run_capture "${SUBJECT}" versions --env test1 --manifest "${wrong_roles_manifest}" "${EXPECTED_ARGS[@]}"
assert_status 40
assert_contains "${RUN_OUTPUT}" "Android roles do not match expected set missing=node-03"

run_capture "${SUBJECT}" versions --env test1 --manifest "${stale_artifact_manifest}" "${EXPECTED_ARGS[@]}"
assert_status 40
assert_contains "${RUN_OUTPUT}" "components.backend.artifact.observedAt is stale"

run_capture "${SUBJECT}" versions --env test1 --manifest "${future_artifact_manifest}" "${EXPECTED_ARGS[@]}"
assert_status 40
assert_contains "${RUN_OUTPUT}" "components.backend.artifact.observedAt is too far in the future"

printf '%s\n' "PASS preflight adapter tests"
