#!/usr/bin/env bash

set -euo pipefail

readonly EXIT_USAGE=20
readonly EXIT_DEEP_CHECK=30
readonly EXIT_RUNTIME_EVIDENCE=40
readonly EXIT_REVISION_MISMATCH=41
readonly MAX_MANIFEST_BYTES=65536

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
DEFAULT_DEPLOY_SCRIPT="${SCRIPT_DIR}/../../deploy-test.sh"
MANIFEST_CHECKER="${SCRIPT_DIR}/preflight-manifest-check.py"

usage() {
  cat <<'EOF'
Usage:
  preflight.sh deep-check --env test1|perf2 [--deploy-script ABSOLUTE_PATH]
  preflight.sh versions --env test1|perf2 --manifest ABSOLUTE_PATH \
    --backend-sha FULL_SHA --frontend-sha FULL_SHA \
    --web-protocol-sha FULL_SHA --android-protocol-sha FULL_SHA \
    --max-age-seconds POSITIVE_INTEGER \
    --android-role coordinator --android-role node-01 [...]
  preflight.sh all <deep-check options> <versions options>

The JSON runtime manifest is produced by a trusted observer, not from local Git
HEAD values. See preflight-manifest.sample.json. Every artifact must contain an
observed full Git commit and a runtime/artifact identity. Missing or malformed
evidence is BLOCKED, never inferred.
EOF
}

usage_error() {
  printf 'preflight: %s\n' "$*" >&2
  return "${EXIT_USAGE}"
}

require_option_value() {
  local option="$1"
  local value="${2:-}"
  [ -n "${value}" ] && [ "${value#--}" = "${value}" ] \
    || usage_error "${option} requires a value"
}

validate_environment() {
  case "$1" in
    test1|perf2) ;;
    *) usage_error "environment must be test1 or perf2" ;;
  esac
}

validate_revision() {
  local label="$1"
  local revision="$2"
  [[ "${revision}" =~ ^[0-9a-fA-F]{40}$ ]] \
    || usage_error "${label} SHA must be a full 40-character Git commit"
}

validate_absolute_path() {
  local label="$1"
  local path="$2"
  case "${path}" in
    /*) ;;
    *) usage_error "${label} must be an absolute path" ;;
  esac
}

run_deep_check() {
  local output status
  if [ ! -f "${DEPLOY_SCRIPT}" ] || [ ! -x "${DEPLOY_SCRIPT}" ]; then
    printf 'CHECK deep-check BLOCKED reason=deploy-script-unavailable\n' >&2
    printf 'RESULT BLOCKED reason=deploy-script-unavailable\n' >&2
    return "${EXIT_RUNTIME_EVIDENCE}"
  fi

  printf 'CHECK deep-check START environment=%s\n' "${ENVIRONMENT}"
  if output="$("${DEPLOY_SCRIPT}" --env "${ENVIRONMENT}" --check 2>&1)"; then
    status=0
  else
    status=$?
  fi
  [ -z "${output}" ] || printf '%s\n' "${output}"
  if [ "${status}" -ne 0 ]; then
    if [ "${status}" -eq 255 ] || [ "${status}" -eq 124 ] \
      || printf '%s\n' "${output}" | grep -Eqi \
        '(^|[[:space:]])ssh:|connection timed out|could not resolve hostname|no route to host|permission denied \(publickey|找不到.*SSH 私钥|需要 ssh'; then
      printf 'CHECK deep-check BLOCKED exitCode=%s reason=observer-unreachable\n' "${status}" >&2
      printf 'RESULT BLOCKED reason=deep-check-observer-unreachable\n' >&2
      return "${EXIT_RUNTIME_EVIDENCE}"
    fi
    printf 'CHECK deep-check FAIL exitCode=%s\n' "${status}" >&2
    printf 'RESULT FAIL reason=deep-check\n' >&2
    return "${EXIT_DEEP_CHECK}"
  fi
  printf 'CHECK deep-check PASS\n'
}

validate_runtime_manifest() {
  local manifest_bytes status

  if [ ! -f "${MANIFEST}" ] || [ ! -r "${MANIFEST}" ]; then
    printf 'MANIFEST BLOCKED reason=unavailable path=%s\n' "${MANIFEST}" >&2
    return "${EXIT_RUNTIME_EVIDENCE}"
  fi
  manifest_bytes="$(wc -c <"${MANIFEST}" | tr -d '[:space:]')"
  if ! [[ "${manifest_bytes}" =~ ^[0-9]+$ ]] \
    || [ "${manifest_bytes}" -gt "${MAX_MANIFEST_BYTES}" ]; then
    printf 'MANIFEST BLOCKED reason=invalid-size\n' >&2
    return "${EXIT_RUNTIME_EVIDENCE}"
  fi
  if [ ! -f "${MANIFEST_CHECKER}" ] || ! command -v python3 >/dev/null 2>&1; then
    printf 'MANIFEST BLOCKED reason=checker-unavailable\n' >&2
    return "${EXIT_RUNTIME_EVIDENCE}"
  fi

  if python3 "${MANIFEST_CHECKER}" \
    --manifest "${MANIFEST}" \
    --environment "${ENVIRONMENT}" \
    --backend-sha "${BACKEND_SHA}" \
    --frontend-sha "${FRONTEND_SHA}" \
    --web-protocol-sha "${WEB_PROTOCOL_SHA}" \
    --android-protocol-sha "${ANDROID_PROTOCOL_SHA}" \
    --max-age-seconds "${MAX_AGE_SECONDS}" \
    "${ANDROID_ROLE_ARGS[@]}"; then
    status=0
  else
    status=$?
  fi

  case "${status}" in
    0|40|41) return "${status}" ;;
    *)
      printf 'MANIFEST BLOCKED reason=parser-error exitCode=%s\n' "${status}" >&2
      return "${EXIT_RUNTIME_EVIDENCE}"
      ;;
  esac
}

run_version_check() {
  local status
  if validate_runtime_manifest; then
    status=0
  else
    status=$?
  fi
  case "${status}" in
    0)
      printf 'RESULT PASS\n'
      return 0
      ;;
    40)
      printf 'RESULT BLOCKED reason=runtime-evidence\n' >&2
      return "${EXIT_RUNTIME_EVIDENCE}"
      ;;
    41)
      printf 'RESULT FAIL reason=revision-mismatch\n' >&2
      return "${EXIT_REVISION_MISMATCH}"
      ;;
    *)
      printf 'RESULT BLOCKED reason=runtime-evidence\n' >&2
      return "${EXIT_RUNTIME_EVIDENCE}"
      ;;
  esac
}

COMMAND="${1:-}"
case "${COMMAND}" in
  deep-check|versions|all) shift ;;
  help|--help|-h) usage; exit 0 ;;
  '') usage >&2; exit "${EXIT_USAGE}" ;;
  *) usage_error "unknown command: ${COMMAND}"; exit "$?" ;;
esac

ENVIRONMENT=""
DEPLOY_SCRIPT="${DEFAULT_DEPLOY_SCRIPT}"
MANIFEST=""
BACKEND_SHA=""
FRONTEND_SHA=""
WEB_PROTOCOL_SHA=""
ANDROID_PROTOCOL_SHA=""
ANDROID_ROLES=()
MAX_AGE_SECONDS=""

while [ "$#" -gt 0 ]; do
  option="$1"
  case "${option}" in
    --env|--deploy-script|--manifest|--backend-sha|--frontend-sha|--web-protocol-sha|--android-protocol-sha|--android-role|--max-age-seconds)
      require_option_value "${option}" "${2:-}" || exit "$?"
      value="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage_error "unknown option: ${option}" || exit "$?"
      ;;
  esac
  case "${option}" in
    --env) ENVIRONMENT="${value}" ;;
    --deploy-script) DEPLOY_SCRIPT="${value}" ;;
    --manifest) MANIFEST="${value}" ;;
    --backend-sha) BACKEND_SHA="${value}" ;;
    --frontend-sha) FRONTEND_SHA="${value}" ;;
    --web-protocol-sha) WEB_PROTOCOL_SHA="${value}" ;;
    --android-protocol-sha) ANDROID_PROTOCOL_SHA="${value}" ;;
    --android-role) ANDROID_ROLES+=("${value}") ;;
    --max-age-seconds) MAX_AGE_SECONDS="${value}" ;;
  esac
done

[ -n "${ENVIRONMENT}" ] || { usage_error "--env is required" || exit "$?"; }
validate_environment "${ENVIRONMENT}" || exit "$?"

if [ "${COMMAND}" = deep-check ] || [ "${COMMAND}" = all ]; then
  validate_absolute_path "deploy script" "${DEPLOY_SCRIPT}" || exit "$?"
fi
if [ "${COMMAND}" = versions ] || [ "${COMMAND}" = all ]; then
  [ -n "${MANIFEST}" ] || { usage_error "--manifest is required" || exit "$?"; }
  validate_absolute_path "manifest" "${MANIFEST}" || exit "$?"
  validate_revision backend "${BACKEND_SHA}" || exit "$?"
  validate_revision frontend "${FRONTEND_SHA}" || exit "$?"
  validate_revision web-protocol "${WEB_PROTOCOL_SHA}" || exit "$?"
  validate_revision android-protocol "${ANDROID_PROTOCOL_SHA}" || exit "$?"
  case "${MAX_AGE_SECONDS}" in
    ''|*[!0-9]*|0) usage_error "--max-age-seconds must be a positive integer" || exit "$?" ;;
  esac
  [ "${#ANDROID_ROLES[@]}" -gt 0 ] \
    || { usage_error "at least one --android-role is required" || exit "$?"; }
  seen_android_roles=" "
  ANDROID_ROLE_ARGS=()
  for role in "${ANDROID_ROLES[@]}"; do
    [[ "${role}" =~ ^[a-z][a-z0-9-]{0,63}$ ]] \
      || { usage_error "invalid Android role: ${role}" || exit "$?"; }
    case "${seen_android_roles}" in
      *" ${role} "*) usage_error "duplicate Android role: ${role}" || exit "$?" ;;
    esac
    seen_android_roles="${seen_android_roles}${role} "
    ANDROID_ROLE_ARGS+=(--android-role "${role}")
  done
fi

printf 'PREFLIGHT environment=%s mode=%s\n' "${ENVIRONMENT}" "${COMMAND}"
case "${COMMAND}" in
  deep-check)
    run_deep_check
    ;;
  versions)
    run_version_check
    ;;
  all)
    if run_deep_check; then
      run_version_check
    else
      exit "$?"
    fi
    ;;
esac
