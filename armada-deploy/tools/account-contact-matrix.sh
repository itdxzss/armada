#!/usr/bin/env bash
if [ -z "${BASH_VERSION:-}" ]; then
  exec /usr/bin/env bash "$0" "$@"
fi
set -euo pipefail

CONTACT_MATRIX_ANDROID_CONCURRENCY="${CONTACT_MATRIX_ANDROID_CONCURRENCY:-6}"
CONTACT_MATRIX_WEB_CONCURRENCY="${CONTACT_MATRIX_WEB_CONCURRENCY:-2}"
CONTACT_MATRIX_ANDROID_INTERVAL_MIN_SECONDS="${CONTACT_MATRIX_ANDROID_INTERVAL_MIN_SECONDS:-1}"
CONTACT_MATRIX_ANDROID_INTERVAL_MAX_SECONDS="${CONTACT_MATRIX_ANDROID_INTERVAL_MAX_SECONDS:-3}"
CONTACT_MATRIX_WEB_INTERVAL_MIN_SECONDS="${CONTACT_MATRIX_WEB_INTERVAL_MIN_SECONDS:-1}"
CONTACT_MATRIX_WEB_INTERVAL_MAX_SECONDS="${CONTACT_MATRIX_WEB_INTERVAL_MAX_SECONDS:-3}"
CONTACT_MATRIX_RATE_LIMIT_BASE_SECONDS="${CONTACT_MATRIX_RATE_LIMIT_BASE_SECONDS:-60}"
CONTACT_MATRIX_RATE_LIMIT_MAX_SECONDS="${CONTACT_MATRIX_RATE_LIMIT_MAX_SECONDS:-300}"
CONTACT_MATRIX_MAX_RATE_LIMIT_RETRIES="${CONTACT_MATRIX_MAX_RATE_LIMIT_RETRIES:-3}"
CONTACT_MATRIX_MAX_TRANSIENT_RETRIES="${CONTACT_MATRIX_MAX_TRANSIENT_RETRIES:-2}"
CONTACT_MATRIX_POLL_SECONDS="${CONTACT_MATRIX_POLL_SECONDS:-0.20}"
CONTACT_MATRIX_TRACE_FILE="${CONTACT_MATRIX_TRACE_FILE:-}"

contact_fail() {
  printf 'ERR %s\n' "$*" >&2
  return 1
}

contact_require_positive_integer() {
  local label="$1"
  local value="$2"
  case "${value}" in
    ''|*[!0-9]*|0) contact_fail "${label}必须是正整数: ${value}" ;;
  esac
}

contact_require_nonnegative_integer() {
  local label="$1"
  local value="$2"
  case "${value}" in
    ''|*[!0-9]*) contact_fail "${label}必须是非负整数: ${value}" ;;
  esac
}

contact_require_digits() {
  local label="$1"
  local value="$2"
  case "${value}" in
    ''|*[!0-9]*) contact_fail "${label}必须是纯数字" ;;
  esac
}

contact_validate_ledger_path() {
  local ledger_file="$1"
  case "${ledger_file}" in
    /tmp/armada-contact-matrix-*.jsonl) ;;
    *) contact_fail "账本必须位于 /tmp 且使用 armada-contact-matrix-*.jsonl 命名"; return 2 ;;
  esac
  case "${ledger_file#/tmp/}" in
    */*) contact_fail "账本必须是 /tmp 直属文件"; return 2 ;;
  esac
  case "${ledger_file}" in
    *[!A-Za-z0-9._/-]*) contact_fail "账本路径含非法字符"; return 2 ;;
  esac
}

contact_validate_jsonl() {
  local ledger_file="$1"
  [ ! -s "${ledger_file}" ] || jq -e . "${ledger_file}" >/dev/null 2>&1
}

contact_ledger_has_dry_run() {
  local ledger_file="$1"
  jq -e 'select(.recordType=="PREFLIGHT" and .mode=="dry-run")' \
    "${ledger_file}" >/dev/null 2>&1
}

contact_cleanup_run_dir() {
  local run_dir="$1"
  local pid_file worker_pid
  case "${run_dir}" in
    /tmp/armada-contact-matrix-run.*)
      for pid_file in "${run_dir}/scheduler/pids/"*.pid; do
        [ -e "${pid_file}" ] || continue
        worker_pid="$(cat "${pid_file}" 2>/dev/null || true)"
        case "${worker_pid}" in
          ''|*[!0-9]*) continue ;;
        esac
        kill "${worker_pid}" 2>/dev/null || true
        wait "${worker_pid}" 2>/dev/null || true
      done
      rm -rf -- "${run_dir}"
      ;;
    *) contact_fail "拒绝清理非联系人任务临时目录: ${run_dir}" ;;
  esac
}

contact_trace() {
  [ -n "${CONTACT_MATRIX_TRACE_FILE}" ] || return 0
  printf '%b\n' "$*" >>"${CONTACT_MATRIX_TRACE_FILE}"
}

contact_now_epoch() {
  date +%s
}

contact_now_iso() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

contact_random_between() {
  local minimum="$1"
  local maximum="$2"
  local span
  if [ "${maximum}" -le "${minimum}" ]; then
    printf '%s\n' "${minimum}"
    return 0
  fi
  span=$((maximum - minimum + 1))
  printf '%s\n' "$((minimum + RANDOM % span))"
}

contact_protocol_interval() {
  local protocol="$1"
  case "${protocol}" in
    ANDROID)
      contact_random_between \
        "${CONTACT_MATRIX_ANDROID_INTERVAL_MIN_SECONDS}" \
        "${CONTACT_MATRIX_ANDROID_INTERVAL_MAX_SECONDS}"
      ;;
    WEB)
      contact_random_between \
        "${CONTACT_MATRIX_WEB_INTERVAL_MIN_SECONDS}" \
        "${CONTACT_MATRIX_WEB_INTERVAL_MAX_SECONDS}"
      ;;
    *) contact_fail "不支持的联系人协议: ${protocol}" ;;
  esac
}

contact_rate_limit_delay() {
  local attempt="$1"
  local delay="${CONTACT_MATRIX_RATE_LIMIT_BASE_SECONDS}"
  local index=1
  while [ "${index}" -lt "${attempt}" ]; do
    delay=$((delay * 2))
    if [ "${delay}" -ge "${CONTACT_MATRIX_RATE_LIMIT_MAX_SECONDS}" ]; then
      delay="${CONTACT_MATRIX_RATE_LIMIT_MAX_SECONDS}"
      break
    fi
    index=$((index + 1))
  done
  printf '%s\n' "${delay}"
}

contact_write_result() {
  local result_file="$1"
  local outcome="$2"
  local http_status="$3"
  local business_code="$4"
  local reason_class="$5"
  local request_id="$6"
  local temporary="${result_file}.tmp.$$"
  jq -nc \
    --arg outcome "${outcome}" \
    --argjson httpStatus "${http_status}" \
    --arg businessCode "${business_code}" \
    --arg reasonClass "${reason_class}" \
    --arg requestId "${request_id}" \
    '{outcome:$outcome,httpStatus:$httpStatus,businessCode:$businessCode,reasonClass:$reasonClass,requestId:$requestId}' \
    >"${temporary}"
  mv "${temporary}" "${result_file}"
}

contact_android_rate_limited() {
  local business_code="$1"
  local application_message="$2"
  local normalized_message
  normalized_message="$(printf '%s' "${application_message}" | tr '[:upper:]' '[:lower:]')"
  case "${business_code} ${normalized_message}" in
    429\ *|*rate-overlimit*|*"code: 429"*) return 0 ;;
  esac
  return 1
}

if ! declare -F contact_execute_request >/dev/null 2>&1; then
  contact_execute_request() {
    local task_file="$1"
    local result_file="$2"
    local attempt="$3"
    local direction actor_id target_id protocol protocol_id actor_phone target_phone
    local body business_code application_message curl_status http_status outcome reason request_id
    local response_file header_file status_file
    local -a android_auth_headers
    IFS=$'\t' read -r \
      direction actor_id target_id protocol protocol_id actor_phone target_phone \
      <"${task_file}"
    : "${direction}" "${actor_id}" "${target_id}" "${attempt}"

    response_file="${result_file}.response"
    header_file="${result_file}.headers"
    status_file="${result_file}.status"
    : >"${response_file}"
    : >"${header_file}"
    : >"${status_file}"
    curl_status=0
    http_status=0
    business_code=""
    application_message=""
    request_id=""

    case "${protocol}" in
      ANDROID)
        body="$(jq -nc --arg number "${target_phone}" '{Numbers:[$number]}')"
        android_auth_headers=()
        if [ -n "${CONTACT_MATRIX_ANDROID_API_KEY:-}" ]; then
          android_auth_headers=(-H "x-api-key: ${CONTACT_MATRIX_ANDROID_API_KEY}")
        fi
        if curl -sS --connect-timeout 10 --max-time 40 \
          "${android_auth_headers[@]}" \
          -D "${header_file}" -o "${response_file}" -w '%{http_code}' \
          -H 'Content-Type: application/json' \
          -X POST "${CONTACT_MATRIX_ANDROID_BASE_URL}/ws/v1/contacts/add/${actor_phone}" \
          --data "${body}" >"${status_file}" 2>/dev/null; then
          curl_status=0
        else
          curl_status=$?
        fi
        business_code="$(jq -r 'if has("Code") then .Code elif has("code") then .code else "" end' \
          "${response_file}" 2>/dev/null || true)"
        application_message="$(jq -r \
          '[(.Msg // .msg // ""),(.Data // .data // "")] | map(tostring) | join(" ")' \
          "${response_file}" 2>/dev/null || true)"
        ;;
      WEB)
        body="$(jq -nc --arg accountId "${protocol_id}" \
          '{accountId:$accountId,contact:{fullName:"contact",saveOnPrimaryAddressbook:true}}')"
        if curl -sS --connect-timeout 10 --max-time 40 \
          -D "${header_file}" -o "${response_file}" -w '%{http_code}' \
          -H 'Content-Type: application/json' \
          -H "x-api-key: ${CONTACT_MATRIX_WEB_API_KEY}" \
          -X POST "${CONTACT_MATRIX_WEB_BASE_URL}/v1/contacts/${target_phone}%40s.whatsapp.net/save" \
          --data "${body}" >"${status_file}" 2>/dev/null; then
          curl_status=0
        else
          curl_status=$?
        fi
        ;;
      *)
        contact_write_result "${result_file}" "FAILED" 0 "" "UNSUPPORTED_PROTOCOL" ""
        return 0
        ;;
    esac

    http_status="$(cat "${status_file}" 2>/dev/null || true)"
    case "${http_status}" in
      ''|*[!0-9]*) http_status=0 ;;
      *) http_status=$((10#${http_status})) ;;
    esac
    request_id="$(awk 'BEGIN{IGNORECASE=1} /^x-request-id:|^request-id:/ {gsub(/\r/,"",$2);print $2;exit}' \
      "${header_file}" 2>/dev/null || true)"

    if [ "${curl_status}" -ne 0 ]; then
      outcome="RETRYABLE"
      reason="TRANSPORT_ERROR"
    elif [ "${http_status}" -eq 429 ]; then
      outcome="RATE_LIMITED"
      reason="HTTP_429"
    elif [ "${protocol}" = "ANDROID" ] \
      && contact_android_rate_limited "${business_code}" "${application_message}"; then
      outcome="RATE_LIMITED"
      reason="ANDROID_RATE_LIMITED"
    elif [ "${protocol}" = "ANDROID" ] \
      && [ "${http_status}" -ge 200 ] && [ "${http_status}" -lt 300 ] \
      && [ "${business_code}" = "0" ]; then
      outcome="SUCCESS"
      reason=""
    elif [ "${protocol}" = "WEB" ] \
      && [ "${http_status}" -ge 200 ] && [ "${http_status}" -lt 300 ]; then
      outcome="SUCCESS"
      reason=""
    elif [ "${http_status}" -ge 500 ]; then
      outcome="RETRYABLE"
      reason="HTTP_5XX"
    elif [ "${protocol}" = "ANDROID" ] \
      && [ "${http_status}" -ge 200 ] && [ "${http_status}" -lt 300 ]; then
      outcome="FAILED"
      reason="ANDROID_APP_FAILURE"
    elif [ "${http_status}" -ge 400 ]; then
      outcome="FAILED"
      reason="HTTP_4XX"
    else
      outcome="FAILED"
      reason="UNEXPECTED_RESPONSE"
    fi
    contact_write_result \
      "${result_file}" "${outcome}" "${http_status}" \
      "${business_code}" "${reason}" "${request_id}"
    rm -f "${response_file}" "${header_file}" "${status_file}"
  }
fi

contact_success_index_key() {
  printf '%s\t%s\t%s\n' "$1" "$2" "$3"
}

contact_build_success_index() {
  local ledger_file="$1"
  local index_file="$2"
  if [ -s "${ledger_file}" ]; then
    jq -r 'select(.recordType=="CONTACT_ATTEMPT" and .outcome=="SUCCESS") | [.direction,.actorAccountId,.targetAccountId] | @tsv' \
      "${ledger_file}" 2>/dev/null | sort -u >"${index_file}"
  else
    : >"${index_file}"
  fi
}

contact_queue_advance() {
  local queue_file="$1"
  local temporary="${queue_file}.next.$$"
  tail -n +2 "${queue_file}" >"${temporary}"
  mv "${temporary}" "${queue_file}"
}

contact_active_count() {
  local work_dir="$1"
  local expected_protocol="$2"
  local pid_file protocol_file protocol count=0
  for pid_file in "${work_dir}/pids/"*.pid; do
    [ -e "${pid_file}" ] || continue
    protocol_file="${pid_file%.pid}.protocol"
    protocol="$(cat "${protocol_file}")"
    if [ "${protocol}" = "${expected_protocol}" ]; then
      count=$((count + 1))
    fi
  done
  printf '%s\n' "${count}"
}

contact_protocol_has_slot() {
  local work_dir="$1"
  local protocol="$2"
  local active limit
  active="$(contact_active_count "${work_dir}" "${protocol}")"
  case "${protocol}" in
    WEB) limit="${CONTACT_MATRIX_WEB_CONCURRENCY}" ;;
    ANDROID) limit="${CONTACT_MATRIX_ANDROID_CONCURRENCY}" ;;
    *) return 1 ;;
  esac
  [ "${active}" -lt "${limit}" ]
}

contact_append_attempt() {
  local ledger_file="$1"
  local task_file="$2"
  local result_file="$3"
  local attempt="$4"
  local direction actor_id target_id protocol protocol_id actor_phone target_phone
  local result
  IFS=$'\t' read -r \
    direction actor_id target_id protocol protocol_id actor_phone target_phone \
    <"${task_file}"
  : "${protocol_id}" "${actor_phone}" "${target_phone}"
  result="$(cat "${result_file}")"
  jq -nc \
    --arg direction "${direction}" \
    --argjson actorAccountId "${actor_id}" \
    --argjson targetAccountId "${target_id}" \
    --arg actorProtocol "${protocol}" \
    --argjson attempt "${attempt}" \
    --argjson result "${result}" \
    --arg attemptedAt "$(contact_now_iso)" \
    '{recordType:"CONTACT_ATTEMPT",direction:$direction,actorAccountId:$actorAccountId,targetAccountId:$targetAccountId,actorProtocol:$actorProtocol,attempt:$attempt,outcome:$result.outcome,httpStatus:$result.httpStatus,businessCode:$result.businessCode,reasonClass:$result.reasonClass,requestId:$result.requestId,attemptedAt:$attemptedAt}' \
    >>"${ledger_file}"
}

contact_dispatch_actor() {
  local actor_id="$1"
  local protocol="$2"
  local work_dir="$3"
  local queue_file task_file result_file attempt_file pid_file
  local attempt pid target_id web_active android_active
  queue_file="${work_dir}/queues/${actor_id}.tsv"
  task_file="${work_dir}/running/${actor_id}.task"
  result_file="${work_dir}/running/${actor_id}.result"
  attempt_file="${work_dir}/state/${actor_id}.attempt"
  pid_file="${work_dir}/pids/${actor_id}.pid"
  head -n 1 "${queue_file}" >"${task_file}"
  attempt="$(cat "${attempt_file}")"
  IFS=$'\t' read -r _ _ target_id _ _ _ _ <"${task_file}"
  contact_execute_request "${task_file}" "${result_file}" "${attempt}" &
  pid=$!
  printf '%s\n' "${pid}" >"${pid_file}"
  printf '%s\n' "${protocol}" >"${work_dir}/pids/${actor_id}.protocol"
  web_active="$(contact_active_count "${work_dir}" WEB)"
  android_active="$(contact_active_count "${work_dir}" ANDROID)"
  contact_trace "DISPATCH\t${actor_id}\t${target_id}\t${protocol}\t${web_active}\t${android_active}"
}

contact_reap_finished() {
  local work_dir="$1"
  local ledger_file="$2"
  local pid_file actor_id pid protocol task_file result_file attempt_file queue_file
  local attempt outcome target_id delay next_at now web_active android_active
  for pid_file in "${work_dir}/pids/"*.pid; do
    [ -e "${pid_file}" ] || continue
    actor_id="$(basename "${pid_file}" .pid)"
    pid="$(cat "${pid_file}")"
    if kill -0 "${pid}" 2>/dev/null; then
      continue
    fi
    if ! wait "${pid}" 2>/dev/null; then
      :
    fi
    protocol="$(cat "${work_dir}/pids/${actor_id}.protocol")"
    task_file="${work_dir}/running/${actor_id}.task"
    result_file="${work_dir}/running/${actor_id}.result"
    attempt_file="${work_dir}/state/${actor_id}.attempt"
    queue_file="${work_dir}/queues/${actor_id}.tsv"
    attempt="$(cat "${attempt_file}")"
    IFS=$'\t' read -r _ _ target_id _ _ _ _ <"${task_file}"
    if [ ! -s "${result_file}" ] || ! jq -e . "${result_file}" >/dev/null 2>&1; then
      contact_write_result "${result_file}" "RETRYABLE" 0 "" "WORKER_EXITED_WITHOUT_RESULT" ""
    fi
    contact_append_attempt "${ledger_file}" "${task_file}" "${result_file}" "${attempt}"
    outcome="$(jq -r '.outcome' "${result_file}")"
    now="$(contact_now_epoch)"
    case "${outcome}" in
      SUCCESS|FAILED)
        contact_queue_advance "${queue_file}"
        printf '1\n' >"${attempt_file}"
        delay="$(contact_protocol_interval "${protocol}")"
        printf '%s\n' "$((now + delay))" >"${work_dir}/state/${actor_id}.next"
        ;;
      RATE_LIMITED)
        if [ "${attempt}" -ge "${CONTACT_MATRIX_MAX_RATE_LIMIT_RETRIES}" ]; then
          contact_queue_advance "${queue_file}"
          printf '1\n' >"${attempt_file}"
          delay="${CONTACT_MATRIX_RATE_LIMIT_MAX_SECONDS}"
        else
          delay="$(contact_rate_limit_delay "${attempt}")"
          printf '%s\n' "$((attempt + 1))" >"${attempt_file}"
        fi
        printf '%s\n' "$((now + delay))" >"${work_dir}/state/${actor_id}.next"
        ;;
      RETRYABLE)
        if [ "${attempt}" -ge "${CONTACT_MATRIX_MAX_TRANSIENT_RETRIES}" ]; then
          contact_queue_advance "${queue_file}"
          printf '1\n' >"${attempt_file}"
          delay="$(contact_protocol_interval "${protocol}")"
        else
          delay=$((5 * attempt))
          printf '%s\n' "$((attempt + 1))" >"${attempt_file}"
        fi
        printf '%s\n' "$((now + delay))" >"${work_dir}/state/${actor_id}.next"
        ;;
      *)
        contact_queue_advance "${queue_file}"
        printf '1\n' >"${attempt_file}"
        printf '%s\n' "${now}" >"${work_dir}/state/${actor_id}.next"
        ;;
    esac
    rm -f \
      "${pid_file}" "${work_dir}/pids/${actor_id}.protocol" \
      "${task_file}" "${result_file}"
    web_active="$(contact_active_count "${work_dir}" WEB)"
    android_active="$(contact_active_count "${work_dir}" ANDROID)"
    contact_trace "COMPLETE\t${actor_id}\t${target_id}\t${protocol}\t${outcome}\t${web_active}\t${android_active}"
  done
}

CONTACT_ACTOR_IDS=()
CONTACT_ACTOR_PROTOCOLS=()
CONTACT_SCHEDULER_CURSOR=0

contact_prepare_scheduler() {
  local tasks_file="$1"
  local ledger_file="$2"
  local work_dir="$3"
  local success_index direction actor_id target_id protocol protocol_id actor_phone target_phone
  local actor_meta existing_protocol key
  mkdir "${work_dir}"
  mkdir \
    "${work_dir}/queues" "${work_dir}/state" \
    "${work_dir}/pids" "${work_dir}/running"
  success_index="${work_dir}/success-index.tsv"
  contact_build_success_index "${ledger_file}" "${success_index}"
  : >"${work_dir}/actors.tsv"
  while IFS=$'\t' read -r \
    direction actor_id target_id protocol protocol_id actor_phone target_phone; do
    [ -n "${direction}" ] || continue
    contact_require_positive_integer "actorAccountId" "${actor_id}"
    contact_require_positive_integer "targetAccountId" "${target_id}"
    contact_require_digits "actorPhone" "${actor_phone}"
    contact_require_digits "targetPhone" "${target_phone}"
    case "${protocol}" in WEB|ANDROID) ;; *) contact_fail "非法协议: ${protocol}"; return 1 ;; esac
    key="$(contact_success_index_key "${direction}" "${actor_id}" "${target_id}")"
    if grep -Fqx -- "${key}" "${success_index}"; then
      continue
    fi
    actor_meta="${work_dir}/state/${actor_id}.protocol"
    if [ -f "${actor_meta}" ]; then
      existing_protocol="$(cat "${actor_meta}")"
      [ "${existing_protocol}" = "${protocol}" ] \
        || { contact_fail "账号 ${actor_id} 的协议在同一批次中不一致"; return 1; }
    else
      printf '%s\n' "${protocol}" >"${actor_meta}"
      printf '1\n' >"${work_dir}/state/${actor_id}.attempt"
      printf '0\n' >"${work_dir}/state/${actor_id}.next"
      printf '%s\t%s\n' "${actor_id}" "${protocol}" >>"${work_dir}/actors.tsv"
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "${direction}" "${actor_id}" "${target_id}" "${protocol}" \
      "${protocol_id}" "${actor_phone}" "${target_phone}" \
      >>"${work_dir}/queues/${actor_id}.tsv"
  done <"${tasks_file}"

  CONTACT_ACTOR_IDS=()
  CONTACT_ACTOR_PROTOCOLS=()
  while IFS=$'\t' read -r actor_id protocol; do
    CONTACT_ACTOR_IDS+=("${actor_id}")
    CONTACT_ACTOR_PROTOCOLS+=("${protocol}")
  done <"${work_dir}/actors.tsv"
  CONTACT_SCHEDULER_CURSOR=0
}

contact_try_dispatch_one() {
  local work_dir="$1"
  local actor_count scanned index actor_id protocol queue_file next_at now
  actor_count="${#CONTACT_ACTOR_IDS[@]}"
  [ "${actor_count}" -gt 0 ] || return 1
  scanned=0
  now="$(contact_now_epoch)"
  while [ "${scanned}" -lt "${actor_count}" ]; do
    index=$(((CONTACT_SCHEDULER_CURSOR + scanned) % actor_count))
    actor_id="${CONTACT_ACTOR_IDS[${index}]}"
    protocol="${CONTACT_ACTOR_PROTOCOLS[${index}]}"
    queue_file="${work_dir}/queues/${actor_id}.tsv"
    if [ -s "${queue_file}" ] \
      && [ ! -f "${work_dir}/pids/${actor_id}.pid" ]; then
      next_at="$(cat "${work_dir}/state/${actor_id}.next")"
      if [ "${next_at}" -le "${now}" ] \
        && contact_protocol_has_slot "${work_dir}" "${protocol}"; then
        contact_dispatch_actor "${actor_id}" "${protocol}" "${work_dir}"
        CONTACT_SCHEDULER_CURSOR=$(((index + 1) % actor_count))
        return 0
      fi
    fi
    scanned=$((scanned + 1))
  done
  return 1
}

contact_scheduler_has_work() {
  local work_dir="$1"
  local actor_id pid_file
  for actor_id in "${CONTACT_ACTOR_IDS[@]}"; do
    [ -s "${work_dir}/queues/${actor_id}.tsv" ] && return 0
  done
  for pid_file in "${work_dir}/pids/"*.pid; do
    [ -e "${pid_file}" ] && return 0
  done
  return 1
}

contact_append_run_summary() {
  local ledger_file="$1"
  local tasks_file="$2"
  local planned attempts succeeded unresolved rate_limited failed
  planned="$(awk 'NF > 0 { count++ } END { print count + 0 }' "${tasks_file}")"
  attempts="$(jq -s '[.[] | select(.recordType=="CONTACT_ATTEMPT")] | length' "${ledger_file}")"
  succeeded="$(jq -s '[.[] | select(.recordType=="CONTACT_ATTEMPT" and .outcome=="SUCCESS")] | map([.direction,.actorAccountId,.targetAccountId]|join(":")) | unique | length' "${ledger_file}")"
  rate_limited="$(jq -s '[.[] | select(.recordType=="CONTACT_ATTEMPT" and .outcome=="RATE_LIMITED")] | length' "${ledger_file}")"
  failed=$((planned - succeeded))
  if [ "${failed}" -lt 0 ]; then failed=0; fi
  unresolved="${failed}"
  jq -nc \
    --argjson planned "${planned}" \
    --argjson attempts "${attempts}" \
    --argjson succeeded "${succeeded}" \
    --argjson unresolved "${unresolved}" \
    --argjson rateLimitedAttempts "${rate_limited}" \
    --arg finishedAt "$(contact_now_iso)" \
    '{recordType:"RUN_SUMMARY",planned:$planned,attempts:$attempts,succeeded:$succeeded,unresolved:$unresolved,rateLimitedAttempts:$rateLimitedAttempts,finishedAt:$finishedAt}' \
    >>"${ledger_file}"
}

contact_run_scheduler() {
  local tasks_file="$1"
  local ledger_file="$2"
  local work_dir="$3"
  contact_prepare_scheduler "${tasks_file}" "${ledger_file}" "${work_dir}"
  while contact_scheduler_has_work "${work_dir}"; do
    contact_reap_finished "${work_dir}" "${ledger_file}"
    while contact_try_dispatch_one "${work_dir}"; do
      :
    done
    if contact_scheduler_has_work "${work_dir}"; then
      sleep "${CONTACT_MATRIX_POLL_SECONDS}"
    fi
  done
  contact_append_run_summary "${ledger_file}" "${tasks_file}"
}

contact_json_number_array_from_tsv() {
  local input_file="$1"
  local group_id="$2"
  awk -F '\t' -v group_id="${group_id}" '$1 == group_id { print $2 }' "${input_file}" \
    | jq -Rsc 'split("\n") | map(select(length > 0) | tonumber)'
}

contact_remote_snapshot() {
  local accounts_file="$1"
  local ledger_file="$2"
  local left_group_id="$3"
  local right_group_id="$4"
  local readiness="$5"
  local left_ids right_ids left_ready_ids right_ready_ids blocked
  left_ids="$(contact_json_number_array_from_tsv "${accounts_file}" "${left_group_id}")"
  right_ids="$(contact_json_number_array_from_tsv "${accounts_file}" "${right_group_id}")"
  left_ready_ids="$(awk -F '\t' -v group_id="${left_group_id}" '$1 == group_id && $6 == "READY" { print $2 }' \
    "${accounts_file}" | jq -Rsc 'split("\n") | map(select(length > 0) | tonumber)')"
  right_ready_ids="$(awk -F '\t' -v group_id="${right_group_id}" '$1 == group_id && $6 == "READY" { print $2 }' \
    "${accounts_file}" | jq -Rsc 'split("\n") | map(select(length > 0) | tonumber)')"
  blocked="$(awk -F '\t' '$6 != "READY" { print $1 "\t" $2 "\t" $6 }' "${accounts_file}" \
    | jq -Rn '[inputs | split("\t") | {groupId:(.[0]|tonumber),accountId:(.[1]|tonumber),reason:.[2]}]')"
  jq -nc \
    --arg environment test1 \
    --arg executionMethod DIRECT_PROTOCOL_HTTP \
    --arg readiness "${readiness}" \
    --argjson leftGroupId "${left_group_id}" \
    --argjson rightGroupId "${right_group_id}" \
    --argjson leftAccountIds "${left_ids}" \
    --argjson rightAccountIds "${right_ids}" \
    --argjson leftReadyAccountIds "${left_ready_ids}" \
    --argjson rightReadyAccountIds "${right_ready_ids}" \
    --argjson blocked "${blocked}" \
    --arg frozenAt "$(contact_now_iso)" \
    '{recordType:"BATCH_SNAPSHOT",environment:$environment,executionMethod:$executionMethod,readiness:$readiness,leftGroupId:$leftGroupId,rightGroupId:$rightGroupId,leftAccountIds:$leftAccountIds,rightAccountIds:$rightAccountIds,leftReadyAccountIds:$leftReadyAccountIds,rightReadyAccountIds:$rightReadyAccountIds,blocked:$blocked,frozenAt:$frozenAt}' \
    >>"${ledger_file}"
}

contact_remote_validate_snapshot() {
  local accounts_file="$1"
  local ledger_file="$2"
  local left_group_id="$3"
  local right_group_id="$4"
  local readiness="$5"
  local snapshot_count frozen_left_group frozen_right_group frozen_readiness
  local frozen_left_ids frozen_right_ids current_left_ids current_right_ids
  snapshot_count="$(jq -s '[.[] | select(.recordType=="BATCH_SNAPSHOT")] | length' "${ledger_file}")"
  [ "${snapshot_count}" -eq 1 ] \
    || { contact_fail "账本必须且只能包含一个 BATCH_SNAPSHOT"; return 1; }
  frozen_left_group="$(jq -sr '[.[] | select(.recordType=="BATCH_SNAPSHOT")][0].leftGroupId' "${ledger_file}")"
  frozen_right_group="$(jq -sr '[.[] | select(.recordType=="BATCH_SNAPSHOT")][0].rightGroupId' "${ledger_file}")"
  frozen_readiness="$(jq -sr '[.[] | select(.recordType=="BATCH_SNAPSHOT")][0].readiness // ""' "${ledger_file}")"
  [ "${frozen_left_group}" = "${left_group_id}" ] \
    && [ "${frozen_right_group}" = "${right_group_id}" ] \
    || { contact_fail "账本冻结的分组与本次参数不一致"; return 1; }
  [ "${frozen_readiness}" = "${readiness}" ] \
    || { contact_fail "账本冻结的 readiness 与本次参数不一致"; return 1; }

  frozen_left_ids="$(jq -sc '[.[] | select(.recordType=="BATCH_SNAPSHOT")][0].leftAccountIds' "${ledger_file}")"
  frozen_right_ids="$(jq -sc '[.[] | select(.recordType=="BATCH_SNAPSHOT")][0].rightAccountIds' "${ledger_file}")"
  current_left_ids="$(contact_json_number_array_from_tsv "${accounts_file}" "${left_group_id}")"
  current_right_ids="$(contact_json_number_array_from_tsv "${accounts_file}" "${right_group_id}")"
  jq -en --argjson frozen "${frozen_left_ids}" --argjson current "${current_left_ids}" \
    '$frozen == $current' >/dev/null \
    || { contact_fail "左分组成员已变化，拒绝沿用旧账本"; return 1; }
  jq -en --argjson frozen "${frozen_right_ids}" --argjson current "${current_right_ids}" \
    '$frozen == $current' >/dev/null \
    || { contact_fail "右分组成员已变化，拒绝沿用旧账本"; return 1; }
}

contact_remote_select_frozen_accounts() {
  local accounts_file="$1"
  local ledger_file="$2"
  local side="$3"
  local output_file="$4"
  local readiness field account_id row
  readiness="$(jq -sr '[.[] | select(.recordType=="BATCH_SNAPSHOT")][0].readiness' "${ledger_file}")"
  case "${readiness}:${side}" in
    strict:left) field="leftAccountIds" ;;
    strict:right) field="rightAccountIds" ;;
    ready-only:left) field="leftReadyAccountIds" ;;
    ready-only:right) field="rightReadyAccountIds" ;;
    *) contact_fail "无法选择冻结账号: readiness=${readiness} side=${side}"; return 1 ;;
  esac
  : >"${output_file}"
  while IFS= read -r account_id; do
    [ -n "${account_id}" ] || continue
    row="$(awk -F '\t' -v account_id="${account_id}" '$2 == account_id { print; exit }' "${accounts_file}")"
    [ -n "${row}" ] \
      || { contact_fail "冻结账号 ${account_id} 已不在当前查询结果中"; return 1; }
    printf '%s\n' "${row}" >>"${output_file}"
  done < <(jq -r --arg field "${field}" \
    'select(.recordType=="BATCH_SNAPSHOT") | .[$field][]' "${ledger_file}")
}

contact_remote_generate_tasks() {
  local left_file="$1"
  local right_file="$2"
  local tasks_file="$3"
  local actor target actor_readiness
  : >"${tasks_file}"
  while IFS= read -r actor; do
    [ -n "${actor}" ] || continue
    actor_readiness="$(printf '%s\n' "${actor}" | awk -F '\t' '{print $6}')"
    [ "${actor_readiness}" = "READY" ] || continue
    while IFS= read -r target; do
      [ -n "${target}" ] || continue
      awk -F '\t' -v actor="${actor}" -v target="${target}" 'BEGIN {
        split(actor,a,"\t"); split(target,t,"\t");
        printf "LEFT_TO_RIGHT\t%s\t%s\t%s\t%s\t%s\t%s\n", a[2],t[2],a[3],a[4],a[5],t[5]
      }'
    done <"${right_file}"
  done <"${left_file}" >>"${tasks_file}"
  while IFS= read -r actor; do
    [ -n "${actor}" ] || continue
    actor_readiness="$(printf '%s\n' "${actor}" | awk -F '\t' '{print $6}')"
    [ "${actor_readiness}" = "READY" ] || continue
    while IFS= read -r target; do
      [ -n "${target}" ] || continue
      awk -F '\t' -v actor="${actor}" -v target="${target}" 'BEGIN {
        split(actor,a,"\t"); split(target,t,"\t");
        printf "RIGHT_TO_LEFT\t%s\t%s\t%s\t%s\t%s\t%s\n", a[2],t[2],a[3],a[4],a[5],t[5]
      }'
    done <"${left_file}"
  done <"${right_file}" >>"${tasks_file}"
}

contact_remote_load_accounts() {
  local output_file="$1"
  local left_group_id="$2"
  local right_group_id="$3"
  local env_lines db_url db_user db_pass db_addr db_hostport db_name db_host db_port
  env_lines="$(docker inspect armada-backend --format '{{range .Config.Env}}{{println .}}{{end}}')"
  contact_runtime_env() {
    printf '%s\n' "${env_lines}" | sed -n "s/^$1=//p" | head -n 1
  }
  db_url="$(contact_runtime_env DB_URL)"
  db_user="$(contact_runtime_env DB_USER)"
  db_pass="$(contact_runtime_env DB_PASSWORD)"
  CONTACT_MATRIX_ANDROID_BASE_URL="$(contact_runtime_env PROTOCOL_ANDROID_BASE_URL)"
  CONTACT_MATRIX_ANDROID_API_KEY="$(contact_runtime_env PROTOCOL_ANDROID_API_KEY)"
  CONTACT_MATRIX_WEB_BASE_URL="$(contact_runtime_env ARMADA_PROTOCOL_BASE_URL)"
  CONTACT_MATRIX_WEB_API_KEY="$(contact_runtime_env ARMADA_PROTOCOL_API_KEY)"
  export CONTACT_MATRIX_ANDROID_BASE_URL CONTACT_MATRIX_ANDROID_API_KEY \
    CONTACT_MATRIX_WEB_BASE_URL CONTACT_MATRIX_WEB_API_KEY
  [ -n "${db_url}" ] && [ -n "${db_user}" ] && [ -n "${db_pass}" ] \
    && [ -n "${CONTACT_MATRIX_ANDROID_BASE_URL}" ] \
    && [ -n "${CONTACT_MATRIX_WEB_BASE_URL}" ] \
    && [ -n "${CONTACT_MATRIX_WEB_API_KEY}" ] \
    || { contact_fail "test1 运行时配置不完整"; return 1; }
  CONTACT_MATRIX_ANDROID_BASE_URL="${CONTACT_MATRIX_ANDROID_BASE_URL%/}"
  CONTACT_MATRIX_WEB_BASE_URL="${CONTACT_MATRIX_WEB_BASE_URL%/}"
  db_addr="${db_url#jdbc:mysql://}"
  db_addr="${db_addr%%\?*}"
  db_hostport="${db_addr%%/*}"
  db_name="${db_addr#*/}"
  db_host="${db_hostport%%:*}"
  db_port="${db_hostport##*:}"
  [ "${db_port}" = "${db_hostport}" ] && db_port=3306
  MYSQL_PWD="${db_pass}" mysql -N -B \
    -h "${db_host}" -P "${db_port}" -u "${db_user}" "${db_name}" -e "
SELECT a.account_group_id,
       a.id,
       UPPER(TRIM(a.protocol_id)),
       TRIM(a.protocol_account_id),
       REPLACE(SUBSTRING_INDEX(TRIM(a.ws_phone), '@', 1), '+', ''),
       CASE
         WHEN a.is_active <> 1 THEN 'INACTIVE'
         WHEN s.account_state <> 2 THEN CONCAT('ACCOUNT_STATE_', COALESCE(s.account_state, -1))
         WHEN s.login_state <> 1 THEN CONCAT('LOGIN_STATE_', COALESCE(s.login_state, -1))
         WHEN a.protocol_account_id IS NULL OR TRIM(a.protocol_account_id) = '' THEN 'PROTOCOL_ACCOUNT_MISSING'
         WHEN a.ws_phone IS NULL OR TRIM(a.ws_phone) = '' THEN 'PHONE_MISSING'
         WHEN UPPER(TRIM(a.protocol_id)) NOT IN ('ANDROID','WEB') THEN 'PROTOCOL_UNSUPPORTED'
         ELSE 'READY'
       END AS readiness
FROM account a
LEFT JOIN account_state s ON s.account_id = a.id AND s.tenant_id = a.tenant_id
WHERE a.deleted_at IS NULL
  AND a.account_group_id IN (${left_group_id}, ${right_group_id})
ORDER BY a.account_group_id, a.id;" >"${output_file}"
}

contact_remote_main() {
  local mode="" left_group_id="" right_group_id="" ledger_file=""
  local readiness="strict" confirmed="false"
  local run_dir accounts_file left_file right_file tasks_file work_dir snapshot_exists
  local left_count right_count left_ready right_ready
  local selected_left_count selected_right_count selected_left_ready selected_right_ready
  local blocked_count planned runnable full_planned status
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --mode) mode="$2"; shift 2 ;;
      --left-group-id) left_group_id="$2"; shift 2 ;;
      --right-group-id) right_group_id="$2"; shift 2 ;;
      --ledger) ledger_file="$2"; shift 2 ;;
      --readiness) readiness="$2"; shift 2 ;;
      --interval-min-seconds)
        CONTACT_MATRIX_ANDROID_INTERVAL_MIN_SECONDS="$2"
        CONTACT_MATRIX_WEB_INTERVAL_MIN_SECONDS="$2"
        shift 2
        ;;
      --interval-max-seconds)
        CONTACT_MATRIX_ANDROID_INTERVAL_MAX_SECONDS="$2"
        CONTACT_MATRIX_WEB_INTERVAL_MAX_SECONDS="$2"
        shift 2
        ;;
      --yes) confirmed="true"; shift ;;
      *) contact_fail "未知远端参数: $1"; return 2 ;;
    esac
  done
  case "${mode}" in dry-run|live) ;; *) contact_fail "--mode 只允许 dry-run 或 live"; return 2 ;; esac
  contact_require_positive_integer "左分组 ID" "${left_group_id}"
  contact_require_positive_integer "右分组 ID" "${right_group_id}"
  [ "${left_group_id}" != "${right_group_id}" ] \
    || { contact_fail "左右分组不能相同"; return 2; }
  case "${readiness}" in strict|ready-only) ;; *) contact_fail "--readiness 只允许 strict 或 ready-only"; return 2 ;; esac
  [ "${mode}" != "live" ] || [ "${confirmed}" = "true" ] \
    || { contact_fail "live 模式必须显式传 --yes"; return 2; }
  contact_validate_ledger_path "${ledger_file}"
  contact_require_positive_integer "Android 并发数" "${CONTACT_MATRIX_ANDROID_CONCURRENCY}"
  contact_require_positive_integer "Web 并发数" "${CONTACT_MATRIX_WEB_CONCURRENCY}"
  contact_require_nonnegative_integer "Android 最小间隔" "${CONTACT_MATRIX_ANDROID_INTERVAL_MIN_SECONDS}"
  contact_require_nonnegative_integer "Android 最大间隔" "${CONTACT_MATRIX_ANDROID_INTERVAL_MAX_SECONDS}"
  contact_require_nonnegative_integer "Web 最小间隔" "${CONTACT_MATRIX_WEB_INTERVAL_MIN_SECONDS}"
  contact_require_nonnegative_integer "Web 最大间隔" "${CONTACT_MATRIX_WEB_INTERVAL_MAX_SECONDS}"
  [ "${CONTACT_MATRIX_ANDROID_INTERVAL_MIN_SECONDS}" -le "${CONTACT_MATRIX_ANDROID_INTERVAL_MAX_SECONDS}" ] \
    || { contact_fail "Android 最小间隔不能大于最大间隔"; return 2; }
  [ "${CONTACT_MATRIX_WEB_INTERVAL_MIN_SECONDS}" -le "${CONTACT_MATRIX_WEB_INTERVAL_MAX_SECONDS}" ] \
    || { contact_fail "Web 最小间隔不能大于最大间隔"; return 2; }
  for command_name in docker mysql jq curl flock; do
    command -v "${command_name}" >/dev/null 2>&1 \
      || { contact_fail "远端缺少命令: ${command_name}"; return 2; }
  done
  umask 077
  exec 9>"/tmp/armada-contact-matrix-${left_group_id}-${right_group_id}.lock"
  flock -n 9 || { contact_fail "相同分组已有联系人批次运行中"; return 75; }
  [ -e "${ledger_file}" ] || : >"${ledger_file}"
  contact_validate_jsonl "${ledger_file}" || { contact_fail "账本不是合法 JSONL"; return 2; }
  run_dir="$(mktemp -d /tmp/armada-contact-matrix-run.XXXXXX)"
  accounts_file="${run_dir}/accounts.tsv"
  left_file="${run_dir}/left.tsv"
  right_file="${run_dir}/right.tsv"
  tasks_file="${run_dir}/tasks.tsv"
  work_dir="${run_dir}/scheduler"
  trap "contact_cleanup_run_dir '${run_dir}'" EXIT
  trap 'exit 130' HUP INT TERM
  contact_remote_load_accounts "${accounts_file}" "${left_group_id}" "${right_group_id}"
  snapshot_exists="$(jq -r 'select(.recordType=="BATCH_SNAPSHOT") | 1' "${ledger_file}" | head -n 1)"
  if [ -z "${snapshot_exists}" ]; then
    contact_remote_snapshot \
      "${accounts_file}" "${ledger_file}" "${left_group_id}" "${right_group_id}" "${readiness}"
  fi
  contact_remote_validate_snapshot \
    "${accounts_file}" "${ledger_file}" "${left_group_id}" "${right_group_id}" "${readiness}"
  if [ "${mode}" = "live" ]; then
    contact_ledger_has_dry_run "${ledger_file}" \
      || { contact_fail "live 必须复用已经完成 dry-run 的账本"; return 2; }
  fi
  contact_remote_select_frozen_accounts "${accounts_file}" "${ledger_file}" left "${left_file}"
  contact_remote_select_frozen_accounts "${accounts_file}" "${ledger_file}" right "${right_file}"
  left_count="$(awk -F '\t' -v id="${left_group_id}" '$1 == id { count++ } END { print count + 0 }' "${accounts_file}")"
  right_count="$(awk -F '\t' -v id="${right_group_id}" '$1 == id { count++ } END { print count + 0 }' "${accounts_file}")"
  left_ready="$(awk -F '\t' -v id="${left_group_id}" '$1 == id && $6 == "READY" { count++ } END { print count + 0 }' "${accounts_file}")"
  right_ready="$(awk -F '\t' -v id="${right_group_id}" '$1 == id && $6 == "READY" { count++ } END { print count + 0 }' "${accounts_file}")"
  selected_left_count="$(awk 'NF > 0 { count++ } END { print count + 0 }' "${left_file}")"
  selected_right_count="$(awk 'NF > 0 { count++ } END { print count + 0 }' "${right_file}")"
  selected_left_ready="$(awk -F '\t' '$6 == "READY" { count++ } END { print count + 0 }' "${left_file}")"
  selected_right_ready="$(awk -F '\t' '$6 == "READY" { count++ } END { print count + 0 }' "${right_file}")"
  blocked_count=$(((selected_left_count - selected_left_ready) + (selected_right_count - selected_right_ready)))
  full_planned=$((left_count * right_count * 2))
  planned=$((selected_left_count * selected_right_count * 2))
  runnable=$(((selected_left_ready * selected_right_count) + (selected_right_ready * selected_left_count)))
  status="READY"
  if [ "${blocked_count}" -gt 0 ]; then
    if [ "${readiness}" = "strict" ]; then
      status="BLOCKED"
    else
      status="DEGRADED"
    fi
  fi
  if [ "${selected_left_ready}" -eq 0 ] && [ "${selected_right_ready}" -eq 0 ]; then
    status="BLOCKED"
  fi
  jq -nc \
    --arg mode "${mode}" --arg readiness "${readiness}" --arg status "${status}" \
    --argjson leftCount "${left_count}" --argjson rightCount "${right_count}" \
    --argjson leftReady "${left_ready}" --argjson rightReady "${right_ready}" \
    --argjson selectedLeftCount "${selected_left_count}" --argjson selectedRightCount "${selected_right_count}" \
    --argjson selectedLeftReady "${selected_left_ready}" --argjson selectedRightReady "${selected_right_ready}" \
    --argjson blockedCount "${blocked_count}" --argjson planned "${planned}" \
    --argjson runnable "${runnable}" --argjson fullPlanned "${full_planned}" --arg checkedAt "$(contact_now_iso)" \
    --argjson androidConcurrency "${CONTACT_MATRIX_ANDROID_CONCURRENCY}" \
    --argjson webConcurrency "${CONTACT_MATRIX_WEB_CONCURRENCY}" \
    --argjson intervalMinSeconds "${CONTACT_MATRIX_ANDROID_INTERVAL_MIN_SECONDS}" \
    --argjson intervalMaxSeconds "${CONTACT_MATRIX_ANDROID_INTERVAL_MAX_SECONDS}" \
    '{recordType:"PREFLIGHT",mode:$mode,readiness:$readiness,status:$status,leftCount:$leftCount,rightCount:$rightCount,leftReady:$leftReady,rightReady:$rightReady,selectedLeftCount:$selectedLeftCount,selectedRightCount:$selectedRightCount,selectedLeftReady:$selectedLeftReady,selectedRightReady:$selectedRightReady,blockedCount:$blockedCount,planned:$planned,runnable:$runnable,fullPlanned:$fullPlanned,androidConcurrency:$androidConcurrency,webConcurrency:$webConcurrency,intervalMinSeconds:$intervalMinSeconds,intervalMaxSeconds:$intervalMaxSeconds,checkedAt:$checkedAt}' \
    >>"${ledger_file}"
  printf 'PREFLIGHT status=%s selectedLeft=%s/%s selectedRight=%s/%s groupLeft=%s/%s groupRight=%s/%s planned=%s runnable=%s\n' \
    "${status}" "${selected_left_ready}" "${selected_left_count}" \
    "${selected_right_ready}" "${selected_right_count}" \
    "${left_ready}" "${left_count}" "${right_ready}" "${right_count}" \
    "${planned}" "${runnable}"
  if [ "${mode}" = "dry-run" ]; then
    printf 'DRY_RUN ledger=%s androidConcurrency=%s webConcurrency=%s androidInterval=%s-%ss webInterval=%s-%ss\n' \
      "${ledger_file}" "${CONTACT_MATRIX_ANDROID_CONCURRENCY}" "${CONTACT_MATRIX_WEB_CONCURRENCY}" \
      "${CONTACT_MATRIX_ANDROID_INTERVAL_MIN_SECONDS}" "${CONTACT_MATRIX_ANDROID_INTERVAL_MAX_SECONDS}" \
      "${CONTACT_MATRIX_WEB_INTERVAL_MIN_SECONDS}" "${CONTACT_MATRIX_WEB_INTERVAL_MAX_SECONDS}"
    contact_cleanup_run_dir "${run_dir}"
    trap - EXIT HUP INT TERM
    return 0
  fi
  if [ "${status}" = "BLOCKED" ]; then
    contact_cleanup_run_dir "${run_dir}"
    trap - EXIT HUP INT TERM
    contact_fail "当前模式没有可安全执行的联系人任务，未发送联系人写请求"
    return 20
  fi
  contact_remote_generate_tasks "${left_file}" "${right_file}" "${tasks_file}"
  contact_run_scheduler "${tasks_file}" "${ledger_file}" "${work_dir}"
  jq -c 'select(.recordType=="RUN_SUMMARY")' "${ledger_file}" | tail -n 1
  contact_cleanup_run_dir "${run_dir}"
  trap - EXIT HUP INT TERM
}

contact_usage() {
  cat <<'EOF'
account-contact-matrix.sh - 按执行账号限速、跨账号并发的双向联系人调度器。

用法:
  bash armada-deploy/tools/account-contact-matrix.sh \
    --env test1 --mode dry-run \
    --left-group-id 148 --right-group-id 110

  bash armada-deploy/tools/account-contact-matrix.sh \
    --env test1 --mode live --yes \
    --left-group-id 148 --right-group-id 110 \
    --readiness strict

参数:
  --env              目前只允许 test1；该参数即目标环境确认。
  --mode             dry-run 只读预检；live 才调用联系人写接口。
  --left-group-id    左侧账号分组 ID。
  --right-group-id   右侧账号分组 ID。
  --readiness        strict（默认）要求全量可用；ready-only 仅执行当前可用账号。
  --ledger           可选，远端 /tmp 下的脱敏 JSONL 账本路径。
  --interval-min-seconds  单账号最小冷却秒数，默认 1；实验模式可设为 0。
  --interval-max-seconds  单账号最大冷却秒数，默认 3；不得小于最小值。
  --yes              live 模式必填。

调度规则:
  同账号最多一个请求在途；不同账号按 Web/Android 独立并发池运行；
  同账号每次成功后独立冷却；HTTP 429 只暂停对应账号并指数退避；
  已在账本中成功的 actor/target/direction 组合恢复时自动跳过。
EOF
}

contact_local_main() {
  local selected_env="" mode="" left_group_id="" right_group_id=""
  local readiness="strict" ledger_file="" confirmed="false"
  local script_dir deploy_dir repo_root workspace_root profile_file ssh_key ssh_bin
  local remote_args
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --env) selected_env="$2"; shift 2 ;;
      --mode) mode="$2"; shift 2 ;;
      --left-group-id) left_group_id="$2"; shift 2 ;;
      --right-group-id) right_group_id="$2"; shift 2 ;;
      --readiness) readiness="$2"; shift 2 ;;
      --ledger) ledger_file="$2"; shift 2 ;;
      --interval-min-seconds)
        CONTACT_MATRIX_ANDROID_INTERVAL_MIN_SECONDS="$2"
        CONTACT_MATRIX_WEB_INTERVAL_MIN_SECONDS="$2"
        shift 2
        ;;
      --interval-max-seconds)
        CONTACT_MATRIX_ANDROID_INTERVAL_MAX_SECONDS="$2"
        CONTACT_MATRIX_WEB_INTERVAL_MAX_SECONDS="$2"
        shift 2
        ;;
      --yes) confirmed="true"; shift ;;
      -h|--help) contact_usage; return 0 ;;
      *) contact_fail "未知参数: $1"; return 2 ;;
    esac
  done
  [ "${selected_env}" = "test1" ] || { contact_fail "目前只允许 --env test1"; return 2; }
  case "${mode}" in dry-run|live) ;; *) contact_fail "--mode 只允许 dry-run 或 live"; return 2 ;; esac
  contact_require_positive_integer "左分组 ID" "${left_group_id}"
  contact_require_positive_integer "右分组 ID" "${right_group_id}"
  contact_require_nonnegative_integer "最小间隔" "${CONTACT_MATRIX_ANDROID_INTERVAL_MIN_SECONDS}"
  contact_require_nonnegative_integer "最大间隔" "${CONTACT_MATRIX_ANDROID_INTERVAL_MAX_SECONDS}"
  [ "${CONTACT_MATRIX_ANDROID_INTERVAL_MIN_SECONDS}" -le "${CONTACT_MATRIX_ANDROID_INTERVAL_MAX_SECONDS}" ] \
    || { contact_fail "最小间隔不能大于最大间隔"; return 2; }
  case "${readiness}" in strict|ready-only) ;; *) contact_fail "--readiness 只允许 strict 或 ready-only"; return 2 ;; esac
  [ "${mode}" != "live" ] || [ "${confirmed}" = "true" ] \
    || { contact_fail "live 模式必须显式传 --yes"; return 2; }
  if [ -z "${ledger_file}" ]; then
    ledger_file="/tmp/armada-contact-matrix-${left_group_id}-${right_group_id}-$(date -u +%Y%m%dT%H%M%SZ).jsonl"
  fi
  contact_validate_ledger_path "${ledger_file}"

  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  deploy_dir="$(cd "${script_dir}/.." && pwd)"
  repo_root="$(cd "${deploy_dir}/.." && pwd)"
  workspace_root="$(cd "${repo_root}/.." && pwd)"
  profile_file="${deploy_dir}/envs/test1.conf"
  # shellcheck source=/dev/null
  . "${profile_file}"
  ssh_key="${workspace_root}/${PROFILE_ARMADA_KEY_REL}"
  [ -f "${ssh_key}" ] || { contact_fail "找不到 test1 SSH 私钥"; return 2; }
  ssh_bin="${CONTACT_MATRIX_SSH_BIN:-ssh}"
  command -v "${ssh_bin}" >/dev/null 2>&1 || { contact_fail "找不到 SSH 命令"; return 2; }
  remote_args="--remote --mode ${mode} --left-group-id ${left_group_id} --right-group-id ${right_group_id} --ledger ${ledger_file} --readiness ${readiness}"
  remote_args="${remote_args} --interval-min-seconds ${CONTACT_MATRIX_ANDROID_INTERVAL_MIN_SECONDS} --interval-max-seconds ${CONTACT_MATRIX_ANDROID_INTERVAL_MAX_SECONDS}"
  if [ "${confirmed}" = "true" ]; then remote_args="${remote_args} --yes"; fi
  "${ssh_bin}" -T -i "${ssh_key}" \
    -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new \
    "${PROFILE_ARMADA_USER}@${PROFILE_ARMADA_HOST}" \
    "bash -s -- ${remote_args}" <"${BASH_SOURCE[0]}"
}

if [ "${1:-}" = "--remote" ]; then
  shift
  contact_remote_main "$@"
elif [[ "${BASH_SOURCE[0]:-}" == "$0" ]]; then
  contact_local_main "$@"
fi
