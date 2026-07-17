# Zhuan Test Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `armada-deploy/deploy-test.sh` so Zhuan can be deployed independently with `--zhuan` and as part of `--full`, without overwriting remote credentials or runtime data.

**Architecture:** Keep Zhuan orchestration in the existing test deployment entry point and model it after the current Baileys protocol flow. The local script validates inputs, performs a protected rsync to the existing remote source tree, invokes a fixed Compose build/migrate/up sequence over SSH, then polls container health and verifies the loopback HTTP endpoint.

**Tech Stack:** Bash 3.2-compatible shell, rsync, OpenSSH, Docker Compose v2, existing shell contract tests.

---

## File Map

- Modify `armada-deploy/deploy-test.sh`: add Zhuan CLI scope, configuration, validation, protected source sync, remote Compose lifecycle, health checks, completion output, and log tailing.
- Modify `armada-deploy/deploy-test.test.sh`: add offline CLI and script-contract tests for Zhuan while retaining all current protocol tests.

### Task 1: Zhuan CLI Scope and Dry-Run Contract

**Files:**
- Modify: `armada-deploy/deploy-test.test.sh:26-115`
- Modify: `armada-deploy/deploy-test.sh:11-40,121-305,327-355,467-529`

- [ ] **Step 1: Write the failing CLI and dry-run tests**

Extend the help test and add Zhuan-only and scope-membership tests:

```bash
test_help_mentions_protocol_scope() {
  local out
  out="$("${SCRIPT}" --help)"
  assert_contains "${out}" "--protocol"
  assert_contains "${out}" "--zhuan"
  assert_contains "${out}" "--full"
  assert_contains "${out}" "ARMADA_PROTOCOL_DEPLOY_HOST"
  assert_contains "${out}" "ARMADA_ZHUAN_DEPLOY_HOST"
  assert_contains "${out}" "ARMADA_ZHUAN_DEPLOY_REMOTE_DIR"
  assert_contains "${out}" "ARMADA_APP_TITLE"
}

test_zhuan_dry_run_is_zhuan_only() {
  local key out
  key="$(mktemp)"
  chmod 600 "${key}"
  out="$(
    ARMADA_DEPLOY_KEY="${key}" \
    ARMADA_ZHUAN_DEPLOY_KEY="${key}" \
    "${SCRIPT}" --zhuan --dry-run
  )"
  rm -f "${key}"

  assert_contains "${out}" "范围          : 只 Zhuan 协议"
  assert_contains "${out}" "Zhuan 目录"
  assert_contains "${out}" "Zhuan 目标"
  assert_contains "${out}" "[dry-run] 将同步 Zhuan 源码"
  assert_contains "${out}" "whatsapp-migrate -env prod"
  assert_not_contains "${out}" "后端 JDK"
  assert_not_contains "${out}" "前端构建"
  assert_not_contains "${out}" "协议 PM2"
}

test_full_includes_zhuan_but_all_does_not() {
  local all_scope full_scope
  all_scope="$(sed -n '/^  all)/,/^    ;;/p' "${SCRIPT}")"
  full_scope="$(sed -n '/^  full)/,/^    ;;/p' "${SCRIPT}")"

  assert_not_contains "${all_scope}" "BUILD_ZHUAN=1"
  assert_contains "${full_scope}" "BUILD_ZHUAN=1"
}

test_zhuan_defaults_to_armada_test_host() {
  local script_content
  script_content="$(sed -n '1,55p' "${SCRIPT}")"

  assert_contains "${script_content}" 'ZHUAN_DIR="${ARMADA_ZHUAN_DIR:-${WORKSPACE_ROOT}/whatsapp-server-feature-android-zhuan}"'
  assert_contains "${script_content}" 'ZHUAN_SSH_HOST="${ARMADA_ZHUAN_DEPLOY_HOST:-${SSH_HOST}}"'
  assert_contains "${script_content}" 'ZHUAN_SSH_USER="${ARMADA_ZHUAN_DEPLOY_USER:-${SSH_USER}}"'
  assert_contains "${script_content}" 'ZHUAN_SSH_KEY="${ARMADA_ZHUAN_DEPLOY_KEY:-${SSH_KEY}}"'
  assert_contains "${script_content}" 'ZHUAN_REMOTE_DIR="${ARMADA_ZHUAN_DEPLOY_REMOTE_DIR:-/home/app/whatsapp-android-zhuan-deploy/src}"'
}
```

Add these calls before the final success message:

```bash
test_zhuan_dry_run_is_zhuan_only
test_full_includes_zhuan_but_all_does_not
test_zhuan_defaults_to_armada_test_host
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
bash armada-deploy/deploy-test.test.sh
```

Expected: FAIL because `--help` does not contain `--zhuan` and the script has no Zhuan scope.

- [ ] **Step 3: Add Zhuan configuration and command help**

Add the sibling-repository and inherited SSH defaults after the protocol variables:

```bash
ZHUAN_DIR="${ARMADA_ZHUAN_DIR:-${WORKSPACE_ROOT}/whatsapp-server-feature-android-zhuan}"
ZHUAN_SSH_HOST="${ARMADA_ZHUAN_DEPLOY_HOST:-${SSH_HOST}}"
ZHUAN_SSH_USER="${ARMADA_ZHUAN_DEPLOY_USER:-${SSH_USER}}"
ZHUAN_SSH_KEY="${ARMADA_ZHUAN_DEPLOY_KEY:-${SSH_KEY}}"
ZHUAN_REMOTE_DIR="${ARMADA_ZHUAN_DEPLOY_REMOTE_DIR:-/home/app/whatsapp-android-zhuan-deploy/src}"
```

Update `usage()` and `guide()` with these exact semantics:

```text
deploy-test.sh - 部署 armada API + wheel-saas-pure-web + Baileys/Zhuan 协议层到测试服。

  --all            构建并部署后端 + 前端。保持旧语义,不部署协议层。
  --full           构建并部署后端 + 前端 + Baileys 协议层 + Zhuan 协议。
  --protocol       只部署 Baileys 协议层。
  --zhuan          只部署 Zhuan 协议。
  --logs           部署后跟随对应服务日志；--full 保持跟随后端日志。

  ARMADA_ZHUAN_DIR
  ARMADA_ZHUAN_DEPLOY_HOST
  ARMADA_ZHUAN_DEPLOY_USER
  ARMADA_ZHUAN_DEPLOY_KEY
  ARMADA_ZHUAN_DEPLOY_REMOTE_DIR
```

The guide must include:

```text
  后端 + 前端 + Baileys 协议层 + Zhuan 协议:
    ./armada-deploy/deploy-test.sh --full -y

  只部署 Zhuan 协议:
    ./armada-deploy/deploy-test.sh --zhuan -y

  4. 前端、Baileys 协议层和 Zhuan 协议仍从各自环境变量指向的本地目录部署,不受 --branch 影响。
```

- [ ] **Step 4: Implement Zhuan scope selection and local validation**

Add argument parsing and scope flags:

```bash
    --zhuan) SCOPE="zhuan" ;;
```

```bash
BUILD_ZHUAN=0

case "${SCOPE}" in
  all)
    BUILD_BE=1
    BUILD_FE=1
    SERVICES=""
    SCOPE_DESC="后端 + 前端"
    ;;
  full)
    BUILD_BE=1
    BUILD_FE=1
    BUILD_PROTOCOL=1
    BUILD_ZHUAN=1
    SERVICES=""
    SCOPE_DESC="后端 + 前端 + Baileys 协议层 + Zhuan 协议"
    ;;
  be)
    BUILD_BE=1
    SERVICES="backend"
    SCOPE_DESC="只后端"
    ;;
  fe)
    BUILD_FE=1
    SERVICES="nginx"
    SCOPE_DESC="只前端"
    ;;
  protocol)
    BUILD_PROTOCOL=1
    SCOPE_DESC="只协议层"
    ;;
  zhuan)
    BUILD_ZHUAN=1
    SCOPE_DESC="只 Zhuan 协议"
    ;;
  *)
    die "无效部署范围: ${SCOPE}"
    ;;
esac
```

Add local preflight checks without reading local or remote secret content:

```bash
if [ "${BUILD_ZHUAN}" = 1 ]; then
  [ -f "${ZHUAN_SSH_KEY}" ] || die "找不到 Zhuan SSH 私钥: ${ZHUAN_SSH_KEY}"
  [ -d "${ZHUAN_DIR}" ] || die "找不到 Zhuan 仓库目录: ${ZHUAN_DIR}"
  [ -f "${ZHUAN_DIR}/go.mod" ] || die "找不到 Zhuan go.mod"
  [ -f "${ZHUAN_DIR}/go.sum" ] || die "找不到 Zhuan go.sum"
  [ -f "${ZHUAN_DIR}/.dockerignore" ] || die "找不到 Zhuan .dockerignore"
  [ -f "${ZHUAN_DIR}/deploy/Dockerfile" ] || die "找不到 Zhuan deploy/Dockerfile"
  [ -f "${ZHUAN_DIR}/deploy/docker-compose.yml" ] || die "找不到 Zhuan deploy/docker-compose.yml"
fi
```

- [ ] **Step 5: Implement Zhuan plan and dry-run output**

Add these lines to `print_plan()` when `BUILD_ZHUAN=1`:

```bash
  if [ "${BUILD_ZHUAN}" = 1 ]; then
    printf '  Zhuan 目录     : %s\n' "${ZHUAN_DIR}"
    printf '  Zhuan 目标     : %s@%s:%s\n' "${ZHUAN_SSH_USER}" "${ZHUAN_SSH_HOST}" "${ZHUAN_REMOTE_DIR}"
  fi
```

Add these dry-run messages:

```bash
  if [ "${BUILD_ZHUAN}" = 1 ]; then
    info "[dry-run] 将检查 Zhuan SSH 连通性"
    info "[dry-run] 将同步 Zhuan 源码到 ${ZHUAN_REMOTE_DIR},保留远端配置和日志"
    info "[dry-run] 将校验并构建 Zhuan Compose 服务"
    info "[dry-run] 将运行迁移: whatsapp-migrate -env prod"
    info "[dry-run] 将启动并验活 redis-zhuan、callback-zhuan、whatsapp-android-zhuan"
  fi
```

- [ ] **Step 6: Run the CLI contract tests**

Run:

```bash
bash armada-deploy/deploy-test.test.sh
```

Expected: `OK deploy-test.sh protocol tests passed`.

- [ ] **Step 7: Commit the CLI contract**

```bash
git add armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh
git commit -m "feat: add zhuan deployment scope"
```

### Task 2: Protected Source Sync and Compose Lifecycle

**Files:**
- Modify: `armada-deploy/deploy-test.test.sh:90-140`
- Modify: `armada-deploy/deploy-test.sh:373-465,531-637`

- [ ] **Step 1: Write failing safety and lifecycle tests**

Add:

```bash
test_zhuan_sync_preserves_remote_runtime_files() {
  local script_content
  script_content="$(cat "${SCRIPT}")"

  assert_contains "${script_content}" '--exclude-from="${ZHUAN_DIR}/.dockerignore"'
  assert_contains "${script_content}" "--exclude=deploy/.env"
  assert_contains "${script_content}" "--exclude=deploy/configs/prod_configs.toml"
  assert_contains "${script_content}" "--exclude=deploy/logs/"
  assert_contains "${script_content}" "--exclude=deploy/callback-logs/"
  assert_not_contains "${script_content}" "--delete-excluded"
}

test_zhuan_remote_deploy_checks_config_and_runs_lifecycle() {
  local script_content
  script_content="$(cat "${SCRIPT}")"

  assert_contains "${script_content}" 'test -f "${remote_dir}/deploy/.env"'
  assert_contains "${script_content}" 'test -f "${remote_dir}/deploy/configs/prod_configs.toml"'
  assert_contains "${script_content}" "sudo docker compose config --quiet"
  assert_contains "${script_content}" "sudo docker compose build whatsapp-android-zhuan"
  assert_contains "${script_content}" "sudo docker compose up -d redis-zhuan callback-zhuan"
  assert_contains "${script_content}" "sudo docker compose run --rm whatsapp-android-zhuan /app/whatsapp-migrate -env prod"
  assert_contains "${script_content}" "sudo docker compose up -d whatsapp-android-zhuan"
}
```

Call both tests before the final success message.

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
bash armada-deploy/deploy-test.test.sh
```

Expected: FAIL because the script has no Zhuan rsync or Compose lifecycle.

- [ ] **Step 3: Add Zhuan SSH and remote script helpers**

Add alongside the existing protocol SSH definitions:

```bash
ZHUAN_SSH_OPTS=(
  -i "${ZHUAN_SSH_KEY}"
  -o BatchMode=yes
  -o ConnectTimeout=15
  -o StrictHostKeyChecking=accept-new
)
ZHUAN_RSYNC_SSH="ssh -i ${ZHUAN_SSH_KEY} -o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new"

zhuan_ssh_run() {
  ssh "${ZHUAN_SSH_OPTS[@]}" "${ZHUAN_SSH_USER}@${ZHUAN_SSH_HOST}" "$@"
}
```

Add the remote config check:

```bash
zhuan_remote_required_files_check='
set -eu
remote_dir="$1"
test -f "${remote_dir}/deploy/.env" || { echo "远端缺少 Zhuan 配置: ${remote_dir}/deploy/.env" >&2; exit 40; }
test -f "${remote_dir}/deploy/configs/prod_configs.toml" || { echo "远端缺少 Zhuan 配置: ${remote_dir}/deploy/configs/prod_configs.toml" >&2; exit 41; }
'
```

Add the remote lifecycle in the order established by the Zhuan deployment guide:

```bash
zhuan_remote_deploy='
set -eu
remote_dir="$1"
cd "${remote_dir}/deploy"
sudo docker compose config --quiet
sudo docker compose build whatsapp-android-zhuan
sudo docker compose up -d redis-zhuan callback-zhuan
sudo docker compose run --rm whatsapp-android-zhuan /app/whatsapp-migrate -env prod
sudo docker compose up -d whatsapp-android-zhuan
'
```

- [ ] **Step 4: Add the Zhuan SSH preflight**

After the Baileys protocol SSH check, add:

```bash
if [ "${BUILD_ZHUAN}" = 1 ]; then
  info "检查 Zhuan SSH 连通性..."
  zhuan_ssh_run true || die "Zhuan SSH 连接失败"
  ok "Zhuan 服务器可达"
fi
```

- [ ] **Step 5: Add protected rsync and invoke the Compose lifecycle**

Add after the Baileys protocol deployment block:

```bash
if [ "${BUILD_ZHUAN}" = 1 ]; then
  info "准备 Zhuan 远端目录..."
  zhuan_ssh_run "mkdir -p '${ZHUAN_REMOTE_DIR}'"

  info "检查 Zhuan 远端配置..."
  zhuan_ssh_run "bash -s -- '${ZHUAN_REMOTE_DIR}'" <<<"${zhuan_remote_required_files_check}"
  ok "Zhuan 远端运行配置已就绪"

  info "同步 Zhuan 源码..."
  rsync -az --delete -e "${ZHUAN_RSYNC_SSH}" \
    --exclude-from="${ZHUAN_DIR}/.dockerignore" \
    --exclude=deploy/.env \
    --exclude=deploy/configs/prod_configs.toml \
    --exclude=deploy/logs/ \
    --exclude=deploy/callback-logs/ \
    "${ZHUAN_DIR}/" \
    "${ZHUAN_SSH_USER}@${ZHUAN_SSH_HOST}:${ZHUAN_REMOTE_DIR}/"

  info "构建并启动 Zhuan 协议..."
  zhuan_ssh_run "bash -s -- '${ZHUAN_REMOTE_DIR}'" <<<"${zhuan_remote_deploy}"
fi
```

Do not add `--delete-excluded`, `docker compose down`, `docker volume rm`, or any command that displays the content of the protected configuration files.

- [ ] **Step 6: Run the safety and lifecycle tests**

Run:

```bash
bash armada-deploy/deploy-test.test.sh
```

Expected: `OK deploy-test.sh protocol tests passed`.

- [ ] **Step 7: Commit the protected deployment lifecycle**

```bash
git add armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh
git commit -m "feat: deploy zhuan with protected compose flow"
```

### Task 3: Health Verification, Completion Output, and Log Tailing

**Files:**
- Modify: `armada-deploy/deploy-test.test.sh:115-160`
- Modify: `armada-deploy/deploy-test.sh:639-691`

- [ ] **Step 1: Write failing health and logs tests**

Add:

```bash
test_zhuan_deploy_waits_for_health_and_checks_http() {
  local script_content
  script_content="$(cat "${SCRIPT}")"

  assert_contains "${script_content}" "redis-zhuan callback-zhuan whatsapp-android-zhuan"
  assert_contains "${script_content}" "running/healthy"
  assert_contains "${script_content}" "Zhuan 容器未在时限内就绪"
  assert_contains "${script_content}" "http://127.0.0.1:8001/swagger/index.html"
}

test_zhuan_only_logs_follow_main_container() {
  local script_content
  script_content="$(cat "${SCRIPT}")"

  assert_contains "${script_content}" 'elif [ "${TAIL_LOGS}" = 1 ] && [ "${BUILD_ZHUAN}" = 1 ]; then'
  assert_contains "${script_content}" "sudo docker compose logs -f --tail 120 whatsapp-android-zhuan"
}
```

Call both tests and change the final test message to:

```bash
printf 'OK deploy-test.sh protocol and zhuan tests passed\n'
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
bash armada-deploy/deploy-test.test.sh
```

Expected: FAIL because the script does not poll Zhuan container health or tail Zhuan logs.

- [ ] **Step 3: Add a bounded remote health check**

Define this remote script next to `zhuan_remote_deploy`:

```bash
zhuan_remote_health_check='
set -eu
remote_dir="$1"
cd "${remote_dir}/deploy"
for container in redis-zhuan callback-zhuan whatsapp-android-zhuan; do
  attempt=1
  while :; do
    state="$(sudo docker inspect -f "{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}" "${container}" 2>/dev/null || true)"
    if [ "${state}" = "running/healthy" ]; then
      break
    fi
    if [ "${attempt}" -ge 24 ]; then
      echo "Zhuan 容器未在时限内就绪: ${container}, state=${state:-missing}" >&2
      exit 42
    fi
    sleep 5
    attempt=$((attempt + 1))
  done
done
curl -fsS -m 8 http://127.0.0.1:8001/swagger/index.html >/dev/null
'
```

Invoke it after the main Zhuan service starts:

```bash
if [ "${BUILD_ZHUAN}" = 1 ]; then
  info "检查 Zhuan 容器和 API..."
  zhuan_ssh_run "bash -s -- '${ZHUAN_REMOTE_DIR}'" <<<"${zhuan_remote_health_check}"
  ok "Zhuan 协议可访问"
fi
```

- [ ] **Step 4: Add completion and log-tail behavior**

Add the completion message:

```bash
if [ "${BUILD_ZHUAN}" = 1 ]; then
  ok "Zhuan 协议部署完成: ${ZHUAN_SSH_USER}@${ZHUAN_SSH_HOST}:${ZHUAN_REMOTE_DIR}"
fi
```

Preserve the current backend and Baileys precedence, then add Zhuan as the final branch:

```bash
if [ "${TAIL_LOGS}" = 1 ] && [ "${BUILD_BE}" = 1 ]; then
  ssh_run "docker logs -f --tail 120 armada-backend"
elif [ "${TAIL_LOGS}" = 1 ] && [ "${BUILD_PROTOCOL}" = 1 ]; then
  protocol_ssh_run "pm2 logs --lines 120"
elif [ "${TAIL_LOGS}" = 1 ] && [ "${BUILD_ZHUAN}" = 1 ]; then
  zhuan_ssh_run "cd '${ZHUAN_REMOTE_DIR}/deploy' && sudo docker compose logs -f --tail 120 whatsapp-android-zhuan"
fi
```

- [ ] **Step 5: Run all shell contract tests**

Run:

```bash
bash armada-deploy/deploy-test.test.sh
```

Expected: `OK deploy-test.sh protocol and zhuan tests passed`.

- [ ] **Step 6: Run syntax and dry-run verification**

Run:

```bash
bash -n armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh
```

Expected: exit code 0 with no output.

Run:

```bash
armada-deploy/deploy-test.sh --zhuan --dry-run
```

Expected: the plan shows `只 Zhuan 协议`, the existing Zhuan repository and test host, protected sync, migration, and health-check actions; it performs no SSH or rsync.

Run:

```bash
git diff --check
```

Expected: exit code 0 with no whitespace errors.

- [ ] **Step 7: Commit health checks and final behavior**

```bash
git add armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh
git commit -m "test: verify zhuan deployment health contract"
```

This plan intentionally stops at local verification. Do not run `--zhuan -y`, `--full -y`, or any other command that changes the test host.
