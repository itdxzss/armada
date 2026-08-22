#!/usr/bin/env bash

protocol_remote_deploy_payload='
set -eu
remote_dir="$1"
preferred_pm2_config="$2"
traffic_enabled="$3"
traffic_retention_max_bytes="$4"
traffic_retention_max_age_ms="$5"
traffic_dashboard_port="$6"
traffic_dir="${remote_dir}/traffic-capture"
cd "${remote_dir}/protocol-layer"
test -f .env || { echo "远端缺少协议配置: ${remote_dir}/protocol-layer/.env" >&2; exit 36; }
set -a
. ./.env
set +a
export TRAFFIC_ENABLED="${traffic_enabled}"
export TRAFFIC_DIR="${traffic_dir}"
export TRAFFIC_RETENTION_MAX_BYTES="${traffic_retention_max_bytes}"
export TRAFFIC_RETENTION_MAX_AGE_MS="${traffic_retention_max_age_ms}"
export TRAFFIC_DASHBOARD_HOST=127.0.0.1
export TRAFFIC_DASHBOARD_PORT="${traffic_dashboard_port}"
mkdir -p "${traffic_dir}"
chmod 700 "${traffic_dir}"
command -v npm >/dev/null 2>&1 || { echo "远端缺少 npm" >&2; exit 30; }
command -v pm2 >/dev/null 2>&1 || { echo "远端缺少 pm2" >&2; exit 31; }
node_version="$(node --version)"
case "${node_version}" in
  v24.*) ;;
  *)
    echo "远端 Node.js 必须为 24.x,当前为 ${node_version}" >&2
    exit 33
    ;;
esac
daemon_pid_file="${PM2_HOME:-${HOME}/.pm2}/pm2.pid"
if [ -s "${daemon_pid_file}" ]; then
  daemon_pid="$(cat "${daemon_pid_file}")"
  if kill -0 "${daemon_pid}" 2>/dev/null; then
    daemon_exe="$(readlink -f "/proc/${daemon_pid}/exe")"
    daemon_version="$("${daemon_exe}" --version 2>/dev/null || true)"
    case "${daemon_version}" in
      v24.*) ;;
      *)
        echo "PM2 daemon 必须运行在 Node.js 24.x,当前为 ${daemon_version:-unknown} (${daemon_exe:-unknown})" >&2
        echo "请先使用 Node.js 24 执行: pm2 save --force && pm2 kill && pm2 resurrect" >&2
        exit 34
        ;;
    esac
  fi
fi
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
test -f deploy/traffic-enabled.pm2.config.cjs \
  || { echo "远端缺少协议流量采集 PM2 包装配置" >&2; exit 37; }
export ARMADA_PROTOCOL_BASE_PM2_CONFIG="${pm2_config}"
pm2 startOrReload deploy/traffic-enabled.pm2.config.cjs --update-env
test -f deploy/traffic-dashboard.pm2.config.cjs \
  || { echo "远端缺少协议流量看板 PM2 配置" >&2; exit 40; }
pm2 startOrReload deploy/traffic-dashboard.pm2.config.cjs --update-env
pm2 jlist | node -e "
let input = \"\"
process.stdin.on(\"data\", chunk => { input += chunk })
process.stdin.on(\"end\", () => {
  const expectedProtocolApps = 5
  const apps = JSON.parse(input).filter(app =>
    /^(?:armada-)?protocol-(?:master|worker-[1-4])$/.test(app.name ?? \"\")
  )
  const invalid = apps.filter(app =>
    app.pm2_env?.status !== \"online\" ||
    !String(app.pm2_env?.node_version ?? \"\").startsWith(\"24.\")
  )
  if (apps.length !== expectedProtocolApps || invalid.length > 0) {
    const states = apps.map(app =>
      (app.name ?? \"unknown\") + \":\" +
      (app.pm2_env?.status ?? \"unknown\") + \":\" +
      (app.pm2_env?.node_version ?? \"unknown\")
    ).join(\",\")
    console.error(\"协议 PM2 应用必须全部运行在 Node.js 24.x; found=\" + apps.length + \"/\" + expectedProtocolApps + \"; states=\" + states)
    process.exit(35)
  }
  console.log(\"协议 PM2 应用校验通过: \" + apps.map(app => app.name).join(\",\"))
})
"
pm2 jlist | node -e "
let input = \"\"
process.stdin.on(\"data\", chunk => { input += chunk })
process.stdin.on(\"end\", () => {
  const app = JSON.parse(input).find(item => item.name === \"protocol-traffic-dashboard\")
  if (app?.pm2_env?.status !== \"online\" || !String(app?.pm2_env?.node_version ?? \"\").startsWith(\"24.\")) {
    console.error(\"协议流量看板必须在线并运行在 Node.js 24.x\")
    process.exit(38)
  }
})
"
attempt=1
while :; do
  overview="$(curl -fsS -m 8 "http://127.0.0.1:${traffic_dashboard_port}/api/overview" 2>/dev/null || true)"
  if printf "%s" "${overview}" | node -e "
let input = \"\"
process.stdin.on(\"data\", chunk => { input += chunk })
process.stdin.on(\"end\", () => {
  try {
    const health = JSON.parse(input).health
    const now = Date.now()
    if (!Array.isArray(health) || health.length !== 5 || health.some(item => now - Number(item.snapshot?.updatedAt ?? 0) > 30_000)) {
      process.exit(1)
    }
  } catch {
    process.exit(1)
  }
})
"; then
    break
  fi
  if [ "${attempt}" -ge 12 ]; then
    echo "协议流量采集未在时限内生成 5 份实时快照" >&2
    exit 39
  fi
  sleep 2
  attempt=$((attempt + 1))
done
pm2 save >/dev/null 2>&1 || true
'

protocol_find_node24() {
  local candidate path_node
  path_node="$(command -v node 2>/dev/null || true)"
  for candidate in \
    "${ARMADA_PROTOCOL_NODE_BIN:-}" \
    "${path_node}" \
    /opt/homebrew/opt/node@24/bin/node \
    /usr/local/opt/node@24/bin/node; do
    [ -n "${candidate}" ] || continue
    [ -x "${candidate}" ] || continue
    case "$("${candidate}" --version 2>/dev/null || true)" in
      v24.*) printf '%s\n' "${candidate}"; return 0 ;;
    esac
  done
  return 1
}

protocol_validate_local_toolchain() {
  PROTOCOL_NODE_BIN="$(protocol_find_node24)" \
    || die "本地构建协议层需要 Node.js 24;可设置 ARMADA_PROTOCOL_NODE_BIN"
  PROTOCOL_NPM_BIN="${ARMADA_PROTOCOL_NPM_BIN:-${PROTOCOL_NODE_BIN%/*}/npm}"
  [ -x "${PROTOCOL_NPM_BIN}" ] || die "Node.js 24 目录缺少 npm: ${PROTOCOL_NPM_BIN}"
  PATH="${PROTOCOL_NODE_BIN%/*}:${PATH}" "${PROTOCOL_NPM_BIN}" --version >/dev/null 2>&1 \
    || die "Node.js 24 npm 不可用: ${PROTOCOL_NPM_BIN}"
}

protocol_build_local() {
  info "本地构建协议层..."
  (cd "${PROTOCOL_LAYER_DIR}" && PATH="${PROTOCOL_NODE_BIN%/*}:${PATH}" "${PROTOCOL_NPM_BIN}" run build)
  [ -d "${PROTOCOL_LAYER_DIR}/dist" ] || die "构建后未找到协议层 dist: ${PROTOCOL_LAYER_DIR}/dist"
  ok "协议层本地构建通过"
}

protocol_prepare_remote() {
  protocol_ssh_run "mkdir -p '${PROTOCOL_REMOTE_DIR}/protocol-layer' '${PROTOCOL_REMOTE_DIR}/openapi'"
}

protocol_sync_source() {
  armada_rsync "Baileys source" -az --delete -e "${PROTOCOL_RSYNC_SSH}" \
    "${PROTOCOL_LAYER_DIR}/src/" \
    "${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}/protocol-layer/src/"
  armada_rsync "Baileys deploy config" -az --delete -e "${PROTOCOL_RSYNC_SSH}" \
    "${PROTOCOL_LAYER_DIR}/deploy/" \
    "${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}/protocol-layer/deploy/"
  armada_rsync "Baileys OpenAPI" -az --delete -e "${PROTOCOL_RSYNC_SSH}" \
    "${PROTOCOL_DIR}/openapi/" \
    "${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}/openapi/"
  armada_rsync "Baileys manifests" -az -e "${PROTOCOL_RSYNC_SSH}" \
    "${PROTOCOL_LAYER_DIR}/package.json" \
    "${PROTOCOL_LAYER_DIR}/package-lock.json" \
    "${PROTOCOL_LAYER_DIR}/tsconfig.json" \
    "${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}/protocol-layer/"
  if [ -f "${PROTOCOL_LAYER_DIR}/jest.config.mjs" ]; then
    armada_rsync "Baileys Jest config" -az -e "${PROTOCOL_RSYNC_SSH}" \
      "${PROTOCOL_LAYER_DIR}/jest.config.mjs" \
      "${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}/protocol-layer/"
  fi
  if [ -d "${PROTOCOL_LAYER_DIR}/patches" ]; then
    armada_rsync "Baileys patches" -az --delete -e "${PROTOCOL_RSYNC_SSH}" \
      "${PROTOCOL_LAYER_DIR}/patches/" \
      "${PROTOCOL_SSH_USER}@${PROTOCOL_SSH_HOST}:${PROTOCOL_REMOTE_DIR}/protocol-layer/patches/"
  fi
}

protocol_deploy_remote() {
  protocol_ssh_run \
    "bash -s -- '${PROTOCOL_REMOTE_DIR}' '${PROTOCOL_PM2_CONFIG}' '${PROTOCOL_TRAFFIC_ENABLED}' '${PROTOCOL_TRAFFIC_RETENTION_MAX_BYTES}' '${PROTOCOL_TRAFFIC_RETENTION_MAX_AGE_MS}' '${PROTOCOL_TRAFFIC_DASHBOARD_PORT}'" \
    <<<"${protocol_remote_deploy_payload}"
}

protocol_verify_health() {
  protocol_ssh_run "pm2 describe armada-protocol-master >/dev/null 2>&1 || pm2 describe protocol-master >/dev/null 2>&1"
  protocol_ssh_run "curl -fsS -m 8 http://127.0.0.1:${PROTOCOL_HEALTH_PORT}/readyz >/dev/null"
  protocol_ssh_run "curl -fsS -m 8 http://127.0.0.1:${PROTOCOL_TRAFFIC_DASHBOARD_PORT}/api/overview >/dev/null"
}

protocol_tail_logs() {
  protocol_ssh_run "pm2 logs --lines 120"
}
