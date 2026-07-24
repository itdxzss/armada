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
: "${RELEASE_VERSION:?missing RELEASE_VERSION}"
: "${COMPOSE_PROJECT:?missing COMPOSE_PROJECT}"
: "${HEALTHCHECK_KIND:?missing HEALTHCHECK_KIND}"

INSTALL_ROOT="${ARMADA_INSTALL_ROOT:-/opt/${RELEASE_KIND}}"
TARGET_RELEASE="${INSTALL_ROOT}/releases/${RELEASE_VERSION}"
CURRENT_LINK="${INSTALL_ROOT}/current"

compose() {
  docker compose "$@"
}

env_value() {
  local key="$1"
  local default="${2:-}"
  local value
  value="$(grep -E "^${key}=" "${RELEASE_DIR}/.env" | tail -n 1 | cut -d= -f2- || true)"
  if [ -n "${value}" ]; then
    printf '%s\n' "${value}"
  else
    printf '%s\n' "${default}"
  fi
}

validate_env() {
  [ -f "${RELEASE_DIR}/.env" ] || die "missing .env. Run: cp .env.example .env, then edit it"
  chmod 600 "${RELEASE_DIR}/.env"
  local decoded_key_bytes key promotion_key value
  for key in ${REQUIRED_ENV_KEYS:-}; do
    value="$(env_value "${key}")"
    [ -n "${value}" ] || die ".env missing required value: ${key}"
    case "${value}" in
      *CHANGE_ME*|*PROTOCOL_PRIVATE_IP*|*REPLACE_ME*)
        die ".env still contains placeholder for ${key}: ${value}"
        ;;
      esac
  done

  if [ "${RELEASE_KIND}" = "armada-app" ]; then
    # 只对 App 包验证 AES-256 密钥；协议包复用本脚本但不承载推广 Token。
    promotion_key="$(env_value PROMOTION_TRACKING_ENCRYPTION_KEY)"
    if ! decoded_key_bytes="$(printf '%s' "${promotion_key}" | base64 --decode 2>/dev/null | wc -c | tr -d '[:space:]')"; then
      die "PROMOTION_TRACKING_ENCRYPTION_KEY must be valid Base64"
    fi
    [ "${decoded_key_bytes}" = 32 ] \
      || die "PROMOTION_TRACKING_ENCRYPTION_KEY must decode to exactly 32 bytes"
  fi
}

check_prerequisites() {
  command -v docker >/dev/null 2>&1 || die "docker is required"
  docker info >/dev/null 2>&1 || die "docker daemon is not reachable"
  docker compose version >/dev/null 2>&1 || die "docker compose v2 is required"
}

load_images() {
  shopt -s nullglob
  local images=("${RELEASE_DIR}/images/"*.tar)
  shopt -u nullglob
  [ "${#images[@]}" -gt 0 ] || die "missing offline Docker image tar files under images/"

  local image
  for image in "${images[@]}"; do
    info "loading image ${image}"
    docker load -i "${image}"
  done
}

install_release_files() {
  mkdir -p "${INSTALL_ROOT}/releases"
  if [ "${RELEASE_DIR}" != "${TARGET_RELEASE}" ]; then
    rm -rf "${TARGET_RELEASE}.tmp"
    mkdir -p "${TARGET_RELEASE}.tmp"
    cp -R "${RELEASE_DIR}/." "${TARGET_RELEASE}.tmp/"
    rm -rf "${TARGET_RELEASE}"
    mv "${TARGET_RELEASE}.tmp" "${TARGET_RELEASE}"
  fi
  chmod 600 "${TARGET_RELEASE}/.env"
  ln -sfn "${TARGET_RELEASE}" "${CURRENT_LINK}"
}

ensure_compose_files() {
  [ -f "${CURRENT_LINK}/docker-compose.yml" ] || die "missing docker-compose.yml in ${CURRENT_LINK}"
  [ -f "${CURRENT_LINK}/.env" ] || die "missing .env in ${CURRENT_LINK}"
}

start_services() {
  cd "${CURRENT_LINK}"
  mkdir -p logs
  compose --env-file .env -p "${COMPOSE_PROJECT}" -f docker-compose.yml up -d
}

assert_container_running() {
  local name="$1"
  local status
  status="$(docker inspect -f '{{.State.Status}}' "${name}" 2>/dev/null || true)"
  [ "${status}" = "running" ] || die "container is not running: ${name} (${status:-missing})"
}

healthcheck_app() {
  assert_container_running armada-backend
  assert_container_running armada-nginx
  local port
  port="$(env_value ARMADA_HTTP_PORT 18080)"
  if command -v curl >/dev/null 2>&1; then
    curl -fsS -m 8 "http://127.0.0.1:${port}/" >/dev/null || die "nginx local check failed on port ${port}"
  else
    info "curl not found; skipped local HTTP check"
  fi
}

healthcheck_protocol() {
  assert_container_running armada-protocol-master
  assert_container_running armada-protocol-worker-1
  assert_container_running armada-protocol-worker-2
  assert_container_running armada-protocol-worker-3
  assert_container_running armada-protocol-worker-4
  local port
  port="$(env_value PROTOCOL_MASTER_PORT 8080)"
  if command -v curl >/dev/null 2>&1; then
    curl -fsS -m 8 "http://127.0.0.1:${port}/healthz" >/dev/null || die "protocol master health check failed on port ${port}"
  else
    info "curl not found; skipped local HTTP check"
  fi
}

run_healthcheck() {
  case "${HEALTHCHECK_KIND}" in
    app) healthcheck_app ;;
    protocol) healthcheck_protocol ;;
    *) die "unknown HEALTHCHECK_KIND: ${HEALTHCHECK_KIND}" ;;
  esac
}

check_prerequisites
validate_env
load_images
install_release_files
ensure_compose_files
start_services
run_healthcheck
ok "${RELEASE_KIND} ${RELEASE_VERSION} installed at ${CURRENT_LINK}"
