#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

die() {
  printf 'ERR %s\n' "$*" >&2
  exit 1
}

info() {
  printf '> %s\n' "$*"
}

ok() {
  printf 'OK %s\n' "$*"
}

[ -f "${RELEASE_DIR}/release.env" ] || die "missing release.env"
# shellcheck disable=SC1091
. "${RELEASE_DIR}/release.env"

: "${RELEASE_KIND:?missing RELEASE_KIND}"
: "${COMPOSE_PROJECT:?missing COMPOSE_PROJECT}"

INSTALL_ROOT="${ARMADA_INSTALL_ROOT:-/opt/${RELEASE_KIND}}"
CURRENT_LINK="${INSTALL_ROOT}/current"

command -v docker >/dev/null 2>&1 || die "docker is required"
docker compose version >/dev/null 2>&1 || die "docker compose v2 is required"
[ -L "${CURRENT_LINK}" ] || die "missing current symlink: ${CURRENT_LINK}"

current_target="$(readlink "${CURRENT_LINK}")"
previous=""
for candidate in "${INSTALL_ROOT}"/releases/*; do
  [ -d "${candidate}" ] || continue
  [ "${candidate}" != "${current_target}" ] || continue
  previous="${candidate}"
done

[ -n "${previous}" ] || die "no previous release found under ${INSTALL_ROOT}/releases"
[ -f "${previous}/docker-compose.yml" ] || die "previous release missing docker-compose.yml: ${previous}"
[ -f "${previous}/.env" ] || die "previous release missing .env: ${previous}"

info "rolling back ${RELEASE_KIND} to ${previous}"
ln -sfn "${previous}" "${CURRENT_LINK}"
cd "${CURRENT_LINK}"
docker compose --env-file .env -p "${COMPOSE_PROJECT}" -f docker-compose.yml up -d
ok "${RELEASE_KIND} rolled back to ${previous}"
