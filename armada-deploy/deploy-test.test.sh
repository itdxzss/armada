#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/deploy-test.sh"
WIN_SCRIPT="${SCRIPT_DIR}/deploy-test-win.sh"
ARTIFACT_LIB="${SCRIPT_DIR}/lib/artifact.sh"
COMMON_LIB="${SCRIPT_DIR}/lib/common.sh"

fail() {
  printf 'FAIL %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  grep -Fq -- "${needle}" <<<"${haystack}" || fail "expected output to contain: ${needle}"
}

assert_not_contains() {
  local haystack="$1"
  local needle="$2"
  if grep -Fq -- "${needle}" <<<"${haystack}"; then
    fail "expected output not to contain: ${needle}"
  fi
}

test_assert_contains_handles_large_haystack() {
  local large_haystack
  large_haystack="$(awk 'BEGIN { print "needle"; for (i = 0; i < 20000; i++) print "padding" }')"
  assert_contains "${large_haystack}" "needle"
}

test_deployment_metrics_summarize_stage_duration() {
  local metrics_log out
  metrics_log="$(mktemp)"
  [ -f "${COMMON_LIB}" ] || fail "expected deployment common library: ${COMMON_LIB}"
  # shellcheck source=/dev/null
  . "${COMMON_LIB}"
  armada_init_colors

  armada_metrics_init "${metrics_log}"
  armada_metrics_record_stage "backend-build" 12 0
  out="$(print_deployment_metrics_summary)"
  rm -f "${metrics_log}"

  assert_contains "${out}" "阶段 backend-build: 12s (SUCCESS)"
}

test_deployment_metrics_summarize_rsync_transfer() {
  local metrics_log out rsync_log
  metrics_log="$(mktemp)"
  rsync_log="$(mktemp)"
  # shellcheck source=/dev/null
  . "${COMMON_LIB}"
  armada_init_colors

  cat >"${rsync_log}" <<'STATS'
Number of files: 18 (reg: 16, dir: 2)
Number of regular files transferred: 3
Total transferred file size: 2,097,152 bytes
STATS
  armada_metrics_init "${metrics_log}"
  armada_metrics_record_rsync_stats "backend-jar" "${rsync_log}"
  out="$(print_deployment_metrics_summary)"
  rm -f "${metrics_log}" "${rsync_log}"

  assert_contains "${out}" "同步 backend-jar: scanned=18 changed=3 transferred=2.0 MiB"
}

test_deployment_metrics_summarize_docker_cache() {
  local build_log metrics_log out
  build_log="$(mktemp)"
  metrics_log="$(mktemp)"
  # shellcheck source=/dev/null
  . "${COMMON_LIB}"
  armada_init_colors

  cat >"${build_log}" <<'BUILD'
#1 [internal] load build definition from Dockerfile
#1 DONE 0.0s
#2 [internal] load metadata for docker.io/library/alpine:3.20
#2 CACHED
#3 [1/2] FROM docker.io/library/alpine:3.20
#3 CACHED
#4 [2/2] COPY app.jar /app/
#4 DONE 0.1s
BUILD
  armada_metrics_init "${metrics_log}"
  armada_metrics_record_docker_cache "zhuan-image" "${build_log}"
  out="$(print_deployment_metrics_summary)"
  rm -f "${build_log}" "${metrics_log}"

  assert_contains "${out}" "Docker zhuan-image: cache=2/4 (50%)"
}

test_deployment_metrics_summarize_zero_docker_cache_hits() {
  local build_log metrics_log out
  build_log="$(mktemp)"
  metrics_log="$(mktemp)"
  # shellcheck source=/dev/null
  . "${COMMON_LIB}"
  armada_init_colors

  cat >"${build_log}" <<'BUILD'
#1 [internal] load build definition from Dockerfile
#1 DONE 0.0s
#2 [internal] load metadata for docker.io/library/debian:bookworm-slim
#2 DONE 1.0s
BUILD
  armada_metrics_init "${metrics_log}"
  armada_metrics_record_docker_cache "zhuan-image" "${build_log}"
  out="$(print_deployment_metrics_summary)"
  rm -f "${build_log}" "${metrics_log}"

  assert_contains "${out}" "Docker zhuan-image: cache=0/2 (0%)"
}

test_deployment_metrics_handles_zero_docker_steps() {
  local metrics_log out
  metrics_log="$(mktemp)"
  # shellcheck source=/dev/null
  . "${COMMON_LIB}"
  armada_init_colors

  armada_metrics_init "${metrics_log}"
  printf 'docker\tzhuan-image\t0\t0\n' >>"${metrics_log}"
  out="$(print_deployment_metrics_summary)"
  rm -f "${metrics_log}"

  assert_contains "${out}" "Docker zhuan-image: cache=0/0 (n/a)"
}

test_backend_jar_resolution_requires_one_executable_jar() {
  local fixture resolved
  fixture="$(mktemp -d)"
  [ -f "${ARTIFACT_LIB}" ] || fail "expected artifact resolver: ${ARTIFACT_LIB}"
  # shellcheck source=/dev/null
  . "${ARTIFACT_LIB}"

  mkdir -p "${fixture}/single" "${fixture}/empty" "${fixture}/multiple"
  : >"${fixture}/single/armada-api-1.0.2-SNAPSHOT.jar"
  : >"${fixture}/single/armada-api-1.0.2-SNAPSHOT.jar.original"
  resolved="$(armada_resolve_backend_jar "${fixture}/single")"
  [ "${resolved}" = "${fixture}/single/armada-api-1.0.2-SNAPSHOT.jar" ] \
    || fail "unexpected resolved jar: ${resolved}"

  if armada_resolve_backend_jar "${fixture}/empty" >/dev/null 2>&1; then
    fail "expected empty target to fail jar resolution"
  fi
  : >"${fixture}/multiple/armada-api-1.0.2-SNAPSHOT.jar"
  : >"${fixture}/multiple/extra.jar"
  if armada_resolve_backend_jar "${fixture}/multiple" >/dev/null 2>&1; then
    fail "expected multiple jars to fail jar resolution"
  fi
  rm -rf "${fixture}"
}

test_backend_deploy_uses_stable_staging_name() {
  local content
  content="$(cat \
    "${SCRIPT_DIR}/deploy-test.sh" \
    "${SCRIPT_DIR}/deploy-test-win.sh" \
    "${SCRIPT_DIR}/backend.prebuilt.Dockerfile")"
  assert_contains "${content}" "armada-api-deploy.jar"
  assert_not_contains "${content}" "armada-api-1.0.0-SNAPSHOT.jar"
}

setup_zhuan_command_fixture() {
  ZHUAN_FIXTURE_ROOT="$(mktemp -d)"
  ZHUAN_FIXTURE_DIR="${ZHUAN_FIXTURE_ROOT}/zhuan source"
  ZHUAN_FIXTURE_BIN="${ZHUAN_FIXTURE_ROOT}/bin"
  ZHUAN_FIXTURE_KEY="${ZHUAN_FIXTURE_ROOT}/key with space.pem"
  ZHUAN_FIXTURE_FLEET_KEYS_DIR="${ZHUAN_FIXTURE_ROOT}/fleet keys"
  ZHUAN_FIXTURE_FLEET_CONFIG="${ZHUAN_FIXTURE_DIR}/deploy/fleet/fleet-nodes.conf"
  ZHUAN_FIXTURE_COMMAND_LOG="${ZHUAN_FIXTURE_ROOT}/commands.log"
  ZHUAN_FIXTURE_PAYLOAD_LOG="${ZHUAN_FIXTURE_ROOT}/payloads.log"

  mkdir -p \
    "${ZHUAN_FIXTURE_DIR}/deploy/fleet" \
    "${ZHUAN_FIXTURE_BIN}" \
    "${ZHUAN_FIXTURE_FLEET_KEYS_DIR}"
  : >"${ZHUAN_FIXTURE_DIR}/go.mod"
  : >"${ZHUAN_FIXTURE_DIR}/go.sum"
  : >"${ZHUAN_FIXTURE_DIR}/.dockerignore"
  : >"${ZHUAN_FIXTURE_DIR}/deploy/Dockerfile"
  : >"${ZHUAN_FIXTURE_DIR}/deploy/docker-compose.yml"
  : >"${ZHUAN_FIXTURE_DIR}/deploy/docker-compose.perf.yml"
  : >"${ZHUAN_FIXTURE_KEY}"
  : >"${ZHUAN_FIXTURE_COMMAND_LOG}"
  : >"${ZHUAN_FIXTURE_PAYLOAD_LOG}"
  chmod 600 "${ZHUAN_FIXTURE_KEY}"

  cat >"${ZHUAN_FIXTURE_FLEET_CONFIG}" <<'CONF'
coordinator|tester|127.0.0.1|coordinator.pem|coordinator
node1|tester|127.0.0.2|node1.pem|node
node2|tester|127.0.0.3|node2.pem|node
node3|tester|127.0.0.4|node3.pem|node
CONF
  for fleet_key in coordinator node1 node2 node3; do
    : >"${ZHUAN_FIXTURE_FLEET_KEYS_DIR}/${fleet_key}.pem"
    chmod 600 "${ZHUAN_FIXTURE_FLEET_KEYS_DIR}/${fleet_key}.pem"
  done

  cat >"${ZHUAN_FIXTURE_DIR}/deploy/fleet/deploy-local.sh" <<'STUB'
#!/usr/bin/env bash
set -eu
{
  printf 'FLEET <REPO=%s> <KEYS_DIR=%s> <NODES_CONF=%s>' "${REPO}" "${KEYS_DIR}" "${NODES_CONF}"
  for arg in "$@"; do
    printf ' <%s>' "${arg}"
  done
  printf '\n'
} >>"${ZHUAN_TEST_COMMAND_LOG}"
STUB
  chmod +x "${ZHUAN_FIXTURE_DIR}/deploy/fleet/deploy-local.sh"
  git -C "${ZHUAN_FIXTURE_DIR}" init -q
  git -C "${ZHUAN_FIXTURE_DIR}" add .
  git -C "${ZHUAN_FIXTURE_DIR}" -c user.name=Test -c user.email=test@example.invalid commit -qm fixture

  cat >"${ZHUAN_FIXTURE_BIN}/ssh" <<'STUB'
#!/usr/bin/env bash
set -eu
{
  printf 'SSH'
  for arg in "$@"; do
    printf ' <%s>' "${arg}"
  done
  printf '\n'
} >>"${ZHUAN_TEST_COMMAND_LOG}"

last_arg=""
for arg in "$@"; do
  last_arg="${arg}"
done
case "${last_arg}" in
  *"/admin/nodes"*)
    printf '%s\n' '{"success":true,"data":[{"id":"1","status":"online"},{"id":"2","status":"online"},{"id":"3","status":"online"}]}'
    ;;
  *"bash -s --"*)
    payload="$(cat)"
    printf '%s\n' "${payload}" >>"${ZHUAN_TEST_PAYLOAD_LOG}"
    if [ -n "${ZHUAN_TEST_FAIL_PAYLOAD:-}" ] && grep -Fq -- "${ZHUAN_TEST_FAIL_PAYLOAD}" <<<"${payload}"; then
      exit 71
    fi
    ;;
esac
STUB

  cat >"${ZHUAN_FIXTURE_BIN}/rsync" <<'STUB'
#!/usr/bin/env bash
set -eu
{
  printf 'RSYNC'
  for arg in "$@"; do
    printf ' <%s>' "${arg}"
  done
  printf '\n'
} >>"${ZHUAN_TEST_COMMAND_LOG}"
STUB
  chmod +x "${ZHUAN_FIXTURE_BIN}/ssh" "${ZHUAN_FIXTURE_BIN}/rsync"
}

cleanup_zhuan_command_fixture() {
  rm -rf "${ZHUAN_FIXTURE_ROOT}"
}

setup_protocol_command_fixture() {
  PROTOCOL_FIXTURE_ROOT="$(mktemp -d)"
  PROTOCOL_FIXTURE_DIR="${PROTOCOL_FIXTURE_ROOT}/protocol source"
  PROTOCOL_FIXTURE_BIN="${PROTOCOL_FIXTURE_ROOT}/bin"
  PROTOCOL_FIXTURE_KEY="${PROTOCOL_FIXTURE_ROOT}/target key.pem"
  PROTOCOL_FIXTURE_JUMP_KEY="${PROTOCOL_FIXTURE_ROOT}/jump key.pem"
  PROTOCOL_FIXTURE_COMMAND_LOG="${PROTOCOL_FIXTURE_ROOT}/commands.log"

  mkdir -p \
    "${PROTOCOL_FIXTURE_DIR}/protocol-layer/src" \
    "${PROTOCOL_FIXTURE_DIR}/protocol-layer/deploy" \
    "${PROTOCOL_FIXTURE_DIR}/openapi" \
    "${PROTOCOL_FIXTURE_BIN}"
  : >"${PROTOCOL_FIXTURE_DIR}/protocol-layer/package.json"
  : >"${PROTOCOL_FIXTURE_DIR}/protocol-layer/package-lock.json"
  : >"${PROTOCOL_FIXTURE_DIR}/protocol-layer/tsconfig.json"
  : >"${PROTOCOL_FIXTURE_KEY}"
  : >"${PROTOCOL_FIXTURE_JUMP_KEY}"
  : >"${PROTOCOL_FIXTURE_COMMAND_LOG}"
  chmod 600 "${PROTOCOL_FIXTURE_KEY}" "${PROTOCOL_FIXTURE_JUMP_KEY}"
  git -C "${PROTOCOL_FIXTURE_DIR}" init -q
  git -C "${PROTOCOL_FIXTURE_DIR}" add .
  git -C "${PROTOCOL_FIXTURE_DIR}" -c user.name=Test -c user.email=test@example.invalid commit -qm fixture

  cat >"${PROTOCOL_FIXTURE_BIN}/ssh" <<'STUB'
#!/usr/bin/env bash
set -eu
{
  printf 'SSH'
  for arg in "$@"; do
    printf ' <%s>' "${arg}"
  done
  printf '\n'
} >>"${PROTOCOL_TEST_COMMAND_LOG}"
last_arg=""
for arg in "$@"; do
  last_arg="${arg}"
done
case "${last_arg}" in
  *"bash -s --"*) cat >/dev/null ;;
esac
STUB

  cat >"${PROTOCOL_FIXTURE_BIN}/rsync" <<'STUB'
#!/usr/bin/env bash
set -eu
{
  printf 'RSYNC'
  for arg in "$@"; do
    printf ' <%s>' "${arg}"
  done
  printf '\n'
} >>"${PROTOCOL_TEST_COMMAND_LOG}"
STUB

  cat >"${PROTOCOL_FIXTURE_BIN}/node" <<'STUB'
#!/usr/bin/env bash
set -eu
printf 'NODE <%s>\n' "$*" >>"${PROTOCOL_TEST_COMMAND_LOG}"
if [ "${1:-}" = "--version" ]; then
  printf 'v24.8.0\n'
fi
STUB

  cat >"${PROTOCOL_FIXTURE_BIN}/npm" <<'STUB'
#!/usr/bin/env bash
set -eu
printf 'NPM <%s>\n' "$*" >>"${PROTOCOL_TEST_COMMAND_LOG}"
if [ "${PROTOCOL_TEST_FAIL_LOCAL_BUILD:-}" = 1 ] \
  && [ "${1:-}" = run ] \
  && [ "${2:-}" = build ]; then
  exit 72
fi
if [ "${1:-}" = run ] && [ "${2:-}" = build ]; then
  mkdir -p dist
fi
STUB
  chmod +x \
    "${PROTOCOL_FIXTURE_BIN}/ssh" \
    "${PROTOCOL_FIXTURE_BIN}/rsync" \
    "${PROTOCOL_FIXTURE_BIN}/node" \
    "${PROTOCOL_FIXTURE_BIN}/npm"
}

cleanup_protocol_command_fixture() {
  rm -rf "${PROTOCOL_FIXTURE_ROOT}"
}

setup_deep_check_fixture() {
  DEEP_CHECK_FIXTURE_ROOT="$(mktemp -d)"
  DEEP_CHECK_FIXTURE_BIN="${DEEP_CHECK_FIXTURE_ROOT}/bin"
  DEEP_CHECK_FIXTURE_KEY="${DEEP_CHECK_FIXTURE_ROOT}/check key.pem"
  DEEP_CHECK_FIXTURE_FLEET_KEYS_DIR="${DEEP_CHECK_FIXTURE_ROOT}/fleet keys"
  DEEP_CHECK_FIXTURE_FLEET_CONFIG="${DEEP_CHECK_FIXTURE_ROOT}/fleet-nodes.conf"
  DEEP_CHECK_FIXTURE_COMMAND_LOG="${DEEP_CHECK_FIXTURE_ROOT}/commands.log"
  mkdir -p "${DEEP_CHECK_FIXTURE_BIN}" "${DEEP_CHECK_FIXTURE_FLEET_KEYS_DIR}"
  : >"${DEEP_CHECK_FIXTURE_KEY}"
  : >"${DEEP_CHECK_FIXTURE_COMMAND_LOG}"
  chmod 600 "${DEEP_CHECK_FIXTURE_KEY}"
  cat >"${DEEP_CHECK_FIXTURE_FLEET_CONFIG}" <<'CONF'
coordinator|tester|127.0.0.1|check.pem|coordinator
node1|tester|127.0.0.2|check.pem|node
node2|tester|127.0.0.3|check.pem|node
node3|tester|127.0.0.4|check.pem|node
CONF
  cp "${DEEP_CHECK_FIXTURE_KEY}" "${DEEP_CHECK_FIXTURE_FLEET_KEYS_DIR}/check.pem"

  cat >"${DEEP_CHECK_FIXTURE_BIN}/ssh" <<'STUB'
#!/usr/bin/env bash
set -eu
{
  printf 'SSH'
  for arg in "$@"; do printf ' <%s>' "${arg}"; done
  printf '\n'
} >>"${DEEP_CHECK_TEST_COMMAND_LOG}"
last_arg=""
for arg in "$@"; do last_arg="${arg}"; done
if [ ! -t 0 ]; then cat >/dev/null; fi
case "${last_arg}" in
  *"/admin/nodes"*)
    printf '%s\n' '{"success":true,"data":[{"id":"1","status":"online"},{"id":"2","status":"online"},{"id":"3","status":"online"}]}'
    ;;
esac
STUB

  cat >"${DEEP_CHECK_FIXTURE_BIN}/curl" <<'STUB'
#!/usr/bin/env bash
set -eu
{
  printf 'CURL'
  for arg in "$@"; do printf ' <%s>' "${arg}"; done
  printf '\n'
} >>"${DEEP_CHECK_TEST_COMMAND_LOG}"
STUB
  chmod +x "${DEEP_CHECK_FIXTURE_BIN}/ssh" "${DEEP_CHECK_FIXTURE_BIN}/curl"
}

cleanup_deep_check_fixture() {
  rm -rf "${DEEP_CHECK_FIXTURE_ROOT}"
}

run_deep_check_with_stubs() {
  PATH="${DEEP_CHECK_FIXTURE_BIN}:${PATH}" \
  DEEP_CHECK_TEST_COMMAND_LOG="${DEEP_CHECK_FIXTURE_COMMAND_LOG}" \
  ARMADA_DEPLOY_KEY="${DEEP_CHECK_FIXTURE_KEY}" \
  ARMADA_PROTOCOL_DEPLOY_KEY="${DEEP_CHECK_FIXTURE_KEY}" \
  ARMADA_PROTOCOL_JUMP_KEY="${DEEP_CHECK_FIXTURE_KEY}" \
  ARMADA_ZHUAN_DEPLOY_KEY="${DEEP_CHECK_FIXTURE_KEY}" \
  ARMADA_ZHUAN_FLEET_CONFIG="${DEEP_CHECK_FIXTURE_FLEET_CONFIG}" \
  ARMADA_ZHUAN_FLEET_KEYS_DIR="${DEEP_CHECK_FIXTURE_FLEET_KEYS_DIR}" \
  "${SCRIPT}" "$@"
}

run_protocol_with_command_stubs() {
  PATH="${PROTOCOL_FIXTURE_BIN}:${PATH}" \
  PROTOCOL_TEST_COMMAND_LOG="${PROTOCOL_FIXTURE_COMMAND_LOG}" \
  ARMADA_PROTOCOL_DIR="${PROTOCOL_FIXTURE_DIR}" \
  ARMADA_PROTOCOL_DEPLOY_KEY="${PROTOCOL_FIXTURE_KEY}" \
  ARMADA_PROTOCOL_JUMP_KEY="${PROTOCOL_FIXTURE_JUMP_KEY}" \
  "${SCRIPT}" "$@"
}

run_zhuan_with_command_stubs() {
  PATH="${ZHUAN_FIXTURE_BIN}:${PATH}" \
  ZHUAN_TEST_COMMAND_LOG="${ZHUAN_FIXTURE_COMMAND_LOG}" \
  ZHUAN_TEST_PAYLOAD_LOG="${ZHUAN_FIXTURE_PAYLOAD_LOG}" \
  ARMADA_ZHUAN_DIR="${ZHUAN_FIXTURE_DIR}" \
  ARMADA_ZHUAN_DEPLOY_HOST="127.0.0.1" \
  ARMADA_ZHUAN_DEPLOY_USER="tester" \
  ARMADA_ZHUAN_DEPLOY_KEY="${ZHUAN_FIXTURE_KEY}" \
  ARMADA_ZHUAN_DEPLOY_REMOTE_DIR="/home/app/zhuan-safe" \
  ARMADA_ZHUAN_FLEET_CONFIG="${ZHUAN_FIXTURE_FLEET_CONFIG}" \
  ARMADA_ZHUAN_FLEET_KEYS_DIR="${ZHUAN_FIXTURE_FLEET_KEYS_DIR}" \
  "${SCRIPT}" "$@"
}

test_zhuan_command_flow_uses_protected_rsync_and_ordered_payload() {
  local build_line config_line deps_line health_line main_line migrate_line command_log payload_log
  setup_zhuan_command_fixture
  run_zhuan_with_command_stubs --env perf2 --zhuan -y >/dev/null
  command_log="$(cat "${ZHUAN_FIXTURE_COMMAND_LOG}")"
  payload_log="$(cat "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"

  assert_contains "${command_log}" "RSYNC <--stats> <-rltz> <--delete>"
  assert_contains "${command_log}" "ssh -i '${ZHUAN_FIXTURE_KEY}'"
  assert_contains "${command_log}" "<--exclude=/.env>"
  assert_contains "${command_log}" "<--exclude=*.key>"
  assert_contains "${command_log}" "<tester@127.0.0.1:/home/app/zhuan-safe/>"
  assert_contains "${payload_log}" 'sudo docker compose -f "${compose_file}" run --rm --interactive=false whatsapp-android-zhuan /app/whatsapp-migrate -env prod'
  assert_contains "${payload_log}" 'sudo docker compose -f "${compose_file}" up -d --force-recreate whatsapp-android-zhuan'

  config_line="$(awk 'index($0, "config --quiet") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  build_line="$(awk 'index($0, "build whatsapp-android-zhuan") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  deps_line="$(awk 'index($0, "up -d ${start_services}") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  migrate_line="$(awk 'index($0, "run --rm --interactive=false whatsapp-android-zhuan /app/whatsapp-migrate -env prod") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  main_line="$(awk 'index($0, "up -d --force-recreate whatsapp-android-zhuan") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  health_line="$(awk 'index($0, "swagger/index.html") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  [ "${config_line}" -lt "${build_line}" ] || fail "expected Compose config before build"
  [ "${build_line}" -lt "${deps_line}" ] || fail "expected build before dependency startup"
  [ "${deps_line}" -lt "${migrate_line}" ] || fail "expected dependencies before migration"
  [ "${migrate_line}" -lt "${main_line}" ] || fail "expected migration before main service startup"
  [ "${main_line}" -lt "${health_line}" ] || fail "expected main service startup before health check"
  assert_contains "${payload_log}" "set -eu"
  cleanup_zhuan_command_fixture
}

test_zhuan_dry_run_invokes_no_external_commands() {
  local out
  setup_zhuan_command_fixture
  out="$(run_zhuan_with_command_stubs --env test1 --zhuan --dry-run)"
  [ ! -s "${ZHUAN_FIXTURE_COMMAND_LOG}" ] || fail "dry-run unexpectedly invoked ssh or rsync"
  assert_contains "${out}" "Zhuan 模式"
  assert_contains "${out}" "fleet / coordinator + 3 nodes"
  assert_contains "${out}" "并发部署 coordinator + 3 台 node"
  assert_not_contains "${out}" "whatsapp-migrate -env prod"
  cleanup_zhuan_command_fixture
}

test_test1_zhuan_uses_lightweight_fleet_connectivity_check() {
  local command_log
  setup_zhuan_command_fixture
  run_zhuan_with_command_stubs --env test1 --zhuan -y >/dev/null
  command_log="$(cat "${ZHUAN_FIXTURE_COMMAND_LOG}")"
  cleanup_zhuan_command_fixture

  assert_contains "${command_log}" "FLEET <REPO=${ZHUAN_FIXTURE_DIR}>"
  assert_contains "${command_log}" "<KEYS_DIR=${ZHUAN_FIXTURE_FLEET_KEYS_DIR}>"
  assert_contains "${command_log}" "<NODES_CONF=${ZHUAN_FIXTURE_FLEET_CONFIG}>"
  assert_contains "${command_log}" "<--check> <all>"
  assert_contains "${command_log}" "<all>"
  assert_not_contains "${command_log}" "<--dry-run> <all>"
  assert_not_contains "${command_log}" "RSYNC <--stats> <-rltz>"
}

test_test1_zhuan_fleet_dry_run_matches_windows_entrypoint() {
  local win_content
  win_content="$(cat "${WIN_SCRIPT}")"

  assert_contains "${win_content}" "fleet / coordinator + %s nodes"
  assert_contains "${win_content}" '并发部署 coordinator + ${ZHUAN_FLEET_EXPECTED_NODES} 台 node'
  assert_contains "${win_content}" "zhuan_check_connectivity"
  assert_contains "${win_content}" "zhuan_deploy_selected"
}

test_windows_help_loads_profile() {
  local out
  out="$(bash "${WIN_SCRIPT}" --help)"
  assert_contains "${out}" "deploy-test-win.sh - 部署 armada API"
  assert_contains "${out}" "--full"
}

test_zhuan_remote_failure_stops_before_health_check() {
  local payload_log
  setup_zhuan_command_fixture
  if ZHUAN_TEST_FAIL_PAYLOAD="build whatsapp-android-zhuan" \
    run_zhuan_with_command_stubs --env perf2 --zhuan -y >/dev/null 2>&1; then
    cleanup_zhuan_command_fixture
    fail "expected failed remote lifecycle to stop deployment"
  fi
  payload_log="$(cat "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  assert_not_contains "${payload_log}" "curl -fsS -m 8 http://127.0.0.1:8001/swagger/index.html"
  cleanup_zhuan_command_fixture
}

test_zhuan_perf_uses_perf_compose_without_local_redis() {
  local payload_log
  setup_zhuan_command_fixture
  run_zhuan_with_command_stubs --env perf2 --zhuan -y >/dev/null
  payload_log="$(cat "${ZHUAN_FIXTURE_COMMAND_LOG}" "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  cleanup_zhuan_command_fixture

  assert_contains "${payload_log}" "docker-compose.perf.yml"
  assert_contains "${payload_log}" "'docker-compose.perf.yml' 'callback-zhuan'"
  assert_not_contains "${payload_log}" "'docker-compose.perf.yml' 'redis-zhuan callback-zhuan'"
  assert_contains "${payload_log}" "whatsapp_android_zhuan_perf"
  assert_contains "${payload_log}" "android-zhuan-perf:"
  assert_contains "${payload_log}" "检测到 perf2 禁止的本地 redis-zhuan 容器"
}

test_zhuan_rsync_filters_preserve_runtime_files_and_modes() {
  local destination mode root source
  root="$(mktemp -d)"
  source="${root}/source"
  destination="${root}/destination"
  mkdir -p "${source}/cmd/server" "${source}/deploy/configs" \
    "${destination}/cmd/server" "${destination}/deploy/configs" "${destination}/logs"
  printf 'package main\n' >"${source}/main.go"
  printf 'current server source\n' >"${source}/cmd/server/main.go"
  printf 'root build artifact\n' >"${source}/server"
  printf 'stale server source\n' >"${destination}/cmd/server/main.go"
  printf 'stale\n' >"${destination}/stale.txt"
  printf 'root-env\n' >"${destination}/.env"
  printf 'root-env-local\n' >"${destination}/.env.local"
  printf 'deploy-env\n' >"${destination}/deploy/.env"
  printf 'prod-config\n' >"${destination}/deploy/configs/prod_configs.toml"
  printf 'private-key\n' >"${destination}/private.key"
  printf 'archive\n' >"${destination}/release.tar.gz"
  printf 'compressed-dump\n' >"${destination}/backup.sql.gz"
  printf 'compressed-dump\n' >"${destination}/backup.bz2"
  printf 'compressed-dump\n' >"${destination}/backup.xz"
  printf 'compressed-dump\n' >"${destination}/backup.zst"
  printf 'compressed-dump\n' >"${destination}/backup.7z"
  printf 'compressed-dump\n' >"${destination}/backup.rar"
  printf 'runtime-log\n' >"${destination}/logs/runtime.log"
  chmod 755 "${source}/deploy/configs"
  chmod 700 "${destination}/deploy/configs"

  rsync -rltz --delete \
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
    "${source}/" "${destination}/"

  [ -f "${destination}/main.go" ] || fail "expected source file to be synchronized"
  assert_contains "$(cat "${destination}/cmd/server/main.go")" "current server source"
  [ ! -e "${destination}/server" ] || fail "expected root server build artifact to be excluded"
  [ ! -e "${destination}/stale.txt" ] || fail "expected unprotected stale file to be deleted"
  [ -f "${destination}/.env" ] || fail "expected root .env to be preserved"
  [ -f "${destination}/.env.local" ] || fail "expected root .env variant to be preserved"
  [ -f "${destination}/deploy/.env" ] || fail "expected deploy .env to be preserved"
  [ -f "${destination}/deploy/configs/prod_configs.toml" ] || fail "expected production config to be preserved"
  [ -f "${destination}/private.key" ] || fail "expected private key to be preserved"
  [ -f "${destination}/release.tar.gz" ] || fail "expected archive to be preserved"
  for archive in backup.sql.gz backup.bz2 backup.xz backup.zst backup.7z backup.rar; do
    [ -f "${destination}/${archive}" ] || fail "expected compressed archive to be preserved: ${archive}"
  done
  [ -f "${destination}/logs/runtime.log" ] || fail "expected runtime log to be preserved"
  mode="$(stat -c '%a' "${destination}/deploy/configs" 2>/dev/null || stat -f '%Lp' "${destination}/deploy/configs")"
  [ "${mode}" = 700 ] || fail "expected protected config directory mode 700, got ${mode}"
  rm -rf "${root}"
}

test_help_mentions_protocol_scope() {
  local out
  out="$("${SCRIPT}" --help)"
  assert_contains "${out}" "--env test1|perf2"
  assert_contains "${out}" "--protocol"
  assert_contains "${out}" "--zhuan"
  assert_contains "${out}" "--full"
  assert_contains "${out}" "ARMADA_PROTOCOL_DEPLOY_HOST"
  assert_contains "${out}" "ARMADA_ZHUAN_DEPLOY_HOST"
  assert_contains "${out}" "ARMADA_ZHUAN_DEPLOY_REMOTE_DIR"
  assert_contains "${out}" "ARMADA_ZHUAN_FLEET_CONFIG"
  assert_contains "${out}" "ARMADA_ZHUAN_FLEET_KEYS_DIR"
  assert_contains "${out}" "ARMADA_APP_TITLE"
}

test_protocol_dry_run_is_protocol_only() {
  local key out
  key="$(mktemp)"
  chmod 600 "${key}"
  out="$(
    ARMADA_DEPLOY_KEY="${key}" \
    ARMADA_PROTOCOL_DEPLOY_KEY="${key}" \
    "${SCRIPT}" --protocol --dry-run
  )"
  rm -f "${key}"

  assert_contains "${out}" "范围          : 只协议层"
  assert_contains "${out}" "协议目录"
  assert_contains "${out}" "协议目标"
  assert_contains "${out}" "[dry-run] 将本地构建协议层"
  assert_contains "${out}" "[dry-run] 将在远端构建协议层"
  assert_not_contains "${out}" "后端 JDK"
  assert_not_contains "${out}" "前端构建"
}

test_protocol_transport_is_direct_for_test1() {
  local command_log
  setup_protocol_command_fixture
  run_protocol_with_command_stubs --env test1 --protocol -y >/dev/null
  command_log="$(cat "${PROTOCOL_FIXTURE_COMMAND_LOG}")"
  cleanup_protocol_command_fixture

  assert_contains "${command_log}" "SSH <-i> <${PROTOCOL_FIXTURE_KEY}>"
  assert_contains "${command_log}" "RSYNC <--stats> <-az> <--delete> <-e> <ssh -i '${PROTOCOL_FIXTURE_KEY}'"
  assert_not_contains "${command_log}" "ProxyCommand="
}

test_protocol_transport_uses_perf2_jump_for_ssh_and_rsync() {
  local command_log
  setup_protocol_command_fixture
  run_protocol_with_command_stubs --env perf2 --protocol -y >/dev/null
  command_log="$(cat "${PROTOCOL_FIXTURE_COMMAND_LOG}")"
  cleanup_protocol_command_fixture

  assert_contains "${command_log}" "ProxyCommand=ssh"
  assert_contains "${command_log}" "${PROTOCOL_FIXTURE_JUMP_KEY}"
  assert_contains "${command_log}" "ec2-user@3.110.124.52"
  [ "$(grep -c 'ProxyCommand=ssh' <<<"${command_log}")" -ge 2 ] \
    || fail "expected jump ProxyCommand in both ssh and rsync"
}

test_protocol_local_build_failure_prevents_remote_commands() {
  local command_log
  setup_protocol_command_fixture
  if PROTOCOL_TEST_FAIL_LOCAL_BUILD=1 \
    run_protocol_with_command_stubs --env test1 --protocol -y >/dev/null 2>&1; then
    cleanup_protocol_command_fixture
    fail "expected failed local protocol build to stop deployment"
  fi
  command_log="$(cat "${PROTOCOL_FIXTURE_COMMAND_LOG}")"
  cleanup_protocol_command_fixture

  assert_contains "${command_log}" "NPM <run build>"
  assert_not_contains "${command_log}" "SSH"
  assert_not_contains "${command_log}" "RSYNC"
}

test_protocol_local_build_precedes_remote_sync() {
  local build_line command_log rsync_line
  setup_protocol_command_fixture
  run_protocol_with_command_stubs --env test1 --protocol -y >/dev/null
  command_log="${PROTOCOL_FIXTURE_COMMAND_LOG}"
  build_line="$(awk 'index($0, "NPM <run build>") { print NR; exit }' "${command_log}")"
  rsync_line="$(awk 'index($0, "RSYNC") { print NR; exit }' "${command_log}")"
  cleanup_protocol_command_fixture

  [ -n "${build_line}" ] || fail "expected local protocol build"
  [ -n "${rsync_line}" ] || fail "expected protocol rsync"
  [ "${build_line}" -lt "${rsync_line}" ] || fail "expected local protocol build before rsync"
}

test_armada_and_protocol_reject_unexpected_remote_roots() {
  local key out protocol_dir
  key="$(mktemp)"
  chmod 600 "${key}"

  if out="$(ARMADA_DEPLOY_KEY="${key}" ARMADA_DEPLOY_REMOTE_DIR=/tmp/armada "${SCRIPT}" --be --dry-run 2>&1)"; then
    rm -f "${key}"
    fail "expected Armada remote directory outside /home to be rejected"
  fi
  assert_contains "${out}" "Armada 远端目录必须位于 /home 下"

  protocol_dir="$(cd "${SCRIPT_DIR}/../../armada-protocol" && pwd)"
  if out="$(ARMADA_PROTOCOL_DIR="${protocol_dir}" ARMADA_PROTOCOL_DEPLOY_KEY="${key}" ARMADA_PROTOCOL_DEPLOY_REMOTE_DIR=/var/protocol "${SCRIPT}" --protocol --dry-run 2>&1)"; then
    rm -f "${key}"
    fail "expected protocol remote directory outside /home to be rejected"
  fi
  rm -f "${key}"
  assert_contains "${out}" "协议 远端目录必须位于 /home 下"
}

test_zhuan_compose_file_is_allowlisted() {
  local key out
  key="$(mktemp)"
  chmod 600 "${key}"
  if out="$(
    ARMADA_ZHUAN_DEPLOY_KEY="${key}" \
    ARMADA_ZHUAN_COMPOSE_FILE=../../docker-compose.yml \
    "${SCRIPT}" --env perf2 --zhuan --dry-run 2>&1
  )"; then
    rm -f "${key}"
    fail "expected unsafe Zhuan Compose override to fail"
  fi
  rm -f "${key}"
  assert_contains "${out}" "Zhuan Compose 只允许 docker-compose.yml 或 docker-compose.perf.yml"
}

test_default_environment_is_test1() {
  local key out
  key="$(mktemp)"
  chmod 600 "${key}"
  out="$(ARMADA_DEPLOY_KEY="${key}" "${SCRIPT}" --be --dry-run)"
  rm -f "${key}"

  assert_contains "${out}" "环境 ID       : test1"
  assert_contains "${out}" "环境标识      : 第一套环境"
  assert_contains "${out}" "ubuntu@65.2.123.53:/home/app/armada-deploy"
}

test_perf2_full_dry_run_uses_all_profile_targets() {
  local key out
  key="$(mktemp)"
  chmod 600 "${key}"
  out="$(
    ARMADA_DEPLOY_KEY="${key}" \
    ARMADA_PROTOCOL_DEPLOY_KEY="${key}" \
    ARMADA_PROTOCOL_JUMP_KEY="${key}" \
    ARMADA_ZHUAN_DEPLOY_KEY="${key}" \
    "${SCRIPT}" --env perf2 --full --dry-run
  )"
  rm -f "${key}"

  assert_contains "${out}" "环境 ID       : perf2"
  assert_contains "${out}" "环境标识      : 第二套环境"
  assert_contains "${out}" "ec2-user@3.110.124.52:/home/app/armada-deploy"
  assert_contains "${out}" "ec2-user@172.31.8.217:/home/ec2-user/armada-protocol"
  assert_contains "${out}" "协议连接      : jump via ec2-user@3.110.124.52"
  assert_contains "${out}" "ec2-user@3.111.245.182:/home/ec2-user/whatsapp-android-zhuan"
  assert_contains "${out}" "docker-compose.perf.yml"
  assert_contains "${out}" "将启动并验活 callback-zhuan、whatsapp-android-zhuan"
  assert_not_contains "${out}" "将启动并验活 redis-zhuan"
}

test_perf2_profile_uses_current_isolated_android_topic_contract() {
  local profile_content
  profile_content="$(cat "${SCRIPT_DIR}/envs/perf2.conf")"

  assert_contains "${profile_content}" "armada.perf.protocol.android.lifecycle.commands.v1=12"
  assert_contains "${profile_content}" "armada.perf.protocol.android.message.commands.v1=12"
  assert_contains "${profile_content}" "armada.perf.protocol.android.group-join.commands.v1=12"
  assert_contains "${profile_content}" "armada-perf-android-zhuan-lifecycle-v1"
  assert_contains "${profile_content}" "armada-perf-android-zhuan-message-v1"
  assert_contains "${profile_content}" "armada-perf-android-zhuan-group-join-v1"
  assert_contains "${profile_content}" "armada.perf.protocol.web.normal-group.commands.v1=12"
  assert_contains "${profile_content}" "armada.perf.protocol.android.normal-group.commands.v1=12"
  assert_contains "${profile_content}" "armada.perf.protocol.normal-group.events.v1=12"
  assert_contains "${profile_content}" "armada-perf-protocol-web-normal-group-commands"
  assert_contains "${profile_content}" "armada-perf-android-zhuan-normal-group"
  assert_contains "${profile_content}" "armada-perf-api-normal-group-results"
  assert_not_contains "${profile_content}" "create_group_command"
  assert_not_contains "${profile_content}" "change_group_announcement_command"
  assert_not_contains "${profile_content}" "send_group_message_command"
}

test_test1_profile_uses_three_node_android_fleet() {
  local profile_content
  profile_content="$(cat "${SCRIPT_DIR}/envs/test1.conf")"

  assert_contains "${profile_content}" "PROFILE_ZHUAN_DEPLOY_MODE=fleet"
  assert_contains "${profile_content}" "PROFILE_ZHUAN_FLEET_EXPECTED_NODES=3"
  assert_contains "${profile_content}" "PROFILE_ZHUAN_FLEET_COORDINATOR_PORT=9100"
  assert_contains "${profile_content}" "EXPECTED_ANDROID_BASE_URL=http://65.2.123.53:9100"
  assert_not_contains "${profile_content}" "PROFILE_ZHUAN_HOST="
  assert_not_contains "${profile_content}" "PROFILE_ZHUAN_COMPOSE_FILE="
}

test_environment_name_is_allowlisted() {
  local bad_env out
  for bad_env in unknown ../perf2 /tmp/perf2; do
    if out="$("${SCRIPT}" --env "${bad_env}" --be --dry-run 2>&1)"; then
      fail "expected environment to be rejected: ${bad_env}"
    fi
    assert_contains "${out}" "环境只允许 test1 或 perf2"
  done
}

test_profile_values_can_be_overridden_by_existing_environment_variables() {
  local key out
  key="$(mktemp)"
  chmod 600 "${key}"
  out="$(
    ARMADA_DEPLOY_HOST="127.0.0.7" \
    ARMADA_DEPLOY_USER="override-user" \
    ARMADA_DEPLOY_KEY="${key}" \
    "${SCRIPT}" --env perf2 --be --dry-run
  )"
  rm -f "${key}"

  assert_contains "${out}" "环境 ID       : perf2"
  assert_contains "${out}" "override-user@127.0.0.7:/home/app/armada-deploy"
}

test_full_dry_run_prints_selected_repository_evidence_without_secrets() {
  local key out secret_value
  key="$(mktemp)"
  chmod 600 "${key}"
  secret_value="do-not-print-kafka-password-7349"
  out="$(
    KAFKA_PASSWORD="${secret_value}" \
    ARMADA_DEPLOY_KEY="${key}" \
    ARMADA_PROTOCOL_DEPLOY_KEY="${key}" \
    ARMADA_PROTOCOL_JUMP_KEY="${key}" \
    ARMADA_ZHUAN_DEPLOY_KEY="${key}" \
    "${SCRIPT}" --env perf2 --full --dry-run
  )"
  rm -f "${key}"

  assert_contains "${out}" "Armada 源码"
  assert_contains "${out}" "前端源码"
  assert_contains "${out}" "协议源码"
  assert_contains "${out}" "Zhuan 源码"
  assert_contains "${out}" "commit="
  assert_contains "${out}" "state="
  assert_not_contains "${out}" "${secret_value}"
  assert_not_contains "${out}" "KAFKA_PASSWORD"
}

test_backend_dry_run_does_not_inspect_unselected_repositories() {
  local key out
  key="$(mktemp)"
  chmod 600 "${key}"
  out="$(ARMADA_DEPLOY_KEY="${key}" "${SCRIPT}" --be --dry-run)"
  rm -f "${key}"

  assert_contains "${out}" "Armada 源码"
  assert_not_contains "${out}" "前端源码"
  assert_not_contains "${out}" "协议源码"
  assert_not_contains "${out}" "Zhuan 源码"
}

test_failed_component_prints_redacted_summary() {
  local out
  setup_zhuan_command_fixture
  if out="$(
    ZHUAN_TEST_FAIL_PAYLOAD="build whatsapp-android-zhuan" \
      run_zhuan_with_command_stubs --env perf2 --zhuan -y 2>&1
  )"; then
    cleanup_zhuan_command_fixture
    fail "expected Zhuan deployment failure"
  fi
  cleanup_zhuan_command_fixture

  assert_contains "${out}" "部署结果"
  assert_contains "${out}" "Zhuan"
  assert_contains "${out}" "FAILED"
  assert_contains "${out}" "Backend"
  assert_contains "${out}" "SKIPPED"
}

test_perf2_check_runs_all_read_only_groups_without_mutations() {
  local command_log out
  setup_deep_check_fixture
  out="$(run_deep_check_with_stubs --env perf2 --check)"
  command_log="$(cat "${DEEP_CHECK_FIXTURE_COMMAND_LOG}")"
  cleanup_deep_check_fixture

  assert_contains "${out}" "[check] Armada"
  assert_contains "${out}" "[check] Baileys"
  assert_contains "${out}" "[check] Zhuan"
  assert_contains "${out}" "[check] Kafka"
  assert_contains "${out}" "[check] Cross-component"
  assert_not_contains "${command_log}" "rsync"
  assert_not_contains "${command_log}" "docker compose up"
  assert_not_contains "${command_log}" "pm2 startOrReload"
  assert_not_contains "${command_log}" "npm run build"
  assert_not_contains "${command_log}" "mvn"
  assert_not_contains "${command_log}" "pnpm"
}

test_test1_check_skips_exact_kafka_metadata() {
  local command_log out
  setup_deep_check_fixture
  out="$(run_deep_check_with_stubs --env test1 --check)"
  command_log="$(cat "${DEEP_CHECK_FIXTURE_COMMAND_LOG}")"
  cleanup_deep_check_fixture
  assert_contains "${out}" "[check] Kafka exact metadata: SKIPPED"
  assert_contains "${command_log}" "/admin/nodes"
}

test_check_rejects_mutation_and_mode_combinations() {
  local args out
  for args in \
    "--check --dry-run" \
    "--check --logs" \
    "--check -y" \
    "--check --branch main" \
    "--check --full" \
    "--check --be"; do
    if out="$("${SCRIPT}" ${args} 2>&1)"; then
      fail "expected invalid --check combination to fail: ${args}"
    fi
    assert_contains "${out}" "--check 不能与部署或日志参数组合"
  done
}

test_normal_dry_run_does_not_run_deep_checks() {
  local key out
  key="$(mktemp)"
  chmod 600 "${key}"
  out="$(
    ARMADA_DEPLOY_KEY="${key}" \
    ARMADA_PROTOCOL_DEPLOY_KEY="${key}" \
    ARMADA_PROTOCOL_JUMP_KEY="${key}" \
    ARMADA_ZHUAN_DEPLOY_KEY="${key}" \
    "${SCRIPT}" --env perf2 --full --dry-run
  )"
  rm -f "${key}"
  assert_not_contains "${out}" "[check] Kafka"
  assert_not_contains "${out}" "[check] Cross-component"
}

test_zhuan_dry_run_is_zhuan_only() {
  local out
  setup_zhuan_command_fixture
  out="$(run_zhuan_with_command_stubs --env test1 --zhuan --dry-run)"
  cleanup_zhuan_command_fixture

  assert_contains "${out}" "范围          : 只 Zhuan 协议"
  assert_contains "${out}" "Zhuan 目录"
  assert_contains "${out}" "Zhuan 模式"
  assert_contains "${out}" "fleet / coordinator + 3 nodes"
  assert_contains "${out}" "并发部署 coordinator + 3 台 node"
  assert_not_contains "${out}" "whatsapp-migrate -env prod"
  assert_not_contains "${out}" "后端 JDK"
  assert_not_contains "${out}" "前端构建"
  assert_not_contains "${out}" "协议 PM2"
}

test_full_includes_zhuan_but_all_does_not() {
  local all_scope full_scope
  all_scope="$(sed -n '/^  all)/,/^    ;;/p' "${SCRIPT}")"
  full_scope="$(sed -n '/^  full)/,/^    ;;/p' "${SCRIPT}")"

  assert_not_contains "${all_scope}" "BUILD_ZHUAN=1"
  assert_contains "${full_scope}" "BUILD_ZHUAN=1"
}

test_zhuan_fleet_mode_reuses_protocol_repository_orchestrator() {
  local module_content
  module_content="$(cat "${SCRIPT_DIR}/lib/zhuan.sh")"

  assert_contains "${module_content}" '"${ZHUAN_FLEET_SCRIPT}" "$@"'
  assert_contains "${module_content}" 'REPO="${ZHUAN_DIR}"'
  assert_contains "${module_content}" 'KEYS_DIR="${ZHUAN_FLEET_KEYS_DIR}"'
  assert_contains "${module_content}" 'NODES_CONF="${ZHUAN_FLEET_CONFIG}"'
  assert_contains "${module_content}" '"status"[[:space:]]*:[[:space:]]*"online"'
}

test_zhuan_rejects_unsafe_remote_dir() {
  local key out
  key="$(mktemp)"
  chmod 600 "${key}"
  if out="$(
    ARMADA_DEPLOY_KEY="${key}" \
    ARMADA_ZHUAN_DEPLOY_KEY="${key}" \
    ARMADA_ZHUAN_DEPLOY_REMOTE_DIR="/tmp/zhuan'bad" \
    "${SCRIPT}" --env perf2 --zhuan --dry-run 2>&1
  )"; then
    rm -f "${key}"
    fail "expected unsafe Zhuan remote directory to be rejected"
  fi
  rm -f "${key}"

  assert_contains "${out}" "Zhuan 远端目录仅允许"
}

test_zhuan_rejects_root_equivalent_remote_dirs() {
  local bad_dir key
  key="$(mktemp)"
  chmod 600 "${key}"
  for bad_dir in / /. /./ // /home//app /home/./app /home/../app; do
    if ARMADA_DEPLOY_KEY="${key}" \
      ARMADA_ZHUAN_DEPLOY_KEY="${key}" \
      ARMADA_ZHUAN_DEPLOY_REMOTE_DIR="${bad_dir}" \
      "${SCRIPT}" --env perf2 --zhuan --dry-run >/dev/null 2>&1; then
      rm -f "${key}"
      fail "expected root-equivalent Zhuan remote directory to be rejected: ${bad_dir}"
    fi
  done
  rm -f "${key}"
}

test_frontend_dry_run_uses_profile_title_instead_of_host_inference() {
  local key out
  key="$(mktemp)"
  chmod 600 "${key}"
  out="$(
    ARMADA_DEPLOY_HOST="3.110.124.52" \
    ARMADA_DEPLOY_USER="ec2-user" \
    ARMADA_DEPLOY_KEY="${key}" \
    "${SCRIPT}" --fe --dry-run
  )"
  rm -f "${key}"

  assert_contains "${out}" "范围          : 只前端"
  assert_contains "${out}" "环境 ID       : test1"
  assert_contains "${out}" "环境标识      : 第一套环境"
  assert_contains "${out}" "APP_TITLE='第一套环境' docker compose"
}

test_sh_invocation_reexecs_bash_for_help() {
  local out
  out="$(sh "${SCRIPT}" --help)"
  assert_contains "${out}" "deploy-test.sh - 部署 armada API"
  assert_contains "${out}" "--protocol"
}

test_protocol_remote_deploy_requires_node_24_toolchain() {
  local script_content
  script_content="$(cat "${SCRIPT_DIR}/lib/protocol.sh")"

  assert_contains "${script_content}" 'node_version="$(node --version)"'
  assert_contains "${script_content}" '远端 Node.js 必须为 24.x'
  assert_contains "${script_content}" 'PM2 daemon 必须运行在 Node.js 24.x'
}

test_protocol_local_toolchain_discovers_keg_only_node24() {
  local script_content
  script_content="$(cat "${SCRIPT_DIR}/lib/protocol.sh")"
  assert_contains "${script_content}" "/opt/homebrew/opt/node@24/bin/node"
  assert_contains "${script_content}" "ARMADA_PROTOCOL_NODE_BIN"
  assert_contains "${script_content}" 'PATH="${PROTOCOL_NODE_BIN%/*}:${PATH}"'
}

test_protocol_remote_deploy_verifies_node_24_apps_after_reload() {
  local script_content
  script_content="$(cat "${SCRIPT_DIR}/lib/protocol.sh")"

  assert_contains "${script_content}" '协议 PM2 应用必须全部运行在 Node.js 24.x'
  assert_contains "${script_content}" 'expectedProtocolApps = 5'
  assert_contains "${script_content}" 'app.pm2_env?.status !== \"online\"'
  assert_contains "${script_content}" '/readyz'
}

test_protocol_remote_deploy_loads_preserved_environment() {
  local script_content
  script_content="$(cat "${SCRIPT_DIR}/lib/protocol.sh")"

  assert_contains "${script_content}" 'test -f .env || { echo "远端缺少协议配置: ${remote_dir}/protocol-layer/.env" >&2; exit 36; }'
  assert_contains "${script_content}" 'set -a'
  assert_contains "${script_content}" '. ./.env'
  assert_contains "${script_content}" 'set +a'
}

test_main_orchestrator_uses_protocol_module_and_dependency_order() {
  local armada_remote_line protocol_line script_content zhuan_line
  script_content="$(cat "${SCRIPT}")"
  assert_contains "${script_content}" '. "${SCRIPT_DIR}/lib/protocol.sh"'
  assert_contains "${script_content}" "protocol_build_local"
  assert_contains "${script_content}" "protocol_deploy_remote"
  assert_not_contains "${script_content}" "protocol_remote_deploy='"

  protocol_line="$(grep -n 'protocol_deploy_remote' "${SCRIPT}" | head -1 | cut -d: -f1)"
  zhuan_line="$(grep -n 'zhuan_deploy_selected' "${SCRIPT}" | head -1 | cut -d: -f1)"
  armada_remote_line="$(grep -n 'armada_prepare_remote' "${SCRIPT}" | head -1 | cut -d: -f1)"
  [ "${protocol_line}" -lt "${zhuan_line}" ] || fail "expected protocol remote deploy before Zhuan"
  [ "${zhuan_line}" -lt "${armada_remote_line}" ] || fail "expected Zhuan before Armada remote mutation"
}

test_zhuan_sync_preserves_remote_runtime_files() {
  local script_content
  script_content="$(cat "${SCRIPT_DIR}/lib/zhuan.sh")"

  assert_contains "${script_content}" 'armada_rsync "Zhuan source" -rltz --delete -e "${ZHUAN_RSYNC_SSH}"'
  assert_not_contains "${script_content}" '--exclude-from="${ZHUAN_DIR}/.dockerignore"'
  assert_contains "${script_content}" "--exclude='/.git/'"
  assert_contains "${script_content}" "--exclude='/server'"
  assert_contains "${script_content}" "--exclude='/migrate'"
  assert_contains "${script_content}" "--exclude='/mock-callback'"
  assert_contains "${script_content}" "--exclude=deploy/.env"
  assert_contains "${script_content}" "--exclude=deploy/configs/prod_configs.toml"
  assert_contains "${script_content}" "--exclude=deploy/logs/"
  assert_contains "${script_content}" "--exclude=deploy/callback-logs/"
  assert_contains "${script_content}" "--exclude=logs/"
  assert_contains "${script_content}" "--exclude='/.env'"
  assert_contains "${script_content}" "--exclude='/.env.*'"
  assert_contains "${script_content}" "--exclude='configs/*.toml'"
  assert_contains "${script_content}" "--exclude='*.pem'"
  assert_contains "${script_content}" "--exclude='*.key'"
  assert_contains "${script_content}" "--exclude='*.log'"
  assert_contains "${script_content}" "--exclude='*.zip'"
  assert_contains "${script_content}" "--exclude='*.tar'"
  assert_contains "${script_content}" "--exclude='*.tar.gz'"
  assert_contains "${script_content}" "--exclude='*.tgz'"
  assert_contains "${script_content}" "--exclude='*.gz'"
  assert_contains "${script_content}" "--exclude='*.bz2'"
  assert_contains "${script_content}" "--exclude='*.xz'"
  assert_contains "${script_content}" "--exclude='*.zst'"
  assert_contains "${script_content}" "--exclude='*.7z'"
  assert_contains "${script_content}" "--exclude='*.rar'"
  assert_not_contains "${script_content}" "--delete-excluded"
}

test_zhuan_remote_deploy_checks_config_and_runs_lifecycle() {
  local script_content
  script_content="$(cat "${SCRIPT_DIR}/lib/zhuan.sh")"

  assert_contains "${script_content}" 'test -f "${remote_dir}/deploy/.env"'
  assert_contains "${script_content}" 'test -f "${config_file}"'
  assert_contains "${script_content}" 'toml_string mysql name "${config_file}"'
  assert_contains "${script_content}" 'toml_string redis keyprefix "${config_file}"'
  assert_contains "${script_content}" 'sudo docker compose -f "${compose_file}" config --quiet'
  assert_contains "${script_content}" 'sudo docker compose -f "${compose_file}" build whatsapp-android-zhuan'
  assert_contains "${script_content}" 'sudo docker compose -f "${compose_file}" up -d ${start_services}'
  assert_contains "${script_content}" 'sudo docker compose -f "${compose_file}" run --rm --interactive=false whatsapp-android-zhuan /app/whatsapp-migrate -env prod'
  assert_contains "${script_content}" 'sudo docker compose -f "${compose_file}" up -d --force-recreate whatsapp-android-zhuan'
}

test_zhuan_deploy_waits_for_health_and_checks_http() {
  local script_content
  script_content="$(cat "${SCRIPT_DIR}/lib/zhuan.sh")"

  assert_contains "${script_content}" 'for container in ${health_services}'
  assert_contains "${script_content}" "running/healthy"
  assert_contains "${script_content}" "Zhuan 容器未在时限内就绪"
  assert_contains "${script_content}" 'http://127.0.0.1:${http_port}/swagger/index.html'
}

test_zhuan_only_logs_follow_main_container() {
  local script_content
  script_content="$(cat "${SCRIPT_DIR}/lib/zhuan.sh")"

  assert_contains "${script_content}" "sudo docker compose -f '\${ZHUAN_COMPOSE_FILE}' logs -f --tail 120 whatsapp-android-zhuan"
}

test_main_orchestrator_uses_zhuan_module() {
  local script_content
  script_content="$(cat "${SCRIPT}")"
  assert_contains "${script_content}" '. "${SCRIPT_DIR}/lib/zhuan.sh"'
  assert_contains "${script_content}" "zhuan_check_connectivity"
  assert_contains "${script_content}" "zhuan_deploy_selected"
  assert_not_contains "${script_content}" "zhuan_remote_deploy='"
}

test_armada_backend_readiness_is_bounded() {
  local attempt_count log_file out sleep_count
  log_file="$(mktemp)"
  if out="$(
    ARMADA_TEST_LOG="${log_file}" bash -c '
      set -uo pipefail
      . "'"${SCRIPT_DIR}"'/lib/common.sh"
      armada_init_colors
      . "'"${SCRIPT_DIR}"'/lib/armada.sh"
      REMOTE_DIR=/home/app/armada-deploy
      ssh_run() { printf "attempt\n" >>"${ARMADA_TEST_LOG}"; return 1; }
      sleep() { printf "sleep:%s\n" "$1" >>"${ARMADA_TEST_LOG}"; }
      armada_wait_backend_ready
    ' 2>&1
  )"; then
    rm -f "${log_file}"
    fail "expected bounded readiness to fail after maximum attempts"
  fi
  attempt_count="$(grep -c '^attempt$' "${log_file}" || true)"
  sleep_count="$(grep -c '^sleep:5$' "${log_file}" || true)"
  rm -f "${log_file}"

  [ "${attempt_count}" = 30 ] || fail "expected 30 readiness attempts, got ${attempt_count}"
  [ "${sleep_count}" = 29 ] || fail "expected 29 readiness sleeps, got ${sleep_count}"
  assert_contains "${out}" "Armada backend 未在时限内就绪"
}

test_armada_backend_readiness_stops_after_success() {
  local log_file
  log_file="$(mktemp)"
  ARMADA_TEST_LOG="${log_file}" bash -c '
    set -euo pipefail
    . "'"${SCRIPT_DIR}"'/lib/common.sh"
    armada_init_colors
    . "'"${SCRIPT_DIR}"'/lib/armada.sh"
    REMOTE_DIR=/home/app/armada-deploy
    ssh_run() { printf "attempt\n" >>"${ARMADA_TEST_LOG}"; return 0; }
    sleep() { printf "sleep:%s\n" "$1" >>"${ARMADA_TEST_LOG}"; }
    armada_wait_backend_ready
  '
  [ "$(grep -c '^attempt$' "${log_file}" || true)" = 1 ] \
    || fail "expected readiness to stop after first success"
  [ "$(grep -c '^sleep:' "${log_file}" || true)" = 0 ] \
    || fail "expected no sleep after immediate success"
  rm -f "${log_file}"
}

test_armada_perf_runtime_contract_checks_android_url_and_topics() {
  local command_log
  command_log="$(mktemp)"
  ARMADA_TEST_LOG="${command_log}" bash -c '
    set -euo pipefail
    . "'"${SCRIPT_DIR}"'/lib/common.sh"
    armada_init_colors
    . "'"${SCRIPT_DIR}"'/lib/armada.sh"
    EXPECTED_ANDROID_BASE_URL=http://172.31.40.84:8001
    EXPECTED_ANDROID_TOPIC_PREFIX=armada.perf.protocol.android.
    EXPECTED_NORMAL_GROUP_WEB_COMMAND_TOPIC=armada.perf.protocol.web.normal-group.commands.v1
    EXPECTED_NORMAL_GROUP_ANDROID_COMMAND_TOPIC=armada.perf.protocol.android.normal-group.commands.v1
    EXPECTED_NORMAL_GROUP_RESULT_TOPIC=armada.perf.protocol.normal-group.events.v1
    EXPECTED_NORMAL_GROUP_RESULT_GROUP_ID=armada-perf-api-normal-group-results
    ssh_run() { printf "%s\n" "$*" >>"${ARMADA_TEST_LOG}"; }
    armada_verify_backend_runtime
  '
  assert_contains "$(cat "${command_log}")" "PROTOCOL_ANDROID_BASE_URL"
  assert_contains "$(cat "${command_log}")" "PROTOCOL_ANDROID_LIFECYCLE_COMMANDS_TOPIC"
  assert_contains "$(cat "${command_log}")" "PROTOCOL_ANDROID_MESSAGE_COMMANDS_TOPIC"
  assert_contains "$(cat "${command_log}")" "PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC"
  assert_contains "$(cat "${command_log}")" "http://172.31.40.84:8001"
  assert_contains "$(cat "${command_log}")" "armada.perf.protocol.android."
  assert_contains "$(cat "${command_log}")" "NORMAL_GROUP_CREATION_WEB_COMMAND_TOPIC=armada.perf.protocol.web.normal-group.commands.v1"
  assert_contains "$(cat "${command_log}")" "NORMAL_GROUP_CREATION_ANDROID_COMMAND_TOPIC=armada.perf.protocol.android.normal-group.commands.v1"
  assert_contains "$(cat "${command_log}")" "NORMAL_GROUP_CREATION_RESULT_TOPIC=armada.perf.protocol.normal-group.events.v1"
  assert_contains "$(cat "${command_log}")" "NORMAL_GROUP_CREATION_RESULT_GROUP_ID=armada-perf-api-normal-group-results"
  rm -f "${command_log}"
}

test_armada_start_applies_normal_group_environment_contract() {
  local command_log
  command_log="$(mktemp)"
  ARMADA_TEST_LOG="${command_log}" bash -c '
    set -euo pipefail
    . "'"${SCRIPT_DIR}"'/lib/common.sh"
    armada_init_colors
    . "'"${SCRIPT_DIR}"'/lib/armada.sh"
    REMOTE_DIR=/home/app/armada-deploy
    APP_TITLE_REMOTE="第二套环境"
    ENV_ID=perf2
    COMPOSE_PROJECT=armada-perf
    COMPOSE_FILE=docker-compose.rds.yml
    COMPOSE_UP_ARGS="up -d backend"
    EXPECTED_NORMAL_GROUP_WEB_COMMAND_TOPIC=armada.perf.protocol.web.normal-group.commands.v1
    EXPECTED_NORMAL_GROUP_ANDROID_COMMAND_TOPIC=armada.perf.protocol.android.normal-group.commands.v1
    EXPECTED_NORMAL_GROUP_RESULT_TOPIC=armada.perf.protocol.normal-group.events.v1
    EXPECTED_NORMAL_GROUP_RESULT_GROUP_ID=armada-perf-api-normal-group-results
    ssh_run() { printf "%s\n" "$*" >>"${ARMADA_TEST_LOG}"; }
    armada_start
  '
  assert_contains "$(cat "${command_log}")" "NORMAL_GROUP_CREATION_WEB_COMMAND_TOPIC='armada.perf.protocol.web.normal-group.commands.v1'"
  assert_contains "$(cat "${command_log}")" "NORMAL_GROUP_CREATION_ANDROID_COMMAND_TOPIC='armada.perf.protocol.android.normal-group.commands.v1'"
  assert_contains "$(cat "${command_log}")" "NORMAL_GROUP_CREATION_RESULT_TOPIC='armada.perf.protocol.normal-group.events.v1'"
  assert_contains "$(cat "${command_log}")" "NORMAL_GROUP_CREATION_RESULT_GROUP_ID='armada-perf-api-normal-group-results'"
  rm -f "${command_log}"
}

test_armada_module_checks_frontend_title_and_api_proxy() {
  local command_log content
  command_log="$(mktemp)"
  ARMADA_TEST_LOG="${command_log}" bash -c '
    set -euo pipefail
    . "'"${SCRIPT_DIR}"'/lib/common.sh"
    armada_init_colors
    . "'"${SCRIPT_DIR}"'/lib/armada.sh"
    REMOTE_DIR=/home/app/armada-deploy
    APP_TITLE_REMOTE=第二套环境
    ssh_run() { printf "%s\n" "$*" >>"${ARMADA_TEST_LOG}"; }
    armada_verify_frontend
    armada_verify_api_proxy
  '
  content="$(cat "${command_log}")"
  rm -f "${command_log}"
  assert_contains "${content}" "<!doctype html"
  assert_contains "${content}" "platform-config.json"
  assert_contains "${content}" "第二套环境"
  assert_contains "${content}" "/api/account-groups"
}

test_armada_module_preserves_unauthenticated_response_body() {
  local module_content
  module_content="$(cat "${SCRIPT_DIR}/lib/armada.sh")"
  assert_contains "${module_content}" 'curl -sS -m 8'
  assert_not_contains "${module_content}" 'curl -fsS -m 8 \"http://127.0.0.1:\${port}/api/account-groups\"'
  assert_contains "${module_content}" '(40101|40104|0|40001)'
}

test_main_orchestrator_uses_armada_module() {
  local script_content
  script_content="$(cat "${SCRIPT}")"
  assert_contains "${script_content}" '. "${SCRIPT_DIR}/lib/armada.sh"'
  assert_contains "${script_content}" "armada_build_backend"
  assert_contains "${script_content}" "armada_build_frontend"
  assert_contains "${script_content}" "armada_prepare_remote"
  assert_contains "${script_content}" "armada_start"
  assert_contains "${script_content}" 'armada_verify_selected "${BUILD_BE}" "${BUILD_FE}"'
  assert_not_contains "${script_content}" "find_jdk17()"
}

test_armada_compose_passes_android_base_url_to_backend() {
  local compose_content example_content
  compose_content="$(cat "${SCRIPT_DIR}/docker-compose.rds.yml")"
  example_content="$(cat "${SCRIPT_DIR}/.env.example")"
  assert_contains "${compose_content}" 'PROTOCOL_ANDROID_BASE_URL: ${PROTOCOL_ANDROID_BASE_URL:-http://localhost:8000}'
  assert_contains "${example_content}" 'PROTOCOL_ANDROID_BASE_URL=http://localhost:8000'
}

test_armada_compose_passes_normal_group_kafka_config_to_backend() {
  local compose_content example_content
  compose_content="$(cat "${SCRIPT_DIR}/docker-compose.rds.yml")"
  example_content="$(cat "${SCRIPT_DIR}/.env.example")"

  assert_contains "${compose_content}" 'NORMAL_GROUP_CREATION_WEB_COMMAND_TOPIC: ${NORMAL_GROUP_CREATION_WEB_COMMAND_TOPIC:-protocol.web.normal-group.commands.v1}'
  assert_contains "${compose_content}" 'NORMAL_GROUP_CREATION_ANDROID_COMMAND_TOPIC: ${NORMAL_GROUP_CREATION_ANDROID_COMMAND_TOPIC:-protocol.android.normal-group.commands.v1}'
  assert_contains "${compose_content}" 'NORMAL_GROUP_CREATION_RESULT_TOPIC: ${NORMAL_GROUP_CREATION_RESULT_TOPIC:-protocol.normal-group.events.v1}'
  assert_contains "${compose_content}" 'NORMAL_GROUP_CREATION_RESULT_GROUP_ID: ${NORMAL_GROUP_CREATION_RESULT_GROUP_ID:-armada-api-normal-group-results}'
  assert_contains "${compose_content}" 'NORMAL_GROUP_CREATION_RESULT_CONCURRENCY: ${NORMAL_GROUP_CREATION_RESULT_CONCURRENCY:-4}'
  assert_contains "${example_content}" 'NORMAL_GROUP_CREATION_RESULT_GROUP_ID=armada-api-normal-group-results'
}

test_windows_entrypoint_requires_normal_group_environment_contract() {
  local perf_profile win_script
  perf_profile="$(cat "${SCRIPT_DIR}/envs/perf2.conf")"
  win_script="$(cat "${SCRIPT_DIR}/deploy-test-win.sh")"

  assert_contains "${win_script}" 'EXPECTED_NORMAL_GROUP_WEB_COMMAND_TOPIC'
  assert_contains "${win_script}" 'EXPECTED_NORMAL_GROUP_ANDROID_COMMAND_TOPIC'
  assert_contains "${win_script}" 'EXPECTED_NORMAL_GROUP_RESULT_TOPIC'
  assert_contains "${win_script}" 'EXPECTED_NORMAL_GROUP_RESULT_GROUP_ID'
  assert_contains "${perf_profile}" 'EXPECTED_NORMAL_GROUP_WEB_COMMAND_TOPIC=armada.perf.protocol.web.normal-group.commands.v1'
  assert_contains "${perf_profile}" 'EXPECTED_NORMAL_GROUP_RESULT_GROUP_ID=armada-perf-api-normal-group-results'
}

test_armada_compose_passes_promotion_token_encryption_config_to_backend() {
  local compose_content example_content modular_chmod_line modular_required_line modular_script
  local win_script
  compose_content="$(cat "${SCRIPT_DIR}/docker-compose.rds.yml")"
  example_content="$(cat "${SCRIPT_DIR}/.env.example")"
  modular_script="$(cat "${SCRIPT_DIR}/lib/armada.sh")"
  win_script="$(cat "${SCRIPT_DIR}/deploy-test-win.sh")"

  assert_contains "${compose_content}" 'PROMOTION_TRACKING_ENCRYPTION_KEY: ${PROMOTION_TRACKING_ENCRYPTION_KEY:?PROMOTION_TRACKING_ENCRYPTION_KEY is required}'
  assert_contains "${compose_content}" 'PROMOTION_TRACKING_ENCRYPTION_KEY_ID: ${PROMOTION_TRACKING_ENCRYPTION_KEY_ID:?PROMOTION_TRACKING_ENCRYPTION_KEY_ID is required}'
  assert_contains "${example_content}" 'PROMOTION_TRACKING_ENCRYPTION_KEY=REPLACE_WITH_BASE64_32_BYTE_KEY'
  assert_contains "${example_content}" 'PROMOTION_TRACKING_ENCRYPTION_KEY_ID=env-v1'
  assert_contains "${modular_script}" 'DB_URL DB_USER DB_PASSWORD PROMOTION_TRACKING_ENCRYPTION_KEY PROMOTION_TRACKING_ENCRYPTION_KEY_ID'
  assert_contains "${modular_script}" 'base64 --decode'
  assert_contains "${modular_script}" 'chmod 600 .env'
  assert_contains "${win_script}" 'source_lf "${SCRIPT_DIR}/lib/armada.sh"'

  modular_chmod_line="$(grep -n 'chmod 600 .env' "${SCRIPT_DIR}/lib/armada.sh" | head -1 | cut -d: -f1)"
  modular_required_line="$(grep -n 'for key in DB_URL' "${SCRIPT_DIR}/lib/armada.sh" | head -1 | cut -d: -f1)"
  [ "${modular_chmod_line}" -lt "${modular_required_line}" ] \
    || fail "expected modular deploy to protect .env before validation"
}

test_kafka_checker_redacts_connection_errors() {
  local checker_content
  checker_content="$(cat "${SCRIPT_DIR}/lib/kafka-check.mjs")"
  assert_contains "${checker_content}" "Kafka metadata connection/check failed (details redacted)"
  assert_contains "${checker_content}" "catch (error)"
  assert_not_contains "${checker_content}" "console.error(error)"
  assert_not_contains "${checker_content}" "console.error(String(error))"
}

test_kafka_checker_reports_consumer_group_state_read_only() {
  local checker_content
  checker_content="$(cat "${SCRIPT_DIR}/lib/kafka-check.mjs")"
  assert_contains "${checker_content}" "admin.describeGroups(groups)"
  assert_contains "${checker_content}" "state="
  assert_not_contains "${checker_content}" "deleteGroups("
  assert_not_contains "${checker_content}" "createTopics("
}

test_backend_jar_resolution_requires_one_executable_jar
test_backend_deploy_uses_stable_staging_name
test_armada_compose_passes_promotion_token_encryption_config_to_backend
test_assert_contains_handles_large_haystack
test_deployment_metrics_summarize_stage_duration
test_deployment_metrics_summarize_rsync_transfer
test_deployment_metrics_summarize_docker_cache
test_deployment_metrics_summarize_zero_docker_cache_hits
test_deployment_metrics_handles_zero_docker_steps
test_zhuan_command_flow_uses_protected_rsync_and_ordered_payload
test_zhuan_dry_run_invokes_no_external_commands
test_test1_zhuan_uses_lightweight_fleet_connectivity_check
test_test1_zhuan_fleet_dry_run_matches_windows_entrypoint
test_windows_help_loads_profile
test_zhuan_remote_failure_stops_before_health_check
test_zhuan_perf_uses_perf_compose_without_local_redis
test_zhuan_rsync_filters_preserve_runtime_files_and_modes
test_help_mentions_protocol_scope
test_protocol_dry_run_is_protocol_only
test_protocol_transport_is_direct_for_test1
test_protocol_transport_uses_perf2_jump_for_ssh_and_rsync
test_protocol_local_build_failure_prevents_remote_commands
test_protocol_local_build_precedes_remote_sync
test_armada_and_protocol_reject_unexpected_remote_roots
test_zhuan_compose_file_is_allowlisted
test_default_environment_is_test1
test_perf2_full_dry_run_uses_all_profile_targets
test_perf2_profile_uses_current_isolated_android_topic_contract
test_test1_profile_uses_three_node_android_fleet
test_environment_name_is_allowlisted
test_profile_values_can_be_overridden_by_existing_environment_variables
test_full_dry_run_prints_selected_repository_evidence_without_secrets
test_backend_dry_run_does_not_inspect_unselected_repositories
test_failed_component_prints_redacted_summary
test_perf2_check_runs_all_read_only_groups_without_mutations
test_test1_check_skips_exact_kafka_metadata
test_check_rejects_mutation_and_mode_combinations
test_normal_dry_run_does_not_run_deep_checks
test_zhuan_dry_run_is_zhuan_only
test_full_includes_zhuan_but_all_does_not
test_zhuan_fleet_mode_reuses_protocol_repository_orchestrator
test_zhuan_rejects_unsafe_remote_dir
test_zhuan_rejects_root_equivalent_remote_dirs
test_frontend_dry_run_uses_profile_title_instead_of_host_inference
test_sh_invocation_reexecs_bash_for_help
test_protocol_remote_deploy_requires_node_24_toolchain
test_protocol_local_toolchain_discovers_keg_only_node24
test_protocol_remote_deploy_verifies_node_24_apps_after_reload
test_protocol_remote_deploy_loads_preserved_environment
test_main_orchestrator_uses_protocol_module_and_dependency_order
test_zhuan_sync_preserves_remote_runtime_files
test_zhuan_remote_deploy_checks_config_and_runs_lifecycle
test_zhuan_deploy_waits_for_health_and_checks_http
test_zhuan_only_logs_follow_main_container
test_main_orchestrator_uses_zhuan_module
test_armada_backend_readiness_is_bounded
test_armada_backend_readiness_stops_after_success
test_armada_perf_runtime_contract_checks_android_url_and_topics
test_armada_start_applies_normal_group_environment_contract
test_armada_module_checks_frontend_title_and_api_proxy
test_armada_module_preserves_unauthenticated_response_body
test_main_orchestrator_uses_armada_module
test_armada_compose_passes_android_base_url_to_backend
test_armada_compose_passes_normal_group_kafka_config_to_backend
test_windows_entrypoint_requires_normal_group_environment_contract
test_kafka_checker_redacts_connection_errors
test_kafka_checker_reports_consumer_group_state_read_only
printf 'OK deploy-test.sh protocol and zhuan tests passed\n'
