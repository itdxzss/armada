#!/usr/bin/env bash

zhuan_validate_fleet_inputs() {
  local coordinator_count duplicate_count fleet_counts invalid_count node_count pem_file pem_files

  [ -f "${ZHUAN_FLEET_SCRIPT}" ] || die "找不到 Zhuan fleet 编排脚本: ${ZHUAN_FLEET_SCRIPT}"
  [ -f "${ZHUAN_FLEET_CONFIG}" ] \
    || die "找不到 Zhuan fleet 节点清单: ${ZHUAN_FLEET_CONFIG}"
  [ -d "${ZHUAN_FLEET_KEYS_DIR}" ] \
    || die "找不到 Zhuan fleet 私钥目录: ${ZHUAN_FLEET_KEYS_DIR}"

  fleet_counts="$(awk -F'|' '
      function trim(value) {
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
        return value
      }
      /^[[:space:]]*(#|$)/ { next }
      {
        label = trim($1)
        user = trim($2)
        host = trim($3)
        pem = trim($4)
        subdir = trim($5)
        if (NF < 4 || label == "" || user == "" || host == "" || pem == "") {
          invalid++
          next
        }
        if (seen[label]++) duplicate++
        if (subdir == "coordinator") coordinator++
        else if (subdir == "" || subdir == "node") nodes++
        else invalid++
      }
      END { printf "%d|%d|%d|%d\n", coordinator, nodes, invalid, duplicate }
    ' "${ZHUAN_FLEET_CONFIG}")"
  IFS='|' read -r coordinator_count node_count invalid_count duplicate_count <<<"${fleet_counts}"

  [ "${invalid_count}" = 0 ] || die "Zhuan fleet 节点清单存在无效条目"
  [ "${duplicate_count}" = 0 ] || die "Zhuan fleet 节点清单存在重复 label"
  [ "${coordinator_count}" = 1 ] \
    || die "Zhuan fleet 必须且只能配置 1 个 coordinator"
  [ "${node_count}" = "${ZHUAN_FLEET_EXPECTED_NODES}" ] \
    || die "Zhuan fleet 节点数不匹配: expected=${ZHUAN_FLEET_EXPECTED_NODES}, actual=${node_count}"

  pem_files="$(awk -F'|' '
    function trim(value) {
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      return value
    }
    !/^[[:space:]]*(#|$)/ { print trim($4) }
  ' "${ZHUAN_FLEET_CONFIG}")"
  while IFS= read -r pem_file; do
    case "${pem_file}" in
      ""|*/*|*..*) die "Zhuan fleet 节点清单包含不安全的私钥文件名" ;;
    esac
    [ -f "${ZHUAN_FLEET_KEYS_DIR}/${pem_file}" ] \
      || die "Zhuan fleet 节点私钥不完整"
  done <<<"${pem_files}"
}

zhuan_fleet_run() {
  LC_ALL=C \
  REPO="${ZHUAN_DIR}" \
  KEYS_DIR="${ZHUAN_FLEET_KEYS_DIR}" \
  NODES_CONF="${ZHUAN_FLEET_CONFIG}" \
    bash "${ZHUAN_FLEET_SCRIPT}" "$@"
}

zhuan_fleet_coordinator_ssh_run() {
  local coordinator coordinator_host coordinator_key coordinator_pem coordinator_user rc temporary_key

  coordinator="$(awk -F'|' '
      function trim(value) {
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
        return value
      }
      !/^[[:space:]]*(#|$)/ && trim($5) == "coordinator" {
        printf "%s|%s|%s\n", trim($2), trim($3), trim($4)
        exit
      }
    ' "${ZHUAN_FLEET_CONFIG}")"
  IFS='|' read -r coordinator_user coordinator_host coordinator_pem <<<"${coordinator}"
  coordinator_key="${ZHUAN_FLEET_KEYS_DIR}/${coordinator_pem}"
  temporary_key="$(mktemp "${TMPDIR:-/tmp}/armada-zhuan-coordinator-key.XXXXXX")"
  cp "${coordinator_key}" "${temporary_key}"
  chmod 600 "${temporary_key}"

  rc=0
  ssh \
    -i "${temporary_key}" \
    -o BatchMode=yes \
    -o ConnectTimeout=15 \
    -o StrictHostKeyChecking=accept-new \
    "${coordinator_user}@${coordinator_host}" "$@" || rc=$?
  rm -f -- "${temporary_key}"
  return "${rc}"
}

zhuan_verify_fleet_health() {
  local body online_count

  body="$(zhuan_fleet_coordinator_ssh_run \
    "curl -fsS -m 8 'http://127.0.0.1:${ZHUAN_FLEET_COORDINATOR_PORT}/admin/nodes'")" \
    || die "Zhuan fleet coordinator 健康检查失败"
  grep -Eq '"success"[[:space:]]*:[[:space:]]*true' <<<"${body}" \
    || die "Zhuan fleet coordinator 返回失败状态"
  online_count="$(
    { grep -oE '"status"[[:space:]]*:[[:space:]]*"online"' <<<"${body}" || true; } \
      | wc -l \
      | tr -d '[:space:]'
  )"
  [ "${online_count}" = "${ZHUAN_FLEET_EXPECTED_NODES}" ] \
    || die "Zhuan fleet 在线节点数不匹配: expected=${ZHUAN_FLEET_EXPECTED_NODES}, actual=${online_count}"
}

zhuan_check_connectivity() {
  case "${ZHUAN_DEPLOY_MODE}" in
    fleet)
      zhuan_fleet_run --check all >/dev/null
      ;;
    single)
      zhuan_ssh_run true
      ;;
    *) die "未知 Zhuan 部署模式: ${ZHUAN_DEPLOY_MODE}" ;;
  esac
}

zhuan_deploy_selected() {
  case "${ZHUAN_DEPLOY_MODE}" in
    fleet)
      info "并发部署 Zhuan fleet: coordinator + ${ZHUAN_FLEET_EXPECTED_NODES} 台 node..."
      armada_capture_docker_build_output "Zhuan fleet" zhuan_fleet_run all
      info "检查 coordinator 和 ${ZHUAN_FLEET_EXPECTED_NODES} 台 Zhuan 节点..."
      zhuan_verify_fleet_health
      ;;
    single)
      info "准备并检查 Zhuan 远端..."
      zhuan_prepare_remote
      ok "Zhuan 远端运行配置已就绪"
      info "同步 Zhuan 源码..."
      zhuan_sync_source
      info "构建并启动 Zhuan 协议..."
      zhuan_deploy_remote
      info "检查 Zhuan 容器和 API..."
      zhuan_verify_health
      ;;
    *) die "未知 Zhuan 部署模式: ${ZHUAN_DEPLOY_MODE}" ;;
  esac
}

zhuan_remote_required_files_check_payload='
set -eu
remote_dir="$1"
compose_file="$2"
expected_schema="$3"
expected_prefix="$4"
config_file="${remote_dir}/deploy/configs/prod_configs.toml"
test -f "${remote_dir}/deploy/.env" || { echo "远端缺少 Zhuan 配置: ${remote_dir}/deploy/.env" >&2; exit 40; }
test -f "${config_file}" || { echo "远端缺少 Zhuan 配置: ${config_file}" >&2; exit 41; }
test -f "${remote_dir}/deploy/${compose_file}" || { echo "远端缺少 Zhuan Compose: ${compose_file}" >&2; exit 43; }

toml_string() {
  section="$1"
  key="$2"
  file="$3"
  awk -v wanted_section="${section}" -v wanted_key="${key}" '\''
    function trim(value) {
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      return value
    }
    $0 ~ "^[[:space:]]*\\[" wanted_section "\\][[:space:]]*$" { inside = 1; next }
    inside && $0 ~ "^[[:space:]]*\\[" { exit }
    inside {
      line = $0
      sub(/[[:space:]]*#.*/, "", line)
      equals = index(line, "=")
      if (equals == 0) next
      name = trim(substr(line, 1, equals - 1))
      if (name != wanted_key) next
      value = trim(substr(line, equals + 1))
      if (value ~ /^".*"$/) value = substr(value, 2, length(value) - 2)
      print value
      exit
    }
  '\'' "${file}"
}

actual_schema="$(toml_string mysql name "${config_file}")"
actual_prefix="$(toml_string redis keyprefix "${config_file}")"
[ "${actual_schema}" = "${expected_schema}" ] || { echo "Zhuan MySQL schema 与环境档案不一致" >&2; exit 44; }
[ "${actual_prefix}" = "${expected_prefix}" ] || { echo "Zhuan Redis prefix 与环境档案不一致" >&2; exit 45; }
'

zhuan_remote_deploy_payload='
set -eu
remote_dir="$1"
compose_file="$2"
start_services="$3"
cd "${remote_dir}/deploy"
sudo docker compose -f "${compose_file}" config --quiet
sudo docker compose -f "${compose_file}" build whatsapp-android-zhuan
if [ -n "${start_services}" ]; then
  # Values come from the fixed repository profile and are validated locally.
  sudo docker compose -f "${compose_file}" up -d ${start_services}
fi
sudo docker compose -f "${compose_file}" run --rm --interactive=false whatsapp-android-zhuan /app/whatsapp-migrate -env prod
sudo docker compose -f "${compose_file}" up -d --force-recreate whatsapp-android-zhuan
'

zhuan_remote_health_check_payload='
set -eu
remote_dir="$1"
compose_file="$2"
health_services="$3"
http_port="$4"
environment_id="$5"
cd "${remote_dir}/deploy"
if [ "${environment_id}" = perf2 ] && sudo docker ps -a --format "{{.Names}}" | grep -Fx redis-zhuan >/dev/null; then
  echo "检测到 perf2 禁止的本地 redis-zhuan 容器" >&2
  exit 46
fi
for container in ${health_services}; do
  attempt=1
  while :; do
    state="$(sudo docker inspect -f "{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}" "${container}" 2>/dev/null || true)"
    if [ "${state}" = "running/healthy" ]; then
      break
    fi
    if [ "${attempt}" -ge 24 ]; then
      echo "Zhuan 容器未在时限内就绪: ${container}, state=${state:-missing}" >&2
      exit 42
    fi
    sleep 5
    attempt=$((attempt + 1))
  done
done
curl -fsS -m 8 "http://127.0.0.1:${http_port}/swagger/index.html" >/dev/null
'

zhuan_prepare_remote() {
  zhuan_ssh_run "mkdir -p '${ZHUAN_REMOTE_DIR}'"
  zhuan_ssh_run \
    "bash -s -- '${ZHUAN_REMOTE_DIR}' '${ZHUAN_COMPOSE_FILE}' '${EXPECTED_ZHUAN_DB_SCHEMA}' '${EXPECTED_ZHUAN_REDIS_PREFIX}'" \
    <<<"${zhuan_remote_required_files_check_payload}"
}

zhuan_sync_source() {
  armada_rsync "Zhuan source" -rltz --delete -e "${ZHUAN_RSYNC_SSH}" \
    --exclude='/.git/' \
    --exclude='/.idea/' \
    --exclude='/.gocache/' \
    --exclude='/.gomodcache/' \
    --exclude='/docs/' \
    --exclude='/main' \
    --exclude='/ws-go' \
    --exclude='/server' \
    --exclude='/migrate' \
    --exclude='/mock-callback' \
    --exclude=deploy/.env \
    --exclude=deploy/configs/prod_configs.toml \
    --exclude=deploy/logs/ \
    --exclude=deploy/callback-logs/ \
    --exclude=logs/ \
    --exclude='/.env' \
    --exclude='/.env.*' \
    --exclude='configs/*.toml' \
    --exclude='*.pem' \
    --exclude='*.key' \
    --exclude='*.log' \
    --exclude='*.zip' \
    --exclude='*.tar' \
    --exclude='*.tar.gz' \
    --exclude='*.tgz' \
    --exclude='*.gz' \
    --exclude='*.bz2' \
    --exclude='*.xz' \
    --exclude='*.zst' \
    --exclude='*.7z' \
    --exclude='*.rar' \
    "${ZHUAN_DIR}/" \
    "${ZHUAN_SSH_USER}@${ZHUAN_SSH_HOST}:${ZHUAN_REMOTE_DIR}/"
}

zhuan_deploy_remote() {
  armada_capture_docker_build_output "Zhuan image" zhuan_ssh_run \
    "bash -s -- '${ZHUAN_REMOTE_DIR}' '${ZHUAN_COMPOSE_FILE}' '${ZHUAN_START_SERVICES}'" \
    <<<"${zhuan_remote_deploy_payload}"
}

zhuan_verify_health() {
  zhuan_ssh_run \
    "bash -s -- '${ZHUAN_REMOTE_DIR}' '${ZHUAN_COMPOSE_FILE}' '${ZHUAN_HEALTH_SERVICES}' '${ZHUAN_HTTP_PORT}' '${ENV_ID}'" \
    <<<"${zhuan_remote_health_check_payload}"
}

zhuan_tail_logs() {
  zhuan_ssh_run "cd '${ZHUAN_REMOTE_DIR}/deploy' && sudo docker compose -f '${ZHUAN_COMPOSE_FILE}' logs -f --tail 120 whatsapp-android-zhuan"
}
