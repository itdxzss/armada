# Account Event Topic Isolation Implementation Plan

> **For agentic workers:** Execute inline in the current `1.0.1-snapshot` workspaces. The user explicitly requires no worktree and no commits. Preserve all unrelated dirty files.

**Goal:** Route account state and account group-sync events through independent Kafka Topics and Armada consumer groups so group snapshot writes cannot delay ONLINE state updates.

**Architecture:** Web and Android keep one producer connection each but choose the Topic from the event type. Armada exposes two independent Kafka listener containers with configurable concurrency 4 and shared retry/DLT behavior. Existing sinks and business services remain unchanged.

**Tech Stack:** Java 17, Spring Boot 3.3.5, Spring Kafka, TypeScript 5.8, KafkaJS, Go 1.25, kafka-go.

---

### Task 1: Split Armada listener configuration

**Files:**
- Modify: `armada-api/src/main/resources/application.yml`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolKafkaConfiguration.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolAccountStateEventConsumerProperties.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolAccountGroupSyncEventConsumerProperties.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolAccountEventErrorProperties.java`
- Delete: `armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolAccountEventConsumerProperties.java`
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolKafkaConfigurationTest.java`

- [x] Add failing property-binding assertions for the two Topics, groups, concurrency 4, retry and DLT suffix.
- [x] Run `mvn -q -Dtest=ProtocolKafkaConfigurationTest test` and confirm failure.
- [x] Implement the three focused property classes and update configuration registration/error-handler dependency.
- [x] Add `spring.datasource.hikari.maximum-pool-size: ${DB_POOL_MAX_SIZE:20}`.
- [x] Re-run the focused test and confirm success.

### Task 2: Split Armada account listener entry points

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumer.java`
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumerTest.java`
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolKafkaListenerConfigurationTest.java`

- [x] Add failing tests for `onStateMessage`, `onGroupSyncMessage`, concurrency 4, and rejection of cross-Topic event types.
- [x] Run both focused test classes and confirm failure.
- [x] Replace the single listener method with two annotated methods while reusing existing parsing and sinks.
- [x] Keep state/offline parsing in the state entry and groups/membership parsing in the group-sync entry.
- [x] Re-run focused tests and confirm success.

### Task 3: Update Armada deployment configuration

**Files:**
- Modify: `armada-deploy/docker-compose.rds.yml`
- Modify: `armada-deploy/prod/app/docker-compose.yml`

- [x] Replace old account-event Topic/group variables with state and group-sync Topic/group/concurrency variables.
- [x] Expose `DB_POOL_MAX_SIZE` with default 20.
- [x] Run relevant compose/deployment script tests without deploying.

### Task 4: Split Web protocol producer routing

**Files:**
- Modify: `protocol-layer/src/config.ts`
- Modify: `protocol-layer/src/events/subjects.ts`
- Modify: `protocol-layer/src/events/subjects.test.ts`
- Modify: `protocol-layer/src/events/publisher.ts`
- Modify: `protocol-layer/src/events/publisher.test.ts`
- Modify: `protocol-layer/deploy/k8s/deployment.yaml`
- Modify: `armada-deploy/prod/protocol/docker-compose.yml`

- [x] Add failing tests proving state/offline route to the state Topic, groups/membership route to group-sync, and other account telemetry remains on the old account Topic.
- [x] Run the two focused Jest suites and confirm failure.
- [x] Add `topicAccountState`/`topicAccountGroupSync` config and event-kind routing.
- [x] Update producer connection logs and deployment environment variables.
- [x] Run focused Jest tests, `npm run lint`, and `npm run build`.

### Task 5: Split Android Zhuan producer routing

**Files:**
- Modify: `internal/configs/configs.go`
- Modify: `internal/configs/configs_test.go`
- Modify: `internal/armada/options.go`
- Modify: `internal/armada/options_test.go`
- Modify: `internal/armada/config.go`
- Modify: `internal/armada/client.go`
- Modify: `internal/armada/client_test.go`
- Modify: `internal/armada/start.go`
- Modify: `internal/armada/event.go`
- Modify: `internal/armada/groups_publisher.go`
- Modify: `internal/armada/doc.go`
- Modify: `configs/prod_configs_example.toml`
- Modify: `deploy/configs/prod_configs.example.toml`

- [x] Add failing config and publisher tests for the two new Topic fields and four-event routing table.
- [x] Run focused Go tests and confirm failure.
- [x] Replace `accounteventtopic` with `accountstateeventtopic` and `accountgroupsynceventtopic`.
- [x] Route with one kafka-go writer and reject unknown account event types.
- [x] Update startup wiring/logging and comments.
- [x] Run `gofmt`, focused tests, `go vet ./...`, `go build ./...`, and `go test ./...`（完整仓库既有失败已记录在 change record）。

### Task 6: Final code verification and diff audit

**Files:**
- Modify: `.harness/changes/2026-07-24-account-event-topic-isolation.md`

- [x] Run the focused Armada tests, then `mvn test` if time permits.
- [x] Re-run Web focused tests/lint/build.
- [x] Re-run Android mandatory verification commands.
- [x] Inspect `git diff` independently in all three repositories and verify no unrelated dirty file was modified.
- [x] Record exact verification results and remaining user-owned perf2 acceptance work in the change record.
