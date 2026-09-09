#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER="${SCRIPT_DIR}/group-task-simulator.sh"

fail() {
  printf 'FAIL %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local file="$1"
  local expected="$2"
  grep -Fq -- "${expected}" "${file}" || fail "expected ${file} to contain: ${expected}"
}

[ -x "${WRAPPER}" ] || fail "wrapper is not executable: ${WRAPPER}"

FIXTURE_ROOT="$(mktemp -d)"
FIXTURE_BIN="${FIXTURE_ROOT}/bin"
FIXTURE_LOG="${FIXTURE_ROOT}/args.log"
mkdir -p "${FIXTURE_BIN}"
trap 'rm -rf -- "${FIXTURE_ROOT}"' EXIT

cat >"${FIXTURE_BIN}/python3" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
: >"${GROUP_SIM_WRAPPER_TEST_LOG}"
for argument in "$@"; do
  printf '<%s>\n' "${argument}" >>"${GROUP_SIM_WRAPPER_TEST_LOG}"
done
STUB
chmod +x "${FIXTURE_BIN}/python3"

GROUP_SIM_WRAPPER_TEST_LOG="${FIXTURE_LOG}" PATH="${FIXTURE_BIN}:${PATH}" \
  "${WRAPPER}" replay --scenario synthetic.json
assert_contains "${FIXTURE_LOG}" '<-m>'
assert_contains "${FIXTURE_LOG}" '<group_task_simulator.cli>'
assert_contains "${FIXTURE_LOG}" '<replay>'
assert_contains "${FIXTURE_LOG}" '<--scenario>'
assert_contains "${FIXTURE_LOG}" '<synthetic.json>'

if grep -Eq -- 'curl|wget|ssh|https?://' "${WRAPPER}"; then
  fail "wrapper contains a network-capable command"
fi

printf 'OK group task simulator wrapper contracts passed\n'
