#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE_SCRIPT="${SCRIPT_DIR}/package-prod.sh"
PROD_DIR="${SCRIPT_DIR}/prod"

fail() {
  printf 'FAIL %s\n' "$*" >&2
  exit 1
}

assert_file() {
  [ -f "$1" ] || fail "expected file to exist: $1"
}

assert_executable() {
  [ -x "$1" ] || fail "expected executable file: $1"
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  printf '%s' "${haystack}" | grep -Fq -- "${needle}" || fail "expected output to contain: ${needle}"
}

assert_file_contains() {
  local file="$1"
  local needle="$2"
  assert_file "${file}"
  grep -Fq -- "${needle}" "${file}" || fail "expected ${file} to contain: ${needle}"
}

assert_file_not_contains_regex() {
  local file="$1"
  local pattern="$2"
  assert_file "${file}"
  if grep -Eq -- "${pattern}" "${file}"; then
    fail "expected ${file} not to match regex: ${pattern}"
  fi
}

test_help_and_dry_run_contract() {
  local out
  assert_executable "${PACKAGE_SCRIPT}"
  out="$("${PACKAGE_SCRIPT}" --help)"
  assert_contains "${out}" "armada app + protocol production offline packages"
  assert_contains "${out}" "--version"
  assert_contains "${out}" "--platform"
  assert_contains "${out}" "--app-only"
  assert_contains "${out}" "--protocol-only"

  out="$("${PACKAGE_SCRIPT}" --dry-run --version test-local)"
  assert_contains "${out}" "armada-app-prod-test-local.tar.gz"
  assert_contains "${out}" "armada-protocol-prod-test-local.tar.gz"
  assert_contains "${out}" "[dry-run] no build, docker save, or archive commands executed"
}

test_app_package_templates() {
  assert_file_contains "${PROD_DIR}/app/docker-compose.yml" "image: armada/backend:__VERSION__"
  assert_file_contains "${PROD_DIR}/app/docker-compose.yml" "image: armada/nginx:__VERSION__"
  assert_file_contains "${PROD_DIR}/app/docker-compose.yml" 'APP_TITLE: ${APP_TITLE:-Wheel SaaS}'
  assert_file_contains "${PROD_DIR}/app/docker-compose.yml" 'ARMADA_PROTOCOL_BASE_URL: ${ARMADA_PROTOCOL_BASE_URL}'
  assert_file_contains "${PROD_DIR}/app/docker-compose.yml" 'PROTOCOL_ANDROID_LIFECYCLE_COMMANDS_TOPIC: ${PROTOCOL_ANDROID_LIFECYCLE_COMMANDS_TOPIC:-protocol.android.lifecycle.commands.v1}'
  assert_file_contains "${PROD_DIR}/app/docker-compose.yml" 'PROTOCOL_ANDROID_MESSAGE_COMMANDS_TOPIC: ${PROTOCOL_ANDROID_MESSAGE_COMMANDS_TOPIC:-protocol.android.message.commands.v1}'
  assert_file_contains "${PROD_DIR}/app/docker-compose.yml" 'PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC: ${PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC:-protocol.android.group-join.commands.v1}'
  assert_file_contains "${PROD_DIR}/app/docker-compose.yml" "      IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE: 200"
  assert_file_contains "${PROD_DIR}/app/docker-compose.yml" "      ARMADA_IP_PROXY_UNAVAILABLE_RECHECK_BATCH_SIZE: 200"
  assert_file_contains "${PROD_DIR}/app/.env.example" "APP_TITLE=Wheel SaaS"
  assert_file_contains "${PROD_DIR}/app/.env.example" "DB_URL=jdbc:mysql://"
  assert_file_contains "${PROD_DIR}/app/.env.example" "KAFKA_BROKERS="
  assert_file_contains "${PROD_DIR}/app/.env.example" "ARMADA_PROTOCOL_BASE_URL=http://PROTOCOL_PRIVATE_IP:8080"
  assert_file_contains "${PROD_DIR}/app/.env.example" "PROTOCOL_ANDROID_LIFECYCLE_COMMANDS_TOPIC=protocol.android.lifecycle.commands.v1"
  assert_file_contains "${PROD_DIR}/app/.env.example" "PROTOCOL_ANDROID_MESSAGE_COMMANDS_TOPIC=protocol.android.message.commands.v1"
  assert_file_contains "${PROD_DIR}/app/.env.example" "PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC=protocol.android.group-join.commands.v1"
  assert_file_contains "${SCRIPT_DIR}/nginx.prebuilt.Dockerfile" "render-platform-config.sh"
  assert_file_contains "${SCRIPT_DIR}/nginx.prebuilt.Dockerfile" "PLATFORM_CONFIG_ROOT=/usr/share/nginx/html/saas"
  assert_file_contains "${SCRIPT_DIR}/render-platform-config.sh" "platform-config.template.json"
  assert_file_contains "${PACKAGE_SCRIPT}" "armada-api-deploy.jar"
  assert_file_contains "${SCRIPT_DIR}/backend.prebuilt.Dockerfile" "armada-api-deploy.jar"
  assert_file_not_contains_regex "${PACKAGE_SCRIPT}" 'armada-api-[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT\.jar'
  assert_file_not_contains_regex "${SCRIPT_DIR}/backend.prebuilt.Dockerfile" \
    'armada-api-[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT\.jar'
}

test_protocol_package_templates() {
  local compose="${PROD_DIR}/protocol/docker-compose.yml"
  assert_file_contains "${compose}" "protocol-master:"
  assert_file_contains "${compose}" "protocol-worker-1:"
  assert_file_contains "${compose}" "protocol-worker-2:"
  assert_file_contains "${compose}" "protocol-worker-3:"
  assert_file_contains "${compose}" "protocol-worker-4:"
  assert_file_contains "${compose}" "image: armada/protocol:__VERSION__"
  assert_file_contains "${compose}" 'REDIS_URL: ${REDIS_URL}'
  assert_file_contains "${compose}" 'MYSQL_CONNECTION_URI: ${MYSQL_CONNECTION_URI}'
  assert_file_contains "${compose}" 'KAFKA_BROKERS: ${KAFKA_BROKERS}'
  assert_file_contains "${PROD_DIR}/protocol/.env.example" "PROTOCOL_PUBLIC_HOST=PROTOCOL_PRIVATE_IP"
  assert_file_contains "${PROD_DIR}/protocol/.env.example" "API_KEYS="
}

test_runtime_scripts_are_offline_only() {
  local script
  for script in install.sh rollback.sh status.sh logs.sh; do
    assert_executable "${PROD_DIR}/scripts/${script}"
    assert_file_not_contains_regex "${PROD_DIR}/scripts/${script}" '(^|[[:space:]])(ssh|rsync|scp|git[[:space:]]+clone|curl[[:space:]]+https?://)'
  done
  assert_file_contains "${PROD_DIR}/scripts/install.sh" "docker load -i"
  assert_file_contains "${PROD_DIR}/scripts/install.sh" "docker compose"
  assert_file_contains "${PROD_DIR}/README-prod.md" "生产机器不需要访问外网"
}

test_help_and_dry_run_contract
test_app_package_templates
test_protocol_package_templates
test_runtime_scripts_are_offline_only
printf 'OK package-prod offline deployment tests passed\n'
