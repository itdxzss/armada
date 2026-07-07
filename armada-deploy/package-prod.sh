#!/usr/bin/env bash
if [ -z "${BASH_VERSION:-}" ]; then
  exec /usr/bin/env bash "$0" "$@"
fi
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/.." && pwd)"

FRONTEND_DIR="${ARMADA_FRONTEND_DIR:-${WORKSPACE_ROOT}/wheel-saas-pure-web}"
PROTOCOL_DIR="${ARMADA_PROTOCOL_DIR:-${WORKSPACE_ROOT}/armada-protocol}"
PROTOCOL_LAYER_DIR="${PROTOCOL_DIR}/protocol-layer"

VERSION="$(date +%Y%m%d%H%M%S)"
OUTPUT_DIR="${SCRIPT_DIR}/dist-prod"
PLATFORM="${ARMADA_PROD_PLATFORM:-linux/amd64}"
BUILD_APP=1
BUILD_PROTOCOL=1
SKIP_BUILD=0
DRY_RUN=0

JAR_NAME="armada-api-1.0.0-SNAPSHOT.jar"
API_DIR="${REPO_ROOT}/armada-api"
JAR_PATH="${API_DIR}/target/${JAR_NAME}"

info() {
  printf '> %s\n' "$*"
}

ok() {
  printf 'OK %s\n' "$*"
}

die() {
  printf 'ERR %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<EOF
package-prod.sh - build armada app + protocol production offline packages.

Usage:
  ./armada-deploy/package-prod.sh [options]

Options:
  --version <value>      Release version used in image tags and package names.
  --output-dir <dir>     Output directory. Default: ${OUTPUT_DIR}
  --platform <value>     Docker build platform. Default: ${PLATFORM}
  --skip-build           Skip Maven/pnpm/Docker builds; docker save existing tagged images.
  --app-only             Build only armada-app-prod-<version>.tar.gz.
  --protocol-only        Build only armada-protocol-prod-<version>.tar.gz.
  -n, --dry-run          Print plan only; do not build, docker save, or archive.
  -h, --help             Show this help.

Environment overrides:
  ARMADA_FRONTEND_DIR    Frontend repo directory. Default: ${FRONTEND_DIR}
  ARMADA_PROTOCOL_DIR    Protocol repo directory. Default: ${PROTOCOL_DIR}
  ARMADA_PROD_PLATFORM   Docker platform. Default: ${PLATFORM}
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      shift
      [ "$#" -gt 0 ] || die "--version requires a value"
      VERSION="$1"
      ;;
    --version=*) VERSION="${1#*=}" ;;
    --output-dir)
      shift
      [ "$#" -gt 0 ] || die "--output-dir requires a value"
      OUTPUT_DIR="$1"
      ;;
    --output-dir=*) OUTPUT_DIR="${1#*=}" ;;
    --platform)
      shift
      [ "$#" -gt 0 ] || die "--platform requires a value"
      PLATFORM="$1"
      ;;
    --platform=*) PLATFORM="${1#*=}" ;;
    --skip-build) SKIP_BUILD=1 ;;
    --app-only)
      BUILD_APP=1
      BUILD_PROTOCOL=0
      ;;
    --protocol-only)
      BUILD_APP=0
      BUILD_PROTOCOL=1
      ;;
    -n|--dry-run) DRY_RUN=1 ;;
    -h|--help)
      usage
      exit 0
      ;;
    *) usage; die "unknown option: $1" ;;
  esac
  shift
done

[[ "${VERSION}" =~ ^[A-Za-z0-9._-]+$ ]] || die "version may only contain letters, numbers, dot, underscore, and hyphen: ${VERSION}"
[ "${BUILD_APP}" = 1 ] || [ "${BUILD_PROTOCOL}" = 1 ] || die "nothing to build"

BACKEND_IMAGE="armada/backend:${VERSION}"
NGINX_IMAGE="armada/nginx:${VERSION}"
PROTOCOL_IMAGE="armada/protocol:${VERSION}"
APP_PACKAGE="${OUTPUT_DIR}/armada-app-prod-${VERSION}.tar.gz"
PROTOCOL_PACKAGE="${OUTPUT_DIR}/armada-protocol-prod-${VERSION}.tar.gz"

find_jdk17() {
  local candidate=""
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    candidate="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi
  if [ -z "${candidate}" ]; then
    candidate="${JAVA17_HOME:-${JAVA_HOME:-}}"
  fi
  if [ -n "${candidate}" ] && [ -x "${candidate}/bin/javac" ]; then
    printf '%s\n' "${candidate}"
    return 0
  fi
  return 1
}

print_plan() {
  echo
  info "production offline package plan"
  printf '  version        : %s\n' "${VERSION}"
  printf '  platform       : %s\n' "${PLATFORM}"
  printf '  output dir     : %s\n' "${OUTPUT_DIR}"
  printf '  armada repo    : %s\n' "${REPO_ROOT}"
  printf '  frontend repo  : %s\n' "${FRONTEND_DIR}"
  printf '  protocol repo  : %s\n' "${PROTOCOL_DIR}"
  if [ "${BUILD_APP}" = 1 ]; then
    printf '  app package    : %s\n' "${APP_PACKAGE}"
    printf '  app images     : %s, %s\n' "${BACKEND_IMAGE}" "${NGINX_IMAGE}"
  fi
  if [ "${BUILD_PROTOCOL}" = 1 ]; then
    printf '  protocol pkg   : %s\n' "${PROTOCOL_PACKAGE}"
    printf '  protocol image : %s\n' "${PROTOCOL_IMAGE}"
  fi
  if [ "${SKIP_BUILD}" = 1 ]; then
    printf '  build mode     : skip build; save existing tagged images\n'
  else
    printf '  build mode     : build source artifacts and Docker images locally\n'
  fi
  echo
}

require_common_files() {
  [ -f "${SCRIPT_DIR}/prod/scripts/install.sh" ] || die "missing prod install script"
  [ -f "${SCRIPT_DIR}/prod/scripts/rollback.sh" ] || die "missing prod rollback script"
  [ -f "${SCRIPT_DIR}/prod/scripts/status.sh" ] || die "missing prod status script"
  [ -f "${SCRIPT_DIR}/prod/scripts/logs.sh" ] || die "missing prod logs script"
  [ -f "${SCRIPT_DIR}/prod/README-prod.md" ] || die "missing prod README"
}

require_app_files() {
  [ -d "${API_DIR}" ] || die "missing armada-api directory: ${API_DIR}"
  [ -f "${API_DIR}/pom.xml" ] || die "missing armada-api/pom.xml"
  [ -d "${FRONTEND_DIR}" ] || die "missing frontend directory: ${FRONTEND_DIR}"
  [ -f "${FRONTEND_DIR}/package.json" ] || die "missing frontend package.json"
  [ -f "${SCRIPT_DIR}/backend.prebuilt.Dockerfile" ] || die "missing backend.prebuilt.Dockerfile"
  [ -f "${SCRIPT_DIR}/nginx.prebuilt.Dockerfile" ] || die "missing nginx.prebuilt.Dockerfile"
  [ -f "${SCRIPT_DIR}/nginx.conf" ] || die "missing nginx.conf"
  [ -f "${SCRIPT_DIR}/stale-chunk-reload.js" ] || die "missing stale-chunk-reload.js"
  [ -f "${SCRIPT_DIR}/prod/app/docker-compose.yml" ] || die "missing app prod compose template"
  [ -f "${SCRIPT_DIR}/prod/app/.env.example" ] || die "missing app .env.example"
}

require_protocol_files() {
  [ -d "${PROTOCOL_DIR}" ] || die "missing protocol directory: ${PROTOCOL_DIR}"
  [ -d "${PROTOCOL_LAYER_DIR}" ] || die "missing protocol-layer directory: ${PROTOCOL_LAYER_DIR}"
  [ -f "${PROTOCOL_LAYER_DIR}/deploy/Dockerfile" ] || die "missing protocol Dockerfile"
  [ -f "${PROTOCOL_LAYER_DIR}/package.json" ] || die "missing protocol package.json"
  [ -f "${PROTOCOL_LAYER_DIR}/package-lock.json" ] || die "missing protocol package-lock.json"
  [ -d "${PROTOCOL_LAYER_DIR}/src" ] || die "missing protocol src directory"
  [ -d "${PROTOCOL_DIR}/openapi" ] || die "missing protocol openapi directory"
  [ -f "${SCRIPT_DIR}/prod/protocol/docker-compose.yml" ] || die "missing protocol prod compose template"
  [ -f "${SCRIPT_DIR}/prod/protocol/.env.example" ] || die "missing protocol .env.example"
}

build_backend() {
  local jdk17_home
  jdk17_home="$(find_jdk17)" || die "JDK 17 is required. Install it or set JAVA17_HOME."
  command -v mvn >/dev/null 2>&1 || die "mvn is required to build backend"
  info "building backend jar"
  (cd "${API_DIR}" && JAVA_HOME="${jdk17_home}" mvn -q -DskipTests clean package)
  [ -f "${JAR_PATH}" ] || die "backend jar not found after build: ${JAR_PATH}"
}

build_frontend() {
  info "building frontend dist"
  if command -v pnpm >/dev/null 2>&1 && pnpm --version >/dev/null 2>&1; then
    (cd "${FRONTEND_DIR}" && pnpm install --frozen-lockfile && pnpm build)
  elif [ -d "${FRONTEND_DIR}/node_modules" ]; then
    (cd "${FRONTEND_DIR}" && npm run build)
  else
    die "pnpm is unavailable and frontend node_modules does not exist"
  fi
  [ -d "${FRONTEND_DIR}/dist" ] || die "frontend dist not found after build: ${FRONTEND_DIR}/dist"
}

prepare_app_context() {
  local context_dir="$1"
  rm -rf "${context_dir}"
  mkdir -p "${context_dir}/armada-api/target" "${context_dir}/wheel-saas-pure-web"
  cp "${SCRIPT_DIR}/backend.prebuilt.Dockerfile" "${context_dir}/"
  cp "${SCRIPT_DIR}/nginx.prebuilt.Dockerfile" "${context_dir}/"
  cp "${SCRIPT_DIR}/nginx.conf" "${context_dir}/"
  cp "${SCRIPT_DIR}/stale-chunk-reload.js" "${context_dir}/"
  cp "${JAR_PATH}" "${context_dir}/armada-api/target/${JAR_NAME}"
  cp -R "${FRONTEND_DIR}/dist" "${context_dir}/wheel-saas-pure-web/dist"
}

docker_build_app_images() {
  local context_dir="$1"
  command -v docker >/dev/null 2>&1 || die "docker is required"
  prepare_app_context "${context_dir}"
  info "building ${BACKEND_IMAGE}"
  docker build --platform "${PLATFORM}" -t "${BACKEND_IMAGE}" -f "${context_dir}/backend.prebuilt.Dockerfile" "${context_dir}"
  info "building ${NGINX_IMAGE}"
  docker build --platform "${PLATFORM}" -t "${NGINX_IMAGE}" -f "${context_dir}/nginx.prebuilt.Dockerfile" "${context_dir}"
}

docker_build_protocol_image() {
  command -v docker >/dev/null 2>&1 || die "docker is required"
  info "building ${PROTOCOL_IMAGE}"
  docker build --platform "${PLATFORM}" -t "${PROTOCOL_IMAGE}" -f "${PROTOCOL_LAYER_DIR}/deploy/Dockerfile" "${PROTOCOL_DIR}"
}

render_template() {
  local source="$1"
  local target="$2"
  sed "s/__VERSION__/${VERSION}/g" "${source}" > "${target}"
}

copy_common_release_files() {
  local release_dir="$1"
  mkdir -p "${release_dir}/scripts"
  cp "${SCRIPT_DIR}/prod/scripts/install.sh" "${release_dir}/scripts/install.sh"
  cp "${SCRIPT_DIR}/prod/scripts/rollback.sh" "${release_dir}/scripts/rollback.sh"
  cp "${SCRIPT_DIR}/prod/scripts/status.sh" "${release_dir}/scripts/status.sh"
  cp "${SCRIPT_DIR}/prod/scripts/logs.sh" "${release_dir}/scripts/logs.sh"
  cp "${SCRIPT_DIR}/prod/README-prod.md" "${release_dir}/README-prod.md"
  chmod +x "${release_dir}/scripts/"*.sh
}

write_release_env() {
  local release_dir="$1"
  local kind="$2"
  local project="$3"
  local healthcheck="$4"
  local required_keys="$5"
  cat > "${release_dir}/release.env" <<EOF
RELEASE_KIND=${kind}
RELEASE_VERSION=${VERSION}
COMPOSE_PROJECT=${project}
HEALTHCHECK_KIND=${healthcheck}
REQUIRED_ENV_KEYS="${required_keys}"
EOF
}

create_app_package() {
  local work_dir="$1"
  local release_dir="${work_dir}/armada-app-prod-${VERSION}"
  rm -rf "${release_dir}"
  mkdir -p "${release_dir}/images"
  render_template "${SCRIPT_DIR}/prod/app/docker-compose.yml" "${release_dir}/docker-compose.yml"
  cp "${SCRIPT_DIR}/prod/app/.env.example" "${release_dir}/.env.example"
  copy_common_release_files "${release_dir}"
  write_release_env "${release_dir}" "armada-app" "armada-prod" "app" "DB_URL DB_USER DB_PASSWORD DEV_LOGIN_PASSWORD KAFKA_BROKERS ARMADA_PROTOCOL_BASE_URL ARMADA_PROTOCOL_API_KEY"
  info "saving app images"
  docker save -o "${release_dir}/images/app-images.tar" "${BACKEND_IMAGE}" "${NGINX_IMAGE}"
  mkdir -p "${OUTPUT_DIR}"
  tar -czf "${APP_PACKAGE}" -C "${work_dir}" "$(basename "${release_dir}")"
  ok "created ${APP_PACKAGE}"
}

create_protocol_package() {
  local work_dir="$1"
  local release_dir="${work_dir}/armada-protocol-prod-${VERSION}"
  rm -rf "${release_dir}"
  mkdir -p "${release_dir}/images"
  render_template "${SCRIPT_DIR}/prod/protocol/docker-compose.yml" "${release_dir}/docker-compose.yml"
  cp "${SCRIPT_DIR}/prod/protocol/.env.example" "${release_dir}/.env.example"
  copy_common_release_files "${release_dir}"
  write_release_env "${release_dir}" "armada-protocol" "armada-protocol-prod" "protocol" "PROTOCOL_PUBLIC_HOST API_KEYS REDIS_URL MYSQL_CONNECTION_URI KAFKA_BROKERS"
  info "saving protocol image"
  docker save -o "${release_dir}/images/protocol-images.tar" "${PROTOCOL_IMAGE}"
  mkdir -p "${OUTPUT_DIR}"
  tar -czf "${PROTOCOL_PACKAGE}" -C "${work_dir}" "$(basename "${release_dir}")"
  ok "created ${PROTOCOL_PACKAGE}"
}

print_plan

if [ "${DRY_RUN}" = 1 ]; then
  info "[dry-run] no build, docker save, or archive commands executed"
  exit 0
fi

require_common_files
[ "${BUILD_APP}" = 0 ] || require_app_files
[ "${BUILD_PROTOCOL}" = 0 ] || require_protocol_files
command -v docker >/dev/null 2>&1 || die "docker is required"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/armada-prod-package.XXXXXX")"
APP_CONTEXT_DIR="${WORK_DIR}/app-docker-context"
cleanup() {
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

if [ "${SKIP_BUILD}" != 1 ]; then
  if [ "${BUILD_APP}" = 1 ]; then
    build_backend
    build_frontend
    docker_build_app_images "${APP_CONTEXT_DIR}"
  fi
  if [ "${BUILD_PROTOCOL}" = 1 ]; then
    docker_build_protocol_image
  fi
else
  info "skipping builds; expecting existing local images for version ${VERSION}"
fi

[ "${BUILD_APP}" = 0 ] || create_app_package "${WORK_DIR}"
[ "${BUILD_PROTOCOL}" = 0 ] || create_protocol_package "${WORK_DIR}"

ok "production offline packaging complete"
