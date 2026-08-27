readonly TEST1_BASE_URL_HTTP='http://armada.65.2.123.53.nip.io/'
readonly TEST1_BASE_URL_HTTPS='https://armada.65.2.123.53.nip.io/'
readonly TEST1_BASE_URL_LOOPBACK_HTTP='http://127.0.0.1/'

fail() {
  printf 'ui-smoke: %s\n' "$1" >&2
  exit 2
}

canonical_path() {
  realpath -- "$1" 2>/dev/null || return 1
}

require_canonical_directory() {
  local label="$1"
  local path="$2"
  local resolved

  [[ "${path}" == /* ]] || fail "${label} must be an absolute path"
  [ -d "${path}" ] || fail "${label} is unavailable"
  resolved="$(canonical_path "${path}")" || fail "${label} is unavailable"
  [ "${resolved}" = "${path}" ] || fail "${label} path must be canonical and contain no symlinks"
}

stat_mode() {
  local path="$1"
  if stat -c '%a' "${path}" >/dev/null 2>&1; then
    stat -c '%a' "${path}"
  else
    stat -f '%Lp' "${path}"
  fi
}

stat_uid() {
  local path="$1"
  if stat -c '%u' "${path}" >/dev/null 2>&1; then
    stat -c '%u' "${path}"
  else
    stat -f '%u' "${path}"
  fi
}

stat_gid() {
  local path="$1"
  if stat -c '%g' "${path}" >/dev/null 2>&1; then
    stat -c '%g' "${path}"
  else
    stat -f '%g' "${path}"
  fi
}

validate_credential_file() {
  local path="$1"
  local test_mode="$2"
  local resolved mode owner group

  [[ "${path}" == /* ]] || fail 'credential file must use an absolute path'
  [ -f "${path}" ] || fail 'credential file is unavailable'
  resolved="$(canonical_path "${path}")" || fail 'credential file is unavailable'
  [ "${resolved}" = "${path}" ] || fail 'credential file path must be canonical and contain no symlinks'
  [ ! -L "${path}" ] || fail 'credential file path must be canonical and contain no symlinks'

  mode="$(stat_mode "${path}")" || fail 'cannot inspect credential file permissions'
  owner="$(stat_uid "${path}")" || fail 'cannot inspect credential file owner'
  group="$(stat_gid "${path}")" || fail 'cannot inspect credential file group'
  if [ "${test_mode}" = '1' ]; then
    [ "${owner}" = "$(id -u)" ] || fail 'credential file owner is unsafe'
    if [ "${mode}" = '640' ]; then
      [ "${group}" = "$(id -g)" ] || fail 'credential file group is unsafe'
    elif [ "${mode}" != '600' ]; then
      fail 'credential file permissions are unsafe; expected 0600 or 0640 in test mode'
    fi
    return
  fi

  [ "${owner}" = '0' ] || fail 'credential file must be owned by root'
  [ "${group}" = "$(id -g)" ] || fail 'credential file group must match the Runner group'
  [ "${mode}" = '640' ] || fail 'credential file permissions are unsafe; expected 0640'
}

unquote_value() {
  local value="$1"
  local first last

  if [ "${#value}" -eq 0 ]; then
    return
  fi
  first="${value:0:1}"
  last="${value: -1}"
  if [ "${first}" = "'" ] || [ "${first}" = '"' ]; then
    [ "${#value}" -ge 2 ] && [ "${last}" = "${first}" ] \
      || fail 'credential file contains an unterminated quoted value'
    printf '%s' "${value:1:${#value}-2}"
    return
  fi
  if [ "${last}" = "'" ] || [ "${last}" = '"' ]; then
    fail 'credential file contains an unterminated quoted value'
  fi
  printf '%s' "${value}"
}

read_configuration() {
  local path="$1"
  local line key value line_number=0
  local seen_environment=0
  local seen_base_url=0
  local seen_username=0
  local seen_password=0

  unset ENVIRONMENT ARMADA_E2E_BASE_URL ARMADA_E2E_USERNAME ARMADA_E2E_PASSWORD 2>/dev/null || true
  ENVIRONMENT=''
  ARMADA_E2E_BASE_URL=''
  ARMADA_E2E_USERNAME=''
  ARMADA_E2E_PASSWORD=''

  while IFS= read -r line || [ -n "${line}" ]; do
    line_number=$((line_number + 1))
    line="${line%$'\r'}"
    [ -z "${line}" ] && continue
    [[ "${line}" == \#* ]] && continue
    [[ "${line}" == *=* ]] || fail "credential file has an invalid entry at line ${line_number}"
    key="${line%%=*}"
    value="$(unquote_value "${line#*=}")"
    case "${key}" in
      ENVIRONMENT)
        [ "${seen_environment}" -eq 0 ] || fail 'credential file contains duplicate ENVIRONMENT'
        ENVIRONMENT="${value}"
        seen_environment=1
        ;;
      ARMADA_E2E_BASE_URL)
        [ "${seen_base_url}" -eq 0 ] || fail 'credential file contains duplicate ARMADA_E2E_BASE_URL'
        ARMADA_E2E_BASE_URL="${value}"
        seen_base_url=1
        ;;
      ARMADA_E2E_USERNAME)
        [ "${seen_username}" -eq 0 ] || fail 'credential file contains duplicate ARMADA_E2E_USERNAME'
        ARMADA_E2E_USERNAME="${value}"
        seen_username=1
        ;;
      ARMADA_E2E_PASSWORD)
        [ "${seen_password}" -eq 0 ] || fail 'credential file contains duplicate ARMADA_E2E_PASSWORD'
        ARMADA_E2E_PASSWORD="${value}"
        seen_password=1
        ;;
      *)
        fail "credential file contains an unsupported key at line ${line_number}"
        ;;
    esac
  done <"${path}"

  [ "${seen_environment}" -eq 1 ] || fail 'credential file is missing ENVIRONMENT'
  [ "${seen_base_url}" -eq 1 ] || fail 'credential file is missing ARMADA_E2E_BASE_URL'
  [ "${seen_username}" -eq 1 ] || fail 'credential file is missing ARMADA_E2E_USERNAME'
  [ "${seen_password}" -eq 1 ] || fail 'credential file is missing ARMADA_E2E_PASSWORD'
  [[ "${ARMADA_E2E_USERNAME}" =~ [^[:space:]] ]] || fail 'ARMADA_E2E_USERNAME must not be empty'
  [[ "${ARMADA_E2E_PASSWORD}" =~ [^[:space:]] ]] || fail 'ARMADA_E2E_PASSWORD must not be empty'
}

validate_target() {
  local test_mode="$1"

  [ "${ENVIRONMENT}" = 'test1' ] || fail 'ENVIRONMENT must be test1'
  if [ "${ARMADA_E2E_BASE_URL}" = "${TEST1_BASE_URL_HTTP}" ] \
    || [ "${ARMADA_E2E_BASE_URL}" = "${TEST1_BASE_URL_HTTPS}" ] \
    || [ "${ARMADA_E2E_BASE_URL}" = "${TEST1_BASE_URL_LOOPBACK_HTTP}" ]; then
    return
  fi
  if [ "${test_mode}" = '1' ] \
    && [[ "${ARMADA_E2E_BASE_URL}" =~ ^https?://(127\.0\.0\.1|localhost)(:[0-9]{1,5})?/?$ ]]; then
    return
  fi
  fail 'base URL must be the fixed test1 URL using http or https'
}

validate_run_directory() {
  local run_root="$1"
  local run_directory="$2"
  local test_mode="$3"
  local relative

  require_canonical_directory 'run root' "${run_root}"
  require_canonical_directory 'run directory' "${run_directory}"
  [[ "${run_directory}" == "${run_root}/"* ]] || fail 'run directory must stay below the allowed run root'
  relative="${run_directory#"${run_root}/"}"
  [ -n "${relative}" ] && [[ "${relative}" != */* ]] \
    || fail 'run directory must be one direct child of the allowed run root'
  if [ "${test_mode}" != '1' ]; then
    [[ "${relative}" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$ ]] \
      || fail 'run directory name is not a Runner run id'
  fi
}

clear_inherited_environment() {
  local name
  while IFS= read -r name; do
    unset "${name}" 2>/dev/null || true
  done < <(compgen -e)
}

ui_smoke_run() {
  local profile="${1:-}"
  local workspace="${2:-}"
  local run_root="${3:-}"
  local credential_file="${4:-}"
  local pnpm_binary="${5:-}"
  local browser_cache="${6:-}"
  local run_directory="${7:-}"
  local test_command_log="${8:-}"
  local artifact_directory test_mode

  [ "$#" -eq 8 ] || fail 'internal wrapper configuration is incomplete'
  case "${profile}" in
    production) test_mode=0 ;;
    test) test_mode=1 ;;
    *) fail 'internal wrapper profile is invalid' ;;
  esac
  [ -n "${workspace}" ] && [ -n "${run_root}" ] && [ -n "${credential_file}" ] \
    && [ -n "${pnpm_binary}" ] && [ -n "${browser_cache}" ] && [ -n "${run_directory}" ] \
    || fail 'internal wrapper configuration is incomplete'

  set +x
  umask 077
  PATH='/usr/local/bin:/usr/bin:/bin'
  export PATH

  require_canonical_directory 'frontend workspace' "${workspace}"
  [ -f "${workspace}/package.json" ] && [ ! -L "${workspace}/package.json" ] \
    || fail 'frontend workspace is missing package.json'
  [ -f "${workspace}/pnpm-lock.yaml" ] && [ ! -L "${workspace}/pnpm-lock.yaml" ] \
    || fail 'frontend workspace is missing pnpm-lock.yaml'
  [ -f "${workspace}/e2e/smoke.spec.ts" ] && [ ! -L "${workspace}/e2e/smoke.spec.ts" ] \
    || fail 'frontend workspace is missing e2e/smoke.spec.ts'
  require_canonical_directory 'Playwright browser cache' "${browser_cache}"
  [ -x "${pnpm_binary}" ] || fail 'fixed pnpm executable is unavailable'
  validate_run_directory "${run_root}" "${run_directory}" "${test_mode}"
  validate_credential_file "${credential_file}" "${test_mode}"
  read_configuration "${credential_file}"
  validate_target "${test_mode}"

  artifact_directory="$(mktemp -d "${run_directory}/ui-smoke.XXXXXXXX")" \
    || fail 'cannot create the UI smoke artifact directory'
  chmod 0700 "${artifact_directory}"

  cd "${workspace}"
  clear_inherited_environment
  PATH='/usr/local/bin:/usr/bin:/bin'
  HOME='/var/lib/staging-accept'
  LANG='C.UTF-8'
  CI='1'
  PLAYWRIGHT_BROWSERS_PATH="${browser_cache}"
  PLAYWRIGHT_HTML_OUTPUT_DIR="${artifact_directory}/playwright-report"
  PLAYWRIGHT_HTML_OPEN='never'
  PLAYWRIGHT_HTML_NO_COPY_PROMPT='1'
  PLAYWRIGHT_LAST_RUN_OUTPUT_FILE="${artifact_directory}/test-results/.last-run.json"
  PLAYWRIGHT_NO_COPY_PROMPT='1'
  export \
    PATH HOME LANG CI \
    PLAYWRIGHT_BROWSERS_PATH PLAYWRIGHT_HTML_OUTPUT_DIR PLAYWRIGHT_HTML_OPEN \
    PLAYWRIGHT_HTML_NO_COPY_PROMPT PLAYWRIGHT_LAST_RUN_OUTPUT_FILE PLAYWRIGHT_NO_COPY_PROMPT \
    ENVIRONMENT ARMADA_E2E_BASE_URL ARMADA_E2E_USERNAME ARMADA_E2E_PASSWORD
  if [ "${test_mode}" = '1' ]; then
    UI_SMOKE_TEST_COMMAND_LOG="${test_command_log}"
    export UI_SMOKE_TEST_COMMAND_LOG
  fi

  printf 'ui-smoke: starting fixed test1 smoke; artifacts=%s\n' "${artifact_directory}"
  exec "${pnpm_binary}" exec playwright test e2e/smoke.spec.ts \
    --browser=chromium \
    --reporter=line,html \
    --output="${artifact_directory}/test-results"
}
