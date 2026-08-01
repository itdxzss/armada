#!/usr/bin/env bash

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
  rsync -rltz --delete -e "${ZHUAN_RSYNC_SSH}" \
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
  zhuan_ssh_run \
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
