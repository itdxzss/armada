# Account State Event Tenant Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry Armada business account identity through protocol online commands and state events so Kafka state write-back restores the correct tenant context.

**Architecture:** Armada publishes `tenantId/accountId/protocolAccountId` in online command payloads. The protocol worker stores that reference on its account context and includes it in state events. Armada consumes the reference, restores `TenantContext`, verifies the protocol account ID, and reuses existing lifecycle update logic.

**Tech Stack:** Java 17, Spring Boot 3.3.5, MyBatis-Plus tenant interceptor, JUnit/Mockito, Node 20, TypeScript, Jest.

---

### Task 1: Armada Command Payload

**Files:**
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/producer/ProtocolCommandPublisherTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/producer/ProtocolCommandPublisher.java`

- [ ] Add a failing test assertion that hydrated `account.online.requested` Kafka payload contains `tenantId`.
- [ ] Run the focused publisher test and verify it fails because `tenantId` is missing.
- [ ] Add `tenantId` to `OnlineCommandKafkaPayload` from `OnlineRowRef`.
- [ ] Re-run the focused publisher test and verify it passes.

### Task 2: Armada State Event Parsing and Tenant Context

**Files:**
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumerTest.java`
- Modify: `armada-api/src/test/java/com/armada/account/service/impl/AccountStateChangedSinkAdapterTest.java`
- Modify: `armada-api/src/test/java/com/armada/account/service/AccountStateEventServiceImplDbTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountStateChangedEvent.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumer.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/AccountStateChangedEvent.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountStateChangedSinkAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountStateEventServiceImpl.java`

- [ ] Add failing parser assertions for `tenantId` and local `accountId` in `account.state_changed`.
- [ ] Add failing service test coverage showing Kafka-thread state update works when `TenantContext` starts empty.
- [ ] Extend event records and adapter mapping with `tenantId/accountId`.
- [ ] In the service, set and restore `TenantContext`, load by local `accountId`, verify `protocolAccountId`, then run the existing update logic.
- [ ] Re-run focused Armada tests.

### Task 3: Protocol Worker Business Reference

**Files:**
- Modify: `armada-protocol/protocol-layer/src/commands/worker-consumer.test.ts`
- Modify: `armada-protocol/protocol-layer/src/worker/account-manager.transient.test.ts`
- Modify: `armada-protocol/protocol-layer/src/commands/worker-consumer.ts`
- Modify: `armada-protocol/protocol-layer/src/worker/account-manager.ts`

- [ ] Add failing worker-consumer expectation that online passes `{ tenantId, accountId, protocolAccountId }` to `accounts.online`.
- [ ] Add failing AccountManager test that `account.state_changed` event data includes the stored business reference.
- [ ] Add a narrow `AccountBusinessRef` type and optional parameter to `accounts.online`.
- [ ] Store business reference on `AccountContext` and merge it into `account.state_changed` and `account.need_reauth` data.
- [ ] Re-run focused protocol tests.

### Task 4: Verification

- [ ] Run focused Java tests for publisher, consumer, adapter, and state service.
- [ ] Run focused TypeScript tests for command parsing, worker command execution, and AccountManager state events.
- [ ] If tests pass, deploy Armada backend and protocol layer only if needed for live validation.
- [ ] Re-click/replay online on test env and confirm `NEED_REAUTH` updates `account_state` instead of logging `tenant_id=-1` symptoms.
