#!/usr/bin/env bash

set -euo pipefail

e2e_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
api_dir="$(cd "$e2e_dir/../../.." && pwd)"
front_dir="${ARMADA_E2E_FRONT_DIR:-$(cd "$api_dir/../../wheel-saas-pure-web" && pwd)}"
compose_file="$e2e_dir/compose.yaml"
artifact_dir="$front_dir/.e2e-artifacts"
backend_pid=""
frontend_pid=""

mkdir -p "$artifact_dir"
export COMPOSE_PROGRESS=plain

terminate_process_tree() {
  local process_id="$1"
  if [[ -z "$process_id" ]]; then
    return
  fi
  pkill -TERM -P "$process_id" 2>/dev/null || true
  kill "$process_id" 2>/dev/null || true
  for _ in {1..20}; do
    if ! kill -0 "$process_id" 2>/dev/null; then
      wait "$process_id" 2>/dev/null || true
      return
    fi
    sleep 0.25
  done
  pkill -KILL -P "$process_id" 2>/dev/null || true
  kill -KILL "$process_id" 2>/dev/null || true
  wait "$process_id" 2>/dev/null || true
}

cleanup() {
  set +e
  terminate_process_tree "$frontend_pid"
  terminate_process_tree "$backend_pid"
  docker compose -f "$compose_file" down --volumes --remove-orphans
}
trap cleanup EXIT INT TERM

wait_for_url() {
  local name="$1"
  local url="$2"
  local log_file="$3"
  local attempts="$4"
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if curl --fail --silent --output /dev/null "$url"; then
      return 0
    fi
    sleep 1
  done
  echo "$name did not become ready: $url" >&2
  tail -n 120 "$log_file" >&2 || true
  return 1
}

docker compose -f "$compose_file" down --volumes --remove-orphans
docker compose -f "$compose_file" up --detach --wait

(
  cd "$api_dir"
  mvn -q clean package -DskipTests
  exec /usr/bin/env \
    JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home \
    DB_URL='jdbc:mysql://127.0.0.1:13316/armada_e2e?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
    DB_USER=armada_e2e \
    DB_PASSWORD=armada_e2e \
    ANDROID_IMAGE_REDIS_ADDRESSES=127.0.0.1:16389 \
    AUTH_SESSION_KEY_PREFIX=armada:e2e: \
    SPRING_KAFKA_LISTENER_AUTO_STARTUP=false \
    PROTOCOL_COMMAND_DISPATCH_SCHEDULER_ENABLED=false \
    PROMOTION_CAPI_SCHEDULER_ENABLED=false \
    PROMOTION_PAIRING_EXPIRY_SCAN_ENABLED=false \
    ACCOUNT_IMPORT_ONLINE_DISPATCH_SCHEDULER_ENABLED=false \
    JOIN_TASK_DISPATCHER_ENABLED=false \
    IP_PROXY_UNAVAILABLE_RECHECK_ENABLED=false \
    ARMADA_GROUP_LINK_HEALTH_CHECK_ENABLED=false \
    mvn -q -DskipTests -Dspring-boot.run.fork=false spring-boot:run
) >"$artifact_dir/backend.log" 2>&1 &
backend_pid="$!"

wait_for_url \
  "armada-api" \
  "http://127.0.0.1:8080/api/public/auth/captcha" \
  "$artifact_dir/backend.log" \
  120

docker compose -f "$compose_file" exec -T mysql \
  mysql --default-character-set=utf8mb4 \
  -uarmada_e2e -parmada_e2e armada_e2e <"$e2e_dir/group-marketing-seed.sql"

(
  cd "$front_dir"
  exec pnpm dev
) >"$artifact_dir/frontend.log" 2>&1 &
frontend_pid="$!"

wait_for_url \
  "wheel-saas-pure-web" \
  "http://127.0.0.1:8848/" \
  "$artifact_dir/frontend.log" \
  120

(
  cd "$front_dir"
  ARMADA_E2E_BASE_URL=http://127.0.0.1:8848 \
    pnpm test:e2e:group-marketing
)
