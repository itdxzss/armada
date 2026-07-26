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
