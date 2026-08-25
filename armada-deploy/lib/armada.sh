#!/usr/bin/env bash

armada_find_jdk17() {
  local candidate javac_path
  local candidates=()

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    candidate="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    [ -z "${candidate}" ] || candidates+=("${candidate}")
  fi

  candidates+=(
    "${JAVA17_HOME:-}"
    "${JAVA_HOME:-}"
    /usr/lib/jvm/java-17-openjdk-*
    /usr/lib/jvm/temurin-17-*
    /usr/lib/jvm/jdk-17*
  )
  for candidate in "${candidates[@]}"; do
    [ -n "${candidate}" ] || continue
    [ -x "${candidate}/bin/javac" ] || continue
    if "${candidate}/bin/javac" -version 2>&1 | grep -Eq '^javac 17([. ]|$)'; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done

  # 兼容发行版只把 javac 注册到 PATH、但 JDK 目录名称不在上方列表中的情况。
  javac_path="$(command -v javac 2>/dev/null || true)"
  if [ -n "${javac_path}" ] && javac_path="$(readlink -f "${javac_path}")" \
    && "${javac_path}" -version 2>&1 | grep -Eq '^javac 17([. ]|$)'; then
    printf '%s\n' "${javac_path%/bin/javac}"
    return 0
  fi
  return 1
}

armada_build_backend() {
  info "构建后端 jar..."
  # 部署包不应被仓库中与当前发布无关的测试源码编译错误阻断；测试由发布前验证阶段单独执行。
  (cd "${API_DIR}" && JAVA_HOME="${JDK17_HOME}" mvn -q -Dmaven.test.skip=true clean package)
  JAR_PATH="$(armada_resolve_backend_jar "${API_DIR}/target")" \
    || die "构建后无法确定唯一后端 jar: ${API_DIR}/target"
  [ -f "${JAR_PATH}" ] || die "构建后未找到后端 jar: ${JAR_PATH}"
  ok "后端 jar 已就绪: ${JAR_PATH}"
}

armada_build_frontend() {
  info "构建前端 dist..."
  if [ "${PNPM_AVAILABLE}" = 1 ]; then
    (cd "${FRONTEND_DIR}" && pnpm install --frozen-lockfile && pnpm build)
  else
    warn "pnpm 不可用,使用现有 node_modules 执行 npm run build"
    (cd "${FRONTEND_DIR}" && npm run build)
  fi
  [ -d "${FRONTEND_DIR}/dist" ] || die "构建后未找到前端 dist: ${FRONTEND_DIR}/dist"
  ok "前端 dist 已就绪: ${FRONTEND_DIR}/dist"
}

armada_prepare_remote() {
  ssh_run "mkdir -p '${REMOTE_DIR}/armada-api/target' '${REMOTE_DIR}/wheel-saas-pure-web/dist'"
  ssh_run "bash -s -- '${REMOTE_DIR}'" <<'REMOTE_CHECK'
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
REMOTE_CHECK
}

armada_sync_assets() {
  armada_rsync "Armada assets" -az -e "${RSYNC_SSH}" \
    "${DEPLOY_ASSET_DIR}/backend.prebuilt.Dockerfile" \
    "${DEPLOY_ASSET_DIR}/nginx.prebuilt.Dockerfile" \
    "${DEPLOY_ASSET_DIR}/render-platform-config.sh" \
    "${DEPLOY_ASSET_DIR}/nginx.conf" \
    "${DEPLOY_ASSET_DIR}/stale-chunk-reload.js" \
    "${DEPLOY_ASSET_DIR}/docker-compose.rds.yml" \
    "${DEPLOY_ASSET_DIR}/.env.example" \
    "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/"
}

armada_sync_backend() {
  armada_rsync "backend jar" -a --partial -e "${RSYNC_SSH}" \
    "${JAR_PATH}" \
    "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/armada-api/target/${JAR_NAME}"
}

armada_sync_frontend() {
  # Mirror only the explicit dist root so stale Vite hash assets cannot accumulate remotely.
  armada_rsync "frontend dist" -az --delete-delay -e "${RSYNC_SSH}" \
    "${FRONTEND_DIR}/dist/" \
    "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/wheel-saas-pure-web/dist/"
}

armada_start() {
  local normal_group_android_topic normal_group_result_group normal_group_result_topic
  local normal_group_web_topic
  normal_group_web_topic="$(shell_single_quote "${EXPECTED_NORMAL_GROUP_WEB_COMMAND_TOPIC}")"
  normal_group_android_topic="$(shell_single_quote "${EXPECTED_NORMAL_GROUP_ANDROID_COMMAND_TOPIC}")"
  normal_group_result_topic="$(shell_single_quote "${EXPECTED_NORMAL_GROUP_RESULT_TOPIC}")"
  normal_group_result_group="$(shell_single_quote "${EXPECTED_NORMAL_GROUP_RESULT_GROUP_ID}")"
  armada_capture_docker_build_output "Armada images" ssh_run \
    "cd '${REMOTE_DIR}' && APP_TITLE='${APP_TITLE_REMOTE}' AUTH_SESSION_KEY_PREFIX='armada:${ENV_ID}:' NORMAL_GROUP_CREATION_WEB_COMMAND_TOPIC='${normal_group_web_topic}' NORMAL_GROUP_CREATION_ANDROID_COMMAND_TOPIC='${normal_group_android_topic}' NORMAL_GROUP_CREATION_RESULT_TOPIC='${normal_group_result_topic}' NORMAL_GROUP_CREATION_RESULT_GROUP_ID='${normal_group_result_group}' docker compose --env-file .env -p '${COMPOSE_PROJECT}' -f '${COMPOSE_FILE}' ${COMPOSE_UP_ARGS}"
}

armada_wait_backend_ready() {
  local attempt=1
  while ! ssh_run "cd '${REMOTE_DIR}' && port=\$(awk -F= '/^ARMADA_HTTP_PORT=/{print \$2}' .env | tail -n 1); port=\${port:-18080}; body=\$(curl -sS -m 8 \"http://127.0.0.1:\${port}/api/account-groups\" || true); printf '%s' \"\${body}\" | grep -Eq '\"code\"[[:space:]]*:[[:space:]]*(40101|40104|0|40001)'"; do
    if [ "${attempt}" -ge 30 ]; then
      die "Armada backend 未在时限内就绪"
    fi
    sleep 5
    attempt=$((attempt + 1))
  done
}

armada_verify_backend_runtime() {
  local expected_contract expected_line expected_prefix
  if [ -n "${EXPECTED_ANDROID_BASE_URL}" ]; then
    expected_line="$(shell_single_quote "PROTOCOL_ANDROID_BASE_URL=${EXPECTED_ANDROID_BASE_URL}")"
    ssh_run "docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' armada-backend | grep -Fx '${expected_line}' >/dev/null"
  fi
  expected_prefix="$(shell_single_quote "${EXPECTED_ANDROID_TOPIC_PREFIX}")"
  for topic_var in \
    PROTOCOL_ANDROID_LIFECYCLE_COMMANDS_TOPIC \
    PROTOCOL_ANDROID_MESSAGE_COMMANDS_TOPIC \
    PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC; do
    ssh_run "docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' armada-backend | grep -F '${topic_var}=${expected_prefix}' >/dev/null"
  done
  for expected_contract in \
    "NORMAL_GROUP_CREATION_WEB_COMMAND_TOPIC=${EXPECTED_NORMAL_GROUP_WEB_COMMAND_TOPIC}" \
    "NORMAL_GROUP_CREATION_ANDROID_COMMAND_TOPIC=${EXPECTED_NORMAL_GROUP_ANDROID_COMMAND_TOPIC}" \
    "NORMAL_GROUP_CREATION_RESULT_TOPIC=${EXPECTED_NORMAL_GROUP_RESULT_TOPIC}" \
    "NORMAL_GROUP_CREATION_RESULT_GROUP_ID=${EXPECTED_NORMAL_GROUP_RESULT_GROUP_ID}"; do
    expected_line="$(shell_single_quote "${expected_contract}")"
    ssh_run "docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' armada-backend | grep -Fx '${expected_line}' >/dev/null"
  done
}

armada_verify_frontend() {
  ssh_run "cd '${REMOTE_DIR}' && port=\$(awk -F= '/^ARMADA_HTTP_PORT=/{print \$2}' .env | tail -n 1); port=\${port:-18080}; curl -fsS -m 8 \"http://127.0.0.1:\${port}/\" | grep -qi '<!doctype html'"
  ssh_run "cd '${REMOTE_DIR}' && APP_TITLE='${APP_TITLE_REMOTE}' port=\$(awk -F= '/^ARMADA_HTTP_PORT=/{print \$2}' .env | tail -n 1); port=\${port:-18080}; curl -fsS -m 8 \"http://127.0.0.1:\${port}/platform-config.json\" | grep -F \"\${APP_TITLE}\" >/dev/null"
}

armada_verify_api_proxy() {
  ssh_run "cd '${REMOTE_DIR}' && port=\$(awk -F= '/^ARMADA_HTTP_PORT=/{print \$2}' .env | tail -n 1); port=\${port:-18080}; body=\$(curl -sS -m 8 \"http://127.0.0.1:\${port}/api/account-groups\" || true); printf '%s' \"\${body}\" | grep -Eq '\"code\"[[:space:]]*:[[:space:]]*(40101|40104|0|40001)'"
}

armada_verify_selected() {
  local verify_backend="$1"
  local verify_frontend="$2"
  if [ "${verify_backend}" = 1 ]; then
    ssh_run "docker inspect -f '{{.State.Status}}' armada-backend | grep -q '^running$'"
    armada_wait_backend_ready
    armada_verify_backend_runtime
    armada_verify_api_proxy
  fi
  if [ "${verify_frontend}" = 1 ]; then
    ssh_run "docker inspect -f '{{.State.Status}}' armada-nginx | grep -q '^running$'"
    armada_verify_frontend
  fi
}

armada_tail_backend_logs() {
  ssh_run "docker logs -f --tail 120 armada-backend"
}
