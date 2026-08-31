#!/usr/bin/env bash

# Resolve the single executable Maven jar produced in the target directory.
armada_resolve_backend_jar() {
  local target_dir="$1"
  local candidate=""
  local count=0
  local path=""

  [ -d "${target_dir}" ] || {
    printf 'backend target directory does not exist: %s\n' "${target_dir}" >&2
    return 1
  }
  for path in "${target_dir}"/*.jar; do
    [ -f "${path}" ] || continue
    candidate="${path}"
    count=$((count + 1))
  done

  if [ "${count}" -ne 1 ]; then
    printf 'expected exactly one executable jar in %s, found %s\n' \
      "${target_dir}" "${count}" >&2
    return 1
  fi
  printf '%s\n' "${candidate}"
}

# 在 macOS 或 Linux 上计算部署产物 SHA-256。
armada_sha256_file() {
  local digest file_path
  file_path="$1"
  [ -f "${file_path}" ] || {
    printf 'artifact does not exist: %s\n' "${file_path}" >&2
    return 1
  }

  if command -v sha256sum >/dev/null 2>&1; then
    digest="$(sha256sum "${file_path}" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    digest="$(shasum -a 256 "${file_path}" | awk '{print $1}')"
  else
    printf 'SHA-256 tool not found; install sha256sum or shasum\n' >&2
    return 1
  fi
  case "${digest}" in
    *[!0-9a-fA-F]*|'')
      printf 'invalid SHA-256 for artifact %s\n' "${file_path}" >&2
      return 1
      ;;
  esac
  [ "${#digest}" -eq 64 ] || {
    printf 'invalid SHA-256 length for artifact %s\n' "${file_path}" >&2
    return 1
  }
  printf '%s\n' "${digest}"
}
