#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/.." && pwd)"

SSH_HOST="${ARMADA_DEPLOY_HOST:-ec2-65-2-123-53.ap-south-1.compute.amazonaws.com}"
SSH_USER="${ARMADA_DEPLOY_USER:-ubuntu}"
SSH_KEY="${ARMADA_DEPLOY_KEY:-${WORKSPACE_ROOT}/dev-1.pem}"
REMOTE_DIR="${ARMADA_DEPLOY_REMOTE_DIR:-/home/app/armada-deploy}"
COMPOSE_FILE="${ARMADA_DEPLOY_COMPOSE:-docker-compose.rds.yml}"
COMPOSE_PROJECT="${ARMADA_DEPLOY_PROJECT:-armada-deploy}"
FRONTEND_DIR="${ARMADA_FRONTEND_DIR:-${WORKSPACE_ROOT}/wheel-saas-pure-web}"
API_DIR="${REPO_ROOT}/armada-api"
JAR_NAME="armada-api-1.0.0-SNAPSHOT.jar"
JAR_PATH="${API_DIR}/target/${JAR_NAME}"

SCOPE="all"
ASSUME_YES=0
DRY_RUN=0
TAIL_LOGS=0

if [ -t 1 ]; then
  C_B=$'\033[1m'
  C_G=$'\033[32m'
  C_Y=$'\033[33m'
  C_R=$'\033[31m'
  C_0=$'\033[0m'
else
  C_B=
  C_G=
  C_Y=
  C_R=
  C_0=
fi

info() { printf '%s\n' "${C_B}> $*${C_0}"; }
ok() { printf '%s\n' "${C_G}OK $*${C_0}"; }
warn() { printf '%s\n' "${C_Y}WARN $*${C_0}"; }
die() { printf '%s\n' "${C_R}ERR $*${C_0}" >&2; exit 1; }

usage() {
  cat <<EOF
deploy-test.sh - deploy armada API + wheel-saas-pure-web to the test server.

Usage:
  ./armada-deploy/deploy-test.sh [options]

Options:
  --be             Build and deploy backend only.
  --fe             Build and deploy frontend/nginx only.
  -y, --yes        Skip confirmation.
  --logs           Tail backend logs after deploy.
  -n, --dry-run    Print the plan without building, syncing, or restarting.
  -h, --help       Show this help.

Environment overrides:
  ARMADA_DEPLOY_HOST
  ARMADA_DEPLOY_USER
  ARMADA_DEPLOY_KEY
  ARMADA_DEPLOY_REMOTE_DIR
  ARMADA_FRONTEND_DIR

Target:
  ${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}

Entrypoint:
  http://65.2.123.53:18080/
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --be) SCOPE="be" ;;
    --fe) SCOPE="fe" ;;
    -y|--yes) ASSUME_YES=1 ;;
    --logs) TAIL_LOGS=1 ;;
    -n|--dry-run) DRY_RUN=1 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; die "unknown argument: $1" ;;
  esac
  shift
done

BUILD_BE=0
BUILD_FE=0
SERVICES=""
PNPM_AVAILABLE=0
FRONTEND_BUILD_MODE=""
case "${SCOPE}" in
  all)
    BUILD_BE=1
    BUILD_FE=1
    SERVICES=""
    SCOPE_DESC="backend + frontend"
    ;;
  be)
    BUILD_BE=1
    SERVICES="backend"
    SCOPE_DESC="backend only"
    ;;
  fe)
    BUILD_FE=1
    SERVICES="nginx"
    SCOPE_DESC="frontend only"
    ;;
  *)
    die "invalid scope: ${SCOPE}"
    ;;
esac

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

JDK17_HOME=""
if [ "${BUILD_BE}" = 1 ]; then
  JDK17_HOME="$(find_jdk17)" || die "JDK 17 is required. Install JDK 17 or set JAVA17_HOME."
fi

[ -f "${SSH_KEY}" ] || die "SSH key not found: ${SSH_KEY}"
[ -d "${API_DIR}" ] || die "armada-api directory not found: ${API_DIR}"
[ -f "${API_DIR}/pom.xml" ] || die "armada-api pom.xml not found"
[ -d "${FRONTEND_DIR}" ] || die "frontend directory not found: ${FRONTEND_DIR}"
[ -f "${FRONTEND_DIR}/package.json" ] || die "frontend package.json not found"
[ -f "${SCRIPT_DIR}/docker-compose.rds.yml" ] || die "missing ${SCRIPT_DIR}/docker-compose.rds.yml"
[ -f "${SCRIPT_DIR}/backend.prebuilt.Dockerfile" ] || die "missing backend.prebuilt.Dockerfile"
[ -f "${SCRIPT_DIR}/nginx.prebuilt.Dockerfile" ] || die "missing nginx.prebuilt.Dockerfile"
[ -f "${SCRIPT_DIR}/nginx.conf" ] || die "missing nginx.conf"

if [ "${BUILD_BE}" = 1 ]; then
  command -v mvn >/dev/null 2>&1 || die "mvn is required for backend build"
fi
if [ "${BUILD_FE}" = 1 ]; then
  if command -v pnpm >/dev/null 2>&1 && pnpm --version >/dev/null 2>&1; then
    PNPM_AVAILABLE=1
    FRONTEND_BUILD_MODE="pnpm install --frozen-lockfile && pnpm build"
  elif [ -d "${FRONTEND_DIR}/node_modules" ]; then
    FRONTEND_BUILD_MODE="npm run build (using existing node_modules; pnpm unavailable)"
  else
    die "pnpm is unavailable and ${FRONTEND_DIR}/node_modules is missing"
  fi
fi
command -v rsync >/dev/null 2>&1 || die "rsync is required"
command -v ssh >/dev/null 2>&1 || die "ssh is required"

SSH_OPTS=(
  -i "${SSH_KEY}"
  -o BatchMode=yes
  -o ConnectTimeout=15
  -o StrictHostKeyChecking=accept-new
)
RSYNC_SSH="ssh -i ${SSH_KEY} -o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new"

ssh_run() {
  ssh "${SSH_OPTS[@]}" "${SSH_USER}@${SSH_HOST}" "$@"
}

remote_required_env_check='
set -eu
cd "$1"
test -f .env || { echo "missing remote .env: $1/.env" >&2; exit 20; }
for key in DB_URL DB_USER DB_PASSWORD; do
  grep -Eq "^${key}=.+" .env || { echo "missing required ${key} in $1/.env" >&2; exit 21; }
done
'

print_plan() {
  echo
  info "Deploy plan"
  printf '  scope        : %s\n' "${SCOPE_DESC}"
  printf '  repo root    : %s\n' "${REPO_ROOT}"
  printf '  frontend dir : %s\n' "${FRONTEND_DIR}"
  printf '  target       : %s@%s:%s\n' "${SSH_USER}" "${SSH_HOST}" "${REMOTE_DIR}"
  printf '  compose      : %s / project=%s\n' "${COMPOSE_FILE}" "${COMPOSE_PROJECT}"
  if [ "${BUILD_BE}" = 1 ]; then
    printf '  backend JDK  : %s\n' "${JDK17_HOME}"
  fi
  if [ "${BUILD_FE}" = 1 ]; then
    printf '  frontend     : %s\n' "${FRONTEND_BUILD_MODE}"
  fi
  echo
}

print_plan

if [ "${DRY_RUN}" = 1 ]; then
  info "[dry-run] would test SSH connectivity"
  [ "${BUILD_BE}" = 1 ] && info "[dry-run] would run: (cd ${API_DIR} && JAVA_HOME=${JDK17_HOME} mvn -q -DskipTests clean package)"
  if [ "${BUILD_FE}" = 1 ]; then
    if [ "${PNPM_AVAILABLE}" = 1 ]; then
      info "[dry-run] would run: (cd ${FRONTEND_DIR} && pnpm install --frozen-lockfile && pnpm build)"
    else
      info "[dry-run] would run: (cd ${FRONTEND_DIR} && npm run build)"
    fi
  fi
  info "[dry-run] would rsync deploy files and artifacts to ${REMOTE_DIR}"
  info "[dry-run] would run remote docker compose up -d --build ${SERVICES}"
  ok "dry run complete"
  exit 0
fi

info "Checking SSH connectivity..."
ssh_run true || die "SSH connection failed"
ok "server reachable"

if [ "${ASSUME_YES}" != 1 ]; then
  printf 'Deploy to test server? [y/N] '
  read -r answer </dev/tty || answer=""
  case "${answer}" in
    y|Y|yes|YES) ;;
    *) die "cancelled" ;;
  esac
fi

if [ "${BUILD_BE}" = 1 ]; then
  info "Building backend jar..."
  (cd "${API_DIR}" && JAVA_HOME="${JDK17_HOME}" mvn -q -DskipTests clean package)
  [ -f "${JAR_PATH}" ] || die "backend jar not found after build: ${JAR_PATH}"
  ok "backend jar ready: ${JAR_PATH}"
fi

if [ "${BUILD_FE}" = 1 ]; then
  info "Building frontend dist..."
  if [ "${PNPM_AVAILABLE}" = 1 ]; then
    (cd "${FRONTEND_DIR}" && pnpm install --frozen-lockfile && pnpm build)
  else
    warn "pnpm is unavailable; using existing node_modules with npm run build"
    (cd "${FRONTEND_DIR}" && npm run build)
  fi
  [ -d "${FRONTEND_DIR}/dist" ] || die "frontend dist not found after build: ${FRONTEND_DIR}/dist"
  ok "frontend dist ready: ${FRONTEND_DIR}/dist"
fi

info "Preparing remote directory..."
ssh_run "mkdir -p '${REMOTE_DIR}/armada-api/target' '${REMOTE_DIR}/wheel-saas-pure-web/dist'"

info "Checking remote .env..."
ssh_run "bash -s -- '${REMOTE_DIR}'" <<<"${remote_required_env_check}"
ok "remote .env has required database keys"

info "Syncing deployment manifests..."
rsync -az -e "${RSYNC_SSH}" \
  "${SCRIPT_DIR}/backend.prebuilt.Dockerfile" \
  "${SCRIPT_DIR}/nginx.prebuilt.Dockerfile" \
  "${SCRIPT_DIR}/nginx.conf" \
  "${SCRIPT_DIR}/docker-compose.rds.yml" \
  "${SCRIPT_DIR}/.env.example" \
  "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/"

if [ "${BUILD_BE}" = 1 ]; then
  info "Syncing backend jar..."
  rsync -a --partial -e "${RSYNC_SSH}" \
    "${JAR_PATH}" \
    "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/armada-api/target/${JAR_NAME}"
fi

if [ "${BUILD_FE}" = 1 ]; then
  info "Syncing frontend dist..."
  rsync -az --delete -e "${RSYNC_SSH}" \
    "${FRONTEND_DIR}/dist/" \
    "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/wheel-saas-pure-web/dist/"
fi

info "Starting containers..."
ssh_run "cd '${REMOTE_DIR}' && docker compose --env-file .env -p '${COMPOSE_PROJECT}' -f '${COMPOSE_FILE}' up -d --build ${SERVICES}"

info "Verifying containers..."
if [ "${SCOPE}" = "all" ] || [ "${SCOPE}" = "be" ]; then
  ssh_run "docker inspect -f '{{.State.Status}}' armada-backend | grep -q '^running$'"
fi
if [ "${SCOPE}" = "all" ] || [ "${SCOPE}" = "fe" ]; then
  ssh_run "docker inspect -f '{{.State.Status}}' armada-nginx | grep -q '^running$'"
fi
ok "containers running"

if [ "${SCOPE}" = "all" ] || [ "${SCOPE}" = "fe" ]; then
  info "Verifying frontend..."
  ssh_run "cd '${REMOTE_DIR}' && port=\$(awk -F= '/^ARMADA_HTTP_PORT=/{print \$2}' .env | tail -n 1); port=\${port:-18080}; curl -fsS -m 8 \"http://127.0.0.1:\${port}/\" | grep -qi '<!doctype html'"
  ok "frontend responds"
fi

if [ "${SCOPE}" = "all" ] || [ "${SCOPE}" = "be" ]; then
  info "Verifying API proxy path..."
  ssh_run "cd '${REMOTE_DIR}' && port=\$(awk -F= '/^ARMADA_HTTP_PORT=/{print \$2}' .env | tail -n 1); port=\${port:-18080}; body=\$(curl -fsS -m 8 \"http://127.0.0.1:\${port}/api/account-groups\" || true); printf '%s' \"\${body}\" | grep -Eq '\"code\"[[:space:]]*:[[:space:]]*(40101|0|40001)'"
  ok "API path reaches backend"
fi

ok "deploy complete: http://65.2.123.53:18080/"

if [ "${TAIL_LOGS}" = 1 ]; then
  ssh_run "docker logs -f --tail 120 armada-backend"
fi
