# Puller Invite Locked Retry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将相邻拉手邀请间隔调整为随机 6～8 秒，并让 Android 对拉手邀请的 `423 locked` 在不查询群成员的前提下等待 6～8 秒后重试一次。

**Architecture:** Armada 后端新增拉手邀请专用延迟策略，保留其他 WhatsApp 副作用现有 3～5 秒策略。Android 在原生 IQ 结果转换边界保留 `code/text`，只对 `pull_task_puller_invite` 的 `423 locked` 做一次上下文可取消的进程内重试；重试耗尽后上报明确的可重试失败。

**Tech Stack:** Java 17、Spring Boot 3.3.5、JUnit 5、AssertJ、Mockito、Go 1.25、zap、标准库 `context`/`errors`/`math/rand/v2`/`time`。

## Global Constraints

- 相邻拉手邀请的随机间隔必须是闭区间 6000～8000 毫秒。
- 现有 `PullTaskOperationDelayPolicy` 必须保持 3000～5000 毫秒，管理员动作、保存联系人和批量拉人不受影响。
- 仅 `source=pull_task_puller_invite` 的 `423 locked` 自动重试，最多一次。
- locked 重试不得查询群成员，不新增群成员查询依赖。
- 第二次仍为 `423 locked` 时返回 `FAILED / GROUP_ACTION_LOCKED / retryable=true`。
- 非 `423` 顶层错误、批量拉人和管理员提权保持现有行为。
- 现有命令 `timeoutMs=30000` 不调整；等待必须响应 `context.Context` 取消。
- 不修改数据库、Flyway、外部配置和依赖版本，不执行远程部署。
- 保留两个仓库中与本任务无关的现有工作区变更，每次提交只包含本任务文件。

## File Map

Armada 后端仓库 `/Users/daishuaishuai/IdeaProjects/armada`：

- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullerInviteDelayPolicy.java` — 拉手邀请专用 6～8 秒随机策略。
- Create: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullerInviteDelayPolicyTest.java` — 延迟边界和非法值测试。
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullerInviteResultServiceImpl.java` — 回调后使用专用策略设置 `nextRunAt`。
- Modify: `armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullerInviteResultServiceImplTest.java` — 守护专用策略接线。
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullerInviteTransactionIntegrationTest.java` — 测试上下文提供确定性专用策略。
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskExecutionEndToEndIntegrationTest.java` — 端到端测试上下文提供确定性专用策略。

Android 仓库 `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan`：

- Modify: `internal/armada/group_participants_sender.go` — 保留 IQ 错误、随机等待、单次重试、终态映射和结构化日志。
- Modify: `internal/armada/group_participants_test.go` — 原生 IQ 转换、重试成功、重试耗尽、非 locked 和取消测试。

---

### Task 1: Armada 拉手邀请专用 6～8 秒延迟

**Files:**
- Create: `armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullerInviteDelayPolicy.java`
- Create: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullerInviteDelayPolicyTest.java`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullerInviteResultServiceImpl.java:21-53,87-94`
- Modify: `armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullerInviteResultServiceImplTest.java:28-41,48-74,194-199`
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullerInviteTransactionIntegrationTest.java:484-496`
- Modify: `armada-api/src/test/java/com/armada/task/scheduler/PullTaskExecutionEndToEndIntegrationTest.java:620-627,849-851`

**Interfaces:**
- Consumes: `long occurredAt`，即上一次拉手邀请协议回调时间。
- Produces: `PullTaskPullerInviteDelayPolicy#nextInviteAt(long occurredAt): long`，返回允许下一次拉手邀请的绝对毫秒时间。

- [ ] **Step 1: 写专用策略的失败测试**

创建 `PullTaskPullerInviteDelayPolicyTest.java`：

```java
package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PullTaskPullerInviteDelayPolicyTest {

    @Test
    void acceptsInclusiveSixAndEightSecondBoundaries() {
        assertThat(new PullTaskPullerInviteDelayPolicy(() -> 6_000L)
                .nextInviteAt(10_000L)).isEqualTo(16_000L);
        assertThat(new PullTaskPullerInviteDelayPolicy(() -> 8_000L)
                .nextInviteAt(10_000L)).isEqualTo(18_000L);
    }

    @Test
    void rejectsDelaySupplierValuesOutsideTheContract() {
        assertThatThrownBy(() -> new PullTaskPullerInviteDelayPolicy(() -> 5_999L)
                .nextInviteAt(10_000L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PullTaskPullerInviteDelayPolicy(() -> 8_001L)
                .nextInviteAt(10_000L)).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run（workdir=`armada/armada-api`）：

```bash
mvn -Dtest=PullTaskPullerInviteDelayPolicyTest test
```

Expected: 测试编译失败，提示 `PullTaskPullerInviteDelayPolicy` 不存在；失败原因必须是新策略尚未实现。

- [ ] **Step 3: 实现最小专用延迟策略**

创建 `PullTaskPullerInviteDelayPolicy.java`：

```java
package com.armada.task.scheduler;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** 相邻拉手邀请之间使用的持久化随机静默策略。 */
@Component
public class PullTaskPullerInviteDelayPolicy {

    static final long MIN_DELAY_MS = 6_000L;
    static final long MAX_DELAY_MS = 8_000L;

    private final LongSupplier delaySupplier;

    /** 创建生产随机策略，闭区间为 6～8 秒。 */
    public PullTaskPullerInviteDelayPolicy() {
        this(() -> ThreadLocalRandom.current().nextLong(
                MIN_DELAY_MS, Math.addExact(MAX_DELAY_MS, 1L)));
    }

    PullTaskPullerInviteDelayPolicy(LongSupplier delaySupplier) {
        if (delaySupplier == null) {
            throw new IllegalArgumentException("拉手邀请静默随机源不能为空");
        }
        this.delaySupplier = delaySupplier;
    }

    /** 返回本次拉手邀请之后允许下一次邀请执行的绝对时间。 */
    public long nextInviteAt(long occurredAt) {
        long delay = delaySupplier.getAsLong();
        if (delay < MIN_DELAY_MS || delay > MAX_DELAY_MS) {
            throw new IllegalStateException("拉手邀请静默随机值超出 6～8 秒边界");
        }
        return Math.addExact(occurredAt, delay);
    }
}
```

- [ ] **Step 4: 运行专用策略测试并确认 GREEN**

```bash
mvn -Dtest=PullTaskPullerInviteDelayPolicyTest test
```

Expected: 2 tests，0 failures，0 errors。

- [ ] **Step 5: 先修改服务测试，让它要求专用策略**

在 `PullTaskPullerInviteResultServiceImplTest` 中改用：

```java
import com.armada.task.scheduler.PullTaskPullerInviteDelayPolicy;

private final PullTaskPullerInviteDelayPolicy delayPolicy = delayPolicy();

private static PullTaskPullerInviteDelayPolicy delayPolicy() {
    PullTaskPullerInviteDelayPolicy policy = mock(PullTaskPullerInviteDelayPolicy.class);
    when(policy.nextInviteAt(anyLong()))
            .thenAnswer(invocation -> invocation.getArgument(0, Long.class) + 7_000L);
    return policy;
}
```

成功用例中的断言改为：

```java
assertThat(executionChange.getValue().nextRunAt()).isEqualTo(8_100L);
verify(delayPolicy).nextInviteAt(1_100L);
```

- [ ] **Step 6: 运行服务测试并确认第二个 RED**

```bash
mvn -Dtest=PullTaskPullerInviteResultServiceImplTest test
```

Expected: 编译或验证失败，因为生产服务仍依赖 `PullTaskOperationDelayPolicy#nextSideEffectAt`。

- [ ] **Step 7: 将生产服务接到专用策略**

在 `PullTaskPullerInviteResultServiceImpl` 中把字段、构造参数和调用替换为：

```java
private final PullTaskPullerInviteDelayPolicy delayPolicy;

public PullTaskPullerInviteResultServiceImpl(
        PullTaskAccountActionMapper actionMapper,
        PullTaskGroupAccountMapper accountMapper,
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskPullerInviteDelayPolicy delayPolicy) {
    this.actionMapper = actionMapper;
    this.accountMapper = accountMapper;
    this.executionMapper = executionMapper;
    this.delayPolicy = delayPolicy;
}
```

执行行唤醒时间改为：

```java
null, delayPolicy.nextInviteAt(callback.occurredAt()),
callback.occurredAt()));
```

- [ ] **Step 8: 更新两个集成测试的 Spring Bean**

`PullTaskPullerInviteTransactionIntegrationTest` 中让结果服务接收 `PullTaskPullerInviteDelayPolicy`，并提供：

```java
@Bean PullTaskPullerInviteDelayPolicy pullerInviteDelayPolicy() {
    return new PullTaskPullerInviteDelayPolicy(() -> 7_000L);
}
```

`PullTaskExecutionEndToEndIntegrationTest` 中同样把 `pullerInviteResultService` 的参数改为 `PullTaskPullerInviteDelayPolicy`，并增加相同的 7000 毫秒确定性 Bean；原有 `PullTaskOperationDelayPolicy(() -> 4_000L)` 保留。

- [ ] **Step 9: 运行后端聚焦测试并确认 GREEN**

```bash
mvn -Dtest='PullTaskOperationDelayPolicyTest,PullTaskPullerInviteDelayPolicyTest,PullTaskPullerInviteResultServiceImplTest,PullTaskPullerInviteTransactionIntegrationTest,PullTaskExecutionEndToEndIntegrationTest' test
```

Expected: 所列测试类全部通过；原 `PullTaskOperationDelayPolicyTest` 仍保持 3～5 秒语义。

- [ ] **Step 10: 提交后端变更**

```bash
git add armada-api/src/main/java/com/armada/task/scheduler/PullTaskPullerInviteDelayPolicy.java \
  armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullerInviteResultServiceImpl.java \
  armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullerInviteDelayPolicyTest.java \
  armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullerInviteResultServiceImplTest.java \
  armada-api/src/test/java/com/armada/task/scheduler/PullTaskPullerInviteTransactionIntegrationTest.java \
  armada-api/src/test/java/com/armada/task/scheduler/PullTaskExecutionEndToEndIntegrationTest.java
git commit -m "feat: increase puller invite delay"
```

---

### Task 2: Android 保留顶层 IQ 错误码与文本

**Files:**
- Modify: `internal/armada/group_participants_sender.go:227-270`
- Modify: `internal/armada/group_participants_test.go:183-210,349-366`

**Interfaces:**
- Consumes: `entity.IqResult` 与期望成员容量。
- Produces: `participantReportsFromIQ(entity.IqResult, int) ([]ParticipantReport, error)`；顶层 IQ 错误返回 `*participantIQError{Code, Text}`。

- [ ] **Step 1: 写原生 IQ 错误保真失败测试**

在 `group_participants_test.go` 增加：

```go
func TestParticipantReportsFromIQPreservesTopLevelError(t *testing.T) {
	_, err := participantReportsFromIQ(entity.IqResult{
		ErrorEntity: entity.NewErrorEntity("423", "locked"),
	}, 1)
	var iqErr *participantIQError
	if !errors.As(err, &iqErr) {
		t.Fatalf("error = %T %v, want *participantIQError", err, err)
	}
	if iqErr.Code != "423" || iqErr.Text != "locked" {
		t.Fatalf("iq error = %#v", iqErr)
	}
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run（workdir=`whatsapp-server-feature-android-zhuan`）：

```bash
go test ./internal/armada -run TestParticipantReportsFromIQPreservesTopLevelError -count=1
```

Expected: 编译失败，提示 `participantReportsFromIQ` 或 `participantIQError` 不存在。

- [ ] **Step 3: 实现带类型的 IQ 错误转换**

在 `group_participants_sender.go` 增加：

```go
type participantIQError struct {
	Code string
	Text string
}

func (e *participantIQError) Error() string {
	return fmt.Sprintf("group participant IQ rejected: code=%s text=%s", e.Code, e.Text)
}

func participantReportsFromIQ(
	iqResult entity.IqResult,
	capacity int,
) ([]ParticipantReport, error) {
	if failure := iqResult.GetErrorEntityResult(); failure != nil {
		return nil, &participantIQError{
			Code: strings.TrimSpace(failure.Code()),
			Text: strings.TrimSpace(failure.Text()),
		}
	}
	addResult := iqResult.GetAddGroupResult()
	reports := make([]ParticipantReport, 0, capacity)
	for _, attr := range addResult.Members() {
		reports = append(reports, ParticipantReport{
			JID: attr.Jid, Phone: attr.PhoneNumber, Err: attr.Err,
		})
	}
	return reports, nil
}
```

把 `waAppParticipantsClient.AddParticipants` 中的顶层错误与逐成员展开代码替换为：

```go
return participantReportsFromIQ(iqResult, len(participants))
```

- [ ] **Step 4: 格式化并运行聚焦测试确认 GREEN**

```bash
gofmt -w internal/armada/group_participants_sender.go internal/armada/group_participants_test.go
go test ./internal/armada -run 'TestParticipantReportsFromIQPreservesTopLevelError|TestPromotionParticipantReport' -count=1
```

Expected: 所列测试全部通过。

- [ ] **Step 5: 提交 IQ 错误保真变更**

```bash
git add internal/armada/group_participants_sender.go internal/armada/group_participants_test.go
git commit -m "fix: preserve group participant IQ errors"
```

---

### Task 3: Android 对拉手邀请的 423 locked 单次重试

**Files:**
- Modify: `internal/armada/group_participants_sender.go:35-135,231-270`
- Modify: `internal/armada/group_participants_test.go:183-305`

**Interfaces:**
- Consumes: Task 2 的 `*participantIQError`、`GroupActionCommand`、`ZhuanParticipantsClient`。
- Produces: `addPullerWithLockedRetry(context.Context, GroupActionCommand, ZhuanParticipantsClient) ([]ParticipantReport, error)`；第一次 locked 后等待并重试一次。

- [ ] **Step 1: 扩充 fake client 以记录顺序调用结果**

在测试文件中把 fake 增强为：

```go
type participantCallOutcome struct {
	reports []ParticipantReport
	err     error
}

type fakeParticipantsClient struct {
	reports       []ParticipantReport
	promoteReport ParticipantReport
	err           error
	action        string
	addOutcomes   []participantCallOutcome
	addCalls      int
	addCalled     chan struct{}
}

func (f *fakeParticipantsClient) AddParticipants(string, []string) ([]ParticipantReport, error) {
	f.action = "add"
	f.addCalls++
	if f.addCalled != nil {
		select {
		case f.addCalled <- struct{}{}:
		default:
		}
	}
	if len(f.addOutcomes) > 0 {
		index := f.addCalls - 1
		if index >= len(f.addOutcomes) {
			index = len(f.addOutcomes) - 1
		}
		outcome := f.addOutcomes[index]
		return outcome.reports, outcome.err
	}
	return f.reports, f.err
}
```

- [ ] **Step 2: 写 locked 后重试成功的失败测试**

```go
func TestParticipantsSenderRetriesPullerInviteLockedOnceThenSucceeds(t *testing.T) {
	client := &fakeParticipantsClient{addOutcomes: []participantCallOutcome{
		{err: &participantIQError{Code: "423", Text: "locked"}},
		{reports: []ParticipantReport{{JID: "8613900000000@s.whatsapp.net"}}},
	}}
	sender := NewZhuanParticipantsSender(&fakeParticipantsResolver{client: client})
	sender.lockedRetryDelay = func() time.Duration { return 0 }
	command, err := ParseGroupActionCommand([]byte(participantsCommandJSON(pullerInviteEnvelope, "")))
	if err != nil {
		t.Fatal(err)
	}

	results, err := sender.SendAll(context.Background(), command)
	if err != nil {
		t.Fatal(err)
	}
	if client.addCalls != 2 || len(results) != 1 || results[0].Outcome != "SUCCESS" {
		t.Fatalf("calls/results = %d/%#v", client.addCalls, results)
	}
}

func TestRandomLockedRetryDelayStaysWithinSixToEightSeconds(t *testing.T) {
	for range 10_000 {
		delay := randomLockedRetryDelay()
		if delay < 6*time.Second || delay > 8*time.Second {
			t.Fatalf("delay = %s, want [6s, 8s]", delay)
		}
	}
}
```

- [ ] **Step 3: 运行重试成功测试并确认 RED**

```bash
go test ./internal/armada -run 'TestParticipantsSenderRetriesPullerInviteLockedOnceThenSucceeds|TestRandomLockedRetryDelayStaysWithinSixToEightSeconds' -count=1
```

Expected: 编译失败或断言 `addCalls=1`，因为 locked 重试尚未实现。

- [ ] **Step 4: 写重试耗尽、非 locked 和取消测试**

```go
func TestParticipantsSenderReportsRetryableFailureAfterSecondLocked(t *testing.T) {
	locked := &participantIQError{Code: "423", Text: "locked"}
	client := &fakeParticipantsClient{addOutcomes: []participantCallOutcome{
		{err: locked}, {err: locked},
	}}
	sender := NewZhuanParticipantsSender(&fakeParticipantsResolver{client: client})
	sender.lockedRetryDelay = func() time.Duration { return 0 }
	command, _ := ParseGroupActionCommand([]byte(participantsCommandJSON(pullerInviteEnvelope, "")))

	results, err := sender.SendAll(context.Background(), command)
	if err != nil {
		t.Fatal(err)
	}
	if client.addCalls != 2 || len(results) != 1 || results[0].Outcome != "FAILED" ||
		results[0].ReasonCode != "GROUP_ACTION_LOCKED" || !results[0].Retryable {
		t.Fatalf("calls/results = %d/%#v", client.addCalls, results)
	}
}

func TestParticipantsSenderDoesNotRetryNonLockedNativeError(t *testing.T) {
	client := &fakeParticipantsClient{err: errors.New("connection closed")}
	sender := NewZhuanParticipantsSender(&fakeParticipantsResolver{client: client})
	command, _ := ParseGroupActionCommand([]byte(participantsCommandJSON(pullerInviteEnvelope, "")))

	results, err := sender.SendAll(context.Background(), command)
	if err != nil {
		t.Fatal(err)
	}
	if client.addCalls != 1 || results[0].Outcome != "UNKNOWN" {
		t.Fatalf("calls/results = %d/%#v", client.addCalls, results)
	}
}

func TestParticipantsSenderCancellationStopsLockedRetry(t *testing.T) {
	client := &fakeParticipantsClient{
		err:       &participantIQError{Code: "423", Text: "locked"},
		addCalled: make(chan struct{}, 1),
	}
	sender := NewZhuanParticipantsSender(&fakeParticipantsResolver{client: client})
	sender.lockedRetryDelay = func() time.Duration { return time.Hour }
	command, _ := ParseGroupActionCommand([]byte(participantsCommandJSON(pullerInviteEnvelope, "")))
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan []GroupActionResult, 1)
	go func() {
		results, _ := sender.SendAll(ctx, command)
		done <- results
	}()
	<-client.addCalled
	cancel()
	results := <-done
	if client.addCalls != 1 || len(results) != 1 || results[0].Outcome != "UNKNOWN" {
		t.Fatalf("calls/results = %d/%#v", client.addCalls, results)
	}
}
```

- [ ] **Step 5: 运行三项测试并确认 RED**

```bash
go test ./internal/armada -run 'TestParticipantsSenderReportsRetryableFailureAfterSecondLocked|TestParticipantsSenderDoesNotRetryNonLockedNativeError|TestParticipantsSenderCancellationStopsLockedRetry' -count=1
```

Expected: locked 终态、单次重试或取消断言失败；非 locked 用例用于固定现有行为。

- [ ] **Step 6: 实现随机延迟、错误识别和上下文等待**

在生产文件增加导入：

```go
"math/rand/v2"
"time"

"go.uber.org/zap"
```

增加随机策略和识别函数：

```go
const (
	minLockedRetryDelayMillis int64 = 6_000
	maxLockedRetryDelayMillis int64 = 8_000
)

func randomLockedRetryDelay() time.Duration {
	millis := minLockedRetryDelayMillis +
		rand.Int64N(maxLockedRetryDelayMillis-minLockedRetryDelayMillis+1)
	return time.Duration(millis) * time.Millisecond
}

func participantLockedError(err error) (*participantIQError, bool) {
	var iqErr *participantIQError
	if !errors.As(err, &iqErr) || iqErr.Code != "423" || !strings.EqualFold(iqErr.Text, "locked") {
		return nil, false
	}
	return iqErr, true
}
```

扩展 sender 并设置生产随机源：

```go
type ZhuanParticipantsSender struct {
	Resolver         ZhuanParticipantsClientResolver
	lockedRetryDelay func() time.Duration
}

func NewZhuanParticipantsSender(resolver ZhuanParticipantsClientResolver) *ZhuanParticipantsSender {
	return &ZhuanParticipantsSender{
		Resolver:         resolver,
		lockedRetryDelay: randomLockedRetryDelay,
	}
}
```

- [ ] **Step 7: 实现只针对拉手邀请的单次重试**

新增：

```go
func (s *ZhuanParticipantsSender) addPullerWithLockedRetry(
	ctx context.Context,
	command GroupActionCommand,
	client ZhuanParticipantsClient,
) ([]ParticipantReport, error) {
	reports, err := client.AddParticipants(
		command.Payload.GroupJID, command.Payload.Participants)
	if err == nil || command.Payload.Source != SourcePullTaskPullerInvite {
		return reports, err
	}
	iqErr, locked := participantLockedError(err)
	if !locked {
		return reports, err
	}
	delay := s.lockedRetryDelay()
	zap.L().Warn("Android 拉手邀请收到群操作锁，等待后重试",
		zap.String("commandId", command.CommandID),
		zap.Int64("actionId", command.Payload.ActionID),
		zap.String("groupJid", command.Payload.GroupJID),
		zap.String("targetJid", command.Payload.Participants[0]),
		zap.String("iqCode", iqErr.Code),
		zap.String("iqText", iqErr.Text),
		zap.Int64("retryDelayMs", delay.Milliseconds()))
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-timer.C:
	}
	return client.AddParticipants(command.Payload.GroupJID, command.Payload.Participants)
}
```

在 `SendAll` 的 goroutine 中，将 ADD 分支改为调用 `addPullerWithLockedRetry`。在 `done.err != nil` 分支最前面增加：

```go
if command.Payload.Source == SourcePullTaskPullerInvite {
	if iqErr, locked := participantLockedError(done.err); locked {
		zap.L().Warn("Android 拉手邀请重试后仍被群操作锁拒绝",
			zap.String("commandId", command.CommandID),
			zap.Int64("actionId", command.Payload.ActionID),
			zap.String("groupJid", command.Payload.GroupJID),
			zap.String("targetJid", command.Payload.Participants[0]),
			zap.String("iqCode", iqErr.Code),
			zap.String("iqText", iqErr.Text))
		return sameResultForAll(participants, func(target string) GroupActionResult {
			return failedGroupActionResult(
				target, "GROUP_ACTION_LOCKED", "WhatsApp 群成员操作暂时锁定", true)
		}), nil
	}
}
```

不得为 `ZhuanParticipantsClient` 增加群成员查询方法；同一业务命令的 `AttemptNo` 保持不变。

- [ ] **Step 8: 格式化并运行 Android 聚焦测试确认 GREEN**

```bash
gofmt -w internal/armada/group_participants_sender.go internal/armada/group_participants_test.go
go test ./internal/armada -run 'TestParticipantReportsFromIQPreservesTopLevelError|TestParticipantsSenderRetriesPullerInviteLockedOnceThenSucceeds|TestRandomLockedRetryDelayStaysWithinSixToEightSeconds|TestParticipantsSenderReportsRetryableFailureAfterSecondLocked|TestParticipantsSenderDoesNotRetryNonLockedNativeError|TestParticipantsSenderCancellationStopsLockedRetry|TestParticipantsSenderReportsUncertainForEveryMemberAfterNativeCallError' -count=1
```

Expected: 所列测试全部通过；locked 成功路径调用原生两次，locked 耗尽路径两次，非 locked 和取消路径一次。

- [ ] **Step 9: 使用 race detector 验证取消与 goroutine 路径**

```bash
go test -race ./internal/armada -run 'TestParticipantsSenderRetriesPullerInviteLockedOnceThenSucceeds|TestParticipantsSenderCancellationStopsLockedRetry' -count=1
```

Expected: PASS，且无 data race 报告。

- [ ] **Step 10: 提交 Android locked 重试变更**

```bash
git add internal/armada/group_participants_sender.go internal/armada/group_participants_test.go
git commit -m "feat: retry locked puller invite once"
```

---

### Task 4: 跨仓完整验证与差异审计

**Files:**
- Verify only: Task 1～3 涉及的全部文件。

**Interfaces:**
- Consumes: 后端 `nextInviteAt` 接线与 Android `GROUP_ACTION_LOCKED` 结果事件。
- Produces: 可供部署评审的测试、构建、静态检查和 diff 证据；本任务不部署。

- [ ] **Step 1: 验证后端完整测试与构建**

Run（workdir=`armada/armada-api`）：

```bash
mvn test
mvn -DskipTests package
```

Expected: 两条命令 exit 0，测试 0 failures/0 errors，构建成功。

- [ ] **Step 2: 验证 Android 强制检查**

Run（workdir=`whatsapp-server-feature-android-zhuan`）：

```bash
gofmt -w internal/armada/group_participants_sender.go internal/armada/group_participants_test.go
git diff --check
go vet ./...
go build ./...
go test ./...
```

Expected: `git diff --check` 无输出，其余命令 exit 0，测试无失败。

- [ ] **Step 3: 审计未扩大行为范围**

```bash
git -C /Users/daishuaishuai/IdeaProjects/armada diff HEAD~1 -- \
  armada-api/src/main/java/com/armada/task/scheduler \
  armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullerInviteResultServiceImpl.java
git -C /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan diff HEAD~2 -- \
  internal/armada/group_participants_sender.go internal/armada/group_participants_test.go
```

Expected: 后端只有拉手邀请改为专用 6～8 秒，原共享策略未变；Android 只有拉手邀请 source 进入 423 单次重试，未出现群成员查询调用。

- [ ] **Step 4: 检查工作区与提交范围**

```bash
git -C /Users/daishuaishuai/IdeaProjects/armada status --short
git -C /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan status --short
```

Expected: 只保留任务开始前已经存在的无关工作区状态；没有未提交的本任务源文件。

- [ ] **Step 5: 形成交付摘要**

交付摘要必须包含：

- 后端相邻拉手邀请为随机 6～8 秒，其他副作用仍为 3～5 秒。
- Android `423 locked` 不查成员、等待 6～8 秒、最多重试一次。
- 第二次 locked 上报 `GROUP_ACTION_LOCKED` 可重试失败。
- 实际执行过的测试、构建、vet 和 race 命令及结果。
- 明确声明未部署测试环境。
