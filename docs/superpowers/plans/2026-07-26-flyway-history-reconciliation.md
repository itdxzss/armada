# Flyway History Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the test1-compatible V061-V075 Flyway history, move the pending desired-login-state migration to V076, and deploy `1.0.2-snapshot` successfully.

**Architecture:** Treat the currently running test1 JAR as the byte-exact source of truth for already-applied V061-V075 migrations because it passes Flyway validation against that database. Lock the restored history with filename and Flyway-checksum contract tests; only V076 remains pending. Keep database history untouched and let normal Flyway startup apply V076.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Flyway 10.10, JUnit 5, AssertJ, Bash, Maven, Docker Compose.

---

### Task 1: Add failing migration-history and deployment-readiness contracts

**Files:**
- Create: `armada-api/src/test/java/com/armada/boot/FlywayMigrationVersionContractTest.java`
- Create: `armada-api/src/test/java/com/armada/boot/FlywayMigrationHistoryContractTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/grouppull/GroupPullMarketingMigrationSqlTest.java`
- Modify: `armada-deploy/deploy-test.test.sh`

- [ ] **Step 1: Restore the normalized Flyway version uniqueness test**

Restore the existing proven test from `origin/1.0.1-snapshot-wyfBranch`; it scans every
`V*__*.sql` filename, normalizes Flyway-equivalent version spellings, and rejects duplicates.

- [ ] **Step 2: Write the V061-V076 history contract test**

Create a test whose expected map contains exactly these filenames and checksums:

```java
Map.entry("061", new MigrationSpec("V061__promotion_template_channel_statistics.sql", 1676422917)),
Map.entry("062", new MigrationSpec("V062__promotion_channel_country_values.sql", 232278498)),
Map.entry("063", new MigrationSpec("V063__promotion_template_visibility_and_seed.sql", 1573867056)),
Map.entry("064", new MigrationSpec("V064__promotion_template_single_domain.sql", -611435249)),
Map.entry("065", new MigrationSpec("V065__promotion_domain_soft_delete_uniqueness.sql", 1198524220)),
Map.entry("066", new MigrationSpec("V066__promotion_channel_runtime_config.sql", 748421947)),
Map.entry("067", new MigrationSpec("V067__promotion_pairing_account_phone_index.sql", -1457194837)),
Map.entry("068", new MigrationSpec("V068__promotion_pairing_ip_reservation.sql", 1122157768)),
Map.entry("069", new MigrationSpec("V069__promotion_pairing_session.sql", -1009231184)),
Map.entry("070", new MigrationSpec("V070__group_pull_marketing.sql", -168361012)),
Map.entry("071", new MigrationSpec("V071__system_management_rbac.sql", -315144987)),
Map.entry("072", new MigrationSpec("V072__default_tenant_admin_user.sql", 166505662)),
Map.entry("073", new MigrationSpec("V073__default_admin_password.sql", -1156189687)),
Map.entry("074", new MigrationSpec("V074__default_admin_password_policy.sql", -1863275304)),
Map.entry("075", new MigrationSpec("V075__restore_task_center_menu_structure.sql", 2104574531)),
Map.entry("076", new MigrationSpec("V076__account_desired_login_state.sql", -1202454221))
```

Use Flyway's pinned `ChecksumCalculator` with `FileSystemResource` so the test follows the
same checksum algorithm as runtime rather than a hand-written approximation. Assert both the
exact V061-V076 filename set and every checksum.

- [ ] **Step 3: Point the group-pull migration test at V070**

Change only its migration path from `V061__group_pull_marketing.sql` to
`V070__group_pull_marketing.sql`; retain all SQL-content assertions.

- [ ] **Step 4: Add a deployment readiness regression assertion**

Extend `deploy-test.test.sh` to require the account-groups readiness command to use `curl -sS`
without `-f` and to accept the current unauthenticated business code `40104` alongside the
legacy accepted codes.

- [ ] **Step 5: Run tests and verify RED**

Run:

```bash
cd armada-api
mvn -Dtest='FlywayMigrationVersionContractTest,FlywayMigrationHistoryContractTest,GroupPullMarketingMigrationSqlTest' test
cd ..
bash armada-deploy/deploy-test.test.sh
```

Expected: Java tests fail because V061-V075/V076 are not yet aligned; deployment test fails
because readiness still uses `curl -fsS` and does not accept `40104`.

### Task 2: Restore migrations and fix readiness detection

**Files:**
- Delete: `armada-api/src/main/resources/db/migration/V061__group_pull_marketing.sql`
- Delete: `armada-api/src/main/resources/db/migration/V062__account_desired_login_state.sql`
- Create: `armada-api/src/main/resources/db/migration/V061__promotion_template_channel_statistics.sql`
- Create: `armada-api/src/main/resources/db/migration/V062__promotion_channel_country_values.sql`
- Create: `armada-api/src/main/resources/db/migration/V063__promotion_template_visibility_and_seed.sql`
- Create: `armada-api/src/main/resources/db/migration/V064__promotion_template_single_domain.sql`
- Create: `armada-api/src/main/resources/db/migration/V065__promotion_domain_soft_delete_uniqueness.sql`
- Create: `armada-api/src/main/resources/db/migration/V066__promotion_channel_runtime_config.sql`
- Create: `armada-api/src/main/resources/db/migration/V067__promotion_pairing_account_phone_index.sql`
- Create: `armada-api/src/main/resources/db/migration/V068__promotion_pairing_ip_reservation.sql`
- Create: `armada-api/src/main/resources/db/migration/V069__promotion_pairing_session.sql`
- Create: `armada-api/src/main/resources/db/migration/V070__group_pull_marketing.sql`
- Create: `armada-api/src/main/resources/db/migration/V071__system_management_rbac.sql`
- Create: `armada-api/src/main/resources/db/migration/V072__default_tenant_admin_user.sql`
- Create: `armada-api/src/main/resources/db/migration/V073__default_admin_password.sql`
- Create: `armada-api/src/main/resources/db/migration/V074__default_admin_password_policy.sql`
- Create: `armada-api/src/main/resources/db/migration/V075__restore_task_center_menu_structure.sql`
- Create: `armada-api/src/main/resources/db/migration/V076__account_desired_login_state.sql`
- Modify: `armada-deploy/lib/armada.sh`

- [ ] **Step 1: Acquire the known-good migration source safely**

Copy the currently running test1 old JAR to a private temporary directory. Extract only
`BOOT-INF/classes/db/migration/V061__*.sql` through `V075__*.sql`; do not copy credentials or
any runtime configuration.

- [ ] **Step 2: Restore V061-V075 byte-for-byte**

Add the extracted SQL files without editing line endings, whitespace, comments, or statements.
This preserves the already-applied database checksums. Remove the two conflicting current files.

- [ ] **Step 3: Add V076 from the current desired-login migration**

Copy the content of `V062__account_desired_login_state.sql` unchanged to
`V076__account_desired_login_state.sql`; only the filename/version changes.

- [ ] **Step 4: Fix both readiness checks**

In `armada_wait_backend_ready` and `armada_verify_api_proxy`, change the protected API request
from `curl -fsS` to `curl -sS` so a 401 response body remains available, and extend the business
code regex to `(40101|40104|0|40001)`.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run the exact commands from Task 1 Step 5. Expected: all selected Java and Bash tests pass.

### Task 3: Verify artifact completeness, review, commit, and deploy test1

**Files:**
- Modify: `.harness/changes/2026-07-26-flyway-history-reconciliation.md`

- [ ] **Step 1: Run full proportional verification**

```bash
cd armada-api
mvn test
mvn -q -DskipTests clean package
jar tf target/armada-api-1.0.2-SNAPSHOT.jar | grep -E 'BOOT-INF/classes/db/migration/V0(6[1-9]|7[0-6])__' | sort
cd ..
bash -n armada-deploy/deploy-test.sh armada-deploy/lib/armada.sh armada-deploy/deploy-test.test.sh
bash armada-deploy/deploy-test.test.sh
bash armada-deploy/package-prod.test.sh
git diff --check
```

Expected: Maven commands exit 0; packaged JAR lists exactly V061-V076; script checks exit 0;
no whitespace errors.

- [ ] **Step 2: Perform expert review**

Review the complete diff against database/Flyway rules. Blocking conditions are any missing
version, checksum drift, manual history manipulation, duplicate version, or readiness check that
can accept an empty/error response.

- [ ] **Step 3: Update the change record and commit**

Record real test outputs and the migration source. Commit only task files; preserve unrelated
`.claude/worktrees` state. Push `1.0.2-snapshot`.

- [ ] **Step 4: Reconfirm test1 history and deploy**

Read-only query test1 `flyway_schema_history` V061-V075 and compare every checksum to the contract,
then run:

```bash
./armada-deploy/deploy-test.sh --env test1 --be --branch 1.0.2-snapshot -y
```

- [ ] **Step 5: Verify real deployment state**

Require deployment exit 0, container `running`, restart count 0, a fresh `Started Application`
log, no Flyway validation/migration error, V076 present and successful in schema history, and
`/api/account-groups` returning the expected unauthenticated JSON response.
