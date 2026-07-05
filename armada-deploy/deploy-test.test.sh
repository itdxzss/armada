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

test_help_mentions_protocol_scope
test_protocol_dry_run_is_protocol_only
printf 'OK deploy-test.sh protocol tests passed\n'
