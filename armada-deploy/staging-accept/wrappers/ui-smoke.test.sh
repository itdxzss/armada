#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER="${SCRIPT_DIR}/ui-smoke.sh"
LIBRARY="${SCRIPT_DIR}/ui-smoke.lib.sh"

fail() {
  printf 'FAIL %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local file="$1"
  local expected="$2"
  grep -Fq -- "${expected}" "${file}" || fail "expected ${file} to contain: ${expected}"
}

assert_not_contains() {
  local file="$1"
  local unexpected="$2"
  if grep -Fq -- "${unexpected}" "${file}"; then
    fail "expected ${file} not to contain: ${unexpected}"
  fi
}

TEMP_ROOT="$(realpath "${TMPDIR:-/tmp}")"
FIXTURE_ROOT="$(mktemp -d "${TEMP_ROOT%/}/armada-ui-smoke.XXXXXX")"
WORKSPACE="${FIXTURE_ROOT}/workspace/wheel-saas-pure-web"
RUN_ROOT="${FIXTURE_ROOT}/runs"
RUN_DIR="${RUN_ROOT}/run-1"
BROWSER_CACHE="${FIXTURE_ROOT}/browser-cache"
SECRET_FILE="${FIXTURE_ROOT}/ui-smoke.env"
COMMAND_LOG="${FIXTURE_ROOT}/command.log"
OUTPUT_LOG="${FIXTURE_ROOT}/output.log"
PNPM_STUB="${FIXTURE_ROOT}/pnpm"
PNPM_FAIL_STUB="${FIXTURE_ROOT}/pnpm-fail"
USERNAME_SENTINEL='ui-smoke-user-sentinel'
PASSWORD_SENTINEL='ui-smoke-password-$()-sentinel'

cleanup() {
  rm -rf -- "${FIXTURE_ROOT}"
}
trap cleanup EXIT

mkdir -p \
  "${WORKSPACE}/e2e" \
  "${RUN_DIR}" \
  "${BROWSER_CACHE}"
: >"${WORKSPACE}/package.json"
: >"${WORKSPACE}/pnpm-lock.yaml"
: >"${WORKSPACE}/e2e/smoke.spec.ts"

cat >"${PNPM_STUB}" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf 'cwd=<%s>\n' "$PWD" >"${UI_SMOKE_TEST_COMMAND_LOG}"
printf 'browser-cache=<%s>\n' "${PLAYWRIGHT_BROWSERS_PATH:-}" >>"${UI_SMOKE_TEST_COMMAND_LOG}"
printf 'html-output=<%s>\n' "${PLAYWRIGHT_HTML_OUTPUT_DIR:-}" >>"${UI_SMOKE_TEST_COMMAND_LOG}"
for argument in "$@"; do
  printf 'arg=<%s>\n' "${argument}" >>"${UI_SMOKE_TEST_COMMAND_LOG}"
done
[ "${ARMADA_E2E_USERNAME:-}" = 'ui-smoke-user-sentinel' ] || exit 41
[ "${ARMADA_E2E_PASSWORD:-}" = 'ui-smoke-password-$()-sentinel' ] || exit 42
[ "${ARMADA_E2E_BASE_URL:-}" = 'http://armada.65.2.123.53.nip.io/' ] || exit 43
printf 'stub playwright passed\n'
STUB

cat >"${PNPM_FAIL_STUB}" <<'STUB'
#!/usr/bin/env bash
exit 7
STUB
chmod 0755 "${PNPM_STUB}" "${PNPM_FAIL_STUB}"

write_secret_file() {
  local environment="${1:-test1}"
  local base_url="${2:-http://armada.65.2.123.53.nip.io/}"
  local username="${3:-${USERNAME_SENTINEL}}"
  local password="${4:-${PASSWORD_SENTINEL}}"
  {
    printf 'ENVIRONMENT=%s\n' "${environment}"
    printf 'ARMADA_E2E_BASE_URL=%s\n' "${base_url}"
    printf 'ARMADA_E2E_USERNAME=%s\n' "${username}"
    printf 'ARMADA_E2E_PASSWORD=%s\n' "${password}"
  } >"${SECRET_FILE}"
  chmod 0600 "${SECRET_FILE}"
}

run_wrapper() {
  local secret_path="${1}"
  local run_directory="${2}"
  local pnpm_binary="${3:-${PNPM_STUB}}"
  (
    ENVIRONMENT='production'
    ARMADA_E2E_BASE_URL='https://production.example.com/'
    ARMADA_E2E_USERNAME='inherited-user-must-not-win'
    ARMADA_E2E_PASSWORD='inherited-password-must-not-win'
    export ENVIRONMENT ARMADA_E2E_BASE_URL ARMADA_E2E_USERNAME ARMADA_E2E_PASSWORD
    ui_smoke_run \
      test \
      "${WORKSPACE}" \
      "${RUN_ROOT}" \
      "${secret_path}" \
      "${pnpm_binary}" \
      "${BROWSER_CACHE}" \
      "${run_directory}" \
      "${COMMAND_LOG}"
  )
}

expect_failure() {
  local description="$1"
  shift
  if "$@" >"${OUTPUT_LOG}" 2>&1; then
    fail "expected failure: ${description}"
  fi
}

production_target_is_valid() (
  ENVIRONMENT='test1'
  ARMADA_E2E_BASE_URL="$1"
  validate_target 0
)

test_missing_secret_file_fails_closed() {
  expect_failure "missing secret file" \
    run_wrapper "${FIXTURE_ROOT}/missing.env" "${RUN_DIR}"
  assert_contains "${OUTPUT_LOG}" 'credential file is unavailable'
}

test_production_entrypoint_rejects_test_overrides() {
  env -i PATH='/usr/bin:/bin' STAGING_ACCEPT_WRAPPER_TEST_MODE=1 \
    "${WRAPPER}" >"${OUTPUT_LOG}" 2>&1 && fail 'production entrypoint accepted test mode'
  assert_contains "${OUTPUT_LOG}" 'test-only variables are not accepted'
}

test_unsafe_secret_permissions_fail_closed() {
  write_secret_file
  chmod 0644 "${SECRET_FILE}"
  expect_failure "world-readable secret file" \
    run_wrapper "${SECRET_FILE}" "${RUN_DIR}"
  assert_contains "${OUTPUT_LOG}" 'credential file permissions are unsafe'
}

test_secret_symlink_is_rejected() {
  local link="${FIXTURE_ROOT}/linked.env"
  write_secret_file
  ln -s "${SECRET_FILE}" "${link}"
  expect_failure "symlinked secret file" \
    run_wrapper "${link}" "${RUN_DIR}"
  assert_contains "${OUTPUT_LOG}" 'credential file path must be canonical'
}

test_non_test1_url_is_rejected() {
  write_secret_file test1 'https://production.example.com/'
  expect_failure "production-like host" \
    run_wrapper "${SECRET_FILE}" "${RUN_DIR}"
  assert_contains "${OUTPUT_LOG}" 'base URL must be the fixed test1 URL'

  write_secret_file production 'http://armada.65.2.123.53.nip.io/'
  expect_failure "non-test1 environment" \
    run_wrapper "${SECRET_FILE}" "${RUN_DIR}"
  assert_contains "${OUTPUT_LOG}" 'ENVIRONMENT must be test1'
}

test_production_loopback_target_is_exact() {
  production_target_is_valid 'http://127.0.0.1/'

  expect_failure "loopback URL without trailing slash" \
    production_target_is_valid 'http://127.0.0.1'
  assert_contains "${OUTPUT_LOG}" 'base URL must be the fixed test1 URL'

  expect_failure "loopback URL with explicit port" \
    production_target_is_valid 'http://127.0.0.1:80/'
  assert_contains "${OUTPUT_LOG}" 'base URL must be the fixed test1 URL'

  expect_failure "HTTPS loopback URL" \
    production_target_is_valid 'https://127.0.0.1/'
  assert_contains "${OUTPUT_LOG}" 'base URL must be the fixed test1 URL'

  expect_failure "localhost alias" \
    production_target_is_valid 'http://localhost/'
  assert_contains "${OUTPUT_LOG}" 'base URL must be the fixed test1 URL'
}

test_run_directory_traversal_and_symlink_escape_are_rejected() {
  local outside="${FIXTURE_ROOT}/outside"
  local link="${RUN_ROOT}/linked-run"
  mkdir -p "${outside}"
  write_secret_file

  expect_failure "run directory traversal" \
    run_wrapper "${SECRET_FILE}" "${RUN_DIR}/../run-1"
  assert_contains "${OUTPUT_LOG}" 'run directory path must be canonical'

  ln -s "${outside}" "${link}"
  expect_failure "run directory symlink escape" \
    run_wrapper "${SECRET_FILE}" "${link}"
  assert_contains "${OUTPUT_LOG}" 'run directory path must be canonical'
}

test_command_is_fixed_and_secrets_are_not_logged() {
  local artifact_directory argument_count output_directory
  write_secret_file
  run_wrapper "${SECRET_FILE}" "${RUN_DIR}" >"${OUTPUT_LOG}" 2>&1

  assert_not_contains "${OUTPUT_LOG}" "${USERNAME_SENTINEL}"
  assert_not_contains "${OUTPUT_LOG}" "${PASSWORD_SENTINEL}"
  assert_not_contains "${COMMAND_LOG}" "${USERNAME_SENTINEL}"
  assert_not_contains "${COMMAND_LOG}" "${PASSWORD_SENTINEL}"
  assert_contains "${COMMAND_LOG}" "cwd=<${WORKSPACE}>"
  assert_contains "${COMMAND_LOG}" "browser-cache=<${BROWSER_CACHE}>"
  assert_contains "${COMMAND_LOG}" 'arg=<exec>'
  assert_contains "${COMMAND_LOG}" 'arg=<playwright>'
  assert_contains "${COMMAND_LOG}" 'arg=<test>'
  assert_contains "${COMMAND_LOG}" 'arg=<e2e/smoke.spec.ts>'
  assert_contains "${COMMAND_LOG}" 'arg=<--browser=chromium>'
  assert_contains "${COMMAND_LOG}" 'arg=<--reporter=line,html>'
  argument_count="$(grep -c '^arg=<' "${COMMAND_LOG}")"
  [ "${argument_count}" -eq 7 ] || fail "fixed command received ${argument_count} arguments, want 7"

  artifact_directory="$(find "${RUN_DIR}" -mindepth 1 -maxdepth 1 -type d -name 'ui-smoke.*' -print)"
  [ -n "${artifact_directory}" ] || fail 'expected a ui-smoke artifact directory'
  output_directory="${artifact_directory}/test-results"
  assert_contains "${COMMAND_LOG}" "arg=<--output=${output_directory}>"
  assert_contains "${COMMAND_LOG}" "html-output=<${artifact_directory}/playwright-report>"
}

test_playwright_failure_exit_is_preserved() {
  local exit_code
  write_secret_file
  set +e
  run_wrapper "${SECRET_FILE}" "${RUN_DIR}" "${PNPM_FAIL_STUB}" >"${OUTPUT_LOG}" 2>&1
  exit_code=$?
  set -e
  [ "${exit_code}" -eq 7 ] || fail "wrapper exit = ${exit_code}, want 7"
}

[ -x "${WRAPPER}" ] || fail "wrapper is not executable: ${WRAPPER}"
[ -r "${LIBRARY}" ] || fail "wrapper library is unavailable: ${LIBRARY}"
# shellcheck source=ui-smoke.lib.sh
. "${LIBRARY}"

test_production_entrypoint_rejects_test_overrides
test_missing_secret_file_fails_closed
test_unsafe_secret_permissions_fail_closed
test_secret_symlink_is_rejected
test_non_test1_url_is_rejected
test_production_loopback_target_is_exact
test_run_directory_traversal_and_symlink_escape_are_rejected
test_command_is_fixed_and_secrets_are_not_logged
test_playwright_failure_exit_is_preserved

printf 'OK ui-smoke wrapper contracts passed\n'
