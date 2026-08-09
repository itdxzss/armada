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

armada_metrics_init() {
  local metrics_file="${1:-}"
  if [ -z "${metrics_file}" ]; then
    metrics_file="$(mktemp "${TMPDIR:-/tmp}/armada-deploy-metrics.XXXXXX")"
  fi
  : >"${metrics_file}"
  ARMADA_DEPLOY_METRICS_FILE="${metrics_file}"
  ARMADA_DEPLOY_METRICS_ACTIVE_STAGE=""
  ARMADA_DEPLOY_METRICS_ACTIVE_STARTED_AT=0
}

armada_metrics_is_active() {
  [ -n "${ARMADA_DEPLOY_METRICS_FILE:-}" ] && [ -f "${ARMADA_DEPLOY_METRICS_FILE}" ]
}

armada_metrics_record_stage() {
  local duration_seconds="$2"
  local exit_code="$3"
  armada_metrics_is_active || return 0
  printf 'stage\t%s\t%s\t%s\n' "$1" "${duration_seconds}" "${exit_code}" \
    >>"${ARMADA_DEPLOY_METRICS_FILE}"
}

armada_metrics_record_rsync_stats() {
  local changed_files metrics_log scanned_files transferred_bytes
  local label="$1"
  metrics_log="$2"
  armada_metrics_is_active || return 0

  scanned_files="$(LC_ALL=C awk '
    function value(line) {
      sub(/^[^:]*:[[:space:]]*/, "", line)
      sub(/[[:space:]].*$/, "", line)
      gsub(/,/, "", line)
      return line
    }
    /^Number of files:/ { total += value($0); found = 1 }
    END { if (found) print total }
  ' "${metrics_log}")"
  changed_files="$(LC_ALL=C awk '
    function value(line) {
      sub(/^[^:]*:[[:space:]]*/, "", line)
      sub(/[[:space:]].*$/, "", line)
      gsub(/,/, "", line)
      return line
    }
    /^Number of regular files transferred:/ { regular += value($0); found_regular = 1; next }
    /^Number of files transferred:/ { generic += value($0); found_generic = 1 }
    END {
      if (found_regular) print regular
      else if (found_generic) print generic
    }
  ' "${metrics_log}")"
  transferred_bytes="$(LC_ALL=C awk '
    function value(line) {
      sub(/^[^:]*:[[:space:]]*/, "", line)
      sub(/[[:space:]].*$/, "", line)
      gsub(/,/, "", line)
      return line
    }
    /^Total transferred file size:/ { total += value($0); found = 1 }
    END { if (found) print total }
  ' "${metrics_log}")"
  [ -n "${scanned_files}${changed_files}${transferred_bytes}" ] || return 0
  printf 'rsync\t%s\t%s\t%s\t%s\n' "${label}" \
    "${scanned_files:-unknown}" "${changed_files:-unknown}" "${transferred_bytes:-unknown}" \
    >>"${ARMADA_DEPLOY_METRICS_FILE}"
}

armada_metrics_record_docker_cache() {
  local cache_hits cache_steps metrics_log
  local label="$1"
  metrics_log="$2"
  armada_metrics_is_active || return 0

  cache_steps="$(LC_ALL=C awk '
    /^#[0-9][0-9]* / {
      step = $1
      if ($0 ~ / CACHED([[:space:]]|$)/) {
        completed[step] = 1
        cached[step] = 1
      } else if ($0 ~ / DONE([[:space:]]|$)/) {
        completed[step] = 1
      }
    }
    END {
      for (step in completed) {
        total++
        if (cached[step]) hits++
      }
      if (total > 0) print hits "\t" total
    }
  ' "${metrics_log}")"
  [ -n "${cache_steps}" ] || return 0
  IFS=$'\t' read -r cache_hits cache_steps <<<"${cache_steps}"
  printf 'docker\t%s\t%s\t%s\n' "${label}" "${cache_hits}" "${cache_steps}" \
    >>"${ARMADA_DEPLOY_METRICS_FILE}"
}

armada_format_bytes() {
  awk -v bytes="$1" '
    BEGIN {
      split("B KiB MiB GiB TiB", units, " ")
      value = bytes + 0
      unit = 1
      while (value >= 1024 && unit < 5) {
        value /= 1024
        unit++
      }
      if (unit == 1) printf "%.0f %s", value, units[unit]
      else printf "%.1f %s", value, units[unit]
    }
  '
}

armada_metrics_start() {
  local label="$1"
  local started_at
  armada_metrics_is_active || return 0
  started_at="$(date +%s)"
  ARMADA_DEPLOY_METRICS_ACTIVE_STAGE="${label}"
  ARMADA_DEPLOY_METRICS_ACTIVE_STARTED_AT="${started_at}"
}

armada_measure() {
  local label="$1"
  shift
  armada_metrics_start "${label}"
  "$@"
  armada_metrics_finish_active 0
}

armada_measure_or_die() {
  local error_message exit_code label
  label="$1"
  shift
  error_message="$1"
  shift
  armada_metrics_start "${label}"
  if "$@"; then
    exit_code=0
  else
    exit_code=$?
  fi
  armada_metrics_finish_active "${exit_code}"
  [ "${exit_code}" = 0 ] || die "${error_message}"
}

armada_metrics_finish_active() {
  local duration_seconds ended_at exit_code started_at
  exit_code="$1"
  [ -n "${ARMADA_DEPLOY_METRICS_ACTIVE_STAGE:-}" ] || return 0
  started_at="${ARMADA_DEPLOY_METRICS_ACTIVE_STARTED_AT:-0}"
  ended_at="$(date +%s)"
  duration_seconds=$((ended_at - started_at))
  armada_metrics_record_stage "${ARMADA_DEPLOY_METRICS_ACTIVE_STAGE}" "${duration_seconds}" "${exit_code}"
  ARMADA_DEPLOY_METRICS_ACTIVE_STAGE=""
  ARMADA_DEPLOY_METRICS_ACTIVE_STARTED_AT=0
}

armada_rsync() {
  local exit_code metrics_log
  local label="$1"
  shift
  metrics_log="$(mktemp "${TMPDIR:-/tmp}/armada-rsync-stats.XXXXXX")"
  if LC_ALL=C rsync --stats "$@" >"${metrics_log}" 2>&1; then
    exit_code=0
  else
    exit_code=$?
  fi
  cat "${metrics_log}"
  armada_metrics_record_rsync_stats "${label}" "${metrics_log}"
  rm -f -- "${metrics_log}"
  return "${exit_code}"
}

armada_capture_docker_build_output() {
  local exit_code metrics_log
  local pipe_status=()
  local label="$1"
  shift
  if ! armada_metrics_is_active; then
    "$@"
    return
  fi

  metrics_log="$(mktemp "${TMPDIR:-/tmp}/armada-docker-build.XXXXXX")"
  if "$@" 2>&1 | tee "${metrics_log}"; then
    pipe_status=("${PIPESTATUS[@]}")
  else
    pipe_status=("${PIPESTATUS[@]}")
  fi
  if [ "${pipe_status[0]}" -ne 0 ]; then
    exit_code="${pipe_status[0]}"
  elif [ "${pipe_status[1]}" -ne 0 ]; then
    exit_code="${pipe_status[1]}"
  else
    exit_code=0
  fi
  armada_metrics_record_rsync_stats "${label}" "${metrics_log}"
  armada_metrics_record_docker_cache "${label}" "${metrics_log}"
  rm -f -- "${metrics_log}"
  return "${exit_code}"
}

print_deployment_metrics_summary() {
  local bytes cache_hits cache_steps changed_files duration_seconds exit_code label record_type scanned_files status transferred_bytes
  armada_metrics_is_active || return 0
  [ -s "${ARMADA_DEPLOY_METRICS_FILE}" ] || return 0

  printf '\n%s\n' "${C_B}部署性能摘要${C_0}"
  while IFS=$'\t' read -r record_type label duration_seconds exit_code transferred_bytes; do
    case "${record_type}" in
      stage)
        status=SUCCESS
        [ "${exit_code}" = 0 ] || status=FAILED
        printf '  阶段 %s: %ss (%s)\n' "${label}" "${duration_seconds}" "${status}"
        ;;
      rsync)
        scanned_files="${duration_seconds}"
        changed_files="${exit_code}"
        if [ "${transferred_bytes}" = unknown ]; then
          bytes=unknown
        else
          bytes="$(armada_format_bytes "${transferred_bytes}")"
        fi
        printf '  同步 %s: scanned=%s changed=%s transferred=%s\n' \
          "${label}" "${scanned_files}" "${changed_files}" "${bytes}"
        ;;
      docker)
        cache_hits="${duration_seconds}"
        cache_steps="${exit_code}"
        printf '  Docker %s: cache=%s/%s (%s%%)\n' \
          "${label}" "${cache_hits}" "${cache_steps}" "$((cache_hits * 100 / cache_steps))"
        ;;
    esac
  done <"${ARMADA_DEPLOY_METRICS_FILE}"
}

armada_metrics_cleanup() {
  if [ -n "${ARMADA_DEPLOY_METRICS_FILE:-}" ]; then
    rm -f -- "${ARMADA_DEPLOY_METRICS_FILE}"
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
    armada_metrics_finish_active "${exit_code}"
    if [ "${exit_code}" -ne 0 ]; then
      mark_active_component_failed
    fi
    print_deployment_summary
    print_deployment_metrics_summary
  fi
  armada_metrics_cleanup
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
