#!/usr/bin/env bash
if [ -z "${BASH_VERSION:-}" ]; then
  exec /usr/bin/env bash "$0" "$@"
fi
set -euo pipefail

group_probe_fail() {
  printf 'ERR %s\n' "$*" >&2
  return 1
}

group_probe_require_id() {
  case "$2" in
    ''|*[!0-9]*|0) group_probe_fail "$1必须是正整数: $2" ;;
  esac
}

group_probe_validate_ledger() {
  case "$1" in
    /tmp/armada-single-group-probe-*.jsonl) ;;
    *) group_probe_fail "账本必须是 /tmp/armada-single-group-probe-*.jsonl"; return 2 ;;
  esac
  case "${1#/tmp/}" in
    */*|*[!A-Za-z0-9._-]*) group_probe_fail "账本路径含非法字符"; return 2 ;;
  esac
}

group_probe_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

group_probe_runtime_env() {
  printf '%s\n' "${GROUP_PROBE_ENV_LINES}" | sed -n "s/^$1=//p" | head -n 1
}

group_probe_mysql() {
  MYSQL_PWD="${GROUP_PROBE_DB_PASS}" mysql -N -B \
    -h "${GROUP_PROBE_DB_HOST}" -P "${GROUP_PROBE_DB_PORT}" \
    -u "${GROUP_PROBE_DB_USER}" "${GROUP_PROBE_DB_NAME}" "$@"
}

group_probe_load_runtime() {
  local db_url db_addr db_hostport
  GROUP_PROBE_ENV_LINES="$(docker inspect armada-backend --format '{{range .Config.Env}}{{println .}}{{end}}')"
  db_url="$(group_probe_runtime_env DB_URL)"
  GROUP_PROBE_DB_USER="$(group_probe_runtime_env DB_USER)"
  GROUP_PROBE_DB_PASS="$(group_probe_runtime_env DB_PASSWORD)"
  GROUP_PROBE_WEB_BASE_URL="$(group_probe_runtime_env ARMADA_PROTOCOL_BASE_URL)"
  GROUP_PROBE_WEB_API_KEY="$(group_probe_runtime_env ARMADA_PROTOCOL_API_KEY)"
  [ -n "${db_url}" ] && [ -n "${GROUP_PROBE_DB_USER}" ] \
    && [ -n "${GROUP_PROBE_DB_PASS}" ] && [ -n "${GROUP_PROBE_WEB_BASE_URL}" ] \
    && [ -n "${GROUP_PROBE_WEB_API_KEY}" ] \
    || { group_probe_fail "test1 运行时配置不完整"; return 1; }
  GROUP_PROBE_WEB_BASE_URL="${GROUP_PROBE_WEB_BASE_URL%/}"
  db_addr="${db_url#jdbc:mysql://}"
  db_addr="${db_addr%%\?*}"
  db_hostport="${db_addr%%/*}"
  GROUP_PROBE_DB_NAME="${db_addr#*/}"
  GROUP_PROBE_DB_HOST="${db_hostport%%:*}"
  GROUP_PROBE_DB_PORT="${db_hostport##*:}"
  [ "${GROUP_PROBE_DB_PORT}" = "${db_hostport}" ] && GROUP_PROBE_DB_PORT=3306
  export GROUP_PROBE_DB_PASS
}

group_probe_load_accounts() {
  local output_file="$1"
  group_probe_mysql -e "
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
  AND a.tenant_id = 1
ORDER BY a.account_group_id, a.id;" >"${output_file}"
}

group_probe_plan() {
  local accounts_file="$1" ledger_file="$2" operation_id="$3" subject="$4"
  local creator_id creator_protocol admin_candidates helper_count helper_missing
  local admin_ids helper_ids expected_participants
  creator_id="$(awk -F '\t' '$1==148 && $3=="WEB" && $6=="READY" {print $2; exit}' "${accounts_file}")"
  [ -n "${creator_id}" ] || { group_probe_fail "分组 148 没有 READY Web 建群号"; return 1; }
  creator_protocol="WEB"
  admin_candidates="$(awk -F '\t' '$1==149 && $6=="READY" {count++} END {print count+0}' "${accounts_file}")"
  [ "${admin_candidates}" -ge 4 ] || { group_probe_fail "分组 149 READY 账号不足 4 个"; return 1; }
  helper_count="$(awk -F '\t' '$1==110 {count++} END {print count+0}' "${accounts_file}")"
  helper_missing="$(awk -F '\t' '$1==110 && ($5=="" || $3!="ANDROID" && $3!="WEB") {count++} END {print count+0}' "${accounts_file}")"
  [ "${helper_count}" -eq 59 ] && [ "${helper_missing}" -eq 0 ] \
    || { group_probe_fail "分组 110 必须冻结 59 个身份完整账号"; return 1; }
  admin_ids="$(awk -F '\t' -v seed="${operation_id}" '$1==149 && $6=="READY" {print seed ":" $2 "\t" $2}' \
    "${accounts_file}" | while IFS=$'\t' read -r key account_id; do
      printf '%s\t%s\n' "$(printf '%s' "${key}" | sha256sum | awk '{print $1}')" "${account_id}"
    done | sort | head -n 4 | awk '{print $2}' | jq -Rsc 'split("\n")|map(select(length>0)|tonumber)')"
  helper_ids="$(awk -F '\t' '$1==110 {print $2}' "${accounts_file}" \
    | jq -Rsc 'split("\n")|map(select(length>0)|tonumber)')"
  expected_participants=$((4 + helper_count))
  jq -nc \
    --arg operationId "${operation_id}" --arg executionMethod DIRECT_PROTOCOL_HTTP \
    --arg subject "${subject}" --argjson creatorAccountId "${creator_id}" \
    --arg creatorProtocol "${creator_protocol}" --argjson adminAccountIds "${admin_ids}" \
    --argjson helperAccountIds "${helper_ids}" --argjson expectedParticipants "${expected_participants}" \
    --argjson expectedGroupSize "$((expected_participants + 1))" --arg frozenAt "$(group_probe_now)" \
    '{recordType:"GROUP_PLAN",operationId:$operationId,executionMethod:$executionMethod,subject:$subject,creatorAccountId:$creatorAccountId,creatorProtocol:$creatorProtocol,adminAccountIds:$adminAccountIds,helperAccountIds:$helperAccountIds,expectedParticipants:$expectedParticipants,expectedGroupSize:$expectedGroupSize,historyPermission:"UNSUPPORTED",inviteLinkPermission:"LINK_EXISTENCE_ONLY",frozenAt:$frozenAt}' \
    >>"${ledger_file}"
}

group_probe_http() {
  local method="$1" url="$2" body_file="$3" response_file="$4"
  local status_file="${response_file}.status" curl_status=0
  : >"${response_file}"
  if [ "${method}" = "GET" ]; then
    curl -sS --connect-timeout 10 --max-time 90 -o "${response_file}" -w '%{http_code}' \
      -H "x-api-key: ${GROUP_PROBE_WEB_API_KEY}" "${url}" >"${status_file}" 2>/dev/null \
      || curl_status=$?
  else
    curl -sS --connect-timeout 10 --max-time 90 -o "${response_file}" -w '%{http_code}' \
      -H 'Content-Type: application/json' -H "x-api-key: ${GROUP_PROBE_WEB_API_KEY}" \
      -X "${method}" --data "@${body_file}" "${url}" >"${status_file}" 2>/dev/null \
      || curl_status=$?
  fi
  GROUP_PROBE_HTTP_STATUS="$(cat "${status_file}" 2>/dev/null || true)"
  case "${GROUP_PROBE_HTTP_STATUS}" in ''|*[!0-9]*) GROUP_PROBE_HTTP_STATUS=0 ;; esac
  GROUP_PROBE_HTTP_STATUS="$((10#${GROUP_PROBE_HTTP_STATUS}))"
  GROUP_PROBE_CURL_STATUS="${curl_status}"
  rm -f "${status_file}"
}

group_probe_post_action() {
  local action="$1" url="$2" body_file="$3" response_file="$4" ledger_file="$5"
  group_probe_http POST "${url}" "${body_file}" "${response_file}"
  local outcome="SUCCESS"
  if [ "${GROUP_PROBE_CURL_STATUS}" -ne 0 ] || [ "${GROUP_PROBE_HTTP_STATUS}" -lt 200 ] \
      || [ "${GROUP_PROBE_HTTP_STATUS}" -ge 300 ]; then
    outcome="FAILED"
  fi
  jq -nc --arg action "${action}" --arg outcome "${outcome}" \
    --argjson httpStatus "${GROUP_PROBE_HTTP_STATUS}" --arg attemptedAt "$(group_probe_now)" \
    '{recordType:"GROUP_ACTION",action:$action,outcome:$outcome,httpStatus:$httpStatus,attemptedAt:$attemptedAt}' \
    >>"${ledger_file}"
  [ "${outcome}" = "SUCCESS" ]
}

group_probe_lookup_group() {
  local account_id="$1" subject="$2" response_file="$3"
  group_probe_http GET "${GROUP_PROBE_WEB_BASE_URL}/v1/accounts/${account_id}/groups" /dev/null "${response_file}"
  [ "${GROUP_PROBE_CURL_STATUS}" -eq 0 ] && [ "${GROUP_PROBE_HTTP_STATUS}" -ge 200 ] \
    && [ "${GROUP_PROBE_HTTP_STATUS}" -lt 300 ] || return 1
  jq -r --arg subject "${subject}" \
    '(.groups // .) as $groups | [$groups[]|select(.subject==$subject)|.groupJid] | if length==1 then .[0] else "" end' \
    "${response_file}"
}

group_probe_remote_main() {
  local mode="" ledger_file="" subject="" operation_id="" confirmed="false"
  local run_dir accounts_file plan_count creator_id account_id creator_protocol creator_phone
  local admin_ids helper_ids participant_ids admin_rows helper_rows participants_file admin_jids_file
  local participants_json admin_jids_json create_body create_response group_jid lookup_response
  local metadata_response settings_body action_response promote_body invite_response
  local expected_size actual_size admin_count missing_count subject_ok announce_ok member_add_ok approval_ok invite_ok
  local create_http_status create_curl_status
  local creator_jid expected_member_rows creator_rows unexpected_rows unexpected_account_ids unexpected_phones
  local unexpected_identity_kinds unexpected_account_groups
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --mode) mode="$2"; shift 2 ;;
      --ledger) ledger_file="$2"; shift 2 ;;
      --subject) subject="$2"; shift 2 ;;
      --operation-id) operation_id="$2"; shift 2 ;;
      --yes) confirmed="true"; shift ;;
      *) group_probe_fail "未知远端参数: $1"; return 2 ;;
    esac
  done
  case "${mode}" in dry-run|live|verify) ;; *) group_probe_fail "mode 必须是 dry-run/live/verify"; return 2 ;; esac
  group_probe_validate_ledger "${ledger_file}"
  [[ "${subject}" =~ ^[A-Za-z0-9][A-Za-z0-9._\ -]{0,99}$ ]] \
    || { group_probe_fail "群名必须是 1-100 位英文/数字/空格/安全分隔符"; return 2; }
  case "${operation_id}" in
    ''|*[!A-Za-z0-9._-]*) group_probe_fail "operation-id 只能包含英文、数字、点、下划线和连字符"; return 2 ;;
  esac
  [ "${mode}" != "live" ] || [ "${confirmed}" = "true" ] \
    || { group_probe_fail "live 必须传 --yes"; return 2; }
  for command_name in docker mysql jq curl flock sha256sum; do
    command -v "${command_name}" >/dev/null 2>&1 || { group_probe_fail "远端缺少 ${command_name}"; return 2; }
  done
  umask 077
  exec 9>/tmp/armada-single-group-probe.lock
  flock -n 9 || { group_probe_fail "已有单群试建运行中"; return 75; }
  [ -e "${ledger_file}" ] || : >"${ledger_file}"
  [ ! -s "${ledger_file}" ] || jq -e . "${ledger_file}" >/dev/null 2>&1 \
    || { group_probe_fail "账本不是合法 JSONL"; return 2; }
  run_dir="$(mktemp -d /tmp/armada-single-group-probe-run.XXXXXX)"
  trap "rm -rf -- '${run_dir}'" EXIT
  group_probe_load_runtime
  accounts_file="${run_dir}/accounts.tsv"
  group_probe_load_accounts "${accounts_file}"
  plan_count="$(jq -s '[.[]|select(.recordType=="GROUP_PLAN")]|length' "${ledger_file}")"
  if [ "${plan_count}" -eq 0 ]; then
    group_probe_plan "${accounts_file}" "${ledger_file}" "${operation_id}" "${subject}"
  elif [ "${plan_count}" -ne 1 ]; then
    group_probe_fail "账本必须且只能有一个 GROUP_PLAN"; return 2
  fi
  creator_id="$(jq -sr '[.[]|select(.recordType=="GROUP_PLAN")][0].creatorAccountId' "${ledger_file}")"
  admin_ids="$(jq -sc '[.[]|select(.recordType=="GROUP_PLAN")][0].adminAccountIds' "${ledger_file}")"
  helper_ids="$(jq -sc '[.[]|select(.recordType=="GROUP_PLAN")][0].helperAccountIds' "${ledger_file}")"
  expected_size="$(jq -sr '[.[]|select(.recordType=="GROUP_PLAN")][0].expectedGroupSize' "${ledger_file}")"
  [ "$(jq -sr '[.[]|select(.recordType=="GROUP_PLAN")][0].subject' "${ledger_file}")" = "${subject}" ] \
    || { group_probe_fail "群名与冻结计划不一致"; return 2; }
  [ "$(jq -sr '[.[]|select(.recordType=="GROUP_PLAN")][0].operationId' "${ledger_file}")" = "${operation_id}" ] \
    || { group_probe_fail "operation-id 与冻结计划不一致"; return 2; }
  creator_protocol="$(awk -F '\t' -v id="${creator_id}" '$2==id {print $3; exit}' "${accounts_file}")"
  [ "$(awk -F '\t' -v id="${creator_id}" '$2==id {print $1; exit}' "${accounts_file}")" = "148" ] \
    && [ "${creator_protocol}" = "WEB" ] \
    && [ "$(awk -F '\t' -v id="${creator_id}" '$2==id {print $6; exit}' "${accounts_file}")" = "READY" ] \
    || { group_probe_fail "冻结建群号当前不是 READY Web 账号"; return 20; }
  [ "$(jq 'length' <<<"${admin_ids}")" -eq 4 ] || { group_probe_fail "冻结管理员不是 4 个"; return 2; }
  while IFS= read -r account_id; do
    [ "$(awk -F '\t' -v id="${account_id}" '$2==id {print $1; exit}' "${accounts_file}")" = "149" ] \
      && [ "$(awk -F '\t' -v id="${account_id}" '$2==id {print $6; exit}' "${accounts_file}")" = "READY" ] \
      || { group_probe_fail "冻结管理员 ${account_id} 当前不可用"; return 20; }
  done < <(jq -r '.[]' <<<"${admin_ids}")
  helper_rows="${run_dir}/helpers.tsv"
  admin_rows="${run_dir}/admins.tsv"
  : >"${helper_rows}"; : >"${admin_rows}"
  while IFS= read -r account_id; do
    awk -F '\t' -v id="${account_id}" '$2==id {print; exit}' "${accounts_file}" >>"${helper_rows}"
  done < <(jq -r '.[]' <<<"${helper_ids}")
  while IFS= read -r account_id; do
    awk -F '\t' -v id="${account_id}" '$2==id {print; exit}' "${accounts_file}" >>"${admin_rows}"
  done < <(jq -r '.[]' <<<"${admin_ids}")
  [ "$(awk 'END{print NR}' "${helper_rows}")" -eq 59 ] \
    || { group_probe_fail "冻结辅助成员不再是 59 个"; return 20; }
  [ "$(awk -F '\t' '$1!=110 || $5=="" || ($3!="ANDROID" && $3!="WEB") {count++} END{print count+0}' "${helper_rows}")" -eq 0 ] \
    || { group_probe_fail "冻结辅助成员的分组或协议身份发生变化"; return 20; }
  participants_file="${run_dir}/participants.txt"
  admin_jids_file="${run_dir}/admin-jids.txt"
  awk -F '\t' '{print $5 "@s.whatsapp.net"}' "${admin_rows}" >"${admin_jids_file}"
  { cat "${admin_jids_file}"; awk -F '\t' '{print $5 "@s.whatsapp.net"}' "${helper_rows}"; } \
    | awk 'NF && !seen[$0]++' >"${participants_file}"
  [ "$(awk 'END{print NR}' "${participants_file}")" -eq 63 ] \
    || { group_probe_fail "冻结 participants 去重后不是 63 个"; return 20; }
  participants_json="$(jq -Rsc 'split("\n")|map(select(length>0))' "${participants_file}")"
  admin_jids_json="$(jq -Rsc 'split("\n")|map(select(length>0))' "${admin_jids_file}")"
  printf 'PLAN creatorAccountId=%s creatorProtocol=WEB adminAccountIds=%s helperCount=59 expectedGroupSize=%s subject=%s\n' \
    "${creator_id}" "$(jq -c . <<<"${admin_ids}")" "${expected_size}" "${subject}"
  if [ "${mode}" = "dry-run" ]; then
    jq -nc --arg status READY --arg checkedAt "$(group_probe_now)" \
      '{recordType:"GROUP_PREFLIGHT",mode:"dry-run",status:$status,checkedAt:$checkedAt}' >>"${ledger_file}"
    printf 'DRY_RUN ledger=%s\n' "${ledger_file}"
    return 0
  fi
  jq -e 'select(.recordType=="GROUP_PREFLIGHT" and .mode=="dry-run" and .status=="READY")' \
    "${ledger_file}" >/dev/null 2>&1 || { group_probe_fail "live 必须复用 dry-run 账本"; return 2; }
  account_id="$(awk -F '\t' -v id="${creator_id}" '$2==id {print $4; exit}' "${accounts_file}")"
  creator_phone="$(awk -F '\t' -v id="${creator_id}" '$2==id {print $5; exit}' "${accounts_file}")"
  creator_jid="${creator_phone}@s.whatsapp.net"
  if [ "${mode}" = "verify" ]; then
    group_jid="$(jq -sr '[.[]|select(.recordType=="GROUP_CREATE" and .outcome=="SUCCESS")][-1].groupJid // ""' "${ledger_file}")"
    [ -n "${group_jid}" ] || { group_probe_fail "verify 账本没有成功建群记录"; return 2; }
  elif jq -e 'select(.recordType=="GROUP_CREATE" and .outcome=="SUCCESS")' "${ledger_file}" >/dev/null 2>&1; then
    group_jid="$(jq -sr '[.[]|select(.recordType=="GROUP_CREATE" and .outcome=="SUCCESS")][-1].groupJid' "${ledger_file}")"
  else
    creator_phone="$(awk -F '\t' -v id="${creator_id}" '$2==id {print $5; exit}' "${accounts_file}")"
    : "${creator_phone}"
    create_body="${run_dir}/create.json"; create_response="${run_dir}/create-response.json"
    jq -nc --arg accountId "${account_id}" --arg subject "${subject}" \
      --argjson participants "${participants_json}" \
      '{accountId:$accountId,subject:$subject,participants:$participants,announceOnly:false}' >"${create_body}"
    group_probe_http POST "${GROUP_PROBE_WEB_BASE_URL}/v1/groups/create" "${create_body}" "${create_response}"
    create_http_status="${GROUP_PROBE_HTTP_STATUS}"
    create_curl_status="${GROUP_PROBE_CURL_STATUS}"
    group_jid="$(jq -r '.groupJid // ""' "${create_response}" 2>/dev/null || true)"
    if [ -z "${group_jid}" ]; then
      lookup_response="${run_dir}/groups.json"
      group_jid="$(group_probe_lookup_group "${account_id}" "${subject}" "${lookup_response}" || true)"
    fi
    if [ -z "${group_jid}" ]; then
      jq -nc --arg outcome UNKNOWN --argjson httpStatus "${create_http_status}" \
        --argjson transportStatus "${create_curl_status}" \
        --arg attemptedAt "$(group_probe_now)" \
        '{recordType:"GROUP_CREATE",outcome:$outcome,httpStatus:$httpStatus,transportStatus:$transportStatus,attemptedAt:$attemptedAt}' >>"${ledger_file}"
      group_probe_fail "建群结果未知且只读对账未找到唯一同名群，未重放"
      return 30
    fi
    jq -nc --arg outcome SUCCESS --arg groupJid "${group_jid}" \
      --argjson httpStatus "${create_http_status}" --arg attemptedAt "$(group_probe_now)" \
      '{recordType:"GROUP_CREATE",outcome:$outcome,groupJid:$groupJid,httpStatus:$httpStatus,attemptedAt:$attemptedAt}' \
      >>"${ledger_file}"
  fi
  if [ "${mode}" = "live" ]; then
    promote_body="${run_dir}/promote.json"; action_response="${run_dir}/action-response.json"
    jq -nc --arg accountId "${account_id}" --argjson participants "${admin_jids_json}" \
      '{accountId:$accountId,participants:$participants,timeoutMs:30000}' >"${promote_body}"
    group_probe_post_action PROMOTE_ADMINS \
      "${GROUP_PROBE_WEB_BASE_URL}/v1/groups/${group_jid}/participants/promote" \
      "${promote_body}" "${action_response}" "${ledger_file}" || true
    settings_body="${run_dir}/setting.json"
    jq -nc --arg accountId "${account_id}" '{accountId:$accountId,mode:"not_announcement"}' >"${settings_body}"
    group_probe_post_action ALLOW_MESSAGES \
      "${GROUP_PROBE_WEB_BASE_URL}/v1/groups/${group_jid}/settings/announcement" \
      "${settings_body}" "${action_response}" "${ledger_file}" || true
    jq -nc --arg accountId "${account_id}" '{accountId:$accountId,mode:"all_member_add"}' >"${settings_body}"
    group_probe_post_action ALLOW_MEMBER_ADD \
      "${GROUP_PROBE_WEB_BASE_URL}/v1/groups/${group_jid}/settings/member-add-mode" \
      "${settings_body}" "${action_response}" "${ledger_file}" || true
    jq -nc --arg accountId "${account_id}" '{accountId:$accountId,mode:"off"}' >"${settings_body}"
    group_probe_post_action DISABLE_JOIN_APPROVAL \
      "${GROUP_PROBE_WEB_BASE_URL}/v1/groups/${group_jid}/settings/join-approval" \
      "${settings_body}" "${action_response}" "${ledger_file}" || true
  fi
  invite_response="${run_dir}/invite.json"
  group_probe_http GET "${GROUP_PROBE_WEB_BASE_URL}/v1/groups/${group_jid}/invite-code?accountId=${account_id}" \
    /dev/null "${invite_response}"
  invite_ok=false
  if [ "${GROUP_PROBE_CURL_STATUS}" -eq 0 ] && [ "${GROUP_PROBE_HTTP_STATUS}" -ge 200 ] \
      && [ "${GROUP_PROBE_HTTP_STATUS}" -lt 300 ] \
      && [ -n "$(jq -r '.inviteCode // ""' "${invite_response}" 2>/dev/null || true)" ]; then
    invite_ok=true
  fi
  jq -nc --arg action VERIFY_INVITE_LINK_EXISTS --argjson success "${invite_ok}" \
    --arg checkedAt "$(group_probe_now)" \
    '{recordType:"GROUP_ACTION",action:$action,outcome:(if $success then "SUCCESS" else "FAILED" end),checkedAt:$checkedAt}' \
    >>"${ledger_file}"
  metadata_response="${run_dir}/metadata.json"
  sleep 3
  group_probe_http GET "${GROUP_PROBE_WEB_BASE_URL}/v1/groups/${group_jid}/metadata?accountId=${account_id}" \
    /dev/null "${metadata_response}"
  [ "${GROUP_PROBE_CURL_STATUS}" -eq 0 ] && [ "${GROUP_PROBE_HTTP_STATUS}" -ge 200 ] \
    && [ "${GROUP_PROBE_HTTP_STATUS}" -lt 300 ] \
    || { group_probe_fail "最终 metadata 读取失败"; return 31; }
  actual_size="$(jq -r '.size // 0' "${metadata_response}")"
  missing_count="$(jq -n --argjson expected "${participants_json}" --slurpfile meta "${metadata_response}" \
    '$expected - ([$meta[0].participants[] | .id, .phoneNumber] | map(select(.!=null)) | unique) | length')"
  expected_member_rows="$(jq -n --argjson expected "${participants_json}" --slurpfile meta "${metadata_response}" \
    '[ $meta[0].participants[] | select([.id,.phoneNumber,.lid] | map(select(.!=null) | . as $identity | select($expected|index($identity))) | length>0) ] | length')"
  creator_rows="$(jq -n --arg creator "${creator_jid}" --slurpfile meta "${metadata_response}" \
    '[ $meta[0].participants[] | select([.id,.phoneNumber,.lid] | map(select(.==$creator)) | length>0) ] | length')"
  unexpected_rows="$((actual_size - expected_member_rows - creator_rows))"
  unexpected_phones="${run_dir}/unexpected-phones.txt"
  jq -r --argjson expected "${participants_json}" --arg creator "${creator_jid}" \
    '.participants[] | select(([.id,.phoneNumber,.lid] | map(select(.!=null) | . as $identity | select($expected|index($identity) or $identity==$creator)) | length)==0) | (.phoneNumber // "") | split("@")[0]' \
    "${metadata_response}" >"${unexpected_phones}"
  unexpected_account_ids="$(while IFS= read -r phone; do
    [ -n "${phone}" ] || continue
    awk -F '\t' -v phone="${phone}" '$5==phone {print $2}' "${accounts_file}"
  done <"${unexpected_phones}" | sort -n -u | jq -Rsc 'split("\n")|map(select(length>0)|tonumber)')"
  unexpected_account_groups="$(while IFS= read -r phone; do
    [ -n "${phone}" ] || continue
    awk -F '\t' -v phone="${phone}" '$5==phone {print $2 "\t" $1}' "${accounts_file}"
  done <"${unexpected_phones}" | sort -n -u \
    | jq -Rsc 'split("\n")|map(select(length>0)|split("\t")|{accountId:(.[0]|tonumber),accountGroupId:(.[1]|tonumber)})')"
  unexpected_identity_kinds="$(jq -c --argjson expected "${participants_json}" --arg creator "${creator_jid}" \
    '[.participants[] | select(([.id,.phoneNumber,.lid] | map(select(.!=null) | . as $identity | select($expected|index($identity) or $identity==$creator)) | length)==0) | {idDomain:((.id // "")|split("@")[1] // "UNKNOWN"),phoneNumberPresent:(.phoneNumber!=null),lidPresent:(.lid!=null),admin:(.admin // "none")} ]' \
    "${metadata_response}")"
  admin_count="$(jq -n --argjson expected "${admin_jids_json}" --slurpfile meta "${metadata_response}" \
    '[ $meta[0].participants[] | select((.id as $id | $expected|index($id)) != null or (.phoneNumber as $phone | $expected|index($phone)) != null) | select(.admin=="admin" or .admin=="superadmin") ] | length')"
  subject_ok=false; announce_ok=false; member_add_ok=false; approval_ok=false
  [ "$(jq -r '.subject // ""' "${metadata_response}")" = "${subject}" ] && subject_ok=true
  [ "$(jq -r '.announce' "${metadata_response}")" = "false" ] && announce_ok=true
  [ "$(jq -r '.memberAddMode' "${metadata_response}")" = "true" ] && member_add_ok=true
  [ "$(jq -r '.joinApprovalMode' "${metadata_response}")" = "false" ] && approval_ok=true
  local final_status="SUCCESS"
  [ "${actual_size}" -eq "${expected_size}" ] && [ "${missing_count}" -eq 0 ] \
    && [ "${admin_count}" -eq 4 ] && [ "${subject_ok}" = true ] \
    && [ "${announce_ok}" = true ] && [ "${member_add_ok}" = true ] \
    && [ "${approval_ok}" = true ] && [ "${invite_ok}" = true ] || final_status="PARTIAL"
  jq -nc --arg finalStatus "${final_status}" --arg groupJid "${group_jid}" \
    --argjson actualSize "${actual_size}" --argjson expectedSize "${expected_size}" \
    --argjson missingParticipants "${missing_count}" --argjson confirmedAdmins "${admin_count}" \
    --argjson plannedMemberRows "${expected_member_rows}" --argjson creatorRows "${creator_rows}" \
    --argjson unexpectedRows "${unexpected_rows}" --argjson unexpectedAccountIds "${unexpected_account_ids}" \
    --argjson unexpectedAccountGroups "${unexpected_account_groups}" \
    --argjson unexpectedIdentityKinds "${unexpected_identity_kinds}" \
    --argjson subjectVerified "${subject_ok}" --argjson messagesAllowed "${announce_ok}" \
    --argjson memberAddAllowed "${member_add_ok}" --argjson joinApprovalOff "${approval_ok}" \
    --argjson inviteLinkExists "${invite_ok}" --arg verifiedAt "$(group_probe_now)" \
    '{recordType:"GROUP_FINAL",finalStatus:$finalStatus,groupJid:$groupJid,actualSize:$actualSize,expectedSize:$expectedSize,missingParticipants:$missingParticipants,plannedMemberRows:$plannedMemberRows,creatorRows:$creatorRows,unexpectedRows:$unexpectedRows,unexpectedAccountIds:$unexpectedAccountIds,unexpectedAccountGroups:$unexpectedAccountGroups,unexpectedIdentityKinds:$unexpectedIdentityKinds,confirmedAdmins:$confirmedAdmins,subjectVerified:$subjectVerified,messagesAllowed:$messagesAllowed,memberAddAllowed:$memberAddAllowed,joinApprovalOff:$joinApprovalOff,inviteLinkExists:$inviteLinkExists,historyPermission:"UNSUPPORTED",verifiedAt:$verifiedAt}' \
    >>"${ledger_file}"
  jq -c 'select(.recordType=="GROUP_FINAL")' "${ledger_file}" | tail -n 1
  [ "${final_status}" = "SUCCESS" ]
}

group_probe_usage() {
  cat <<'EOF'
用法:
  bash armada-deploy/tools/single-group-probe.sh --env test1 --mode dry-run \
    --subject "Armada Test Group 001" --operation-id probe-001 --ledger /tmp/armada-single-group-probe-probe-001.jsonl
  bash armada-deploy/tools/single-group-probe.sh --env test1 --mode live --yes \
    --subject "Armada Test Group 001" --operation-id probe-001 --ledger /tmp/armada-single-group-probe-probe-001.jsonl
  bash armada-deploy/tools/single-group-probe.sh --env test1 --mode verify \
    --subject "Armada Test Group 001" --operation-id probe-001 --ledger /tmp/armada-single-group-probe-probe-001.jsonl
EOF
}

group_probe_local_main() {
  local selected_env="" mode="" ledger_file="" subject="" operation_id="" confirmed="false"
  local script_dir deploy_dir repo_root workspace_root profile_file ssh_key remote_command escaped_arg
  local -a remote_args
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --env) selected_env="$2"; shift 2 ;;
      --mode) mode="$2"; shift 2 ;;
      --ledger) ledger_file="$2"; shift 2 ;;
      --subject) subject="$2"; shift 2 ;;
      --operation-id) operation_id="$2"; shift 2 ;;
      --yes) confirmed="true"; shift ;;
      -h|--help) group_probe_usage; return 0 ;;
      *) group_probe_fail "未知参数: $1"; return 2 ;;
    esac
  done
  [ "${selected_env}" = "test1" ] || { group_probe_fail "只允许 --env test1"; return 2; }
  case "${mode}" in dry-run|live|verify) ;; *) group_probe_fail "mode 必须是 dry-run/live/verify"; return 2 ;; esac
  group_probe_validate_ledger "${ledger_file}"
  [[ "${subject}" =~ ^[A-Za-z0-9][A-Za-z0-9._\ -]{0,99}$ ]] \
    || { group_probe_fail "群名必须是 1-100 位英文/数字/空格/安全分隔符"; return 2; }
  case "${operation_id}" in
    ''|*[!A-Za-z0-9._-]*) group_probe_fail "operation-id 只能包含英文、数字、点、下划线和连字符"; return 2 ;;
  esac
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  deploy_dir="$(cd "${script_dir}/.." && pwd)"
  repo_root="$(cd "${deploy_dir}/.." && pwd)"
  workspace_root="$(cd "${repo_root}/.." && pwd)"
  profile_file="${deploy_dir}/envs/test1.conf"
  . "${profile_file}"
  ssh_key="${workspace_root}/${PROFILE_ARMADA_KEY_REL}"
  remote_args=(--remote --mode "${mode}" --ledger "${ledger_file}" --subject "${subject}" --operation-id "${operation_id}")
  [ "${confirmed}" = true ] && remote_args+=(--yes)
  remote_command="bash -s --"
  for escaped_arg in "${remote_args[@]}"; do
    printf -v escaped_arg '%q' "${escaped_arg}"
    remote_command+=" ${escaped_arg}"
  done
  ssh -T -i "${ssh_key}" -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new \
    "${PROFILE_ARMADA_USER}@${PROFILE_ARMADA_HOST}" "${remote_command}" <"${BASH_SOURCE[0]}"
}

if [ "${1:-}" = "--remote" ]; then
  shift
  group_probe_remote_main "$@"
elif [[ "${BASH_SOURCE[0]:-}" == "$0" ]]; then
  group_probe_local_main "$@"
fi
