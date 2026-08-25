#!/usr/bin/env bash

set -eu

: "${PREFLIGHT_FIXTURE_LOG:?PREFLIGHT_FIXTURE_LOG is required}"

printf '%s\n' "$*" >>"${PREFLIGHT_FIXTURE_LOG}"
printf '%s\n' "${PREFLIGHT_FIXTURE_OUTPUT:-fixture deep check}"
exit "${PREFLIGHT_FIXTURE_EXIT_CODE:-0}"
