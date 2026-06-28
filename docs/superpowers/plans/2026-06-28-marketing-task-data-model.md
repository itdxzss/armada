# Marketing Task Data Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first-phase marketing task data model to armada without implementing task APIs.

**Architecture:** The marketing domain owns `marketing_task`, `marketing_task_target`, and `marketing_task_send_attempt`. Account baseline filtering is modeled as one JSON snapshot row per account in `account_group_baseline`, with `account.group_baseline_state` as the worker state flag.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Flyway, MySQL 8, MyBatis, JUnit DbTest.

---

### Task 1: Schema Contract Test

**Files:**
- Create: `armada-api/src/test/java/com/armada/marketing/MarketingTaskDataModelMigrationDbTest.java`

- [x] **Step 1: Write the failing schema DbTest**

Create a DbTest that asserts the four new data model tables, the account baseline state column, key column data types, and unique indexes.

- [x] **Step 2: Run the focused DbTest and verify RED**

Run: `cd armada-api && ./dbtest.sh MarketingTaskDataModelMigrationDbTest`

Expected: FAIL because `marketing_task`, `marketing_task_target`, `marketing_task_send_attempt`, `account_group_baseline`, and `account.group_baseline_state` do not exist yet.

### Task 2: Flyway Migration

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V014__marketing_task_data_model.sql`

- [x] **Step 1: Add V014 migration**

Create:
- `account.group_baseline_state`
- `account_group_baseline`
- `marketing_task`
- `marketing_task_target`
- `marketing_task_send_attempt`

All tenant business tables must include `tenant_id`; all time columns use `BIGINT` epoch milliseconds; enum/status columns use `TINYINT` with comments.

- [x] **Step 2: Run the focused DbTest and verify GREEN**

Run: `cd armada-api && ./dbtest.sh MarketingTaskDataModelMigrationDbTest`

Expected: PASS on a clean schema. The existing default `armada` schema is blocked by a pre-existing Flyway history entry for missing local `V013__protocol_command_outbox.sql`.

### Task 3: Business And Harness Docs

**Files:**
- Create: `docs/business/marketing-task-data-model.md`
- Create: `.harness/changes/marketing-task/summary.md`
- Create: `.harness/changes/marketing-task/db-migrations.sql`
- Create: `.harness/changes/marketing-task/rollback.sql`

- [x] **Step 1: Document the data model**

Write the table responsibilities, fields, status codes, indexes, rejected alternatives, and why JSON baseline is used for account group baseline snapshots.

- [x] **Step 2: Add harness change files**

Copy the migration body into `db-migrations.sql`, add reverse DROP/ALTER statements to `rollback.sql`, and summarize scope and verification in `summary.md`.

### Task 4: Generated Data Model Wiki

**Files:**
- Modify: `.harness/wiki/数据模型.md`

- [x] **Step 1: Regenerate the data model wiki**

Run: export the migrated clean schema information_schema TSV, then `python3 .harness/wiki/gen_datamodel.py`.

Expected: `.harness/wiki/数据模型.md` contains the four new tables and `account.group_baseline_state`.

### Task 5: Final Verification

- [x] **Step 1: Run focused schema test**

Run: `cd armada-api && ./dbtest.sh MarketingTaskDataModelMigrationDbTest`

Expected: PASS on the migrated clean schema; default `armada` remains blocked by the missing local V013 migration.

- [x] **Step 2: Run XML/schema smoke where practical**

Run any existing focused schema smoke command available locally. If DB credentials are unavailable, report that blocker explicitly.
