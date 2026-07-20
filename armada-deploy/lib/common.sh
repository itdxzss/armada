#!/usr/bin/env bash

armada_init_colors() {
  if [ -t 1 ]; then
    C_B=$'\033[1m'
    C_G=$'\033[32m'
    C_Y=$'\033[33m'
    C_R=$'\033[31m'
    C_0=$'\033[0m'
  else
    C_B=
    C_G=
    C_Y=
    C_R=
    C_0=
  fi
}

info() { printf '%s\n' "${C_B}> $*${C_0}"; }
ok() { printf '%s\n' "${C_G}OK $*${C_0}"; }
warn() { printf '%s\n' "${C_Y}WARN $*${C_0}"; }
die() { printf '%s\n' "${C_R}ERR $*${C_0}" >&2; exit 1; }

shell_single_quote() {
  printf '%s' "$1" | sed "s/'/'\\\\''/g"
}

validate_remote_dir() {
  local label="$1"
  local remote_dir="$2"
  case "${remote_dir}" in
    *[!A-Za-z0-9_./-]*) die "${label} 远端目录仅允许字母、数字、点、下划线、斜杠和连字符: ${remote_dir}" ;;
  esac
  case "${remote_dir}" in
    /) die "${label} 远端目录不能是根目录" ;;
    /home/*) ;;
    /*) die "${label} 远端目录必须位于 /home 下: ${remote_dir}" ;;
    *) die "${label} 远端目录必须是绝对路径: ${remote_dir}" ;;
  esac
  case "${remote_dir}" in
    *//*|*/./*|*/.) die "${label} 远端目录不能包含重复斜杠或 . 路径段: ${remote_dir}" ;;
  esac
  case "/${remote_dir#/}/" in
    */../*) die "${label} 远端目录不能包含 .. 路径段: ${remote_dir}" ;;
  esac
}

validate_ssh_identity() {
  local host="$2"
  local label="$1"
  local user="$3"
  case "${host}" in
    ''|*[!A-Za-z0-9.-]*|.*|-*) die "${label} SSH 主机不合法: ${host}" ;;
  esac
  case "${user}" in
    ''|*[!A-Za-z0-9._-]*|-*) die "${label} SSH 用户不合法: ${user}" ;;
  esac
}

require_ssh_key() {
  local key_path="$2"
  local label="$1"
  [ -f "${key_path}" ] || die "找不到${label} SSH 私钥: ${key_path}"
}

build_proxy_command() {
  local jump_host="$2"
  local jump_key_quoted
  local jump_user="$3"
  jump_key_quoted="$(shell_single_quote "$1")"
  printf "ssh -i '%s' -o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new -W %%h:%%p '%s@%s'" \
    "${jump_key_quoted}" "${jump_user}" "${jump_host}"
}

build_rsync_ssh() {
  local key_quoted proxy_command
  key_quoted="$(shell_single_quote "$1")"
  proxy_command="${2:-}"
  printf "ssh -i '%s' -o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new" "${key_quoted}"
  if [ -n "${proxy_command}" ]; then
    printf ' -o "ProxyCommand=%s"' "${proxy_command}"
  fi
}

print_repository_evidence() {
  local branch commit label repo_dir state
  label="$1"
  repo_dir="$2"
  [ -d "${repo_dir}" ] || die "${label}目录不存在: ${repo_dir}"
  git -C "${repo_dir}" rev-parse --is-inside-work-tree >/dev/null 2>&1 \
    || die "${label}不是 Git 仓库: ${repo_dir}"
  commit="$(git -C "${repo_dir}" rev-parse --short HEAD)"
  branch="$(git -C "${repo_dir}" branch --show-current)"
  [ -n "${branch}" ] || branch=DETACHED
  state=clean
  if ! git -C "${repo_dir}" diff --quiet --ignore-submodules -- \
    || ! git -C "${repo_dir}" diff --cached --quiet --ignore-submodules -- \
    || [ -n "$(git -C "${repo_dir}" ls-files --others --exclude-standard | sed -n '1p')" ]; then
    state=dirty
  fi
  printf '  %-14s: branch=%s commit=%s state=%s\n' "${label}" "${branch}" "${commit}" "${state}"
}

print_deployment_summary() {
  printf '\n%s\n' "${C_B}部署结果${C_0}"
  printf '  %-10s : %s\n' Protocol "${STATUS_PROTOCOL:-SKIPPED}"
  printf '  %-10s : %s\n' Zhuan "${STATUS_ZHUAN:-SKIPPED}"
  printf '  %-10s : %s\n' Backend "${STATUS_BACKEND:-SKIPPED}"
  printf '  %-10s : %s\n' Frontend "${STATUS_FRONTEND:-SKIPPED}"
}

mark_active_component_failed() {
  case "${ACTIVE_COMPONENT:-}" in
    protocol) STATUS_PROTOCOL=FAILED ;;
    zhuan) STATUS_ZHUAN=FAILED ;;
    backend) STATUS_BACKEND=FAILED ;;
    frontend) STATUS_FRONTEND=FAILED ;;
    armada)
      [ "${STATUS_BACKEND:-SKIPPED}" != SKIPPED ] && STATUS_BACKEND=FAILED
      [ "${STATUS_FRONTEND:-SKIPPED}" != SKIPPED ] && STATUS_FRONTEND=FAILED
      ;;
  esac
}

armada_on_exit() {
  local exit_code="$1"
  cleanup_branch_worktree
  if [ "${SUMMARY_ENABLED:-0}" = 1 ]; then
    if [ "${exit_code}" -ne 0 ]; then
      mark_active_component_failed
    fi
    print_deployment_summary
  fi
  trap - EXIT
  exit "${exit_code}"
}

cleanup_branch_worktree() {
  if [ -n "${BRANCH_WORKTREE:-}" ]; then
    git -C "${REPO_ROOT}" worktree remove --force "${BRANCH_WORKTREE}" >/dev/null 2>&1 \
      || rmdir "${BRANCH_WORKTREE}" >/dev/null 2>&1 \
      || true
  fi
}
