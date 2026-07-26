# Promotion Channel Update/Delete Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add transaction-safe promotion channel update and soft-delete APIs without changing shared infrastructure or the existing schema.

**Architecture:** Extend the existing `PromotionChannelController -> PromotionChannelService -> PromotionChannelMapper` chain. Reuse current validation, domain resolution, country lookup, token encryption and tenant interception; add only update/delete-specific DTO and SQL.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis XML, JUnit 5, Mockito, MockMvc, AssertJ, MySQL 8.

## Global Constraints

- Theme color stays frontend-only and is not accepted or persisted.
- Do not modify shared response, paging, tenant, security or exception code.
- Access Token is never returned or logged; a blank update Token preserves ciphertext only when provider and tracking ID are unchanged and stored ciphertext is complete.
- Delete uses `deleted_at`; domain and historical account references remain intact.
- No Flyway or schema change is required.
- Do not create a Git commit; stage only after verification.

---

### Task 1: Lock the HTTP contract

**Files:**
- Create: `armada-api/src/main/java/com/armada/promotion/channel/model/dto/PromotionChannelUpdateDTO.java`
- Modify: `armada-api/src/test/java/com/armada/promotion/channel/controller/PromotionChannelControllerTest.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/controller/PromotionChannelController.java`

**Interfaces:**
- Consumes: existing `ApiResponse`, `PromotionChannelService` and JSON field conventions.
- Produces: `PUT /api/promotion-channels/{id}` and `DELETE /api/promotion-channels/{id}`.

- [ ] Write MockMvc tests for update field binding, Facebook/TikTok aliases, no tenant parameter and delete path ID.
- [ ] Run `mvn -Dtest=PromotionChannelControllerTest test` and verify RED because service/controller methods are absent.
- [ ] Add the update DTO and controller methods with complete Javadoc.
- [ ] Run the controller test and verify GREEN after the service interface exists.

### Task 2: Lock update/delete business behavior

**Files:**
- Modify: `armada-api/src/test/java/com/armada/promotion/channel/service/impl/PromotionChannelServiceImplTest.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/service/PromotionChannelService.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/service/impl/PromotionChannelServiceImpl.java`

**Interfaces:**
- Consumes: `PromotionChannelUpdateDTO`, current domain/country/template validation and `PromotionTokenCipher`.
- Produces: `void update(Long id, PromotionChannelUpdateDTO request)` and `void delete(Long id)`.

- [ ] Write service tests for updating the main row, replacing a supplied Token, preserving a blank Token, soft-deleting tracking for non-CAPI platforms, rejecting missing channels and soft-deleting channel/tracking together.
- [ ] Run `mvn -Dtest=PromotionChannelServiceImplTest test` and verify RED because mapper/service methods are absent.
- [ ] Add service interface methods and minimal transactional implementation.
- [ ] Reuse the existing validated write model, domain resolver, template lookup, country lookup and token cipher rather than creating parallel validation.
- [ ] Run the service test and verify GREEN.

### Task 3: Add tenant-safe update and soft-delete SQL

**Files:**
- Modify: `armada-api/src/test/java/com/armada/promotion/channel/mapper/PromotionChannelMapperSqlContractTest.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/mapper/PromotionChannelMapper.java`
- Modify: `armada-api/src/main/resources/mapper/promotion/channel/PromotionChannelMapper.xml`

**Interfaces:**
- Consumes: `PromotionChannel`, `PromotionChannelTrackingConfig` and MyBatis tenant interception.
- Produces: active channel lookup, main row update, tracking update/revival, tracking soft delete and channel soft delete.

- [ ] Write SQL contract tests requiring `deleted_at IS NULL`, dynamic Token columns, no physical `DELETE`, and no explicit tenant request parameter.
- [ ] Run `mvn -Dtest=PromotionChannelMapperSqlContractTest test` and verify RED.
- [ ] Add Mapper methods and minimal XML statements; all updates use the current row ID and active-row predicates.
- [ ] Run the SQL contract test and verify GREEN.

### Task 4: Verify and hand off

**Files:**
- Modify: `.harness/changes/promotion-template-channel-statistics/summary.md`
- Modify: files changed by Tasks 1-3.

**Interfaces:**
- Consumes: completed implementation and test evidence.
- Produces: reviewable staged changes without a commit.

- [ ] Update the change summary with API paths, no-schema decision, Token behavior and delete behavior.
- [ ] Run channel controller/service/mapper tests together and record exact counts.
- [ ] Run Maven compile/package-level verification appropriate to the module.
- [ ] Run `git diff --check` and inspect the complete diff for unrelated changes.
- [ ] Request Java review and database/SQL review; fix blocking findings and rerun tests.
- [ ] Stage only this feature's files plus the pre-existing user-approved staged files; do not commit or push.
