#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${SCRIPT_DIR}/deploy-test.sh"

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
  : >"${ZHUAN_FIXTURE_KEY}"
  : >"${ZHUAN_FIXTURE_COMMAND_LOG}"
  : >"${ZHUAN_FIXTURE_PAYLOAD_LOG}"
  chmod 600 "${ZHUAN_FIXTURE_KEY}"

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

  config_line="$(awk 'index($0, "sudo docker compose config --quiet") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  build_line="$(awk 'index($0, "sudo docker compose build whatsapp-android-zhuan") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  deps_line="$(awk 'index($0, "sudo docker compose up -d redis-zhuan callback-zhuan") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  migrate_line="$(awk 'index($0, "whatsapp-migrate -env prod") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  main_line="$(awk 'index($0, "sudo docker compose up -d whatsapp-android-zhuan") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  health_line="$(awk 'index($0, "curl -fsS -m 8 http://127.0.0.1:8001/swagger/index.html") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
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
  if ZHUAN_TEST_FAIL_PAYLOAD="sudo docker compose build whatsapp-android-zhuan" \
    run_zhuan_with_command_stubs --zhuan -y >/dev/null 2>&1; then
    cleanup_zhuan_command_fixture
    fail "expected failed remote lifecycle to stop deployment"
  fi
  payload_log="$(cat "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  assert_not_contains "${payload_log}" "curl -fsS -m 8 http://127.0.0.1:8001/swagger/index.html"
  cleanup_zhuan_command_fixture
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
  assert_contains "${out}" "[dry-run] 将构建协议层"
  assert_not_contains "${out}" "后端 JDK"
  assert_not_contains "${out}" "前端构建"
}

test_protocol_default_key_uses_testpem_directory() {
  local script_content
  script_content="$(sed -n '1,40p' "${SCRIPT}")"
  assert_contains "${script_content}" 'PROTOCOL_SSH_KEY="${ARMADA_PROTOCOL_DEPLOY_KEY:-${WORKSPACE_ROOT}/测试pem/protocol.pem}"'
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
  script_content="$(sed -n '1,55p' "${SCRIPT}")"

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

test_armada_default_key_uses_testpem_directory() {
  local script_content
  script_content="$(sed -n '1,40p' "${SCRIPT}")"
  assert_contains "${script_content}" 'SSH_KEY="${ARMADA_DEPLOY_KEY:-${WORKSPACE_ROOT}/测试pem/dev-1.pem}"'
}

test_frontend_dry_run_infers_second_environment_title() {
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
  assert_contains "${out}" "环境标识      : 第二套环境"
  assert_contains "${out}" "APP_TITLE='第二套环境' docker compose"
}

test_sh_invocation_reexecs_bash_for_help() {
  local out
  out="$(sh "${SCRIPT}" --help)"
  assert_contains "${out}" "deploy-test.sh - 部署 armada API"
  assert_contains "${out}" "--protocol"
}

test_protocol_remote_deploy_requires_node_24_toolchain() {
  local script_content
  script_content="$(cat "${SCRIPT}")"

  assert_contains "${script_content}" 'node_version="$(node --version)"'
  assert_contains "${script_content}" '远端 Node.js 必须为 24.x'
  assert_contains "${script_content}" 'PM2 daemon 必须运行在 Node.js 24.x'
}

test_protocol_remote_deploy_verifies_node_24_apps_after_reload() {
  local script_content
  script_content="$(cat "${SCRIPT}")"

  assert_contains "${script_content}" '协议 PM2 应用必须全部运行在 Node.js 24.x'
  assert_contains "${script_content}" 'expectedProtocolApps = 5'
}

test_zhuan_sync_preserves_remote_runtime_files() {
  local script_content
  script_content="$(cat "${SCRIPT}")"

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
  script_content="$(cat "${SCRIPT}")"

  assert_contains "${script_content}" 'test -f "${remote_dir}/deploy/.env"'
  assert_contains "${script_content}" 'test -f "${remote_dir}/deploy/configs/prod_configs.toml"'
  assert_contains "${script_content}" "sudo docker compose config --quiet"
  assert_contains "${script_content}" "sudo docker compose build whatsapp-android-zhuan"
  assert_contains "${script_content}" "sudo docker compose up -d redis-zhuan callback-zhuan"
  assert_contains "${script_content}" "sudo docker compose run --rm whatsapp-android-zhuan /app/whatsapp-migrate -env prod"
  assert_contains "${script_content}" "sudo docker compose up -d whatsapp-android-zhuan"
}

test_zhuan_deploy_waits_for_health_and_checks_http() {
  local script_content
  script_content="$(cat "${SCRIPT}")"

  assert_contains "${script_content}" "redis-zhuan callback-zhuan whatsapp-android-zhuan"
  assert_contains "${script_content}" "running/healthy"
  assert_contains "${script_content}" "Zhuan 容器未在时限内就绪"
  assert_contains "${script_content}" "http://127.0.0.1:8001/swagger/index.html"
}

test_zhuan_only_logs_follow_main_container() {
  local script_content
  script_content="$(cat "${SCRIPT}")"

  assert_contains "${script_content}" 'elif [ "${TAIL_LOGS}" = 1 ] && [ "${BUILD_ZHUAN}" = 1 ]; then'
  assert_contains "${script_content}" "sudo docker compose logs -f --tail 120 whatsapp-android-zhuan"
}

test_assert_contains_handles_large_haystack
test_zhuan_command_flow_uses_protected_rsync_and_ordered_payload
test_zhuan_dry_run_invokes_no_external_commands
test_zhuan_remote_failure_stops_before_health_check
test_zhuan_rsync_filters_preserve_runtime_files_and_modes
test_help_mentions_protocol_scope
test_protocol_dry_run_is_protocol_only
test_protocol_default_key_uses_testpem_directory
test_zhuan_dry_run_is_zhuan_only
test_full_includes_zhuan_but_all_does_not
test_zhuan_defaults_to_armada_test_host
test_zhuan_rejects_unsafe_remote_dir
test_zhuan_rejects_root_equivalent_remote_dirs
test_armada_default_key_uses_testpem_directory
test_frontend_dry_run_infers_second_environment_title
test_sh_invocation_reexecs_bash_for_help
test_protocol_remote_deploy_requires_node_24_toolchain
test_protocol_remote_deploy_verifies_node_24_apps_after_reload
test_zhuan_sync_preserves_remote_runtime_files
test_zhuan_remote_deploy_checks_config_and_runs_lifecycle
test_zhuan_deploy_waits_for_health_and_checks_http
test_zhuan_only_logs_follow_main_container
printf 'OK deploy-test.sh protocol and zhuan tests passed\n'
