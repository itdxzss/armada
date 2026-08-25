web_observer_server_main() {
  local env_file="${1:-}"
  local python_binary="${2:-}"
  local dispatcher="${3:-}"
  local protocol_root="${4:-}"
  local observability_root="${5:-}"
  local capture_root="${6:-}"
  local original_command="${7:-}"
  local output status forced_error=''

  [ "$#" -eq 7 ] || return 40
  if [ ! -f "${env_file}" ] || [ -L "${env_file}" ]; then
    forced_error='WEB_ENV_UNAVAILABLE'
  fi

  if [ -z "${forced_error}" ]; then
    set +e
    output="$({
      set -a
      # The deployment already treats this root-owned application file as shell syntax.
      # shellcheck disable=SC1090
      . "${env_file}" >/dev/null 2>&1
      source_status=$?
      set +a
      [ "${source_status}" -eq 0 ] || exit 91
      REGISTRY_REDIS_URL="${REGISTRY_REDIS_URL:-${REDIS_URL:-}}"
      KEYS_REDIS_URL="${KEYS_REDIS_URL:-${REDIS_URL:-}}"
      RATELIMIT_REDIS_URL="${RATELIMIT_REDIS_URL:-${REDIS_URL:-}}"
      RUNTIME_REDIS_URL="${RUNTIME_REDIS_URL:-${REDIS_URL:-}}"
      export REGISTRY_REDIS_URL KEYS_REDIS_URL RATELIMIT_REDIS_URL RUNTIME_REDIS_URL
      unset KAFKAJS_MODULE IOREDIS_MODULE NODE_OPTIONS NODE_PATH PYTHONPATH PYTHONHOME
      HOME='/home/ec2-user'
      PM2_HOME='/home/ec2-user/.pm2'
      PATH='/usr/local/bin:/usr/bin:/bin:/home/ec2-user/.local/bin'
      export HOME PM2_HOME PATH
      exec "${python_binary}" "${dispatcher}" \
        --original-command "${original_command}" \
        --protocol-root "${protocol_root}" \
        --observability-root "${observability_root}" \
        --capture-root "${capture_root}"
    } 2>/dev/null)"
    status=$?
    set -e
    if [ -z "${output}" ] || [[ "${output}" == *$'\n'* ]]; then
      forced_error='WEB_OBSERVER_OUTPUT_INVALID'
    else
      printf '%s\n' "${output}"
      return "${status}"
    fi
  fi

  "${python_binary}" "${dispatcher}" \
    --original-command "${original_command}" \
    --protocol-root "${protocol_root}" \
    --observability-root "${observability_root}" \
    --capture-root "${capture_root}" \
    --forced-error "${forced_error}" 2>/dev/null
}
