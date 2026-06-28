# Marketing Task Backend Slices Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first-phase marketing task backend APIs incrementally, with review checkpoints after each small vertical slice.

**Architecture:** Keep all new Java code in `com.armada.marketing`. Reuse existing `marketing_template`, `account`, `account_state`, `group_link`, `group_link_preview`, and `group_link_health` tables as facts, but do not depend on other domains' mapper/entity classes. The first pass persists task configuration and target rows; it does not send WhatsApp messages or call the protocol layer.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis XML, Flyway schema from `V014`, real MySQL DbTest.

---

## Checkpoints

### Checkpoint 1: Save And Read Marketing Tasks

**Scope:** Implement persistence and service behavior for create/list/detail. Creating a task writes `marketing_task` plus `marketing_task_target` rows from explicit account→group selections. `startMode=IMMEDIATE` only sets status to `2=发送中`; no send job runs.

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTask.java`
- Create: `armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTaskTarget.java`
- Create: `armada-api/src/main/java/com/armada/marketing/model/enums/MarketingTaskStatus.java`
- Create: `armada-api/src/main/java/com/armada/marketing/model/dto/CreateMarketingTaskDTO.java`
- Create: `armada-api/src/main/java/com/armada/marketing/model/dto/MarketingSelectionDTO.java`
- Create: `armada-api/src/main/java/com/armada/marketing/model/dto/MarketingTaskQuery.java`
- Create: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskVO.java`
- Create: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskDetailVO.java`
- Create: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskTargetVO.java`
- Create: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Create: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Create: `armada-api/src/main/java/com/armada/marketing/service/MarketingTaskService.java`
- Create: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`

- [x] Write failing DbTest for create/list/detail.
- [x] Run focused DbTest against clean schema and verify RED.
- [x] Add entities, DTOs, VOs, mapper XML, and service.
- [x] Run focused DbTest and verify GREEN.
- [x] Stop for user review.

### Checkpoint 2: Controller Endpoints

**Scope:** Add REST endpoints around Checkpoint 1 behavior.

**Endpoints:**
- `GET /api/marketing-tasks`
- `POST /api/marketing-tasks`
- `GET /api/marketing-tasks/{id}`

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/controller/MarketingTaskController.java`
- Test: `armada-api/src/test/java/com/armada/marketing/controller/MarketingTaskControllerDbTest.java`

- [x] Write failing controller DbTest for list/create/detail.
- [x] Add controller.
- [x] Run focused controller DbTest and verify GREEN.
- [x] Stop for user review.

### Checkpoint 3: Start, Stop, Batch Delete

**Scope:** Implement task status mutation and deletion rules. Sending tasks cannot be deleted; start is allowed from pending/stopped, stop is allowed from sending.

**Endpoints:**
- `POST /api/marketing-tasks/{id}/start`
- `POST /api/marketing-tasks/{id}/stop`
- `POST /api/marketing-tasks/batch-delete`

**Files:**
- Modify: `MarketingTaskService.java`
- Modify: `MarketingTaskServiceImpl.java`
- Modify: `MarketingTaskMapper.java`
- Modify: `MarketingTaskMapper.xml`
- Modify: `MarketingTaskController.java`
- Test: `MarketingTaskMutationDbTest.java`

- [x] Write failing DbTest for start/stop/delete guards.
- [x] Add status update and soft-delete mapper methods.
- [x] Add service guards and controller endpoints.
- [x] Run focused mutation DbTest and controller DbTest.
- [x] Stop for user review.

### Checkpoint 4: Account Group Marketing Tree

**Scope:** Implement account→available group tree for the create drawer. Only online usable accounts are selectable; baseline JSON excludes groups that existed before the account baseline was captured.

**Endpoint:**
- `GET /api/marketing-tasks/account-tree?groupId=...`

**Files:**
- Create: `MarketingAccountTreeVO.java`
- Create: `MarketingTreeAccountVO.java`
- Create: `MarketingTreeGroupVO.java`
- Modify: `MarketingTaskMapper.java`
- Modify: `MarketingTaskMapper.xml`
- Modify: `MarketingTaskService.java`
- Modify: `MarketingTaskServiceImpl.java`
- Modify: `MarketingTaskController.java`
- Test: `MarketingTaskAccountTreeDbTest.java`

- [x] Write failing DbTest for online/offline account filtering and baseline exclusion.
- [x] Add mapper query joining `account`, `account_state`, `group_link`, `group_link_preview`, `group_link_health`, and `account_group_baseline`.
- [x] Add service tree grouping.
- [x] Add controller endpoint.
- [x] Run focused DbTest.
- [x] Stop for user review.

### Checkpoint 5: Modify Marketing Material Through Task

**Scope:** Support the existing requirement that modifying a task's marketing material updates the referenced shared marketing template. This is a service-level delegation to existing `MarketingTemplateService.update`.

**Endpoint:**
- `PUT /api/marketing-tasks/{id}/marketing-template`

**Files:**
- Modify: `MarketingTaskController.java`
- Modify: `MarketingTaskService.java`
- Modify: `MarketingTaskServiceImpl.java`
- Test: `MarketingTaskMaterialUpdateDbTest.java`

- [x] Write failing DbTest for updating a task's referenced template.
- [x] Add service method that verifies task exists and delegates to `MarketingTemplateService.update`.
- [x] Add controller endpoint.
- [x] Run focused DbTest.
- [x] Stop for user review.

### Checkpoint 6: Harness Docs And Smoke

**Scope:** Refresh docs after all reviewed slices are accepted.

**Files:**
- Modify: `.harness/changes/marketing-task/summary.md`
- Modify: `.harness/wiki/接口协议.md` if endpoint parser sees new APIs
- Modify: `docs/business/marketing-task-data-model.md` only if implementation reveals a schema contract correction

- [x] Update change summary with API scope and verification evidence.
- [x] Run focused marketing tests on a clean schema.
- [x] Run `git diff --check`.
- [x] Stop for final review.
