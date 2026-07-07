#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

die() {
  printf 'ERR %s\n' "$*" >&2
  exit 1
}

[ -f "${RELEASE_DIR}/release.env" ] || die "missing release.env"
# shellcheck disable=SC1091
. "${RELEASE_DIR}/release.env"

: "${RELEASE_KIND:?missing RELEASE_KIND}"
: "${COMPOSE_PROJECT:?missing COMPOSE_PROJECT}"

INSTALL_ROOT="${ARMADA_INSTALL_ROOT:-/opt/${RELEASE_KIND}}"
CURRENT_LINK="${INSTALL_ROOT}/current"
if [ -d "${CURRENT_LINK}" ]; then
  cd "${CURRENT_LINK}"
else
  cd "${RELEASE_DIR}"
fi

docker compose --env-file .env -p "${COMPOSE_PROJECT}" -f docker-compose.yml ps
