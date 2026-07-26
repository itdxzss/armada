# Promotion Channel Facebook CAPI Probe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Execute inline with TDD; do not commit because the user requires IDEA Commit-area review.

**Goal:** Add a safe Facebook CAPI test-event probe endpoint that persists the latest probe state and returns the failure-detail shape required by the channel page.

**Architecture:** `PromotionChannelController -> PromotionChannelService -> PromotionChannelMapper/FacebookCapiProbeClient`. The service atomically claims the database probe state, performs the external HTTP call outside a transaction, and persists a sanitized terminal result. Existing tenant interception, response wrappers, and exception handling remain unchanged.

**Tech Stack:** Java 17, Spring Boot 3.3.5 `RestClient`, MyBatis/MySQL 8, JUnit 5, Mockito, Spring MockMvc.

## Global Constraints

- Require a Meta `testEventCode`; never send a probe into the production event stream.
- Never return or log Access Token plaintext, ciphertext, fingerprint, authorization header, or raw platform response.
- Implement Facebook only; other platforms return an `ABNORMAL` detail result.
- Reuse V061 probe columns; no schema or index changes.
- Do not modify shared/public framework behavior and do not commit.

---

### Task 1: Lock the API and crypto contract with failing tests

**Files:**
- Modify: `armada-api/src/test/java/com/armada/promotion/channel/controller/PromotionChannelControllerTest.java`
- Modify: `armada-api/src/test/java/com/armada/promotion/channel/security/PromotionTokenCipherTest.java`
- Modify: `armada-api/src/test/java/com/armada/promotion/channel/service/impl/PromotionChannelServiceImplTest.java`

**Interfaces:**
- Produces: `PromotionChannelProbeDTO(String testEventCode)` and `PromotionChannelProbeVO` contract expectations.
- Produces: `PromotionTokenCipher.decrypt(byte[] ciphertext, String storedKeyId)` expectation.

- [ ] Add a MockMvc test for `POST /api/promotion-channels/probe/51` asserting `ABNORMAL`, nullable event fields, `trackingId`, `accessTokenConfigured`, and absence of token material.
- [ ] Add encryption/decryption round-trip, key-version mismatch, and tampered-ciphertext tests.
- [ ] Add service tests for success, missing configuration, unsupported platform, duplicate probe, and sanitized client failure.
- [ ] Run the three tests and verify compilation/test failure is caused by the missing probe types and methods.

### Task 2: Implement the database probe state contract

**Files:**
- Create: `armada-api/src/main/java/com/armada/promotion/channel/model/vo/PromotionChannelProbeConfigRow.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/model/entity/PromotionChannelTrackingConfig.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/mapper/PromotionChannelMapper.java`
- Modify: `armada-api/src/main/resources/mapper/promotion/channel/PromotionChannelMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/promotion/channel/mapper/PromotionChannelMapperSqlContractTest.java`

**Interfaces:**
- Produces: `selectProbeConfigByChannelId(Long)` with channel/platform/domain and encrypted tracking fields.
- Produces: `markProbeRunning(PromotionChannelTrackingConfig,long)` and `updateProbeResult(PromotionChannelTrackingConfig)`.

- [ ] Add SQL contract assertions for explicit sensitive projection, soft-delete filters, atomic stale-running condition, and terminal result update.
- [ ] Run the SQL test and verify RED.
- [ ] Implement explicit-column SELECT and indexed UPDATE statements using existing `(tenant_id, channel_id)` uniqueness.
- [ ] Run the SQL test and verify GREEN.

### Task 3: Implement token decryption and Facebook HTTP adapter

**Files:**
- Create: `armada-api/src/main/java/com/armada/promotion/channel/service/FacebookCapiProbeClient.java`
- Create: `armada-api/src/main/java/com/armada/promotion/channel/service/impl/HttpFacebookCapiProbeClient.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/security/PromotionTokenCipher.java`
- Create: `armada-api/src/test/java/com/armada/promotion/channel/service/impl/HttpFacebookCapiProbeClientTest.java`

**Interfaces:**
- Consumes: Pixel ID, plaintext token, test code, source URL, event ID/time.
- Produces: a result with `success`, stable error code, and sanitized message.

- [ ] Add a local-JDK-HTTP-server test asserting Bearer authorization, `test_event_code`, synthetic `PageView`, and no real user data.
- [ ] Add HTTP 401, 404, 429, timeout/general error mapping tests.
- [ ] Run the client/crypto tests and verify RED.
- [ ] Implement AES-GCM decrypt symmetry and a private `RestClient` adapter with bounded timeouts and stable error mapping.
- [ ] Run the client/crypto tests and verify GREEN.

### Task 4: Wire the probe workflow

**Files:**
- Create: `armada-api/src/main/java/com/armada/promotion/channel/model/dto/PromotionChannelProbeDTO.java`
- Create: `armada-api/src/main/java/com/armada/promotion/channel/model/vo/PromotionChannelProbeVO.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/controller/PromotionChannelController.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/service/PromotionChannelService.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/service/impl/PromotionChannelServiceImpl.java`

**Interfaces:**
- Produces: `PromotionChannelProbeVO probe(Long id, PromotionChannelProbeDTO request)`.

- [ ] Implement failure-detail creation for unsupported/missing configuration without calling Meta.
- [ ] Validate `TEST` code, atomically claim a complete Facebook configuration, decrypt, call the client outside a transaction, and persist the terminal state.
- [ ] Add `POST /probe/{id}` controller delegation.
- [ ] Run all probe/channel tests and verify GREEN.

### Task 5: Verify, review, and stage

**Files:**
- Modify: `.harness/changes/promotion-template-channel-statistics/summary.md`
- Include: the design and plan documents created for this task.

- [ ] Run scoped Maven tests and `mvn -DskipTests package`.
- [ ] If `armada-api/.env` and an explicitly confirmed local MySQL target exist, run the scoped DbTest; otherwise report it as not run.
- [ ] Review Java layering, MySQL concurrency/tenant filters, and Token/HTTP logging safety.
- [ ] Run `git diff --check`, then stage only this task's files; leave existing staged deployment changes untouched and create no commit.
