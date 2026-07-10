# Marketing Template Lock And Terminal Material UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. The user requires all work to remain uncommitted.

**Goal:** Serialize marketing-task creation with template deletion, and prevent completed or closed tasks from opening the material editor in the frontend.

**Architecture:** Both task creation and batch template deletion acquire pessimistic locks on the same `marketing_template` rows before touching task state. Batch deletion locks normalized template IDs in ascending order before completing tasks and releasing accounts. The frontend keeps the backend contract unchanged and applies terminal-state guards in both the table and drawer-opening handler.

**Tech Stack:** Java 17, Spring transactions, MyBatis XML, JUnit 5/Mockito, Vue 3, TypeScript, Element Plus, Node test runner.

---

### Task 1: Serialize template creation and deletion

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskServiceImplLifecycleTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTemplateServiceImplTest.java`
- Add: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTemplateMapperSqlShapeTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTemplateMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTemplateMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTemplateServiceImpl.java`

- [x] Add failing tests requiring task creation to call `selectByIdForUpdate`, and batch deletion to sort IDs and call `selectExistingIdsForUpdate` before task completion.
- [x] Run the focused Maven tests and verify they fail because the lock methods do not exist.
- [x] Add mapper methods and SQL using `FOR UPDATE`; switch task creation to the locked lookup and lock sorted template IDs first during deletion.
- [x] Run the focused Maven tests and verify they pass.

### Task 2: Hide terminal material editing in the frontend

**Files:**
- Modify: `src/views/task/group-marketing/components/GroupMarketingTaskLifecycleUi.test.ts`
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts`
- Modify: `src/views/task/group-marketing/components/GroupMarketingTaskTable.vue`
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts`

- [x] Add failing tests requiring the material button to use the active-state guard and the drawer-opening handler to reject statuses 7 and 8 without an API call.
- [x] Run the focused Node tests and verify the new assertions fail.
- [x] Show the material button only for statuses 1, 2 and 5, and add the same defensive guard to `openMaterialDrawer`.
- [x] Run the focused Node tests and verify they pass.

### Task 3: Regression verification

**Files:**
- Modify: `.harness/changes/marketing-task/summary.md`
- Modify: `wheel-saas-pure-web/.harness/changes/marketing-task-frontend/summary.md`

- [x] Run backend focused tests, test compilation, Mapper XML validation and `git diff --check`.
- [x] Run frontend group-marketing tests, type checks, lint/style checks, production build and `git diff --check`.
- [x] Confirm both worktrees have no commits beyond `1.0.1-snapshot` and preserve them for IDEA review.
