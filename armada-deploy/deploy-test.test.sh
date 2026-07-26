#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/deploy-test.sh"
ARTIFACT_LIB="${SCRIPT_DIR}/lib/artifact.sh"

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
  ZHUAN_FIXTURE_COMMAND_LOG="${ZHUAN_FIXTURE_ROOT}/commands.log"
  ZHUAN_FIXTURE_PAYLOAD_LOG="${ZHUAN_FIXTURE_ROOT}/payloads.log"

  mkdir -p "${ZHUAN_FIXTURE_DIR}/deploy" "${ZHUAN_FIXTURE_BIN}"
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
  DEEP_CHECK_FIXTURE_COMMAND_LOG="${DEEP_CHECK_FIXTURE_ROOT}/commands.log"
  mkdir -p "${DEEP_CHECK_FIXTURE_BIN}"
  : >"${DEEP_CHECK_FIXTURE_KEY}"
  : >"${DEEP_CHECK_FIXTURE_COMMAND_LOG}"
  chmod 600 "${DEEP_CHECK_FIXTURE_KEY}"

  cat >"${DEEP_CHECK_FIXTURE_BIN}/ssh" <<'STUB'
#!/usr/bin/env bash
set -eu
{
  printf 'SSH'
  for arg in "$@"; do printf ' <%s>' "${arg}"; done
  printf '\n'
} >>"${DEEP_CHECK_TEST_COMMAND_LOG}"
if [ ! -t 0 ]; then cat >/dev/null; fi
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
  "${SCRIPT}" "$@"
}

test_zhuan_command_flow_uses_protected_rsync_and_ordered_payload() {
  local build_line config_line deps_line health_line main_line migrate_line command_log payload_log
  setup_zhuan_command_fixture
  run_zhuan_with_command_stubs --zhuan -y >/dev/null
  command_log="$(cat "${ZHUAN_FIXTURE_COMMAND_LOG}")"
  payload_log="$(cat "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"

  assert_contains "${command_log}" "RSYNC <-rltz> <--delete>"
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
  setup_zhuan_command_fixture
  run_zhuan_with_command_stubs --zhuan --dry-run >/dev/null
  [ ! -s "${ZHUAN_FIXTURE_COMMAND_LOG}" ] || fail "dry-run unexpectedly invoked ssh or rsync"
  cleanup_zhuan_command_fixture
}

test_zhuan_remote_failure_stops_before_health_check() {
  local payload_log
  setup_zhuan_command_fixture
  if ZHUAN_TEST_FAIL_PAYLOAD="build whatsapp-android-zhuan" \
    run_zhuan_with_command_stubs --zhuan -y >/dev/null 2>&1; then
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
  mkdir -p "${source}/deploy/configs" "${destination}/deploy/configs" "${destination}/logs"
  : >"${source}/.dockerignore"
  printf 'package main\n' >"${source}/main.go"
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
    --exclude-from="${source}/.dockerignore" \
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
  mode="$(stat -f '%Lp' "${destination}/deploy/configs" 2>/dev/null || stat -c '%a' "${destination}/deploy/configs")"
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
  assert_contains "${command_log}" "RSYNC <-az> <--delete> <-e> <ssh -i '${PROTOCOL_FIXTURE_KEY}'"
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
    "${SCRIPT}" --zhuan --dry-run 2>&1
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
  assert_not_contains "${profile_content}" "create_group_command"
  assert_not_contains "${profile_content}" "change_group_announcement_command"
  assert_not_contains "${profile_content}" "send_group_message_command"
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
      run_zhuan_with_command_stubs --zhuan -y 2>&1
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
  local out
  setup_deep_check_fixture
  out="$(run_deep_check_with_stubs --env test1 --check)"
  cleanup_deep_check_fixture
  assert_contains "${out}" "[check] Kafka exact metadata: SKIPPED"
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
  local key out
  key="$(mktemp)"
  chmod 600 "${key}"
  out="$(
    ARMADA_DEPLOY_KEY="${key}" \
    ARMADA_ZHUAN_DEPLOY_KEY="${key}" \
    "${SCRIPT}" --zhuan --dry-run
  )"
  rm -f "${key}"

  assert_contains "${out}" "范围          : 只 Zhuan 协议"
  assert_contains "${out}" "Zhuan 目录"
  assert_contains "${out}" "Zhuan 目标"
  assert_contains "${out}" "[dry-run] 将同步 Zhuan 源码"
  assert_contains "${out}" "whatsapp-migrate -env prod"
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

test_zhuan_defaults_to_armada_test_host() {
  local script_content
  script_content="$(cat "${SCRIPT}")"

  assert_contains "${script_content}" 'ZHUAN_DIR="${ARMADA_ZHUAN_DIR:-${WORKSPACE_ROOT}/whatsapp-server-feature-android-zhuan}"'
  assert_contains "${script_content}" 'ZHUAN_SSH_HOST="${ARMADA_ZHUAN_DEPLOY_HOST:-${SSH_HOST}}"'
  assert_contains "${script_content}" 'ZHUAN_SSH_USER="${ARMADA_ZHUAN_DEPLOY_USER:-${SSH_USER}}"'
  assert_contains "${script_content}" 'ZHUAN_SSH_KEY="${ARMADA_ZHUAN_DEPLOY_KEY:-${SSH_KEY}}"'
  assert_contains "${script_content}" 'ZHUAN_REMOTE_DIR="${ARMADA_ZHUAN_DEPLOY_REMOTE_DIR:-/home/app/whatsapp-android-zhuan-deploy/src}"'
}

test_zhuan_rejects_unsafe_remote_dir() {
  local key out
  key="$(mktemp)"
  chmod 600 "${key}"
  if out="$(
    ARMADA_DEPLOY_KEY="${key}" \
    ARMADA_ZHUAN_DEPLOY_KEY="${key}" \
    ARMADA_ZHUAN_DEPLOY_REMOTE_DIR="/tmp/zhuan'bad" \
    "${SCRIPT}" --zhuan --dry-run 2>&1
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
      "${SCRIPT}" --zhuan --dry-run >/dev/null 2>&1; then
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
  zhuan_line="$(grep -n 'zhuan_prepare_remote' "${SCRIPT}" | head -1 | cut -d: -f1)"
  armada_remote_line="$(grep -n 'armada_prepare_remote' "${SCRIPT}" | head -1 | cut -d: -f1)"
  [ "${protocol_line}" -lt "${zhuan_line}" ] || fail "expected protocol remote deploy before Zhuan"
  [ "${zhuan_line}" -lt "${armada_remote_line}" ] || fail "expected Zhuan before Armada remote mutation"
}

test_zhuan_sync_preserves_remote_runtime_files() {
  local script_content
  script_content="$(cat "${SCRIPT_DIR}/lib/zhuan.sh")"

  assert_contains "${script_content}" 'rsync -rltz --delete -e "${ZHUAN_RSYNC_SSH}"'
  assert_contains "${script_content}" '--exclude-from="${ZHUAN_DIR}/.dockerignore"'
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
  assert_contains "${script_content}" "zhuan_prepare_remote"
  assert_contains "${script_content}" "zhuan_sync_source"
  assert_contains "${script_content}" "zhuan_deploy_remote"
  assert_contains "${script_content}" "zhuan_verify_health"
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
    ssh_run() { printf "%s\n" "$*" >>"${ARMADA_TEST_LOG}"; }
    armada_verify_backend_runtime
  '
  assert_contains "$(cat "${command_log}")" "PROTOCOL_ANDROID_BASE_URL"
  assert_contains "$(cat "${command_log}")" "PROTOCOL_ANDROID_LIFECYCLE_COMMANDS_TOPIC"
  assert_contains "$(cat "${command_log}")" "PROTOCOL_ANDROID_MESSAGE_COMMANDS_TOPIC"
  assert_contains "$(cat "${command_log}")" "PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC"
  assert_contains "$(cat "${command_log}")" "http://172.31.40.84:8001"
  assert_contains "$(cat "${command_log}")" "armada.perf.protocol.android."
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
test_assert_contains_handles_large_haystack
test_zhuan_command_flow_uses_protected_rsync_and_ordered_payload
test_zhuan_dry_run_invokes_no_external_commands
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
test_zhuan_defaults_to_armada_test_host
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
test_armada_module_checks_frontend_title_and_api_proxy
test_main_orchestrator_uses_armada_module
test_armada_compose_passes_android_base_url_to_backend
test_kafka_checker_redacts_connection_errors
test_kafka_checker_reports_consumer_group_state_read_only
printf 'OK deploy-test.sh protocol and zhuan tests passed\n'
