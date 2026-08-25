#!/usr/bin/env bash
set -euo pipefail
set +x

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PRODUCTION_WORKSPACE='/var/lib/staging-accept/workspace/wheel-saas-pure-web'
readonly PRODUCTION_RUN_ROOT='/var/lib/staging-accept/runs'
readonly PRODUCTION_ENV_FILE='/etc/staging-accept/ui-smoke.env'
readonly PRODUCTION_PNPM='/usr/local/bin/pnpm'
readonly PRODUCTION_BROWSER_CACHE='/var/lib/staging-accept/.cache/ms-playwright'

for variable_name in \
  STAGING_ACCEPT_WRAPPER_TEST_MODE \
  STAGING_ACCEPT_WRAPPER_TEST_WORKSPACE \
  STAGING_ACCEPT_WRAPPER_TEST_RUN_ROOT \
  STAGING_ACCEPT_WRAPPER_TEST_ENV_FILE \
  STAGING_ACCEPT_WRAPPER_TEST_PNPM \
  STAGING_ACCEPT_WRAPPER_TEST_BROWSER_CACHE \
  UI_SMOKE_TEST_COMMAND_LOG; do
  if declare -p "${variable_name}" >/dev/null 2>&1; then
    printf 'ui-smoke: test-only variables are not accepted by the production entrypoint\n' >&2
    exit 2
  fi
done

[ "$#" -eq 0 ] || {
  printf 'ui-smoke: arguments are not accepted\n' >&2
  exit 2
}

# shellcheck source=ui-smoke.lib.sh
. "${SCRIPT_DIR}/ui-smoke.lib.sh"

ui_smoke_run \
  production \
  "${PRODUCTION_WORKSPACE}" \
  "${PRODUCTION_RUN_ROOT}" \
  "${PRODUCTION_ENV_FILE}" \
  "${PRODUCTION_PNPM}" \
  "${PRODUCTION_BROWSER_CACHE}" \
  "${STAGING_ACCEPT_RUN_DIR:-}" \
  ''
