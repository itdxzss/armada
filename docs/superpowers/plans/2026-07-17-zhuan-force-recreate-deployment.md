# Zhuan Force-Recreate Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure every Zhuan test deployment recreates `whatsapp-android-zhuan` from the image built during that deployment.

**Architecture:** Keep the existing protected rsync, Compose build, dependency startup, migration, and health-check flow. Disable Compose stdin interaction for the migration command so the stdin-fed remote Bash script reaches its final command, then recreate the main service with `--force-recreate`. Protect both behaviors with the existing offline shell contract tests before deploying to the default Armada test environment.

**Tech Stack:** Bash 3.2-compatible shell, Docker Compose v2, OpenSSH, existing shell contract-test harness.

---

## File Map

- Modify `armada-deploy/deploy-test.test.sh`: require the generated Zhuan remote payload and static lifecycle contract to disable migration stdin interaction and contain the force-recreate command.
- Modify `armada-deploy/deploy-test.sh`: prevent migration from consuming the remaining remote script, then force recreation of only the Zhuan main service.
- Reference `docs/superpowers/specs/2026-07-17-zhuan-force-recreate-deployment-design.md`: approved scope and acceptance criteria.

### Task 1: Protect the Zhuan Main-Service Recreation Contract

**Files:**
- Modify: `armada-deploy/deploy-test.test.sh:107-132`
- Modify: `armada-deploy/deploy-test.test.sh:411-422`
- Test: `armada-deploy/deploy-test.test.sh`

- [ ] **Step 1: Write the failing payload assertion**

In `test_zhuan_command_flow_uses_protected_rsync_and_ordered_payload()`, require the exact force-recreate command before calculating lifecycle line numbers, then use that command to locate `main_line`:

```bash
  assert_contains "${payload_log}" "sudo docker compose up -d --force-recreate whatsapp-android-zhuan"

  config_line="$(awk 'index($0, "sudo docker compose config --quiet") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  build_line="$(awk 'index($0, "sudo docker compose build whatsapp-android-zhuan") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  deps_line="$(awk 'index($0, "sudo docker compose up -d redis-zhuan callback-zhuan") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  migrate_line="$(awk 'index($0, "whatsapp-migrate -env prod") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
  main_line="$(awk 'index($0, "sudo docker compose up -d --force-recreate whatsapp-android-zhuan") { print NR; exit }' "${ZHUAN_FIXTURE_PAYLOAD_LOG}")"
```

In `test_zhuan_remote_deploy_checks_config_and_runs_lifecycle()`, replace the old main-service assertion with:

```bash
  assert_contains "${script_content}" "sudo docker compose up -d --force-recreate whatsapp-android-zhuan"
```

- [ ] **Step 2: Run the contract test and verify RED**

Run:

```bash
bash armada-deploy/deploy-test.test.sh
```

Expected: non-zero exit with `expected output to contain: sudo docker compose up -d --force-recreate whatsapp-android-zhuan` because the current deployment payload still uses the old command.

- [ ] **Step 3: Preserve the observed failing output**

Confirm the failure is caused by the new expectation, not by a fixture or environment error. Do not modify production code until the failure text names the missing force-recreate command.

### Task 2: Preserve the Remote Script and Force Recreate the Zhuan Main Service

**Files:**
- Modify: `armada-deploy/deploy-test.sh:538-546`
- Test: `armada-deploy/deploy-test.test.sh`

- [ ] **Step 1: Prevent the migration container from consuming the script stdin**

Update the migration command in `zhuan_remote_deploy` to:

```bash
sudo docker compose run --rm --interactive=false whatsapp-android-zhuan /app/whatsapp-migrate -env prod
```

- [ ] **Step 2: Apply the minimal main-service change**

Update the final command in `zhuan_remote_deploy` to:

```bash
sudo docker compose up -d --force-recreate whatsapp-android-zhuan
```

Keep these preceding commands unchanged:

```bash
sudo docker compose build whatsapp-android-zhuan
sudo docker compose up -d redis-zhuan callback-zhuan
```

- [ ] **Step 3: Run shell syntax checks**

Run:

```bash
bash -n armada-deploy/deploy-test.sh
bash -n armada-deploy/deploy-test.test.sh
```

Expected: both commands exit `0` with no output.

- [ ] **Step 4: Run the contract suite and verify GREEN**

Run:

```bash
bash armada-deploy/deploy-test.test.sh
```

Expected final line:

```text
OK deploy-test.sh protocol and zhuan tests passed
```

- [ ] **Step 5: Verify the dry-run plan**

Run:

```bash
bash armada-deploy/deploy-test.sh --zhuan --dry-run
```

Expected: exit `0`; output identifies the Zhuan-only scope, source directory, default test target, protected source sync, migration, startup, and health check without invoking SSH or Docker.

- [ ] **Step 6: Keep the tested change uncommitted for local review**

```bash
git diff --check -- armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh docs/superpowers/plans/2026-07-17-zhuan-force-recreate-deployment.md
git status --short --branch
```

Expected: the script, test, and plan remain visible as local changes on `1.0.1-snapshot`; do not run `git add` or `git commit`.

### Task 3: Deploy and Verify the Default Test Environment

**Files:**
- No repository file changes.

- [ ] **Step 1: Deploy Zhuan non-interactively**

Run from the Armada repository:

```bash
bash armada-deploy/deploy-test.sh --zhuan -y
```

Expected: source sync, Compose build, migration, forced main-service recreation, and health checks all succeed.

- [ ] **Step 2: Compare the running and tagged image IDs**

Using the same SSH target and key resolved by `deploy-test.sh`, run this read-only check in `/home/app/whatsapp-android-zhuan-deploy/src/deploy`:

```bash
configured_image="$(sudo docker inspect -f '{{.Config.Image}}' whatsapp-android-zhuan)"
running_image="$(sudo docker inspect -f '{{.Image}}' whatsapp-android-zhuan)"
tagged_image="$(sudo docker image inspect -f '{{.Id}}' "${configured_image}")"
printf 'configured=%s\nrunning=%s\ntagged=%s\n' "${configured_image}" "${running_image}" "${tagged_image}"
test "${running_image}" = "${tagged_image}"
```

Expected: `running` and `tagged` contain the same image ID and the final equality check exits `0`.

- [ ] **Step 3: Verify container health and the HTTP endpoint**

Run on the test host:

```bash
for container in redis-zhuan callback-zhuan whatsapp-android-zhuan; do
  sudo docker inspect -f '{{.Name}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${container}"
done
curl -fsS -m 8 http://127.0.0.1:8001/swagger/index.html >/dev/null
```

Expected: all three containers report `running healthy`, and curl exits `0`.

- [ ] **Step 4: Hand off the business-level check**

Ask the user to send a Zhuan group message with `mentionAll=true` and visible text containing `@all`. The acceptance criterion is that WhatsApp applies the all-members mention semantics; visible literal text alone is not sufficient evidence.
