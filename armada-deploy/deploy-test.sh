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
deploy-test.sh - 部署 armada API + wheel-saas-pure-web 到测试服。

用法:
  ./armada-deploy/deploy-test.sh [options]
  ./armada-deploy/deploy-test.sh          只显示部署指引,不执行部署。

参数:
  --all            构建并部署后端 + 前端。
  --be             只构建并部署后端。
  --fe             只构建并部署前端/nginx。
  -y, --yes        跳过确认提示。
  --logs           部署后跟随后端日志。
  -n, --dry-run    只打印计划,不构建、不同步、不重启。
  -h, --help       显示本帮助。

可覆盖的环境变量:
  ARMADA_DEPLOY_HOST
  ARMADA_DEPLOY_USER
  ARMADA_DEPLOY_KEY
  ARMADA_DEPLOY_REMOTE_DIR
  ARMADA_FRONTEND_DIR

目标服务器:
  ${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}

访问入口:
  http://65.2.123.53:18080/
EOF
}

guide() {
  cat <<EOF
Armada 测试环境部署指引

你要部署什么?
  后端 + 前端:
    ./armada-deploy/deploy-test.sh --all -y

  只部署后端:
    ./armada-deploy/deploy-test.sh --be -y

  只部署前端/nginx:
    ./armada-deploy/deploy-test.sh --fe -y

  只看部署计划,不真正执行:
    ./armada-deploy/deploy-test.sh --dry-run

常用参数:
  --all        本地构建后端 jar 和前端 dist,同步两者并重启两个容器。
  --be         本地构建后端 jar,同步 jar,只重建/重启 armada-backend。
  --fe         本地构建前端 dist,同步 dist,只重建/重启 armada-nginx。
  -y, --yes    跳过确认提示。
  --logs       部署完成后跟随 armada-backend 日志。
  --dry-run    只显示将要做什么,不构建、不 rsync、不 SSH 重启、不验活。
  -h, --help   显示完整参数说明。

目标:
  ${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}
  http://65.2.123.53:18080/

提示:
  如果不确定要发什么,先跑 --dry-run 看计划。
EOF
}

if [ "$#" -eq 0 ]; then
  guide
  exit 0
fi

while [ $# -gt 0 ]; do
  case "$1" in
    --all) SCOPE="all" ;;
    --be) SCOPE="be" ;;
    --fe) SCOPE="fe" ;;
    -y|--yes) ASSUME_YES=1 ;;
    --logs) TAIL_LOGS=1 ;;
    -n|--dry-run) DRY_RUN=1 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; die "未知参数: $1" ;;
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
    SCOPE_DESC="后端 + 前端"
    ;;
  be)
    BUILD_BE=1
    SERVICES="backend"
    SCOPE_DESC="只后端"
    ;;
  fe)
    BUILD_FE=1
    SERVICES="nginx"
    SCOPE_DESC="只前端"
    ;;
  *)
    die "无效部署范围: ${SCOPE}"
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
  JDK17_HOME="$(find_jdk17)" || die "需要 JDK 17。请安装 JDK 17 或设置 JAVA17_HOME。"
fi

[ -f "${SSH_KEY}" ] || die "找不到 SSH 私钥: ${SSH_KEY}"
[ -d "${API_DIR}" ] || die "找不到 armada-api 目录: ${API_DIR}"
[ -f "${API_DIR}/pom.xml" ] || die "找不到 armada-api/pom.xml"
[ -d "${FRONTEND_DIR}" ] || die "找不到前端目录: ${FRONTEND_DIR}"
[ -f "${FRONTEND_DIR}/package.json" ] || die "找不到前端 package.json"
[ -f "${SCRIPT_DIR}/docker-compose.rds.yml" ] || die "缺少 ${SCRIPT_DIR}/docker-compose.rds.yml"
[ -f "${SCRIPT_DIR}/backend.prebuilt.Dockerfile" ] || die "缺少 backend.prebuilt.Dockerfile"
[ -f "${SCRIPT_DIR}/nginx.prebuilt.Dockerfile" ] || die "缺少 nginx.prebuilt.Dockerfile"
[ -f "${SCRIPT_DIR}/nginx.conf" ] || die "缺少 nginx.conf"

if [ "${BUILD_BE}" = 1 ]; then
  command -v mvn >/dev/null 2>&1 || die "构建后端需要 mvn"
fi
if [ "${BUILD_FE}" = 1 ]; then
  if command -v pnpm >/dev/null 2>&1 && pnpm --version >/dev/null 2>&1; then
    PNPM_AVAILABLE=1
    FRONTEND_BUILD_MODE="pnpm install --frozen-lockfile && pnpm build"
  elif [ -d "${FRONTEND_DIR}/node_modules" ]; then
    FRONTEND_BUILD_MODE="npm run build (pnpm 不可用,使用现有 node_modules)"
  else
    die "pnpm 不可用,且 ${FRONTEND_DIR}/node_modules 不存在"
  fi
fi
command -v rsync >/dev/null 2>&1 || die "需要 rsync"
command -v ssh >/dev/null 2>&1 || die "需要 ssh"

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
test -f .env || { echo "远端缺少 .env: $1/.env" >&2; exit 20; }
for key in DB_URL DB_USER DB_PASSWORD; do
  grep -Eq "^${key}=.+" .env || { echo "$1/.env 缺少必需配置 ${key}" >&2; exit 21; }
done
'

print_plan() {
  echo
  info "部署计划"
  printf '  范围          : %s\n' "${SCOPE_DESC}"
  printf '  仓库目录      : %s\n' "${REPO_ROOT}"
  printf '  前端目录      : %s\n' "${FRONTEND_DIR}"
  printf '  目标服务器    : %s@%s:%s\n' "${SSH_USER}" "${SSH_HOST}" "${REMOTE_DIR}"
  printf '  compose       : %s / project=%s\n' "${COMPOSE_FILE}" "${COMPOSE_PROJECT}"
  if [ "${BUILD_BE}" = 1 ]; then
    printf '  后端 JDK      : %s\n' "${JDK17_HOME}"
  fi
  if [ "${BUILD_FE}" = 1 ]; then
    printf '  前端构建      : %s\n' "${FRONTEND_BUILD_MODE}"
  fi
  echo
}

print_plan

if [ "${DRY_RUN}" = 1 ]; then
  info "[dry-run] 将检查 SSH 连通性"
  [ "${BUILD_BE}" = 1 ] && info "[dry-run] 将执行: (cd ${API_DIR} && JAVA_HOME=${JDK17_HOME} mvn -q -DskipTests clean package)"
  if [ "${BUILD_FE}" = 1 ]; then
    if [ "${PNPM_AVAILABLE}" = 1 ]; then
      info "[dry-run] 将执行: (cd ${FRONTEND_DIR} && pnpm install --frozen-lockfile && pnpm build)"
    else
      info "[dry-run] 将执行: (cd ${FRONTEND_DIR} && npm run build)"
    fi
  fi
  info "[dry-run] 将 rsync 部署文件和产物到 ${REMOTE_DIR}"
  info "[dry-run] 将在远端执行 docker compose up -d --build ${SERVICES}"
  ok "dry-run 完成"
  exit 0
fi

info "检查 SSH 连通性..."
ssh_run true || die "SSH 连接失败"
ok "服务器可达"

if [ "${ASSUME_YES}" != 1 ]; then
  printf '确认部署到测试服? [y/N] '
  read -r answer </dev/tty || answer=""
  case "${answer}" in
    y|Y|yes|YES) ;;
    *) die "已取消" ;;
  esac
fi

if [ "${BUILD_BE}" = 1 ]; then
  info "构建后端 jar..."
  (cd "${API_DIR}" && JAVA_HOME="${JDK17_HOME}" mvn -q -DskipTests clean package)
  [ -f "${JAR_PATH}" ] || die "构建后未找到后端 jar: ${JAR_PATH}"
  ok "后端 jar 已就绪: ${JAR_PATH}"
fi

if [ "${BUILD_FE}" = 1 ]; then
  info "构建前端 dist..."
  if [ "${PNPM_AVAILABLE}" = 1 ]; then
    (cd "${FRONTEND_DIR}" && pnpm install --frozen-lockfile && pnpm build)
  else
    warn "pnpm 不可用,使用现有 node_modules 执行 npm run build"
    (cd "${FRONTEND_DIR}" && npm run build)
  fi
  [ -d "${FRONTEND_DIR}/dist" ] || die "构建后未找到前端 dist: ${FRONTEND_DIR}/dist"
  ok "前端 dist 已就绪: ${FRONTEND_DIR}/dist"
fi

info "准备远端目录..."
ssh_run "mkdir -p '${REMOTE_DIR}/armada-api/target' '${REMOTE_DIR}/wheel-saas-pure-web/dist'"

info "检查远端 .env..."
ssh_run "bash -s -- '${REMOTE_DIR}'" <<<"${remote_required_env_check}"
ok "远端 .env 已包含必需数据库配置"

info "同步部署编排文件..."
rsync -az -e "${RSYNC_SSH}" \
  "${SCRIPT_DIR}/backend.prebuilt.Dockerfile" \
  "${SCRIPT_DIR}/nginx.prebuilt.Dockerfile" \
  "${SCRIPT_DIR}/nginx.conf" \
  "${SCRIPT_DIR}/docker-compose.rds.yml" \
  "${SCRIPT_DIR}/.env.example" \
  "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/"

if [ "${BUILD_BE}" = 1 ]; then
  info "同步后端 jar..."
  rsync -a --partial -e "${RSYNC_SSH}" \
    "${JAR_PATH}" \
    "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/armada-api/target/${JAR_NAME}"
fi

if [ "${BUILD_FE}" = 1 ]; then
  info "同步前端 dist..."
  rsync -az --delete -e "${RSYNC_SSH}" \
    "${FRONTEND_DIR}/dist/" \
    "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/wheel-saas-pure-web/dist/"
fi

info "启动容器..."
ssh_run "cd '${REMOTE_DIR}' && docker compose --env-file .env -p '${COMPOSE_PROJECT}' -f '${COMPOSE_FILE}' up -d --build ${SERVICES}"

info "检查容器状态..."
if [ "${SCOPE}" = "all" ] || [ "${SCOPE}" = "be" ]; then
  ssh_run "docker inspect -f '{{.State.Status}}' armada-backend | grep -q '^running$'"
fi
if [ "${SCOPE}" = "all" ] || [ "${SCOPE}" = "fe" ]; then
  ssh_run "docker inspect -f '{{.State.Status}}' armada-nginx | grep -q '^running$'"
fi
ok "容器运行中"

if [ "${SCOPE}" = "all" ] || [ "${SCOPE}" = "fe" ]; then
  info "检查前端访问..."
  ssh_run "cd '${REMOTE_DIR}' && port=\$(awk -F= '/^ARMADA_HTTP_PORT=/{print \$2}' .env | tail -n 1); port=\${port:-18080}; curl -fsS -m 8 \"http://127.0.0.1:\${port}/\" | grep -qi '<!doctype html'"
  ok "前端可访问"
fi

if [ "${SCOPE}" = "all" ] || [ "${SCOPE}" = "be" ]; then
  info "检查 API 代理路径..."
  ssh_run "cd '${REMOTE_DIR}' && port=\$(awk -F= '/^ARMADA_HTTP_PORT=/{print \$2}' .env | tail -n 1); port=\${port:-18080}; body=\$(curl -fsS -m 8 \"http://127.0.0.1:\${port}/api/account-groups\" || true); printf '%s' \"\${body}\" | grep -Eq '\"code\"[[:space:]]*:[[:space:]]*(40101|0|40001)'"
  ok "API 路径已打到后端"
fi

ok "部署完成: http://65.2.123.53:18080/"

if [ "${TAIL_LOGS}" = 1 ]; then
  ssh_run "docker logs -f --tail 120 armada-backend"
fi
