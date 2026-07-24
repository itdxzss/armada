#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/.." && pwd)"

# Use the public IP by default so local proxy fake-ip DNS cannot break ssh/rsync.
SSH_HOST="${ARMADA_DEPLOY_HOST:-65.2.123.53}"
SSH_USER="${ARMADA_DEPLOY_USER:-ubuntu}"
SSH_KEY="${ARMADA_DEPLOY_KEY:-${WORKSPACE_ROOT}/dev-1.pem}"
REMOTE_DIR="${ARMADA_DEPLOY_REMOTE_DIR:-/home/app/armada-deploy}"
COMPOSE_FILE="${ARMADA_DEPLOY_COMPOSE:-docker-compose.rds.yml}"
COMPOSE_PROJECT="${ARMADA_DEPLOY_PROJECT:-armada-deploy}"
PUBLIC_URL="${ARMADA_DEPLOY_PUBLIC_URL:-http://armada.65.2.123.53.nip.io/}"
FRONTEND_DIR="${ARMADA_FRONTEND_DIR:-${WORKSPACE_ROOT}/wheel-saas-pure-web}"
PROTOCOL_DIR="${ARMADA_PROTOCOL_DIR:-${WORKSPACE_ROOT}/armada-protocol}"
PROTOCOL_LAYER_DIR="${PROTOCOL_DIR}/protocol-layer"
PROTOCOL_SSH_HOST="${ARMADA_PROTOCOL_DEPLOY_HOST:-65.2.122.109}"
PROTOCOL_SSH_USER="${ARMADA_PROTOCOL_DEPLOY_USER:-ec2-user}"
PROTOCOL_SSH_KEY="${ARMADA_PROTOCOL_DEPLOY_KEY:-${WORKSPACE_ROOT}/protocol.pem}"
PROTOCOL_REMOTE_DIR="${ARMADA_PROTOCOL_DEPLOY_REMOTE_DIR:-/home/ec2-user/armada-protocol}"
PROTOCOL_PM2_CONFIG="${ARMADA_PROTOCOL_PM2_CONFIG:-armada.ecosystem.config.cjs}"
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
BUILD_PROTOCOL=0

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

resolve_wsl_ssh_key() {
  local configured_key="$1"
  local secure_key="$2"
  local label="$3"

  if { [ -n "${WSL_DISTRO_NAME:-}" ] || [ -n "${WSL_INTEROP:-}" ]; } \
    && [[ "${configured_key}" == /mnt/* ]]; then
    [ -f "${secure_key}" ] \
      || die "${label} 私钥位于 Windows 盘且权限不安全。请先执行: install -m 600 '${configured_key}' '${secure_key}'"
    chmod 600 "${secure_key}" \
      || die "无法收紧 ${label} 私钥权限: ${secure_key}"
    printf '%s\n' "${secure_key}"
    return 0
  fi

  printf '%s\n' "${configured_key}"
}

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
deploy-test.sh - 部署 armada API + wheel-saas-pure-web + 协议层到测试服。

用法:
  ./armada-deploy/deploy-test.sh [options]
  ./armada-deploy/deploy-test.sh          只显示部署指引,不执行部署。

参数:
  --all            构建并部署后端 + 前端。保持旧语义,不部署协议层。
  --full           构建并部署后端 + 前端 + 协议层。
  --be             只构建并部署后端。
  --fe             只构建并部署前端/nginx。
  --protocol       只部署协议层。
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
  ARMADA_PROTOCOL_DIR
  ARMADA_PROTOCOL_DEPLOY_HOST
  ARMADA_PROTOCOL_DEPLOY_USER
  ARMADA_PROTOCOL_DEPLOY_KEY
  ARMADA_PROTOCOL_DEPLOY_REMOTE_DIR
  ARMADA_PROTOCOL_PM2_CONFIG

Armada 目标服务器:
  ${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}
协议目标服务器:
  ${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}

访问入口:
  ${PUBLIC_URL}
EOF
}

guide() {
  cat <<EOF
Armada 测试环境部署指引

你要部署什么?
  后端 + 前端:
    ./armada-deploy/deploy-test.sh --all -y

  后端 + 前端 + 协议层:
    ./armada-deploy/deploy-test.sh --full -y

  只部署后端:
    ./armada-deploy/deploy-test.sh --be -y

  只部署前端/nginx:
    ./armada-deploy/deploy-test.sh --fe -y

  只部署协议层:
    ./armada-deploy/deploy-test.sh --protocol -y

  只看部署计划,不真正执行:
    ./armada-deploy/deploy-test.sh --dry-run

  部署指定 armada 分支:
    ./armada-deploy/deploy-test.sh --all --branch main -y
    ./armada-deploy/deploy-test.sh --be --branch feature/group-import -y
    ARMADA_DEPLOY_BRANCH=main ./armada-deploy/deploy-test.sh --be -y

--branch 说明:
  1. 分支名写远端分支名,例如 main 或 feature/group-import,不要写 origin/main。
  2. 脚本会先 fetch origin/<branch>,再创建临时 worktree 构建 armada 后端和部署编排文件。
  3. 不会切换当前工作区分支,也不要求当前工作区干净。
  4. 前端仍然从 ARMADA_FRONTEND_DIR 指向的目录构建,不受 --branch 影响。
  5. 如果只想确认会部署哪个分支,先加 --dry-run:
     ./armada-deploy/deploy-test.sh --be --branch main --dry-run

常用参数:
  --all        本地构建后端 jar 和前端 dist,同步两者并重启两个容器。
  --full       部署后端 + 前端 + 协议层。
  --be         本地构建后端 jar,同步 jar,只重建/重启 armada-backend。
  --fe         本地构建前端 dist,同步 dist,只重建/重启 armada-nginx。
  --protocol   同步协议源码到协议机,远端 npm ci + npm run build,PM2 滚动重载。
  --branch     从 origin/<branch> 创建临时 worktree 构建 armada 与部署编排,不切换当前工作区。
  -y, --yes    跳过确认提示。
  --logs       部署完成后跟随 armada-backend 日志。
  --dry-run    只显示将要做什么,不构建、不 rsync、不 SSH 重启、不验活。
  -h, --help   显示完整参数说明。

目标:
  Armada: ${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}
  协议层: ${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}
  ${PUBLIC_URL}

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
    --full) SCOPE="full" ;;
    --be) SCOPE="be" ;;
    --fe) SCOPE="fe" ;;
    --protocol) SCOPE="protocol" ;;
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
BUILD_PROTOCOL=0
SERVICES=""
COMPOSE_UP_EXTRA=""
COMPOSE_UP_ARGS=""
COMPOSE_UP_COMMAND=""
PNPM_AVAILABLE=0
FRONTEND_BUILD_MODE=""
FRONTEND_BUILD_RUNTIME=""
WINDOWS_FRONTEND_DIR=""
case "${SCOPE}" in
  all)
    BUILD_BE=1
    BUILD_FE=1
    SERVICES=""
    SCOPE_DESC="后端 + 前端"
    ;;
  full)
    BUILD_BE=1
    BUILD_FE=1
    BUILD_PROTOCOL=1
    SERVICES=""
    SCOPE_DESC="后端 + 前端 + 协议层"
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
  protocol)
    BUILD_PROTOCOL=1
    SCOPE_DESC="只协议层"
    ;;
  *)
    die "无效部署范围: ${SCOPE}"
    ;;
esac

if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
  SSH_KEY="$(resolve_wsl_ssh_key "${SSH_KEY}" "${HOME}/.ssh/armada-dev-1.pem" "Armada")"
fi
if [ "${BUILD_PROTOCOL}" = 1 ]; then
  PROTOCOL_SSH_KEY="$(resolve_wsl_ssh_key "${PROTOCOL_SSH_KEY}" "${HOME}/.ssh/armada-protocol.pem" "协议层")"
fi

if [ "${SCOPE}" != "all" ] && [ "${SCOPE}" != "full" ]; then
  COMPOSE_UP_EXTRA="--no-deps"
fi
COMPOSE_UP_ARGS="up -d --build"
[ -n "${COMPOSE_UP_EXTRA}" ] && COMPOSE_UP_ARGS="${COMPOSE_UP_ARGS} ${COMPOSE_UP_EXTRA}"
[ -n "${SERVICES}" ] && COMPOSE_UP_ARGS="${COMPOSE_UP_ARGS} ${SERVICES}"
COMPOSE_UP_COMMAND="docker compose ${COMPOSE_UP_ARGS}"

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

if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
  [ -f "${SSH_KEY}" ] || die "找不到 SSH 私钥: ${SSH_KEY}"
  [ -f "${DEPLOY_ASSET_DIR}/docker-compose.rds.yml" ] || die "缺少 ${DEPLOY_ASSET_DIR}/docker-compose.rds.yml"
  [ -f "${DEPLOY_ASSET_DIR}/backend.prebuilt.Dockerfile" ] || die "缺少 ${DEPLOY_ASSET_DIR}/backend.prebuilt.Dockerfile"
  [ -f "${DEPLOY_ASSET_DIR}/nginx.prebuilt.Dockerfile" ] || die "缺少 ${DEPLOY_ASSET_DIR}/nginx.prebuilt.Dockerfile"
  [ -f "${DEPLOY_ASSET_DIR}/nginx.conf" ] || die "缺少 ${DEPLOY_ASSET_DIR}/nginx.conf"
  [ -f "${DEPLOY_ASSET_DIR}/stale-chunk-reload.js" ] || die "缺少 ${DEPLOY_ASSET_DIR}/stale-chunk-reload.js"
  [ -f "${DEPLOY_ASSET_DIR}/.env.example" ] || die "缺少 ${DEPLOY_ASSET_DIR}/.env.example"
fi
if [ "${BUILD_BE}" = 1 ]; then
  [ -d "${API_DIR}" ] || die "找不到 armada-api 目录: ${API_DIR}"
  [ -f "${API_DIR}/pom.xml" ] || die "找不到 armada-api/pom.xml"
fi
if [ "${BUILD_FE}" = 1 ]; then
  [ -d "${FRONTEND_DIR}" ] || die "找不到前端目录: ${FRONTEND_DIR}"
  [ -f "${FRONTEND_DIR}/package.json" ] || die "找不到前端 package.json"
fi
if [ "${BUILD_PROTOCOL}" = 1 ]; then
  [ -f "${PROTOCOL_SSH_KEY}" ] || die "找不到协议 SSH 私钥: ${PROTOCOL_SSH_KEY}"
  [ -d "${PROTOCOL_DIR}" ] || die "找不到协议仓库目录: ${PROTOCOL_DIR}"
  [ -d "${PROTOCOL_LAYER_DIR}" ] || die "找不到协议层目录: ${PROTOCOL_LAYER_DIR}"
  [ -f "${PROTOCOL_LAYER_DIR}/package.json" ] || die "找不到协议层 package.json"
  [ -f "${PROTOCOL_LAYER_DIR}/package-lock.json" ] || die "找不到协议层 package-lock.json"
  [ -f "${PROTOCOL_LAYER_DIR}/tsconfig.json" ] || die "找不到协议层 tsconfig.json"
  [ -d "${PROTOCOL_LAYER_DIR}/src" ] || die "找不到协议层 src 目录"
  [ -d "${PROTOCOL_LAYER_DIR}/deploy" ] || die "找不到协议层 deploy 目录"
  [ -d "${PROTOCOL_DIR}/openapi" ] || die "找不到协议 openapi 目录"
fi

if [ "${BUILD_BE}" = 1 ]; then
  command -v mvn >/dev/null 2>&1 || die "构建后端需要 mvn"
fi
if [ "${BUILD_FE}" = 1 ]; then
  # The repositories normally live on a Windows drive. When this script runs in
  # WSL, use the Windows package manager so it does not modify an NTFS
  # node_modules with Linux filesystem semantics (which can fail with EPERM/futime).
  if [ -n "${WSL_DISTRO_NAME:-}" ] \
    && command -v cmd.exe >/dev/null 2>&1 \
    && command -v wslpath >/dev/null 2>&1; then
    WINDOWS_FRONTEND_DIR="$(wslpath -w "${FRONTEND_DIR}")"
    if cmd.exe /d /c "where pnpm >NUL 2>NUL" >/dev/null 2>&1; then
      FRONTEND_BUILD_RUNTIME="windows-pnpm"
      FRONTEND_BUILD_MODE="Windows pnpm install --frozen-lockfile && pnpm build"
    elif [ -d "${FRONTEND_DIR}/node_modules" ] \
      && cmd.exe /d /c "where npm >NUL 2>NUL" >/dev/null 2>&1; then
      FRONTEND_BUILD_RUNTIME="windows-npm"
      FRONTEND_BUILD_MODE="Windows npm run build (pnpm 不可用,使用现有 node_modules)"
    fi
  fi

  if [ -n "${FRONTEND_BUILD_RUNTIME}" ]; then
    :
  elif command -v pnpm >/dev/null 2>&1 && pnpm --version >/dev/null 2>&1; then
    PNPM_AVAILABLE=1
    FRONTEND_BUILD_RUNTIME="pnpm"
    FRONTEND_BUILD_MODE="pnpm install --frozen-lockfile && pnpm build"
  elif [ -d "${FRONTEND_DIR}/node_modules" ]; then
    FRONTEND_BUILD_RUNTIME="npm"
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
PROTOCOL_SSH_OPTS=(
  -i "${PROTOCOL_SSH_KEY}"
  -o BatchMode=yes
  -o ConnectTimeout=15
  -o StrictHostKeyChecking=accept-new
)
PROTOCOL_RSYNC_SSH="ssh -i ${PROTOCOL_SSH_KEY} -o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new"

ssh_run() {
  ssh "${SSH_OPTS[@]}" "${SSH_USER}@${SSH_HOST}" "$@"
}

protocol_ssh_run() {
  ssh "${PROTOCOL_SSH_OPTS[@]}" "${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}" "$@"
}

remote_required_env_check="$(
cat <<'REMOTE_REQUIRED_ENV_CHECK'
set -euo pipefail
cd "$1"
test -f .env || { echo "远端缺少 .env: $1/.env" >&2; exit 20; }
chmod 600 .env
for key in DB_URL DB_USER DB_PASSWORD PROMOTION_TRACKING_ENCRYPTION_KEY PROMOTION_TRACKING_ENCRYPTION_KEY_ID; do
  grep -Eq "^${key}=.+" .env || { echo "$1/.env 缺少必需配置 ${key}" >&2; exit 21; }
done
promotion_key="$(grep -E '^PROMOTION_TRACKING_ENCRYPTION_KEY=' .env | tail -n 1 | cut -d= -f2- | tr -d '\r')"
promotion_key_id="$(grep -E '^PROMOTION_TRACKING_ENCRYPTION_KEY_ID=' .env | tail -n 1 | cut -d= -f2- | tr -d '\r')"
case "${promotion_key}:${promotion_key_id}" in
  *REPLACE*|*CHANGE_ME*)
    echo "$1/.env 的推广 Token 加密配置仍是占位值" >&2
    exit 22
    ;;
esac
if ! decoded_key_bytes="$(printf '%s' "${promotion_key}" | base64 --decode 2>/dev/null | wc -c | tr -d '[:space:]')"; then
  echo "$1/.env 的 PROMOTION_TRACKING_ENCRYPTION_KEY 不是合法 Base64" >&2
  exit 22
fi
[ "${decoded_key_bytes}" = 32 ] || {
  echo "$1/.env 的 PROMOTION_TRACKING_ENCRYPTION_KEY 解码后必须为 32 字节" >&2
  exit 22
}
REMOTE_REQUIRED_ENV_CHECK
)"

protocol_remote_deploy='
set -eu
remote_dir="$1"
preferred_pm2_config="$2"
cd "${remote_dir}/protocol-layer"
command -v npm >/dev/null 2>&1 || { echo "远端缺少 npm" >&2; exit 30; }
command -v pm2 >/dev/null 2>&1 || { echo "远端缺少 pm2" >&2; exit 31; }
npm ci --no-audit --no-fund
npm run build
if [ -f "${preferred_pm2_config}" ]; then
  pm2_config="${preferred_pm2_config}"
elif [ -f deploy/pm2.config.cjs ]; then
  pm2_config="deploy/pm2.config.cjs"
else
  echo "远端缺少 PM2 配置: ${preferred_pm2_config} 或 deploy/pm2.config.cjs" >&2
  exit 32
fi
pm2 startOrReload "${pm2_config}" --update-env
pm2 save >/dev/null 2>&1 || true
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
  if [ "${BUILD_FE}" = 1 ]; then
    printf '  前端目录      : %s\n' "${FRONTEND_DIR}"
  fi
  if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
    printf '  Armada 目标   : %s@%s:%s\n' "${SSH_USER}" "${SSH_HOST}" "${REMOTE_DIR}"
    printf '  compose       : %s / project=%s\n' "${COMPOSE_FILE}" "${COMPOSE_PROJECT}"
  fi
  if [ "${BUILD_PROTOCOL}" = 1 ]; then
    printf '  协议目录      : %s\n' "${PROTOCOL_DIR}"
    printf '  协议目标      : %s@%s:%s\n' "${PROTOCOL_SSH_USER}" "${PROTOCOL_SSH_HOST}" "${PROTOCOL_REMOTE_DIR}"
    printf '  协议 PM2      : %s\n' "${PROTOCOL_PM2_CONFIG}"
  fi
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
  if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
    info "[dry-run] 将检查 Armada SSH 连通性"
  fi
  if [ "${BUILD_PROTOCOL}" = 1 ]; then
    info "[dry-run] 将检查协议 SSH 连通性"
  fi
  [ -n "${DEPLOY_BRANCH}" ] && info "[dry-run] 实际部署时将 fetch origin/${DEPLOY_BRANCH} 并创建临时 worktree"
  [ "${BUILD_BE}" = 1 ] && info "[dry-run] 将执行: (cd ${API_DIR} && JAVA_HOME=${JDK17_HOME} mvn -q -DskipTests clean package)"
  if [ "${BUILD_FE}" = 1 ]; then
    if [ "${FRONTEND_BUILD_RUNTIME}" = "windows-pnpm" ]; then
      info "[dry-run] 将通过 Windows 执行: (cd ${WINDOWS_FRONTEND_DIR} && pnpm install --frozen-lockfile && pnpm build)"
    elif [ "${FRONTEND_BUILD_RUNTIME}" = "windows-npm" ]; then
      info "[dry-run] 将通过 Windows 执行: (cd ${WINDOWS_FRONTEND_DIR} && npm run build)"
    elif [ "${PNPM_AVAILABLE}" = 1 ]; then
      info "[dry-run] 将执行: (cd ${FRONTEND_DIR} && pnpm install --frozen-lockfile && pnpm build)"
    else
      info "[dry-run] 将执行: (cd ${FRONTEND_DIR} && npm run build)"
    fi
  fi
  if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
    info "[dry-run] 将 rsync Armada 部署文件和产物到 ${REMOTE_DIR}"
    info "[dry-run] 将在 Armada 远端执行 ${COMPOSE_UP_COMMAND}"
  fi
  if [ "${BUILD_PROTOCOL}" = 1 ]; then
    info "[dry-run] 将同步协议层源码到 ${PROTOCOL_REMOTE_DIR}"
    info "[dry-run] 将构建协议层: cd ${PROTOCOL_REMOTE_DIR}/protocol-layer && npm ci --no-audit --no-fund && npm run build"
    info "[dry-run] 将重载协议 PM2: pm2 startOrReload ${PROTOCOL_PM2_CONFIG} --update-env"
  fi
  ok "dry-run 完成"
  exit 0
fi

if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
  info "检查 Armada SSH 连通性..."
  ssh_run true || die "Armada SSH 连接失败"
  ok "Armada 服务器可达"
fi
if [ "${BUILD_PROTOCOL}" = 1 ]; then
  info "检查协议 SSH 连通性..."
  protocol_ssh_run true || die "协议 SSH 连接失败"
  ok "协议服务器可达"
fi

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
  if [ "${FRONTEND_BUILD_RUNTIME}" = "windows-pnpm" ]; then
    info "检测到 WSL + Windows 前端目录,使用 Windows pnpm 构建"
    (cd "${FRONTEND_DIR}" && cmd.exe /d /c "pnpm install --frozen-lockfile && pnpm build")
  elif [ "${FRONTEND_BUILD_RUNTIME}" = "windows-npm" ]; then
    warn "Windows pnpm 不可用,使用 Windows 现有 node_modules 执行 npm run build"
    (cd "${FRONTEND_DIR}" && cmd.exe /d /c "npm run build")
  elif [ "${PNPM_AVAILABLE}" = 1 ]; then
    (cd "${FRONTEND_DIR}" && pnpm install --frozen-lockfile && pnpm build)
  else
    warn "pnpm 不可用,使用现有 node_modules 执行 npm run build"
    (cd "${FRONTEND_DIR}" && npm run build)
  fi
  [ -d "${FRONTEND_DIR}/dist" ] || die "构建后未找到前端 dist: ${FRONTEND_DIR}/dist"
  ok "前端 dist 已就绪: ${FRONTEND_DIR}/dist"
fi

if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
  info "准备 Armada 远端目录..."
  ssh_run "mkdir -p '${REMOTE_DIR}/armada-api/target' '${REMOTE_DIR}/wheel-saas-pure-web/dist'"

  info "检查 Armada 远端 .env..."
  ssh_run "bash -s -- '${REMOTE_DIR}'" <<<"${remote_required_env_check}"
  ok "Armada 远端 .env 已包含必需数据库和推广 Token 加密配置"

  info "同步 Armada 部署编排文件..."
  rsync -az -e "${RSYNC_SSH}" \
    "${DEPLOY_ASSET_DIR}/backend.prebuilt.Dockerfile" \
    "${DEPLOY_ASSET_DIR}/nginx.prebuilt.Dockerfile" \
    "${DEPLOY_ASSET_DIR}/nginx.conf" \
    "${DEPLOY_ASSET_DIR}/stale-chunk-reload.js" \
    "${DEPLOY_ASSET_DIR}/docker-compose.rds.yml" \
    "${DEPLOY_ASSET_DIR}/.env.example" \
    "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/"
fi

if [ "${BUILD_BE}" = 1 ]; then
  info "同步后端 jar..."
  rsync -a --partial -e "${RSYNC_SSH}" \
    "${JAR_PATH}" \
    "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/armada-api/target/${JAR_NAME}"
fi

if [ "${BUILD_FE}" = 1 ]; then
  info "同步前端 dist..."
  # Keep old hashed chunks because browsers may still reference cached entry bundles.
  rsync -az -e "${RSYNC_SSH}" \
    "${FRONTEND_DIR}/dist/" \
    "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/wheel-saas-pure-web/dist/"
fi

if [ "${BUILD_PROTOCOL}" = 1 ]; then
  info "准备协议远端目录..."
  protocol_ssh_run "mkdir -p '${PROTOCOL_REMOTE_DIR}/protocol-layer' '${PROTOCOL_REMOTE_DIR}/openapi'"

  info "同步协议层源码..."
  rsync -az --delete -e "${PROTOCOL_RSYNC_SSH}" \
    "${PROTOCOL_LAYER_DIR}/src/" \
    "${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}/protocol-layer/src/"
  rsync -az --delete -e "${PROTOCOL_RSYNC_SSH}" \
    "${PROTOCOL_LAYER_DIR}/deploy/" \
    "${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}/protocol-layer/deploy/"
  rsync -az --delete -e "${PROTOCOL_RSYNC_SSH}" \
    "${PROTOCOL_DIR}/openapi/" \
    "${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}/openapi/"
  rsync -az -e "${PROTOCOL_RSYNC_SSH}" \
    "${PROTOCOL_LAYER_DIR}/package.json" \
    "${PROTOCOL_LAYER_DIR}/package-lock.json" \
    "${PROTOCOL_LAYER_DIR}/tsconfig.json" \
    "${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}/protocol-layer/"
  if [ -f "${PROTOCOL_LAYER_DIR}/jest.config.mjs" ]; then
    rsync -az -e "${PROTOCOL_RSYNC_SSH}" \
      "${PROTOCOL_LAYER_DIR}/jest.config.mjs" \
      "${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}/protocol-layer/"
  fi
  if [ -d "${PROTOCOL_LAYER_DIR}/patches" ]; then
    rsync -az --delete -e "${PROTOCOL_RSYNC_SSH}" \
      "${PROTOCOL_LAYER_DIR}/patches/" \
      "${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}/protocol-layer/patches/"
  fi

  info "构建并重载协议层..."
  protocol_ssh_run "bash -s -- '${PROTOCOL_REMOTE_DIR}' '${PROTOCOL_PM2_CONFIG}'" <<<"${protocol_remote_deploy}"
fi

if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
  info "启动 Armada 容器..."
  ssh_run "cd '${REMOTE_DIR}' && docker compose --env-file .env -p '${COMPOSE_PROJECT}' -f '${COMPOSE_FILE}' ${COMPOSE_UP_ARGS}"
fi

if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
  info "检查 Armada 容器状态..."
fi
if [ "${BUILD_BE}" = 1 ]; then
  ssh_run "docker inspect -f '{{.State.Status}}' armada-backend | grep -q '^running$'"
fi
if [ "${BUILD_FE}" = 1 ]; then
  ssh_run "docker inspect -f '{{.State.Status}}' armada-nginx | grep -q '^running$'"
fi
if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
  ok "Armada 容器运行中"
fi

if [ "${BUILD_FE}" = 1 ]; then
  info "检查前端访问..."
  ssh_run "cd '${REMOTE_DIR}' && port=\$(awk -F= '/^ARMADA_HTTP_PORT=/{print \$2}' .env | tail -n 1); port=\${port:-18080}; curl -fsS -m 8 \"http://127.0.0.1:\${port}/\" | grep -qi '<!doctype html'"
  ok "前端可访问"
fi

if [ "${BUILD_BE}" = 1 ]; then
  info "检查 API 代理路径..."
  ssh_run "cd '${REMOTE_DIR}' && port=\$(awk -F= '/^ARMADA_HTTP_PORT=/{print \$2}' .env | tail -n 1); port=\${port:-18080}; body=\$(curl -fsS -m 8 \"http://127.0.0.1:\${port}/api/account-groups\" || true); printf '%s' \"\${body}\" | grep -Eq '\"code\"[[:space:]]*:[[:space:]]*(40101|0|40001)'"
  ok "API 路径已打到后端"
fi

if [ "${BUILD_PROTOCOL}" = 1 ]; then
  info "检查协议层访问..."
  protocol_ssh_run "pm2 describe armada-protocol-master >/dev/null 2>&1 || pm2 describe protocol-master >/dev/null 2>&1"
  protocol_ssh_run "curl -fsS -m 8 http://127.0.0.1:8080/healthz >/dev/null"
  ok "协议层可访问"
fi

if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
  ok "Armada 部署完成: ${PUBLIC_URL}"
fi
if [ "${BUILD_PROTOCOL}" = 1 ]; then
  ok "协议层部署完成: ${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}"
fi

if [ "${TAIL_LOGS}" = 1 ] && [ "${BUILD_BE}" = 1 ]; then
  ssh_run "docker logs -f --tail 120 armada-backend"
elif [ "${TAIL_LOGS}" = 1 ] && [ "${BUILD_PROTOCOL}" = 1 ]; then
  protocol_ssh_run "pm2 logs --lines 120"
fi
