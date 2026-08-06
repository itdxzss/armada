# Test1 Flyway V101 Collision Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the migration already recorded as V101 in test1, move the unapplied manager-admin migration to V102, and restart only the backend successfully.

**Architecture:** Preserve Flyway's immutable history by carrying the exact V101 SQL already applied to test1 instead of repairing database metadata. Treat the current manager-admin SQL as a new V102 migration, protect both decisions with checksum and SQL-contract tests, then let normal Spring Boot startup validate V101 and apply V102.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Flyway 10.10.0, JUnit 5, AssertJ, Maven, MySQL 8.4, Docker Compose.

---

### Task 1: Lock the collision recovery with failing tests

**Files:**
- Modify: `armada-api/src/test/java/com/armada/boot/FlywayAppliedMigrationCompatibilityTest.java`
- Modify: `armada-api/src/test/java/com/armada/task/PullTaskManagerAdminStageMigrationSqlTest.java`

- [ ] **Step 1: Add the applied V101 checksum contract**

Append this assertion to `test1AppliedMigrationsKeepOriginalChecksums()`:

```java
assertAppliedMigration(
        "V101__normal_group_creation.sql",
        419_410_967);
```

- [ ] **Step 2: Point the manager-admin contract at V102**

Change the migration path and checkpoint assertion to:

```java
private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V102__pull_task_manager_admin_stage.sql");
```

```java
.contains("migration_key = 'V102_pull_task_manager_admin_stage'")
```

- [ ] **Step 3: Run the focused tests and verify RED**

Run:

```bash
cd armada-api
mvn -Dtest='FlywayAppliedMigrationCompatibilityTest,PullTaskManagerAdminStageMigrationSqlTest' test
```

Expected: FAIL because `V101__normal_group_creation.sql` and `V102__pull_task_manager_admin_stage.sql` do not yet exist.

### Task 2: Restore V101 and move the unapplied migration to V102

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V101__normal_group_creation.sql`
- Rename: `armada-api/src/main/resources/db/migration/V101__pull_task_manager_admin_stage.sql` to `armada-api/src/main/resources/db/migration/V102__pull_task_manager_admin_stage.sql`
- Modify: `armada-api/src/main/resources/db/migration/V102__pull_task_manager_admin_stage.sql`

- [ ] **Step 1: Restore the exact applied V101**

Restore the file byte-for-byte from the known source commit:

```bash
git show e69b785:armada-api/src/main/resources/db/migration/V101__normal_group_creation.sql
```

The resulting Flyway checksum must be `419410967`; no SQL text may be reformatted.

- [ ] **Step 2: Rename the manager-admin migration and checkpoint key**

Move the file to V102 and replace all three occurrences of the checkpoint key:

```sql
V102_pull_task_manager_admin_stage
```

No DDL, DML, guards, stage mapping, or retry behavior changes.

- [ ] **Step 3: Run the focused tests and verify GREEN**

Run:

```bash
cd armada-api
mvn -Dtest='FlywayAppliedMigrationCompatibilityTest,PullTaskManagerAdminStageMigrationSqlTest' test
```

Expected: PASS with 5 tests and no failures or errors.

- [ ] **Step 4: Commit the migration recovery**

```bash
git add armada-api/src/main/resources/db/migration/V101__normal_group_creation.sql \
  armada-api/src/main/resources/db/migration/V102__pull_task_manager_admin_stage.sql \
  armada-api/src/test/java/com/armada/boot/FlywayAppliedMigrationCompatibilityTest.java \
  armada-api/src/test/java/com/armada/task/PullTaskManagerAdminStageMigrationSqlTest.java
git commit -m "fix(db): resolve test1 flyway v101 collision"
```

### Task 3: Verify, deploy, and prove recovery

**Files:**
- Verify: `armada-api/src/main/resources/db/migration/`
- Verify: `armada-deploy/deploy-test.sh`

- [ ] **Step 1: Run repository verification**

Run:

```bash
git diff --check HEAD^
cd armada-api
mvn test
mvn -q -Dmaven.test.skip=true clean package
```

Expected: no whitespace errors, full Maven suite passes, and the deploy jar is produced.

- [ ] **Step 2: Review the exact deployment diff**

Run:

```bash
git show --stat --oneline HEAD
git show --format=fuller --find-renames HEAD -- \
  armada-api/src/main/resources/db/migration \
  armada-api/src/test/java/com/armada/boot/FlywayAppliedMigrationCompatibilityTest.java \
  armada-api/src/test/java/com/armada/task/PullTaskManagerAdminStageMigrationSqlTest.java
```

Expected: one exact historical migration restored, one migration renamed to V102 with only its checkpoint identifier changed, and two focused tests updated.

- [ ] **Step 3: Confirm remote migration preconditions**

Query test1 without exposing credentials. Confirm V101 is `V101__normal_group_creation.sql` with checksum `419410967`, success `1`, and no V102 row exists.

- [ ] **Step 4: Deploy only the backend**

Run:

```bash
./armada-deploy/deploy-test.sh --env test1 --be -y
```

Expected: exit code 0 and backend deployment summary `SUCCESS`; nginx is not rebuilt or restarted.

- [ ] **Step 5: Verify database, runtime, and API**

Confirm all of the following on test1:

```text
V101 script=V101__normal_group_creation.sql checksum=419410967 success=1
V102 script=V102__pull_task_manager_admin_stage.sql success=1
V102 checkpoint stage_renumbered=1 manager_rewound=1
armada-backend State.Status=running and restart count remains stable
backend log contains "Started Application" after the deployment
GET /api/account-groups returns an accepted application response code
armada-nginx remains running without a deployment-time restart
```

- [ ] **Step 6: Report the exact commits and verification evidence**

Report the design commit, plan commit, migration fix commit, Maven results, Flyway V101/V102 rows, checkpoint state, container stability, and API smoke result. Do not report or expose `.env`, database credentials, tokens, or private-key content.
