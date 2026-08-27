#!/usr/bin/env bash
set -euo pipefail
set +x

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROTOCOL_ROOT='/home/ec2-user/armada-protocol/protocol-layer'
readonly WEB_ENV_FILE='/home/ec2-user/armada-protocol/protocol-layer/.env'
readonly CAPTURE_ROOT='/home/ec2-user/armada-protocol/traffic-capture'
readonly OBSERVABILITY_ROOT='/usr/local/libexec/staging-accept/observability'
readonly PYTHON_BINARY='/usr/bin/python3'

# shellcheck source=web-observer-server.lib.sh
. "${SCRIPT_DIR}/web-observer-server.lib.sh"

if [ "$#" -ne 0 ]; then
  exec "${PYTHON_BINARY}" "${SCRIPT_DIR}/web-observer-dispatch.py" \
    --original-command '' \
    --protocol-root "${PROTOCOL_ROOT}" \
    --observability-root "${OBSERVABILITY_ROOT}" \
    --capture-root "${CAPTURE_ROOT}" 2>/dev/null
fi

web_observer_server_main \
  "${WEB_ENV_FILE}" \
  "${PYTHON_BINARY}" \
  "${SCRIPT_DIR}/web-observer-dispatch.py" \
  "${PROTOCOL_ROOT}" \
  "${OBSERVABILITY_ROOT}" \
  "${CAPTURE_ROOT}" \
  "${SSH_ORIGINAL_COMMAND:-}"
