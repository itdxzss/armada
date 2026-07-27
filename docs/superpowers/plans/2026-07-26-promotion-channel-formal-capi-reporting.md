# Promotion Channel Formal Facebook CAPI Reporting Implementation Plan

> **For Codex:** Follow `superpowers:test-driven-development` for every behavior change and `superpowers:verification-before-completion` before reporting completion. Work in the two user-specified IDEA checkouts, preserve unrelated dirty files, and do not stage or commit.

**Goal:** Replace the front-end CAPI probe flow with reliable server-side Facebook CAPI reporting driven by the three confirmed pairing lifecycle transitions, while restricting channel configuration to the 18 supported Meta standard events.

**Architecture:** The channel domain owns the standard-event catalog, configuration validation, token decryption, and Graph API call. The pairing domain snapshots three configured events into a tenant-scoped MySQL outbox when a pairing session is created, activates the request and success stages in the same transactions as their business state changes, and asynchronously dispatches active rows without blocking pairing or account provisioning. The public page supplies optional browser attribution data; the protocol repository and protocol messages remain unchanged.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis, Flyway/MySQL 8, H2 integration tests, Vue 3, TypeScript, Element Plus, Node test runner.

---

## Task 1: Make the backend event catalog the single source of truth

**Files:**
- Create: `armada-api/src/main/java/com/armada/promotion/channel/model/enums/FacebookStandardEvent.java`
- Create: `armada-api/src/main/java/com/armada/promotion/channel/model/vo/FacebookStandardEventVO.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/controller/PromotionChannelController.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/service/PromotionChannelService.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/service/impl/PromotionChannelServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/promotion/channel/controller/PromotionChannelControllerTest.java`
- Modify: `armada-api/src/test/java/com/armada/promotion/channel/service/impl/PromotionChannelServiceImplTest.java`

1. Add failing tests proving the authenticated catalog endpoint returns the 18 codes in the approved order and that create/update reject a non-standard event.
2. Run the focused controller/service tests and confirm the new assertions fail for the missing catalog and permissive validation.
3. Implement the enum, response record, service method, controller route, and strict Service-layer normalization/defaulting.
4. Preserve the defaults `Lead`, `InitiateCheckout`, and `CompleteRegistration`; do not silently accept historical custom values on a new save.
5. Re-run the focused tests.

## Task 2: Remove the front-end probe and consume the backend catalog

**Files:**
- Modify: `D:/idea_project/wheel-saas-pure-web/src/api/buyer-channel.ts`
- Modify: `D:/idea_project/wheel-saas-pure-web/src/api/buyer-channel.test.ts`
- Delete: `D:/idea_project/wheel-saas-pure-web/src/api/buyer-channel-probe.test.ts`
- Modify: `D:/idea_project/wheel-saas-pure-web/src/views/buyer/channel/ChannelPageContract.test.ts`
- Modify: `D:/idea_project/wheel-saas-pure-web/src/views/buyer/channel/index.vue`
- Modify: `D:/idea_project/wheel-saas-pure-web/src/views/buyer/channel/components/ChannelFormDrawer.vue`
- Modify: `D:/idea_project/wheel-saas-pure-web/src/views/buyer/channel/components/channel-platform-fields.ts`
- Delete: `D:/idea_project/wheel-saas-pure-web/src/views/buyer/channel/components/ChannelDetectDialog.vue`

1. Replace probe contract tests with behavior tests for the catalog request, mapping, visible load failure, and three selects sharing the returned options.
2. Run the focused Node tests and confirm they fail before implementation.
3. Delete probe API/types/dialog/button/imports/state and load the event catalog together with existing channel form options.
4. Make all three Facebook selects required and backed only by the API options; prevent form submission when catalog loading failed.
5. Change UI copy from probe/test wording to formal server-side reporting wording.
6. Re-run focused front-end tests and type checking for the touched contracts.

## Task 3: Collect optional browser attribution at public pairing creation

**Files:**
- Modify: `D:/idea_project/wheel-saas-pure-web/src/views/buyer/public-promotion/domain/public-promotion-pairing.ts`
- Modify: `D:/idea_project/wheel-saas-pure-web/src/views/buyer/public-promotion/domain/public-promotion-pairing.test.ts`
- Modify: `D:/idea_project/wheel-saas-pure-web/src/views/buyer/public-promotion/composables/usePublicPromotionPairing.ts`
- Modify: `D:/idea_project/wheel-saas-pure-web/src/api/public-promotion-channel.ts`

1. Add failing pure-domain tests for `_fbp`, `_fbc`, valid `fbclid` fallback, invalid/oversized values, and HTTP/HTTPS source URL validation.
2. Run the domain tests and confirm the attribution cases fail.
3. Implement a side-effect-free attribution resolver and pass its optional values in the pairing-create request body.
4. Do not persist attribution in browser storage and do not make absence of attribution a pairing error.
5. Re-run the focused tests.

## Task 4: Add the tenant-scoped CAPI outbox with real Mapper coverage

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V081__promotion_capi_event_outbox.sql`
- Create: `armada-api/src/main/java/com/armada/promotion/pairing/model/entity/PromotionCapiEventOutbox.java`
- Create: `armada-api/src/main/java/com/armada/promotion/pairing/model/enums/PromotionCapiEventStage.java`
- Create: `armada-api/src/main/java/com/armada/promotion/pairing/model/enums/PromotionCapiEventStatus.java`
- Create: `armada-api/src/main/java/com/armada/promotion/pairing/mapper/PromotionCapiEventOutboxMapper.java`
- Create: `armada-api/src/main/resources/mapper/promotion/pairing/PromotionCapiEventOutboxMapper.xml`
- Create: `armada-api/src/test/java/com/armada/promotion/pairing/mapper/PromotionCapiEventOutboxSchemaDbTest.java`
- Create: `armada-api/src/test/java/com/armada/promotion/pairing/mapper/PromotionCapiEventOutboxMapperDbTest.java`

1. Add failing H2 tests for the table shape, unique session-stage/event IDs, tenant predicates, initial statuses, conditional activation, conditional claim, retry/dead/sent transitions, lock recovery, cancellation, and sensitive-field clearing.
2. Run the two DbTests and confirm the schema/Mapper failures.
3. Add the Flyway migration, model, enums, Mapper interface, and XML using explicit tenant conditions and conditional updates.
4. Keep `event_time` null while waiting; only `PENDING` rows may be claimed. Clear matching fields for `SENT`, `DEAD`, and `CANCELED`.
5. Re-run the DbTests. If the repository's existing duplicate Flyway version blocks startup, capture the exact baseline error and run the migration-specific H2 setup without modifying historical migrations.

## Task 5: Generalize the Facebook client for test and production events

**Files:**
- Delete: `armada-api/src/main/java/com/armada/promotion/channel/service/FacebookCapiProbeClient.java`
- Delete: `armada-api/src/main/java/com/armada/promotion/channel/service/impl/HttpFacebookCapiProbeClient.java`
- Create: `armada-api/src/main/java/com/armada/promotion/channel/service/FacebookCapiClient.java`
- Create: `armada-api/src/main/java/com/armada/promotion/channel/service/impl/HttpFacebookCapiClient.java`
- Delete: `armada-api/src/test/java/com/armada/promotion/channel/service/impl/HttpFacebookCapiProbeClientTest.java`
- Create: `armada-api/src/test/java/com/armada/promotion/channel/service/impl/HttpFacebookCapiClientTest.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/service/PromotionChannelService.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/service/impl/PromotionChannelServiceImpl.java`

1. Add failing local-HTTP-server tests for a production payload with a stable `event_id`, SHA-256 phone only, optional matching fields, no `test_event_code`, `events_received > 0`, retryable timeout/429/5xx, and permanent 4xx.
2. Run the client test and confirm the production method is missing.
3. Replace the probe-only abstraction with a shared client that retains the protected legacy test method and adds formal business delivery.
4. Add a channel Service operation that looks up the current tenant/channel tracking configuration, decrypts the token only for the call, and returns a sanitized delivery result.
5. Never include token, phone hash, IP, User-Agent, cookies, or raw Meta response in logs or persisted diagnostics.
6. Re-run client and channel service tests.

## Task 6: Snapshot and activate all three stages at the confirmed business transitions

**Files:**
- Modify: `armada-api/src/main/java/com/armada/promotion/channel/model/vo/PromotionChannelPairingContextRow.java`
- Modify: `armada-api/src/main/resources/mapper/promotion/channel/PromotionChannelMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/promotion/pairing/model/dto/PromotionPairingCreateDTO.java`
- Create: `armada-api/src/main/java/com/armada/promotion/pairing/model/command/PromotionPairingCreateCommand.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/pairing/controller/PromotionPairingPublicController.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/pairing/service/PromotionPairingService.java`
- Create: `armada-api/src/main/java/com/armada/promotion/pairing/service/PromotionCapiEventService.java`
- Create: `armada-api/src/main/java/com/armada/promotion/pairing/service/impl/PromotionCapiEventServiceImpl.java`
- Create: `armada-api/src/main/java/com/armada/promotion/pairing/service/impl/PromotionPairingTransitionService.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/pairing/service/impl/PromotionPairingServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/promotion/pairing/service/impl/PromotionPairingCompletionService.java`
- Modify corresponding controller, service, completion, Mapper SQL contract, and H2 tests under `armada-api/src/test/java/com/armada/promotion/`.

1. Add failing tests for attribution validation, trusted `X-Real-IP`/remote-address resolution, normalized phone hashing, three-row creation, request activation only after accepted state, success activation in the completion transaction, duplicate callback idempotency, and cancellation on failure/expiry.
2. Run focused tests and confirm the new behavior fails.
3. Extend the channel pairing-context query with the active Facebook platform/event mappings, without reading or exposing the Access Token.
4. Introduce narrow transactional transition methods so session insert plus three snapshots, accepted state plus request activation, and completed state plus success activation are atomic; keep the external protocol call outside database transactions.
5. Validate/normalize optional attribution and store only the E.164 phone SHA-256. Existing non-Facebook channels create no CAPI rows.
6. Re-run focused pairing and Mapper tests.

## Task 7: Dispatch active rows asynchronously without impacting business flow

**Files:**
- Create: `armada-api/src/main/java/com/armada/promotion/pairing/scheduler/PromotionCapiDispatchScheduler.java`
- Create: `armada-api/src/main/java/com/armada/promotion/pairing/service/impl/PromotionCapiDispatcher.java`
- Create: `armada-api/src/test/java/com/armada/promotion/pairing/scheduler/PromotionCapiDispatchSchedulerTest.java`
- Create: `armada-api/src/test/java/com/armada/promotion/pairing/service/impl/PromotionCapiDispatcherTest.java`
- Modify: `armada-api/src/main/resources/application.yml`

1. Add failing tests for conditional claiming, tenant-context restoration, successful delivery, bounded exponential backoff, permanent failure, maximum retry exhaustion, and stale-lock recovery.
2. Run the focused tests and confirm the dispatcher/scheduler behavior is absent.
3. Implement a property-controlled fixed-delay scheduler and dispatcher. Claim in short transactions, call Meta outside transactions, and update terminal state afterward.
4. Ensure all exceptions are absorbed into outbox retry/dead state and never modify pairing/account/protocol state.
5. Re-run scheduler/dispatcher tests.

## Task 8: Update Harness change and data-model records

**Files:**
- Create: `.harness/changes/promotion-channel-formal-capi-reporting/summary.md`
- Create: `.harness/changes/promotion-channel-formal-capi-reporting/db-migrations.sql`
- Create: `.harness/changes/promotion-channel-formal-capi-reporting/rollback.sql`
- Update or regenerate: `.harness/wiki/数据模型.md`
- Create: `D:/idea_project/wheel-saas-pure-web/.harness/changes/promotion-channel-formal-capi-reporting/summary.md`

1. Record behavior, interfaces, invariants, trigger semantics, privacy handling, compatibility, verification, and the protocol-layer non-change.
2. Copy the reviewed migration into `db-migrations.sql`; make rollback explicitly refuse normal use unless there are no unsent rows.
3. Use the repository generator for the data-model wiki if it supports migration input; do not silently hand-edit an auto-generated file or connect to an unconfirmed shared database.
4. Document any generator/environment blocker precisely.

## Task 9: Run gates and mandatory specialist reviews

1. Run all focused backend tests, then `mvn test` if the baseline Flyway collision permits it.
2. Run all touched front-end Node tests, `pnpm typecheck`, non-mutating scoped ESLint, and `pnpm build`.
3. Run `git diff --check` in both repositories and inspect both `git status --short` outputs to confirm unrelated files remain untouched.
4. Request Java, TypeScript, Vue, database, and security specialist reviews; fix findings with another red-green cycle.
5. Re-run affected gates after review fixes.
6. Report changed files, exact successful/blocked verification output, the pre-existing duplicate Flyway-version risk if still present, and how to enable/observe formal CAPI dispatch. Do not deploy, stage, or commit.
