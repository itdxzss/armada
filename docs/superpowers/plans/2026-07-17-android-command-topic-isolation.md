# Android Command Topic Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Armada → Android Zhuan 的生命周期、营销消息和进群命令拆到三个独立 Kafka topic 与 consumer pool，使营销或进群积压不再拖慢账号上线、下线。

**Architecture:** Armada 继续使用现有 outbox、dispatcher 和 Kafka envelope，只把 Android 命令按命令族选择三个 topic；Web/master 路由保持不变。Zhuan 把旧的统一 reader group 改成三组独立 reader pool，每组只接受自己的 `commandType`，共享现有 Redis 状态、业务 executor 和结果 event writer。切换采用已批准的 dev-1 停机方式，不双写、不双读、不迁移旧 topic 消息。

**Tech Stack:** Java 17、Spring Boot 3.3.5、Spring Kafka、JUnit 5、AssertJ、Mockito；Go 1.25、segmentio/kafka-go、标准库 testing、zap；AWS MSK/Kafka、Docker Compose。

---

## Scope and execution constraints

- Contract source: `docs/superpowers/specs/2026-07-17-android-command-topic-isolation-design.md`.
- Producer repository: `/Users/daishuaishuai/IdeaProjects/armada`.
- Consumer repository: `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan`.
- `armada-protocol/protocol-layer` and the Web/master command path are outside this change.
- There is no database schema change and no event-topic change.
- The old `protocol.android.commands.v1` topic remains present but is no longer produced to or consumed from after cutover.
- At plan-writing time, both repositories have unrelated work in progress. In Armada, `ProtocolCommandOutboxServiceImpl.java`, `AndroidMessageSendBackend.java`, `application.yml`, and their tests already contain uncommitted user changes. Re-read every target file immediately before editing, make only hunk-sized patches, and never stage or commit pre-existing hunks without explicit approval.
- The repository-declared Armada `request-analysis` skill is absent from `.agents/skills`; this plan records the equivalent direct impact analysis instead.

## File map

### Armada producer

- Modify `armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolAndroidCommandProperties.java`: own the three Android topic values and fail startup on blank or duplicate values.
- Modify `armada-api/src/main/resources/application.yml`: replace the old single topic property with three environment-backed properties.
- Modify `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`: route Android lifecycle and group-join outbox rows.
- Modify `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java`: route Android message outbox rows.
- Modify `armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolAndroidCommandPropertiesTest.java`: bind/default/blank/duplicate contract tests.
- Modify `armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolKafkaConfigurationTest.java`: assert three registered defaults.
- Modify `armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`: lifecycle and group-join routing tests.
- Modify `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackendTest.java`: message routing test.
- Modify `armada-deploy/docker-compose.rds.yml`, `armada-deploy/.env.example`, `armada-deploy/prod/app/docker-compose.yml`, and `armada-deploy/prod/app/.env.example`: expose all three topic settings in test and offline production templates.
- Modify `armada-deploy/verify-config.mjs` and `armada-deploy/package-prod.test.sh`: guard the deployment templates.
- Create `.harness/changes/2026-07-17-android-command-topic-isolation.md`: persistent progress, verification, deployment, and rollback record.
- Create `docs/operations/android-command-topic-isolation-cutover.md`: dev-1 stop/create/configure/start/accept/rollback runbook.

### Android Zhuan consumer

- Modify `internal/configs/configs.go`: replace old TOML fields with nine family-specific fields.
- Modify `internal/configs/configs_test.go`: assert TOML decoding of all three families.
- Modify `internal/armada/options.go`: represent and validate three independent command channels.
- Modify `internal/armada/options_test.go`: defaults for shared settings plus enabled-channel validation.
- Modify `internal/armada/config.go`: map the nine TOML values into three command-channel option structs.
- Create `internal/armada/consumer_pool.go`: common three-pool reader construction, runner lifecycle, cleanup, and stop orchestration.
- Create `internal/armada/consumer_pool_test.go`: concurrency, partial-start cleanup, idempotent stop, and isolation regression tests.
- Modify `internal/armada/consumer.go`: attach `commandFamily`, `commandTopic`, and `consumerIndex` to safe consumer logs.
- Modify `internal/armada/consumer_test.go`: preserve publish-before-commit and verify offline message/group results commit.
- Modify `internal/armada/start.go`: create the three executors as today, construct three family handlers/specs, validate all reader configs, then start all pools.
- Modify `internal/armada/start_test.go`: replace unified-handler tests with family-specific acceptance/rejection and pool configuration tests.
- Modify `internal/armada/doc.go`: document three source topics without changing event topics.
- Modify `configs/prod_configs_example.toml` and `deploy/configs/prod_configs.example.toml`: publish the new required keys and remove old keys.

## Task 1: Establish a safe baseline and persistent change record

**Files:**
- Create: `armada/.harness/changes/2026-07-17-android-command-topic-isolation.md`
- Inspect only: all target files listed above

- [ ] **Step 1: Capture repository and overlapping-file state**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects
git -C armada status --short --branch
git -C whatsapp-server-feature-android-zhuan status --short --branch
git -C armada diff -- \
  armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java \
  armada-api/src/main/resources/application.yml \
  armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackendTest.java
git -C whatsapp-server-feature-android-zhuan diff -- \
  internal/configs internal/armada configs/prod_configs_example.toml deploy/configs/prod_configs.example.toml
```

Expected: the repository status matches the current user work; no command changes it. Save the terminal output in the change record under “执行前基线”, but do not paste credentials, broker addresses, message bodies, invite codes, or full phone numbers.

- [ ] **Step 2: Run focused pre-change tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='ProtocolAndroidCommandPropertiesTest,ProtocolKafkaConfigurationTest,ProtocolCommandOutboxServiceImplTest,AndroidMessageSendBackendTest' test

cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
go test ./internal/configs ./internal/armada -count=1
```

Expected: record the exact existing result before editing. A pre-existing failure is not waived; it must be identified as baseline and the new focused tests introduced below must still pass.

- [ ] **Step 3: Create the Harness change record**

Create the file with this complete initial content:

```markdown
# 变更记录：Android 命令 Topic 隔离

- 日期 / 分支 / worktree: 2026-07-17 / `1.0.1-snapshot` / 当前 checkout（执行前重新核对）
- 需求来源: 用户确认；`docs/superpowers/specs/2026-07-17-android-command-topic-isolation-design.md`
- 状态: 进行中

## 目标（一句话）

把 Android 生命周期、营销消息和进群命令拆成三个 Kafka topic 和三个独立 consumer pool，避免营销或进群积压拖慢批量上线、下线。

## 缺口拆解 / 任务清单

- [ ] Armada 三 topic 配置与启动校验
- [ ] Armada 四类 Android command type 精确路由
- [ ] Zhuan 三组 TOML 配置与启动校验
- [ ] Zhuan 三个独立 consumer pool 与错路由永久提交
- [ ] 离线营销 `ACCOUNT_OFFLINE`、离线进群 `ACCOUNT_NOT_ONLINE` 回归
- [ ] 本地 Java/Go/部署模板验证
- [ ] dev-1 停机切换与隔离验收

## 关键设计决策

- 停机切换，不双写、不双读、不迁移旧 topic 消息。
- 三个 topic 都以 `protocolAccountId` 为 key，每个默认 4 分区、Zhuan 每组 4 consumer。
- 营销发送不预查、不等待账号上线；账号实例不可用时回报 `ACCOUNT_OFFLINE` 后提交 source offset。
- 进群离线继续回报 `ACCOUNT_NOT_ONLINE`。
- event topic、outbox 状态机和 Web/master 路由不变。

## 执行前基线

- 仓库状态和聚焦测试真实输出记录在实施时追加；不得覆盖已有用户改动。

## 验证（evidence-before-done）

- 实施后追加命令、测试数量和真实结果。

## 部署

- 目标: dev-1 (`65.2.123.53`)，用户已确认允许停机和丢弃旧 topic 未消费命令。
- 实施后追加 commit、镜像、切换时间和验收结果。

## 遗留 / 跟进

- 旧 `protocol.android.commands.v1` 保留；后续删除必须单独批准。
```

- [ ] **Step 4: Verify only the new record was added**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git diff --check -- .harness/changes/2026-07-17-android-command-topic-isolation.md
git status --short -- .harness/changes/2026-07-17-android-command-topic-isolation.md
```

Expected: `git diff --check` is silent; status shows only the new change-record file for this step.

## Task 2: Split Zhuan TOML and runtime options into three required channels

**Files:**
- Modify: `whatsapp-server-feature-android-zhuan/internal/configs/configs.go`
- Modify: `whatsapp-server-feature-android-zhuan/internal/configs/configs_test.go`
- Modify: `whatsapp-server-feature-android-zhuan/internal/armada/options.go`
- Modify: `whatsapp-server-feature-android-zhuan/internal/armada/options_test.go`
- Modify: `whatsapp-server-feature-android-zhuan/internal/armada/config.go`

- [ ] **Step 1: Write failing TOML mapping tests**

Replace the old `commandtopic`, `consumergroup`, and `concurrency` fixture fields with:

```toml
lifecyclecommandtopic = "protocol.android.lifecycle.commands.v1"
lifecycleconsumergroup = "whatsapp-server-feature-android-armada-lifecycle"
lifecycleconcurrency = 4
messagecommandtopic = "protocol.android.message.commands.v1"
messageconsumergroup = "whatsapp-server-feature-android-armada-message"
messageconcurrency = 4
groupjoincommandtopic = "protocol.android.group-join.commands.v1"
groupjoinconsumergroup = "whatsapp-server-feature-android-armada-group-join"
groupjoinconcurrency = 4
```

Assert every decoded value explicitly:

```go
if got.Kafka.LifecycleCommandTopic != "protocol.android.lifecycle.commands.v1" ||
	got.Kafka.LifecycleConsumerGroup != "whatsapp-server-feature-android-armada-lifecycle" ||
	got.Kafka.LifecycleConcurrency != 4 {
	t.Fatalf("lifecycle Kafka config = %#v", got.Kafka)
}
if got.Kafka.MessageCommandTopic != "protocol.android.message.commands.v1" ||
	got.Kafka.MessageConsumerGroup != "whatsapp-server-feature-android-armada-message" ||
	got.Kafka.MessageConcurrency != 4 {
	t.Fatalf("message Kafka config = %#v", got.Kafka)
}
if got.Kafka.GroupJoinCommandTopic != "protocol.android.group-join.commands.v1" ||
	got.Kafka.GroupJoinConsumerGroup != "whatsapp-server-feature-android-armada-group-join" ||
	got.Kafka.GroupJoinConcurrency != 4 {
	t.Fatalf("group-join Kafka config = %#v", got.Kafka)
}
```

- [ ] **Step 2: Write failing options validation tests**

Add a reusable enabled fixture and table-driven invalid cases:

```go
func validEnabledOptions() Options {
	return Options{
		Enabled: true,
		Brokers: []string{"kafka.example:9092"},
		LifecycleCommands: CommandConsumerOptions{
			Topic: "protocol.android.lifecycle.commands.v1", ConsumerGroup: "zhuan-lifecycle", Concurrency: 4,
		},
		MessageCommands: CommandConsumerOptions{
			Topic: "protocol.android.message.commands.v1", ConsumerGroup: "zhuan-message", Concurrency: 4,
		},
		GroupJoinCommands: CommandConsumerOptions{
			Topic: "protocol.android.group-join.commands.v1", ConsumerGroup: "zhuan-group-join", Concurrency: 4,
		},
	}
}

func TestNormalizeOptionsRejectsInvalidCommandChannels(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*Options)
	}{
		{"blank lifecycle topic", func(o *Options) { o.LifecycleCommands.Topic = " " }},
		{"blank message group", func(o *Options) { o.MessageCommands.ConsumerGroup = " " }},
		{"zero group join concurrency", func(o *Options) { o.GroupJoinCommands.Concurrency = 0 }},
		{"negative lifecycle concurrency", func(o *Options) { o.LifecycleCommands.Concurrency = -1 }},
		{"duplicate topics", func(o *Options) { o.MessageCommands.Topic = o.LifecycleCommands.Topic }},
		{"duplicate groups", func(o *Options) { o.GroupJoinCommands.ConsumerGroup = o.MessageCommands.ConsumerGroup }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			options := validEnabledOptions()
			test.mutate(&options)
			if _, err := NormalizeOptions(options); err == nil {
				t.Fatal("NormalizeOptions() error = nil")
			}
		})
	}
}
```

Also change the disabled-options test to assert that `Options{}` remains a valid no-op without synthesizing old command topic/group defaults.

- [ ] **Step 3: Run the tests and confirm they fail for missing fields**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
go test ./internal/configs ./internal/armada -run 'Test(ConfigDecodesKafka|NormalizeOptions|OptionsFromConfig)' -count=1
```

Expected: FAIL to compile because `LifecycleCommandTopic`, `CommandConsumerOptions`, and the three option groups do not exist yet.

- [ ] **Step 4: Replace the old config fields**

In `internal/configs/configs.go`, make the Kafka command portion exactly:

```go
LifecycleCommandTopic  string `toml:"lifecyclecommandtopic"`
LifecycleConsumerGroup string `toml:"lifecycleconsumergroup"`
LifecycleConcurrency   int    `toml:"lifecycleconcurrency"`
MessageCommandTopic    string `toml:"messagecommandtopic"`
MessageConsumerGroup   string `toml:"messageconsumergroup"`
MessageConcurrency     int    `toml:"messageconcurrency"`
GroupJoinCommandTopic  string `toml:"groupjoincommandtopic"`
GroupJoinConsumerGroup string `toml:"groupjoinconsumergroup"`
GroupJoinConcurrency   int    `toml:"groupjoinconcurrency"`
```

Delete `CommandTopic`, `ConsumerGroup`, and `Concurrency`; do not keep aliases or compatibility reads.

- [ ] **Step 5: Implement grouped runtime options and strict enabled validation**

Add this type and replace the old three flat fields in `Options`:

```go
// CommandConsumerOptions 定义一个命令族独占的 Kafka topic、consumer group 和进程内并发。
type CommandConsumerOptions struct {
	Topic         string
	ConsumerGroup string
	Concurrency   int
}

// Options 中的三个命令族互不共享 reader、consumer group 或并发槽。
LifecycleCommands CommandConsumerOptions
MessageCommands   CommandConsumerOptions
GroupJoinCommands CommandConsumerOptions
```

Use these complete helpers from `NormalizeOptions` after shared string/duration normalization and before returning:

```go
func normalizeCommandConsumerOptions(family string, options CommandConsumerOptions) (CommandConsumerOptions, error) {
	options.Topic = strings.TrimSpace(options.Topic)
	options.ConsumerGroup = strings.TrimSpace(options.ConsumerGroup)
	if options.Topic == "" {
		return CommandConsumerOptions{}, fmt.Errorf("Armada %s command topic is empty", family)
	}
	if options.ConsumerGroup == "" {
		return CommandConsumerOptions{}, fmt.Errorf("Armada %s consumer group is empty", family)
	}
	if options.Concurrency < 1 {
		return CommandConsumerOptions{}, fmt.Errorf("Armada %s concurrency must be at least one", family)
	}
	return options, nil
}

func hasDuplicate(values ...string) bool {
	seen := make(map[string]struct{}, len(values))
	for _, value := range values {
		if _, exists := seen[value]; exists {
			return true
		}
		seen[value] = struct{}{}
	}
	return false
}
```

For `Enabled=true`, normalize lifecycle, message, and group-join channels and then reject duplicate topics and duplicate consumer groups. For `Enabled=false`, return after shared safe defaults without requiring channel values, so `Start(context.Background(), Options{})` remains a no-op. Remove `defaultCommandTopic`, `defaultConsumerGroup`, and `defaultConcurrency` because blank enabled config must fail instead of silently sharing a channel.

- [ ] **Step 6: Map TOML into the grouped options**

In `OptionsFromConfig`, replace the old mappings with:

```go
LifecycleCommands: CommandConsumerOptions{
	Topic: config.Kafka.LifecycleCommandTopic,
	ConsumerGroup: config.Kafka.LifecycleConsumerGroup,
	Concurrency: config.Kafka.LifecycleConcurrency,
},
MessageCommands: CommandConsumerOptions{
	Topic: config.Kafka.MessageCommandTopic,
	ConsumerGroup: config.Kafka.MessageConsumerGroup,
	Concurrency: config.Kafka.MessageConcurrency,
},
GroupJoinCommands: CommandConsumerOptions{
	Topic: config.Kafka.GroupJoinCommandTopic,
	ConsumerGroup: config.Kafka.GroupJoinConsumerGroup,
	Concurrency: config.Kafka.GroupJoinConcurrency,
},
```

- [ ] **Step 7: Format and run focused tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
gofmt -w internal/configs/configs.go internal/configs/configs_test.go internal/armada/options.go internal/armada/options_test.go internal/armada/config.go
go test ./internal/configs ./internal/armada -run 'Test(ConfigDecodesKafka|NormalizeOptions|OptionsFromConfig)' -count=1
```

Expected: PASS; invalid enabled configurations fail before any Kafka or Redis resource is created.

- [ ] **Step 8: Commit the isolated Zhuan config change**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
git add internal/configs/configs.go internal/configs/configs_test.go internal/armada/options.go internal/armada/options_test.go internal/armada/config.go
git diff --cached --check
git commit -m "feat: split android command channel configuration"
```

Expected: the commit contains only these five files; existing `internal/service/node/*` work remains unstaged.

## Task 3: Replace the unified handler with command-family handlers

**Files:**
- Modify: `whatsapp-server-feature-android-zhuan/internal/armada/start.go`
- Modify: `whatsapp-server-feature-android-zhuan/internal/armada/start_test.go`
- Modify: `whatsapp-server-feature-android-zhuan/internal/armada/consumer_test.go`

- [ ] **Step 1: Write wrong-route tests before changing handlers**

Add three table entries that feed a valid command from another family and assert `ErrPermanentCommand` plus zero executor side effects:

```go
func TestCommandFamilyHandlersRejectWrongRoutes(t *testing.T) {
	tests := []struct {
		name    string
		handler CommandHandler
		raw     []byte
	}{
		{"lifecycle rejects message", newLifecycleCommandHandler(&LifecycleExecutor{}), []byte(messageCommandJSON("TEXT", `"text":"hello"`))},
		{"message rejects lifecycle", newMessageCommandHandler(&MessageCommandExecutor{}), mustJSON(t, validOnlineEnvelope())},
		{"group join rejects message", newGroupJoinCommandHandler(&GroupJoinCommandExecutor{}), []byte(messageCommandJSON("TEXT", `"text":"hello"`))},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := test.handler(context.Background(), test.raw)
			if !errors.Is(err, ErrPermanentCommand) || !errors.Is(err, ErrInvalidCommand) {
				t.Fatalf("handler() error = %v", err)
			}
		})
	}
}
```

Keep separate positive tests for online, offline, message, and group join. Rename the existing `TestUnifiedCommandHandler*` tests to the family they now exercise.

- [ ] **Step 2: Run handler tests and verify the new constructors are missing**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
go test ./internal/armada -run 'Test(CommandFamilyHandlers|LifecycleCommandHandler|MessageCommandHandler|GroupJoinCommandHandler)' -count=1
```

Expected: FAIL to compile because `newMessageCommandHandler` and `newGroupJoinCommandHandler` do not exist.

- [ ] **Step 3: Implement one reusable family guard and three explicit constructors**

Replace `newCommandHandler` with:

```go
// newCommandFamilyHandler 在执行任何协议副作用前校验命令是否属于当前 topic 的命令族。
func newCommandFamilyHandler(accepted map[string]struct{}, handler CommandHandler) CommandHandler {
	return func(ctx context.Context, raw []byte) error {
		envelope, err := ParseRawProtocolCommand(raw)
		if err != nil {
			zap.L().Warn("Armada Zhuan command rejected", zap.String("errorClass", "permanent_validation"))
			return PermanentCommand(err)
		}
		if _, ok := accepted[envelope.CommandType]; !ok {
			return PermanentCommand(invalidCommand("commandType", "does not belong to this command family"))
		}
		return handler(ctx, raw)
	}
}

func newLifecycleCommandHandler(executor *LifecycleExecutor) CommandHandler {
	return newCommandFamilyHandler(map[string]struct{}{
		CommandTypeAccountOnlineRequested:  {},
		CommandTypeAccountOfflineRequested: {},
	}, func(ctx context.Context, raw []byte) error {
		return handleLifecycleCommand(ctx, executor, raw)
	})
}

func newMessageCommandHandler(executor *MessageCommandExecutor) CommandHandler {
	return newCommandFamilyHandler(map[string]struct{}{
		CommandTypeMessageSendRequested: {},
	}, func(ctx context.Context, raw []byte) error {
		return handleMessageCommand(ctx, executor, raw)
	})
}

func newGroupJoinCommandHandler(executor *GroupJoinCommandExecutor) CommandHandler {
	return newCommandFamilyHandler(map[string]struct{}{
		CommandTypeGroupJoinRequested: {},
	}, func(ctx context.Context, raw []byte) error {
		return handleGroupJoinCommand(ctx, executor, raw)
	})
}
```

Delete the old executor-switching `newCommandHandler`; no compatibility wrapper remains.

- [ ] **Step 4: Update consumer/executor tests to use the correct handler**

Use `newMessageCommandHandler` in message publish-before-commit tests, `newGroupJoinCommandHandler` in group-join replay tests, and `newLifecycleCommandHandler` in lifecycle tests. Do not create a handler that can execute multiple command families.

- [ ] **Step 5: Run handler and commit-order tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
gofmt -w internal/armada/start.go internal/armada/start_test.go internal/armada/consumer_test.go
go test ./internal/armada -run 'Test(CommandFamilyHandlers|LifecycleCommandHandler|MessageCommandHandler|GroupJoinCommandHandler|CommandConsumer)' -count=1
```

Expected: PASS; wrong-family commands are permanent and no protocol sender is called.

- [ ] **Step 6: Commit the family-handler change**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
git add internal/armada/start.go internal/armada/start_test.go internal/armada/consumer_test.go
git diff --cached --check
git commit -m "refactor: isolate android command family handlers"
```

Expected: only the three handler/test files are committed.

## Task 4: Start three independent Zhuan consumer pools

**Files:**
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/consumer_pool.go`
- Create: `whatsapp-server-feature-android-zhuan/internal/armada/consumer_pool_test.go`
- Modify: `whatsapp-server-feature-android-zhuan/internal/armada/consumer.go`
- Modify: `whatsapp-server-feature-android-zhuan/internal/armada/consumer_test.go`
- Modify: `whatsapp-server-feature-android-zhuan/internal/armada/start.go`
- Modify: `whatsapp-server-feature-android-zhuan/internal/armada/start_test.go`

- [ ] **Step 1: Write pool construction and cleanup tests**

Define three specs at concurrency 4, use a recording factory, and assert exactly 12 runners with indexes `0..3` inside each family. Add a factory failure on the second family and assert every previously constructed runner closes exactly once. Add an idempotent stop assertion that all runners close once even when `stop` is called twice.

The spec fixture must be:

```go
func testCommandPoolSpecs(handler CommandHandler) []commandPoolSpec {
	return []commandPoolSpec{
		{Family: commandFamilyLifecycle, Topic: "lifecycle", ConsumerGroup: "lifecycle-group", Concurrency: 4, Handler: handler},
		{Family: commandFamilyMessage, Topic: "message", ConsumerGroup: "message-group", Concurrency: 4, Handler: handler},
		{Family: commandFamilyGroupJoin, Topic: "group-join", ConsumerGroup: "group-join-group", Concurrency: 4, Handler: handler},
	}
}
```

- [ ] **Step 2: Write the isolation regression test**

Use three one-message readers. Make the message handler block on a channel before returning, let lifecycle and group-join handlers return immediately, and start all three pools. Assert lifecycle and group-join each commit while the message reader has not committed; release the message handler and then assert its commit. The test must fail if all three families share one runner slot.

```go
select {
case <-messageHandlerStarted:
case <-time.After(time.Second):
	t.Fatal("message handler did not start")
}
waitForCommit(t, lifecycleReader, 1)
waitForCommit(t, groupJoinReader, 1)
if messageReader.commitCount() != 0 {
	t.Fatalf("message commit count = %d before release", messageReader.commitCount())
}
close(releaseMessageHandler)
waitForCommit(t, messageReader, 1)
```

- [ ] **Step 3: Run the pool tests and verify they fail because pool types are absent**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
go test ./internal/armada -run 'Test(CommandPools|CommandPoolIsolation)' -count=1
```

Expected: FAIL to compile because `commandPoolSpec` and `startCommandPools` do not exist.

- [ ] **Step 4: Create the shared pool implementation**

Implement `consumer_pool.go` around these exact types and lifecycle rules:

```go
package armada

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/segmentio/kafka-go"
	"go.uber.org/zap"
)

type commandFamily string

const (
	commandFamilyLifecycle commandFamily = "lifecycle"
	commandFamilyMessage   commandFamily = "message"
	commandFamilyGroupJoin commandFamily = "group-join"
)

type commandPoolSpec struct {
	Family        commandFamily
	Topic         string
	ConsumerGroup string
	Concurrency   int
	Handler       CommandHandler
}

type ConsumerRunner interface {
	Run(context.Context) error
	Close() error
}

type ConsumerFactory func(commandPoolSpec, int) (ConsumerRunner, error)

func buildKafkaReaderConfig(options Options, spec commandPoolSpec) kafka.ReaderConfig {
	return kafka.ReaderConfig{
		Brokers:                append([]string(nil), options.Brokers...),
		Topic:                  spec.Topic,
		GroupID:                spec.ConsumerGroup,
		CommitInterval:         0,
		Dialer:                 newKafkaDialer(options.SecurityProtocol),
		WatchPartitionChanges:  true,
		PartitionWatchInterval: 5 * time.Second,
	}
}
```

Move the existing `buildConsumerRunners` and runner wait/stop logic out of `start.go`. Change the factory signature to receive the spec. `startCommandPools` must construct all runners before starting any goroutine, close all already-created runners if a later family fails construction, create one cancellable run context for all pools, include `commandFamily`, `commandTopic`, and `consumerIndex` in unexpected-runner logs, and return a `sync.Once`-guarded stop function that performs `cancel → Close every reader → wait`.

Use this runner-construction and cleanup code without changing its order:

```go
func buildConsumerRunners(spec commandPoolSpec, factory ConsumerFactory) ([]ConsumerRunner, error) {
	if spec.Concurrency < 1 {
		return nil, fmt.Errorf("build Armada %s consumers: concurrency must be at least one", spec.Family)
	}
	if spec.Handler == nil {
		return nil, fmt.Errorf("build Armada %s consumers: handler is nil", spec.Family)
	}
	if factory == nil {
		return nil, fmt.Errorf("build Armada %s consumers: factory is nil", spec.Family)
	}
	runners := make([]ConsumerRunner, 0, spec.Concurrency)
	for index := 0; index < spec.Concurrency; index++ {
		runner, err := factory(spec, index)
		if err != nil || runner == nil {
			buildErr := err
			if buildErr == nil {
				buildErr = fmt.Errorf("factory returned nil")
			}
			return nil, errors.Join(
				fmt.Errorf("build Armada %s consumer %d: %w", spec.Family, index, buildErr),
				closeRunners(runners),
			)
		}
		runners = append(runners, runner)
	}
	return runners, nil
}

func closeRunners(runners []ConsumerRunner) error {
	errs := make([]error, 0, len(runners))
	for _, runner := range runners {
		errs = append(errs, runner.Close())
	}
	return errors.Join(errs...)
}

func startCommandPools(parent context.Context, specs []commandPoolSpec, factory ConsumerFactory) (StopFunc, error) {
	if factory == nil {
		return nil, fmt.Errorf("start Armada command pools: factory is nil")
	}
	allRunners := make([]ConsumerRunner, 0)
	type indexedRunner struct {
		spec   commandPoolSpec
		index  int
		runner ConsumerRunner
	}
	indexed := make([]indexedRunner, 0)
	for _, spec := range specs {
		runners, err := buildConsumerRunners(spec, factory)
		if err != nil {
			return nil, errors.Join(err, closeRunners(allRunners))
		}
		for index, runner := range runners {
			allRunners = append(allRunners, runner)
			indexed = append(indexed, indexedRunner{spec: spec, index: index, runner: runner})
		}
	}
	if parent == nil {
		parent = context.Background()
	}
	runContext, cancel := context.WithCancel(parent)
	var group sync.WaitGroup
	group.Add(len(indexed))
	for _, item := range indexed {
		item := item
		go func() {
			defer group.Done()
			if err := item.runner.Run(runContext); err != nil && runContext.Err() == nil {
				zap.L().Warn("Armada Zhuan consumer runner stopped unexpectedly",
					zap.String("commandFamily", string(item.spec.Family)),
					zap.String("commandTopic", item.spec.Topic),
					zap.Int("consumerIndex", item.index),
					zap.String("errorClass", lifecycleErrorClass(err)))
			}
		}()
	}
	done := make(chan struct{})
	go func() {
		group.Wait()
		close(done)
	}()
	var stopOnce sync.Once
	var stopErr error
	return func(stopContext context.Context) error {
		stopOnce.Do(func() {
			cancel()
			stopErr = closeRunners(allRunners)
			if stopContext == nil {
				stopContext = context.Background()
			}
			select {
			case <-done:
			case <-stopContext.Done():
				stopErr = errors.Join(stopErr, stopContext.Err())
			}
		})
		return stopErr
	}, nil
}
```

`buildConsumerRunners` closes only runners created for the current spec; `startCommandPools` closes runners from earlier specs.

- [ ] **Step 5: Add safe command-family metadata to consumer logs**

Extend `CommandConsumer`:

```go
CommandFamily commandFamily
CommandTopic  string
ConsumerIndex int
```

Add the three fields to permanent failure, handler retry, commit retry, and fetch-loop retry logs. Do not log the raw payload, key, credentials, invite code, message body, base64, or full phone.

- [ ] **Step 6: Refactor Start to create and validate three specs**

After constructing the existing lifecycle, message, and group-join executors, build:

```go
specs := []commandPoolSpec{
	{
		Family: commandFamilyLifecycle, Topic: normalized.LifecycleCommands.Topic,
		ConsumerGroup: normalized.LifecycleCommands.ConsumerGroup,
		Concurrency: normalized.LifecycleCommands.Concurrency,
		Handler: newLifecycleCommandHandler(executor),
	},
	{
		Family: commandFamilyMessage, Topic: normalized.MessageCommands.Topic,
		ConsumerGroup: normalized.MessageCommands.ConsumerGroup,
		Concurrency: normalized.MessageCommands.Concurrency,
		Handler: newMessageCommandHandler(messageExecutor),
	},
	{
		Family: commandFamilyGroupJoin, Topic: normalized.GroupJoinCommands.Topic,
		ConsumerGroup: normalized.GroupJoinCommands.ConsumerGroup,
		Concurrency: normalized.GroupJoinCommands.Concurrency,
		Handler: newGroupJoinCommandHandler(groupJoinExecutor),
	},
}
```

Validate every `buildKafkaReaderConfig(normalized, spec)` before constructing a reader. Then call:

```go
consumerStop, err := startCommandPools(adapterContext, specs, func(spec commandPoolSpec, index int) (ConsumerRunner, error) {
	config := buildKafkaReaderConfig(normalized, spec)
	return &CommandConsumer{
		Reader: NewKafkaCommandReader(config), Handler: spec.Handler, RetryDelay: time.Second,
		CommandFamily: spec.Family, CommandTopic: spec.Topic, ConsumerIndex: index,
	}, nil
})
```

On validation or pool-construction failure, preserve the existing reverse cleanup of callback observer, group runtime, adapter context, group event writer, message event writer, and account event writer. On normal stop, preserve the approved order: unregister observers and stop group runtime, cancel adapter context, stop all consumer pools, then close all three event writers.

- [ ] **Step 7: Add explicit offline-result commit regression tests**

Change the failed-message consumer test to return and assert `ACCOUNT_OFFLINE`, then add a group-join equivalent returning `ACCOUNT_NOT_ONLINE`. Each test must assert one result event and one source offset commit:

```go
if reader.commitCalls != 1 || len(events.events) != 1 ||
	events.events[0].Data.ReasonCode != "ACCOUNT_OFFLINE" {
	t.Fatalf("commit/events = %d/%#v", reader.commitCalls, events.events)
}
```

```go
if reader.commitCalls != 1 || len(events.events) != 1 ||
	events.events[0].Data.ReasonCode != "ACCOUNT_NOT_ONLINE" {
	t.Fatalf("commit/events = %d/%#v", reader.commitCalls, events.events)
}
```

- [ ] **Step 8: Format and run focused plus race tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
gofmt -w internal/armada/consumer_pool.go internal/armada/consumer_pool_test.go internal/armada/consumer.go internal/armada/consumer_test.go internal/armada/start.go internal/armada/start_test.go
go test ./internal/armada -run 'Test(CommandPool|CommandConsumer|BuildKafkaReaderConfig|Start|CommandFamily)' -count=1
go test -race ./internal/armada -run 'Test(CommandPool|CommandConsumerRunStops|StartCommandPools)' -count=1
```

Expected: PASS; race detector reports no races; isolation test proves a blocked message handler does not prevent lifecycle or group-join commits.

- [ ] **Step 9: Commit the three-pool implementation**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
git add internal/armada/consumer_pool.go internal/armada/consumer_pool_test.go internal/armada/consumer.go internal/armada/consumer_test.go internal/armada/start.go internal/armada/start_test.go
git diff --cached --check
git commit -m "feat: run independent android command consumer pools"
```

Expected: only pool, consumer, startup, and test files are committed.

## Task 5: Add three validated Armada topic properties and deployment templates

**Files:**
- Modify: `armada/armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolAndroidCommandProperties.java`
- Modify: `armada/armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolAndroidCommandPropertiesTest.java`
- Modify: `armada/armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolKafkaConfigurationTest.java`
- Modify: `armada/armada-api/src/main/resources/application.yml`
- Modify: `armada/armada-deploy/docker-compose.rds.yml`
- Modify: `armada/armada-deploy/.env.example`
- Modify: `armada/armada-deploy/prod/app/docker-compose.yml`
- Modify: `armada/armada-deploy/prod/app/.env.example`
- Modify: `armada/armada-deploy/verify-config.mjs`
- Modify: `armada/armada-deploy/package-prod.test.sh`

- [ ] **Step 1: Write failing property binding/default/validation tests**

Bind all three explicit values and assert all getters. Add application.yml default assertions. Add two context-failure tests: one blank topic and one duplicate pair. Add an old-property regression proving `armada.protocol.kafka.android-commands.topic` does not override any new property.

```java
contextRunner
        .withPropertyValues(
                "armada.protocol.kafka.android-commands.lifecycle-topic=lifecycle.test",
                "armada.protocol.kafka.android-commands.message-topic=message.test",
                "armada.protocol.kafka.android-commands.group-join-topic=group-join.test")
        .run(context -> {
            ProtocolAndroidCommandProperties properties =
                    context.getBean(ProtocolAndroidCommandProperties.class);
            assertThat(properties.getLifecycleTopic()).isEqualTo("lifecycle.test");
            assertThat(properties.getMessageTopic()).isEqualTo("message.test");
            assertThat(properties.getGroupJoinTopic()).isEqualTo("group-join.test");
        });
```

```java
contextRunner
        .withPropertyValues("armada.protocol.kafka.android-commands.message-topic= ")
        .run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessageContaining("Android 命令 topic 不能为空");
        });
```

- [ ] **Step 2: Run the property tests and verify getter failures**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='ProtocolAndroidCommandPropertiesTest,ProtocolKafkaConfigurationTest' test
```

Expected: FAIL to compile because the three getters and constants do not exist.

- [ ] **Step 3: Implement the three-property bean with startup validation**

Replace the old single value with these constants and fields, and implement `org.springframework.beans.factory.InitializingBean`:

```java
public static final String DEFAULT_LIFECYCLE_TOPIC = "protocol.android.lifecycle.commands.v1";
public static final String DEFAULT_MESSAGE_TOPIC = "protocol.android.message.commands.v1";
public static final String DEFAULT_GROUP_JOIN_TOPIC = "protocol.android.group-join.commands.v1";

private String lifecycleTopic = DEFAULT_LIFECYCLE_TOPIC;
private String messageTopic = DEFAULT_MESSAGE_TOPIC;
private String groupJoinTopic = DEFAULT_GROUP_JOIN_TOPIC;

@Override
public void afterPropertiesSet() {
    if (isBlank(lifecycleTopic) || isBlank(messageTopic) || isBlank(groupJoinTopic)) {
        throw new IllegalArgumentException("Android 命令 topic 不能为空");
    }
    lifecycleTopic = lifecycleTopic.trim();
    messageTopic = messageTopic.trim();
    groupJoinTopic = groupJoinTopic.trim();
    if (lifecycleTopic.equals(messageTopic)
            || lifecycleTopic.equals(groupJoinTopic)
            || messageTopic.equals(groupJoinTopic)) {
        throw new IllegalArgumentException("Android 命令 topic 必须互不重复");
    }
}

private static boolean isBlank(String value) {
    return value == null || value.isBlank();
}
```

Provide Javadoc-backed getter/setter pairs for all three fields. Delete `DEFAULT_TOPIC`, `topic`, `getTopic`, and `setTopic`; no compatibility accessor remains.

- [ ] **Step 4: Replace application.yml and Compose/env template keys**

Use exactly:

```yaml
android-commands:
  lifecycle-topic: ${PROTOCOL_ANDROID_LIFECYCLE_COMMANDS_TOPIC:protocol.android.lifecycle.commands.v1}
  message-topic: ${PROTOCOL_ANDROID_MESSAGE_COMMANDS_TOPIC:protocol.android.message.commands.v1}
  group-join-topic: ${PROTOCOL_ANDROID_GROUP_JOIN_COMMANDS_TOPIC:protocol.android.group-join.commands.v1}
```

Expose the same three environment names and defaults in both Compose files and both `.env.example` files. Do not leave `PROTOCOL_ANDROID_COMMANDS_TOPIC` in any active template.

- [ ] **Step 5: Extend deployment-template guards**

In `verify-config.mjs`, add `expectIncludes` checks for all three Compose mappings and all three test `.env.example` values. In `package-prod.test.sh`, add `assert_file_contains` checks for all three production Compose mappings and all three production `.env.example` values.

- [ ] **Step 6: Run property and deployment-template tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='ProtocolAndroidCommandPropertiesTest,ProtocolKafkaConfigurationTest' test

cd /Users/daishuaishuai/IdeaProjects/armada
node armada-deploy/verify-config.mjs
bash armada-deploy/package-prod.test.sh
```

Expected: Java tests PASS; Node prints `armada deploy config verification passed`; package test prints `OK package-prod offline deployment tests passed`.

- [ ] **Step 7: Review overlap before committing**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git diff --check -- \
  armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolAndroidCommandProperties.java \
  armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolAndroidCommandPropertiesTest.java \
  armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolKafkaConfigurationTest.java \
  armada-api/src/main/resources/application.yml \
  armada-deploy/docker-compose.rds.yml armada-deploy/.env.example \
  armada-deploy/prod/app/docker-compose.yml armada-deploy/prod/app/.env.example \
  armada-deploy/verify-config.mjs armada-deploy/package-prod.test.sh
git diff -- armada-api/src/main/resources/application.yml
```

Expected: no whitespace error. If `application.yml` still includes pre-existing user hunks, leave the entire Armada task unstaged. If the target files are clean relative to the task baseline, commit with:

```bash
git add armada-api/src/main/java/com/armada/platform/kafka/config/ProtocolAndroidCommandProperties.java \
  armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolAndroidCommandPropertiesTest.java \
  armada-api/src/test/java/com/armada/platform/kafka/config/ProtocolKafkaConfigurationTest.java \
  armada-api/src/main/resources/application.yml \
  armada-deploy/docker-compose.rds.yml armada-deploy/.env.example \
  armada-deploy/prod/app/docker-compose.yml armada-deploy/prod/app/.env.example \
  armada-deploy/verify-config.mjs armada-deploy/package-prod.test.sh
git diff --cached --check
git commit -m "feat: configure isolated android command topics"
```

## Task 6: Route each Armada Android command family to its topic

**Files:**
- Modify: `armada/armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`
- Modify: `armada/armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java`
- Modify: `armada/armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java`
- Modify: `armada/armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`
- Modify: `armada/armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackendTest.java`
- Modify: `armada/armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java`

- [ ] **Step 1: Change tests to use three distinct Android topics**

Create the properties fixture once per test helper:

```java
ProtocolAndroidCommandProperties androidProperties = new ProtocolAndroidCommandProperties();
androidProperties.setLifecycleTopic("protocol.android.lifecycle.commands.test");
androidProperties.setMessageTopic("protocol.android.message.commands.test");
androidProperties.setGroupJoinTopic("protocol.android.group-join.commands.test");
```

Assert:

- Android online and offline rows use `protocol.android.lifecycle.commands.test`.
- Android group-join rows use `protocol.android.group-join.commands.test`.
- `AndroidMessageSendBackend` supplies `protocol.android.message.commands.test` to `ProtocolMessageOutboxCommand`.
- Web online, offline, message, and group-join expectations remain on their current account/master topics.

- [ ] **Step 2: Run routing tests and verify they fail against old getters**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='ProtocolCommandOutboxServiceImplTest,AndroidMessageSendBackendTest,ProtocolConfigurationTest' test
```

Expected: FAIL because production code still calls `getTopic()` and routes all Android commands to the old shared topic.

- [ ] **Step 3: Apply the minimal routing changes**

Make only these production substitutions:

```java
// ProtocolCommandOutboxServiceImpl
row.setKafkaTopic(command.protocolBackend() == ProtocolBackend.ANDROID
        ? androidCommandProperties.getGroupJoinTopic() : masterCommandProperties.getTopic());

private String onlineCommandTopic(ProtocolBackend protocolBackend) {
    return protocolBackend == ProtocolBackend.ANDROID
            ? androidCommandProperties.getLifecycleTopic()
            : accountCommandProperties.getTopic();
}

private String offlineCommandTopic(ProtocolBackend protocolBackend) {
    return protocolBackend == ProtocolBackend.ANDROID
            ? androidCommandProperties.getLifecycleTopic()
            : masterCommandProperties.getTopic();
}
```

```java
// AndroidMessageSendBackend.toOutboxCommand
return new ProtocolMessageOutboxCommand(
        command,
        ProtocolBackend.ANDROID,
        properties.getMessageTopic(),
        command.account().protocolAccountId(),
        payload);
```

Update nearby Javadoc from “Android topic” to the exact command-family topic; do not change payloads, keys, outbox transaction boundaries, dispatcher behavior, result events, or account-online checks.

- [ ] **Step 4: Run routing tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='ProtocolCommandOutboxServiceImplTest,AndroidMessageSendBackendTest,ProtocolConfigurationTest,ProtocolAndroidCommandPropertiesTest' test
```

Expected: PASS; Android routes are lifecycle/message/group-join and all Web expectations remain unchanged.

- [ ] **Step 5: Prove active producer code no longer references the old topic**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
rg -n 'protocol\.android\.commands\.v1|PROTOCOL_ANDROID_COMMANDS_TOPIC|getTopic\(\)' \
  armada-api/src/main armada-deploy
```

Expected: no old Android topic or old environment key in active source/templates; unrelated `getTopic()` calls for Web/account/event properties may remain and must be manually distinguished.

- [ ] **Step 6: Review overlapping hunks and commit only when ownership is clear**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git diff --check -- \
  armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java \
  armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java \
  armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackendTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java
```

If any file still contains pre-existing user work, do not stage it without approval. If all target diffs are owned by this feature, commit with:

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java \
  armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java \
  armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackendTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java
git diff --cached --check
git commit -m "feat: route android commands by command family"
```

## Task 7: Publish Zhuan sample configuration and documentation

**Files:**
- Modify: `whatsapp-server-feature-android-zhuan/configs/prod_configs_example.toml`
- Modify: `whatsapp-server-feature-android-zhuan/deploy/configs/prod_configs.example.toml`
- Modify: `whatsapp-server-feature-android-zhuan/internal/armada/doc.go`

- [ ] **Step 1: Replace the old sample keys in both TOML files**

Use this block in both examples:

```toml
# 三个命令族使用独立 topic、consumer group 和消费槽；topic 分区数至少与对应并发一致。
lifecyclecommandtopic = "protocol.android.lifecycle.commands.v1"
lifecycleconsumergroup = "whatsapp-server-feature-android-armada-lifecycle"
lifecycleconcurrency = 4

messagecommandtopic = "protocol.android.message.commands.v1"
messageconsumergroup = "whatsapp-server-feature-android-armada-message"
messageconcurrency = 4

groupjoincommandtopic = "protocol.android.group-join.commands.v1"
groupjoinconsumergroup = "whatsapp-server-feature-android-armada-group-join"
groupjoinconcurrency = 4
```

Delete `commandtopic`, `consumergroup`, and `concurrency`. Keep event topics, timeouts, worker ID, TTL, security protocol, and the single `[kafka].enabled` switch unchanged.

- [ ] **Step 2: Update package documentation**

Document that lifecycle consumes `protocol.android.lifecycle.commands.v1`, message consumes `protocol.android.message.commands.v1`, and group join consumes `protocol.android.group-join.commands.v1`; all result events remain on their existing topics.

- [ ] **Step 3: Assert old keys are gone and new keys decode**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
rg -n '^(commandtopic|consumergroup|concurrency)[[:space:]]*=' configs deploy/configs
go test ./internal/configs ./internal/armada -count=1
go vet ./internal/configs ./internal/armada
```

Expected: `rg` prints nothing; tests and vet PASS.

- [ ] **Step 4: Commit sample and documentation changes**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
git add configs/prod_configs_example.toml deploy/configs/prod_configs.example.toml internal/armada/doc.go
git diff --cached --check
git commit -m "docs: configure isolated android command consumers"
```

Expected: only the two examples and package documentation are committed.

## Task 8: Create the dev-1 cutover and rollback runbook

**Files:**
- Create: `armada/docs/operations/android-command-topic-isolation-cutover.md`
- Modify: `armada/.harness/changes/2026-07-17-android-command-topic-isolation.md`

- [ ] **Step 1: Write the runbook with the confirmed environment facts**

The runbook must record:

- Target host: `ubuntu@ec2-65-2-123-53.ap-south-1.compute.amazonaws.com`.
- Armada Compose: `/home/app/armada-deploy/docker-compose.rds.yml`, service `backend`, container `armada-backend`.
- Zhuan Compose: `/home/app/whatsapp-android-zhuan-deploy/src/deploy/docker-compose.yml`, service/container `whatsapp-android-zhuan`.
- Zhuan protected config: `/home/app/whatsapp-android-zhuan-deploy/src/deploy/configs/prod_configs.toml`.
- Kafka security protocol: `SSL`; available local image: `apache/kafka:3.8.0`.
- User authorization: dev-1 may be stopped; unconsumed old-topic commands may be dropped; old topic must not be deleted.

- [ ] **Step 2: Add exact stop and topic-create commands**

Use the private key only by local path; never copy it to the server or repository.

```bash
ssh -T -i '/Users/daishuaishuai/IdeaProjects/测试pem/dev-1.pem' \
  ubuntu@ec2-65-2-123-53.ap-south-1.compute.amazonaws.com \
  'cd /home/app/armada-deploy && sudo docker compose -f docker-compose.rds.yml stop backend && cd /home/app/whatsapp-android-zhuan-deploy/src/deploy && sudo docker compose stop whatsapp-android-zhuan'
```

On dev-1, derive the broker list without printing it and create a temporary SSL client config:

```bash
ARMADA_KAFKA_BROKERS="$(sudo docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' armada-backend | sed -n 's/^KAFKA_BOOTSTRAP_SERVERS=//p')"
ARMADA_KAFKA_CLIENT_CONFIG="$(mktemp)"
printf 'security.protocol=SSL\n' >"${ARMADA_KAFKA_CLIENT_CONFIG}"
```

Create the topics individually, leaving replication factor to the MSK broker default:

```bash
sudo docker run --rm --network host \
  -v "${ARMADA_KAFKA_CLIENT_CONFIG}:/tmp/client.properties:ro" \
  apache/kafka:3.8.0 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server "${ARMADA_KAFKA_BROKERS}" --command-config /tmp/client.properties \
  --create --if-not-exists --topic protocol.android.lifecycle.commands.v1 --partitions 4

sudo docker run --rm --network host \
  -v "${ARMADA_KAFKA_CLIENT_CONFIG}:/tmp/client.properties:ro" \
  apache/kafka:3.8.0 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server "${ARMADA_KAFKA_BROKERS}" --command-config /tmp/client.properties \
  --create --if-not-exists --topic protocol.android.message.commands.v1 --partitions 4

sudo docker run --rm --network host \
  -v "${ARMADA_KAFKA_CLIENT_CONFIG}:/tmp/client.properties:ro" \
  apache/kafka:3.8.0 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server "${ARMADA_KAFKA_BROKERS}" --command-config /tmp/client.properties \
  --create --if-not-exists --topic protocol.android.group-join.commands.v1 --partitions 4
```

Describe all three and verify `PartitionCount: 4`. Keep the temporary SSL client file until the consumer-group verification in Step 5 completes.

- [ ] **Step 3: Add exact protected-config migration rules**

Before editing the ignored runtime TOML, create a fixed, non-overwriting backup in the same protected directory and replace only the three old keys:

```bash
sudo cp -p -n \
  /home/app/whatsapp-android-zhuan-deploy/src/deploy/configs/prod_configs.toml \
  /home/app/whatsapp-android-zhuan-deploy/src/deploy/configs/prod_configs.toml.before-topic-split-20260717

sudo perl -0pi -e '
s/^commandtopic\s*=.*$/lifecyclecommandtopic = "protocol.android.lifecycle.commands.v1"\nlifecycleconsumergroup = "whatsapp-server-feature-android-armada-lifecycle"\nlifecycleconcurrency = 4\n\nmessagecommandtopic = "protocol.android.message.commands.v1"\nmessageconsumergroup = "whatsapp-server-feature-android-armada-message"\nmessageconcurrency = 4\n\ngroupjoincommandtopic = "protocol.android.group-join.commands.v1"\ngroupjoinconsumergroup = "whatsapp-server-feature-android-armada-group-join"\ngroupjoinconcurrency = 4/m;
s/^consumergroup\s*=.*\n//m;
s/^concurrency\s*=.*\n//m;
' /home/app/whatsapp-android-zhuan-deploy/src/deploy/configs/prod_configs.toml
```

Verify with:

```bash
grep -E '^(lifecycle|message|groupjoin)(commandtopic|consumergroup|concurrency)[[:space:]]*=' \
  /home/app/whatsapp-android-zhuan-deploy/src/deploy/configs/prod_configs.toml
grep -E '^(commandtopic|consumergroup|concurrency)[[:space:]]*=' \
  /home/app/whatsapp-android-zhuan-deploy/src/deploy/configs/prod_configs.toml
```

Expected: the first command prints nine non-secret lines; the second prints nothing. Do not display brokers, Redis/MySQL credentials, proxy credentials, message payloads, or phone data.

- [ ] **Step 4: Add deployment order and health checks**

From the local Armada repository, deploy Zhuan first and backend second:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
./armada-deploy/deploy-test.sh --zhuan -y
./armada-deploy/deploy-test.sh --be -y
```

Verify containers and safe startup fields:

```bash
ssh -T -i '/Users/daishuaishuai/IdeaProjects/测试pem/dev-1.pem' \
  ubuntu@ec2-65-2-123-53.ap-south-1.compute.amazonaws.com \
  'docker ps --filter name=whatsapp-android-zhuan --filter name=armada-backend --format "{{.Names}}|{{.Status}}"; docker logs --since 10m whatsapp-android-zhuan 2>&1 | grep -E "commandFamily|commandTopic|consumerGroup|adapter started" | tail -n 80'
```

Expected: both containers are up/healthy; Zhuan reports lifecycle, message, and group-join pools with concurrency 4 and no legacy command topic.

- [ ] **Step 5: Add consumer-group verification**

Using the same broker variable and SSL config, run:

```bash
for ARMADA_COMMAND_GROUP in \
  whatsapp-server-feature-android-armada-lifecycle \
  whatsapp-server-feature-android-armada-message \
  whatsapp-server-feature-android-armada-group-join
do
  sudo docker run --rm --network host \
    -v "${ARMADA_KAFKA_CLIENT_CONFIG}:/tmp/client.properties:ro" \
    apache/kafka:3.8.0 /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server "${ARMADA_KAFKA_BROKERS}" --command-config /tmp/client.properties \
    --describe --group "${ARMADA_COMMAND_GROUP}"
done
```

Run it for these exact groups:

```text
whatsapp-server-feature-android-armada-lifecycle
whatsapp-server-feature-android-armada-message
whatsapp-server-feature-android-armada-group-join
```

Expected: each group is assigned only its matching topic; all four partitions appear. The old `whatsapp-server-feature-android-armada` group receives no new records after cutover.

After topic and group verification, remove only the `mktemp` file after validating that the variable is non-empty and points to a regular file:

```bash
if [ -n "${ARMADA_KAFKA_CLIENT_CONFIG}" ] && [ -f "${ARMADA_KAFKA_CLIENT_CONFIG}" ]; then
  rm -f -- "${ARMADA_KAFKA_CLIENT_CONFIG}"
fi
```

- [ ] **Step 6: Add database and business acceptance queries**

Query `protocol_command_outbox` for rows created after the recorded cutover timestamp and assert this exact mapping:

```sql
SELECT command_type, kafka_topic, COUNT(*) AS row_count
FROM protocol_command_outbox
WHERE protocol_backend = 'ANDROID'
  AND created_at >= :cutover_epoch_ms
GROUP BY command_type, kafka_topic
ORDER BY command_type, kafka_topic;
```

Expected mappings only:

```text
account.online.requested  -> protocol.android.lifecycle.commands.v1
account.offline.requested -> protocol.android.lifecycle.commands.v1
message.send.requested    -> protocol.android.message.commands.v1
group.join.requested      -> protocol.android.group-join.commands.v1
```

Run a second query and require zero:

```sql
SELECT COUNT(*) AS legacy_rows
FROM protocol_command_outbox
WHERE protocol_backend = 'ANDROID'
  AND created_at >= :cutover_epoch_ms
  AND kafka_topic = 'protocol.android.commands.v1';
```

Business acceptance sequence:

1. Send marketing to an offline Android account; verify `message.send_result_reported.reasonCode=ACCOUNT_OFFLINE`, the Armada attempt reaches its failure terminal state, and message lag continues advancing.
2. Send group join for an offline Android account; verify `group.join_result_reported.reasonCode=ACCOUNT_NOT_ONLINE` and the join result state converges.
3. Block or accumulate message commands, then issue a batch online command. Verify only the message group lag rises while lifecycle offsets advance and accounts reach ONLINE independently.
4. Record elapsed time, group lag snapshots, affected account IDs, and final states in the Harness record without logging credentials or message bodies.

- [ ] **Step 7: Add exact rollback order**

Rollback must stop both services, restore the pre-change Zhuan TOML backup and previous application images/commits, then start Zhuan before Armada. Do not copy commands from any new topic back to the old topic; those messages are explicitly allowed to be dropped. Do not delete any Kafka topic.

- [ ] **Step 8: Validate and commit documentation when overlap permits**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git diff --check -- docs/operations/android-command-topic-isolation-cutover.md .harness/changes/2026-07-17-android-command-topic-isolation.md
```

If the change record contains no pre-existing unrelated edits, commit:

```bash
git add docs/operations/android-command-topic-isolation-cutover.md .harness/changes/2026-07-17-android-command-topic-isolation.md
git diff --cached --check
git commit -m "docs: add android command topic cutover runbook"
```

## Task 9: Run complete local verification and review the final diff

**Files:**
- Verify all files from Tasks 2–8
- Update: `armada/.harness/changes/2026-07-17-android-command-topic-isolation.md`

- [ ] **Step 1: Run the complete Zhuan quality gate**

Run in order required by `whatsapp-server-feature-android-zhuan/AGENTS.md`:

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
gofmt -w internal/configs/configs.go internal/configs/configs_test.go internal/armada/options.go internal/armada/options_test.go internal/armada/config.go internal/armada/consumer_pool.go internal/armada/consumer_pool_test.go internal/armada/consumer.go internal/armada/consumer_test.go internal/armada/start.go internal/armada/start_test.go internal/armada/doc.go
go vet ./...
go build ./...
go test ./...
go test -race ./internal/armada -count=1
```

Expected: all commands PASS. If an unrelated full-repository baseline failure remains, focused `internal/configs` and `internal/armada` tests plus race must pass, and the exact baseline-matching failure must be recorded rather than described as success.

- [ ] **Step 2: Run the Armada focused and full quality gates**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='ProtocolAndroidCommandPropertiesTest,ProtocolKafkaConfigurationTest,ProtocolCommandOutboxServiceImplTest,AndroidMessageSendBackendTest,ProtocolConfigurationTest' test
mvn test

cd /Users/daishuaishuai/IdeaProjects/armada
node armada-deploy/verify-config.mjs
bash armada-deploy/deploy-test.test.sh
bash armada-deploy/package-prod.test.sh
```

Expected: focused tests and deployment guards PASS; full Maven PASS. If a pre-existing full-suite failure remains, record its exact class/message and prove it matches the baseline.

- [ ] **Step 3: Run contract-removal scans**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects
rg -n 'protocol\.android\.commands\.v1|PROTOCOL_ANDROID_COMMANDS_TOPIC' \
  armada/armada-api/src/main armada/armada-deploy \
  whatsapp-server-feature-android-zhuan/internal whatsapp-server-feature-android-zhuan/configs whatsapp-server-feature-android-zhuan/deploy/configs
rg -n 'commandtopic|consumergroup|^[[:space:]]*Concurrency[[:space:]]+int' \
  whatsapp-server-feature-android-zhuan/internal whatsapp-server-feature-android-zhuan/configs whatsapp-server-feature-android-zhuan/deploy/configs
```

Expected: no active old Android topic/environment key and no old single-channel Go/TOML fields. Historical design/plan/change documents are intentionally not part of this scan.

- [ ] **Step 4: Review both diffs for scope and secrets**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects
git -C armada diff --check
git -C whatsapp-server-feature-android-zhuan diff --check
git -C armada diff --stat
git -C whatsapp-server-feature-android-zhuan diff --stat
git -C armada status --short
git -C whatsapp-server-feature-android-zhuan status --short
```

Expected: no whitespace errors, no credentials/private keys, no unrelated file staging, no compatibility branch for the old topic, and no event-topic/business-retry changes.

- [ ] **Step 5: Update evidence and design status**

Append exact test commands/results and commits to the Harness record. Change the design document status from `已确认，待实施计划` to `已实施，待 dev-1 验收` only after all local gates pass; change it to `已部署并验收` only after Task 8 acceptance evidence exists.

- [ ] **Step 6: Request focused code review before deployment**

Review these invariants explicitly:

1. Android online/offline share lifecycle topic and key; message and group join use their own topics.
2. Web/master routing is unchanged.
3. Three Zhuan consumer groups and reader pools are independent.
4. Wrong-route messages are permanent, side-effect free, and committed.
5. Result publication/persistence still precedes source offset commit.
6. Stop and partial-start failure close every reader/writer exactly once.
7. Logs contain family/topic/index but no sensitive payload data.
8. No old single-topic compatibility path remains.

Deploy to dev-1 only after the review has no unresolved correctness findings.
