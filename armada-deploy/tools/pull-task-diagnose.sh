#!/usr/bin/env bash
if [ -z "${BASH_VERSION:-}" ]; then
  exec /usr/bin/env bash "$0" "$@"
fi
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${DEPLOY_DIR}/.." && pwd)"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/.." && pwd)"
PROFILE_DIR="${ARMADA_DIAG_PROFILE_DIR:-${DEPLOY_DIR}/envs}"
DIAGNOSIS_SQL="${REPO_ROOT}/docs/operations/pull-task-normal-link-diagnosis.sql"
STATISTICS_SQL="${REPO_ROOT}/docs/operations/pull-task-fact-statistics.sql"
SSH_BIN="${ARMADA_DIAG_SSH_BIN:-ssh}"

# shellcheck source=../lib/common.sh
. "${DEPLOY_DIR}/lib/common.sh"
armada_init_colors

usage() {
  cat <<'EOF'
pull-task-diagnose.sh - 拉群任务测试环境只读快速诊断。

用法:
  bash armada-deploy/tools/pull-task-diagnose.sh \
    --env test1 --task-id '#123' \
    [--execution-id 456] [--observed-at '14:20'] [--symptom '页面一直执行中']

  bash armada-deploy/tools/pull-task-diagnose.sh \
    --env test1 --task-id '#123' --canary-line

参数:
  --env             必填，只允许 test1 或 perf2；该参数就是目标环境确认。
  --task-id         必填，页面列表显示的 #任务号，即 pull_task.id。
  --execution-id    可选，只收窄普通链接拉群的群执行行。
  --observed-at     可选，测试现象的时间，仅在本次摘要中回显。
  --symptom         可选，页面现象，仅在本次摘要中回显。
  --canary-line     只输出一行普通拉群金丝雀安全摘要；不输出号码、链接、JID 或 payload。
  -h, --help        显示帮助。

安全边界:
  单条查询最多执行 5 秒，超时立即退出。
  只执行 SET / SELECT / WITH 诊断 SQL；不重试、不修改状态、不释放资源、不重启服务。
EOF
}

normalize_positive_id() {
  local label="$1"
  local raw="$2"
  raw="${raw#\#}"
  case "${raw}" in
    ''|*[!0-9]*|0) die "${label} ID 必须是正整数: ${2}" ;;
  esac
  printf '%s\n' "${raw}"
}

sanitize_note() {
  local value="$1"
  value="$(printf '%s' "${value}" | tr '\t\r\n' '   ')"
  printf '%.200s' "${value}"
}

SELECTED_ENV=""
TASK_ID=""
EXECUTION_ID=""
OBSERVED_AT=""
SYMPTOM=""
CANARY_LINE=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env)
      [ "$#" -ge 2 ] || die "--env 需要环境名"
      SELECTED_ENV="$2"
      shift 2
      ;;
    --env=*)
      SELECTED_ENV="${1#*=}"
      shift
      ;;
    --task-id)
      [ "$#" -ge 2 ] || die "--task-id 需要任务 ID"
      TASK_ID="$2"
      shift 2
      ;;
    --task-id=*)
      TASK_ID="${1#*=}"
      shift
      ;;
    --execution-id)
      [ "$#" -ge 2 ] || die "--execution-id 需要执行行 ID"
      EXECUTION_ID="$2"
      shift 2
      ;;
    --execution-id=*)
      EXECUTION_ID="${1#*=}"
      shift
      ;;
    --observed-at)
      [ "$#" -ge 2 ] || die "--observed-at 需要时间"
      OBSERVED_AT="$2"
      shift 2
      ;;
    --observed-at=*)
      OBSERVED_AT="${1#*=}"
      shift
      ;;
    --symptom)
      [ "$#" -ge 2 ] || die "--symptom 需要现象描述"
      SYMPTOM="$2"
      shift 2
      ;;
    --symptom=*)
      SYMPTOM="${1#*=}"
      shift
      ;;
    --canary-line)
      CANARY_LINE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *) die "未知参数: $1" ;;
  esac
done

[ -n "${SELECTED_ENV}" ] || die "必须显式指定 --env test1 或 --env perf2"
case "${SELECTED_ENV}" in
  test1|perf2) ;;
  *) die "诊断环境只允许 test1 或 perf2: ${SELECTED_ENV}" ;;
esac
[ -n "${TASK_ID}" ] || die "缺少 --task-id（页面 #任务号）"
TASK_ID="$(normalize_positive_id "任务" "${TASK_ID}")"
if [ "${CANARY_LINE}" -eq 1 ]; then
  [ "${SELECTED_ENV}" = "test1" ] \
    || die "--canary-line 只允许 test1，避免误查其他环境"
  [ -z "${EXECUTION_ID}" ] \
    || die "--canary-line 只接受 taskId，不允许 --execution-id，以免隐藏同任务的额外执行行"
fi
if [ -n "${EXECUTION_ID}" ]; then
  EXECUTION_ID="$(normalize_positive_id "执行行" "${EXECUTION_ID}")"
fi
OBSERVED_AT="$(sanitize_note "${OBSERVED_AT}")"
SYMPTOM="$(sanitize_note "${SYMPTOM}")"

PROFILE_FILE="${PROFILE_DIR}/${SELECTED_ENV}.conf"
[ -f "${PROFILE_FILE}" ] || die "缺少环境档案: ${PROFILE_FILE}"
# shellcheck source=/dev/null
. "${PROFILE_FILE}"
[ "${ENV_ID:-}" = "${SELECTED_ENV}" ] || die "环境档案 ID 不匹配: ${PROFILE_FILE}"
for required_profile_var in \
  PROFILE_ARMADA_HOST PROFILE_ARMADA_USER PROFILE_ARMADA_KEY_REL \
  PROFILE_ARMADA_REMOTE_DIR EXPECTED_ARMADA_DB_SCHEMA; do
  [ -n "${!required_profile_var:-}" ] \
    || die "环境档案缺少必填字段: ${required_profile_var}"
done

SSH_HOST="${ARMADA_DIAG_BACKEND_HOST:-${PROFILE_ARMADA_HOST}}"
SSH_USER="${ARMADA_DIAG_BACKEND_USER:-${PROFILE_ARMADA_USER}}"
SSH_KEY="${ARMADA_DIAG_BACKEND_KEY:-${WORKSPACE_ROOT}/${PROFILE_ARMADA_KEY_REL}}"
REMOTE_DIR="${ARMADA_DIAG_BACKEND_REMOTE_DIR:-${PROFILE_ARMADA_REMOTE_DIR}}"
EXPECTED_DB_SCHEMA="${EXPECTED_ARMADA_DB_SCHEMA}"

validate_ssh_identity "Armada" "${SSH_HOST}" "${SSH_USER}"
validate_remote_dir "Armada" "${REMOTE_DIR}"
require_ssh_key "Armada" "${SSH_KEY}"
case "${EXPECTED_DB_SCHEMA}" in
  ''|*[!A-Za-z0-9_]*) die "预期数据库 schema 不合法: ${EXPECTED_DB_SCHEMA}" ;;
esac
[ -f "${DIAGNOSIS_SQL}" ] || die "缺少诊断 SQL: ${DIAGNOSIS_SQL}"
[ -f "${STATISTICS_SQL}" ] || die "缺少统计 SQL: ${STATISTICS_SQL}"
command -v "${SSH_BIN}" >/dev/null 2>&1 || die "找不到 SSH 命令: ${SSH_BIN}"

render_parameter_block() {
  awk '
    /^-- 参数块：/ { in_parameters = 1; next }
    /^-- 结果 0：/ { in_parameters = 0 }
    in_parameters { print }
  ' "${DIAGNOSIS_SQL}"
}

render_anomaly_query() {
  awk '
    /^-- 结果 9：异常摘要候选。/ { in_result = 1 }
    in_result { print }
  ' "${DIAGNOSIS_SQL}"
}

render_summary_queries() {
  cat <<'SQL'
SELECT
    'TASK' AS record_type,
    id AS task_id,
    task_type,
    mode,
    status,
    COALESCE(primary_stage, '-') AS primary_stage,
    group_count,
    expected_pull_count,
    (@now - updated_at) DIV 1000 AS updated_age_seconds
FROM pull_task
WHERE id = @task_id
  AND deleted_at IS NULL;

SELECT
    'EXECUTIONS' AS record_type,
    COUNT(e.id) AS total_count,
    COALESCE(SUM(e.execution_status IN (1, 2)), 0) AS active_count,
    COALESCE(SUM(e.execution_status = 3), 0) AS wait_resource_count,
    COALESCE(SUM(e.execution_status IN (4, 5, 6)), 0) AS terminal_count,
    COALESCE(SUM(
        e.execution_status IN (1, 2, 3)
        AND e.manual_paused = 0
        AND e.next_run_at <= @now
    ), 0) AS due_count,
    COALESCE(SUM(
        e.execution_status IN (1, 2, 3)
        AND e.manual_paused = 0
        AND e.next_run_at > @now
    ), 0) AS not_due_count
FROM pull_task t
LEFT JOIN pull_task_group_execution e
  ON e.task_id = t.id
 AND e.tenant_id = t.tenant_id
 AND (@execution_id IS NULL OR e.id = @execution_id)
WHERE t.id = @task_id
  AND t.deleted_at IS NULL;

SELECT
    'THRESHOLDS' AS record_type,
    @reconcile_overdue_ms DIV 1000 AS reconcile_overdue_seconds;

SELECT
    'MARKETING' AS record_type,
    s.target_group_count,
    s.transfer_waiting_count,
    s.transfer_running_count,
    s.transfer_failed_count,
    s.remaining_target_count,
    s.message_failed_count,
    s.message_unknown_count
FROM pull_task_group_marketing_summary s
JOIN pull_task t
  ON t.id = s.task_id
 AND t.tenant_id = s.tenant_id
WHERE t.id = @task_id
  AND t.task_type = 'GROUP_MARKETING'
  AND t.deleted_at IS NULL;
SQL
}

render_canary_summary_query() {
  cat <<'SQL'
SET SESSION TRANSACTION READ ONLY;
START TRANSACTION READ ONLY;
SET @now := CAST(FLOOR(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000) AS SIGNED);

WITH target_task AS (
    SELECT id, tenant_id, status
    FROM pull_task
    WHERE id = @task_id
      AND task_type = 'STANDARD'
      AND mode = 'NORMAL_LINK'
      AND deleted_at IS NULL
), scoped_execution AS (
    SELECT e.id, e.tenant_id, e.task_id, e.execution_status, e.stage,
           e.wait_resource_type, e.reason_code, e.link_occupancy_key,
           e.valid_member_count, e.invalid_line_count, e.duplicate_line_count
    FROM pull_task_group_execution e
    JOIN target_task t
      ON t.id = e.task_id
     AND t.tenant_id = e.tenant_id
), execution_stats AS (
    SELECT
        COUNT(*) AS execution_count,
        COALESCE(SUM(execution_status = 4), 0) AS completed_count,
        CASE
          WHEN COUNT(*) = 1 THEN CAST(MAX(id) AS CHAR)
          WHEN COUNT(*) = 0 THEN '-'
          ELSE CONCAT('MULTI:', COUNT(*))
        END AS execution_id_label,
        CASE
          WHEN COUNT(*) = 1 THEN CAST(MAX(execution_status) AS CHAR)
          WHEN COUNT(*) = 0 THEN '-'
          ELSE 'MIXED'
        END AS execution_status_label,
        CASE
          WHEN COUNT(*) = 1 THEN CAST(MAX(stage) AS CHAR)
          WHEN COUNT(*) = 0 THEN '-'
          ELSE 'MIXED'
        END AS stage_label,
        CASE
          WHEN COUNT(*) = 1 THEN COALESCE(CAST(MAX(wait_resource_type) AS CHAR), '-')
          WHEN COUNT(*) = 0 THEN '-'
          ELSE 'MIXED'
        END AS wait_label,
        COALESCE(
          REPLACE(REPLACE(REPLACE(REPLACE(MAX(NULLIF(reason_code, '')), ' ', '_'),
                  CHAR(9), '_'), CHAR(10), '_'), CHAR(13), '_'),
          '-'
        ) AS reason_label,
        COALESCE(SUM(execution_status = 3 OR wait_resource_type IS NOT NULL), 0) AS wait_count,
        COALESCE(SUM(execution_status = 5), 0) AS failed_count,
        COALESCE(SUM(UPPER(TRIM(COALESCE(reason_code, ''))) = 'GROUP_BANNED'), 0)
          AS group_banned_count,
        COALESCE(SUM(execution_status = 5
                     OR UPPER(TRIM(COALESCE(reason_code, ''))) = 'GROUP_BANNED'), 0)
          AS failed_or_group_banned_count,
        COALESCE(SUM(link_occupancy_key IS NOT NULL), 0) AS occupied_group_count,
        COALESCE(SUM(valid_member_count), 0) AS valid_member_count,
        COALESCE(SUM(invalid_line_count), 0) AS invalid_line_count,
        COALESCE(SUM(duplicate_line_count), 0) AS duplicate_line_count
    FROM scoped_execution
), setting_stats AS (
    SELECT CASE
      WHEN COUNT(s.task_id) = 1
       AND MAX(
         s.auto_start = 0
         AND s.puller_sync_mode = 1
         AND s.material_admin_timing = 2
         AND s.is_clear_existing_members = 0
         AND s.is_puller_join_by_link = 0
         AND s.early_pull_count = 1
         AND s.early_pull_call_count = 1
         AND s.pull_count_min = 1
         AND s.pull_count_max = 1
         AND s.pull_interval_seconds = 30
         AND s.puller_count_per_group = 1
         AND s.station_count_per_call = 0
         AND s.concurrent_group_count = 1
         AND s.is_creator_leave_after_pull = 0
         AND s.required_manager_count = 1
       ) = 1
       AND COUNT(gs.task_id) = 1
       AND MAX(
         gs.is_group_setting_enabled = 0
         AND gs.is_auto_unmute_after_task = 0
         AND gs.is_auto_close_invite_after_task = 0
       ) = 1
      THEN 0 ELSE 1 END AS config_bad
    FROM target_task t
    LEFT JOIN pull_task_standard_setting s
      ON s.task_id = t.id
     AND s.tenant_id = t.tenant_id
    LEFT JOIN pull_task_standard_group_setting gs
      ON gs.task_id = t.id
     AND gs.tenant_id = t.tenant_id
), role_scope AS (
    SELECT r.role_type, r.availability_status, r.released_at,
           a.id AS joined_account_id, a.protocol_id,
           s.id AS state_row_id, s.account_state, s.login_state,
           s.risk_status, s.mute_status, s.pulling_restriction_until
    FROM pull_task_group_account r
    JOIN scoped_execution e
      ON e.id = r.group_execution_id
     AND e.tenant_id = r.tenant_id
    LEFT JOIN account a
      ON a.id = r.account_id
     AND a.tenant_id = r.tenant_id
     AND a.deleted_at IS NULL
    LEFT JOIN account_state s
      ON s.account_id = a.id
     AND s.tenant_id = a.tenant_id
), role_stats AS (
    SELECT
        COALESCE(SUM(role_type = 1), 0) AS manager_count,
        COALESCE(SUM(role_type = 2), 0) AS puller_count,
        COALESCE(SUM(role_type = 3), 0) AS station_count,
        COALESCE(SUM(role_type = 4), 0) AS promoter_count,
        COALESCE(SUM(role_type = 5), 0) AS controller_count,
        COALESCE(SUM(UPPER(TRIM(protocol_id)) = 'ANDROID'), 0) AS android_count,
        COALESCE(SUM(UPPER(TRIM(protocol_id)) = 'WEB'), 0) AS web_count,
        COALESCE(SUM(
          protocol_id IS NULL OR UPPER(TRIM(protocol_id)) NOT IN ('ANDROID', 'WEB')
        ), 0) AS other_backend_count,
        COALESCE(SUM(
          joined_account_id IS NULL
          OR state_row_id IS NULL
          OR availability_status <> 1
          OR account_state IS NULL
          OR account_state NOT IN (2, 6, 7)
          OR login_state IS NULL
          OR login_state <> 1
          OR (risk_status IS NOT NULL AND risk_status <> 1)
          OR mute_status IN (2, 3)
          OR (pulling_restriction_until IS NOT NULL AND pulling_restriction_until > @now)
        ), 0) AS health_bad,
        COALESCE(SUM(role_type = 2 AND released_at IS NULL), 0) AS unreleased_puller_count
    FROM role_scope
), action_stats AS (
    SELECT
        COUNT(*) AS total_count,
        COALESCE(SUM(a.action_status = 3), 0) AS success_count,
        COALESCE(SUM(a.action_type = 1), 0) AS save_contact_count,
        COALESCE(SUM(a.action_type = 2), 0) AS invite_count,
        COALESCE(SUM(a.action_type = 3), 0) AS join_count,
        COALESCE(SUM(a.action_type = 4), 0) AS promote_count,
        COALESCE(SUM(a.action_type = 5), 0) AS open_member_add_count,
        COALESCE(SUM(a.action_type = 6), 0) AS close_approval_count,
        COALESCE(SUM(a.action_status IN (1, 2)), 0) AS open_count,
        COALESCE(SUM(a.action_status IN (4, 5, 7)), 0) AS bad_count,
        COALESCE(SUM(a.attempt_no > 1), 0) AS retry_count,
        COALESCE(SUM(a.action_type NOT IN (1, 2, 3, 4, 5, 6)), 0) AS unexpected_count
    FROM pull_task_account_action a
    JOIN scoped_execution e
      ON e.id = a.group_execution_id
     AND e.tenant_id = a.tenant_id
), call_stats AS (
    SELECT
        COUNT(*) AS total_count,
        COALESCE(SUM(c.call_status = 3), 0) AS success_count,
        COALESCE(SUM(c.call_status IN (1, 2)), 0) AS open_count,
        COALESCE(SUM(c.call_status = 4), 0) AS bad_count
    FROM pull_task_pull_call c
    JOIN scoped_execution e
      ON e.id = c.group_execution_id
     AND e.tenant_id = c.tenant_id
), query_stats AS (
    SELECT
        COUNT(*) AS total_count,
        COALESCE(SUM(q.query_status <> 2), 0) AS non_success_count,
        COALESCE(SUM(q.query_status = 1), 0) AS open_count,
        COALESCE(SUM(q.query_status IN (3, 4)), 0) AS bad_count,
        COALESCE(SUM(q.attempt_no > 1), 0) AS retry_count
    FROM pull_task_member_query q
    JOIN scoped_execution e
      ON e.id = q.group_execution_id
     AND e.tenant_id = q.tenant_id
), material_stats AS (
    SELECT
        COUNT(*) AS total_count,
        COALESCE(SUM(m.pull_status <> 2
                     OR m.admin_required <> 0
                     OR m.admin_status <> 0), 0) AS non_success_count,
        COALESCE(SUM(m.pull_status IN (3, 4) OR m.admin_status IN (4, 5)), 0) AS bad_count,
        COALESCE(SUM(m.admin_required = 1), 0) AS admin_required_count
    FROM pull_task_material_member m
    JOIN scoped_execution e
      ON e.id = m.group_execution_id
     AND e.tenant_id = m.tenant_id
), attempt_stats AS (
    SELECT
        COUNT(*) AS total_count,
        COALESCE(SUM(a.lifecycle_status <> 3
                     OR a.protocol_outcome <> 'SUCCESS'), 0) AS non_success_count,
        COALESCE(SUM(a.attempt_no > 1), 0) AS retry_count,
        COALESCE(SUM(a.protocol_outcome IN ('FAILED', 'UNKNOWN')
                     OR a.execution_state = 'UNCERTAIN'), 0) AS bad_count,
        COALESCE(SUM(a.lifecycle_status IN (1, 2)), 0) AS open_count
    FROM pull_task_pull_call_member_attempt a
    JOIN scoped_execution e
      ON e.id = a.group_execution_id
     AND e.tenant_id = a.tenant_id
), wave_stats AS (
    SELECT COUNT(*) AS total_count,
           COALESCE(SUM(w.wave_status = 3), 0) AS settled_count,
           COALESCE(SUM(w.wave_status IN (1, 2)), 0) AS active_count
    FROM pull_task_pull_wave w
    JOIN scoped_execution e
      ON e.id = w.group_execution_id
     AND e.tenant_id = w.tenant_id
), fact_scope AS (
    SELECT a.tenant_id, 'PULL_TASK_ACCOUNT_ACTION' AS aggregate_type,
           a.id AS aggregate_id, 1 AS is_side_effect
    FROM pull_task_account_action a
    JOIN scoped_execution e
      ON e.id = a.group_execution_id
     AND e.tenant_id = a.tenant_id
    UNION ALL
    SELECT c.tenant_id, 'PULL_TASK_PULL_CALL', c.id, 1
    FROM pull_task_pull_call c
    JOIN scoped_execution e
      ON e.id = c.group_execution_id
     AND e.tenant_id = c.tenant_id
    UNION ALL
    SELECT m.tenant_id, 'PULL_TASK_MATERIAL_MEMBER', m.id, 1
    FROM pull_task_material_member m
    JOIN scoped_execution e
      ON e.id = m.group_execution_id
     AND e.tenant_id = m.tenant_id
    WHERE m.admin_required = 1 OR m.admin_command_id IS NOT NULL
    UNION ALL
    SELECT q.tenant_id, 'PULL_TASK_MEMBER_QUERY', q.id, 0
    FROM pull_task_member_query q
    JOIN scoped_execution e
      ON e.id = q.group_execution_id
     AND e.tenant_id = q.tenant_id
), command_scope AS (
    SELECT o.id, o.command_id, o.aggregate_type, o.aggregate_id,
           o.protocol_backend, o.status, o.retry_count, f.is_side_effect
    FROM fact_scope f
    JOIN protocol_command_outbox o
      ON o.tenant_id = f.tenant_id
     AND o.aggregate_type = f.aggregate_type
     AND o.aggregate_id = f.aggregate_id
     AND o.deleted_at IS NULL
), outbox_stats AS (
    SELECT
        COUNT(*) AS total_count,
        COALESCE(SUM(is_side_effect = 1), 0) AS side_effect_count,
        COALESCE(SUM(status IN (0, 1, 5, 6)), 0) AS open_count,
        COALESCE(SUM(status = 3), 0) AS dead_count,
        COALESCE(SUM(status <> 2), 0) AS non_sent_count,
        COALESCE(SUM(retry_count > 0), 0) AS retry_count,
        COALESCE(SUM(protocol_backend IS NULL
                     OR UPPER(TRIM(protocol_backend)) <> 'ANDROID'), 0)
          AS non_android_count
    FROM command_scope
), duplicate_side_effect AS (
    SELECT COUNT(*) AS aggregate_count
    FROM (
        SELECT aggregate_type, aggregate_id
        FROM command_scope
        WHERE is_side_effect = 1
        GROUP BY aggregate_type, aggregate_id
        HAVING COUNT(DISTINCT command_id) > 1
    ) duplicated
), terminal_deltas AS (
    SELECT
      CASE
        WHEN t.status = 'COMPLETED'
         AND es.execution_count = 1
         AND es.completed_count = 1
        THEN GREATEST(7 - ast.total_count, 0)
           + GREATEST(1 - cs.total_count, 0)
           + GREATEST(1 - ms.total_count, 0)
           + GREATEST(1 - ats.total_count, 0)
           + GREATEST(1 - ws.total_count, 0)
           + GREATEST(8 - os.side_effect_count, 0)
           + GREATEST(8 + qs.total_count - os.total_count, 0)
        ELSE 0
      END AS missing_count,
      CASE
        WHEN t.status = 'COMPLETED'
         AND es.execution_count = 1
         AND es.completed_count = 1
        THEN GREATEST(ast.total_count - 7, 0)
           + GREATEST(cs.total_count - 1, 0)
           + GREATEST(ms.total_count - 1, 0)
           + GREATEST(ats.total_count - 1, 0)
           + GREATEST(ws.total_count - 1, 0)
           + GREATEST(qs.total_count - 1, 0)
           + GREATEST(os.side_effect_count - 8, 0)
           + GREATEST(os.total_count - (8 + qs.total_count), 0)
        ELSE 0
      END AS excess_count
    FROM target_task t
    CROSS JOIN execution_stats es
    CROSS JOIN action_stats ast
    CROSS JOIN call_stats cs
    CROSS JOIN query_stats qs
    CROSS JOIN material_stats ms
    CROSS JOIN attempt_stats ats
    CROSS JOIN wave_stats ws
    CROSS JOIN outbox_stats os
), terminal_stats AS (
    SELECT CASE
      WHEN t.status <> 'COMPLETED' THEN 0
      WHEN es.execution_count = 1
       AND es.completed_count = 1
       AND td.missing_count = 0
       AND td.excess_count = 0
       AND rs.manager_count = 1
       AND rs.puller_count = 1
       AND rs.station_count = 0
       AND rs.promoter_count = 1
       AND rs.controller_count = 0
       AND rs.android_count = 3
       AND rs.web_count = 0
       AND rs.other_backend_count = 0
       AND rs.health_bad = 0
       AND ast.success_count = 7
       AND ast.save_contact_count = 2
       AND ast.invite_count = 1
       AND ast.join_count = 1
       AND ast.promote_count = 1
       AND ast.open_member_add_count = 1
       AND ast.close_approval_count = 1
       AND cs.success_count = 1
       AND qs.total_count <= 1
       AND qs.non_success_count = 0
       AND ms.non_success_count = 0
       AND ats.non_success_count = 0
       AND ws.settled_count = 1
       AND os.non_sent_count = 0
       AND ds.aggregate_count = 0
       AND rs.unreleased_puller_count = 0
       AND es.occupied_group_count = 0
      THEN 0 ELSE 1 END AS terminal_shape_bad
    FROM target_task t
    CROSS JOIN execution_stats es
    CROSS JOIN role_stats rs
    CROSS JOIN action_stats ast
    CROSS JOIN call_stats cs
    CROSS JOIN query_stats qs
    CROSS JOIN material_stats ms
    CROSS JOIN attempt_stats ats
    CROSS JOIN wave_stats ws
    CROSS JOIN outbox_stats os
    CROSS JOIN duplicate_side_effect ds
    CROSS JOIN terminal_deltas td
)
SELECT
    'CANARY_SUMMARY' AS record_type,
    CONCAT_WS(' ',
      CONCAT('observedAt=', DATE_FORMAT(UTC_TIMESTAMP(3), '%Y-%m-%dT%H:%i:%s.%fZ')),
      CONCAT('taskId=', t.id),
      CONCAT('task=', t.status),
      CONCAT('executionId=', es.execution_id_label),
      CONCAT('executions=', es.execution_count),
      CONCAT('exec=', es.execution_status_label),
      CONCAT('stage=', es.stage_label),
      CONCAT('wait=', es.wait_label),
      CONCAT('reason=', es.reason_label),
      CONCAT('groupBanned=', es.group_banned_count),
      CONCAT('roles=M', rs.manager_count, '/P', rs.puller_count,
             '/S', rs.station_count, '/R', rs.promoter_count,
             '/C', rs.controller_count),
      CONCAT('backend=A', rs.android_count, '/W', rs.web_count,
             '/O', rs.other_backend_count),
      CONCAT('healthBad=', rs.health_bad),
      CONCAT('actions=', ast.total_count, '/open', ast.open_count,
             '/bad', ast.bad_count, '/retry', ast.retry_count,
             '/unexpected', ast.unexpected_count),
      CONCAT('calls=', cs.total_count, '/open', cs.open_count, '/bad', cs.bad_count),
      CONCAT('queries=', qs.total_count, '/open', qs.open_count,
             '/bad', qs.bad_count, '/retry', qs.retry_count),
      CONCAT('materials=', ms.total_count, '/bad', ms.bad_count,
             '/admin', ms.admin_required_count),
      CONCAT('outbox=', os.total_count, '/sideFx', os.side_effect_count,
             '/open', os.open_count, '/dead', os.dead_count,
             '/retry', os.retry_count, '/nonAndroid', os.non_android_count),
      CONCAT('duplicates=', ds.aggregate_count),
      CONCAT('attemptRetry=', ats.retry_count),
      CONCAT('unreleasedPuller=', rs.unreleased_puller_count),
      CONCAT('groupOccupied=', es.occupied_group_count),
      CONCAT('configBad=', ss.config_bad),
      CONCAT('terminalMissing=', td.missing_count),
      CONCAT('terminalExcess=', td.excess_count),
      CONCAT('terminalShapeBad=', ts.terminal_shape_bad),
      CONCAT('anomalies=',
        ss.config_bad
        + ts.terminal_shape_bad
        + IF(es.execution_count = 1, 0, 1)
        + es.wait_count + es.failed_or_group_banned_count
        + es.invalid_line_count + es.duplicate_line_count
        + IF(es.valid_member_count = 1, 0, 1)
        + rs.web_count + rs.other_backend_count + rs.health_bad
        + GREATEST(rs.manager_count - 1, 0)
        + GREATEST(rs.puller_count - 1, 0)
        + rs.station_count + GREATEST(rs.promoter_count - 1, 0) + rs.controller_count
        + ast.bad_count + ast.retry_count + ast.unexpected_count
        + GREATEST(ast.total_count - 7, 0)
        + cs.bad_count + GREATEST(cs.total_count - 1, 0)
        + qs.bad_count + qs.retry_count
        + ms.bad_count + ms.admin_required_count
        + ats.bad_count + ats.retry_count
        + os.dead_count + os.retry_count + os.non_android_count
        + GREATEST(os.side_effect_count - 8, 0)
        + ds.aggregate_count
        + IF(t.status IN ('COMPLETED', 'ENDED') AND ast.open_count > 0, ast.open_count, 0)
        + IF(t.status IN ('COMPLETED', 'ENDED') AND cs.open_count > 0, cs.open_count, 0)
        + IF(t.status IN ('COMPLETED', 'ENDED') AND qs.open_count > 0, qs.open_count, 0)
        + IF(t.status IN ('COMPLETED', 'ENDED') AND ats.open_count > 0, ats.open_count, 0)
        + IF(t.status IN ('COMPLETED', 'ENDED') AND ws.active_count > 0, ws.active_count, 0)
        + IF(t.status IN ('COMPLETED', 'ENDED') AND os.open_count > 0, os.open_count, 0)
        + IF(t.status IN ('COMPLETED', 'ENDED') AND rs.unreleased_puller_count > 0,
             rs.unreleased_puller_count, 0)
        + IF(t.status IN ('COMPLETED', 'ENDED') AND es.occupied_group_count > 0,
             es.occupied_group_count, 0)
      )
    ) AS summary
FROM target_task t
CROSS JOIN execution_stats es
CROSS JOIN setting_stats ss
CROSS JOIN role_stats rs
CROSS JOIN action_stats ast
CROSS JOIN call_stats cs
CROSS JOIN query_stats qs
CROSS JOIN material_stats ms
CROSS JOIN attempt_stats ats
CROSS JOIN wave_stats ws
CROSS JOIN outbox_stats os
CROSS JOIN duplicate_side_effect ds
CROSS JOIN terminal_deltas td
CROSS JOIN terminal_stats ts;
COMMIT;
SQL
}

render_sql() {
  printf 'SET @task_id := %s;\n' "${TASK_ID}"
  if [ -n "${EXECUTION_ID}" ]; then
    printf 'SET @execution_id := %s;\n' "${EXECUTION_ID}"
  else
    printf 'SET @execution_id := NULL;\n'
  fi
  if [ "${CANARY_LINE}" -eq 1 ]; then
    render_canary_summary_query
    return
  fi
  render_parameter_block
  render_summary_queries
  cat "${STATISTICS_SQL}"
  render_anomaly_query
}

remote_dir_quoted="$(shell_single_quote "${REMOTE_DIR}")"
expected_schema_quoted="$(shell_single_quote "${EXPECTED_DB_SCHEMA}")"
REMOTE_COMMAND="set -euo pipefail
remote_dir='${remote_dir_quoted}'
expected_schema='${expected_schema_quoted}'
cd \"\${remote_dir}\"
test -f .env || { echo 'ERR 远端缺少 .env' >&2; exit 40; }
read_env_value() {
  env_key=\"\$1\"
  env_value=\"\$(sed -n \"s/^\${env_key}=//p\" .env | tail -n 1 | tr -d '\\r')\"
  [ -n \"\${env_value}\" ] || {
    echo \"ERR 远端 .env 缺少 \${env_key}\" >&2
    exit 43
  }
  printf '%s' \"\${env_value}\"
}
DB_URL=\"\$(read_env_value DB_URL)\"
DB_USER=\"\$(read_env_value DB_USER)\"
DB_PASSWORD=\"\$(read_env_value DB_PASSWORD)\"
case \"\${DB_URL}\" in
  jdbc:mysql://*) ;;
  *) echo 'ERR DB_URL 不是 JDBC MySQL 地址' >&2; exit 41 ;;
esac
db_target=\"\${DB_URL#jdbc:mysql://}\"
db_authority=\"\${db_target%%/*}\"
db_name_query=\"\${db_target#*/}\"
db_name=\"\${db_name_query%%\\?*}\"
case \"\${db_authority}\" in
  *:*) db_host=\"\${db_authority%:*}\"; db_port=\"\${db_authority##*:}\" ;;
  *) db_host=\"\${db_authority}\"; db_port=3306 ;;
esac
[ \"\${db_name}\" = \"\${expected_schema}\" ] || {
  echo \"ERR 远端 schema 与环境档案不匹配\" >&2
  exit 42
}
runtime_status=unknown
runtime_created=unknown
runtime_image=unknown
if command -v docker >/dev/null 2>&1; then
  runtime_status=\"\$(docker inspect -f '{{.State.Status}}' armada-backend 2>/dev/null || true)\"
  runtime_created=\"\$(docker inspect -f '{{.Created}}' armada-backend 2>/dev/null || true)\"
  runtime_image=\"\$(docker inspect -f '{{.Image}}' armada-backend 2>/dev/null || true)\"
fi
printf 'RUNTIME\\t%s\\t%s\\t%s\\n' \
  \"\${runtime_status:-unknown}\" \"\${runtime_created:-unknown}\" \"\${runtime_image:-unknown}\"
MYSQL_PWD=\"\${DB_PASSWORD:?DB_PASSWORD is required}\" \
  mysql --no-defaults --connect-timeout=8 --default-character-set=utf8mb4 --batch --raw \
  -h \"\${db_host}\" -P \"\${db_port}\" -u \"\${DB_USER:?DB_USER is required}\" \"\${db_name}\""

RESULT_FILE="$(mktemp "${TMPDIR:-/tmp}/armada-pull-task-diagnosis.XXXXXX")"
cleanup() {
  rm -f "${RESULT_FILE}"
}
trap cleanup EXIT

SSH_ARGS=(
  -T
  -i "${SSH_KEY}"
  -o BatchMode=yes
  -o ConnectTimeout=8
  -o StrictHostKeyChecking=accept-new
  -o ControlMaster=no
  -o ControlPath=none
)
if ! render_sql | "${SSH_BIN}" "${SSH_ARGS[@]}" \
  "${SSH_USER}@${SSH_HOST}" "${REMOTE_COMMAND}" >"${RESULT_FILE}"; then
  die "诊断查询失败；未执行任何修改或恢复操作"
fi

if [ "${CANARY_LINE}" -eq 1 ]; then
  RUNTIME_STATUS="$(awk -F '\t' '
    $1 == "RUNTIME" {
      print $2
      exit
    }
  ' "${RESULT_FILE}")"
  case "${RUNTIME_STATUS}" in
    running) RUNTIME_BAD=0 ;;
    ''|*[!A-Za-z0-9_.-]*) RUNTIME_STATUS=unknown; RUNTIME_BAD=1 ;;
    *) RUNTIME_BAD=1 ;;
  esac
  CANARY_SUMMARY="$(awk -F '\t' '
    $1 == "CANARY_SUMMARY" {
      sub(/^[^\t]*\t/, "")
      print
      exit
    }
  ' "${RESULT_FILE}")"
  [ -n "${CANARY_SUMMARY}" ] \
    || die "在 ${SELECTED_ENV} 中找不到可归因的普通链接拉群任务 #${TASK_ID}"
  case "${CANARY_SUMMARY}" in
    *' anomalies='*) ;;
    *) die "金丝雀摘要缺少 anomalies 计数" ;;
  esac
  CANARY_PREFIX="${CANARY_SUMMARY% anomalies=*}"
  CANARY_ANOMALIES="${CANARY_SUMMARY##* anomalies=}"
  case "${CANARY_ANOMALIES}" in
    ''|*[!0-9]*) die "金丝雀摘要 anomalies 计数不合法" ;;
  esac
  printf 'env=%s runtime=%s runtimeBad=%s %s anomalies=%s\n' \
    "${SELECTED_ENV}" "${RUNTIME_STATUS}" "${RUNTIME_BAD}" \
    "${CANARY_PREFIX}" "$((CANARY_ANOMALIES + RUNTIME_BAD))"
  exit 0
fi

RUNTIME_STATUS=unknown
RUNTIME_CREATED=unknown
RUNTIME_IMAGE=unknown
TASK_TYPE=""
TASK_MODE=""
TASK_STATUS=""
TASK_STAGE="-"
TASK_GROUP_COUNT=0
TASK_EXPECTED_PULL_COUNT=0
TASK_UPDATED_AGE=0
EXECUTION_TOTAL=0
EXECUTION_ACTIVE=0
EXECUTION_WAIT_RESOURCE=0
EXECUTION_TERMINAL=0
EXECUTION_DUE=0
EXECUTION_NOT_DUE=0
FACT_ACTIONS=0
FACT_ACTION_COMMANDS=0
FACT_CALLS=0
FACT_CALL_COMMANDS=0
FACT_MATERIALS=0
FACT_UNRELEASED_PULLERS=0
FACT_BACKENDS="-"
RECONCILE_OVERDUE_SECONDS=180
MARKETING_TARGET=""
MARKETING_WAITING=0
MARKETING_RUNNING=0
MARKETING_FAILED=0
MARKETING_REMAINING=0
MARKETING_MESSAGE_FAILED=0
MARKETING_MESSAGE_UNKNOWN=0
ANOMALY_COUNT=0
ACTIONABLE_COUNT=0
ANOMALY_LINES=()

while IFS=$'\t' read -r column1 column2 column3 column4 column5 column6 column7 column8 column9 column10 rest; do
  case "${column1}" in
    RUNTIME)
      RUNTIME_STATUS="${column2:-unknown}"
      RUNTIME_CREATED="${column3:-unknown}"
      RUNTIME_IMAGE="${column4:-unknown}"
      ;;
    TASK)
      TASK_TYPE="${column3}"
      TASK_MODE="${column4}"
      TASK_STATUS="${column5}"
      TASK_STAGE="${column6}"
      TASK_GROUP_COUNT="${column7}"
      TASK_EXPECTED_PULL_COUNT="${column8}"
      TASK_UPDATED_AGE="${column9}"
      ;;
    EXECUTIONS)
      EXECUTION_TOTAL="${column2}"
      EXECUTION_ACTIVE="${column3}"
      EXECUTION_WAIT_RESOURCE="${column4}"
      EXECUTION_TERMINAL="${column5}"
      EXECUTION_DUE="${column6}"
      EXECUTION_NOT_DUE="${column7}"
      ;;
    THRESHOLDS)
      RECONCILE_OVERDUE_SECONDS="${column2}"
      ;;
    FACTS)
      FACT_ACTIONS="${column2}"
      FACT_ACTION_COMMANDS="${column3}"
      FACT_CALLS="${column4}"
      FACT_CALL_COMMANDS="${column5}"
      FACT_MATERIALS="${column6}"
      FACT_UNRELEASED_PULLERS="${column7}"
      FACT_BACKENDS="${column8}"
      ;;
    MARKETING)
      MARKETING_TARGET="${column2}"
      MARKETING_WAITING="${column3}"
      MARKETING_RUNNING="${column4}"
      MARKETING_FAILED="${column5}"
      MARKETING_REMAINING="${column6}"
      MARKETING_MESSAGE_FAILED="${column7}"
      MARKETING_MESSAGE_UNKNOWN="${column8}"
      ;;
    record_type|category|'') ;;
    *)
      ANOMALY_COUNT=$((ANOMALY_COUNT + 1))
      anomaly_category="${column1}"
      anomaly_execution_id="${column3}"
      anomaly_command_id="${column5}"
      anomaly_diagnosis="${column6}"
      anomaly_stall_seconds="${column7}"
      candidate_note=""
      if [ "${anomaly_category}" = WAIT_RESOURCE ] \
        && [[ "${anomaly_diagnosis}" == *RETRYING* ]]; then
        candidate_note="正常重试中"
      elif [ "${anomaly_category}" = UNKNOWN_RESULT ]; then
        case "${anomaly_stall_seconds}" in
          ''|*[!0-9]*) ;;
          *)
            if [ "${anomaly_stall_seconds}" -lt "${RECONCILE_OVERDUE_SECONDS}" ]; then
              candidate_note="结果收敛中"
            fi
            ;;
        esac
      fi
      if [ -z "${candidate_note}" ]; then
        ACTIONABLE_COUNT=$((ACTIONABLE_COUNT + 1))
      fi
      anomaly_line="  - ${anomaly_category} executionId=${anomaly_execution_id}"
      if [ "${anomaly_command_id}" != NULL ] && [ -n "${anomaly_command_id}" ]; then
        anomaly_line="${anomaly_line} commandId=${anomaly_command_id}"
      fi
      if [ -n "${candidate_note}" ]; then
        anomaly_line="${anomaly_line} (${candidate_note})"
      else
        anomaly_line="${anomaly_line} age=${anomaly_stall_seconds}s"
      fi
      if [ "${#ANOMALY_LINES[@]}" -lt 8 ]; then
        ANOMALY_LINES+=("${anomaly_line}")
      fi
      ;;
  esac
done <"${RESULT_FILE}"

[ -n "${TASK_TYPE}" ] \
  || die "在 ${SELECTED_ENV} 的 pull_task 中找不到 #${TASK_ID}（或任务已删除）"

printf '拉群任务只读诊断\n'
printf '  环境: %s\n' "${SELECTED_ENV}"
printf '  任务: #%s\n' "${TASK_ID}"
[ -z "${EXECUTION_ID}" ] || printf '  执行行过滤: %s\n' "${EXECUTION_ID}"
[ -z "${OBSERVED_AT}" ] || printf '  测试时间: %s\n' "${OBSERVED_AT}"
[ -z "${SYMPTOM}" ] || printf '  现象: %s\n' "${SYMPTOM}"
printf '  运行时: backend=%s created=%s image=%s\n' \
  "${RUNTIME_STATUS}" "${RUNTIME_CREATED}" "${RUNTIME_IMAGE}"

case "${TASK_TYPE}/${TASK_MODE}" in
  STANDARD/NORMAL_LINK)
    printf '  类型: 普通链接拉群 (STANDARD/NORMAL_LINK)\n'
    printf '  状态: %s / 阶段=%s / 任务更新距今=%ss\n' \
      "${TASK_STATUS}" "${TASK_STAGE}" "${TASK_UPDATED_AGE}"
    printf '  执行行: 总数=%s 活动=%s 等资源=%s 终态=%s 已到期=%s 未到期=%s\n' \
      "${EXECUTION_TOTAL}" "${EXECUTION_ACTIVE}" "${EXECUTION_WAIT_RESOURCE}" \
      "${EXECUTION_TERMINAL}" "${EXECUTION_DUE}" "${EXECUTION_NOT_DUE}"
    printf '  事实统计: 动作=%s 动作命令=%s 拉人调用=%s 拉人命令=%s 料子=%s 未释放拉手=%s 后端=%s\n' \
      "${FACT_ACTIONS}" "${FACT_ACTION_COMMANDS}" "${FACT_CALLS}" "${FACT_CALL_COMMANDS}" \
      "${FACT_MATERIALS}" "${FACT_UNRELEASED_PULLERS}" "${FACT_BACKENDS}"
    if [ "${ANOMALY_COUNT}" -eq 0 ]; then
      printf '  结论: 未发现超过宽限期的异常候选\n'
    elif [ "${ACTIONABLE_COUNT}" -eq 0 ]; then
      printf '  结论: 发现 %s 条候选，均处于正常等待/重试/结果收敛中\n' "${ANOMALY_COUNT}"
    else
      printf '  结论: 发现 %s 条候选，其中 %s 条需要继续处理\n' \
        "${ANOMALY_COUNT}" "${ACTIONABLE_COUNT}"
    fi
    if [ "${ANOMALY_COUNT}" -gt 0 ]; then
      printf '  证据:\n'
      for anomaly_line in "${ANOMALY_LINES[@]}"; do
        printf '%s\n' "${anomaly_line}"
      done
      if [ "${ANOMALY_COUNT}" -gt "${#ANOMALY_LINES[@]}" ]; then
        printf '  - 其余 %s 条已省略，请用 --execution-id 收窄\n' \
          "$((ANOMALY_COUNT - ${#ANOMALY_LINES[@]}))"
      fi
    fi
    if [ "${ACTIONABLE_COUNT}" -gt 0 ]; then
      printf '  下一步: 优先按上述 executionId/commandId 进入 Armada、Outbox 或协议日志定点核对\n'
    else
      printf '  下一步: 结合页面现象观察排期；无需盲查协议层\n'
    fi
    ;;
  GROUP_MARKETING/*)
    printf '  类型: 拉群营销 (GROUP_MARKETING/%s)\n' "${TASK_MODE}"
    printf '  状态: %s / 阶段=%s / 任务更新距今=%ss\n' \
      "${TASK_STATUS}" "${TASK_STAGE}" "${TASK_UPDATED_AGE}"
    printf '  自动分流: 已跳过普通链接拉群的七阶段判断\n'
    if [ -n "${MARKETING_TARGET}" ]; then
      printf '  群进度: 目标=%s 等待=%s 执行中=%s 失败=%s\n' \
        "${MARKETING_TARGET}" "${MARKETING_WAITING}" "${MARKETING_RUNNING}" "${MARKETING_FAILED}"
      printf '  拉人/营销: 剩余目标=%s 消息失败=%s 消息未知=%s\n' \
        "${MARKETING_REMAINING}" "${MARKETING_MESSAGE_FAILED}" "${MARKETING_MESSAGE_UNKNOWN}"
      printf '  结论: 已完成任务类型和任务级聚合判断\n'
    else
      printf '  结论: 未找到拉群营销聚合行，需核对任务初始化\n'
    fi
    printf '  下一步: 如需深查，沿拉群营销执行/料子/营销状态机定点追踪\n'
    ;;
  *)
    printf '  类型: %s/%s\n' "${TASK_TYPE}" "${TASK_MODE}"
    printf '  状态: %s / 阶段=%s / 任务更新距今=%ss\n' \
      "${TASK_STATUS}" "${TASK_STAGE}" "${TASK_UPDATED_AGE}"
    printf '  结论: 已识别任务，但当前快速诊断未定义该模式的状态机\n'
    printf '  下一步: 不套用普通链接拉群规则，转对应业务手册\n'
    ;;
esac
