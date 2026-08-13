#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/batch-group-create.sh"

fail() {
  printf 'FAIL %s\n' "$*" >&2
  exit 1
}

assert_equals() {
  [ "$1" = "$2" ] || fail "$3: expected=$1 actual=$2"
}

[ -f "${SCRIPT}" ] || fail "missing batch-group-create.sh"
batch_new_banned_creator_ids() {
  return 0
}
# shellcheck source=/dev/null
. "${SCRIPT}"

fixture="$(mktemp -d)"
trap 'rm -rf -- "${fixture}"' EXIT

BATCH_GROUP_INTERVAL_SECONDS=1
BATCH_GROUP_ANDROID_CONCURRENCY=2
BATCH_GROUP_WEB_CONCURRENCY=1
BATCH_GROUP_POLL_SECONDS=0.02
BATCH_GROUP_TRACE_FILE="${fixture}/trace.tsv"
BATCH_GROUP_LEDGER_FILE="${fixture}/ledger.jsonl"
: >"${BATCH_GROUP_TRACE_FILE}"
: >"${BATCH_GROUP_LEDGER_FILE}"

batch_execute_item() {
  local item_file="$1" result_file="$2"
  local item_id creator_id protocol subject
  IFS=$'\t' read -r item_id creator_id protocol subject <"${item_file}"
  printf 'START\t%s\t%s\t%s\t%s\n' "$(date +%s)" "${creator_id}" "${item_id}" "${protocol}" \
    >>"${BATCH_GROUP_TRACE_FILE}"
  sleep 0.20
  printf 'END\t%s\t%s\t%s\t%s\n' "$(date +%s)" "${creator_id}" "${item_id}" "${protocol}" \
    >>"${BATCH_GROUP_TRACE_FILE}"
  jq -nc --argjson itemId "${item_id}" --argjson creatorAccountId "${creator_id}" \
    '{recordType:"GROUP_ITEM_FINAL",itemId:$itemId,creatorAccountId:$creatorAccountId,finalStatus:"SUCCESS"}' \
    >"${result_file}"
}

tasks="${fixture}/tasks.tsv"
cat >"${tasks}" <<'TSV'
1	11	ANDROID	Armada Batch 001
2	11	ANDROID	Armada Batch 002
3	12	ANDROID	Armada Batch 003
4	12	ANDROID	Armada Batch 004
5	21	WEB	Armada Batch 005
6	22	WEB	Armada Batch 006
TSV

batch_run_scheduler "${tasks}" "${BATCH_GROUP_LEDGER_FILE}" "${fixture}/work"

awk -F '\t' '$1=="DISPATCH"{if($5>1)exit 1;if($6>2)exit 2}' "${BATCH_GROUP_TRACE_FILE}" \
  || fail "protocol concurrency cap was exceeded"

if awk -F '\t' '$1=="START"{active[$3]++; if(active[$3]>1) exit 1} $1=="END"{active[$3]--}' \
    "${BATCH_GROUP_TRACE_FILE}"; then :; else fail "same creator overlapped"; fi

first_android_end="$(awk -F '\t' '$1=="END" && $5=="ANDROID"{print NR;exit}' "${BATCH_GROUP_TRACE_FILE}")"
second_android_start="$(awk -F '\t' '$1=="START" && $5=="ANDROID"{count++;if(count==2){print NR;exit}}' "${BATCH_GROUP_TRACE_FILE}")"
[ "${second_android_start}" -lt "${first_android_end}" ] || fail "different Android creators did not run concurrently"

actor11_end="$(awk -F '\t' '$1=="END" && $3==11 && $4==1{print $2}' "${BATCH_GROUP_TRACE_FILE}")"
actor11_next="$(awk -F '\t' '$1=="START" && $3==11 && $4==2{print $2}' "${BATCH_GROUP_TRACE_FILE}")"
[ $((actor11_next - actor11_end)) -ge 1 ] || fail "creator cooldown was shorter than configured"

assert_equals 6 "$(jq -s '[.[]|select(.recordType=="GROUP_ITEM_FINAL")]|length' "${BATCH_GROUP_LEDGER_FILE}")" \
  "completed item count"

before="$(wc -l <"${BATCH_GROUP_TRACE_FILE}" | tr -d ' ')"
batch_run_scheduler "${tasks}" "${BATCH_GROUP_LEDGER_FILE}" "${fixture}/work-resume"
after="$(wc -l <"${BATCH_GROUP_TRACE_FILE}" | tr -d ' ')"
assert_equals "${before}" "${after}" "resume must skip terminal items"

unset cleanup_probe
(
  set -u
  scope_with_cleanup() {
    local cleanup_probe="${fixture}/cleanup-probe"
    trap "rm -f -- '${cleanup_probe}'" EXIT
    : >"${cleanup_probe}"
  }
  scope_with_cleanup
) || cleanup_status=$?
assert_equals 0 "${cleanup_status:-0}" "cleanup trap must survive local scope exit"

docker() {
  printf '%s\n' \
    'DB_URL=jdbc:mysql://db.internal:3306/armada?useSSL=false' \
    'DB_USER=test' \
    'DB_PASSWORD=test' \
    'ARMADA_PROTOCOL_BASE_URL=http://web.internal' \
    'ARMADA_PROTOCOL_API_KEY=test' \
    'PROTOCOL_ANDROID_BASE_URL=http://android.internal' \
    'PROTOCOL_ANDROID_API_KEY=test'
}
if batch_load_runtime; then
  runtime_status=0
else
  runtime_status=$?
fi
assert_equals 0 "${runtime_status}" "runtime loader must succeed when DB URL has an explicit port"
assert_equals 3306 "${BATCH_DB_PORT}" "explicit DB port"

plan_accounts="${fixture}/plan-accounts.tsv"
plan_ledger="${fixture}/plan-ledger.jsonl"
blocked_plan_ledger="${fixture}/blocked-plan-ledger.jsonl"
reduced_plan_ledger="${fixture}/reduced-plan-ledger.jsonl"
contact_evidence="${fixture}/contact-evidence.tsv"
admin_block_evidence="${fixture}/admin-block-evidence.tsv"
: >"${plan_accounts}"
: >"${plan_ledger}"
: >"${blocked_plan_ledger}"
: >"${reduced_plan_ledger}"
: >"${contact_evidence}"
id=1
while [ "${id}" -le 32 ]; do
  readiness=READY
  [ "${id}" -gt 7 ] || readiness=LOGIN_STATE_0
  printf '148\t%s\tANDROID\tcreator-%s\t910000%04d\t%s\n' "$((1000 + id))" "${id}" "${id}" "${readiness}" >>"${plan_accounts}"
  id=$((id + 1))
done
id=1
while [ "${id}" -le 4 ]; do
  printf '149\t%s\tANDROID\tadmin-%s\t920000%04d\tREADY\n' "$((2000 + id))" "${id}" "${id}" >>"${plan_accounts}"
  id=$((id + 1))
done
id=1
while [ "${id}" -le 59 ]; do
  printf '110\t%s\tANDROID\thelper-%s\t930000%04d\tREADY\n' "$((3000 + id))" "${id}" "${id}" >>"${plan_accounts}"
  id=$((id + 1))
done
fixture_creator=1008
while [ "${fixture_creator}" -le 1010 ]; do
  id=1
  while [ "${id}" -le 4 ]; do
    printf '%s\t%s\n%s\t%s\n' "${fixture_creator}" "$((2000 + id))" "$((2000 + id))" "${fixture_creator}" >>"${contact_evidence}"
    id=$((id + 1))
  done
  id=1
  while [ "${id}" -le 39 ]; do
    printf '%s\t%s\n%s\t%s\n' "${fixture_creator}" "$((3000 + id))" "$((3000 + id))" "${fixture_creator}" >>"${contact_evidence}"
    id=$((id + 1))
  done
  printf '%s\t3040\n' "${fixture_creator}" >>"${contact_evidence}"
  fixture_creator=$((fixture_creator + 1))
done
awk -F '\t' '$1==2001 || $2==2001 || ($1>=3001 && $1<=3059) || ($2>=3001 && $2<=3059)' \
  "${contact_evidence}" >"${admin_block_evidence}"
if batch_make_plans "${plan_accounts}" "${blocked_plan_ledger}" blocked-admin-gate-test \
    'Armada Blocked Admin Gate Test' "${admin_block_evidence}"; then
  fail "fewer than two mutual-contact admins must block plan creation"
fi
assert_equals 0 "$(jq -s '[.[]|select(.recordType=="GROUP_PLAN")]|length' "${blocked_plan_ledger}")" \
  "blocked contact gate plan count"

batch_make_plans "${plan_accounts}" "${reduced_plan_ledger}" reduced-helper-test \
  'Armada Reduced Helper Test' "${contact_evidence}"
assert_equals 15 "$(jq -s '[.[]|select(.recordType=="GROUP_PLAN")]|length' "${reduced_plan_ledger}")" \
  "five rounds of reduced-helper plans"
assert_equals 0 "$(jq -s '[.[]|select(.recordType=="GROUP_PLAN" and ((.helperAccountIds|length)!=39 or .expectedParticipants!=41 or .expectedGroupSize!=42))]|length' "${reduced_plan_ledger}")" \
  "one-way helper must be excluded without blocking"

fixture_creator=1008
while [ "${fixture_creator}" -le 1010 ]; do
  printf '3040\t%s\n' "${fixture_creator}" >>"${contact_evidence}"
  id=41
  while [ "${id}" -le 59 ]; do
    printf '%s\t%s\n%s\t%s\n' "${fixture_creator}" "$((3000 + id))" "$((3000 + id))" "${fixture_creator}" >>"${contact_evidence}"
    id=$((id + 1))
  done
  fixture_creator=$((fixture_creator + 1))
done
batch_make_plans "${plan_accounts}" "${plan_ledger}" single-round-test \
  'Armada Single Round Test' "${contact_evidence}"
assert_equals 15 "$(jq -s '[.[]|select(.recordType=="GROUP_PLAN")]|length' "${plan_ledger}")" \
  "five rounds for every eligible creator"
assert_equals 0 "$(jq -s '[.[]|select(.recordType=="GROUP_PLAN" and ((.adminAccountIds|length)!=2 or (.helperAccountIds|length)!=59 or .expectedParticipants!=61 or .expectedGroupSize!=62))]|length' "${plan_ledger}")" \
  "plan member shape"
assert_equals 5 "$(jq -s '[.[]|select(.recordType=="GROUP_PLAN")]|group_by(.creatorAccountId)|map(length)|max' "${plan_ledger}")" \
  "maximum groups per creator"
assert_equals 0 "$(jq -s '[.[]|select(.recordType=="GROUP_PLAN")]|group_by(.creatorAccountId)|map(select((map(.creatorSequence)|sort)!=[1,2,3,4,5]))|length' "${plan_ledger}")" \
  "five creator sequences"
assert_equals 3 "$(jq -s '[.[]|select(.recordType=="GROUP_PLAN")|.creatorAccountId]|unique|length' "${plan_ledger}")" \
  "distinct selected creators"
assert_equals 0 "$(jq -sr '[.[]|select(.recordType=="GROUP_PLAN")|.creatorAccountId] as $selected|[$selected[] as $id|select($id<1008 or $id>1010)]|length' "${plan_ledger}")" \
  "mutual-contact eligible creator selection"
batch_validate_all_plan_contact_gates "${plan_accounts}" "${plan_ledger}" "${contact_evidence}" dry-run
selected_helper="$(jq -sr '[.[]|select(.recordType=="GROUP_PLAN")][0].helperAccountIds[0]' "${plan_ledger}")"
awk -F '\t' -v id="${selected_helper}" 'BEGIN{OFS="\t"}{$6=($2==id?"LOGIN_STATE_0":$6);print}' \
  "${plan_accounts}" >"${plan_accounts}.offline"
batch_validate_all_plan_contact_gates "${plan_accounts}.offline" "${plan_ledger}" "${contact_evidence}" live
assert_equals 0 "$(jq -s '[.[]|select(.recordType=="CONTACT_GATE_CHECK" and .mode=="live" and (.status!="READY" or (.effectiveHelperAccountIds|length)!=58))]|length' "${plan_ledger}")" \
  "offline frozen helper must be excluded from every plan"
assert_equals 15 "$(jq -s --argjson id "${selected_helper}" '[.[]|select(.recordType=="CONTACT_GATE_CHECK" and .mode=="live")|.excludedHelpers[]|select(.accountId==$id)]|length' "${plan_ledger}")" \
  "offline helper exclusions must be recorded per plan"

stop_ledger="${fixture}/stop-ledger.jsonl"
stop_tasks="${fixture}/stop-tasks.tsv"
: >"${stop_ledger}"
cat >"${stop_tasks}" <<'TSV'
101	31	ANDROID	Stop Test 101
102	31	ANDROID	Stop Test 102
103	32	ANDROID	Stop Test 103
104	32	ANDROID	Stop Test 104
TSV
BATCH_GROUP_INTERVAL_SECONDS=0
BATCH_GROUP_LEDGER_FILE="${stop_ledger}"
batch_new_banned_creator_ids() {
  if jq -e 'select(.recordType=="GROUP_ITEM_FINAL")' "${stop_ledger}" >/dev/null 2>&1; then
    printf '31\n'
  fi
}
batch_run_scheduler "${stop_tasks}" "${stop_ledger}" "${fixture}/stop-work"
assert_equals 1 "$(jq -s '[.[]|select(.recordType=="GLOBAL_STOP")]|length' "${stop_ledger}")" \
  "creator ban must trigger one global stop"
assert_equals 2 "$(jq -s '[.[]|select(.recordType=="GROUP_ITEM_FINAL")]|length' "${stop_ledger}")" \
  "global stop must leave queued items undispatched"

printf 'PASS batch-group-create tests\n'
