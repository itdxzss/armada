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
  printf '%s' "${haystack}" | grep -Fq -- "${needle}" || fail "expected output to contain: ${needle}"
}

assert_not_contains() {
  local haystack="$1"
  local needle="$2"
  if printf '%s' "${haystack}" | grep -Fq -- "${needle}"; then
    fail "expected output not to contain: ${needle}"
  fi
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

  assert_contains "${script_content}" '--exclude-from="${ZHUAN_DIR}/.dockerignore"'
  assert_contains "${script_content}" "--exclude=deploy/.env"
  assert_contains "${script_content}" "--exclude=deploy/configs/prod_configs.toml"
  assert_contains "${script_content}" "--exclude=deploy/logs/"
  assert_contains "${script_content}" "--exclude=deploy/callback-logs/"
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

test_help_mentions_protocol_scope
test_protocol_dry_run_is_protocol_only
test_protocol_default_key_uses_testpem_directory
test_zhuan_dry_run_is_zhuan_only
test_full_includes_zhuan_but_all_does_not
test_zhuan_defaults_to_armada_test_host
test_armada_default_key_uses_testpem_directory
test_frontend_dry_run_infers_second_environment_title
test_sh_invocation_reexecs_bash_for_help
test_protocol_remote_deploy_requires_node_24_toolchain
test_protocol_remote_deploy_verifies_node_24_apps_after_reload
test_zhuan_sync_preserves_remote_runtime_files
test_zhuan_remote_deploy_checks_config_and_runs_lifecycle
printf 'OK deploy-test.sh protocol tests passed\n'
