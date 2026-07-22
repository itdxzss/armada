# Template Single Domain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Execute inline with TDD. The user requires staging for IDEA review and no commit.

**Goal:** Enforce one active domain per landing template while allowing multiple channel codes for that template.

**Architecture:** Keep `promotion_domain` as the template-domain binding. Add a reverse Mapper lookup and conflict-only current reads, reuse the existing `resolveDomain` path for create/update, and add a tenant-scoped unique key as the concurrency guard.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis, MySQL 8, Flyway, JUnit 5, Mockito.

## Global Constraints

- Do not change channel API request/response fields.
- Do not regenerate `channel_code` during edit.
- Do not add a table or shared error code.
- Use Flyway V064 for the unique key.
- Do not commit or push.

---

### Task 1: Lock service behavior with failing tests

**Files:**
- Modify: `armada-api/src/test/java/com/armada/promotion/channel/service/impl/PromotionChannelServiceImplTest.java`

**Interfaces:**
- Consumes: existing `PromotionChannelServiceImpl.create` and `update`.
- Produces: expected conflict behavior for the shared `resolveDomain` method.

- [x] Add a create test where `selectActiveDomainByHost(newHost)` is null and `selectActiveDomainByTemplateId(templateId)` returns another host; assert `BusinessException` conflict and no domain/channel insert.
- [x] Add an update test for the same template/different domain rule; assert no channel update.
- [x] Run the scoped test and verify RED because the template lookup does not exist.

### Task 2: Implement the reverse lookup and shared validation

**Files:**
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/mapper/PromotionChannelMapper.java`
- Modify: `armada-api/src/main/resources/mapper/promotion/channel/PromotionChannelMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/service/impl/PromotionChannelServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/promotion/channel/mapper/PromotionChannelMapperSqlContractTest.java`

**Interfaces:**
- Produces: `PromotionDomain selectActiveDomainByTemplateId(@Param("templateId") Long templateId)`.

- [x] Add SQL contract assertions for explicit columns, `landing_template_id = #{templateId}`, `deleted_at IS NULL`, and `LIMIT 1`.
- [x] Add the Mapper method and SELECT.
- [x] Update `resolveDomain`: reuse by host when template matches; otherwise reject; when host is free, reuse/reject by template host; only then insert.
- [x] In `DuplicateKeyException`, use `FOR UPDATE` current reads by host and template and return the same business result.
- [x] Run the scoped Service and Mapper tests and verify GREEN.

### Task 3: Add the database concurrency constraint

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V064__promotion_template_single_domain.sql`
- Modify: `armada-api/src/test/java/com/armada/promotion/PromotionSchemaSqlContractTest.java`

**Interfaces:**
- Produces: unique key `uq_promotion_domain_tenant_template (tenant_id, landing_template_id)`.

- [x] Add a failing schema contract test that reads V064 and asserts one `ALTER TABLE promotion_domain` with the exact unique key.
- [x] Create V064 with `ALTER TABLE promotion_domain ADD UNIQUE KEY uq_promotion_domain_tenant_template (tenant_id, landing_template_id);`.
- [x] Run the schema and Flyway contract tests and verify GREEN.

### Task 4: Verify and stage

**Files:**
- Modify: `.harness/changes/promotion-template-channel-statistics/summary.md`

**Interfaces:**
- Produces: staged code and migration for manual IDEA review.

- [x] Run the scoped service, Mapper, schema, Flyway tests.
- [x] Run `mvn -DskipTests package`.
- [x] Run `git diff --check` and Java/database reviews.
- [x] Stage only this task's files while preserving existing staged work; do not commit.
