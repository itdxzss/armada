# Restore Marketing Group Send Time Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the existing account-group send-time field, 72-hour validation/default, detail display, request contract, and dynamic-group time boundary without reverting unrelated current-group synchronization work.

**Architecture:** Re-establish the former `accountGroupSendAt` data flow from Vue form to the Java task entity and from the scheduled worker to the dynamic-target Mapper query. Preserve the newer full current-membership synchronization and the removal of baseline/account/group-health eligibility filters.

**Tech Stack:** Vue 3, TypeScript, Element Plus, Java 17, Spring Boot, MyBatis, Maven, Node/Jiti.

---

### Task 1: Restore backend creation semantics

**Files:**
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/dto/CreateMarketingTaskDTO.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`

- [ ] Change the two creation tests to require a default of `taskStartAt - 72 hours` and rejection of an explicitly older timestamp with `账号群组发送时间最多支持追溯72小时`.
- [ ] Run `./dbtest.sh 'com.armada.marketing.service.MarketingTaskCreateReadDbTest#createTask_defaultsAccountGroupSendAtFromTaskStartMinusSeventyTwoHours+createTask_rejectsManualAccountGroupSendAtOlderThanSeventyTwoHours'` and confirm the current null/ignore implementation fails.
- [ ] Restore `ACCOUNT_GROUP_SEND_LOOKBACK_MS`, validation, `normalizeAccountGroupSendAt`, DTO documentation, and `task.setAccountGroupSendAt(accountGroupSendAt)`.
- [ ] Re-run the same DbTest selection and confirm both cases pass. If the local database configuration is unavailable, report that explicitly and run the focused service/unit compilation tests instead.

### Task 2: Restore dynamic-target time boundary

**Files:**
- Test: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeService.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeServiceTest.java`

- [ ] Make the SQL-shape test require `(#{accountGroupSendAt} IS NULL OR m.joined_at &gt;= #{accountGroupSendAt})`, while continuing to forbid baseline, account-state, membership-state, and group-health filters.
- [ ] Make worker and realtime-tree tests expect `selectDynamicTargetGroups(accountId, accountGroupSendAt)`.
- [ ] Run `mvn -q -Dtest=MarketingTaskMapperSqlShapeTest,MarketingRoundWorkerTest,MarketingAccountTreeRealtimeServiceTest test` and confirm failures are caused by the missing parameter/time predicate.
- [ ] Restore the two-argument Mapper signature, pass the task cutoff from the worker, pass `null` from realtime preview, and restore only the `joined_at` SQL predicate.
- [ ] Re-run the focused Maven tests and confirm they pass.

### Task 3: Restore frontend field and validation

**Files:**
- Test: `src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts`
- Test: `src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts`
- Test: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts`
- Modify: `src/api/marketing-task.ts`
- Modify: `src/views/task/group-marketing/components/GroupMarketingCreateDrawer.vue`
- Modify: `src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue`
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts`

- [ ] Restore test expectations for the labeled date picker, `accountGroupSendAt` payload, detail field, and rejection beyond 72 hours.
- [ ] Run the three Jiti tests and confirm they fail against the removed UI/data flow.
- [ ] Restore the request/form property, empty-form value, 72-hour disabled-date and validation logic, date picker with “此刻”, payload mapping, and detail description item.
- [ ] Re-run the three Jiti tests, then run the focused ESLint/typecheck command available in `package.json`.

### Task 4: Regression and scope verification

**Files:**
- Modify: `.harness/changes/2026-07-16-marketing-current-group-send.md`

- [ ] Update the change record so it no longer claims the field is ignored, null, or removed.
- [ ] Run `git diff --check` in both repositories.
- [ ] Run focused backend tests and frontend tests again from fresh processes.
- [ ] Inspect both repository diffs and confirm only account-group-send-time removal hunks were reversed; keep full current-membership synchronization and unrelated working-tree changes intact.
- [ ] Do not deploy, modify shared data, or commit unrelated files.
