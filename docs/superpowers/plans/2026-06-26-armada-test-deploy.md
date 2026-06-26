# Armada Test Deploy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a one-command staging deployment path for `armada-api` plus `wheel-saas-pure-web`, deployed as an isolated Docker Compose project on the existing wheel test server.

**Architecture:** The deploy flow is local-prebuilt: build the Spring Boot jar and Vue static dist locally, rsync only artifacts plus deployment manifests to `/home/app/armada-deploy`, then run Docker Compose remotely. Nginx serves the frontend on host port `18080` and proxies `/api/` to `armada-backend:8080`; remote `.env` supplies the RDS schema and secrets and is never overwritten.

**Tech Stack:** Bash, rsync, SSH, Docker Compose, Nginx 1.27 Alpine, Eclipse Temurin 17 JRE, Maven, pnpm, Spring Boot 3.3.5, Vue/Vite.

---

## Scope

This plan implements only the Web/API deployment slice from `docs/superpowers/specs/2026-06-26-armada-test-deploy-design.md`.

`armada-protocol` remains a later, separate旁路部署 task because it targets a different host and a different runtime model (`systemd` Node service instead of Docker Compose Web/API).

## File Structure

- Create `armada-deploy/.env.example`: non-secret staging environment template for the remote `/home/app/armada-deploy/.env`.
- Create `armada-deploy/backend.prebuilt.Dockerfile`: runtime image that copies the locally built `armada-api` jar.
- Create `armada-deploy/nginx.prebuilt.Dockerfile`: Nginx runtime image that copies `wheel-saas-pure-web/dist`.
- Create `armada-deploy/nginx.conf`: single-site SPA hosting plus same-origin `/api/` reverse proxy.
- Create `armada-deploy/docker-compose.rds.yml`: isolated Compose project definition for `armada-backend` and `armada-nginx`.
- Create `armada-deploy/deploy-test.sh`: one-command local prebuilt deployment script.

## Constraints

- Do not modify existing business code.
- Do not touch old `wheel-deploy` files or containers.
- Do not write or overwrite remote `/home/app/armada-deploy/.env`.
- Do not create, drop, truncate, or migrate schemas outside normal `armada-api` Flyway startup.
- Do not run `docker compose down -v`.
- Keep all deployment shell scripts ASCII-only and use `${VAR}` where a variable is followed by punctuation.
- Stage and commit only files under `armada-deploy/` and this plan when executing.

---

### Task 1: Create Deployment Manifests

**Files:**
- Create: `armada-deploy/.env.example`
- Create: `armada-deploy/backend.prebuilt.Dockerfile`
- Create: `armada-deploy/nginx.prebuilt.Dockerfile`
- Create: `armada-deploy/nginx.conf`
- Create: `armada-deploy/docker-compose.rds.yml`

- [ ] **Step 1: Create `armada-deploy/.env.example`**

Create the file with this exact content:

```env
# Armada staging deployment env.
# Copy this file to /home/app/armada-deploy/.env on the test server and fill real values there.
# deploy-test.sh syncs this example but never overwrites the real .env.

ARMADA_HTTP_PORT=18080

DB_URL=jdbc:mysql://REPLACE_RDS_ENDPOINT:3306/armada?useSSL=false&serverTimezone=UTC&characterEncoding=utf8mb4
DB_USER=REPLACE_DB_USER
DB_PASSWORD=REPLACE_DB_PASSWORD

DEV_LOGIN_PASSWORD=armada123
JAVA_TOOL_OPTIONS=-Xms1024m -Xmx1024m
```

- [ ] **Step 2: Create `armada-deploy/backend.prebuilt.Dockerfile`**

Create the file with this exact content:

```dockerfile
FROM eclipse-temurin:17-jre

WORKDIR /app

ENV TZ=UTC

COPY armada-api/target/armada-api-1.0.0-SNAPSHOT.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 3: Create `armada-deploy/nginx.prebuilt.Dockerfile`**

Create the file with this exact content:

```dockerfile
FROM nginx:1.27-alpine

RUN rm -f /etc/nginx/conf.d/default.conf

COPY nginx.conf /etc/nginx/conf.d/armada.conf
COPY wheel-saas-pure-web/dist /usr/share/nginx/html/saas

EXPOSE 80
```

- [ ] **Step 4: Create `armada-deploy/nginx.conf`**

Create the file with this exact content:

```nginx
upstream armada_backend {
    server backend:8080;
    keepalive 32;
}

server {
    listen 80;
    server_name _;

    root /usr/share/nginx/html/saas;
    index index.html;

    access_log /var/log/nginx/armada.access.log;
    error_log  /var/log/nginx/armada.error.log warn;

    location /api/ {
        proxy_pass http://armada_backend;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection        "";
        proxy_connect_timeout 5s;
        proxy_read_timeout   120s;
        proxy_send_timeout   120s;
    }

    location ^~ /static/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
        try_files $uri =404;
    }

    location ^~ /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
        try_files $uri =404;
    }

    location / {
        add_header Cache-Control "no-cache";
        try_files $uri $uri/ /index.html;
    }
}
```

- [ ] **Step 5: Create `armada-deploy/docker-compose.rds.yml`**

Create the file with this exact content:

```yaml
services:
  backend:
    build:
      context: .
      dockerfile: backend.prebuilt.Dockerfile
    container_name: armada-backend
    environment:
      DB_URL: ${DB_URL}
      DB_USER: ${DB_USER}
      DB_PASSWORD: ${DB_PASSWORD}
      DEV_LOGIN_PASSWORD: ${DEV_LOGIN_PASSWORD:-armada123}
      JAVA_TOOL_OPTIONS: ${JAVA_TOOL_OPTIONS:--Xms1024m -Xmx1024m}
    expose:
      - "8080"
    restart: unless-stopped

  nginx:
    build:
      context: .
      dockerfile: nginx.prebuilt.Dockerfile
    container_name: armada-nginx
    depends_on:
      - backend
    ports:
      - "${ARMADA_HTTP_PORT:-18080}:80"
    restart: unless-stopped
```

- [ ] **Step 6: Verify Compose renders locally**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
mkdir -p /private/tmp/armada-compose-check
cp armada-deploy/.env.example /private/tmp/armada-compose-check/.env
cp armada-deploy/docker-compose.rds.yml /private/tmp/armada-compose-check/docker-compose.rds.yml
cp armada-deploy/backend.prebuilt.Dockerfile /private/tmp/armada-compose-check/backend.prebuilt.Dockerfile
cp armada-deploy/nginx.prebuilt.Dockerfile /private/tmp/armada-compose-check/nginx.prebuilt.Dockerfile
cp armada-deploy/nginx.conf /private/tmp/armada-compose-check/nginx.conf
docker compose --env-file /private/tmp/armada-compose-check/.env -f /private/tmp/armada-compose-check/docker-compose.rds.yml config >/private/tmp/armada-compose-check/config.out
```

Expected:

```text
command exits 0
```

If Docker is unavailable locally, run this fallback syntax check:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
bash -n armada-deploy/deploy-test.sh 2>/dev/null || true
```

- [ ] **Step 7: Commit**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-deploy/.env.example armada-deploy/backend.prebuilt.Dockerfile armada-deploy/nginx.prebuilt.Dockerfile armada-deploy/nginx.conf armada-deploy/docker-compose.rds.yml
git commit -m "feat(deploy): add armada compose manifests"
```

---

### Task 2: Create Local-Prebuilt Deploy Script

**Files:**
- Create: `armada-deploy/deploy-test.sh`

- [ ] **Step 1: Create `armada-deploy/deploy-test.sh`**

Create the executable Bash script with this content:

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/.." && pwd)"

SSH_HOST="${ARMADA_DEPLOY_HOST:-ec2-65-2-123-53.ap-south-1.compute.amazonaws.com}"
SSH_USER="${ARMADA_DEPLOY_USER:-ubuntu}"
SSH_KEY="${ARMADA_DEPLOY_KEY:-${WORKSPACE_ROOT}/dev-1.pem}"
REMOTE_DIR="${ARMADA_DEPLOY_REMOTE_DIR:-/home/app/armada-deploy}"
COMPOSE_FILE="${ARMADA_DEPLOY_COMPOSE:-docker-compose.rds.yml}"
COMPOSE_PROJECT="${ARMADA_DEPLOY_PROJECT:-armada-deploy}"
FRONTEND_DIR="${ARMADA_FRONTEND_DIR:-${WORKSPACE_ROOT}/wheel-saas-pure-web}"
API_DIR="${REPO_ROOT}/armada-api"
JAR_NAME="armada-api-1.0.0-SNAPSHOT.jar"
JAR_PATH="${API_DIR}/target/${JAR_NAME}"

SCOPE="all"
ASSUME_YES=0
DRY_RUN=0
TAIL_LOGS=0

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

info() { printf '%s\n' "${C_B}> $*${C_0}"; }
ok() { printf '%s\n' "${C_G}OK $*${C_0}"; }
warn() { printf '%s\n' "${C_Y}WARN $*${C_0}"; }
die() { printf '%s\n' "${C_R}ERR $*${C_0}" >&2; exit 1; }

usage() {
  cat <<EOF
deploy-test.sh - deploy armada API + wheel-saas-pure-web to the test server.

Usage:
  ./armada-deploy/deploy-test.sh [options]

Options:
  --be             Build and deploy backend only.
  --fe             Build and deploy frontend/nginx only.
  -y, --yes        Skip confirmation.
  --logs           Tail backend logs after deploy.
  -n, --dry-run    Print the plan without building, syncing, or restarting.
  -h, --help       Show this help.

Environment overrides:
  ARMADA_DEPLOY_HOST
  ARMADA_DEPLOY_USER
  ARMADA_DEPLOY_KEY
  ARMADA_DEPLOY_REMOTE_DIR
  ARMADA_FRONTEND_DIR

Target:
  ${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}

Entrypoint:
  http://65.2.123.53:18080/
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --be) SCOPE="be" ;;
    --fe) SCOPE="fe" ;;
    -y|--yes) ASSUME_YES=1 ;;
    --logs) TAIL_LOGS=1 ;;
    -n|--dry-run) DRY_RUN=1 ;;
    -h|--help) usage; exit 0 ;;
    *) usage; die "unknown argument: $1" ;;
  esac
  shift
done

BUILD_BE=0
BUILD_FE=0
SERVICES=""
case "${SCOPE}" in
  all)
    BUILD_BE=1
    BUILD_FE=1
    SERVICES=""
    SCOPE_DESC="backend + frontend"
    ;;
  be)
    BUILD_BE=1
    SERVICES="backend"
    SCOPE_DESC="backend only"
    ;;
  fe)
    BUILD_FE=1
    SERVICES="nginx"
    SCOPE_DESC="frontend only"
    ;;
  *)
    die "invalid scope: ${SCOPE}"
    ;;
esac

find_jdk17() {
  local candidate=""
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    candidate="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi
  if [ -z "${candidate}" ]; then
    candidate="${JAVA17_HOME:-${JAVA_HOME:-}}"
  fi
  if [ -n "${candidate}" ] && [ -x "${candidate}/bin/javac" ]; then
    printf '%s\n' "${candidate}"
    return 0
  fi
  return 1
}

JDK17_HOME=""
if [ "${BUILD_BE}" = 1 ]; then
  JDK17_HOME="$(find_jdk17)" || die "JDK 17 is required. Install JDK 17 or set JAVA17_HOME."
fi

[ -f "${SSH_KEY}" ] || die "SSH key not found: ${SSH_KEY}"
[ -d "${API_DIR}" ] || die "armada-api directory not found: ${API_DIR}"
[ -f "${API_DIR}/pom.xml" ] || die "armada-api pom.xml not found"
[ -d "${FRONTEND_DIR}" ] || die "frontend directory not found: ${FRONTEND_DIR}"
[ -f "${FRONTEND_DIR}/package.json" ] || die "frontend package.json not found"
[ -f "${SCRIPT_DIR}/docker-compose.rds.yml" ] || die "missing ${SCRIPT_DIR}/docker-compose.rds.yml"
[ -f "${SCRIPT_DIR}/backend.prebuilt.Dockerfile" ] || die "missing backend.prebuilt.Dockerfile"
[ -f "${SCRIPT_DIR}/nginx.prebuilt.Dockerfile" ] || die "missing nginx.prebuilt.Dockerfile"
[ -f "${SCRIPT_DIR}/nginx.conf" ] || die "missing nginx.conf"

if [ "${BUILD_BE}" = 1 ]; then
  command -v mvn >/dev/null 2>&1 || die "mvn is required for backend build"
fi
if [ "${BUILD_FE}" = 1 ]; then
  command -v pnpm >/dev/null 2>&1 || die "pnpm is required for frontend build"
fi
command -v rsync >/dev/null 2>&1 || die "rsync is required"
command -v ssh >/dev/null 2>&1 || die "ssh is required"

SSH_OPTS=(
  -i "${SSH_KEY}"
  -o BatchMode=yes
  -o ConnectTimeout=15
  -o StrictHostKeyChecking=accept-new
)
RSYNC_SSH="ssh -i ${SSH_KEY} -o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new"

ssh_run() {
  ssh "${SSH_OPTS[@]}" "${SSH_USER}@${SSH_HOST}" "$@"
}

remote_required_env_check='
set -eu
cd "$1"
test -f .env || { echo "missing remote .env: $1/.env" >&2; exit 20; }
for key in DB_URL DB_USER DB_PASSWORD; do
  grep -Eq "^${key}=.+" .env || { echo "missing required ${key} in $1/.env" >&2; exit 21; }
done
'

print_plan() {
  echo
  info "Deploy plan"
  printf '  scope        : %s\n' "${SCOPE_DESC}"
  printf '  repo root    : %s\n' "${REPO_ROOT}"
  printf '  frontend dir : %s\n' "${FRONTEND_DIR}"
  printf '  target       : %s@%s:%s\n' "${SSH_USER}" "${SSH_HOST}" "${REMOTE_DIR}"
  printf '  compose      : %s / project=%s\n' "${COMPOSE_FILE}" "${COMPOSE_PROJECT}"
  if [ "${BUILD_BE}" = 1 ]; then
    printf '  backend JDK  : %s\n' "${JDK17_HOME}"
  fi
  echo
}

print_plan

if [ "${DRY_RUN}" = 1 ]; then
  info "[dry-run] would test SSH connectivity"
  [ "${BUILD_BE}" = 1 ] && info "[dry-run] would run: (cd ${API_DIR} && JAVA_HOME=${JDK17_HOME} mvn -q -DskipTests package)"
  [ "${BUILD_FE}" = 1 ] && info "[dry-run] would run: (cd ${FRONTEND_DIR} && pnpm install --frozen-lockfile && pnpm build)"
  info "[dry-run] would rsync deploy files and artifacts to ${REMOTE_DIR}"
  info "[dry-run] would run remote docker compose up -d --build ${SERVICES}"
  ok "dry run complete"
  exit 0
fi

info "Checking SSH connectivity..."
ssh_run true || die "SSH connection failed"
ok "server reachable"

if [ "${ASSUME_YES}" != 1 ]; then
  printf 'Deploy to test server? [y/N] '
  read -r answer </dev/tty || answer=""
  case "${answer}" in
    y|Y|yes|YES) ;;
    *) die "cancelled" ;;
  esac
fi

if [ "${BUILD_BE}" = 1 ]; then
  info "Building backend jar..."
  (cd "${API_DIR}" && JAVA_HOME="${JDK17_HOME}" mvn -q -DskipTests package)
  [ -f "${JAR_PATH}" ] || die "backend jar not found after build: ${JAR_PATH}"
  ok "backend jar ready: ${JAR_PATH}"
fi

if [ "${BUILD_FE}" = 1 ]; then
  info "Building frontend dist..."
  (cd "${FRONTEND_DIR}" && pnpm install --frozen-lockfile && pnpm build)
  [ -d "${FRONTEND_DIR}/dist" ] || die "frontend dist not found after build: ${FRONTEND_DIR}/dist"
  ok "frontend dist ready: ${FRONTEND_DIR}/dist"
fi

info "Preparing remote directory..."
ssh_run "mkdir -p '${REMOTE_DIR}/armada-api/target' '${REMOTE_DIR}/wheel-saas-pure-web/dist'"

info "Checking remote .env..."
ssh_run "bash -s -- '${REMOTE_DIR}'" <<<"${remote_required_env_check}"
ok "remote .env has required database keys"

info "Syncing deployment manifests..."
rsync -az -e "${RSYNC_SSH}" \
  "${SCRIPT_DIR}/backend.prebuilt.Dockerfile" \
  "${SCRIPT_DIR}/nginx.prebuilt.Dockerfile" \
  "${SCRIPT_DIR}/nginx.conf" \
  "${SCRIPT_DIR}/docker-compose.rds.yml" \
  "${SCRIPT_DIR}/.env.example" \
  "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/"

if [ "${BUILD_BE}" = 1 ]; then
  info "Syncing backend jar..."
  rsync -a --partial -e "${RSYNC_SSH}" \
    "${JAR_PATH}" \
    "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/armada-api/target/${JAR_NAME}"
fi

if [ "${BUILD_FE}" = 1 ]; then
  info "Syncing frontend dist..."
  rsync -az --delete -e "${RSYNC_SSH}" \
    "${FRONTEND_DIR}/dist/" \
    "${SSH_USER}@${SSH_HOST}:${REMOTE_DIR}/wheel-saas-pure-web/dist/"
fi

info "Starting containers..."
ssh_run "cd '${REMOTE_DIR}' && docker compose --env-file .env -p '${COMPOSE_PROJECT}' -f '${COMPOSE_FILE}' up -d --build ${SERVICES}"

info "Verifying containers..."
if [ "${SCOPE}" = "all" ] || [ "${SCOPE}" = "be" ]; then
  ssh_run "docker inspect -f '{{.State.Status}}' armada-backend | grep -q '^running$'"
fi
if [ "${SCOPE}" = "all" ] || [ "${SCOPE}" = "fe" ]; then
  ssh_run "docker inspect -f '{{.State.Status}}' armada-nginx | grep -q '^running$'"
fi
ok "containers running"

if [ "${SCOPE}" = "all" ] || [ "${SCOPE}" = "fe" ]; then
  info "Verifying frontend..."
  ssh_run "cd '${REMOTE_DIR}' && port=\$(awk -F= '/^ARMADA_HTTP_PORT=/{print \$2}' .env | tail -n 1); port=\${port:-18080}; curl -fsS -m 8 \"http://127.0.0.1:\${port}/\" | grep -qi '<!doctype html'"
  ok "frontend responds"
fi

if [ "${SCOPE}" = "all" ] || [ "${SCOPE}" = "be" ]; then
  info "Verifying API proxy path..."
  ssh_run "cd '${REMOTE_DIR}' && port=\$(awk -F= '/^ARMADA_HTTP_PORT=/{print \$2}' .env | tail -n 1); port=\${port:-18080}; body=\$(curl -fsS -m 8 \"http://127.0.0.1:\${port}/api/account-groups\" || true); printf '%s' \"\${body}\" | grep -Eq '\"code\"[[:space:]]*:[[:space:]]*(40101|0|40001)'"
  ok "API path reaches backend"
fi

ok "deploy complete: http://65.2.123.53:18080/"

if [ "${TAIL_LOGS}" = 1 ]; then
  ssh_run "docker logs -f --tail 120 armada-backend"
fi
```

- [ ] **Step 2: Make it executable**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
chmod +x armada-deploy/deploy-test.sh
```

- [ ] **Step 3: Syntax-check the script**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
bash -n armada-deploy/deploy-test.sh
```

Expected:

```text
command exits 0 with no output
```

- [ ] **Step 4: Dry-run the script**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
./armada-deploy/deploy-test.sh --dry-run
```

Expected output includes:

```text
Deploy plan
[dry-run] would test SSH connectivity
dry run complete
```

- [ ] **Step 5: Commit**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-deploy/deploy-test.sh
git commit -m "feat(deploy): add armada test deploy script"
```

---

### Task 3: Local Build Verification

**Files:**
- No new files.
- Verify: `armada-api/pom.xml`, `wheel-saas-pure-web/package.json`, `armada-deploy/deploy-test.sh`

- [ ] **Step 1: Verify backend package builds locally**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
JAVA_HOME="$(/usr/libexec/java_home -v 17)" mvn -q -DskipTests package
test -f target/armada-api-1.0.0-SNAPSHOT.jar
```

Expected:

```text
command exits 0
target/armada-api-1.0.0-SNAPSHOT.jar exists
```

If `/usr/libexec/java_home` is unavailable, use:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
JAVA_HOME="${JAVA17_HOME}" mvn -q -DskipTests package
test -f target/armada-api-1.0.0-SNAPSHOT.jar
```

- [ ] **Step 2: Verify frontend package builds locally**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm install --frozen-lockfile
pnpm build
test -f dist/index.html
```

Expected:

```text
command exits 0
dist/index.html exists
```

- [ ] **Step 3: Verify deploy script backend-only dry run**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
./armada-deploy/deploy-test.sh --be --dry-run
```

Expected:

```text
scope        : backend only
dry run complete
```

- [ ] **Step 4: Verify deploy script frontend-only dry run**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
./armada-deploy/deploy-test.sh --fe --dry-run
```

Expected:

```text
scope        : frontend only
dry run complete
```

---

### Task 4: First Remote Deployment

**Files:**
- No local code changes.
- Remote required file: `/home/app/armada-deploy/.env`

- [ ] **Step 1: Prepare the remote env file**

Run on the test server or through SSH after filling real values:

```bash
mkdir -p /home/app/armada-deploy
cat >/home/app/armada-deploy/.env <<'EOF'
ARMADA_HTTP_PORT=18080
DB_URL=jdbc:mysql://REPLACE_RDS_ENDPOINT:3306/armada?useSSL=false&serverTimezone=UTC&characterEncoding=utf8mb4
DB_USER=REPLACE_DB_USER
DB_PASSWORD=REPLACE_DB_PASSWORD
DEV_LOGIN_PASSWORD=REPLACE_LOGIN_PASSWORD
JAVA_TOOL_OPTIONS=-Xms1024m -Xmx1024m
EOF
chmod 600 /home/app/armada-deploy/.env
```

Expected:

```text
/home/app/armada-deploy/.env exists with mode 600
```

Do not commit real values. Do not send them in chat.

- [ ] **Step 2: Ensure the RDS schema exists**

Run with an approved database admin path:

```sql
CREATE DATABASE IF NOT EXISTS armada CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON armada.* TO 'REPLACE_DB_USER'@'%';
```

Expected:

```text
schema armada exists
application DB user can connect to schema armada
```

- [ ] **Step 3: Deploy**

Run locally:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
./armada-deploy/deploy-test.sh -y
```

Expected output includes:

```text
backend jar ready
frontend dist ready
containers running
frontend responds
API path reaches backend
deploy complete
```

- [ ] **Step 4: Verify old wheel remains untouched**

Run:

```bash
ssh -i /Users/daishuaishuai/IdeaProjects/dev-1.pem ubuntu@ec2-65-2-123-53.ap-south-1.compute.amazonaws.com \
  'docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep -E "wheel-|armada-"'
```

Expected:

```text
wheel-backend running
wheel-nginx running
armada-backend running
armada-nginx running
```

- [ ] **Step 5: Verify public staging entrypoint**

Run:

```bash
curl -fsS -m 10 http://65.2.123.53:18080/ | grep -qi '<!doctype html'
```

Expected:

```text
command exits 0
```

- [ ] **Step 6: Verify backend logs**

Run:

```bash
ssh -i /Users/daishuaishuai/IdeaProjects/dev-1.pem ubuntu@ec2-65-2-123-53.ap-south-1.compute.amazonaws.com \
  'docker logs --tail 120 armada-backend'
```

Expected:

```text
No Flyway migration failure
No database connection failure
No XML mapper parse failure
```

---

## Self-Review Checklist

- Spec coverage: Task 1 and Task 2 implement `armada/armada-deploy` files, prebuilt artifacts, isolated compose, Nginx same-origin `/api`, and remote `.env` protection. Task 4 covers RDS schema separation and first deploy verification. `armada-protocol` is intentionally out of scope per spec section 9.
- Placeholder scan: no `TBD`, `TODO`, `fill later`, or unspecified implementation step remains.
- Type/name consistency: Compose service names are `backend` and `nginx`; container names are `armada-backend` and `armada-nginx`; Nginx upstream uses Compose service `backend:8080`; script project name is `armada-deploy`; remote directory is `/home/app/armada-deploy`.
