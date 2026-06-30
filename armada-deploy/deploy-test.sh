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
JAR_NAME="armada-api-1.0.0-SNAPSHOT.jar"

SCOPE="all"
ASSUME_YES=0
DRY_RUN=0
TAIL_LOGS=0
DEPLOY_BRANCH="${ARMADA_DEPLOY_BRANCH:-}"
BUILD_REPO_ROOT="${REPO_ROOT}"
DEPLOY_ASSET_DIR="${SCRIPT_DIR}"
BRANCH_WORKTREE=""
API_DIR=""
JAR_PATH=""

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

refresh_build_paths() {
  API_DIR="${BUILD_REPO_ROOT}/armada-api"
  JAR_PATH="${API_DIR}/target/${JAR_NAME}"
  DEPLOY_ASSET_DIR="${BUILD_REPO_ROOT}/armada-deploy"
}

cleanup_branch_worktree() {
  if [ -n "${BRANCH_WORKTREE}" ]; then
    git -C "${REPO_ROOT}" worktree remove --force "${BRANCH_WORKTREE}" >/dev/null 2>&1 \
      || rmdir "${BRANCH_WORKTREE}" >/dev/null 2>&1 \
      || true
  fi
}

trap cleanup_branch_worktree EXIT

validate_branch_name() {
  [ -n "${DEPLOY_BRANCH}" ] || die "--branch 不能为空"
  git -C "${REPO_ROOT}" check-ref-format --branch "${DEPLOY_BRANCH}" >/dev/null 2>&1 \
    || die "非法分支名: ${DEPLOY_BRANCH}"
}

prepare_branch_worktree() {
  validate_branch_name
  command -v git >/dev/null 2>&1 || die "按分支部署需要 git"

  info "拉取部署分支 origin/${DEPLOY_BRANCH}..."
  git -C "${REPO_ROOT}" fetch --prune origin "+refs/heads/${DEPLOY_BRANCH}:refs/remotes/origin/${DEPLOY_BRANCH}"

  BRANCH_WORKTREE="$(mktemp -d "${TMPDIR:-/tmp}/armada-deploy-${DEPLOY_BRANCH//\//-}.XXXXXX")"
  rmdir "${BRANCH_WORKTREE}"
  info "创建临时分支 worktree..."
  git -C "${REPO_ROOT}" worktree add --detach "${BRANCH_WORKTREE}" "origin/${DEPLOY_BRANCH}" >/dev/null
  BUILD_REPO_ROOT="${BRANCH_WORKTREE}"
  refresh_build_paths
  ok "分支源码已就绪: origin/${DEPLOY_BRANCH}"
}

refresh_build_paths

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
  --branch <name>  从指定 armada 远端分支创建临时 worktree 构建并部署。
  -y, --yes        跳过确认提示。
  --logs           部署后跟随后端日志。
  -n, --dry-run    只打印计划,不构建、不同步、不重启。
  -h, --help       显示本帮助。

可覆盖的环境变量:
  ARMADA_DEPLOY_HOST
  ARMADA_DEPLOY_USER
  ARMADA_DEPLOY_KEY
  ARMADA_DEPLOY_REMOTE_DIR
  ARMADA_DEPLOY_BRANCH
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

  部署指定 armada 分支:
    ./armada-deploy/deploy-test.sh --all --branch main -y

常用参数:
  --all        本地构建后端 jar 和前端 dist,同步两者并重启两个容器。
  --be         本地构建后端 jar,同步 jar,只重建/重启 armada-backend。
  --fe         本地构建前端 dist,同步 dist,只重建/重启 armada-nginx。
  --branch     从 origin/<branch> 创建临时 worktree 构建 armada 与部署编排,不切换当前工作区。
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
    --branch)
      shift
      [ $# -gt 0 ] || die "--branch 需要分支名"
      DEPLOY_BRANCH="$1"
      ;;
    --branch=*) DEPLOY_BRANCH="${1#*=}" ;;
    -y|--yes) ASSUME_YES=1 ;;
    --logs) TAIL_LOGS=1 ;;
    -n|--dry-run) DRY_RUN=1 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; die "未知参数: $1" ;;
  esac
  shift
done

if [ -n "${DEPLOY_BRANCH}" ] && [ "${DRY_RUN}" != 1 ]; then
  prepare_branch_worktree
elif [ -n "${DEPLOY_BRANCH}" ]; then
  validate_branch_name
fi

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
[ -f "${DEPLOY_ASSET_DIR}/docker-compose.rds.yml" ] || die "缺少 ${DEPLOY_ASSET_DIR}/docker-compose.rds.yml"
[ -f "${DEPLOY_ASSET_DIR}/backend.prebuilt.Dockerfile" ] || die "缺少 ${DEPLOY_ASSET_DIR}/backend.prebuilt.Dockerfile"
[ -f "${DEPLOY_ASSET_DIR}/nginx.prebuilt.Dockerfile" ] || die "缺少 ${DEPLOY_ASSET_DIR}/nginx.prebuilt.Dockerfile"
[ -f "${DEPLOY_ASSET_DIR}/nginx.conf" ] || die "缺少 ${DEPLOY_ASSET_DIR}/nginx.conf"
[ -f "${DEPLOY_ASSET_DIR}/.env.example" ] || die "缺少 ${DEPLOY_ASSET_DIR}/.env.example"

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
  if [ -n "${DEPLOY_BRANCH}" ]; then
    printf '  armada 分支   : origin/%s%s\n' "${DEPLOY_BRANCH}" "$([ "${DRY_RUN}" = 1 ] && printf ' (dry-run 不拉取)')"
  else
    printf '  armada 来源   : 当前工作区\n'
  fi
  printf '  构建目录      : %s\n' "${BUILD_REPO_ROOT}"
  printf '  编排目录      : %s\n' "${DEPLOY_ASSET_DIR}"
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
  [ -n "${DEPLOY_BRANCH}" ] && info "[dry-run] 实际部署时将 fetch origin/${DEPLOY_BRANCH} 并创建临时 worktree"
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
  "${DEPLOY_ASSET_DIR}/backend.prebuilt.Dockerfile" \
  "${DEPLOY_ASSET_DIR}/nginx.prebuilt.Dockerfile" \
  "${DEPLOY_ASSET_DIR}/nginx.conf" \
  "${DEPLOY_ASSET_DIR}/docker-compose.rds.yml" \
  "${DEPLOY_ASSET_DIR}/.env.example" \
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
