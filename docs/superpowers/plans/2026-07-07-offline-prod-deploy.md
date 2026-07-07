# Offline Production Deploy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a no-network production release flow that creates separate offline packages for the app machine and protocol machine.

**Architecture:** Add production packaging under `armada-deploy/`. A local build machine creates Docker images, saves them into tar files, and bundles compose files plus simple install/rollback/status/log scripts. Production machines only run Docker Compose from the extracted package and never need git, Maven, pnpm, npm, SSH, rsync, or internet access.

**Tech Stack:** Bash, Docker, Docker Compose v2, Java 17/Maven, Vue/pnpm, Node protocol Docker image.

---

### Task 1: Package Contract Tests

**Files:**
- Create: `armada-deploy/package-prod.test.sh`

- [x] **Step 1: Write the failing shell tests**

Create tests that assert the package script exposes a dry-run, the app/protocol templates exist, install scripts avoid network deployment primitives, and protocol compose runs master plus four workers.

- [x] **Step 2: Run tests to verify they fail**

Run: `bash armada-deploy/package-prod.test.sh`
Expected: FAIL because `package-prod.sh` and prod templates do not exist yet.

### Task 2: Production Package Script

**Files:**
- Create: `armada-deploy/package-prod.sh`

- [x] **Step 1: Implement argument parsing and dry-run**

Support `--version`, `--output-dir`, `--platform`, `--skip-build`, `--app-only`, `--protocol-only`, `--dry-run`, and `--help`.

- [x] **Step 2: Implement local build and packaging**

Build backend jar, frontend dist, app Docker images, protocol Docker image, save image tar files, render compose templates with the release version, and create `armada-app-prod-<version>.tar.gz` plus `armada-protocol-prod-<version>.tar.gz`.

### Task 3: App Machine Package Templates

**Files:**
- Create: `armada-deploy/prod/app/docker-compose.yml`
- Create: `armada-deploy/prod/app/.env.example`

- [x] **Step 1: Compose app services**

Define `armada-backend` and `armada-nginx` image-based services wired to AWS RDS/MSK and protocol master URL.

- [x] **Step 2: App env example**

Document required production values without real credentials.

### Task 4: Protocol Machine Package Templates

**Files:**
- Create: `armada-deploy/prod/protocol/docker-compose.yml`
- Create: `armada-deploy/prod/protocol/.env.example`

- [x] **Step 1: Compose protocol services**

Define one master and four workers from the same offline image, using AWS Redis, RDS, and MSK env values.

- [x] **Step 2: Protocol env example**

Document worker endpoints, API keys, Kafka topics, Redis URL, and MySQL connection URI.

### Task 5: Offline Runtime Scripts

**Files:**
- Create: `armada-deploy/prod/scripts/install.sh`
- Create: `armada-deploy/prod/scripts/rollback.sh`
- Create: `armada-deploy/prod/scripts/status.sh`
- Create: `armada-deploy/prod/scripts/logs.sh`
- Create: `armada-deploy/prod/README-prod.md`

- [x] **Step 1: Install script**

Check Docker/Compose, validate `.env`, load `images/*.tar`, copy the release into `/opt/<kind>/releases/<version>`, update `current`, start Compose, and run local health checks.

- [x] **Step 2: Rollback/status/log helpers**

Provide small scripts that use the package-local compose file and env.

- [x] **Step 3: README**

Document production prerequisites, app machine install, protocol machine install, rollback, status, and logs.

### Task 6: Verification

**Files:**
- Test: `armada-deploy/package-prod.test.sh`

- [x] **Step 1: Run package tests**

Run: `bash armada-deploy/package-prod.test.sh`
Expected: PASS.

- [x] **Step 2: Run dry-run**

Run: `bash armada-deploy/package-prod.sh --dry-run --version test-local`
Expected: prints planned app and protocol package paths and does not build images.
