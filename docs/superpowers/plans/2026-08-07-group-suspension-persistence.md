# Group Suspension Persistence Implementation Plan

> **For Codex:** Execute this plan in order and use test-driven development for every behavior change.

**Goal:** Persist explicit WhatsApp `suspended/terminated` group signals from both Web and Android protocols and prevent visible-group snapshots from clearing the resulting ban.

**Architecture:** Both protocol implementations publish the existing critical `group.health_reported` v1 event with `health=BANNED` and a precise reason code. Armada accepts real-time reports without `groupLinkId`, resolves the group inside the tenant by `groupJid`, and writes the existing `group_link_health` aggregate. Account group snapshot SQL preserves an existing ban while still refreshing member count and observation time.

**Tech Stack:** TypeScript/Jest/Baileys, Go/testing/kafka-go, Java 17/Spring/MyBatis/JUnit 5/H2.

---

### Task 1: Web protocol publishes explicit group terminal signals

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/events/subjects.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/events/subjects.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/group-signal.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/group-signal.test.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.ts`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.heartbeat.test.ts`

1. Add failing tests proving `suspended` and `terminated` WGP2 nodes normalize to `BANNED/CHAT_*`, ordinary nodes do not, and the event remains critical and group-routed.
2. Add a failing AccountManager test proving the raw notification publishes `group.health_reported` with business references, JID, health, reason and timestamp.
3. Run the focused Jest tests and confirm they fail for the missing behavior.
4. Implement the smallest signal normalizer, event registration and publish call. Preserve the user's existing uncommitted AccountManager changes.
5. Re-run the focused Jest tests and type checking.

### Task 2: Android protocol parses and publishes WGP2 and HistorySync signals

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/processor/group_notification.go`
- Modify: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/processor/group_notification_test.go`
- Add: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/group_health_event.go`
- Add: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/group_health_event_test.go`
- Add: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/group_health_publisher.go`
- Add: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/group_health_publisher_test.go`
- Modify: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/group_snapshot_coordinator.go`
- Modify: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/group_snapshot_coordinator_test.go`
- Modify: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/start.go`
- Modify: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/start_test.go`

1. Add failing parser tests for WGP2 `suspended/terminated` producing `GroupChatStateChangedEvent` with a valid group JID.
2. Add failing event-builder/publisher tests for the exact `group.health_reported` v1 envelope and group topic routing.
3. Add failing coordinator tests proving WGP2 and HistorySync `true` states publish one ban, while nil/false states do not.
4. Run focused Go tests and confirm failure.
5. Implement the parser case, health event builder/publisher, coordinator dependency and startup wiring with the existing online command context.
6. Re-run focused Go tests and `go test ./internal/armada ./internal/service/node/processor`.

### Task 3: Backend accepts JID-only health reports and persists the ban

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumer.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/platform/kafka/consumer/group/ProtocolGroupEventConsumerTest.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/GroupLinkHealthReportServiceImpl.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/group/service/GroupLinkHealthReportServiceImplTest.java`

1. Add a failing consumer test for `tenantId + groupJid + BANNED` without `groupLinkId` and update the missing-routing test to reject only when tenant or JID is missing.
2. Add failing service tests for tenant-scoped JID resolution, ban mapping, mismatch rejection and unknown JID skip.
3. Run the two focused JUnit test classes and confirm failure.
4. Relax consumer routing to require tenant/JID, then resolve the optional link ID in the group service using `GroupLinkMapper.selectActiveIdByGroupJid` under `TenantContext`.
5. Re-run the focused JUnit tests.

### Task 4: Visible-group snapshots preserve explicit bans

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/group/GroupLinkHealthMapper.xml`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/mapper/GroupLinkHealthMapper.java`
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java`

1. Add a failing H2/MyBatis test that seeds a banned health row, applies `updateFromAccountGroupSync` and `upsertFromAccountGroupSync`, and asserts ban/status/reason/failure count survive while count/time refresh.
2. Run the focused mapper test and confirm failure.
3. Change both account-sync SQL paths to preserve terminal health fields when the stored row is banned. Keep the generic `upsert` unchanged so an explicit healthy report can recover the group.
4. Run XML validation and the focused H2 test.

### Task 5: Cross-repository verification and handoff

**Files:**
- Modify: `/Users/daishuaishuai/IdeaProjects/armada/.harness/changes/2026-08-07-group-suspension-persistence.md`

1. Run Web focused Jest tests and type checking.
2. Run Android focused and package-level Go tests.
3. Run backend focused JUnit tests, H2 mapper tests, XML validation and compile.
4. Review each repository's diff and status to ensure no user-owned changes were overwritten.
5. Record exact commands/results in the change record. Do not deploy to the first test environment without a separate deployment confirmation.
