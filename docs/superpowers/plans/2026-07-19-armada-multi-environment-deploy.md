# Armada Multi-Environment Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Extend the existing Armada deployment entry point so `test1` and `perf2` can deploy all four components from reviewed non-sensitive profiles without duplicating the script or slowing normal deployments with full infrastructure scans.

**Architecture:** Keep `deploy-test.sh` as the CLI orchestrator, move shared transport and component lifecycles into Bash libraries, and select a fixed allowlist profile from `armada-deploy/envs`. Normal deploys run only scope-specific fast checks and post-deploy health checks; explicit `--check` runs read-only deep checks for infrastructure and cross-component contracts.

**Tech Stack:** Bash 3.2, SSH, rsync, Docker Compose, PM2, Node.js 24, KafkaJS, Maven/JDK 17, pnpm/npm.

---

## Guardrails

- Do not commit or print PEM contents, database credentials, Redis credentials, Kafka credentials, or remote `.env` values.
- Keep user environment-variable overrides working, but validate immutable profile guards after overrides.
- Default to `test1` when `--env` is omitted.
- Reject arbitrary profile paths; only `test1` and `perf2` are valid.
- A normal deploy must not enumerate Kafka topics, inspect database grants, inspect Redis ACLs, or perform a full infrastructure scan.
- `--check` is read-only and must not build, rsync, restart, reload, or modify cloud resources.
- `--full` builds locally first, then deploys Baileys, Zhuan, backend, and frontend. Stop at the first failed component; do not attempt a global rollback.
- Do not modify a security group or deploy remotely without a separate, explicit confirmation of the exact target environment and scope.

## File Map

Create:

- `armada-deploy/envs/test1.conf`
- `armada-deploy/envs/perf2.conf`
- `armada-deploy/lib/common.sh`
- `armada-deploy/lib/armada.sh`
- `armada-deploy/lib/protocol.sh`
- `armada-deploy/lib/zhuan.sh`
- `armada-deploy/lib/deep-check.sh`
- `armada-deploy/lib/kafka-check.mjs`

Modify:

- `armada-deploy/deploy-test.sh`
- `armada-deploy/deploy-test.test.sh`
- `docs/superpowers/specs/2026-07-19-armada-multi-environment-deploy-design.md`
- `.harness/changes/2026-07-19-multi-environment-deploy.md`

## Task 1: Add fixed environment profile selection

**Files:**

- Create: `armada-deploy/envs/test1.conf`
- Create: `armada-deploy/envs/perf2.conf`
- Modify: `armada-deploy/deploy-test.sh`
- Test: `armada-deploy/deploy-test.test.sh`

- [ ] Add failing CLI contract tests for:

  - omitted `--env` selecting `test1`;
  - `--env perf2 --full --dry-run` printing all four perf targets;
  - unknown, relative-path, and absolute-path environment names failing before build or SSH;
  - existing scope flags and environment-variable overrides remaining compatible.

Run:

~~~bash
bash armada-deploy/deploy-test.test.sh
~~~

Expected: only the new profile tests fail.

- [ ] Add `armada-deploy/envs/test1.conf` with the reviewed values:

~~~bash
ENV_ID=test1
PROFILE_APP_TITLE=第一套环境

PROFILE_ARMADA_HOST=65.2.123.53
PROFILE_ARMADA_USER=ubuntu
PROFILE_ARMADA_KEY_REL=测试pem/dev-1.pem
PROFILE_ARMADA_REMOTE_DIR=/home/app/armada-deploy
PROFILE_ARMADA_COMPOSE_FILE=docker-compose.rds.yml
PROFILE_ARMADA_COMPOSE_PROJECT=armada-deploy
PROFILE_ARMADA_PUBLIC_URL=http://armada.65.2.123.53.nip.io/

PROFILE_PROTOCOL_HOST=65.2.122.109
PROFILE_PROTOCOL_USER=ec2-user
PROFILE_PROTOCOL_KEY_REL=测试pem/protocol.pem
PROFILE_PROTOCOL_REMOTE_DIR=/home/ec2-user/armada-protocol
PROFILE_PROTOCOL_PM2_CONFIG=armada.ecosystem.config.cjs
PROFILE_PROTOCOL_HEALTH_PORT=8080
PROFILE_PROTOCOL_TRANSPORT=direct
PROFILE_PROTOCOL_JUMP_HOST=
PROFILE_PROTOCOL_JUMP_USER=
PROFILE_PROTOCOL_JUMP_KEY_REL=

PROFILE_ZHUAN_HOST=65.2.123.53
PROFILE_ZHUAN_USER=ubuntu
PROFILE_ZHUAN_KEY_REL=测试pem/dev-1.pem
PROFILE_ZHUAN_REMOTE_DIR=/home/app/whatsapp-android-zhuan-deploy/src
PROFILE_ZHUAN_COMPOSE_FILE=docker-compose.yml
PROFILE_ZHUAN_HTTP_PORT=8001
PROFILE_ZHUAN_START_SERVICES=redis-zhuan callback-zhuan
PROFILE_ZHUAN_HEALTH_SERVICES=redis-zhuan callback-zhuan whatsapp-android-zhuan

EXPECTED_ARMADA_DB_SCHEMA=armada
EXPECTED_ANDROID_BASE_URL=
EXPECTED_ANDROID_TOPIC_PREFIX=protocol.
EXPECTED_ZHUAN_DB_SCHEMA=whatsapp_android_zhuan_test
EXPECTED_ZHUAN_REDIS_PREFIX=
EXPECTED_KAFKA_TOPICS=
EXPECTED_KAFKA_GROUPS=
~~~

- [ ] Add `armada-deploy/envs/perf2.conf` with the reviewed values:

~~~bash
ENV_ID=perf2
PROFILE_APP_TITLE=第二套环境

PROFILE_ARMADA_HOST=3.110.124.52
PROFILE_ARMADA_USER=ec2-user
PROFILE_ARMADA_KEY_REL=测试pem/armada-perf.pem
PROFILE_ARMADA_REMOTE_DIR=/home/app/armada-deploy
PROFILE_ARMADA_COMPOSE_FILE=docker-compose.rds.yml
PROFILE_ARMADA_COMPOSE_PROJECT=armada-perf
PROFILE_ARMADA_PUBLIC_URL=http://armada.3.110.124.52.nip.io/

PROFILE_PROTOCOL_HOST=172.31.8.217
PROFILE_PROTOCOL_USER=ec2-user
PROFILE_PROTOCOL_KEY_REL=测试pem/armada-protocol-perf.pem
PROFILE_PROTOCOL_REMOTE_DIR=/home/ec2-user/armada-protocol
PROFILE_PROTOCOL_PM2_CONFIG=armada.ecosystem.config.cjs
PROFILE_PROTOCOL_HEALTH_PORT=8080
PROFILE_PROTOCOL_TRANSPORT=jump
PROFILE_PROTOCOL_JUMP_HOST=3.110.124.52
PROFILE_PROTOCOL_JUMP_USER=ec2-user
PROFILE_PROTOCOL_JUMP_KEY_REL=测试pem/armada-perf.pem

PROFILE_ZHUAN_HOST=3.111.245.182
PROFILE_ZHUAN_USER=ec2-user
PROFILE_ZHUAN_KEY_REL=测试pem/android-protocol.pem
PROFILE_ZHUAN_REMOTE_DIR=/home/ec2-user/whatsapp-android-zhuan
PROFILE_ZHUAN_COMPOSE_FILE=docker-compose.perf.yml
PROFILE_ZHUAN_HTTP_PORT=8001
PROFILE_ZHUAN_START_SERVICES=callback-zhuan
PROFILE_ZHUAN_HEALTH_SERVICES=callback-zhuan whatsapp-android-zhuan

EXPECTED_ARMADA_DB_SCHEMA=armada_perf
EXPECTED_ANDROID_BASE_URL=http://172.31.40.84:8001
EXPECTED_ANDROID_TOPIC_PREFIX=armada.perf.protocol.android.
EXPECTED_ZHUAN_DB_SCHEMA=whatsapp_android_zhuan_perf
EXPECTED_ZHUAN_REDIS_PREFIX=android-zhuan-perf:
EXPECTED_KAFKA_TOPICS=armada.perf.protocol.android.create_group_command=12,armada.perf.protocol.android.change_group_announcement_command=12,armada.perf.protocol.android.send_group_message_command=12
EXPECTED_KAFKA_GROUPS=android-zhuan-perf-create-group,android-zhuan-perf-change-announcement,android-zhuan-perf-send-message
~~~

- [ ] Implement allowlisted profile loading before derived defaults:

  1. Pre-parse only `--env`.
  2. Accept exactly `test1` or `perf2`.
  3. Source the matching repository-owned profile.
  4. Apply user overrides after profile defaults.
  5. Refresh derived paths and transports.
  6. Validate profile guards and remote directories.
  7. Parse remaining command options.

Remove IP-based title inference. Update `--help`, guide, plan, and dry-run output without exposing secrets.

- [ ] Verify and commit:

~~~bash
bash -n armada-deploy/deploy-test.sh
bash armada-deploy/deploy-test.test.sh
git add armada-deploy/envs armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh
git commit -m "feat: add deploy environment profiles"
~~~

## Task 2: Add safe direct and jump-host transports

**Files:**

- Create: `armada-deploy/lib/common.sh`
- Modify: `armada-deploy/deploy-test.sh`
- Test: `armada-deploy/deploy-test.test.sh`

- [ ] Add failing tests proving:

  - `test1` protocol SSH and rsync are direct;
  - `perf2` protocol SSH and rsync share a ProxyCommand through Armada;
  - `ARMADA_PROTOCOL_JUMP_KEY` overrides only the jump key;
  - unsafe remote directories fail before SSH;
  - dry-run prints hosts and paths, never key or `.env` contents.

- [ ] Move shared helpers to `lib/common.sh`:

  - log and error helpers;
  - `shell_single_quote`;
  - generic `validate_remote_dir`;
  - worktree cleanup;
  - key-file checks;
  - SSH common options;
  - direct/jump transport construction;
  - safe plan rendering.

Keep macOS Bash 3.2 compatibility: no associative arrays, namerefs, `mapfile`, or Bash 4-only case conversion.

- [ ] Reuse one resolved transport for protocol SSH and rsync.

Perf route:

~~~text
local -> ec2-user@3.110.124.52 -> ec2-user@172.31.8.217
~~~

Quote all key and host values. Do not source a remote file locally.

- [ ] Verify and commit:

~~~bash
bash -n armada-deploy/lib/common.sh
bash -n armada-deploy/deploy-test.sh
bash armada-deploy/deploy-test.test.sh
git add armada-deploy/lib/common.sh armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh
git commit -m "feat: support jump-host deployment transport"
~~~

## Task 3: Extract the Armada lifecycle and add bounded readiness

**Files:**

- Create: `armada-deploy/lib/armada.sh`
- Modify: `armada-deploy/deploy-test.sh`
- Test: `armada-deploy/deploy-test.test.sh`

- [ ] Extend the shell fixture to record Maven/frontend builds, rsync, Compose project/file arguments, backend/Nginx starts, health attempts, and runtime environment checks.

- [ ] Add failing tests for:

  - at most 30 health attempts with 5-second intervals;
  - immediate success when a probe passes;
  - `perf2` runtime `PROTOCOL_ANDROID_BASE_URL` and three Android topic guards;
  - a runtime mismatch failing before Armada is marked successful;
  - frontend HTML, title, `platform-config.json`, and `/api` proxy checks;
  - unchanged `test1` Compose and public URL behavior.

- [ ] Move to `lib/armada.sh`:

  - JDK 17 discovery;
  - backend/frontend builds;
  - artifact validation;
  - sync;
  - Compose start;
  - readiness polling;
  - runtime contract checks;
  - scoped logs.

The module receives resolved settings; it does not select profiles or own orchestration status.

- [ ] Verify and commit:

~~~bash
bash -n armada-deploy/lib/armada.sh
bash armada-deploy/deploy-test.test.sh
git add armada-deploy/lib/armada.sh armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh
git commit -m "refactor: isolate armada deployment lifecycle"
~~~

## Task 4: Extract the Baileys lifecycle and enforce the failure gate

**Files:**

- Create: `armada-deploy/lib/protocol.sh`
- Modify: `armada-deploy/deploy-test.sh`
- Test: `armada-deploy/deploy-test.test.sh`

- [ ] Add failing tests proving:

  - all selected local builds finish before the first rsync;
  - protocol is the first remote mutation in `--full`;
  - failed protocol npm build or PM2 reload prevents Zhuan and Armada commands;
  - `perf2` uses the jump transport;
  - the master and all four workers are online on Node.js 24 and `/readyz` passes;
  - current protocol sync exclusions remain.

- [ ] Move to `lib/protocol.sh`:

  - Node.js 24 validation;
  - local production build;
  - remote directory/Node validation;
  - source sync;
  - dependency install;
  - PM2 reload/start;
  - health verification;
  - scoped logs.

Keep the current ecosystem-file behavior and never print remote `.env`.

- [ ] Make full order explicit after all local builds:

~~~text
protocol -> zhuan -> backend -> frontend
~~~

Stop on first failure. Do not change later components and do not attempt global rollback.

- [ ] Verify and commit:

~~~bash
bash -n armada-deploy/lib/protocol.sh
bash armada-deploy/deploy-test.test.sh
git add armada-deploy/lib/protocol.sh armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh
git commit -m "refactor: isolate protocol deployment lifecycle"
~~~

## Task 5: Make Zhuan deployment profile-aware

**Files:**

- Create: `armada-deploy/lib/zhuan.sh`
- Modify: `armada-deploy/deploy-test.sh`
- Test: `armada-deploy/deploy-test.test.sh`

- [ ] Extend fixtures with both Compose variants, test Redis, perf external Redis, callback/app health, safe exclusions, TOML, and certificate paths.

- [ ] Add failing tests proving:

  - `test1` uses `docker-compose.yml` and starts `redis-zhuan callback-zhuan`;
  - `perf2` uses `docker-compose.perf.yml` and starts only `callback-zhuan` before the app;
  - `perf2` fails if a local `redis-zhuan` container is present;
  - `perf2` verifies `android-zhuan-perf:` and `whatsapp_android_zhuan_perf`;
  - logs use the selected Compose file;
  - sync excludes PEM, `.env`, data, logs, and node dependencies.

- [ ] Move local validation, remote validation, sync, Compose lifecycle, profile-driven dependency start, application start, health/isolation checks, and logs to `lib/zhuan.sh`.

Do not infer perf behavior from host IP.

- [ ] Verify and commit:

~~~bash
bash -n armada-deploy/lib/zhuan.sh
bash armada-deploy/deploy-test.test.sh
git add armada-deploy/lib/zhuan.sh armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh
git commit -m "feat: deploy zhuan from environment profile"
~~~

## Task 6: Add scoped fast checks, source evidence, and summaries

**Files:**

- Modify: `armada-deploy/deploy-test.sh`
- Modify: `armada-deploy/lib/common.sh`
- Test: `armada-deploy/deploy-test.test.sh`

- [ ] Add failing tests proving:

  - `--be` never checks protocol or Zhuan repositories, keys, hosts, or health;
  - `--protocol` never invokes Maven, pnpm, Armada SSH, or Zhuan SSH;
  - full dry-run prints branch, short commit, and dirty/clean state for all four repositories;
  - a failed full deploy reports status and skips later components;
  - normal output contains no secrets.

- [ ] Implement scope-specific fast checks only for selected components:

  - local repository/branch;
  - required toolchain;
  - key existence and permissions;
  - remote directory shape;
  - lightweight SSH reachability;
  - required local artifacts after build;
  - post-deploy component health.

Do not enumerate Kafka topics, database grants, or Redis ACLs here.

- [ ] Track Bash 3.2-compatible scalar statuses:

~~~bash
STATUS_PROTOCOL=SKIPPED
STATUS_ZHUAN=SKIPPED
STATUS_BACKEND=SKIPPED
STATUS_FRONTEND=SKIPPED
~~~

Use `PENDING`, `RUNNING`, `SUCCESS`, and `FAILED`. One exit trap cleans the worktree and prints a summary while preserving the original exit code.

- [ ] Verify and commit:

~~~bash
bash -n armada-deploy/deploy-test.sh
bash armada-deploy/deploy-test.test.sh
git add armada-deploy/deploy-test.sh armada-deploy/lib/common.sh armada-deploy/deploy-test.test.sh
git commit -m "feat: add scoped deployment checks and summaries"
~~~

## Task 7: Add explicit read-only deep checks

**Files:**

- Create: `armada-deploy/lib/deep-check.sh`
- Create: `armada-deploy/lib/kafka-check.mjs`
- Modify: `armada-deploy/deploy-test.sh`
- Test: `armada-deploy/deploy-test.test.sh`

- [ ] Add failing tests proving:

  - `--env perf2 --check` invokes Armada, protocol, Zhuan, Kafka, and cross-component markers;
  - `--check` never invokes builds, rsync, Compose up, or PM2 reload;
  - normal `--full` never enumerates Kafka metadata;
  - `test1` skips exact Kafka checks when expectations are empty;
  - `--check` rejects `--dry-run`, `--logs`, `-y`, branch deployment, and mutation scopes.

- [ ] Implement `lib/kafka-check.mjs`.

It must import KafkaJS from the protocol installation, read connection settings from process environment, accept expected `topic=partitionCount` entries and group names, and verify exact topic/group contracts. Output only names, counts, and states; never print brokers, usernames, passwords, or configuration objects.

~~~bash
node --check armada-deploy/lib/kafka-check.mjs
~~~

- [ ] Implement read-only checks in `lib/deep-check.sh`:

  - Armada remote file presence, semantic schema/topic/URL guards, runtime environment, backend/public readiness;
  - protocol SSH route, required files, Node 24, PM2, ready endpoint, expected Kafka metadata;
  - Zhuan selected Compose/project, TOML schema/prefix/topics/groups, RDS CA, containers, and health;
  - Armada-to-protocol, Armada-to-Zhuan, and public-endpoint connectivity.

Source the protocol `.env` only inside the remote shell and pipe the local checker to remote Node without installing or copying it. All deep-check commands must be observational: no Docker/PM2 mutations, SQL writes, Kafka admin mutations, Redis writes, or cloud mutations.

- [ ] Wire `--check` before build/deploy and exit after a redacted summary.

- [ ] Verify and commit:

~~~bash
bash -n armada-deploy/lib/deep-check.sh
node --check armada-deploy/lib/kafka-check.mjs
bash armada-deploy/deploy-test.test.sh
git add armada-deploy/lib/deep-check.sh armada-deploy/lib/kafka-check.mjs armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh
git commit -m "feat: add read-only deployment environment checks"
~~~

## Task 8: Run local release gates and review

**Files:** Verify every file changed in Tasks 1-7; modify only files required by findings.

- [ ] Run syntax checks:

~~~bash
bash -n armada-deploy/deploy-test.sh
for file in armada-deploy/lib/*.sh; do bash -n "$file"; done
node --check armada-deploy/lib/kafka-check.mjs
~~~

- [ ] Run the complete shell suite:

~~~bash
bash armada-deploy/deploy-test.test.sh
~~~

Expected: all tests pass.

- [ ] Run repository-specific local gates:

~~~bash
./mvnw -pl armada-api -am -DskipTests package
node armada-deploy/verify-config.mjs
bash armada-deploy/package-prod.test.sh
cd ../wheel-saas-pure-web
pnpm verify:config
cd ../armada-protocol/protocol-layer
npm run test:package-prod
~~~

If a command has changed, inspect the relevant package file and record the exact replacement before running it.

- [ ] Run both dry-runs:

~~~bash
./armada-deploy/deploy-test.sh --env test1 --full --dry-run
./armada-deploy/deploy-test.sh --env perf2 --full --dry-run
~~~

Expected: four components in the correct order; no SSH, build, rsync, restart, or secret output.

- [ ] Inspect the complete diff:

~~~bash
git diff --check
git diff --stat
git status --short
~~~

Review first-environment compatibility, Bash 3.2, quoting, ProxyCommand, rsync exclusions, secret redaction, fail-stop behavior, `--check` read-only behavior, and preservation of unrelated changes.

- [ ] Use the `requesting-code-review` skill and resolve findings with focused tests. Never stage the unrelated marketing SQL or worktree changes.

## Task 9: Open the perf protocol SSH route after explicit approval

**External state:** AWS security groups for the second environment.

- [ ] Pause and show the exact proposed change:

  - environment: `perf2`;
  - target protocol security group: `sg-08ece6402838b09f8`;
  - source Armada security group: `sg-0910d9bc97301ebd5`;
  - protocol/port: TCP 22;
  - source: security group only, never a public CIDR.

Plan approval is not authorization for this cloud mutation.

- [ ] Describe current rules read-only:

~~~bash
aws ec2 describe-security-groups --group-ids sg-08ece6402838b09f8
~~~

- [ ] Only if the rule is absent and the user explicitly approves, add it:

~~~bash
aws ec2 authorize-security-group-ingress --group-id sg-08ece6402838b09f8 --ip-permissions 'IpProtocol=tcp,FromPort=22,ToPort=22,UserIdGroupPairs=[{GroupId=sg-0910d9bc97301ebd5,Description="Armada perf deploy jump"}]'
~~~

- [ ] Verify the jump route with a read-only hostname/Node-version command. Record the AWS rule ID and result without credentials.

## Task 10: Validate and deploy perf2 under separate authorization

**External state:** all four second-environment services.

- [ ] Record branch, exact short commit, dirty/clean state, and scope for Armada, frontend, protocol, and Zhuan. Stop on unexpected local changes.

- [ ] Obtain explicit confirmation listing target `perf2`, exact four commits, and `--full` or the exact component scope.

- [ ] Run read-only deep validation first:

~~~bash
./armada-deploy/deploy-test.sh --env perf2 --check
~~~

Expected: isolation, Kafka metadata, jump transport, services, and cross-component connectivity pass with redacted output.

- [ ] After a separate explicit deployment approval, run only the approved scope.

Full deployment:

~~~bash
./armada-deploy/deploy-test.sh --env perf2 --full -y
~~~

- [ ] Run the post-deploy deep check:

~~~bash
./armada-deploy/deploy-test.sh --env perf2 --check
~~~

Also confirm the Armada backend runtime contains the expected Android base URL and three perf command topics, and all four components are healthy.

- [ ] Update the change record with redacted evidence and commit documentation:

~~~bash
git add .harness/changes/2026-07-19-multi-environment-deploy.md docs/superpowers/specs/2026-07-19-armada-multi-environment-deploy-design.md
git commit -m "docs: record multi-environment deployment verification"
~~~

Do not claim deployment success without current successful output from both the deploy command and post-deploy deep check.
