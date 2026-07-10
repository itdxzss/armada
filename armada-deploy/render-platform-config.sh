#!/bin/sh
set -eu

: "${APP_TITLE:=Wheel SaaS}"
: "${PLATFORM_CONFIG_ROOT:=/usr/share/nginx/html/saas}"

template="${PLATFORM_CONFIG_ROOT}/platform-config.template.json"
target="${PLATFORM_CONFIG_ROOT}/platform-config.json"

if [ ! -f "${template}" ]; then
  exit 0
fi

escaped_app_title="$(printf '%s' "${APP_TITLE}" | sed 's/\\/\\\\/g; s/"/\\"/g')"
export APP_TITLE="${escaped_app_title}"

envsubst '${APP_TITLE}' < "${template}" > "${target}"
