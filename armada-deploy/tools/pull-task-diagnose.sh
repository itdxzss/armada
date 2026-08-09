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

参数:
  --env             必填，只允许 test1 或 perf2；该参数就是目标环境确认。
  --task-id         必填，页面列表显示的 #任务号，即 pull_task.id。
  --execution-id    可选，只收窄普通链接拉群的群执行行。
  --observed-at     可选，测试现象的时间，仅在本次摘要中回显。
  --symptom         可选，页面现象，仅在本次摘要中回显。
  -h, --help        显示帮助。

安全边界:
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

render_sql() {
  printf 'SET @task_id := %s;\n' "${TASK_ID}"
  render_parameter_block
  if [ -n "${EXECUTION_ID}" ]; then
    printf 'SET @execution_id := %s;\n' "${EXECUTION_ID}"
  else
    printf 'SET @execution_id := NULL;\n'
  fi
  render_summary_queries
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
  mysql --connect-timeout=8 --default-character-set=utf8mb4 --batch --raw \
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
