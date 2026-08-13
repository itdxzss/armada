#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/account-contact-matrix.sh"

fail() {
  printf 'FAIL %s\n' "$*" >&2
  exit 1
}

assert_equals() {
  local expected="$1"
  local actual="$2"
  local label="$3"
  [ "${actual}" = "${expected}" ] \
    || fail "${label}: expected=${expected} actual=${actual}"
}

setup_fixture() {
  FIXTURE_ROOT="$(mktemp -d)"
  TASKS_FILE="${FIXTURE_ROOT}/tasks.tsv"
  LEDGER_FILE="${FIXTURE_ROOT}/ledger.jsonl"
  TRACE_FILE="${FIXTURE_ROOT}/trace.tsv"
  MOCK_EVENTS="${FIXTURE_ROOT}/mock-events.tsv"
  MOCK_STATE_DIR="${FIXTURE_ROOT}/mock-state"
  mkdir -p "${MOCK_STATE_DIR}"
  : >"${LEDGER_FILE}"
  : >"${TRACE_FILE}"
  : >"${MOCK_EVENTS}"

  CONTACT_MATRIX_ANDROID_CONCURRENCY=2
  CONTACT_MATRIX_WEB_CONCURRENCY=1
  CONTACT_MATRIX_ANDROID_INTERVAL_MIN_SECONDS=0
  CONTACT_MATRIX_ANDROID_INTERVAL_MAX_SECONDS=0
  CONTACT_MATRIX_WEB_INTERVAL_MIN_SECONDS=0
  CONTACT_MATRIX_WEB_INTERVAL_MAX_SECONDS=0
  CONTACT_MATRIX_RATE_LIMIT_BASE_SECONDS=1
  CONTACT_MATRIX_RATE_LIMIT_MAX_SECONDS=1
  CONTACT_MATRIX_MAX_RATE_LIMIT_RETRIES=2
  CONTACT_MATRIX_POLL_SECONDS=0.02
  CONTACT_MATRIX_TRACE_FILE="${TRACE_FILE}"
}

teardown_fixture() {
  rm -rf "${FIXTURE_ROOT}"
}

write_result() {
  local result_file="$1"
  local outcome="$2"
  local http_status="$3"
  local reason="$4"
  local temporary="${result_file}.tmp.$$"
  jq -nc \
    --arg outcome "${outcome}" \
    --argjson httpStatus "${http_status}" \
    --arg reasonClass "${reason}" \
    '{outcome:$outcome,httpStatus:$httpStatus,businessCode:"",reasonClass:$reasonClass,requestId:""}' \
    >"${temporary}"
  mv "${temporary}" "${result_file}"
}

contact_execute_request() {
  local task_file="$1"
  local result_file="$2"
  local attempt="$3"
  local direction actor_id target_id protocol protocol_id actor_phone target_phone
  IFS=$'\t' read -r direction actor_id target_id protocol protocol_id actor_phone target_phone <"${task_file}"
  : "${direction}" "${protocol_id}" "${actor_phone}" "${target_phone}"

  if ! mkdir "${MOCK_STATE_DIR}/active-${actor_id}" 2>/dev/null; then
    printf 'REENTRY\t%s\n' "${actor_id}" >>"${MOCK_EVENTS}"
  fi
  printf 'START\t%s\t%s\t%s\n' "${actor_id}" "${target_id}" "${attempt}" >>"${MOCK_EVENTS}"
  sleep 0.12

  if [ "${MOCK_RATE_LIMIT_ACTOR:-}" = "${actor_id}" ] \
      && [ "${attempt}" -eq 1 ]; then
    write_result "${result_file}" "RATE_LIMITED" 429 "HTTP_429"
  else
    write_result "${result_file}" "SUCCESS" 200 ""
  fi
  printf 'END\t%s\t%s\t%s\n' "${actor_id}" "${target_id}" "${attempt}" >>"${MOCK_EVENTS}"
  rmdir "${MOCK_STATE_DIR}/active-${actor_id}" 2>/dev/null || true
}

test_fair_scheduler_never_reenters_one_actor_and_respects_protocol_caps() {
  cat >"${TASKS_FILE}" <<'TSV'
A_TO_B	1	101	ANDROID	pa-1	91001	91101
A_TO_B	1	102	ANDROID	pa-1	91001	91102
A_TO_B	2	101	ANDROID	pa-2	91002	91101
A_TO_B	2	102	ANDROID	pa-2	91002	91102
B_TO_A	3	101	WEB	pa-3	91003	91101
B_TO_A	3	102	WEB	pa-3	91003	91102
B_TO_A	4	101	WEB	pa-4	91004	91101
TSV

  contact_run_scheduler "${TASKS_FILE}" "${LEDGER_FILE}" "${FIXTURE_ROOT}/work"

  [ ! -s "${MOCK_STATE_DIR}/violation" ] || fail "actor reentry was detected"
  if grep -q '^REENTRY' "${MOCK_EVENTS}"; then
    fail "the same actor had overlapping requests"
  fi
  awk -F '\t' '
    $1 == "DISPATCH" {
      if ($5 > 1) exit 11
      if ($6 > 2) exit 12
    }
  ' "${TRACE_FILE}" || fail "protocol concurrency limit was exceeded"
  assert_equals 7 "$(jq -s '[.[] | select(.recordType == "CONTACT_ATTEMPT" and .outcome == "SUCCESS")] | length' "${LEDGER_FILE}")" "success count"

  local first_end_line second_start_line
  first_end_line="$(grep -n '^END' "${MOCK_EVENTS}" | head -n 1 | cut -d: -f1)"
  second_start_line="$(grep -n '^START' "${MOCK_EVENTS}" | sed -n '2p' | cut -d: -f1)"
  [ "${second_start_line}" -lt "${first_end_line}" ] \
    || fail "different actors should run concurrently"
}

test_rate_limit_pauses_only_the_affected_actor() {
  cat >"${TASKS_FILE}" <<'TSV'
A_TO_B	11	201	WEB	pa-11	92011	92201
A_TO_B	12	201	WEB	pa-12	92012	92201
A_TO_B	12	202	WEB	pa-12	92012	92202
TSV
  MOCK_RATE_LIMIT_ACTOR=11
  export MOCK_RATE_LIMIT_ACTOR

  contact_run_scheduler "${TASKS_FILE}" "${LEDGER_FILE}" "${FIXTURE_ROOT}/work"

  assert_equals 1 "$(jq -s '[.[] | select(.recordType == "CONTACT_ATTEMPT" and .outcome == "RATE_LIMITED")] | length' "${LEDGER_FILE}")" "rate-limit attempts"
  assert_equals 3 "$(jq -s '[.[] | select(.recordType == "CONTACT_ATTEMPT" and .outcome == "SUCCESS")] | length' "${LEDGER_FILE}")" "eventual successes"

  local actor12_first_complete actor11_retry_dispatch
  actor12_first_complete="$(grep -n $'^COMPLETE\t12\t' "${TRACE_FILE}" | head -n 1 | cut -d: -f1)"
  actor11_retry_dispatch="$(grep -n $'^DISPATCH\t11\t' "${TRACE_FILE}" | tail -n 1 | cut -d: -f1)"
  [ "${actor12_first_complete}" -lt "${actor11_retry_dispatch}" ] \
    || fail "another actor should continue while one actor cools down"
}

test_resume_skips_successful_pairs() {
  cat >"${TASKS_FILE}" <<'TSV'
A_TO_B	21	301	ANDROID	pa-21	93021	93301
A_TO_B	21	302	ANDROID	pa-21	93021	93302
TSV
  jq -nc '{recordType:"CONTACT_ATTEMPT",direction:"A_TO_B",actorAccountId:21,targetAccountId:301,actorProtocol:"ANDROID",attempt:1,outcome:"SUCCESS",httpStatus:200,businessCode:"",reasonClass:"",requestId:"",attemptedAt:"2026-08-13T00:00:00Z"}' >"${LEDGER_FILE}"

  contact_run_scheduler "${TASKS_FILE}" "${LEDGER_FILE}" "${FIXTURE_ROOT}/work"

  assert_equals 2 "$(jq -s '[.[] | select(.recordType == "CONTACT_ATTEMPT" and .outcome == "SUCCESS")] | length' "${LEDGER_FILE}")" "resume success total"
  assert_equals 1 "$(grep -c '^START' "${MOCK_EVENTS}")" "executed task count"
  grep -q $'^START\t21\t302\t1$' "${MOCK_EVENTS}" \
    || fail "resume should execute only the missing pair"
}

test_snapshot_freezes_scope_and_rejects_group_drift() {
  local accounts_file changed_accounts left_file right_file
  accounts_file="${FIXTURE_ROOT}/accounts.tsv"
  changed_accounts="${FIXTURE_ROOT}/accounts-changed.tsv"
  left_file="${FIXTURE_ROOT}/left.tsv"
  right_file="${FIXTURE_ROOT}/right.tsv"
  cat >"${accounts_file}" <<'TSV'
148	1	ANDROID	pa-1	91001	READY
148	2	WEB	pa-2	91002	INACTIVE
110	101	ANDROID	pa-101	91101	READY
110	102	ANDROID	pa-102	91102	ACCOUNT_STATE_3
TSV

  contact_remote_snapshot "${accounts_file}" "${LEDGER_FILE}" 148 110 strict
  contact_remote_validate_snapshot "${accounts_file}" "${LEDGER_FILE}" 148 110 strict
  contact_remote_select_frozen_accounts "${accounts_file}" "${LEDGER_FILE}" left "${left_file}"
  contact_remote_select_frozen_accounts "${accounts_file}" "${LEDGER_FILE}" right "${right_file}"
  assert_equals 2 "$(awk 'END { print NR }' "${left_file}")" "strict frozen left count"
  assert_equals 2 "$(awk 'END { print NR }' "${right_file}")" "strict frozen right count"

  cp "${accounts_file}" "${changed_accounts}"
  printf '148\t3\tANDROID\tpa-3\t91003\tREADY\n' >>"${changed_accounts}"
  if contact_remote_validate_snapshot \
      "${changed_accounts}" "${LEDGER_FILE}" 148 110 strict 2>/dev/null; then
    fail "a changed group must not reuse the old snapshot"
  fi
  if contact_remote_validate_snapshot \
      "${accounts_file}" "${LEDGER_FILE}" 148 110 ready-only 2>/dev/null; then
    fail "a ledger must not switch readiness policy"
  fi
}

test_ready_only_snapshot_keeps_original_ready_subset() {
  local accounts_file current_accounts left_file right_file
  accounts_file="${FIXTURE_ROOT}/accounts.tsv"
  current_accounts="${FIXTURE_ROOT}/accounts-current.tsv"
  left_file="${FIXTURE_ROOT}/left.tsv"
  right_file="${FIXTURE_ROOT}/right.tsv"
  cat >"${accounts_file}" <<'TSV'
148	1	ANDROID	pa-1	91001	READY
148	2	WEB	pa-2	91002	INACTIVE
110	101	ANDROID	pa-101	91101	READY
110	102	ANDROID	pa-102	91102	ACCOUNT_STATE_3
TSV
  contact_remote_snapshot "${accounts_file}" "${LEDGER_FILE}" 148 110 ready-only

  sed 's/\tINACTIVE$/\tREADY/; s/\tACCOUNT_STATE_3$/\tREADY/' \
    "${accounts_file}" >"${current_accounts}"
  contact_remote_validate_snapshot "${current_accounts}" "${LEDGER_FILE}" 148 110 ready-only
  contact_remote_select_frozen_accounts "${current_accounts}" "${LEDGER_FILE}" left "${left_file}"
  contact_remote_select_frozen_accounts "${current_accounts}" "${LEDGER_FILE}" right "${right_file}"
  assert_equals $'148\t1\tANDROID\tpa-1\t91001\tREADY' "$(cat "${left_file}")" "ready-only left subset"
  assert_equals $'110\t101\tANDROID\tpa-101\t91101\tREADY' "$(cat "${right_file}")" "ready-only right subset"
}

test_live_requires_a_dry_run_checkpoint() {
  local accounts_file
  accounts_file="${FIXTURE_ROOT}/accounts.tsv"
  cat >"${accounts_file}" <<'TSV'
148	1	ANDROID	pa-1	91001	READY
110	101	ANDROID	pa-101	91101	READY
TSV
  contact_remote_snapshot "${accounts_file}" "${LEDGER_FILE}" 148 110 strict
  if contact_ledger_has_dry_run "${LEDGER_FILE}"; then
    fail "a fresh snapshot must not look like a completed dry-run"
  fi
  jq -nc '{recordType:"PREFLIGHT",mode:"dry-run",status:"READY"}' >>"${LEDGER_FILE}"
  contact_ledger_has_dry_run "${LEDGER_FILE}" \
    || fail "the dry-run checkpoint must be discoverable"
}

test_android_application_rate_limit_is_detected() {
  contact_android_rate_limited 1003 'rate-overlimit, Code: 429' \
    || fail "Android rate-overlimit message must be classified"
  contact_android_rate_limited 429 '' \
    || fail "Android business Code=429 must be classified"
  if contact_android_rate_limited 1003 'contact save failed'; then
    fail "an ordinary Android application failure must not be rate limited"
  fi
}

test_degraded_task_plan_skips_offline_actor_but_keeps_it_as_target() {
  local left_file right_file tasks_file
  left_file="${FIXTURE_ROOT}/left.tsv"
  right_file="${FIXTURE_ROOT}/right.tsv"
  tasks_file="${FIXTURE_ROOT}/degraded-tasks.tsv"
  cat >"${left_file}" <<'TSV'
148	1	ANDROID	pa-1	91001	READY
148	2	ANDROID	pa-2	91002	LOGIN_STATE_2
TSV
  cat >"${right_file}" <<'TSV'
110	101	ANDROID	pa-101	91101	READY
TSV

  contact_remote_generate_tasks "${left_file}" "${right_file}" "${tasks_file}"

  assert_equals 3 "$(awk 'END { print NR }' "${tasks_file}")" "degraded planned task count"
  grep -q $'^LEFT_TO_RIGHT\t1\t101\t' "${tasks_file}" \
    || fail "the online left actor must execute"
  if grep -q $'^LEFT_TO_RIGHT\t2\t' "${tasks_file}"; then
    fail "the offline left account must not act"
  fi
  grep -q $'^RIGHT_TO_LEFT\t101\t2\t' "${tasks_file}" \
    || fail "the offline account must remain a contact target"
}

main() {
  [ -f "${SCRIPT}" ] || fail "missing scheduler: ${SCRIPT}"
  # shellcheck source=/dev/null
  . "${SCRIPT}"

  setup_fixture
  trap teardown_fixture EXIT
  test_fair_scheduler_never_reenters_one_actor_and_respects_protocol_caps
  teardown_fixture

  setup_fixture
  test_rate_limit_pauses_only_the_affected_actor
  teardown_fixture

  setup_fixture
  test_resume_skips_successful_pairs
  teardown_fixture

  setup_fixture
  test_snapshot_freezes_scope_and_rejects_group_drift
  teardown_fixture

  setup_fixture
  test_ready_only_snapshot_keeps_original_ready_subset
  teardown_fixture

  setup_fixture
  test_live_requires_a_dry_run_checkpoint
  teardown_fixture

  setup_fixture
  test_android_application_rate_limit_is_detected
  teardown_fixture

  setup_fixture
  test_degraded_task_plan_skips_offline_actor_but_keeps_it_as_target
  teardown_fixture
  trap - EXIT
  printf 'PASS account-contact-matrix tests\n'
}

main "$@"
