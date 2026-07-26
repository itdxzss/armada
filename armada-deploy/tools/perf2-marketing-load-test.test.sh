#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER="${SCRIPT_DIR}/perf2-marketing-load-test.sh"

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
: >"${PERF2_WRAPPER_TEST_LOG}"
for argument in "$@"; do
  printf '<%s>\n' "${argument}" >>"${PERF2_WRAPPER_TEST_LOG}"
done
if [ "${PERF2_WRAPPER_TEST_EXIT:-0}" -ne 0 ]; then
  exit "${PERF2_WRAPPER_TEST_EXIT}"
fi
printf 'stub-help --execute\n'
STUB
chmod +x "${FIXTURE_BIN}/python3"

PERF2_WRAPPER_TEST_LOG="${FIXTURE_LOG}" PATH="${FIXTURE_BIN}:${PATH}" \
  "${WRAPPER}" --env perf2 --tenant demo >/dev/null
assert_contains "${FIXTURE_LOG}" '<-m>'
assert_contains "${FIXTURE_LOG}" '<perf2_loadtest.cli>'
assert_contains "${FIXTURE_LOG}" '<--env>'
assert_contains "${FIXTURE_LOG}" '<perf2>'
assert_contains "${FIXTURE_LOG}" '<--tenant>'
assert_contains "${FIXTURE_LOG}" '<demo>'
if grep -Fq -- '<--execute>' "${FIXTURE_LOG}"; then
  fail "wrapper synthesized --execute"
fi

HELP_OUTPUT="$(cd /private/tmp && PERF2_WRAPPER_TEST_LOG="${FIXTURE_LOG}" PATH="${FIXTURE_BIN}:${PATH}" \
  "${WRAPPER}" --help)"
case "${HELP_OUTPUT}" in
  *"stub-help --execute"*) ;;
  *) fail "help was not forwarded" ;;
esac

set +e
PERF2_WRAPPER_TEST_LOG="${FIXTURE_LOG}" PERF2_WRAPPER_TEST_EXIT=7 PATH="${FIXTURE_BIN}:${PATH}" \
  "${WRAPPER}" --env perf2 >/dev/null
EXIT_CODE=$?
set -e
[ "${EXIT_CODE}" -eq 7 ] || fail "wrapper exit = ${EXIT_CODE}, want 7"

if grep -Eq -- 'mysql|UPDATE|docker[[:space:]]+(restart|stop)|docker compose[[:space:]]+(up|down)|eval' "${WRAPPER}"; then
  fail "wrapper contains a forbidden state-changing command"
fi

printf 'OK perf2 wrapper contracts passed\n'
