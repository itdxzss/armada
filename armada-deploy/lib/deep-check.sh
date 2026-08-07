#!/usr/bin/env bash

deep_armada_check_payload='
set -eu
remote_dir="$1"
expected_schema="$2"
expected_android_url="$3"
expected_topic_prefix="$4"
cd "${remote_dir}"
test -f .env || { echo "Armada 远端缺少 .env" >&2; exit 60; }
env_value() {
  sed -n "s/^$1=//p" .env | tail -n 1
}
db_url="$(env_value DB_URL)"
case "${db_url}" in
  */${expected_schema}|*/${expected_schema}\?*) ;;
  *) echo "Armada DB schema 与环境档案不一致" >&2; exit 61 ;;
esac
if [ -n "${expected_android_url}" ]; then
  [ "$(env_value PROTOCOL_ANDROID_BASE_URL)" = "${expected_android_url}" ] || {
    echo "Armada Android base URL 与环境档案不一致" >&2
    exit 62
  }
fi
for topic_var in \
  PROTOCOL_ANDROID_LIFECYCLE_COMMANDS_TOPIC \
  PROTOCOL_ANDROID_MESSAGE_COMMANDS_TOPIC \
  PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC; do
  topic_value="$(env_value "${topic_var}")"
  case "${topic_value}" in
    "${expected_topic_prefix}"*) ;;
    *) echo "Armada Android topic prefix 与环境档案不一致: ${topic_var}" >&2; exit 63 ;;
  esac
done
[ "$(docker inspect -f "{{.State.Status}}" armada-backend)" = running ] || {
  echo "Armada backend 未运行" >&2
  exit 64
}
runtime_env="$(docker inspect -f "{{range .Config.Env}}{{println .}}{{end}}" armada-backend)"
if [ -n "${expected_android_url}" ]; then
  printf "%s\n" "${runtime_env}" | grep -Fx "PROTOCOL_ANDROID_BASE_URL=${expected_android_url}" >/dev/null || {
    echo "Armada backend 未取得 Android base URL" >&2
    exit 65
  }
fi
for topic_var in \
  PROTOCOL_ANDROID_LIFECYCLE_COMMANDS_TOPIC \
  PROTOCOL_ANDROID_MESSAGE_COMMANDS_TOPIC \
  PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC; do
  printf "%s\n" "${runtime_env}" | grep -F "${topic_var}=${expected_topic_prefix}" >/dev/null || {
    echo "Armada backend Android topic 未生效: ${topic_var}" >&2
    exit 66
  }
done
port="$(env_value ARMADA_HTTP_PORT)"
port="${port:-18080}"
curl -fsS -m 8 "http://127.0.0.1:${port}/" >/dev/null
body="$(curl -fsS -m 8 "http://127.0.0.1:${port}/api/account-groups" || true)"
printf "%s" "${body}" | grep -Eq "\"code\"[[:space:]]*:[[:space:]]*(40101|0|40001)"
'

deep_protocol_check_payload='
set -eu
remote_dir="$1"
health_port="$2"
cd "${remote_dir}/protocol-layer"
test -f .env || { echo "协议层远端缺少 .env" >&2; exit 70; }
test -f package.json || { echo "协议层远端缺少 package.json" >&2; exit 71; }
case "$(node --version)" in
  v24.*) ;;
  *) echo "协议层远端 Node.js 不是 24.x" >&2; exit 72 ;;
esac
pm2 jlist | node -e "
let input = \"\"
process.stdin.on(\"data\", chunk => { input += chunk })
process.stdin.on(\"end\", () => {
  const apps = JSON.parse(input).filter(app =>
    /^(?:armada-)?protocol-(?:master|worker-[1-4])$/.test(app.name ?? \"\")
  )
  const invalid = apps.filter(app =>
    app.pm2_env?.status !== \"online\" ||
    !String(app.pm2_env?.node_version ?? \"\").startsWith(\"24.\")
  )
  if (apps.length !== 5 || invalid.length > 0) process.exit(73)
})
"
curl -fsS -m 8 "http://127.0.0.1:${health_port}/readyz" >/dev/null
'

deep_zhuan_check_payload='
set -eu
remote_dir="$1"
compose_file="$2"
expected_topics="$3"
expected_groups="$4"
health_services="$5"
environment_id="$6"
config_file="${remote_dir}/deploy/configs/prod_configs.toml"
cd "${remote_dir}/deploy"
test -f "${config_file}" || { echo "Zhuan 远端缺少 prod_configs.toml" >&2; exit 80; }
test -f "${compose_file}" || { echo "Zhuan 远端缺少环境 Compose" >&2; exit 81; }
test -f certs/rds-global-bundle.pem || { echo "Zhuan 远端缺少 RDS CA" >&2; exit 82; }
toml_string() {
  section="$1"
  key="$2"
  file="$3"
  sed -n "/^[[:space:]]*\\[${section}\\][[:space:]]*$/,/^[[:space:]]*\\[/p" "${file}" \
    | sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" \
    | head -n 1
}
csv_item() {
  printf "%s" "$1" | cut -d, -f"$2"
}
topic_name() {
  printf "%s" "$1" | sed "s/=[0-9][0-9]*$//"
}
topic_1="$(topic_name "$(csv_item "${expected_topics}" 1)")"
topic_2="$(topic_name "$(csv_item "${expected_topics}" 2)")"
topic_3="$(topic_name "$(csv_item "${expected_topics}" 3)")"
group_1="$(csv_item "${expected_groups}" 1)"
group_2="$(csv_item "${expected_groups}" 2)"
group_3="$(csv_item "${expected_groups}" 3)"
if [ -n "${expected_topics}" ]; then
  [ "$(toml_string kafka lifecyclecommandtopic "${config_file}")" = "${topic_1}" ] || { echo "Zhuan lifecycle topic 不匹配" >&2; exit 83; }
  [ "$(toml_string kafka messagecommandtopic "${config_file}")" = "${topic_2}" ] || { echo "Zhuan message topic 不匹配" >&2; exit 84; }
  [ "$(toml_string kafka groupjoincommandtopic "${config_file}")" = "${topic_3}" ] || { echo "Zhuan group topic 不匹配" >&2; exit 85; }
fi
if [ -n "${expected_groups}" ]; then
  [ "$(toml_string kafka lifecycleconsumergroup "${config_file}")" = "${group_1}" ] || { echo "Zhuan lifecycle group 不匹配" >&2; exit 86; }
  [ "$(toml_string kafka messageconsumergroup "${config_file}")" = "${group_2}" ] || { echo "Zhuan message group 不匹配" >&2; exit 87; }
  [ "$(toml_string kafka groupjoinconsumergroup "${config_file}")" = "${group_3}" ] || { echo "Zhuan group group 不匹配" >&2; exit 88; }
fi
sudo docker compose -f "${compose_file}" config --quiet
if [ "${environment_id}" = perf2 ] && sudo docker ps -a --format "{{.Names}}" | grep -Fx redis-zhuan >/dev/null; then
  echo "perf2 存在禁止的本地 redis-zhuan" >&2
  exit 89
fi
for container in ${health_services}; do
  state="$(sudo docker inspect -f "{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}" "${container}" 2>/dev/null || true)"
  [ "${state}" = running/healthy ] || { echo "Zhuan 容器未健康: ${container}" >&2; exit 90; }
done
'

deep_check_validate_targets() {
  validate_remote_dir "Armada" "${REMOTE_DIR}"
  validate_ssh_identity "Armada" "${SSH_HOST}" "${SSH_USER}"
  require_ssh_key " Armada" "${SSH_KEY}"
  validate_remote_dir "协议" "${PROTOCOL_REMOTE_DIR}"
  validate_ssh_identity "协议" "${PROTOCOL_SSH_HOST}" "${PROTOCOL_SSH_USER}"
  require_ssh_key "协议" "${PROTOCOL_SSH_KEY}"
  if [ "${PROTOCOL_TRANSPORT}" = jump ]; then
    validate_ssh_identity "协议跳板" "${PROTOCOL_JUMP_HOST}" "${PROTOCOL_JUMP_USER}"
    require_ssh_key "协议跳板" "${PROTOCOL_JUMP_KEY}"
  fi
  if [ "${ZHUAN_DEPLOY_MODE}" = fleet ]; then
    zhuan_validate_fleet_inputs
  else
    validate_remote_dir "Zhuan" "${ZHUAN_REMOTE_DIR}"
    validate_ssh_identity "Zhuan" "${ZHUAN_SSH_HOST}" "${ZHUAN_SSH_USER}"
    require_ssh_key " Zhuan" "${ZHUAN_SSH_KEY}"
  fi
  [ -f "${SCRIPT_DIR}/lib/kafka-check.mjs" ] || die "缺少 Kafka 只读检查器"
}

deep_check_armada() {
  info "[check] Armada"
  ssh_run \
    "bash -s -- '${REMOTE_DIR}' '${EXPECTED_ARMADA_DB_SCHEMA}' '${EXPECTED_ANDROID_BASE_URL}' '${EXPECTED_ANDROID_TOPIC_PREFIX}'" \
    <<<"${deep_armada_check_payload}"
  ok "[check] Armada"
}

deep_check_protocol() {
  info "[check] Baileys"
  protocol_ssh_run \
    "bash -s -- '${PROTOCOL_REMOTE_DIR}' '${PROTOCOL_HEALTH_PORT}'" \
    <<<"${deep_protocol_check_payload}"
  ok "[check] Baileys"
}

deep_check_kafka() {
  local expected_groups_quoted expected_topics_quoted remote_dir_quoted
  if [ -z "${EXPECTED_KAFKA_TOPICS}" ] && [ -z "${EXPECTED_KAFKA_GROUPS}" ]; then
    info "[check] Kafka exact metadata: SKIPPED"
    return 0
  fi
  info "[check] Kafka"
  expected_topics_quoted="$(shell_single_quote "${EXPECTED_KAFKA_TOPICS}")"
  expected_groups_quoted="$(shell_single_quote "${EXPECTED_KAFKA_GROUPS}")"
  remote_dir_quoted="$(shell_single_quote "${PROTOCOL_REMOTE_DIR}")"
  protocol_ssh_run \
    "cd '${remote_dir_quoted}/protocol-layer' && test -f .env && set -a && . ./.env && set +a && EXPECTED_KAFKA_TOPICS='${expected_topics_quoted}' EXPECTED_KAFKA_GROUPS='${expected_groups_quoted}' node --input-type=module -" \
    <"${SCRIPT_DIR}/lib/kafka-check.mjs"
  ok "[check] Kafka"
}

deep_check_zhuan() {
  info "[check] Zhuan"
  if [ "${ZHUAN_DEPLOY_MODE}" = fleet ]; then
    zhuan_verify_fleet_health
  else
    zhuan_ssh_run \
      "bash -s -- '${ZHUAN_REMOTE_DIR}' '${ZHUAN_COMPOSE_FILE}' '${EXPECTED_KAFKA_TOPICS}' '${EXPECTED_KAFKA_GROUPS}' '${ZHUAN_HEALTH_SERVICES}' '${ENV_ID}'" \
      <<<"${deep_zhuan_check_payload}"
  fi
  ok "[check] Zhuan"
}

deep_check_cross_component() {
  local android_url_quoted protocol_host_quoted public_url_quoted
  info "[check] Cross-component"
  protocol_host_quoted="$(shell_single_quote "${PROTOCOL_SSH_HOST}")"
  ssh_run "curl -fsS -m 8 'http://${protocol_host_quoted}:${PROTOCOL_HEALTH_PORT}/readyz' >/dev/null"
  if [ -n "${EXPECTED_ANDROID_BASE_URL}" ]; then
    android_url_quoted="$(shell_single_quote "${EXPECTED_ANDROID_BASE_URL}")"
    if [ "${ZHUAN_DEPLOY_MODE}" = fleet ]; then
      ssh_run "curl -fsS -m 8 '${android_url_quoted}/healthz' >/dev/null"
    else
      ssh_run "curl -fsS -m 8 '${android_url_quoted}/swagger/index.html' >/dev/null"
    fi
  fi
  public_url_quoted="$(shell_single_quote "${PUBLIC_URL}")"
  curl -fsS -m 8 "${public_url_quoted}" >/dev/null
  ok "[check] Cross-component"
}

run_deep_check() {
  deep_check_validate_targets
  printf '深度检查环境: %s (%s)\n' "${ENV_ID}" "${PROFILE_APP_TITLE}"
  deep_check_armada
  deep_check_protocol
  deep_check_kafka
  deep_check_zhuan
  deep_check_cross_component
  ok "只读深度检查通过"
}
