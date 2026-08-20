#!/usr/bin/env bash
if [ -z "${BASH_VERSION:-}" ]; then
  exec /usr/bin/env bash "$0" "$@"
fi
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/.." && pwd)"

profile_die() {
  printf 'ERR %s\n' "$*" >&2
  exit 1
}

SELECTED_ENV=test1
ENV_OPTION_SEEN=0
EXPECT_ENV_VALUE=0
for arg in "$@"; do
  if [ "${EXPECT_ENV_VALUE}" = 1 ]; then
    SELECTED_ENV="${arg}"
    EXPECT_ENV_VALUE=0
    continue
  fi
  case "${arg}" in
    --env)
      [ "${ENV_OPTION_SEEN}" = 0 ] || profile_die "--env 不能重复"
      ENV_OPTION_SEEN=1
      EXPECT_ENV_VALUE=1
      ;;
    --env=*)
      [ "${ENV_OPTION_SEEN}" = 0 ] || profile_die "--env 不能重复"
      ENV_OPTION_SEEN=1
      SELECTED_ENV="${arg#*=}"
      ;;
  esac
done
[ "${EXPECT_ENV_VALUE}" = 0 ] || profile_die "--env 需要环境名"
case "${SELECTED_ENV}" in
  test1|perf2) ;;
  *) profile_die "环境只允许 test1 或 perf2: ${SELECTED_ENV}" ;;
esac

PROFILE_FILE="${SCRIPT_DIR}/envs/${SELECTED_ENV}.conf"
[ -f "${PROFILE_FILE}" ] || profile_die "缺少环境档案: ${PROFILE_FILE}"
# shellcheck source=/dev/null
. "${PROFILE_FILE}"
[ "${ENV_ID:-}" = "${SELECTED_ENV}" ] || profile_die "环境档案 ID 不匹配: ${PROFILE_FILE}"

for required_profile_var in \
  PROFILE_APP_TITLE \
  PROFILE_ARMADA_HOST PROFILE_ARMADA_USER PROFILE_ARMADA_KEY_REL \
  PROFILE_ARMADA_REMOTE_DIR PROFILE_ARMADA_COMPOSE_FILE \
  PROFILE_ARMADA_COMPOSE_PROJECT PROFILE_ARMADA_PUBLIC_URL \
  PROFILE_PROTOCOL_HOST PROFILE_PROTOCOL_USER PROFILE_PROTOCOL_KEY_REL \
  PROFILE_PROTOCOL_REMOTE_DIR PROFILE_PROTOCOL_PM2_CONFIG \
  PROFILE_PROTOCOL_HEALTH_PORT PROFILE_PROTOCOL_TRANSPORT \
  PROFILE_PROTOCOL_TRAFFIC_ENABLED PROFILE_PROTOCOL_TRAFFIC_RETENTION_MAX_BYTES \
  PROFILE_PROTOCOL_TRAFFIC_RETENTION_MAX_AGE_MS PROFILE_PROTOCOL_TRAFFIC_DASHBOARD_PORT \
  PROFILE_ZHUAN_DEPLOY_MODE \
  EXPECTED_ARMADA_DB_SCHEMA EXPECTED_ANDROID_TOPIC_PREFIX \
  EXPECTED_NORMAL_GROUP_WEB_COMMAND_TOPIC \
  EXPECTED_NORMAL_GROUP_ANDROID_COMMAND_TOPIC \
  EXPECTED_NORMAL_GROUP_RESULT_TOPIC \
  EXPECTED_NORMAL_GROUP_RESULT_GROUP_ID; do
  [ -n "${!required_profile_var:-}" ] \
    || profile_die "环境档案缺少必填字段: ${required_profile_var}"
done
case "${PROFILE_PROTOCOL_TRANSPORT}" in
  direct|jump) ;;
  *) profile_die "协议连接模式只允许 direct 或 jump: ${PROFILE_PROTOCOL_TRANSPORT}" ;;
esac
case "${PROFILE_PROTOCOL_TRAFFIC_ENABLED}" in
  true|false) ;;
  *) profile_die "协议流量采集开关只允许 true 或 false" ;;
esac
for numeric_profile_var in \
  PROFILE_PROTOCOL_TRAFFIC_RETENTION_MAX_BYTES \
  PROFILE_PROTOCOL_TRAFFIC_RETENTION_MAX_AGE_MS \
  PROFILE_PROTOCOL_TRAFFIC_DASHBOARD_PORT; do
  case "${!numeric_profile_var}" in
    ''|*[!0-9]*|0) profile_die "${numeric_profile_var} 必须是正整数" ;;
  esac
done
case "${PROFILE_ZHUAN_DEPLOY_MODE}" in
  fleet)
    for required_profile_var in \
      PROFILE_ZHUAN_FLEET_CONFIG_REL PROFILE_ZHUAN_FLEET_KEYS_REL \
      PROFILE_ZHUAN_FLEET_EXPECTED_NODES PROFILE_ZHUAN_FLEET_COORDINATOR_PORT; do
      [ -n "${!required_profile_var:-}" ] \
        || profile_die "环境档案缺少必填字段: ${required_profile_var}"
    done
    case "${PROFILE_ZHUAN_FLEET_EXPECTED_NODES}" in
      ''|*[!0-9]*|0) profile_die "Zhuan fleet 节点数必须是正整数" ;;
    esac
    case "${PROFILE_ZHUAN_FLEET_COORDINATOR_PORT}" in
      ''|*[!0-9]*|0) profile_die "Zhuan fleet coordinator 端口必须是正整数" ;;
    esac
    ;;
  single)
    for required_profile_var in \
      PROFILE_ZHUAN_HOST PROFILE_ZHUAN_USER PROFILE_ZHUAN_KEY_REL \
      PROFILE_ZHUAN_REMOTE_DIR PROFILE_ZHUAN_COMPOSE_FILE PROFILE_ZHUAN_HTTP_PORT \
      PROFILE_ZHUAN_START_SERVICES PROFILE_ZHUAN_HEALTH_SERVICES \
      EXPECTED_ZHUAN_DB_SCHEMA; do
      [ -n "${!required_profile_var:-}" ] \
        || profile_die "环境档案缺少必填字段: ${required_profile_var}"
    done
    ;;
  *) profile_die "Zhuan 部署模式只允许 fleet 或 single: ${PROFILE_ZHUAN_DEPLOY_MODE}" ;;
esac
readonly EXPECTED_ARMADA_DB_SCHEMA EXPECTED_ANDROID_BASE_URL \
  EXPECTED_ANDROID_TOPIC_PREFIX EXPECTED_ZHUAN_DB_SCHEMA \
  EXPECTED_ZHUAN_REDIS_PREFIX EXPECTED_KAFKA_TOPICS EXPECTED_KAFKA_GROUPS \
  EXPECTED_NORMAL_GROUP_WEB_COMMAND_TOPIC \
  EXPECTED_NORMAL_GROUP_ANDROID_COMMAND_TOPIC \
  EXPECTED_NORMAL_GROUP_RESULT_TOPIC EXPECTED_NORMAL_GROUP_RESULT_GROUP_ID

# Use the public IP by default so local proxy fake-ip DNS cannot break ssh/rsync.
SSH_HOST="${ARMADA_DEPLOY_HOST:-${PROFILE_ARMADA_HOST}}"
SSH_USER="${ARMADA_DEPLOY_USER:-${PROFILE_ARMADA_USER}}"
SSH_KEY="${ARMADA_DEPLOY_KEY:-${WORKSPACE_ROOT}/${PROFILE_ARMADA_KEY_REL}}"
REMOTE_DIR="${ARMADA_DEPLOY_REMOTE_DIR:-${PROFILE_ARMADA_REMOTE_DIR}}"
COMPOSE_FILE="${ARMADA_DEPLOY_COMPOSE:-${PROFILE_ARMADA_COMPOSE_FILE}}"
COMPOSE_PROJECT="${ARMADA_DEPLOY_PROJECT:-${PROFILE_ARMADA_COMPOSE_PROJECT}}"
PUBLIC_URL="${ARMADA_DEPLOY_PUBLIC_URL:-${PROFILE_ARMADA_PUBLIC_URL}}"
APP_TITLE="${ARMADA_APP_TITLE:-${APP_TITLE:-${PROFILE_APP_TITLE}}}"
FRONTEND_DIR="${ARMADA_FRONTEND_DIR:-${WORKSPACE_ROOT}/wheel-saas-pure-web}"
PROTOCOL_DIR="${ARMADA_PROTOCOL_DIR:-${WORKSPACE_ROOT}/armada-protocol}"
PROTOCOL_LAYER_DIR="${PROTOCOL_DIR}/protocol-layer"
PROTOCOL_SSH_HOST="${ARMADA_PROTOCOL_DEPLOY_HOST:-${PROFILE_PROTOCOL_HOST}}"
PROTOCOL_SSH_USER="${ARMADA_PROTOCOL_DEPLOY_USER:-${PROFILE_PROTOCOL_USER}}"
PROTOCOL_SSH_KEY="${ARMADA_PROTOCOL_DEPLOY_KEY:-${WORKSPACE_ROOT}/${PROFILE_PROTOCOL_KEY_REL}}"
PROTOCOL_REMOTE_DIR="${ARMADA_PROTOCOL_DEPLOY_REMOTE_DIR:-${PROFILE_PROTOCOL_REMOTE_DIR}}"
PROTOCOL_PM2_CONFIG="${ARMADA_PROTOCOL_PM2_CONFIG:-${PROFILE_PROTOCOL_PM2_CONFIG}}"
PROTOCOL_HEALTH_PORT="${ARMADA_PROTOCOL_HEALTH_PORT:-${PROFILE_PROTOCOL_HEALTH_PORT}}"
PROTOCOL_TRANSPORT="${ARMADA_PROTOCOL_TRANSPORT:-${PROFILE_PROTOCOL_TRANSPORT}}"
PROTOCOL_TRAFFIC_ENABLED="${PROFILE_PROTOCOL_TRAFFIC_ENABLED}"
PROTOCOL_TRAFFIC_RETENTION_MAX_BYTES="${PROFILE_PROTOCOL_TRAFFIC_RETENTION_MAX_BYTES}"
PROTOCOL_TRAFFIC_RETENTION_MAX_AGE_MS="${PROFILE_PROTOCOL_TRAFFIC_RETENTION_MAX_AGE_MS}"
PROTOCOL_TRAFFIC_DASHBOARD_PORT="${PROFILE_PROTOCOL_TRAFFIC_DASHBOARD_PORT}"
PROTOCOL_JUMP_HOST="${ARMADA_PROTOCOL_JUMP_HOST:-${PROFILE_PROTOCOL_JUMP_HOST}}"
PROTOCOL_JUMP_USER="${ARMADA_PROTOCOL_JUMP_USER:-${PROFILE_PROTOCOL_JUMP_USER}}"
PROTOCOL_JUMP_KEY="${ARMADA_PROTOCOL_JUMP_KEY:-${WORKSPACE_ROOT}/${PROFILE_PROTOCOL_JUMP_KEY_REL}}"
ZHUAN_DIR="${ARMADA_ZHUAN_DIR:-${WORKSPACE_ROOT}/whatsapp-server-feature-android-zhuan}"
ZHUAN_DEPLOY_MODE="${PROFILE_ZHUAN_DEPLOY_MODE}"
ZHUAN_SSH_HOST=""
ZHUAN_SSH_USER=""
ZHUAN_SSH_KEY=""
ZHUAN_REMOTE_DIR=""
ZHUAN_COMPOSE_FILE=""
ZHUAN_HTTP_PORT=""
ZHUAN_START_SERVICES=""
ZHUAN_HEALTH_SERVICES=""
ZHUAN_HEALTH_DISPLAY=""
ZHUAN_FLEET_SCRIPT="${ZHUAN_DIR}/deploy/fleet/deploy-local.sh"
ZHUAN_FLEET_CONFIG="${ARMADA_ZHUAN_FLEET_CONFIG:-${ZHUAN_DIR}/${PROFILE_ZHUAN_FLEET_CONFIG_REL:-}}"
ZHUAN_FLEET_KEYS_DIR="${ARMADA_ZHUAN_FLEET_KEYS_DIR:-${WORKSPACE_ROOT}/${PROFILE_ZHUAN_FLEET_KEYS_REL:-}}"
ZHUAN_FLEET_EXPECTED_NODES="${PROFILE_ZHUAN_FLEET_EXPECTED_NODES:-0}"
ZHUAN_FLEET_COORDINATOR_PORT="${PROFILE_ZHUAN_FLEET_COORDINATOR_PORT:-0}"
if [ "${ZHUAN_DEPLOY_MODE}" = single ]; then
  ZHUAN_SSH_HOST="${ARMADA_ZHUAN_DEPLOY_HOST:-${PROFILE_ZHUAN_HOST}}"
  ZHUAN_SSH_USER="${ARMADA_ZHUAN_DEPLOY_USER:-${PROFILE_ZHUAN_USER}}"
  ZHUAN_SSH_KEY="${ARMADA_ZHUAN_DEPLOY_KEY:-${WORKSPACE_ROOT}/${PROFILE_ZHUAN_KEY_REL}}"
  ZHUAN_REMOTE_DIR="${ARMADA_ZHUAN_DEPLOY_REMOTE_DIR:-${PROFILE_ZHUAN_REMOTE_DIR}}"
  ZHUAN_COMPOSE_FILE="${ARMADA_ZHUAN_COMPOSE_FILE:-${PROFILE_ZHUAN_COMPOSE_FILE}}"
  ZHUAN_HTTP_PORT="${ARMADA_ZHUAN_HTTP_PORT:-${PROFILE_ZHUAN_HTTP_PORT}}"
  ZHUAN_START_SERVICES="${PROFILE_ZHUAN_START_SERVICES}"
  ZHUAN_HEALTH_SERVICES="${PROFILE_ZHUAN_HEALTH_SERVICES}"
  ZHUAN_HEALTH_DISPLAY="${ZHUAN_HEALTH_SERVICES// /、}"
fi
JAR_NAME="armada-api-deploy.jar"

# shellcheck source=lib/common.sh
. "${SCRIPT_DIR}/lib/common.sh"
# shellcheck source=lib/artifact.sh
. "${SCRIPT_DIR}/lib/artifact.sh"
# shellcheck source=lib/armada.sh
. "${SCRIPT_DIR}/lib/armada.sh"
# shellcheck source=lib/protocol.sh
. "${SCRIPT_DIR}/lib/protocol.sh"
# shellcheck source=lib/zhuan.sh
. "${SCRIPT_DIR}/lib/zhuan.sh"
# shellcheck source=lib/deep-check.sh
. "${SCRIPT_DIR}/lib/deep-check.sh"
armada_init_colors

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
SUMMARY_ENABLED=0
ACTIVE_COMPONENT=""
STATUS_PROTOCOL=SKIPPED
STATUS_ZHUAN=SKIPPED
STATUS_BACKEND=SKIPPED
STATUS_FRONTEND=SKIPPED
CHECK_ONLY=0
SCOPE_EXPLICIT=0

refresh_build_paths() {
  API_DIR="${BUILD_REPO_ROOT}/armada-api"
  JAR_PATH=""
  DEPLOY_ASSET_DIR="${BUILD_REPO_ROOT}/armada-deploy"
}

trap 'armada_on_exit "$?"' EXIT

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

APP_TITLE_REMOTE="$(shell_single_quote "${APP_TITLE}")"
ZHUAN_SSH_KEY_RSYNC="$(shell_single_quote "${ZHUAN_SSH_KEY}")"

usage() {
  cat <<EOF
deploy-test.sh - 部署 armada API + wheel-saas-pure-web + Baileys/Zhuan 协议层到测试服。

用法:
  ./armada-deploy/deploy-test.sh [options]
  ./armada-deploy/deploy-test.sh          只显示部署指引,不执行部署。

参数:
  --env test1|perf2  选择环境;默认 test1。
  --check          对所选环境执行只读深度检查;不构建、不同步、不重启。
  --all            构建并部署后端 + 前端。保持旧语义,不部署协议层。
  --full           构建并部署后端 + 前端 + Baileys 协议层 + Zhuan 协议。
  --be             只构建并部署后端。
  --fe             只构建并部署前端/nginx。
  --protocol       只部署 Baileys 协议层。
  --zhuan          只部署 Zhuan 协议。
  --branch <name>  从指定 armada 远端分支创建临时 worktree 构建并部署。
  -y, --yes        跳过确认提示。
  --logs           部署后跟随对应服务日志;--full 保持跟随后端日志。
  -n, --dry-run    只打印计划,不构建、不同步、不重启。
  -h, --help       显示本帮助。

可覆盖的环境变量:
  ARMADA_DEPLOY_HOST
  ARMADA_DEPLOY_USER
  ARMADA_DEPLOY_KEY
  ARMADA_DEPLOY_REMOTE_DIR
  ARMADA_DEPLOY_BRANCH
  ARMADA_APP_TITLE       前端左上角环境标识;未设置时使用环境档案值
  ARMADA_FRONTEND_DIR
  ARMADA_PROTOCOL_DIR
  ARMADA_PROTOCOL_DEPLOY_HOST
  ARMADA_PROTOCOL_DEPLOY_USER
  ARMADA_PROTOCOL_DEPLOY_KEY
  ARMADA_PROTOCOL_DEPLOY_REMOTE_DIR
  ARMADA_PROTOCOL_PM2_CONFIG
  ARMADA_PROTOCOL_NODE_BIN
  ARMADA_PROTOCOL_NPM_BIN
  ARMADA_PROTOCOL_TRANSPORT
  ARMADA_PROTOCOL_JUMP_HOST
  ARMADA_PROTOCOL_JUMP_USER
  ARMADA_PROTOCOL_JUMP_KEY
  ARMADA_ZHUAN_DIR
  ARMADA_ZHUAN_DEPLOY_HOST
  ARMADA_ZHUAN_DEPLOY_USER
  ARMADA_ZHUAN_DEPLOY_KEY
  ARMADA_ZHUAN_DEPLOY_REMOTE_DIR
  ARMADA_ZHUAN_COMPOSE_FILE
  ARMADA_ZHUAN_FLEET_CONFIG
  ARMADA_ZHUAN_FLEET_KEYS_DIR

Armada 目标服务器:
  ${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}
协议目标服务器:
  ${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}
Zhuan 目标服务器:
  $([ "${ZHUAN_DEPLOY_MODE}" = fleet ] \
    && printf 'fleet / coordinator + %s nodes' "${ZHUAN_FLEET_EXPECTED_NODES}" \
    || printf '%s@%s:%s' "${ZHUAN_SSH_USER}" "${ZHUAN_SSH_HOST}" "${ZHUAN_REMOTE_DIR}")

访问入口:
  ${PUBLIC_URL}
EOF
}

guide() {
  cat <<EOF
Armada 测试环境部署指引

你要部署什么?
  选择环境:
    --env test1    第一套测试环境(默认)
    --env perf2    第二套性能环境

  后端 + 前端:
    ./armada-deploy/deploy-test.sh --all -y

  后端 + 前端 + Baileys 协议层 + Zhuan 协议:
    ./armada-deploy/deploy-test.sh --full -y

  只部署后端:
    ./armada-deploy/deploy-test.sh --be -y

  只部署前端/nginx:
    ./armada-deploy/deploy-test.sh --fe -y

  只部署协议层:
    ./armada-deploy/deploy-test.sh --protocol -y

  只部署 Zhuan 协议:
    ./armada-deploy/deploy-test.sh --zhuan -y

  只看部署计划,不真正执行:
    ./armada-deploy/deploy-test.sh --dry-run

  只读深度检查:
    ./armada-deploy/deploy-test.sh --env perf2 --check

  部署指定 armada 分支:
    ./armada-deploy/deploy-test.sh --all --branch main -y
    ./armada-deploy/deploy-test.sh --be --branch feature/group-import -y
    ARMADA_DEPLOY_BRANCH=main ./armada-deploy/deploy-test.sh --be -y

--branch 说明:
  1. 分支名写远端分支名,例如 main 或 feature/group-import,不要写 origin/main。
  2. 脚本会先 fetch origin/<branch>,再创建临时 worktree 构建 armada 后端和部署编排文件。
  3. 不会切换当前工作区分支,也不要求当前工作区干净。
  4. 前端、Baileys 协议层和 Zhuan 协议仍从各自环境变量指向的本地目录部署,不受 --branch 影响。
  5. 如果只想确认会部署哪个分支,先加 --dry-run:
     ./armada-deploy/deploy-test.sh --be --branch main --dry-run

常用参数:
  --env        只接受 test1 或 perf2;不接受文件路径。
  --check      检查配置、Kafka 元数据和跨组件连通性;不修改环境。
  --all        本地构建后端 jar 和前端 dist,同步两者并重启两个容器。
  --full       部署后端 + 前端 + Baileys 协议层 + Zhuan 协议。
  --be         本地构建后端 jar,同步 jar,只重建/重启 armada-backend。
  --fe         本地构建前端 dist,同步 dist,只重建/重启 armada-nginx。
  --protocol   同步协议源码到协议机,远端 npm ci + npm run build,PM2 滚动重载。
  --zhuan      同步 Zhuan 源码到 Armada 测试机,远端 Compose 构建、迁移并重启。
  --branch     从 origin/<branch> 创建临时 worktree 构建 armada 与部署编排,不切换当前工作区。
  -y, --yes    跳过确认提示。
  --logs       部署完成后跟随对应服务日志;--full 保持跟随 armada-backend。
  --dry-run    只显示将要做什么,不构建、不 rsync、不 SSH 重启、不验活。
  -h, --help   显示完整参数说明。

目标:
  Armada: ${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}
  协议层: ${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}
  Zhuan: $([ "${ZHUAN_DEPLOY_MODE}" = fleet ] \
    && printf 'fleet / coordinator + %s nodes' "${ZHUAN_FLEET_EXPECTED_NODES}" \
    || printf '%s@%s:%s' "${ZHUAN_SSH_USER}" "${ZHUAN_SSH_HOST}" "${ZHUAN_REMOTE_DIR}")
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
    --env)
      shift
      [ $# -gt 0 ] || die "--env 需要环境名"
      [ "$1" = "${SELECTED_ENV}" ] || die "环境解析结果不一致: $1"
      ;;
    --env=*)
      [ "${1#*=}" = "${SELECTED_ENV}" ] || die "环境解析结果不一致: ${1#*=}"
      ;;
    --check) CHECK_ONLY=1 ;;
    --all) SCOPE="all"; SCOPE_EXPLICIT=1 ;;
    --full) SCOPE="full"; SCOPE_EXPLICIT=1 ;;
    --be) SCOPE="be"; SCOPE_EXPLICIT=1 ;;
    --fe) SCOPE="fe"; SCOPE_EXPLICIT=1 ;;
    --protocol) SCOPE="protocol"; SCOPE_EXPLICIT=1 ;;
    --zhuan) SCOPE="zhuan"; SCOPE_EXPLICIT=1 ;;
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

if [ "${CHECK_ONLY}" = 1 ]; then
  if [ "${SCOPE_EXPLICIT}" = 1 ] \
    || [ "${DRY_RUN}" = 1 ] \
    || [ "${TAIL_LOGS}" = 1 ] \
    || [ "${ASSUME_YES}" = 1 ] \
    || [ -n "${DEPLOY_BRANCH}" ]; then
    die "--check 不能与部署或日志参数组合"
  fi
  SCOPE=check
fi

if [ -n "${DEPLOY_BRANCH}" ] && [ "${DRY_RUN}" != 1 ]; then
  prepare_branch_worktree
elif [ -n "${DEPLOY_BRANCH}" ]; then
  validate_branch_name
fi

BUILD_BE=0
BUILD_FE=0
BUILD_PROTOCOL=0
BUILD_ZHUAN=0
SERVICES=""
COMPOSE_UP_EXTRA=""
COMPOSE_UP_ARGS=""
COMPOSE_UP_COMMAND=""
PNPM_AVAILABLE=0
FRONTEND_BUILD_MODE=""
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
    BUILD_ZHUAN=1
    SERVICES=""
    SCOPE_DESC="后端 + 前端 + Baileys 协议层 + Zhuan 协议"
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
  zhuan)
    BUILD_ZHUAN=1
    SCOPE_DESC="只 Zhuan 协议"
    ;;
  check)
    SCOPE_DESC="只读深度检查"
    ;;
  *)
    die "无效部署范围: ${SCOPE}"
    ;;
esac

if [ "${BUILD_PROTOCOL}" = 1 ]; then STATUS_PROTOCOL=PENDING; fi
if [ "${BUILD_ZHUAN}" = 1 ]; then STATUS_ZHUAN=PENDING; fi
if [ "${BUILD_BE}" = 1 ]; then STATUS_BACKEND=PENDING; fi
if [ "${BUILD_FE}" = 1 ]; then STATUS_FRONTEND=PENDING; fi

if [ "${SCOPE}" != "all" ] && [ "${SCOPE}" != "full" ]; then
  COMPOSE_UP_EXTRA="--no-deps"
fi
COMPOSE_UP_ARGS="up -d --build"
[ -n "${COMPOSE_UP_EXTRA}" ] && COMPOSE_UP_ARGS="${COMPOSE_UP_ARGS} ${COMPOSE_UP_EXTRA}"
[ -n "${SERVICES}" ] && COMPOSE_UP_ARGS="${COMPOSE_UP_ARGS} ${SERVICES}"
COMPOSE_UP_COMMAND="docker compose ${COMPOSE_UP_ARGS}"

JDK17_HOME=""
if [ "${BUILD_BE}" = 1 ]; then
  JDK17_HOME="$(armada_find_jdk17)" || die "需要 JDK 17。请安装 JDK 17 或设置 JAVA17_HOME。"
fi

if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
  validate_remote_dir "Armada" "${REMOTE_DIR}"
  validate_ssh_identity "Armada" "${SSH_HOST}" "${SSH_USER}"
  require_ssh_key " Armada" "${SSH_KEY}"
  [ -f "${DEPLOY_ASSET_DIR}/docker-compose.rds.yml" ] || die "缺少 ${DEPLOY_ASSET_DIR}/docker-compose.rds.yml"
  [ -f "${DEPLOY_ASSET_DIR}/backend.prebuilt.Dockerfile" ] || die "缺少 ${DEPLOY_ASSET_DIR}/backend.prebuilt.Dockerfile"
  [ -f "${DEPLOY_ASSET_DIR}/nginx.prebuilt.Dockerfile" ] || die "缺少 ${DEPLOY_ASSET_DIR}/nginx.prebuilt.Dockerfile"
  [ -f "${DEPLOY_ASSET_DIR}/render-platform-config.sh" ] || die "缺少 ${DEPLOY_ASSET_DIR}/render-platform-config.sh"
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
  validate_remote_dir "协议" "${PROTOCOL_REMOTE_DIR}"
  validate_ssh_identity "协议" "${PROTOCOL_SSH_HOST}" "${PROTOCOL_SSH_USER}"
  require_ssh_key "协议" "${PROTOCOL_SSH_KEY}"
  case "${PROTOCOL_TRANSPORT}" in
    direct) ;;
    jump)
      validate_ssh_identity "协议跳板" "${PROTOCOL_JUMP_HOST}" "${PROTOCOL_JUMP_USER}"
      require_ssh_key "协议跳板" "${PROTOCOL_JUMP_KEY}"
      ;;
    *) die "协议连接模式只允许 direct 或 jump: ${PROTOCOL_TRANSPORT}" ;;
  esac
  [ -d "${PROTOCOL_DIR}" ] || die "找不到协议仓库目录: ${PROTOCOL_DIR}"
  [ -d "${PROTOCOL_LAYER_DIR}" ] || die "找不到协议层目录: ${PROTOCOL_LAYER_DIR}"
  [ -f "${PROTOCOL_LAYER_DIR}/package.json" ] || die "找不到协议层 package.json"
  [ -f "${PROTOCOL_LAYER_DIR}/package-lock.json" ] || die "找不到协议层 package-lock.json"
  [ -f "${PROTOCOL_LAYER_DIR}/tsconfig.json" ] || die "找不到协议层 tsconfig.json"
  [ -d "${PROTOCOL_LAYER_DIR}/src" ] || die "找不到协议层 src 目录"
  [ -d "${PROTOCOL_LAYER_DIR}/deploy" ] || die "找不到协议层 deploy 目录"
  [ -f "${PROTOCOL_LAYER_DIR}/deploy/traffic-dashboard.pm2.config.cjs" ] \
    || die "找不到协议流量看板 PM2 配置"
  [ -d "${PROTOCOL_DIR}/openapi" ] || die "找不到协议 openapi 目录"
  if [ "${DRY_RUN}" != 1 ]; then
    protocol_validate_local_toolchain
  fi
fi
if [ "${BUILD_ZHUAN}" = 1 ]; then
  [ -d "${ZHUAN_DIR}" ] || die "找不到 Zhuan 仓库目录: ${ZHUAN_DIR}"
  [ -f "${ZHUAN_DIR}/go.mod" ] || die "找不到 Zhuan go.mod"
  [ -f "${ZHUAN_DIR}/go.sum" ] || die "找不到 Zhuan go.sum"
  [ -f "${ZHUAN_DIR}/.dockerignore" ] || die "找不到 Zhuan .dockerignore"
  [ -f "${ZHUAN_DIR}/deploy/Dockerfile" ] || die "找不到 Zhuan deploy/Dockerfile"
  if [ "${ZHUAN_DEPLOY_MODE}" = fleet ]; then
    zhuan_validate_fleet_inputs
  else
    case "${ZHUAN_COMPOSE_FILE}" in
      docker-compose.yml|docker-compose.perf.yml) ;;
      *) die "Zhuan Compose 只允许 docker-compose.yml 或 docker-compose.perf.yml" ;;
    esac
    validate_remote_dir "Zhuan" "${ZHUAN_REMOTE_DIR}"
    validate_ssh_identity "Zhuan" "${ZHUAN_SSH_HOST}" "${ZHUAN_SSH_USER}"
    require_ssh_key " Zhuan" "${ZHUAN_SSH_KEY}"
    [ -f "${ZHUAN_DIR}/deploy/${ZHUAN_COMPOSE_FILE}" ] \
      || die "找不到 Zhuan deploy/${ZHUAN_COMPOSE_FILE}"
  fi
fi

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
if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ] || [ "${BUILD_PROTOCOL}" = 1 ] || [ "${BUILD_ZHUAN}" = 1 ]; then
  command -v rsync >/dev/null 2>&1 || die "需要 rsync"
fi
if [ "${BUILD_ZHUAN}" = 1 ] \
  && [ "${ZHUAN_DEPLOY_MODE}" = fleet ] \
  && [ "${TAIL_LOGS}" = 1 ] \
  && [ "${BUILD_BE}" = 0 ] \
  && [ "${BUILD_PROTOCOL}" = 0 ]; then
  die "Zhuan fleet 包含多台机器，--zhuan --logs 不支持；请到目标节点分别查看日志"
fi
command -v ssh >/dev/null 2>&1 || die "需要 ssh"
if [ "${CHECK_ONLY}" = 1 ]; then
  command -v curl >/dev/null 2>&1 || die "只读深度检查需要 curl"
fi

SSH_OPTS=(
  -i "${SSH_KEY}"
  -o BatchMode=yes
  -o ConnectTimeout=15
  -o StrictHostKeyChecking=accept-new
)
RSYNC_SSH="$(build_rsync_ssh "${SSH_KEY}")"
PROTOCOL_SSH_OPTS=(
  -i "${PROTOCOL_SSH_KEY}"
  -o BatchMode=yes
  -o ConnectTimeout=15
  -o StrictHostKeyChecking=accept-new
)
PROTOCOL_PROXY_COMMAND=""
if [ "${PROTOCOL_TRANSPORT}" = jump ]; then
  PROTOCOL_PROXY_COMMAND="$(build_proxy_command "${PROTOCOL_JUMP_KEY}" "${PROTOCOL_JUMP_HOST}" "${PROTOCOL_JUMP_USER}")"
  PROTOCOL_SSH_OPTS+=(
    -o "ProxyCommand=${PROTOCOL_PROXY_COMMAND}"
  )
fi
PROTOCOL_RSYNC_SSH="$(build_rsync_ssh "${PROTOCOL_SSH_KEY}" "${PROTOCOL_PROXY_COMMAND}")"
ZHUAN_SSH_OPTS=(
  -i "${ZHUAN_SSH_KEY}"
  -o BatchMode=yes
  -o ConnectTimeout=15
  -o StrictHostKeyChecking=accept-new
)
ZHUAN_RSYNC_SSH="$(build_rsync_ssh "${ZHUAN_SSH_KEY}")"

ssh_run() {
  ssh "${SSH_OPTS[@]}" "${SSH_USER}@${SSH_HOST}" "$@"
}

protocol_ssh_run() {
  ssh "${PROTOCOL_SSH_OPTS[@]}" "${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}" "$@"
}

zhuan_ssh_run() {
  ssh "${ZHUAN_SSH_OPTS[@]}" "${ZHUAN_SSH_USER}@${ZHUAN_SSH_HOST}" "$@"
}

if [ "${CHECK_ONLY}" = 1 ]; then
  run_deep_check
  exit 0
fi

print_plan() {
  echo
  info "部署计划"
  printf '  环境 ID       : %s\n' "${ENV_ID}"
  printf '  范围          : %s\n' "${SCOPE_DESC}"
  if [ -n "${DEPLOY_BRANCH}" ]; then
    printf '  armada 分支   : origin/%s%s\n' "${DEPLOY_BRANCH}" "$([ "${DRY_RUN}" = 1 ] && printf ' (dry-run 不拉取)')"
  else
    printf '  armada 来源   : 当前工作区\n'
  fi
  printf '  构建目录      : %s\n' "${BUILD_REPO_ROOT}"
  printf '  编排目录      : %s\n' "${DEPLOY_ASSET_DIR}"
  if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
    print_repository_evidence "Armada 源码" "${BUILD_REPO_ROOT}"
  fi
  if [ "${BUILD_FE}" = 1 ]; then
    printf '  前端目录      : %s\n' "${FRONTEND_DIR}"
    print_repository_evidence "前端源码" "${FRONTEND_DIR}"
  fi
  if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
    printf '  Armada 目标   : %s@%s:%s\n' "${SSH_USER}" "${SSH_HOST}" "${REMOTE_DIR}"
    printf '  compose       : %s / project=%s\n' "${COMPOSE_FILE}" "${COMPOSE_PROJECT}"
    printf '  环境标识      : %s\n' "${APP_TITLE}"
  fi
  if [ "${BUILD_PROTOCOL}" = 1 ]; then
    printf '  协议目录      : %s\n' "${PROTOCOL_DIR}"
    print_repository_evidence "协议源码" "${PROTOCOL_DIR}"
    printf '  协议目标      : %s@%s:%s\n' "${PROTOCOL_SSH_USER}" "${PROTOCOL_SSH_HOST}" "${PROTOCOL_REMOTE_DIR}"
    if [ "${PROTOCOL_TRANSPORT}" = jump ]; then
      printf '  协议连接      : jump via %s@%s\n' "${PROTOCOL_JUMP_USER}" "${PROTOCOL_JUMP_HOST}"
    else
      printf '  协议连接      : direct\n'
    fi
    printf '  协议 PM2      : %s\n' "${PROTOCOL_PM2_CONFIG}"
    printf '  协议流量监控  : enabled=%s / dashboard=127.0.0.1:%s / retention=7d,8GiB\n' \
      "${PROTOCOL_TRAFFIC_ENABLED}" "${PROTOCOL_TRAFFIC_DASHBOARD_PORT}"
  fi
  if [ "${BUILD_ZHUAN}" = 1 ]; then
    printf '  Zhuan 目录     : %s\n' "${ZHUAN_DIR}"
    print_repository_evidence "Zhuan 源码" "${ZHUAN_DIR}"
    if [ "${ZHUAN_DEPLOY_MODE}" = fleet ]; then
      printf '  Zhuan 模式     : fleet / coordinator + %s nodes\n' "${ZHUAN_FLEET_EXPECTED_NODES}"
      printf '  Zhuan 清单     : %s\n' "${ZHUAN_FLEET_CONFIG}"
    else
      printf '  Zhuan 模式     : single\n'
      printf '  Zhuan 目标     : %s@%s:%s\n' "${ZHUAN_SSH_USER}" "${ZHUAN_SSH_HOST}" "${ZHUAN_REMOTE_DIR}"
      printf '  Zhuan compose  : %s\n' "${ZHUAN_COMPOSE_FILE}"
    fi
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
  if [ "${BUILD_ZHUAN}" = 1 ]; then
    info "[dry-run] 将检查 Zhuan SSH 连通性"
  fi
  [ -n "${DEPLOY_BRANCH}" ] && info "[dry-run] 实际部署时将 fetch origin/${DEPLOY_BRANCH} 并创建临时 worktree"
  [ "${BUILD_BE}" = 1 ] && info "[dry-run] 将执行: (cd ${API_DIR} && JAVA_HOME=${JDK17_HOME} mvn -q -Dmaven.test.skip=true clean package)"
  if [ "${BUILD_FE}" = 1 ]; then
    if [ "${PNPM_AVAILABLE}" = 1 ]; then
      info "[dry-run] 将执行: (cd ${FRONTEND_DIR} && pnpm install --frozen-lockfile && pnpm build)"
    else
      info "[dry-run] 将执行: (cd ${FRONTEND_DIR} && npm run build)"
    fi
  fi
  if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
    info "[dry-run] 将 rsync Armada 部署文件和产物到 ${REMOTE_DIR}"
    info "[dry-run] 将在 Armada 远端执行 APP_TITLE='${APP_TITLE}' ${COMPOSE_UP_COMMAND}"
  fi
  if [ "${BUILD_PROTOCOL}" = 1 ]; then
    info "[dry-run] 将本地构建协议层: cd ${PROTOCOL_LAYER_DIR} && Node.js 24 npm run build"
    info "[dry-run] 将同步协议层源码到 ${PROTOCOL_REMOTE_DIR}"
    info "[dry-run] 将在远端构建协议层: cd ${PROTOCOL_REMOTE_DIR}/protocol-layer && npm ci --no-audit --no-fund && npm run build"
    info "[dry-run] 将重载协议 PM2: pm2 startOrReload ${PROTOCOL_PM2_CONFIG} --update-env"
    info "[dry-run] 将启用协议流量采集并托管 127.0.0.1:${PROTOCOL_TRAFFIC_DASHBOARD_PORT} 看板"
  fi
  if [ "${BUILD_ZHUAN}" = 1 ]; then
    if [ "${ZHUAN_DEPLOY_MODE}" = fleet ]; then
      info "[dry-run] 将在四台目标并行预构建镜像，再并发部署 coordinator + ${ZHUAN_FLEET_EXPECTED_NODES} 台 node"
      info "[dry-run] 将保留各节点 .env、configs、certs、logs 和 data"
      info "[dry-run] 将在四机并发部署前检查旧 lifecycle Stream 已排空"
      info "[dry-run] 将验证 ${ZHUAN_FLEET_EXPECTED_NODES} 台协议节点全部 online"
    else
      info "[dry-run] 将同步 Zhuan 源码到 ${ZHUAN_REMOTE_DIR},保留远端配置和日志"
      info "[dry-run] 将校验并构建 Zhuan Compose 服务"
      info "[dry-run] 将运行迁移: whatsapp-migrate -env prod"
      info "[dry-run] 将启动并验活 ${ZHUAN_HEALTH_DISPLAY}"
    fi
  fi
  ok "dry-run 完成"
  exit 0
fi

if [ "${ASSUME_YES}" != 1 ]; then
  printf '确认部署到测试服? [y/N] '
  read -r answer </dev/tty || answer=""
  case "${answer}" in
    y|Y|yes|YES) ;;
    *) die "已取消" ;;
  esac
fi
SUMMARY_ENABLED=1
armada_metrics_init

if [ "${BUILD_BE}" = 1 ]; then
  ACTIVE_COMPONENT=backend
  armada_measure "backend local build" armada_build_backend
  ACTIVE_COMPONENT=""
fi

if [ "${BUILD_FE}" = 1 ]; then
  ACTIVE_COMPONENT=frontend
  armada_measure "frontend local build" armada_build_frontend
  ACTIVE_COMPONENT=""
fi

if [ "${BUILD_PROTOCOL}" = 1 ]; then
  ACTIVE_COMPONENT=protocol
  armada_measure "Baileys local build" protocol_build_local
  ACTIVE_COMPONENT=""
fi

if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
  ACTIVE_COMPONENT=armada
  info "检查 Armada SSH 连通性..."
  armada_measure_or_die "Armada SSH connectivity" "Armada SSH 连接失败" ssh_run true
  ok "Armada 服务器可达"
  ACTIVE_COMPONENT=""
fi
if [ "${BUILD_PROTOCOL}" = 1 ]; then
  ACTIVE_COMPONENT=protocol
  info "检查协议 SSH 连通性..."
  armada_measure_or_die "Baileys SSH connectivity" "协议 SSH 连接失败" protocol_ssh_run true
  ok "协议服务器可达"
  ACTIVE_COMPONENT=""
fi
if [ "${BUILD_ZHUAN}" = 1 ]; then
  ACTIVE_COMPONENT=zhuan
  info "检查 Zhuan SSH 连通性..."
  armada_measure_or_die "Zhuan SSH connectivity" "Zhuan SSH 连接失败" zhuan_check_connectivity
  ok "Zhuan 部署目标可达"
  ACTIVE_COMPONENT=""
fi

if [ "${BUILD_PROTOCOL}" = 1 ]; then
  ACTIVE_COMPONENT=protocol
  STATUS_PROTOCOL=RUNNING
  info "准备协议远端目录..."
  armada_measure "Baileys remote prepare" protocol_prepare_remote
  info "同步协议层源码..."
  armada_measure "Baileys source sync" protocol_sync_source
  info "构建并重载协议层..."
  armada_measure "Baileys remote build/reload" protocol_deploy_remote
  info "检查协议层访问..."
  armada_measure "Baileys health check" protocol_verify_health
  STATUS_PROTOCOL=SUCCESS
  ACTIVE_COMPONENT=""
  ok "协议层可访问"
fi

if [ "${BUILD_ZHUAN}" = 1 ]; then
  ACTIVE_COMPONENT=zhuan
  STATUS_ZHUAN=RUNNING
  armada_measure "Zhuan deployment" zhuan_deploy_selected
  STATUS_ZHUAN=SUCCESS
  ACTIVE_COMPONENT=""
  ok "Zhuan 协议可访问"
fi

if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
  ACTIVE_COMPONENT=armada
  if [ "${BUILD_BE}" = 1 ]; then STATUS_BACKEND=RUNNING; fi
  if [ "${BUILD_FE}" = 1 ]; then STATUS_FRONTEND=RUNNING; fi
  info "准备 Armada 远端目录..."
  armada_measure "Armada remote prepare" armada_prepare_remote
  ok "Armada 远端 .env 已包含必需数据库配置"

  info "同步 Armada 部署编排文件..."
  armada_measure "Armada assets sync" armada_sync_assets
fi

if [ "${BUILD_BE}" = 1 ]; then
  info "同步后端 jar..."
  armada_measure "backend jar sync" armada_sync_backend
fi

if [ "${BUILD_FE}" = 1 ]; then
  info "同步前端 dist..."
  armada_measure "frontend dist sync" armada_sync_frontend
fi

if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
  info "启动 Armada 容器..."
  armada_measure "Armada image build/start" armada_start
  info "检查 Armada 容器状态..."
  armada_measure "Armada health check" armada_verify_selected "${BUILD_BE}" "${BUILD_FE}"
  if [ "${BUILD_BE}" = 1 ]; then STATUS_BACKEND=SUCCESS; fi
  if [ "${BUILD_FE}" = 1 ]; then STATUS_FRONTEND=SUCCESS; fi
  ACTIVE_COMPONENT=""
  ok "Armada 容器运行中"
fi

if [ "${BUILD_FE}" = 1 ]; then
  ok "前端可访问"
  ok "前端环境标识: ${APP_TITLE}"
fi

if [ "${BUILD_BE}" = 1 ]; then
  ok "API 路径已打到后端"
fi

if [ "${BUILD_BE}" = 1 ] || [ "${BUILD_FE}" = 1 ]; then
  ok "Armada 部署完成: ${PUBLIC_URL}"
fi
if [ "${BUILD_PROTOCOL}" = 1 ]; then
  ok "协议层部署完成: ${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}"
fi
if [ "${BUILD_ZHUAN}" = 1 ]; then
  if [ "${ZHUAN_DEPLOY_MODE}" = fleet ]; then
    ok "Zhuan fleet 部署完成: coordinator + ${ZHUAN_FLEET_EXPECTED_NODES} nodes"
  else
    ok "Zhuan 协议部署完成: ${ZHUAN_SSH_USER}@${ZHUAN_SSH_HOST}:${ZHUAN_REMOTE_DIR}"
  fi
fi

if [ "${TAIL_LOGS}" = 1 ] && [ "${BUILD_BE}" = 1 ]; then
  armada_tail_backend_logs
elif [ "${TAIL_LOGS}" = 1 ] && [ "${BUILD_PROTOCOL}" = 1 ]; then
  protocol_tail_logs
elif [ "${TAIL_LOGS}" = 1 ] && [ "${BUILD_ZHUAN}" = 1 ]; then
  zhuan_tail_logs
fi
