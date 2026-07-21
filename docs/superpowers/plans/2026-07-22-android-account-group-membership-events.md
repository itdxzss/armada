# Android Account Group Membership Events Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Android Zhuan 对当前账号的 `add/remove/leave` 发布精确群关系事件，并给全量群快照增加可靠的完整性标记。

**Architecture:** 复用现有 `w:gp2` participant 自身分类与 `AccountEventWriter`；新增专用关系事件 builder/publisher，协调器对 `SELF` 事件独立异步发布精确事实，同时继续防抖刷新群快照。快照 publisher 改为接收 `ParticipatingGroupsSnapshot`，由 IQ 容器有效性和 `SkippedGroupCount` 计算 `snapshotComplete`。

**Tech Stack:** Go 1.25、Zhuan XMPP 事件、Kafka account events、标准库 testing、race detector

---

## 0. 执行边界与文件结构

本计划只修改：

`/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan`

当前原工作区已有大量 LID 群发送在途修改，且与本计划会同时触及 `group.go`、notification、node
processor 等附近代码。执行前必须使用 `superpowers:using-git-worktrees` 建隔离 worktree；不得在原工作区
直接编辑、格式化、暂存或提交。

新增文件：

- `internal/armada/group_membership_event.go`：精确关系事件模型和 builder。
- `internal/armada/group_membership_event_test.go`：事件契约、路由、敏感字段测试。
- `internal/armada/group_membership_publisher.go`：复用账号事件 writer 的关系事件 publisher。
- `internal/armada/group_membership_publisher_test.go`：publisher 成功、失败和校验测试。

修改文件：

- `internal/armada/event.go` / `_test.go`：账号事件 data 增加关系/快照字段，快照 builder 接收完整性。
- `internal/armada/groups_publisher.go` / `_test.go`：发布完整快照对象而不是只传 groups。
- `internal/armada/group_snapshot_coordinator.go` / `_test.go`：精确事件独立发布，快照继续防抖。
- `internal/armada/start.go` / `_test.go`：装配两个 publisher。

不修改 `internal/service/node/processor/group_notification.go` 的 participant 解析规则；现有
`GroupParticipantsChangedEvent` 已只携带 `Self/Other/Unknown`、action、群 JID 和数量，满足本次输入要求。

## Task 1: 冻结精确关系事件契约

**Files:**

- Create: `internal/armada/group_membership_event.go`
- Create: `internal/armada/group_membership_event_test.go`
- Modify: `internal/armada/event.go`
- Modify: `internal/armada/event_test.go`

- [ ] **Step 1: 写精确事件失败测试**

```go
func TestBuildGroupMembershipChangedEventSerializesSafeContract(t *testing.T) {
	when := time.Date(2026, 7, 22, 2, 0, 0, 123, time.UTC)
	event, err := BuildGroupMembershipChangedEvent(
		completeCommandContext(),
		GroupMembershipChange{GroupJID: "120363001@g.us", Action: "remove"},
		"worker-a",
		when,
	)
	if err != nil {
		t.Fatal(err)
	}
	if event.Event != EventAccountGroupMembershipChanged {
		t.Fatalf("event = %q", event.Event)
	}
	if event.Data.GroupJID != "120363001@g.us" || event.Data.Action != "remove" {
		t.Fatalf("data = %#v", event.Data)
	}
	payload, err := json.Marshal(event)
	if err != nil {
		t.Fatal(err)
	}
	for _, forbidden := range []string{"participants", "phone_number", "pn", "lid", "operator"} {
		if bytes.Contains(bytes.ToLower(payload), []byte(forbidden)) {
			t.Fatalf("payload contains forbidden field %q: %s", forbidden, payload)
		}
	}
}
```

再用表驱动覆盖：`add/remove/leave` 通过；空租户、空业务账号、空协议账号、非群 JID、未知 action
失败；builder 固定写 `selfParticipation=SELF`、`source=android_wgp2`。

- [ ] **Step 2: 运行并确认先失败**

```bash
go test ./internal/armada -run 'TestBuildGroupMembershipChangedEvent'
```

Expected: FAIL，`BuildGroupMembershipChangedEvent` 和事件常量不存在。

- [ ] **Step 3: 定义事件输入和 builder**

`group_membership_event.go` 使用以下稳定类型：

```go
const EventAccountGroupMembershipChanged = "account.group_membership_changed"

type GroupMembershipChange struct {
	GroupJID string
	Action   string
}

func BuildGroupMembershipChangedEvent(
	command CommandContext,
	change GroupMembershipChange,
	workerID string,
	occurredAt time.Time,
) (EventEnvelope, error)
```

在 `AccountEventData` 增加只供新事件使用的字段：

```go
GroupJID          string `json:"groupJid,omitempty"`
Action            string `json:"action,omitempty"`
SelfParticipation string `json:"selfParticipation,omitempty"`
```

builder 规范化 action 为小写，仅接受 `add/remove/leave`；群 JID 必须能被 `jabber.ParseJID`
解析且 `jabber.IsGroupJid` 为 true。事件 ID 使用：

```go
fmt.Sprintf("%s:%s:%s:%s:%d",
	protocolAccountID,
	EventAccountGroupMembershipChanged,
	groupJID,
	action,
	occurredAt.UnixNano())
```

data 固定写 `Source: "android_wgp2"` 和 `SelfParticipation: "SELF"`，不接受外部 participant 数据。

- [ ] **Step 4: 运行测试确认通过**

```bash
go test ./internal/armada -run 'TestBuild(GroupMembershipChangedEvent|GroupsReportedEvent|StateChangedEvent)'
```

Expected: PASS；原状态事件继续不序列化群专用字段。

- [ ] **Step 5: 提交事件契约**

```bash
git add internal/armada/event.go internal/armada/event_test.go \
  internal/armada/group_membership_event.go internal/armada/group_membership_event_test.go
git commit -m "feat: define android group membership events"
```

## Task 2: 发布精确事件且不依赖快照查询

**Files:**

- Create: `internal/armada/group_membership_publisher.go`
- Create: `internal/armada/group_membership_publisher_test.go`
- Modify: `internal/armada/group_snapshot_coordinator.go`
- Modify: `internal/armada/group_snapshot_coordinator_test.go`

- [ ] **Step 1: 写 publisher 和协调器失败测试**

```go
func TestGroupSnapshotCoordinatorPublishesSelfMembershipChangeAndStillRefreshes(t *testing.T) {
	scheduler := &manualScheduler{}
	membershipPublisher := &recordingMembershipPublisher{}
	groupPublisher := &recordingGroupPublisher{}
	coordinator := newCoordinatorWithMembershipPublisher(
		scheduler, &recordingGroupFetcher{}, groupPublisher, membershipPublisher,
	)
	defer coordinator.Stop()
	command := completeCommandContext()
	coordinator.StatePublished(command, "ONLINE")

	coordinator.ObserveEvent(&events.GroupParticipantsChangedEvent{
		BaseEvent: events.BaseEvent{Username: command.Phone},
		GroupJid: jabber.MustParseJID("120363001@g.us"),
		Action: "remove",
		SelfParticipation: events.SelfParticipationSelf,
	})

	if got := membershipPublisher.Changes(); len(got) != 1 || got[0].Action != "remove" {
		t.Fatalf("changes = %#v", got)
	}
	scheduler.Fire(t, 1)
	if len(groupPublisher.Snapshots()) != 1 {
		t.Fatalf("snapshots = %#v", groupPublisher.Snapshots())
	}
}
```

还要覆盖：Other 不发布也不刷新；Unknown 不发布但刷新；publisher 返回错误仍刷新；账号不在线或代次失效
不发布；重复事件允许发布独立事实，但事件正文不含 participant 身份。

- [ ] **Step 2: 运行并确认先失败**

```bash
go test ./internal/armada -run 'Test(GroupMembershipEventPublisher|GroupSnapshotCoordinatorPublishesSelfMembership)'
```

Expected: FAIL，新 publisher 和协调器依赖不存在。

- [ ] **Step 3: 实现窄 publisher**

```go
type GroupMembershipChangePublisher interface {
	PublishChange(context.Context, CommandContext, GroupMembershipChange) error
}

type MembershipChangedEventPublisher struct {
	Publisher AccountEventWriter
	WorkerID  string
	Now       func() time.Time
}
```

`PublishChange` 只做 builder 校验和 `AccountEventWriter.Publish`，错误分别包装为
`build event` / `write event`；禁止记录原始 event JSON。

- [ ] **Step 4: 在协调器复制路由上下文后异步发布**

在 `GroupSnapshotCoordinatorOptions` 增加：

```go
MembershipPublisher GroupMembershipChangePublisher
```

`ObserveEvent` 对 `SelfParticipationSelf` 执行：

```go
c.publishMembershipChange(
	strings.TrimSpace(event.GetEventUserName()),
	GroupMembershipChange{
		GroupJID: value.GroupJid.ToNonAD().String(),
		Action: strings.ToLower(strings.TrimSpace(value.Action)),
	},
)
```

`publishMembershipChange` 必须在锁内从 `phoneToProtocol` 和当前 state 复制 `CommandContext`/generation，
解锁后通过 `options.Run` 调 publisher。发布失败只写低敏 `accountId/action/errorClass=publish_failed`，
不得 return 阻止后续 `scheduleSignal`。

- [ ] **Step 5: 运行协调器测试和 race**

```bash
go test ./internal/armada -run 'Test(GroupMembershipEventPublisher|GroupSnapshotCoordinator)'
go test -race ./internal/armada -run 'TestGroupSnapshotCoordinatorPublishesSelfMembershipChangeAndStillRefreshes|TestGroupSnapshotCoordinatorSerializesAndMergesInFlightSignals'
```

Expected: PASS，无 data race。

- [ ] **Step 6: 提交独立发布链路**

```bash
git add internal/armada/group_membership_publisher.go \
  internal/armada/group_membership_publisher_test.go \
  internal/armada/group_snapshot_coordinator.go \
  internal/armada/group_snapshot_coordinator_test.go
git commit -m "feat: publish android self membership changes"
```

## Task 3: 给全量快照增加完整性

**Files:**

- Modify: `internal/armada/event.go`
- Modify: `internal/armada/event_test.go`
- Modify: `internal/armada/groups_publisher.go`
- Modify: `internal/armada/groups_publisher_test.go`
- Modify: `internal/armada/group_snapshot_coordinator.go`
- Modify: `internal/armada/group_snapshot_coordinator_test.go`

- [ ] **Step 1: 写完整/不完整快照失败测试**

```go
func TestBuildGroupsReportedEventMarksSkippedSnapshotIncomplete(t *testing.T) {
	snapshot := ParticipatingGroupsSnapshot{
		Groups: []ReportedGroup{{GroupJID: "120363001@g.us"}},
		SkippedGroupCount: 1,
	}
	event, err := BuildGroupsReportedEvent(
		completeCommandContext(), "android_groups_dirty", snapshot, "worker", time.Unix(0, 1))
	if err != nil {
		t.Fatal(err)
	}
	if event.Data.SnapshotComplete == nil || *event.Data.SnapshotComplete {
		t.Fatalf("snapshotComplete = %#v", event.Data.SnapshotComplete)
	}
	if event.Data.SkippedGroupCount == nil || *event.Data.SkippedGroupCount != 1 {
		t.Fatalf("skippedGroupCount = %#v", event.Data.SkippedGroupCount)
	}
}
```

另测成功零群序列化为 `groups:[]/snapshotComplete:true/skippedGroupCount:0`；状态事件不带这三个字段。

- [ ] **Step 2: 运行并确认先失败**

```bash
go test ./internal/armada -run 'TestBuildGroupsReportedEvent|TestGroupsEventPublisher'
```

Expected: FAIL，快照完整性字段和签名尚未实现。

- [ ] **Step 3: 扩展事件 data 并收紧函数签名**

```go
SnapshotComplete   *bool `json:"snapshotComplete,omitempty"`
SkippedGroupCount *int  `json:"skippedGroupCount,omitempty"`
```

将 builder 改为：

```go
func BuildGroupsReportedEvent(
	command CommandContext,
	source string,
	snapshot ParticipatingGroupsSnapshot,
	workerID string,
	occurredAt time.Time,
) (EventEnvelope, error)
```

使用局部变量取指针，确保 false 和 0 都显式序列化：

```go
complete := snapshot.SkippedGroupCount == 0
skipped := snapshot.SkippedGroupCount
```

- [ ] **Step 4: publisher 全程传递 snapshot**

```go
type GroupReportPublisher interface {
	PublishGroups(context.Context, CommandContext, string, ParticipatingGroupsSnapshot) error
}
```

协调器调用改为：

```go
err = c.options.Publisher.PublishGroups(c.ctx, command, source, snapshot)
```

Fetcher 已保证 IQ 失败和 groups 容器缺失返回 error、不发布；合法条目过滤数量继续来自
`ParticipatingGroupsSnapshot.SkippedGroupCount`。

- [ ] **Step 5: 运行群快照全套测试**

```bash
go test ./internal/armada -run 'Test(BuildGroupsReportedEvent|GroupsEventPublisher|ZhuanParticipatingGroupFetcher|GroupSnapshotCoordinator)'
```

Expected: PASS；不完整快照仍会发布可见群，但后端可根据 `snapshotComplete=false` 禁止缺失校准。

- [ ] **Step 6: 提交快照完整性**

```bash
git add internal/armada/event.go internal/armada/event_test.go \
  internal/armada/groups_publisher.go internal/armada/groups_publisher_test.go \
  internal/armada/group_snapshot_coordinator.go internal/armada/group_snapshot_coordinator_test.go
git commit -m "feat: report android group snapshot completeness"
```

## Task 4: 装配两个 publisher 并回归生命周期

**Files:**

- Modify: `internal/armada/start.go`
- Modify: `internal/armada/start_test.go`

- [ ] **Step 1: 写装配失败测试**

测试启动时 `GroupsEventPublisher` 和 `MembershipChangedEventPublisher` 使用同一个
`AccountEventWriter`，Stop 仍只关闭 writer 一次，observer 仍只注册一次。

- [ ] **Step 2: 运行并确认先失败**

```bash
go test ./internal/armada -run 'TestStart.*Group'
```

Expected: FAIL，协调器尚未拿到关系 publisher。

- [ ] **Step 3: 更新生产装配**

在构造 `GroupSnapshotCoordinatorOptions` 时显式注入：

```go
Publisher: &GroupsEventPublisher{
	Publisher: accountEventWriter,
	WorkerID: options.WorkerID,
},
MembershipPublisher: &MembershipChangedEventPublisher{
	Publisher: accountEventWriter,
	WorkerID: options.WorkerID,
},
```

不得新建第二个 Kafka writer，不改变 topic、consumer、ONLINE 30–45 秒延迟或 1 秒防抖参数。

- [ ] **Step 4: 运行包测试并提交**

```bash
go test ./internal/armada -run 'TestStart|TestGroupSnapshotCoordinator'
git add internal/armada/start.go internal/armada/start_test.go
git commit -m "feat: wire android group membership publisher"
```

## Task 5: Android 完整验证

- [ ] **Step 1: 格式化本计划改动文件**

```bash
gofmt -w internal/armada/event.go internal/armada/event_test.go \
  internal/armada/group_membership_event.go internal/armada/group_membership_event_test.go \
  internal/armada/group_membership_publisher.go internal/armada/group_membership_publisher_test.go \
  internal/armada/groups_publisher.go internal/armada/groups_publisher_test.go \
  internal/armada/group_snapshot_coordinator.go internal/armada/group_snapshot_coordinator_test.go \
  internal/armada/start.go internal/armada/start_test.go
git diff --check
```

Expected: 无输出、退出码 0。

- [ ] **Step 2: 执行仓库强制门禁**

```bash
go vet ./...
go build ./...
go test ./...
go test -race ./internal/armada -run 'TestGroupSnapshotCoordinator|TestMembershipChangedEventPublisher'
```

Expected: 全部退出码 0；记录实际测试数和耗时，不用单个定向测试冒充全量验证。

- [ ] **Step 3: 做敏感字段和范围扫描**

```bash
rg -n 'participants|phone_number|\bPN\b|\bLID\b|raw notification' \
  internal/armada/group_membership_event.go \
  internal/armada/group_membership_publisher.go
git status --short
```

Expected: 生产事件结构无 participant 身份字段；工作区只包含本计划提交，不包含原脏工作区文件。
