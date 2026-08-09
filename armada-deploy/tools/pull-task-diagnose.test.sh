#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/pull-task-diagnose.sh"

fail() {
  printf 'FAIL %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  grep -Fq -- "${needle}" <<<"${haystack}" \
    || fail "expected output to contain: ${needle}"
}

assert_not_contains() {
  local haystack="$1"
  local needle="$2"
  if grep -Fq -- "${needle}" <<<"${haystack}"; then
    fail "expected output not to contain: ${needle}"
  fi
}

setup_fixture() {
  FIXTURE_ROOT="$(mktemp -d)"
  FIXTURE_BIN="${FIXTURE_ROOT}/bin"
  FIXTURE_PROFILES="${FIXTURE_ROOT}/profiles"
  FIXTURE_KEY="${FIXTURE_ROOT}/test key.pem"
  FIXTURE_SQL="${FIXTURE_ROOT}/query.sql"
  mkdir -p "${FIXTURE_BIN}" "${FIXTURE_PROFILES}"
  : >"${FIXTURE_KEY}"
  chmod 600 "${FIXTURE_KEY}"

  cat >"${FIXTURE_PROFILES}/test1.conf" <<'CONF'
ENV_ID=test1
PROFILE_ARMADA_HOST=127.0.0.1
PROFILE_ARMADA_USER=tester
PROFILE_ARMADA_KEY_REL=unused.pem
PROFILE_ARMADA_REMOTE_DIR=/home/tester/armada
EXPECTED_ARMADA_DB_SCHEMA=armada_test
CONF

  cat >"${FIXTURE_BIN}/ssh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
cat >"${MOCK_SQL_CAPTURE}"
printf '%s\n' "${MOCK_SSH_OUTPUT}"
STUB
  chmod +x "${FIXTURE_BIN}/ssh"
}

teardown_fixture() {
  rm -rf "${FIXTURE_ROOT}"
}

run_cli() {
  ARMADA_DIAG_PROFILE_DIR="${FIXTURE_PROFILES}" \
  ARMADA_DIAG_SSH_BIN="${FIXTURE_BIN}/ssh" \
  ARMADA_DIAG_BACKEND_KEY="${FIXTURE_KEY}" \
  MOCK_SQL_CAPTURE="${FIXTURE_SQL}" \
  MOCK_SSH_OUTPUT="${MOCK_SSH_OUTPUT}" \
    bash "${SCRIPT}" "$@"
}

standard_fixture() {
  cat <<'TSV'
RUNTIME	running	2026-08-09T07:43:59Z	sha256:backend
record_type	task_id	task_type	mode	status	primary_stage	group_count	expected_pull_count	updated_age_seconds
TASK	123	STANDARD	NORMAL_LINK	EXECUTING	PULL_EXECUTION	3	90	12
record_type	total_count	active_count	wait_resource_count	terminal_count	due_count	not_due_count
EXECUTIONS	3	2	1	0	2	0
record_type	reconcile_overdue_seconds
THRESHOLDS	180
record_type	target_group_count	transfer_waiting_count	transfer_running_count	transfer_failed_count	remaining_target_count	message_failed_count	message_unknown_count
category	task_id	execution_id	fact_id	command_id	diagnosis	stall_seconds	fact_updated_at
OUTBOX_DEAD	123	456	789	cmd_abc	PULL_CALL: broker unavailable	300	1786240000000
WAIT_RESOURCE	123	457	457	NULL	wait_resource_type=2, reason_code=PULLER_UNAVAILABLE, RETRYING	20	1786240000001
UNKNOWN_RESULT	123	458	790	cmd_pending	PULL_CALL: fact_status=4	9	1786240000002
TSV
}

group_marketing_fixture() {
  cat <<'TSV'
RUNTIME	running	2026-08-09T07:43:59Z	sha256:backend
record_type	task_id	task_type	mode	status	primary_stage	group_count	expected_pull_count	updated_age_seconds
TASK	124	GROUP_MARKETING	GROUP_TRANSFER	EXECUTING	TRANSFER	4	100	8
record_type	total_count	active_count	wait_resource_count	terminal_count	due_count	not_due_count
EXECUTIONS	0	0	0	0	0	0
record_type	reconcile_overdue_seconds
THRESHOLDS	180
record_type	target_group_count	transfer_waiting_count	transfer_running_count	transfer_failed_count	remaining_target_count	message_failed_count	message_unknown_count
MARKETING	4	1	2	1	30	0	0
category	task_id	execution_id	fact_id	command_id	diagnosis	stall_seconds	fact_updated_at
TSV
}

test_standard_task_accepts_page_hash_id_and_summarizes_anomalies() {
  local out
  MOCK_SSH_OUTPUT="$(standard_fixture)"
  out="$(run_cli --env test1 --task-id '#123' --observed-at '14:20' --symptom '页面一直执行中')"

  assert_contains "${out}" "任务: #123"
  assert_contains "${out}" "类型: 普通链接拉群 (STANDARD/NORMAL_LINK)"
  assert_contains "${out}" "结论: 发现 3 条候选，其中 1 条需要继续处理"
  assert_contains "${out}" "OUTBOX_DEAD executionId=456 commandId=cmd_abc"
  assert_contains "${out}" "WAIT_RESOURCE executionId=457 (正常重试中)"
  assert_contains "${out}" "UNKNOWN_RESULT executionId=458 commandId=cmd_pending (结果收敛中)"
  assert_contains "${out}" "现象: 页面一直执行中"
}

test_group_marketing_task_is_routed_without_standard_state_machine() {
  local out
  MOCK_SSH_OUTPUT="$(group_marketing_fixture)"
  out="$(run_cli --env test1 --task-id 124)"

  assert_contains "${out}" "类型: 拉群营销 (GROUP_MARKETING/GROUP_TRANSFER)"
  assert_contains "${out}" "自动分流: 已跳过普通链接拉群的七阶段判断"
  assert_contains "${out}" "群进度: 目标=4 等待=1 执行中=2 失败=1"
  assert_not_contains "${out}" "OUTBOX_DEAD"
}

test_invalid_task_id_is_rejected_before_ssh() {
  local out status
  MOCK_SSH_OUTPUT="$(standard_fixture)"
  set +e
  out="$(run_cli --env test1 --task-id '123;DELETE' 2>&1)"
  status=$?
  set -e

  [ "${status}" -ne 0 ] || fail "invalid task id should fail"
  assert_contains "${out}" "任务 ID 必须是正整数"
  [ ! -f "${FIXTURE_SQL}" ] || fail "ssh should not run for invalid task id"
}

test_generated_sql_is_read_only_and_excludes_sensitive_columns() {
  local out
  MOCK_SSH_OUTPUT="$(standard_fixture)"
  out="$(run_cli --env test1 --task-id 123 --execution-id 456)"
  : "${out}"

  grep -Fq 'SET @task_id := 123;' "${FIXTURE_SQL}" \
    || fail "task id was not rendered"
  grep -Fq 'SET @execution_id := 456;' "${FIXTURE_SQL}" \
    || fail "execution id was not rendered"
  grep -Fq "'DUE_EXECUTION_STALLED' AS category" "${FIXTURE_SQL}" \
    || fail "existing anomaly summary query should be reused"
  if grep -Eiq '^[[:space:]]*(INSERT|UPDATE|DELETE|ALTER|DROP|TRUNCATE|REPLACE|CALL)[[:space:]]' "${FIXTURE_SQL}"; then
    fail "generated SQL must be read-only"
  fi
  if grep -Eiq '(^|[^A-Za-z0-9_])(account_phone|normalized_phone|payload_json|normalized_link|invite_code|wa_jid|group_jid)([^A-Za-z0-9_]|$)' "${FIXTURE_SQL}"; then
    fail "generated SQL must not select sensitive columns"
  fi
}

test_remote_env_file_is_not_executed_as_shell_code() {
  if grep -Fq '. ./.env' "${SCRIPT}"; then
    fail "remote .env must be parsed by explicit keys instead of sourced"
  fi
  grep -Fq 'read_env_value DB_URL' "${SCRIPT}" \
    || fail "DB_URL should be read explicitly"
  grep -Fq 'read_env_value DB_USER' "${SCRIPT}" \
    || fail "DB_USER should be read explicitly"
  grep -Fq 'read_env_value DB_PASSWORD' "${SCRIPT}" \
    || fail "DB_PASSWORD should be read explicitly"
}

test_missing_task_returns_a_clear_error() {
  local out status
  MOCK_SSH_OUTPUT=$'RUNTIME\trunning\t2026-08-09T07:43:59Z\tsha256:backend\nrecord_type\ttask_id\ttask_type\tmode\tstatus\tprimary_stage\tgroup_count\texpected_pull_count\tupdated_age_seconds\nrecord_type\ttotal_count\tactive_count\twait_resource_count\tterminal_count\tdue_count\tnot_due_count\nEXECUTIONS\t0\t0\t0\t0\t0\t0\ncategory\ttask_id\texecution_id\tfact_id\tcommand_id\tdiagnosis\tstall_seconds\tfact_updated_at'
  set +e
  out="$(run_cli --env test1 --task-id 999 2>&1)"
  status=$?
  set -e

  [ "${status}" -ne 0 ] || fail "missing task should fail"
  assert_contains "${out}" "在 test1 的 pull_task 中找不到 #999"
}

main() {
  [ -f "${SCRIPT}" ] || fail "expected diagnosis CLI: ${SCRIPT}"
  setup_fixture
  trap teardown_fixture EXIT
  test_standard_task_accepts_page_hash_id_and_summarizes_anomalies
  rm -f "${FIXTURE_SQL}"
  test_group_marketing_task_is_routed_without_standard_state_machine
  rm -f "${FIXTURE_SQL}"
  test_invalid_task_id_is_rejected_before_ssh
  rm -f "${FIXTURE_SQL}"
  test_generated_sql_is_read_only_and_excludes_sensitive_columns
  rm -f "${FIXTURE_SQL}"
  test_remote_env_file_is_not_executed_as_shell_code
  test_missing_task_returns_a_clear_error
  printf 'PASS pull-task-diagnose tests\n'
}

main "$@"
