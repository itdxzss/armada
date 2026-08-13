#!/usr/bin/env bash
if [ -z "${BASH_VERSION:-}" ]; then
  exec /usr/bin/env bash "$0" "$@"
fi
set -euo pipefail

BATCH_GROUP_ANDROID_CONCURRENCY="${BATCH_GROUP_ANDROID_CONCURRENCY:-6}"
BATCH_GROUP_WEB_CONCURRENCY="${BATCH_GROUP_WEB_CONCURRENCY:-2}"
BATCH_GROUP_INTERVAL_SECONDS="${BATCH_GROUP_INTERVAL_SECONDS:-10}"
BATCH_GROUP_POLL_SECONDS="${BATCH_GROUP_POLL_SECONDS:-1}"
BATCH_GROUP_TRACE_FILE="${BATCH_GROUP_TRACE_FILE:-}"
BATCH_GROUP_LEDGER_FILE="${BATCH_GROUP_LEDGER_FILE:-}"
BATCH_CONTACT_148_149_BASE="${BATCH_CONTACT_148_149_BASE:-/tmp/armada-mutual-contacts-failures-20260813T014830Z.jsonl}"
BATCH_CONTACT_148_110_SNAPSHOT="${BATCH_CONTACT_148_110_SNAPSHOT:-/tmp/armada-contact-matrix-148-110-20260813T050324Z.jsonl}"

batch_fail() {
  printf 'ERR %s\n' "$*" >&2
  return 1
}

batch_require_positive_integer() {
  case "$2" in
    ''|*[!0-9]*|0) batch_fail "$1必须是正整数: $2" ;;
  esac
}

batch_validate_ledger() {
  case "$1" in
    /tmp/armada-batch-group-*.jsonl) ;;
    *) batch_fail "账本必须是 /tmp/armada-batch-group-*.jsonl"; return 2 ;;
  esac
  case "${1#/tmp/}" in
    */*|*[!A-Za-z0-9._-]*) batch_fail "账本路径含非法字符"; return 2 ;;
  esac
}

batch_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

batch_trace() {
  [ -n "${BATCH_GROUP_TRACE_FILE}" ] || return 0
  printf '%s\n' "$*" >>"${BATCH_GROUP_TRACE_FILE}"
}

batch_terminal_item_ids() {
  local ledger_file="$1"
  if [ -s "${ledger_file}" ]; then
    jq -r 'select(.recordType=="GROUP_ITEM_FINAL")|.itemId' "${ledger_file}" 2>/dev/null | sort -n -u
  fi
}

batch_active_count() {
  local work_dir="$1" expected_protocol="$2" protocol_file count=0
  for protocol_file in "${work_dir}/pids/"*.protocol; do
    [ -e "${protocol_file}" ] || continue
    [ "$(cat "${protocol_file}")" = "${expected_protocol}" ] && count=$((count + 1))
  done
  printf '%s\n' "${count}"
}

batch_has_slot() {
  local work_dir="$1" protocol="$2" active limit
  active="$(batch_active_count "${work_dir}" "${protocol}")"
  case "${protocol}" in
    ANDROID) limit="${BATCH_GROUP_ANDROID_CONCURRENCY}" ;;
    WEB) limit="${BATCH_GROUP_WEB_CONCURRENCY}" ;;
    *) return 1 ;;
  esac
  [ "${active}" -lt "${limit}" ]
}

BATCH_CREATOR_IDS=()
BATCH_CREATOR_PROTOCOLS=()
BATCH_CURSOR=0

batch_prepare_scheduler() {
  local tasks_file="$1" ledger_file="$2" work_dir="$3"
  local terminal_file item_id creator_id protocol subject creator_protocol
  mkdir "${work_dir}"
  mkdir "${work_dir}/queues" "${work_dir}/state" "${work_dir}/pids" "${work_dir}/running"
  terminal_file="${work_dir}/terminal.txt"
  batch_terminal_item_ids "${ledger_file}" >"${terminal_file}"
  : >"${work_dir}/creators.tsv"
  while IFS=$'\t' read -r item_id creator_id protocol subject; do
    [ -n "${item_id}" ] || continue
    if grep -Fqx -- "${item_id}" "${terminal_file}"; then
      continue
    fi
    batch_require_positive_integer "itemId" "${item_id}"
    batch_require_positive_integer "creatorAccountId" "${creator_id}"
    case "${protocol}" in ANDROID|WEB) ;; *) batch_fail "非法协议: ${protocol}"; return 2 ;; esac
    if [ -f "${work_dir}/state/${creator_id}.protocol" ]; then
      creator_protocol="$(cat "${work_dir}/state/${creator_id}.protocol")"
      [ "${creator_protocol}" = "${protocol}" ] || { batch_fail "账号协议不一致"; return 2; }
    else
      printf '%s\n' "${protocol}" >"${work_dir}/state/${creator_id}.protocol"
      printf '0\n' >"${work_dir}/state/${creator_id}.next"
      printf '%s\t%s\n' "${creator_id}" "${protocol}" >>"${work_dir}/creators.tsv"
    fi
    printf '%s\t%s\t%s\t%s\n' "${item_id}" "${creator_id}" "${protocol}" "${subject}" \
      >>"${work_dir}/queues/${creator_id}.tsv"
  done <"${tasks_file}"
  BATCH_CREATOR_IDS=()
  BATCH_CREATOR_PROTOCOLS=()
  while IFS=$'\t' read -r creator_id protocol; do
    BATCH_CREATOR_IDS+=("${creator_id}")
    BATCH_CREATOR_PROTOCOLS+=("${protocol}")
  done <"${work_dir}/creators.tsv"
  BATCH_CURSOR=0
}

batch_dispatch_creator() {
  local creator_id="$1" protocol="$2" work_dir="$3"
  local queue_file item_file result_file pid_file item_id pid
  queue_file="${work_dir}/queues/${creator_id}.tsv"
  item_file="${work_dir}/running/${creator_id}.item"
  result_file="${work_dir}/running/${creator_id}.result"
  pid_file="${work_dir}/pids/${creator_id}.pid"
  head -n 1 "${queue_file}" >"${item_file}"
  IFS=$'\t' read -r item_id _ _ _ <"${item_file}"
  batch_execute_item "${item_file}" "${result_file}" &
  pid=$!
  printf '%s\n' "${pid}" >"${pid_file}"
  printf '%s\n' "${protocol}" >"${work_dir}/pids/${creator_id}.protocol"
  batch_trace "DISPATCH\t${creator_id}\t${item_id}\t${protocol}\t$(batch_active_count "${work_dir}" WEB)\t$(batch_active_count "${work_dir}" ANDROID)"
}

batch_advance_queue() {
  local queue_file="$1" temporary="${queue_file}.next.$$"
  tail -n +2 "${queue_file}" >"${temporary}"
  mv "${temporary}" "${queue_file}"
}

batch_reap_finished() {
  local work_dir="$1" ledger_file="$2" pid_file creator_id pid protocol item_file result_file queue_file
  local item_id final_status reason_class now
  for pid_file in "${work_dir}/pids/"*.pid; do
    [ -e "${pid_file}" ] || continue
    creator_id="$(basename "${pid_file}" .pid)"
    pid="$(cat "${pid_file}")"
    kill -0 "${pid}" 2>/dev/null && continue
    wait "${pid}" 2>/dev/null || true
    protocol="$(cat "${work_dir}/pids/${creator_id}.protocol")"
    item_file="${work_dir}/running/${creator_id}.item"
    result_file="${work_dir}/running/${creator_id}.result"
    queue_file="${work_dir}/queues/${creator_id}.tsv"
    IFS=$'\t' read -r item_id _ _ _ <"${item_file}"
    if [ ! -s "${result_file}" ] || ! jq -e . "${result_file}" >/dev/null 2>&1; then
      jq -nc --argjson itemId "${item_id}" --argjson creatorAccountId "${creator_id}" \
        --arg creatorProtocol "${protocol}" --arg completedAt "$(batch_now)" \
        '{recordType:"GROUP_ITEM_FINAL",itemId:$itemId,creatorAccountId:$creatorAccountId,creatorProtocol:$creatorProtocol,finalStatus:"UNKNOWN",reasonClass:"WORKER_EXITED_WITHOUT_RESULT",completedAt:$completedAt}' \
        >"${result_file}"
    fi
    cat "${result_file}" >>"${ledger_file}"
    final_status="$(jq -r '.finalStatus' "${result_file}")"
    reason_class="$(jq -r '.reasonClass // ""' "${result_file}")"
    batch_advance_queue "${queue_file}"
    now="$(date +%s)"
    printf '%s\n' "$((now + BATCH_GROUP_INTERVAL_SECONDS))" >"${work_dir}/state/${creator_id}.next"
    if [ "${reason_class}" = "RATE_LIMITED" ] || [ "${reason_class}" = "ACCOUNT_RESTRICTED" ]; then
      : >"${queue_file}"
      jq -nc --argjson creatorAccountId "${creator_id}" --arg reasonClass "${reason_class}" \
        --arg pausedAt "$(batch_now)" \
        '{recordType:"CREATOR_PAUSED",creatorAccountId:$creatorAccountId,reasonClass:$reasonClass,pausedAt:$pausedAt}' \
        >>"${ledger_file}"
    fi
    rm -f "${pid_file}" "${work_dir}/pids/${creator_id}.protocol" "${item_file}" "${result_file}"
    batch_trace "COMPLETE\t${creator_id}\t${item_id}\t${protocol}\t${final_status}\t$(batch_active_count "${work_dir}" WEB)\t$(batch_active_count "${work_dir}" ANDROID)"
  done
}

batch_scheduler_has_work() {
  local work_dir="$1" queue_file pid_file
  for pid_file in "${work_dir}/pids/"*.pid; do [ -e "${pid_file}" ] && return 0; done
  for queue_file in "${work_dir}/queues/"*.tsv; do [ -s "${queue_file}" ] && return 0; done
  return 1
}

if ! declare -F batch_new_banned_creator_ids >/dev/null 2>&1; then
  batch_new_banned_creator_ids() {
    local creator_ids_csv
    creator_ids_csv="$(printf '%s\n' "${BATCH_CREATOR_IDS[@]}" | paste -sd, -)"
    [ -n "${creator_ids_csv}" ] || return 0
    batch_mysql -e "
SELECT a.id
FROM account a
JOIN account_state s ON s.account_id=a.id AND s.tenant_id=a.tenant_id
WHERE a.tenant_id=1 AND a.deleted_at IS NULL
  AND a.id IN (${creator_ids_csv})
  AND s.account_state=3
ORDER BY a.id;"
  }
fi

batch_check_global_stop() {
  local work_dir="$1" ledger_file="$2" banned_ids
  [ ! -e "${work_dir}/global-stop" ] || return 0
  banned_ids="$(batch_new_banned_creator_ids | jq -Rsc 'split("\n")|map(select(length>0)|tonumber)')"
  [ "$(jq 'length' <<<"${banned_ids}")" -eq 0 ] && return 1
  : >"${work_dir}/global-stop"
  jq -nc --argjson bannedCreatorAccountIds "${banned_ids}" --arg stoppedAt "$(batch_now)" \
    '{recordType:"GLOBAL_STOP",reasonClass:"CREATOR_BANNED",bannedCreatorAccountIds:$bannedCreatorAccountIds,stoppedAt:$stoppedAt}' \
    >>"${ledger_file}"
  return 0
}

batch_dispatch_ready() {
  local work_dir="$1" total index creator_id protocol queue_file next_at now dispatched=0
  total="${#BATCH_CREATOR_IDS[@]}"
  [ "${total}" -gt 0 ] || return 1
  [ ! -e "${work_dir}/global-stop" ] || return 1
  now="$(date +%s)"
  index=0
  while [ "${index}" -lt "${total}" ]; do
    BATCH_CURSOR=$(((BATCH_CURSOR + 1) % total))
    creator_id="${BATCH_CREATOR_IDS[BATCH_CURSOR]}"
    protocol="${BATCH_CREATOR_PROTOCOLS[BATCH_CURSOR]}"
    queue_file="${work_dir}/queues/${creator_id}.tsv"
    next_at="$(cat "${work_dir}/state/${creator_id}.next")"
    if [ -s "${queue_file}" ] && [ ! -e "${work_dir}/pids/${creator_id}.pid" ] \
        && [ "${now}" -ge "${next_at}" ] && batch_has_slot "${work_dir}" "${protocol}"; then
      batch_dispatch_creator "${creator_id}" "${protocol}" "${work_dir}"
      dispatched=1
    fi
    index=$((index + 1))
  done
  [ "${dispatched}" -eq 1 ]
}

batch_run_scheduler() {
  local tasks_file="$1" ledger_file="$2" work_dir="$3"
  batch_prepare_scheduler "${tasks_file}" "${ledger_file}" "${work_dir}"
  while batch_scheduler_has_work "${work_dir}"; do
    batch_reap_finished "${work_dir}" "${ledger_file}"
    batch_check_global_stop "${work_dir}" "${ledger_file}" || true
    if [ -e "${work_dir}/global-stop" ]; then
      [ "$(batch_active_count "${work_dir}" WEB)" -gt 0 ] || [ "$(batch_active_count "${work_dir}" ANDROID)" -gt 0 ] || break
    else
      batch_dispatch_ready "${work_dir}" || true
    fi
    sleep "${BATCH_GROUP_POLL_SECONDS}"
  done
  batch_reap_finished "${work_dir}" "${ledger_file}"
}

batch_runtime_env() {
  printf '%s\n' "${BATCH_ENV_LINES}" | sed -n "s/^$1=//p" | head -n 1
}

batch_mysql() {
  MYSQL_PWD="${BATCH_DB_PASS}" mysql -N -B -h "${BATCH_DB_HOST}" -P "${BATCH_DB_PORT}" \
    -u "${BATCH_DB_USER}" "${BATCH_DB_NAME}" "$@"
}

batch_load_runtime() {
  local db_url db_addr db_hostport
  BATCH_ENV_LINES="$(docker inspect armada-backend --format '{{range .Config.Env}}{{println .}}{{end}}')"
  db_url="$(batch_runtime_env DB_URL)"
  BATCH_DB_USER="$(batch_runtime_env DB_USER)"
  BATCH_DB_PASS="$(batch_runtime_env DB_PASSWORD)"
  BATCH_WEB_BASE_URL="$(batch_runtime_env ARMADA_PROTOCOL_BASE_URL)"
  BATCH_WEB_API_KEY="$(batch_runtime_env ARMADA_PROTOCOL_API_KEY)"
  BATCH_ANDROID_BASE_URL="$(batch_runtime_env PROTOCOL_ANDROID_BASE_URL)"
  BATCH_ANDROID_API_KEY="$(batch_runtime_env PROTOCOL_ANDROID_API_KEY)"
  [ -n "${db_url}" ] && [ -n "${BATCH_DB_USER}" ] && [ -n "${BATCH_DB_PASS}" ] \
    && [ -n "${BATCH_WEB_BASE_URL}" ] && [ -n "${BATCH_WEB_API_KEY}" ] \
    && [ -n "${BATCH_ANDROID_BASE_URL}" ] || { batch_fail "test1 运行时配置不完整"; return 1; }
  BATCH_WEB_BASE_URL="${BATCH_WEB_BASE_URL%/}"
  BATCH_ANDROID_BASE_URL="${BATCH_ANDROID_BASE_URL%/}"
  db_addr="${db_url#jdbc:mysql://}"; db_addr="${db_addr%%\?*}"
  db_hostport="${db_addr%%/*}"; BATCH_DB_NAME="${db_addr#*/}"
  BATCH_DB_HOST="${db_hostport%%:*}"; BATCH_DB_PORT="${db_hostport##*:}"
  [ "${BATCH_DB_PORT}" = "${db_hostport}" ] && BATCH_DB_PORT=3306
  return 0
}

batch_load_accounts() {
  local output_file="$1"
  batch_mysql -e "
SELECT a.account_group_id,a.id,UPPER(TRIM(a.protocol_id)),TRIM(a.protocol_account_id),
       REPLACE(SUBSTRING_INDEX(TRIM(a.ws_phone),'@',1),'+',''),
       CASE WHEN a.is_active<>1 THEN 'INACTIVE'
            WHEN s.account_state<>2 THEN CONCAT('ACCOUNT_STATE_',COALESCE(s.account_state,-1))
            WHEN s.login_state<>1 THEN CONCAT('LOGIN_STATE_',COALESCE(s.login_state,-1))
            WHEN a.protocol_account_id IS NULL OR TRIM(a.protocol_account_id)='' THEN 'PROTOCOL_ACCOUNT_MISSING'
            WHEN a.ws_phone IS NULL OR TRIM(a.ws_phone)='' THEN 'PHONE_MISSING'
            WHEN UPPER(TRIM(a.protocol_id)) NOT IN ('ANDROID','WEB') THEN 'PROTOCOL_UNSUPPORTED'
            ELSE 'READY' END
FROM account a LEFT JOIN account_state s ON s.account_id=a.id AND s.tenant_id=a.tenant_id
WHERE a.deleted_at IS NULL AND a.tenant_id=1
ORDER BY a.account_group_id,a.id;" >"${output_file}"
}

batch_contact_pair_succeeded() {
  local evidence_file="$1" actor_id="$2" target_id="$3"
  grep -Fqx -- "$(printf '%s\t%s' "${actor_id}" "${target_id}")" "${evidence_file}"
}

batch_mutual_contact_ids() {
  local accounts_file="$1" evidence_file="$2" creator_id="$3" group_id="$4"
  local member_id
  while IFS= read -r member_id; do
    [ -n "${member_id}" ] || continue
    if batch_contact_pair_succeeded "${evidence_file}" "${creator_id}" "${member_id}" \
        && batch_contact_pair_succeeded "${evidence_file}" "${member_id}" "${creator_id}"; then
      printf '%s\n' "${member_id}"
    fi
  done < <(awk -F '\t' -v group_id="${group_id}" '$1==group_id && $6=="READY" && $5!=""{print $2}' "${accounts_file}")
}

batch_build_contact_evidence() {
  local accounts_file="$1" output_file="$2" evidence_work_dir="$3"
  local baseline_failures retry_successes unsorted creator_id admin_id direction actor_id target_id ledger
  local frozen_admin_ids='888 892 1568 1569 1570 1571 1572 1573 1574'
  [ -s "${BATCH_CONTACT_148_149_BASE}" ] \
    && [ -s "${BATCH_CONTACT_148_110_SNAPSHOT}" ] \
    || { batch_fail "联系人硬门禁缺少历史账本"; return 20; }
  jq -es '
    ([.[]|select(.type=="run_start" and .sourceGroupId==148 and .targetGroupId==149 and .planned==540)]|length)==1
    and ([.[]|select(.type=="run_summary" and .planned==540 and .processed==540 and .failed==48)]|length)==1
    and ([.[]|select(.type=="failure")]|length)==48
  ' "${BATCH_CONTACT_148_149_BASE}" >/dev/null \
    || { batch_fail "148↔149 基线账本不完整"; return 20; }
  jq -e 'select(.recordType=="BATCH_SNAPSHOT" and .leftGroupId==148 and .rightGroupId==110 and (.leftReadyAccountIds|length)==30)' \
    "${BATCH_CONTACT_148_110_SNAPSHOT}" >/dev/null \
    || { batch_fail "148↔110 冻结快照不完整"; return 20; }
  [ "$(awk -F '\t' '$1==149{print $2}' "${accounts_file}" | paste -sd' ' -)" = "${frozen_admin_ids}" ] \
    || { batch_fail "次管理分组成员已变化，旧联系人证据失效"; return 20; }

  baseline_failures="${evidence_work_dir}/admin-baseline-failures.tsv"
  retry_successes="${evidence_work_dir}/admin-retry-successes.tsv"
  unsorted="${evidence_work_dir}/contact-evidence.unsorted.tsv"
  jq -r 'select(.type=="failure")|[.direction,.actorAccountId,.targetAccountId]|@tsv' \
    "${BATCH_CONTACT_148_149_BASE}" >"${baseline_failures}"
  : >"${retry_successes}"
  for ledger in /tmp/armada-mutual-contacts-retry*.jsonl; do
    [ -e "${ledger}" ] || continue
    jq -r 'select(.type=="retry_result" and .succeeded==true)|[.direction,.actorAccountId,.targetAccountId]|@tsv' \
      "${ledger}" >>"${retry_successes}"
  done
  : >"${unsorted}"
  while IFS= read -r creator_id; do
    [ -n "${creator_id}" ] || continue
    for admin_id in ${frozen_admin_ids}; do
      for direction in 148_TO_149 149_TO_148; do
        if [ "${direction}" = 148_TO_149 ]; then
          actor_id="${creator_id}"; target_id="${admin_id}"
        else
          actor_id="${admin_id}"; target_id="${creator_id}"
        fi
        if ! grep -Fqx -- "$(printf '%s\t%s\t%s' "${direction}" "${actor_id}" "${target_id}")" "${baseline_failures}" \
            || grep -Fqx -- "$(printf '%s\t%s\t%s' "${direction}" "${actor_id}" "${target_id}")" "${retry_successes}"; then
          printf '%s\t%s\n' "${actor_id}" "${target_id}" >>"${unsorted}"
        fi
      done
    done
  done < <(jq -r 'select(.recordType=="BATCH_SNAPSHOT")|.leftReadyAccountIds[]' "${BATCH_CONTACT_148_110_SNAPSHOT}")
  for ledger in /tmp/armada-contact-matrix-148-110-ready-*.jsonl; do
    [ -e "${ledger}" ] || continue
    jq -r 'select(.recordType=="CONTACT_ATTEMPT" and .outcome=="SUCCESS")|[.actorAccountId,.targetAccountId]|@tsv' \
      "${ledger}" >>"${unsorted}"
  done
  sort -n -k1,1 -k2,2 -u "${unsorted}" >"${output_file}"
}

batch_validate_plan_contact_gate() {
  local accounts_file="$1" ledger_file="$2" evidence_file="$3" mode="$4" item_id="${5:-}"
  local plan creator_id member_id readiness failures='[]' excluded_helpers='[]' effective_helpers='[]' status=READY
  local planned_helper_count expected_participants expected_size
  plan="$(jq -sc --argjson itemId "${item_id:-0}" '[.[]|select(.recordType=="GROUP_PLAN" and (.itemId==$itemId or $itemId==0))][0]' "${ledger_file}")"
  creator_id="$(jq -r '.creatorAccountId // 0' <<<"${plan}")"
  if [ "$(awk -F '\t' -v id="${creator_id}" '$1==148 && $2==id{print $6;exit}' "${accounts_file}")" != READY ]; then
    failures="$(jq -nc --argjson accountId "${creator_id}" '[{role:"CREATOR",accountId:$accountId,reason:"NOT_READY_OR_GROUP_MISMATCH"}]')"
  fi
  while IFS= read -r member_id; do
    [ -n "${member_id}" ] || continue
    readiness="$(awk -F '\t' -v id="${member_id}" '$1==149 && $2==id{print $6;exit}' "${accounts_file}")"
    if [ "${readiness}" != READY ]; then
      failures="$(jq -nc --argjson current "${failures}" --argjson accountId "${member_id}" '$current+[{role:"ADMIN",accountId:$accountId,reason:"NOT_READY_OR_GROUP_MISMATCH"}]')"
    elif ! batch_contact_pair_succeeded "${evidence_file}" "${creator_id}" "${member_id}" \
        || ! batch_contact_pair_succeeded "${evidence_file}" "${member_id}" "${creator_id}"; then
      failures="$(jq -nc --argjson current "${failures}" --argjson accountId "${member_id}" '$current+[{role:"ADMIN",accountId:$accountId,reason:"CONTACT_NOT_MUTUAL"}]')"
    fi
  done < <(jq -r '.adminAccountIds[]' <<<"${plan}")
  while IFS= read -r member_id; do
    [ -n "${member_id}" ] || continue
    readiness="$(awk -F '\t' -v id="${member_id}" '$1==110 && $2==id{print $6;exit}' "${accounts_file}")"
    if [ "${readiness}" != READY ]; then
      excluded_helpers="$(jq -nc --argjson current "${excluded_helpers}" --argjson accountId "${member_id}" '$current+[{accountId:$accountId,reason:"NOT_READY_OR_GROUP_MISMATCH"}]')"
    elif ! batch_contact_pair_succeeded "${evidence_file}" "${creator_id}" "${member_id}" \
        || ! batch_contact_pair_succeeded "${evidence_file}" "${member_id}" "${creator_id}"; then
      excluded_helpers="$(jq -nc --argjson current "${excluded_helpers}" --argjson accountId "${member_id}" '$current+[{accountId:$accountId,reason:"CONTACT_NOT_MUTUAL"}]')"
    else
      effective_helpers="$(jq -nc --argjson current "${effective_helpers}" --argjson accountId "${member_id}" '$current+[$accountId]')"
    fi
  done < <(jq -r '.helperAccountIds[]' <<<"${plan}")
  planned_helper_count="$(jq '.helperAccountIds|length' <<<"${plan}")"
  [ "$(jq '.adminAccountIds|length' <<<"${plan}")" -eq 2 ] \
    && [ "$(jq '.adminAccountIds|unique|length' <<<"${plan}")" -eq 2 ] \
    && [ "${planned_helper_count}" -le 59 ] \
    && [ "$(jq '.helperAccountIds|unique|length' <<<"${plan}")" -eq "${planned_helper_count}" ] \
    && [ "$(jq '.expectedParticipants' <<<"${plan}")" -eq "$((planned_helper_count + 2))" ] \
    && [ "$(jq '.expectedGroupSize' <<<"${plan}")" -eq "$((planned_helper_count + 3))" ] \
    || failures="$(jq -nc --argjson current "${failures}" '$current+[{role:"PLAN",accountId:0,reason:"MEMBER_COUNT_INVALID"}]')"
  [ "$(jq 'length' <<<"${failures}")" -eq 0 ] || status=BLOCKED
  expected_participants=$((2 + $(jq 'length' <<<"${effective_helpers}")))
  expected_size=$((expected_participants + 1))
  jq -nc --arg mode "${mode}" --arg status "${status}" --argjson itemId "$(jq '.itemId' <<<"${plan}")" --argjson creatorAccountId "${creator_id}" \
    --argjson failures "${failures}" --argjson excludedHelpers "${excluded_helpers}" \
    --argjson effectiveHelperAccountIds "${effective_helpers}" \
    --argjson expectedParticipants "${expected_participants}" --argjson expectedGroupSize "${expected_size}" \
    --arg checkedAt "$(batch_now)" \
    '{recordType:"CONTACT_GATE_CHECK",mode:$mode,status:$status,itemId:$itemId,creatorAccountId:$creatorAccountId,failures:$failures,excludedHelpers:$excludedHelpers,effectiveHelperAccountIds:$effectiveHelperAccountIds,expectedParticipants:$expectedParticipants,expectedGroupSize:$expectedGroupSize,checkedAt:$checkedAt}' \
    >>"${ledger_file}"
  [ "${status}" = READY ] || { batch_fail "联系人硬门禁未通过"; return 20; }
}

batch_validate_all_plan_contact_gates() {
  local accounts_file="$1" ledger_file="$2" evidence_file="$3" mode="$4"
  local item_id ready_count=0
  while IFS= read -r item_id; do
    [ -n "${item_id}" ] || continue
    if batch_validate_plan_contact_gate "${accounts_file}" "${ledger_file}" "${evidence_file}" "${mode}" "${item_id}"; then
      ready_count=$((ready_count + 1))
    fi
  done < <(jq -r 'select(.recordType=="GROUP_PLAN")|.itemId' "${ledger_file}")
  [ "${ready_count}" -gt 0 ] || { batch_fail "没有通过联系人硬门禁的计划群"; return 20; }
}

batch_make_plans() {
  local accounts_file="$1" ledger_file="$2" operation_id="$3" subject_prefix="$4" evidence_file="$5"
  local creators admins helpers creator_id candidate_id protocol sequence item_no=0 admin_ids helper_ids
  local admin_count helper_count candidate_rows='' qualified_creators='' gate_candidates gate_status selected_creator_json
  [ "$(awk -F '\t' '$1==148{n++}END{print n+0}' "${accounts_file}")" -eq 32 ] || { batch_fail "分组 148 必须是 32 个账号"; return 20; }
  while IFS= read -r candidate_id; do
    [ -n "${candidate_id}" ] || continue
    admin_count="$(batch_mutual_contact_ids "${accounts_file}" "${evidence_file}" "${candidate_id}" 149 | awk 'NF{n++}END{print n+0}')"
    helper_count="$(batch_mutual_contact_ids "${accounts_file}" "${evidence_file}" "${candidate_id}" 110 | awk 'NF{n++}END{print n+0}')"
    candidate_rows="${candidate_rows}${candidate_id}\t${admin_count}\t${helper_count}\n"
    if [ "${admin_count}" -ge 2 ]; then
      qualified_creators="${qualified_creators}${candidate_id}\n"
    fi
  done < <(awk -F '\t' '$1==148 && $6=="READY"{print $2}' "${accounts_file}")
  gate_candidates="$(printf '%b' "${candidate_rows}" | jq -Rn '[inputs|select(length>0)|split("\t")|{creatorAccountId:(.[0]|tonumber),eligibleAdminCount:(.[1]|tonumber),eligibleHelperCount:(.[2]|tonumber)}]')"
  creators="$(printf '%b' "${qualified_creators}" | while IFS= read -r candidate_id; do
    [ -n "${candidate_id}" ] || continue
    printf '%s\t%s\n' "$(printf '%s' "${operation_id}:creator:${candidate_id}" | sha256sum | awk '{print $1}')" "${candidate_id}"
  done | sort | awk '{print $2}' | jq -Rsc 'split("\n")|map(select(length>0)|tonumber)')"
  gate_status=BLOCKED; selected_creator_json='[]'
  if [ "$(jq 'length' <<<"${creators}")" -gt 0 ]; then
    gate_status=READY; selected_creator_json="${creators}"
  fi
  jq -nc --arg status "${gate_status}" --argjson candidates "${gate_candidates}" \
    --argjson selectedCreatorAccountIds "${selected_creator_json}" --arg checkedAt "$(batch_now)" \
    '{recordType:"CONTACT_GATE",status:$status,creatorSelection:"ALL_ELIGIBLE",roundsPerCreator:5,requiredAdmins:2,maxHelpers:59,candidates:$candidates,selectedCreatorAccountIds:$selectedCreatorAccountIds,checkedAt:$checkedAt}' \
    >>"${ledger_file}"
  [ "${gate_status}" = READY ] || { batch_fail "没有满足在线状态和双向联系人门禁的建群号"; return 20; }
  while IFS= read -r creator_id; do
    protocol="$(awk -F '\t' -v id="${creator_id}" '$2==id{print $3;exit}' "${accounts_file}")"
    admins="$(batch_mutual_contact_ids "${accounts_file}" "${evidence_file}" "${creator_id}" 149)"
    helpers="$(batch_mutual_contact_ids "${accounts_file}" "${evidence_file}" "${creator_id}" 110)"
    for sequence in 1 2 3 4 5; do
      item_no=$((item_no + 1))
      admin_ids="$(printf '%s\n' "${admins}" | while IFS= read -r admin_id; do
        printf '%s\t%s\n' "$(printf '%s' "${operation_id}:${item_no}:${admin_id}" | sha256sum | awk '{print $1}')" "${admin_id}"
      done | sort | head -n 2 | awk '{print $2}' | jq -Rsc 'split("\n")|map(select(length>0)|tonumber)')"
      helper_ids="$(printf '%s\n' "${helpers}" | jq -Rsc 'split("\n")|map(select(length>0)|tonumber)')"
      jq -nc --arg operationId "${operation_id}" --argjson itemId "${item_no}" \
        --argjson creatorAccountId "${creator_id}" --arg creatorProtocol "${protocol}" \
        --arg subject "$(printf '%s %03d' "${subject_prefix}" "${item_no}")" \
        --argjson adminAccountIds "${admin_ids}" --argjson helperAccountIds "${helper_ids}" \
        --argjson creatorSequence "${sequence}" --arg frozenAt "$(batch_now)" \
        '{recordType:"GROUP_PLAN",operationId:$operationId,itemId:$itemId,creatorAccountId:$creatorAccountId,creatorProtocol:$creatorProtocol,creatorSequence:$creatorSequence,subject:$subject,adminAccountIds:$adminAccountIds,helperAccountIds:$helperAccountIds,expectedParticipants:(($adminAccountIds|length)+($helperAccountIds|length)),expectedGroupSize:(1+($adminAccountIds|length)+($helperAccountIds|length)),executionMethod:"DIRECT_PROTOCOL_HTTP",historyPermission:"UNSUPPORTED",inviteLinkPermission:"LINK_EXISTENCE_ONLY",frozenAt:$frozenAt}' \
        >>"${ledger_file}"
    done
  done < <(jq -r '.[]' <<<"${creators}")
}

batch_http() {
  local method="$1" protocol="$2" url="$3" body_file="$4" response_file="$5"
  local status_file="${response_file}.status" curl_status=0
  local -a headers
  headers=(-H 'Content-Type: application/json')
  if [ "${protocol}" = "WEB" ]; then
    headers+=(-H "x-api-key: ${BATCH_WEB_API_KEY}")
  elif [ -n "${BATCH_ANDROID_API_KEY}" ]; then
    headers+=(-H "x-api-key: ${BATCH_ANDROID_API_KEY}")
  fi
  : >"${response_file}"
  if [ "${method}" = "GET" ]; then
    curl -sS --connect-timeout 10 --max-time 90 -o "${response_file}" -w '%{http_code}' \
      "${headers[@]}" "${url}" >"${status_file}" 2>/dev/null || curl_status=$?
  else
    curl -sS --connect-timeout 10 --max-time 90 -o "${response_file}" -w '%{http_code}' \
      "${headers[@]}" -X "${method}" --data "@${body_file}" "${url}" >"${status_file}" 2>/dev/null \
      || curl_status=$?
  fi
  BATCH_HTTP_STATUS="$(cat "${status_file}" 2>/dev/null || true)"
  case "${BATCH_HTTP_STATUS}" in ''|*[!0-9]*) BATCH_HTTP_STATUS=0 ;; *) BATCH_HTTP_STATUS=$((10#${BATCH_HTTP_STATUS})) ;; esac
  BATCH_CURL_STATUS="${curl_status}"
  rm -f "${status_file}"
}

batch_android_success() {
  local response_file="$1"
  [ "${BATCH_CURL_STATUS}" -eq 0 ] && [ "${BATCH_HTTP_STATUS}" -ge 200 ] \
    && [ "${BATCH_HTTP_STATUS}" -lt 300 ] && [ "$(jq -r '.Code // .code // ""' "${response_file}" 2>/dev/null)" = "0" ]
}

batch_web_success() {
  [ "${BATCH_CURL_STATUS}" -eq 0 ] && [ "${BATCH_HTTP_STATUS}" -ge 200 ] && [ "${BATCH_HTTP_STATUS}" -lt 300 ]
}

batch_lookup_group() {
  local protocol="$1" protocol_id="$2" phone="$3" subject="$4" run_dir="$5" response_file
  response_file="${run_dir}/groups.json"
  if [ "${protocol}" = "WEB" ]; then
    batch_http GET WEB "${BATCH_WEB_BASE_URL}/v1/accounts/${protocol_id}/groups" /dev/null "${response_file}"
    batch_web_success || return 1
    jq -r --arg subject "${subject}" '[(.groups // .)[]|select(.subject==$subject)|.groupJid] | if length==1 then .[0] else "" end' "${response_file}"
  else
    batch_http GET ANDROID "${BATCH_ANDROID_BASE_URL}/ws/v1/groups/list/${phone}" /dev/null "${response_file}"
    batch_android_success "${response_file}" || return 1
    jq -r --arg subject "${subject}" '[.Data.GroupInfos[]|select((.subject // .Subject)==$subject)|(.group_id // .GroupId)] | if length==1 then (.[0]|if contains("@") then . else .+"@g.us" end) else "" end' "${response_file}"
  fi
}

batch_result() {
  local result_file="$1" item_id="$2" creator_id="$3" protocol="$4" subject="$5"
  local final_status="$6" reason_class="$7" group_jid="$8" expected_size="$9" actual_size="${10}"
  local missing="${11}" admins="${12}" unexpected="${13}" messages="${14}" member_add="${15}"
  local approval="${16}" approval_verification="${17}" invite="${18}" subject_ok="${19}"
  jq -nc --argjson itemId "${item_id}" --argjson creatorAccountId "${creator_id}" \
    --arg creatorProtocol "${protocol}" --arg subject "${subject}" --arg finalStatus "${final_status}" \
    --arg reasonClass "${reason_class}" --arg groupJid "${group_jid}" \
    --argjson expectedSize "${expected_size}" --argjson actualSize "${actual_size}" \
    --argjson missingParticipants "${missing}" --argjson confirmedAdmins "${admins}" \
    --argjson unexpectedRows "${unexpected}" --argjson messagesAllowed "${messages}" \
    --argjson memberAddAllowed "${member_add}" --argjson joinApprovalOff "${approval}" \
    --arg joinApprovalVerification "${approval_verification}" --argjson inviteLinkExists "${invite}" \
    --argjson subjectVerified "${subject_ok}" --arg completedAt "$(batch_now)" \
    '{recordType:"GROUP_ITEM_FINAL",itemId:$itemId,creatorAccountId:$creatorAccountId,creatorProtocol:$creatorProtocol,subject:$subject,finalStatus:$finalStatus,reasonClass:$reasonClass,groupJid:$groupJid,actualSize:$actualSize,expectedSize:$expectedSize,missingParticipants:$missingParticipants,unexpectedRows:$unexpectedRows,confirmedAdmins:$confirmedAdmins,subjectVerified:$subjectVerified,messagesAllowed:$messagesAllowed,memberAddAllowed:$memberAddAllowed,joinApprovalOff:$joinApprovalOff,joinApprovalVerification:$joinApprovalVerification,inviteLinkExists:$inviteLinkExists,historyPermission:"UNSUPPORTED",completedAt:$completedAt}' \
    >"${result_file}"
}

batch_execute_item() {
  local item_file="$1" result_file="$2" item_id creator_id protocol subject run_dir
  local protocol_id phone admin_ids helper_ids admin_rows helper_rows participants_file admin_file participants_json
  local create_body create_response group_jid create_http create_curl response body action_ok=true
  local expected_participants expected_size metadata actual_size missing admins unexpected messages member_add approval invite subject_ok final_status reason=""
  IFS=$'\t' read -r item_id creator_id protocol subject <"${item_file}"
  run_dir="$(mktemp -d "/tmp/armada-batch-group-item-${item_id}.XXXXXX")"
  protocol_id="$(awk -F '\t' -v id="${creator_id}" '$2==id{print $4;exit}' "${BATCH_GROUP_ACCOUNTS_FILE}")"
  phone="$(awk -F '\t' -v id="${creator_id}" '$2==id{print $5;exit}' "${BATCH_GROUP_ACCOUNTS_FILE}")"
  admin_ids="$(jq -sc --argjson itemId "${item_id}" '[.[]|select(.recordType=="GROUP_PLAN" and .itemId==$itemId)][0].adminAccountIds' "${BATCH_GROUP_LEDGER_FILE}")"
  helper_ids="$(jq -sc --argjson itemId "${item_id}" '[.[]|select(.recordType=="CONTACT_GATE_CHECK" and .mode=="live" and .status=="READY" and .itemId==$itemId)][-1].effectiveHelperAccountIds' "${BATCH_GROUP_LEDGER_FILE}")"
  expected_participants=$((2 + $(jq 'length' <<<"${helper_ids}")))
  expected_size=$((expected_participants + 1))
  admin_rows="${run_dir}/admins.tsv"; helper_rows="${run_dir}/helpers.tsv"; : >"${admin_rows}"; : >"${helper_rows}"
  while IFS= read -r id; do awk -F '\t' -v id="${id}" '$2==id{print;exit}' "${BATCH_GROUP_ACCOUNTS_FILE}" >>"${admin_rows}"; done < <(jq -r '.[]' <<<"${admin_ids}")
  while IFS= read -r id; do awk -F '\t' -v id="${id}" '$2==id{print;exit}' "${BATCH_GROUP_ACCOUNTS_FILE}" >>"${helper_rows}"; done < <(jq -r '.[]' <<<"${helper_ids}")
  participants_file="${run_dir}/participants.txt"; admin_file="${run_dir}/admins.txt"
  awk -F '\t' '{print $5 "@s.whatsapp.net"}' "${admin_rows}" >"${admin_file}"
  { cat "${admin_file}"; awk -F '\t' '{print $5 "@s.whatsapp.net"}' "${helper_rows}"; } | awk 'NF&&!seen[$0]++' >"${participants_file}"
  if [ "$(awk 'END{print NR}' "${participants_file}")" -ne "${expected_participants}" ]; then
    batch_result "${result_file}" "${item_id}" "${creator_id}" "${protocol}" "${subject}" FAILED PLAN_INVALID "" "${expected_size}" 0 "${expected_participants}" 0 0 false false false NONE false false
    rm -rf -- "${run_dir}"; return 0
  fi
  participants_json="$(jq -Rsc 'split("\n")|map(select(length>0))' "${participants_file}")"
  create_body="${run_dir}/create.json"; create_response="${run_dir}/create-response.json"
  if [ "${protocol}" = "WEB" ]; then
    jq -nc --arg accountId "${protocol_id}" --arg subject "${subject}" --argjson participants "${participants_json}" '{accountId:$accountId,subject:$subject,participants:$participants,announceOnly:false}' >"${create_body}"
    batch_http POST WEB "${BATCH_WEB_BASE_URL}/v1/groups/create" "${create_body}" "${create_response}"
    group_jid="$(jq -r '.groupJid // ""' "${create_response}" 2>/dev/null || true)"
  else
    jq -nc --arg subject "${subject}" --argjson participants "${participants_json}" '{subject:$subject,participants:$participants}' >"${create_body}"
    batch_http POST ANDROID "${BATCH_ANDROID_BASE_URL}/ws/v1/groups/create/${phone}" "${create_body}" "${create_response}"
    group_jid="$(jq -r '.Data.GroupId // .data.GroupId // ""' "${create_response}" 2>/dev/null || true)"
    [ -z "${group_jid}" ] || [[ "${group_jid}" == *@* ]] || group_jid="${group_jid}@g.us"
  fi
  create_http="${BATCH_HTTP_STATUS}"; create_curl="${BATCH_CURL_STATUS}"
  [ -n "${group_jid}" ] || group_jid="$(batch_lookup_group "${protocol}" "${protocol_id}" "${phone}" "${subject}" "${run_dir}" || true)"
  if [ -z "${group_jid}" ]; then
    if grep -Eqi 'rate-overlimit|code[^0-9]*429|too many|reachout' "${create_response}" 2>/dev/null || [ "${create_http}" -eq 429 ]; then reason=RATE_LIMITED; final_status=FAILED
    elif [ "${create_curl}" -ne 0 ] || [ "${create_http}" -ge 500 ] || [ "${create_http}" -eq 0 ]; then reason=CREATE_RESULT_UNKNOWN; final_status=UNKNOWN
    else reason=CREATE_FAILED; final_status=FAILED; fi
    batch_result "${result_file}" "${item_id}" "${creator_id}" "${protocol}" "${subject}" "${final_status}" "${reason}" "" "${expected_size}" 0 "${expected_participants}" 0 0 false false false NONE false false
    rm -rf -- "${run_dir}"; return 0
  fi
  response="${run_dir}/response.json"; body="${run_dir}/body.json"
  if [ "${protocol}" = "WEB" ]; then
    jq -nc --arg accountId "${protocol_id}" --argjson participants "$(jq -Rsc 'split("\n")|map(select(length>0))' "${admin_file}")" '{accountId:$accountId,participants:$participants,timeoutMs:30000}' >"${body}"
    batch_http POST WEB "${BATCH_WEB_BASE_URL}/v1/groups/${group_jid}/participants/promote" "${body}" "${response}"; batch_web_success || action_ok=false
    jq -nc --arg accountId "${protocol_id}" '{accountId:$accountId,mode:"not_announcement"}' >"${body}"; batch_http POST WEB "${BATCH_WEB_BASE_URL}/v1/groups/${group_jid}/settings/announcement" "${body}" "${response}"; batch_web_success || action_ok=false
    jq -nc --arg accountId "${protocol_id}" '{accountId:$accountId,mode:"all_member_add"}' >"${body}"; batch_http POST WEB "${BATCH_WEB_BASE_URL}/v1/groups/${group_jid}/settings/member-add-mode" "${body}" "${response}"; batch_web_success || action_ok=false
    jq -nc --arg accountId "${protocol_id}" '{accountId:$accountId,mode:"off"}' >"${body}"; batch_http POST WEB "${BATCH_WEB_BASE_URL}/v1/groups/${group_jid}/settings/join-approval" "${body}" "${response}"; batch_web_success || action_ok=false
    batch_http GET WEB "${BATCH_WEB_BASE_URL}/v1/groups/${group_jid}/invite-code?accountId=${protocol_id}" /dev/null "${response}"; invite=false; batch_web_success && [ -n "$(jq -r '.inviteCode // ""' "${response}" 2>/dev/null)" ] && invite=true
    sleep 3
    batch_http GET WEB "${BATCH_WEB_BASE_URL}/v1/groups/${group_jid}/metadata?accountId=${protocol_id}" /dev/null "${response}"
    if ! batch_web_success; then batch_result "${result_file}" "${item_id}" "${creator_id}" WEB "${subject}" PARTIAL METADATA_FAILED "${group_jid}" "${expected_size}" 0 "${expected_participants}" 0 0 false false false METADATA false false; rm -rf -- "${run_dir}"; return 0; fi
    metadata="${response}"; actual_size="$(jq -r '.size // 0' "${metadata}")"
    missing="$(jq -n --argjson expected "${participants_json}" --slurpfile meta "${metadata}" '$expected-([$meta[0].participants[]|.id,.phoneNumber,.lid]|map(select(.!=null))|unique)|length')"
    admins="$(jq -n --argjson expected "$(jq -Rsc 'split("\n")|map(select(length>0))' "${admin_file}")" --slurpfile meta "${metadata}" '[ $meta[0].participants[]|select(([.id,.phoneNumber,.lid]|map(select(.!=null)|. as $x|select($expected|index($x)))|length)>0)|select(.admin=="admin" or .admin=="superadmin")]|length')"
    unexpected=$((actual_size - (expected_participants - missing) - 1)); [ "${unexpected}" -ge 0 ] || unexpected=0
    [ "$(jq -r '.subject // ""' "${metadata}")" = "${subject}" ] && subject_ok=true || subject_ok=false
    [ "$(jq -r '.announce' "${metadata}")" = false ] && messages=true || messages=false
    [ "$(jq -r '.memberAddMode' "${metadata}")" = true ] && member_add=true || member_add=false
    [ "$(jq -r '.joinApprovalMode' "${metadata}")" = false ] && approval=true || approval=false
    approval_verification=METADATA
  else
    while IFS= read -r admin; do jq -nc --arg group_id "${group_jid}" --arg participant "${admin}" '{group_id:$group_id,state:true,participant:$participant}' >"${body}"; batch_http POST ANDROID "${BATCH_ANDROID_BASE_URL}/ws/v1/groups/admin/set/${phone}" "${body}" "${response}"; batch_android_success "${response}" || action_ok=false; done <"${admin_file}"
    jq -nc --arg group_id "${group_jid}" '{group_id:$group_id,state:true}' >"${body}"; batch_http POST ANDROID "${BATCH_ANDROID_BASE_URL}/ws/v1/groups/settings/sendmessage/${phone}" "${body}" "${response}"; batch_android_success "${response}" || action_ok=false
    batch_http POST ANDROID "${BATCH_ANDROID_BASE_URL}/ws/v1/groups/settings/join-mode/${phone}" "${body}" "${response}"; batch_android_success "${response}" || action_ok=false
    jq -nc --arg group_id "${group_jid}" '{group_id:$group_id,state:false}' >"${body}"; batch_http POST ANDROID "${BATCH_ANDROID_BASE_URL}/ws/v1/groups/settings/approval/${phone}" "${body}" "${response}"; approval=false; batch_android_success "${response}" && approval=true || action_ok=false; approval_verification=API_CONFIRMED
    jq -nc --arg group_id "${group_jid}" '{group_id:$group_id}' >"${body}"; batch_http POST ANDROID "${BATCH_ANDROID_BASE_URL}/ws/v1/groups/qrcode/${phone}" "${body}" "${response}"; invite=false; batch_android_success "${response}" && [ -n "$(jq -r '.Data // ""' "${response}" 2>/dev/null)" ] && invite=true
    sleep 3
    batch_http POST ANDROID "${BATCH_ANDROID_BASE_URL}/ws/v1/groups/members/${phone}" "${body}" "${response}"
    if ! batch_android_success "${response}"; then batch_result "${result_file}" "${item_id}" "${creator_id}" ANDROID "${subject}" PARTIAL METADATA_FAILED "${group_jid}" "${expected_size}" 0 "${expected_participants}" 0 0 false false "${approval}" "${approval_verification}" "${invite}" false; rm -rf -- "${run_dir}"; return 0; fi
    metadata="${response}"; actual_size="$(jq -r '.Data.Count // 0' "${metadata}")"
    missing="$(jq -n --argjson expected "${participants_json}" --slurpfile meta "${metadata}" '$expected-([$meta[0].Data.Participants[]|.jid,.phone,.phone_number,.phoneNumber]|map(select(.!=null)|tostring|split("@")[0]+"@s.whatsapp.net")|unique)|length')"
    admins="$(jq -n --argjson expected "$(jq -Rsc 'split("\n")|map(select(length>0))' "${admin_file}")" --slurpfile meta "${metadata}" '[ $meta[0].Data.Participants[]|select((.type // "" | ascii_downcase)=="admin" or (.type // "" | ascii_downcase)=="superadmin")|(.phone // .phone_number // .phoneNumber // .jid // "")|tostring|split("@")[0]+"@s.whatsapp.net" as $jid|select($expected|index($jid))]|length')"
    unexpected=$((actual_size - (expected_participants - missing) - 1)); [ "${unexpected}" -ge 0 ] || unexpected=0
    [ "$(jq -r '.Data.Subject // ""' "${metadata}")" = "${subject}" ] && subject_ok=true || subject_ok=false
    [ "$(jq -r '.Data.Announce' "${metadata}")" = false ] && messages=true || messages=false
    [ "$(jq -r '.Data.MemberAddMode // ""' "${metadata}")" = all_member_add ] && member_add=true || member_add=false
  fi
  final_status=SUCCESS
  [ "${actual_size}" -eq "${expected_size}" ] && [ "${missing}" -eq 0 ] && [ "${admins}" -eq 2 ] \
    && [ "${unexpected}" -eq 0 ] && [ "${messages}" = true ] && [ "${member_add}" = true ] \
    && [ "${approval}" = true ] && [ "${invite}" = true ] && [ "${subject_ok}" = true ] && [ "${action_ok}" = true ] \
    || final_status=PARTIAL
  [ "${final_status}" = SUCCESS ] || reason=VERIFY_MISMATCH
  batch_result "${result_file}" "${item_id}" "${creator_id}" "${protocol}" "${subject}" "${final_status}" "${reason}" "${group_jid}" "${expected_size}" "${actual_size}" "${missing}" "${admins}" "${unexpected}" "${messages}" "${member_add}" "${approval}" "${approval_verification}" "${invite}" "${subject_ok}"
  rm -rf -- "${run_dir}"
}

batch_remote_main() {
  local mode="" ledger_file="" operation_id="" subject_prefix="Armada Batch Group" confirmed=false
  local run_dir accounts_file contact_evidence plan_count planned_creators tasks_file work_dir total ready runnable blocked summary
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --mode) mode="$2"; shift 2 ;; --ledger) ledger_file="$2"; shift 2 ;;
      --operation-id) operation_id="$2"; shift 2 ;; --subject-prefix) subject_prefix="$2"; shift 2 ;;
      --yes) confirmed=true; shift ;; *) batch_fail "未知远端参数: $1"; return 2 ;;
    esac
  done
  case "${mode}" in dry-run|live) ;; *) batch_fail "mode 必须是 dry-run/live"; return 2 ;; esac
  batch_validate_ledger "${ledger_file}"
  case "${operation_id}" in ''|*[!A-Za-z0-9._-]*) batch_fail "operation-id 非法"; return 2 ;; esac
  [[ "${subject_prefix}" =~ ^[A-Za-z][A-Za-z0-9._\ -]{0,80}$ ]] || { batch_fail "群名前缀非法"; return 2; }
  [ "${mode}" != live ] || [ "${confirmed}" = true ] || { batch_fail "live 必须 --yes"; return 2; }
  for command_name in docker mysql jq curl flock sha256sum; do command -v "${command_name}" >/dev/null || { batch_fail "缺少 ${command_name}"; return 2; }; done
  umask 077; exec 9>/tmp/armada-batch-group-create.lock; flock -n 9 || { batch_fail "已有批量建群运行中"; return 75; }
  [ -e "${ledger_file}" ] || : >"${ledger_file}"; [ ! -s "${ledger_file}" ] || jq -e . "${ledger_file}" >/dev/null
  run_dir="$(mktemp -d /tmp/armada-batch-group-run.XXXXXX)"; trap "rm -rf -- '${run_dir}'" EXIT
  batch_load_runtime; accounts_file="${run_dir}/accounts.tsv"; batch_load_accounts "${accounts_file}"
  contact_evidence="${run_dir}/contact-evidence.tsv"
  batch_build_contact_evidence "${accounts_file}" "${contact_evidence}" "${run_dir}"
  plan_count="$(jq -s '[.[]|select(.recordType=="GROUP_PLAN")]|length' "${ledger_file}")"
  if [ "${plan_count}" -eq 0 ]; then
    batch_make_plans "${accounts_file}" "${ledger_file}" "${operation_id}" "${subject_prefix}" "${contact_evidence}"
    plan_count="$(jq -s '[.[]|select(.recordType=="GROUP_PLAN")]|length' "${ledger_file}")"
  fi
  [ "${plan_count}" -gt 0 ] && [ $((plan_count % 5)) -eq 0 ] \
    || { batch_fail "账本 GROUP_PLAN 必须按每个建群号 5 条生成"; return 2; }
  planned_creators=$((plan_count / 5))
  [ "$(jq -sr '[.[]|select(.recordType=="GROUP_PLAN")]|map(.operationId)|unique|.[0]' "${ledger_file}")" = "${operation_id}" ] || { batch_fail "operation-id 与计划不一致"; return 2; }
  batch_validate_all_plan_contact_gates "${accounts_file}" "${ledger_file}" "${contact_evidence}" "${mode}"
  total="$(awk -F '\t' '$1==148{n++}END{print n+0}' "${accounts_file}")"; ready="$(awk -F '\t' '$1==148&&$6=="READY"{n++}END{print n+0}' "${accounts_file}")"; blocked=$((total-ready))
  runnable="$(jq -s --arg mode "${mode}" '[.[]|select(.recordType=="CONTACT_GATE_CHECK" and .mode==$mode)]|group_by(.itemId)|map(.[-1])|map(select(.status=="READY"))|length' "${ledger_file}")"
  summary=DEGRADED; [ "${runnable}" -eq "${plan_count}" ] && summary=READY
  jq -nc --arg mode "${mode}" --arg status "${summary}" --argjson creators "${total}" --argjson readyCreators "${ready}" --argjson blockedCreators "${blocked}" --argjson plannedCreators "${planned_creators}" --argjson plannedGroups "${plan_count}" --argjson runnableGroups "${runnable}" --argjson intervalSeconds "${BATCH_GROUP_INTERVAL_SECONDS}" --argjson androidConcurrency "${BATCH_GROUP_ANDROID_CONCURRENCY}" --argjson webConcurrency "${BATCH_GROUP_WEB_CONCURRENCY}" --arg checkedAt "$(batch_now)" '{recordType:"BATCH_PREFLIGHT",mode:$mode,status:$status,creators:$creators,readyCreators:$readyCreators,blockedCreators:$blockedCreators,plannedCreators:$plannedCreators,plannedGroups:$plannedGroups,runnableGroups:$runnableGroups,intervalSeconds:$intervalSeconds,androidConcurrency:$androidConcurrency,webConcurrency:$webConcurrency,checkedAt:$checkedAt}' >>"${ledger_file}"
  printf 'PREFLIGHT status=%s creators=%s/%s plannedCreators=%s runnableGroups=%s/%s interval=%ss androidConcurrency=%s webConcurrency=%s\n' "${summary}" "${ready}" "${total}" "${planned_creators}" "${runnable}" "${plan_count}" "${BATCH_GROUP_INTERVAL_SECONDS}" "${BATCH_GROUP_ANDROID_CONCURRENCY}" "${BATCH_GROUP_WEB_CONCURRENCY}"
  if [ "${mode}" = dry-run ]; then printf 'DRY_RUN ledger=%s\n' "${ledger_file}"; return 0; fi
  jq -e 'select(.recordType=="BATCH_PREFLIGHT" and .mode=="dry-run")' "${ledger_file}" >/dev/null || { batch_fail "live 必须复用 dry-run 账本"; return 2; }
  tasks_file="${run_dir}/tasks.tsv"
  jq -r --slurpfile gate "${ledger_file}" 'select(.recordType=="GROUP_PLAN")|select(.itemId as $item|([$gate[]|select(.recordType=="CONTACT_GATE_CHECK" and .mode=="live" and .itemId==$item)][-1].status=="READY"))|[.itemId,.creatorAccountId,.creatorProtocol,.subject]|@tsv' "${ledger_file}" \
    | while IFS=$'\t' read -r item creator protocol subject; do
        [ "$(awk -F '\t' -v id="${creator}" '$2==id{print $6;exit}' "${accounts_file}")" = READY ] && printf '%s\t%s\t%s\t%s\n' "${item}" "${creator}" "${protocol}" "${subject}"
      done >"${tasks_file}"
  BATCH_GROUP_LEDGER_FILE="${ledger_file}"; BATCH_GROUP_ACCOUNTS_FILE="${accounts_file}"; export BATCH_GROUP_LEDGER_FILE BATCH_GROUP_ACCOUNTS_FILE
  work_dir="${run_dir}/scheduler"; batch_run_scheduler "${tasks_file}" "${ledger_file}" "${work_dir}"
  jq -sc '{recordType:"BATCH_RUN_SUMMARY",terminalItems:([.[]|select(.recordType=="GROUP_ITEM_FINAL")|.itemId]|unique|length),success:([.[]|select(.recordType=="GROUP_ITEM_FINAL" and .finalStatus=="SUCCESS")|.itemId]|unique|length),partial:([.[]|select(.recordType=="GROUP_ITEM_FINAL" and .finalStatus=="PARTIAL")|.itemId]|unique|length),failed:([.[]|select(.recordType=="GROUP_ITEM_FINAL" and .finalStatus=="FAILED")|.itemId]|unique|length),unknown:([.[]|select(.recordType=="GROUP_ITEM_FINAL" and .finalStatus=="UNKNOWN")|.itemId]|unique|length),pausedCreators:([.[]|select(.recordType=="CREATOR_PAUSED")|.creatorAccountId]|unique|length)}' "${ledger_file}" | tee -a "${ledger_file}"
}

batch_usage() {
  cat <<'EOF'
bash armada-deploy/tools/batch-group-create.sh --env test1 --mode dry-run --operation-id batch-20260813-001 --ledger /tmp/armada-batch-group-batch-20260813-001.jsonl
bash armada-deploy/tools/batch-group-create.sh --env test1 --mode live --yes --operation-id batch-20260813-001 --ledger /tmp/armada-batch-group-batch-20260813-001.jsonl
EOF
}

batch_local_main() {
  local selected_env="" mode="" ledger_file="" operation_id="" subject_prefix="Armada Batch Group" confirmed=false
  local script_dir deploy_dir repo_root workspace_root profile_file ssh_key remote_command escaped
  local -a args
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --env) selected_env="$2"; shift 2 ;; --mode) mode="$2"; shift 2 ;; --ledger) ledger_file="$2"; shift 2 ;;
      --operation-id) operation_id="$2"; shift 2 ;; --subject-prefix) subject_prefix="$2"; shift 2 ;;
      --yes) confirmed=true; shift ;; -h|--help) batch_usage; return 0 ;; *) batch_fail "未知参数: $1"; return 2 ;;
    esac
  done
  [ "${selected_env}" = test1 ] || { batch_fail "只允许 test1"; return 2; }
  case "${mode}" in dry-run|live) ;; *) batch_fail "mode 必须是 dry-run/live"; return 2 ;; esac
  batch_validate_ledger "${ledger_file}"; batch_require_positive_integer "间隔" "${BATCH_GROUP_INTERVAL_SECONDS}"
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; deploy_dir="$(cd "${script_dir}/.." && pwd)"; repo_root="$(cd "${deploy_dir}/.." && pwd)"; workspace_root="$(cd "${repo_root}/.." && pwd)"; profile_file="${deploy_dir}/envs/test1.conf"; . "${profile_file}"; ssh_key="${workspace_root}/${PROFILE_ARMADA_KEY_REL}"
  args=(--remote --mode "${mode}" --ledger "${ledger_file}" --operation-id "${operation_id}" --subject-prefix "${subject_prefix}"); [ "${confirmed}" = true ] && args+=(--yes)
  remote_command='bash -s --'; for escaped in "${args[@]}"; do printf -v escaped '%q' "${escaped}"; remote_command+=" ${escaped}"; done
  ssh -T -i "${ssh_key}" -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new "${PROFILE_ARMADA_USER}@${PROFILE_ARMADA_HOST}" "${remote_command}" <"${BASH_SOURCE[0]}"
}

if [ "${1:-}" = --remote ]; then shift; batch_remote_main "$@"; elif [[ "${BASH_SOURCE[0]:-}" == "$0" ]]; then batch_local_main "$@"; fi
