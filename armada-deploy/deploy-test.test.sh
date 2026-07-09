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
  assert_contains "${out}" "--full"
  assert_contains "${out}" "ARMADA_PROTOCOL_DEPLOY_HOST"
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

test_help_mentions_protocol_scope
test_protocol_dry_run_is_protocol_only
test_protocol_default_key_uses_testpem_directory
test_armada_default_key_uses_testpem_directory
test_frontend_dry_run_infers_second_environment_title
test_sh_invocation_reexecs_bash_for_help
printf 'OK deploy-test.sh protocol tests passed\n'
