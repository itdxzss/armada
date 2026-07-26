# Perf2 Frontend and Backend Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deploy backend commit `a1de75de82631e8aa65a93c2aed2fea2719f6e04` from `origin/1.0.1-snapshot` and the current wheel-saas-pure-web working tree to the perf2 application host with an exact Docker and deployment-directory rollback point.

**Architecture:** Use `deploy-test.sh --branch 1.0.1-snapshot` so the backend and deployment assets are built from a temporary detached worktree of the fetched remote branch; no local uncommitted backend file is packaged. The frontend remains sourced from the current sibling working tree. Preserve the server's existing `armada-deploy` Compose project identity because the running fixed-name containers belong to that project, while recording exact image IDs, tagged image backups, image tar archives, and a deployment-directory snapshot under `/home/app/armada-backups/20260723T105937Z`.

**Tech Stack:** Java 17, Maven, Spring Boot, Vue 3, TypeScript, pnpm, rsync, SSH, Docker Engine 25, Docker Compose v5.

---

### Task 1: Verify local source and deployment tooling

**Files:**
- Verify: `armada-api/`
- Verify: `armada-deploy/deploy-test.sh`
- Verify: `../wheel-saas-pure-web/`

- [ ] **Step 1: Record source identities and dirty state**

Run in both repositories:

```bash
git branch --show-current
git rev-parse HEAD
git status --short
```

Expected: backend source resolves to remote commit `a1de75de82631e8aa65a93c2aed2fea2719f6e04`; local backend dirty state is recorded but excluded. The frontend source is the execution-time working-tree snapshot.

- [ ] **Step 2: Validate deployment scripts**

Run from a temporary detached worktree created from `origin/1.0.1-snapshot`:

```bash
bash -n armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh
bash armada-deploy/deploy-test.test.sh
bash armada-deploy/package-prod.test.sh
```

Expected: exit code 0 for all commands.

- [ ] **Step 3: Validate the backend main-source build and startup**

Run from a temporary detached worktree created from `origin/1.0.1-snapshot` with test compilation disabled as explicitly requested:

```bash
cd armada-api
MAVEN_OPTS=-Dmaven.test.skip=true JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -q -DskipTests clean package
```

Start the packaged JAR against a dedicated temporary local schema created from the branch migrations:

```bash
MAVEN_OPTS=-Dmaven.test.skip=true JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home java -jar target/armada-api-1.0.0-SNAPSHOT.jar --spring.main.web-application-type=none
```

Expected: the main-source package command exits 0 and Spring reaches a successful application startup. `src/test` is neither compiled nor executed. Database environment values are injected from the original gitignored `.env` without copying or printing them; the temporary schema is deleted after the run.

- [ ] **Step 4: Validate the frontend**

Run from `../wheel-saas-pure-web`:

```bash
pnpm typecheck
pnpm build
```

Expected: exit code 0 and a populated `dist/` directory.

### Task 2: Create the remote rollback point

**Files:**
- Read: `/home/app/armada-deploy/`
- Create: `/home/app/armada-backups/20260723T105937Z/`

- [ ] **Step 1: Reconfirm runtime identity and capacity**

Run read-only checks for `armada-backend`, `armada-nginx`, their Compose labels, image IDs, restart counts, disk capacity, local HTTP root, and unauthenticated API response.

Expected: both containers are running under Compose project `armada-deploy`, port 80 returns HTTP 200, and sufficient disk capacity remains.

- [ ] **Step 2: Tag and export the running images**

On the remote host, tag the exact image IDs currently used by the two running containers as:

```text
armada-rollback/backend:20260723T105937Z
armada-rollback/nginx:20260723T105937Z
```

Save both tags to `/home/app/armada-backups/20260723T105937Z/docker-images.tar` and write image/container inspection JSON plus SHA-256 checksums in the same backup directory.

Expected: `docker image inspect` resolves both rollback tags, `docker-images.tar` is non-empty, and `sha256sum -c SHA256SUMS` exits 0.

- [ ] **Step 3: Snapshot deployment files**

Archive `/home/app/armada-deploy` while excluding runtime logs and nested backup directories:

```text
/home/app/armada-backups/20260723T105937Z/armada-deploy.tar.gz
```

Expected: the archive contains `.env`, Compose/Dockerfile assets, the backend JAR, and frontend `dist/index.html`; its checksum verifies successfully.

- [ ] **Step 4: Write the executable rollback script**

Create `/home/app/armada-backups/20260723T105937Z/rollback.sh` that:

1. stops/removes only `armada-backend` and `armada-nginx` if they belong to an interrupted replacement;
2. restores the deployment snapshot while preserving the backup directory;
3. loads `docker-images.tar` if the tags are missing;
4. retags the rollback images to `armada-deploy-backend:latest` and `armada-deploy-nginx:latest`;
5. runs Compose project `armada-deploy` with `--no-build --force-recreate`;
6. verifies both containers and local HTTP port 80.

Expected: `bash -n rollback.sh` exits 0. Do not execute it unless deployment or verification fails.

### Task 3: Deploy the remote-branch backend and current frontend

**Files:**
- Deploy: temporary `origin/1.0.1-snapshot` worktree `armada-api/target/armada-api-1.0.0-SNAPSHOT.jar`
- Deploy: `../wheel-saas-pure-web/dist/`
- Sync: `armada-deploy/` prebuilt Compose assets

- [ ] **Step 1: Execute repository deployment**

Run:

```bash
MAVEN_OPTS=-Dmaven.test.skip=true ARMADA_DEPLOY_PROJECT=armada-deploy ./armada-deploy/deploy-test.sh --env perf2 --all --branch 1.0.1-snapshot -y
```

Expected: script exits 0 with Backend=`SUCCESS` and Frontend=`SUCCESS`; protocol and Zhuan remain `SKIPPED`.

- [ ] **Step 2: Record the deployed source snapshot**

Write the exact remote backend commit, frontend commit and dirty-state flag, artifact hashes, UTC deployment time, backup ID, and post-deploy container image IDs to a non-secret manifest under the backup directory.

Expected: no `.env`, credential, key, token, or connection-string value is written to the manifest.

### Task 4: Verify runtime and rollback readiness

**Files:**
- Verify: remote containers and logs
- Verify: public URL `http://armada.3.110.124.52.nip.io/`

- [ ] **Step 1: Verify container stability**

Check status, health, restart count, creation/start time, image ID, and recent logs after a stabilization window.

Expected: both containers remain `running`, restart count is 0, backend startup completed, and no migration/configuration/connection/crash-loop error appears.

- [ ] **Step 2: Verify HTTP behavior**

Request the local and public root page, `platform-config.json`, and unauthenticated `/api/account-groups`.

Expected: roots return HTTP 200 with HTML, platform config identifies `第二套环境`, and the API reaches the authentication chain with the repository-accepted response code.

- [ ] **Step 3: Verify exact artifacts and backup checksums**

Compare local backend JAR and frontend `dist/index.html` SHA-256 values with the remote deployed files; rerun backup checksum verification.

Expected: local and remote hashes match and all backup checksums remain valid.

- [ ] **Step 4: Roll back on any blocking failure**

If deployment or required verification fails, run:

```bash
/home/app/armada-backups/20260723T105937Z/rollback.sh
```

Expected: original image IDs are restored, both original containers are running under `armada-deploy`, and local HTTP checks pass.
