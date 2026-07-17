# Android Zhuan Marketing Message Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Android Zhuan 从现有 Armada Kafka command topic 消费五种营销消息，原生发送图片说明文字、单跳转按钮和真实 `@all`，并以可恢复且不重复触达的方式回写统一结果事件。

**Architecture:** 生命周期命令和消息命令共用原始 envelope 分发器及显式提交 consumer；消息链路新增 `MessageCommandExecutor`，按 Redis 状态机 `NEW -> PROCESSING -> RESULT_STORED -> PUBLISHED` 控制发送副作用。`ZhuanMessageSender` 只依赖可替换的原生 client 接口，根据 `wsPhone` 解析在线 `WaApp`，五种消息最终都走 Zhuan 进程内发送方法；结果由独立 publisher 写入 `protocol.message.events.v1`。

**Tech Stack:** Go 1.25、kafka-go、go-redis/v9、miniredis、Zhuan 原生 WhatsApp protobuf/Signal 群发送链路、标准库 testing

---

## 0. 执行边界、兼容要求和文件结构

本计划修改 `/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan`，配套 Armada Java 适配由
`/Users/daishuaishuai/IdeaProjects/armada/docs/superpowers/plans/2026-07-15-armada-marketing-message-routing-implementation.md` 实现。

实施前必须执行：

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
git status --short
git diff -- internal/armada/group_snapshot_coordinator.go internal/armada/group_snapshot_coordinator_test.go
```

Expected: 看见现有群快照在途修改；本计划不得编辑、格式化、暂存或提交这两个文件。若状态增加其它并行改动，逐文件避让，不使用目录级 `git add`。

新增文件：

- `internal/armada/message_command.go` / `_test.go`：消息命令 wire model、解析与校验。
- `internal/armada/message_event.go` / `_test.go`：统一发送结果事件模型和构造器。
- `internal/armada/message_publisher.go` / `_test.go`：发送结果 Kafka publisher。
- `internal/armada/message_state.go` / `_test.go`：Redis 命令状态机。
- `internal/armada/message_sender.go` / `_test.go`：五类消息、账号解析和 `@all` 编排。
- `internal/armada/message_executor.go` / `_test.go`：状态机、发送和结果发布的事务边界。
- `internal/service/entity/message_context.go` / `_test.go`：WhatsApp protobuf mention context helper。
- `internal/service/node/message_payload.go` / `_test.go`：文本、图片、链接卡片和按钮卡片纯 payload builder。

修改文件：

- `internal/armada/command.go` / `_test.go`：抽取通用 envelope，保留生命周期解析契约。
- `internal/armada/options.go`、`config.go`、`options_test.go`：消息事件 topic 和处理中超时。
- `internal/armada/start.go` / `_test.go`：同时装配 lifecycle/message handler、state store 和两个 publisher。
- `internal/configs/configs.go` / `_test.go`、两份示例 TOML：公开新配置。
- `internal/service/entity/message.go`：链接/按钮 wire 到原生层所需字段。
- `internal/service/app/group.go`：公开群成员查询，透传 mentions、caption、mimetype。
- `internal/service/app/waapp.go`、`api/service/message.go`：迁移图片/链接签名且保持 HTTP 行为。
- `internal/service/node/node_processor.go`：使用纯 builder 产生包含 `ContextInfo.MentionedJID` 的 payload。

明确不修改数据库 schema，不新建 HTTP 营销接口，不从 `protocolAccountId` 推导手机号，不改变 Web 协议行为。

## Task 1: 通用 envelope 分发与消息命令解析

**Files:**

- Modify: `internal/armada/command.go`
- Modify: `internal/armada/command_test.go`
- Create: `internal/armada/message_command.go`
- Create: `internal/armada/message_command_test.go`

- [ ] **Step 1: 写通用 envelope 和五种类型失败测试**

测试覆盖：生命周期 fixture 解析结果不变；`message.send.requested` 能按 `commandType` 分流；TEXT、LINK、IMAGE、LINK_CARD、BUTTON_CARD 全部解析；未知类型、空 `wsPhone`、非 `@g.us` 目标、非法 base64、BUTTON_CARD 非一个 link 按钮均返回 `ErrInvalidCommand`；日志错误不包含正文、图片 base64、完整手机号。

```go
func TestParseMessageCommandParsesFlatCorrelation(t *testing.T) {
	raw := []byte(`{
	  "commandId":"cmd_1",
	  "commandType":"message.send.requested",
	  "protocolAccountId":"acc_android_1",
	  "payload":{
	    "tenantId":1,"accountId":2,"protocolAccountId":"acc_android_1",
	    "wsPhone":"8613800138000","groupJid":"120363001@g.us",
	    "messageType":"TEXT","text":"hello","mentionAll":true,
	    "source":"marketing_task","marketingTaskId":42,"targetId":501,
	    "attemptId":9001,"roundNo":1
	  }
	}`)

	command, err := ParseMessageCommand(raw)
	if err != nil {
		t.Fatal(err)
	}
	if command.Payload.MarketingTaskID != 42 || command.Payload.WSPhone != "8613800138000" {
		t.Fatalf("unexpected command: %#v", command)
	}
}
```

- [ ] **Step 2: 运行并确认测试先失败**

```bash
go test ./internal/armada -run 'TestParse(MessageCommand|Command)'
```

Expected: FAIL，消息命令类型和解析器尚不存在。

- [ ] **Step 3: 抽取只解析信封的模型**

在 `command.go` 定义并复用：

```go
type RawProtocolCommand struct {
	CommandID         string          `json:"commandId"`
	BatchID           string          `json:"batchId"`
	CommandType       string          `json:"commandType"`
	AggregateType     string          `json:"aggregateType"`
	AggregateID       int64           `json:"aggregateId"`
	ProtocolAccountID string          `json:"protocolAccountId"`
	Payload           json.RawMessage `json:"payload"`
}

func ParseRawProtocolCommand(raw []byte) (RawProtocolCommand, error)
```

`ParseCommand` 先调用该函数，再只反序列化生命周期 payload；原有 `ProtocolCommand`、错误分类、必填字段和测试全部保留，不新增兼容分支来掩盖非法数据。

- [ ] **Step 4: 实现消息命令 wire model 与严格校验**

```go
const CommandTypeMessageSendRequested = "message.send.requested"

type MessageCommand struct {
	CommandID         string
	CommandType       string
	ProtocolAccountID string
	Payload           MessageCommandPayload
}

type MessageCommandPayload struct {
	TenantID                int64              `json:"tenantId"`
	AccountID               int64              `json:"accountId"`
	ProtocolAccountID       string             `json:"protocolAccountId"`
	WSPhone                 string             `json:"wsPhone"`
	GroupJID                string             `json:"groupJid"`
	MessageType             string             `json:"messageType"`
	Text                    string             `json:"text"`
	Image                   *MessageImage      `json:"image"`
	LinkCard                *MessageLinkCard   `json:"linkCard"`
	ButtonCard              *MessageButtonCard `json:"buttonCard"`
	MentionAll              bool               `json:"mentionAll"`
	Source                  string             `json:"source"`
	MarketingTaskID         int64              `json:"marketingTaskId"`
	TargetID                int64              `json:"targetId"`
	AttemptID               int64              `json:"attemptId"`
	RoundNo                 int64              `json:"roundNo"`
	GroupCreationTaskID     int64              `json:"groupCreationTaskId"`
	GroupCreationItemID     int64              `json:"groupCreationItemId"`
}

type MessageImage struct {
	Base64   string `json:"base64"`
	Mimetype string `json:"mimetype"`
}

type MessageLinkCard struct {
	URL         string        `json:"url"`
	Title       string        `json:"title"`
	Description string        `json:"description"`
	Thumbnail   *MessageImage `json:"thumbnail"`
}

type MessageButtonCard struct {
	Title     string          `json:"title"`
	Footer    string          `json:"footer"`
	Buttons   []MessageButton `json:"buttons"`
	Thumbnail *MessageImage   `json:"thumbnail"`
}

type MessageButton struct {
	Type        string `json:"type"`
	DisplayText string `json:"displayText"`
	Value       string `json:"value"`
}
```

普通营销必须同时具备四个普通关联 ID；`source=group_creation_marketing` 必须具备两个建群关联 ID。Android 再做一次单按钮防御校验：数量恰好 1、`type=link`、显示文字非空、URL 为绝对 `http/https`。IMAGE/卡片缩略图只在字段存在时解码，解析器不把 base64 内容写入错误。

- [ ] **Step 5: 运行 package 测试并提交**

```bash
go test ./internal/armada -run 'TestParse(MessageCommand|Command)'
git add internal/armada/command.go internal/armada/command_test.go \
  internal/armada/message_command.go internal/armada/message_command_test.go
git commit -m "feat: parse android marketing message commands"
```

Expected: PASS，提交只包含四个列出的文件。

## Task 2: 消息结果事件、publisher 与配置

**Files:**

- Create: `internal/armada/message_event.go`
- Create: `internal/armada/message_event_test.go`
- Create: `internal/armada/message_publisher.go`
- Create: `internal/armada/message_publisher_test.go`
- Modify: `internal/armada/options.go`
- Modify: `internal/armada/options_test.go`
- Modify: `internal/armada/config.go`
- Modify: `internal/configs/configs.go`
- Modify: `internal/configs/configs_test.go`
- Modify: `configs/prod_configs_example.toml`
- Modify: `deploy/configs/prod_configs.example.toml`

- [ ] **Step 1: 写事件契约和默认配置失败测试**

测试断言默认 `MessageCommandsEnabled=false`、`MessageEventTopic=protocol.message.events.v1`、默认 `MessageProcessingTimeout=2m`；publisher 的 topic 正确、key 为 `protocolAccountId`；成功、原生失败、`SEND_RESULT_UNKNOWN` 三类事件序列化字段与 Armada consumer 完全一致。

```go
func TestBuildMessageResultEventUsesStableIdentity(t *testing.T) {
	event, err := BuildMessageResultEvent(commandFixture(), MessageSendResult{
		Success: true, MessageID: "wamid.1",
	}, "worker-a", time.UnixMilli(1783159200000))
	if err != nil {
		t.Fatal(err)
	}
	if event.EventID != "acc_android_1:message.send_result_reported:cmd_1" {
		t.Fatalf("event ID = %q", event.EventID)
	}
	if event.Data.GroupStatus != "UNCONFIRMED" ||
		event.Data.GroupStatusReason != "STATUS_RESOLUTION_UNAVAILABLE" {
		t.Fatalf("group status = %#v", event.Data)
	}
}
```

- [ ] **Step 2: 运行并确认测试先失败**

```bash
go test ./internal/armada ./internal/configs -run 'Test(BuildMessageResultEvent|MessageEventPublisher|NormalizeOptions|OptionsFromConfig|LoadConfig)'
```

Expected: FAIL，新事件和配置字段不存在。

- [ ] **Step 3: 实现独立消息事件模型**

不要扩展账号状态 `EventEnvelope` 的 data 联合体；新增专用模型，避免两个 topic 的字段相互污染：

```go
type MessageSendResult struct {
	Success       bool   `json:"success"`
	MessageID     string `json:"messageId,omitempty"`
	ReasonCode    string `json:"reasonCode,omitempty"`
	ReasonMessage string `json:"reasonMessage,omitempty"`
}

type MessageResultEventEnvelope struct {
	EventID    string                 `json:"eventId"`
	Event      string                 `json:"event"`
	Version    string                 `json:"version"`
	AccountID  string                 `json:"accountId"`
	OccurredAt string                 `json:"occurredAt"`
	WorkerID   string                 `json:"workerId"`
	Data       MessageResultEventData `json:"data"`
}

type MessageResultEventData struct {
	TenantID              int64  `json:"tenantId"`
	AccountID             int64  `json:"accountId,omitempty"`
	MarketingTaskID       int64  `json:"marketingTaskId,omitempty"`
	TargetID              int64  `json:"targetId,omitempty"`
	AttemptID             int64  `json:"attemptId,omitempty"`
	RoundNo               int64  `json:"roundNo,omitempty"`
	ProtocolAccountID     string `json:"protocolAccountId"`
	GroupJID              string `json:"groupJid"`
	CommandID             string `json:"commandId"`
	Success               bool   `json:"success"`
	MessageID             string `json:"messageId,omitempty"`
	ReasonCode            string `json:"reasonCode,omitempty"`
	ReasonMessage         string `json:"reasonMessage,omitempty"`
	Timestamp             int64  `json:"timestamp"`
	GroupCreationTaskID   int64  `json:"groupCreationTaskId,omitempty"`
	GroupCreationItemID   int64  `json:"groupCreationItemId,omitempty"`
	Source                 string `json:"source,omitempty"`
	GroupStatus            string `json:"groupStatus"`
	GroupStatusReason      string `json:"groupStatusReason"`
	GroupStatusCheckedAt   int64  `json:"groupStatusCheckedAt"`
}
```

`eventId` 固定为 `<protocolAccountId>:message.send_result_reported:<commandId>`；`occurredAt` 用 UTC RFC3339Nano；`timestamp/groupStatusCheckedAt` 用同一个 epoch 毫秒；无法可靠判断群状态时明确写 `UNCONFIRMED/STATUS_RESOLUTION_UNAVAILABLE`。

- [ ] **Step 4: 实现 publisher 并复用 transport**

`MessageEventPublisher` 复用 `MessageWriter`/`KafkaMessageWriter`，但独立持有 writer 和 topic；`Publish` 校验 topic、protocolAccountId、commandId 后序列化，绝不打印原始事件或正文。

```go
type MessageResultEventWriter interface {
	Publish(context.Context, MessageResultEventEnvelope) error
	Close() error
}
```

- [ ] **Step 5: 扩展 Options 和 TOML**

增加：

```go
MessageCommandsEnabled   bool
MessageEventTopic        string
MessageProcessingTimeout time.Duration
```

TOML 字段使用 `messagecommandsenabled`、`messageeventtopic` 和 `messageprocessingtimeoutseconds`。消息开关不做自动开启，默认始终为 false；零值默认 topic `protocol.message.events.v1`、timeout `120s`；timeout 小于等于 0（非零负值）返回配置错误。更新两份示例文件的说明，明确同一 `commandtopic` 同时承载生命周期和营销消息。

- [ ] **Step 6: 运行测试并提交**

```bash
go test ./internal/armada ./internal/configs -run 'Test(BuildMessageResultEvent|MessageEventPublisher|NormalizeOptions|OptionsFromConfig|LoadConfig)'
git add internal/armada/message_event.go internal/armada/message_event_test.go \
  internal/armada/message_publisher.go internal/armada/message_publisher_test.go \
  internal/armada/options.go internal/armada/options_test.go internal/armada/config.go \
  internal/configs/configs.go internal/configs/configs_test.go \
  configs/prod_configs_example.toml deploy/configs/prod_configs.example.toml
git commit -m "feat: publish android marketing message results"
```

Expected: PASS；两个示例 TOML 仍保持 `enabled=false`。

## Task 3: Redis 命令状态机

**Files:**

- Create: `internal/armada/message_state.go`
- Create: `internal/armada/message_state_test.go`

- [ ] **Step 1: 用 miniredis 写状态迁移失败测试**

覆盖以下真值表：

| 当前状态 | handler 行为 | 是否调用原生发送 |
|---|---|---:|
| key 不存在 | 原子 claim 为 PROCESSING | 是 |
| 新鲜 PROCESSING | 返回可重试错误 | 否 |
| 超时 PROCESSING | CAS 存储 `SEND_RESULT_UNKNOWN` | 否 |
| RESULT_STORED | 返回已存结果供重新发布 | 否 |
| PUBLISHED | 直接完成 | 否 |

还要断言 Redis JSON 不含 text、base64、thumbnail、按钮 URL，只存 phase、updatedAt 和必要结果；每次写入 TTL 都等于 `ContextTTL`。

- [ ] **Step 2: 运行并确认测试先失败**

```bash
go test ./internal/armada -run 'TestRedisMessageCommandStateStore'
```

Expected: FAIL，状态存储不存在。

- [ ] **Step 3: 定义状态和 store 接口**

```go
type MessageCommandPhase string

const (
	MessagePhaseProcessing   MessageCommandPhase = "PROCESSING"
	MessagePhaseResultStored MessageCommandPhase = "RESULT_STORED"
	MessagePhasePublished    MessageCommandPhase = "PUBLISHED"
)

type StoredMessageCommandState struct {
	Phase     MessageCommandPhase `json:"phase"`
	UpdatedAt int64               `json:"updatedAt"`
	ResultAt  int64               `json:"resultAt,omitempty"`
	Result    *MessageSendResult  `json:"result,omitempty"`
}

type MessageCommandStateStore interface {
	Claim(context.Context, string, time.Time) (MessageCommandClaim, error)
	StoreResult(context.Context, string, MessageSendResult, time.Time) error
	MarkPublished(context.Context, string, time.Time) error
}
```

key 固定为 `armada:zhuan:message:command:<commandId>`。`Claim` 使用 `SET NX`；未抢到时读取并分类。`StoreResult` 和 `MarkPublished` 分别使用 Lua compare-and-set，禁止无条件覆盖并发状态。

- [ ] **Step 4: 实现超时 PROCESSING 的不重发策略**

当 `now-updatedAt >= MessageProcessingTimeout`，Lua 只允许把同一个 PROCESSING 快照改为 RESULT_STORED：

```go
MessageSendResult{
	Success:       false,
	ReasonCode:    "SEND_RESULT_UNKNOWN",
	ReasonMessage: "发送进程中断，无法确认 WhatsApp 是否已接收；为避免重复触达不自动重发",
}
```

fresh PROCESSING 返回一个可由 consumer 重试的 sentinel error；stale PROCESSING 返回已存 unknown 结果，executor 只发布事件。Redis/传输错误不得被包装成永久命令错误。

- [ ] **Step 5: 运行测试并提交**

```bash
go test ./internal/armada -run 'TestRedisMessageCommandStateStore'
git add internal/armada/message_state.go internal/armada/message_state_test.go
git commit -m "feat: persist android message command state"
```

Expected: PASS，竞态测试证明只有一次 claim 成功。

## Task 4: 原生 protobuf 支持 caption、单 CTA 和真实 mentions

**Files:**

- Create: `internal/service/entity/message_context.go`
- Create: `internal/service/entity/message_context_test.go`
- Create: `internal/service/node/message_payload.go`
- Create: `internal/service/node/message_payload_test.go`
- Modify: `internal/service/entity/message.go`
- Modify: `internal/service/app/group.go`
- Modify: `internal/service/app/waapp.go`
- Modify: `internal/service/node/node_processor.go`
- Modify: `api/service/message.go`
- Create: `api/service/message_test.go`

- [ ] **Step 1: 写 protobuf 纯 builder 失败测试**

测试直接反序列化 builder 产出的 `waproto.Message`，不连 WhatsApp：

- TEXT 的 `ExtendedTextMessage.ContextInfo.MentionedJID` 与传入 JID 完全一致；
- IMAGE 同时包含 `Caption`、传入 mimetype 和 mention JID；
- LINK_CARD 的 external ad reply 保留 title/description/url/thumbnail，context 同时保留 mention JID；
- BUTTON_CARD 的 native flow 只有一个 `cta_url`，JSON 中 `display_text/url/merchant_url` 为业务值；有缩略图时带 image header，无缩略图时不解引用空 `MediaInfo`；interactive context 含 mention JID；
- 零 mentions 时不生成伪 `@all` 或空 JID。

```go
func TestBuildButtonCardPayloadUsesRequestedCTA(t *testing.T) {
	payload, err := BuildLinkGroupPayload(&entity.HyperLinkMessage{
		Template: "2", Body: "body", Footer: "footer",
		URL: "https://example.com/path", ButtonText: "查看详情",
	}, []jabber.JID{jabber.NewJID("8613800138001")}, []byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	button := payload.GetViewOnceMessage().GetMessage().GetInteractiveMessage().
		GetNativeFlowMessage().GetButtons()[0]
	if button.GetName() != "cta_url" ||
		button.GetButtonParamsJSON() != `{"display_text":"查看详情","url":"https://example.com/path","merchant_url":"https://example.com/path"}` {
		t.Fatalf("button = %#v", button)
	}
}
```

- [ ] **Step 2: 运行并确认测试先失败**

```bash
go test ./internal/service/entity ./internal/service/node -run 'Test(MentionContextInfo|Build.*GroupPayload)'
```

Expected: FAIL，helper/builder 尚不存在，现有 button 文案仍硬编码。

- [ ] **Step 3: 实现 mention context helper**

```go
func MentionContextInfo(mentioned []jabber.JID) *waproto.ContextInfo {
	if len(mentioned) == 0 {
		return nil
	}
	values := make([]string, 0, len(mentioned))
	seen := make(map[string]struct{}, len(mentioned))
	for _, jid := range mentioned {
		value := strings.TrimSpace(jid.ToNonAD().String())
		if value == "" {
			continue
		}
		if _, ok := seen[value]; ok {
			continue
		}
		seen[value] = struct{}{}
		values = append(values, value)
	}
	if len(values) == 0 {
		return nil
	}
	return &waproto.ContextInfo{MentionedJID: values}
}
```

卡片 builder 若已有 `ContextInfo`，在同一对象上同时设置 `MentionedJID` 与 `ExternalAdReply`/entry point 字段，不能用后赋值覆盖其中一组。

- [ ] **Step 4: 抽取四类群消息 payload builder**

在 `internal/service/node/message_payload.go` 提供：

```go
func BuildTextGroupPayload(text string, mentioned []jabber.JID) (*waproto.Message, error)
func BuildImageGroupPayload(text, mimetype string, fileBytes []byte,
	mentioned []jabber.JID, info *entity.MediaDownloadInfo) (*waproto.Message, error)
func BuildLinkGroupPayload(link *entity.HyperLinkMessage,
	mentioned []jabber.JID, messageSecret []byte) (*waproto.Message, error)
```

链接和按钮共用 `BuildLinkGroupPayload`，按 `Template=1/2` 选择 wire；不保留 `Template=3` 的营销入口，但原 HTTP 若仍调用模板 3，现有分支及行为继续保留。`HyperLinkMessage` 增加 `ButtonText` 和 `Footer`；BUTTON_CARD 无缩略图时构造无 media header 的 interactive message。

- [ ] **Step 5: 修改 app/node 签名并保持 HTTP 兼容**

统一签名：

```go
func (w *WaApp) GroupParticipants(groupID jabber.JID) ([]string, error)
func (w *WaApp) SendGroupImageMessage(groupID jabber.JID, text string,
	fileBytes []byte, mimetype string, mentionedUsers []string) (*msg.MySendMsg, error)
func (w *WaApp) SendGroupLinkMessage(groupID jabber.JID,
	hyperLink *entity.HyperLinkMessage, mentionedUsers []string) (*msg.MySendMsg, error)
```

`getGroupParticipants` 重命名为公开方法，`group.go` 内所有调用同步迁移。`SendLinkGroupMessage` 增加 `mentionedUsers []jabber.JID` 参数；text/image/link 的 protobuf 全部由纯 builder 创建，`nodes.BuildGroupMessage` 中的 `mentioned_users` meta 仍只服务广播，真实群 mention 依赖 protobuf `ContextInfo.MentionedJID`。

迁移三个既有调用点：

- HTTP 图片发送传 `image/jpeg` 和原 mentions；
- status broadcast 图片透传 `image/jpeg`；
- HTTP 超链传 nil mentions，并设置 `ButtonText: "click here"`，确保旧 API 模板 2 的界面行为不变。

- [ ] **Step 6: 运行原生层与 HTTP 回归**

```bash
gofmt -w internal/service/entity/message_context.go internal/service/entity/message_context_test.go \
  internal/service/node/message_payload.go internal/service/node/message_payload_test.go \
  internal/service/entity/message.go internal/service/app/group.go internal/service/app/waapp.go \
  internal/service/node/node_processor.go api/service/message.go api/service/message_test.go
go test ./internal/service/entity ./internal/service/node ./internal/service/app ./api/service
```

Expected: PASS；若 `api/service/message_test.go` 原先不存在，新增测试至少断言 HTTP DTO 映射仍使用 `click here` 且 nil mentions。

- [ ] **Step 7: 精确暂存并提交**

```bash
git add internal/service/entity/message_context.go internal/service/entity/message_context_test.go \
  internal/service/node/message_payload.go internal/service/node/message_payload_test.go \
  internal/service/entity/message.go internal/service/app/group.go internal/service/app/waapp.go \
  internal/service/node/node_processor.go api/service/message.go api/service/message_test.go
git diff --cached --name-only
git commit -m "feat: support native android marketing message payloads"
```

Expected: staged file list 不含 `internal/armada/group_snapshot_coordinator*`。

## Task 5: 五种消息 sender 与 `@all` 编排

**Files:**

- Create: `internal/armada/message_sender.go`
- Create: `internal/armada/message_sender_test.go`

- [ ] **Step 1: 写 fake client 的五类消息失败测试**

Fake 不连接网络，记录调用方法、目标群、正文、图片 bytes/mimetype、卡片字段和 mentions。覆盖：

- TEXT 和 LINK 都调用文本发送，LINK 保留 URL；
- IMAGE 解码 bytes 并把 text 作为 caption；
- LINK_CARD 使用模板 1；
- BUTTON_CARD 使用模板 2，单按钮显示文案和 URL 原样透传，可选 thumbnail 不崩溃；
- `mentionAll=false` 不查成员；
- `mentionAll=true` 查一次成员，去空、去重、排除自己，转成 `@s.whatsapp.net`，正文只前缀一次 `@all\n`；
- 群成员查询失败返回 `MENTION_ALL_RESOLUTION_FAILED`，不调用任何发送方法；
- `wsPhone` 找不到在线 app 返回 `ACCOUNT_OFFLINE`；
- 原生 ACK 成功的 `MySendMsg.Id` 作为结果 messageId，空 ID 当作 `EMPTY_MESSAGE_ID` 失败。

```go
func TestZhuanMessageSenderMentionAllUsesRealParticipants(t *testing.T) {
	client := &fakeZhuanMessageClient{
		self: "8613800138000",
		participants: []string{
			"8613800138000", "8613800138001", "8613800138001@s.whatsapp.net", " ",
		},
	}
	sender := &ZhuanMessageSender{Resolver: fixedResolver(client)}

	result := sender.Send(context.Background(), textCommand(true, "hello"))

	if !result.Success || result.MessageID != "wamid.1" {
		t.Fatalf("result = %#v", result)
	}
	if diff := cmp.Diff([]string{"8613800138001@s.whatsapp.net"}, client.mentions); diff != "" {
		t.Fatal(diff)
	}
	if client.text != "@all\nhello" {
		t.Fatalf("text = %q", client.text)
	}
}
```

若仓库未依赖 `go-cmp`，使用标准库逐项断言，不为此新增依赖。

- [ ] **Step 2: 运行并确认测试先失败**

```bash
go test ./internal/armada -run 'TestZhuanMessageSender'
```

Expected: FAIL，sender 尚不存在。

- [ ] **Step 3: 定义可替换 client 和生产 resolver**

```go
type ZhuanMessageClient interface {
	SelfUser() string
	GroupParticipants(jabber.JID) ([]string, error)
	SendText(jabber.JID, string, []string) (string, error)
	SendImage(jabber.JID, string, []byte, string, []string) (string, error)
	SendLinkCard(jabber.JID, string, MessageLinkCard, []string) (string, error)
	SendButtonCard(jabber.JID, string, MessageButtonCard, []string) (string, error)
}

type ZhuanMessageClientResolver interface {
	Resolve(wsPhone string) (ZhuanMessageClient, error)
}
```

生产 resolver 只调用 `app.GetValidWSApp(strings.TrimSpace(wsPhone))`。包装器把 `WaApp` 的 `*msg.MySendMsg` 收窄成 message ID；卡片缩略图存在时才通过 `QueryMediaConn` + `media.UploadFor` 上传并填充 `MediaInfo`，BUTTON_CARD 无缩略图不上传。

- [ ] **Step 4: 实现安全的 mention-all 解析**

先验证 `groupJid.Server == jabber.GroupServer`，再查询成员；每个成员用 `jabber.ParseJID` 或 `jabber.NewJID(phone, jabber.DefaultUserServer)` 规范化，去重并过滤 `SelfUser()`。任一非空成员无法解析时整体失败，不静默少提醒。查询成功但有效成员为空时允许发送带 `@all` 前缀且 mention 列表为空，因为该群可能只有自己。

`prefixMentionAll` 必须幂等：去除左侧空白后已经以独立行 `@all` 开头时不再添加；否则返回 `@all\n` + 原正文。图片 caption、LINK_CARD text、BUTTON_CARD body 都遵循同一规则。

- [ ] **Step 5: 实现五类分派和稳定错误码**

复用 Task 2 定义的 `MessageSendResult`。类型到原生调用映射固定为：TEXT/LINK -> `SendText`，IMAGE -> `SendImage`，LINK_CARD -> `SendLinkCard`，BUTTON_CARD -> `SendButtonCard`。卡片调用的第二个参数使用 payload 顶层 `text`，作为链接卡片正文或按钮卡片 body，并参与 `@all` 幂等前缀处理。业务/协议错误转换成失败 result；context 取消、Redis 和 Kafka 错误不在 sender 内吞掉。reason message 不含完整 `wsPhone`、base64 或消息正文。

- [ ] **Step 6: 运行测试并提交**

```bash
gofmt -w internal/armada/message_sender.go internal/armada/message_sender_test.go
go test ./internal/armada -run 'TestZhuanMessageSender'
git add internal/armada/message_sender.go internal/armada/message_sender_test.go
git commit -m "feat: send android marketing message types"
```

Expected: PASS，五种类型和 mention-all 失败路径都有断言。

## Task 6: 幂等 executor 与统一 command handler

**Files:**

- Create: `internal/armada/message_executor.go`
- Create: `internal/armada/message_executor_test.go`
- Modify: `internal/armada/start.go`
- Modify: `internal/armada/start_test.go`
- Modify: `internal/armada/consumer_test.go`

- [ ] **Step 1: 写副作用顺序和重试失败测试**

用 recording state store/sender/publisher 断言严格顺序：`Claim -> Send -> StoreResult -> Publish -> MarkPublished`。再覆盖：

- PUBLISHED：五个依赖中只调用 Claim；
- RESULT_STORED：只 Publish/MarkPublished，不 Send；
- stale PROCESSING：发布 `SEND_RESULT_UNKNOWN`，不 Send；
- fresh PROCESSING：返回普通可重试错误，不 Publish、不提交 offset；
- Send 失败：失败结果先 Store 再 Publish，handler 成功后 consumer 提交；
- Publish 失败：返回普通错误，Kafka 保持当前消息；下次从 RESULT_STORED 重发同一 eventId，不重发 WhatsApp；
- StoreResult 失败：不 Publish；重试期间不再调用 Send，超时后走 unknown；
- 格式非法但可提取 commandId/账号/群/关联字段：以 `INVALID_MESSAGE_PAYLOAD` 走同一状态机发布失败；
- 连关联字段也无法提取：`PermanentCommand`，安全日志后提交，不能构造错误归属事件；
- 生命周期 fixture 继续进入原 `LifecycleExecutor`。

- [ ] **Step 2: 运行并确认测试先失败**

```bash
go test ./internal/armada -run 'Test(MessageCommandExecutor|UnifiedCommandHandler|CommandConsumer.*Message)'
```

Expected: FAIL，executor 和统一 handler 尚不存在。

- [ ] **Step 3: 实现 executor 状态机**

```go
type MessageCommandExecutor struct {
	States    MessageCommandStateStore
	Sender    *ZhuanMessageSender
	Events    MessageResultEventWriter
	WorkerID  string
	Now       func() time.Time
}
```

`Execute` 先 claim；只有 claim 返回 NEW 才调用 sender。sender result 无论成功失败都必须先存 Redis，再构造事件并发布，最后 CAS 标记 PUBLISHED。构造 event 的时间在结果首次存储时固定，并放入 Stored state，确保 RESULT_STORED 重发的 JSON 语义和 eventId 不漂移。

- [ ] **Step 4: 为非法 payload 增加最小关联提取**

在 `message_command.go` 增加：

```go
type MessageCommandReference struct {
	CommandID         string
	ProtocolAccountID string
	Payload           MessageCommandPayload
}

func ParseMessageCommandReference(raw []byte) (MessageCommandReference, error)
```

它只接受能唯一归属结果的字段：commandId、tenantId、accountId、protocolAccountId、groupJid，以及与 source 对应的关联 ID；不要求消息内容合法。统一 handler 在严格 Parse 失败后调用该函数，构造 `INVALID_MESSAGE_PAYLOAD` result 交给 executor 的 `ExecuteRejected`，仍保证 Redis 幂等。

- [ ] **Step 5: 实现按 commandType 分发的统一 handler**

```go
func newCommandHandler(lifecycle *LifecycleExecutor,
	messages *MessageCommandExecutor, messageCommandsEnabled bool) CommandHandler {
	return func(ctx context.Context, raw []byte) error {
		envelope, err := ParseRawProtocolCommand(raw)
		if err != nil {
			return PermanentCommand(err)
		}
		switch envelope.CommandType {
		case CommandTypeAccountOnlineRequested, CommandTypeAccountOfflineRequested:
			return handleLifecycleCommand(ctx, lifecycle, raw)
		case CommandTypeMessageSendRequested:
			if !messageCommandsEnabled {
				return ErrMessageCommandsDisabled
			}
			return handleMessageCommand(ctx, messages, raw)
		default:
			return PermanentCommand(&CommandValidationError{Field: "commandType", Reason: "is unsupported"})
		}
	}
}
```

`ErrMessageCommandsDisabled` 是普通可重试错误，consumer 不提交该消息；配置开启并重启后继续处理同一 offset。这样首轮部署不会误消费 Android 营销命令；因此部署顺序必须保证 Armada Android backend 在开关关闭期间不生产消息，避免同分区生命周期命令被前置营销消息暂时阻塞。把现有 `newLifecycleCommandHandler` 的主体抽为 `handleLifecycleCommand`，不改变生命周期错误分类。日志只记录 commandId、messageType、source、掩码账号和错误类别。

- [ ] **Step 6: 运行聚焦测试并提交**

```bash
gofmt -w internal/armada/message_command.go internal/armada/message_command_test.go \
  internal/armada/message_executor.go internal/armada/message_executor_test.go \
  internal/armada/start.go internal/armada/start_test.go internal/armada/consumer_test.go
go test ./internal/armada -run 'Test(MessageCommandExecutor|UnifiedCommandHandler|CommandConsumer.*Message|Lifecycle)'
git add internal/armada/message_command.go internal/armada/message_command_test.go \
  internal/armada/message_executor.go internal/armada/message_executor_test.go \
  internal/armada/start.go internal/armada/start_test.go internal/armada/consumer_test.go
git commit -m "feat: execute android message commands idempotently"
```

Expected: PASS；重复 delivery 测试中原生 sender 调用次数始终为 1。

## Task 7: Start 装配、资源回收与默认关闭门禁

**Files:**

- Modify: `internal/armada/start.go`
- Modify: `internal/armada/start_test.go`
- Modify: `internal/armada/options_test.go`

- [ ] **Step 1: 写启动装配失败测试**

在现有 Start/factory 测试结构中增加：

- `MessageCommandsEnabled=false` 时消息 executor/publisher 不创建，生命周期 handler 正常；遇到 message command 返回 `ErrMessageCommandsDisabled` 且不提交；
- 开关 true 时创建 Redis message state store、Zhuan sender、message publisher，并把统一 handler 交给所有 consumer runner；
- message publisher 创建失败时按反向顺序注销 callback/group runtime、取消 context、关闭 account writer；
- reader/runner 创建失败时同时关闭 account 和 message writers；
- 正常 Stop 幂等，consumer 停止后两个 writer 各 Close 一次；
- 现有 group snapshot observer 注册、停止顺序测试不变。

- [ ] **Step 2: 运行并确认测试先失败**

```bash
go test ./internal/armada -run 'Test(Start|StartWithFactory|BuildConsumerRunners|NormalizeOptions.*Message)'
```

Expected: FAIL，Start 尚未装配消息运行时或关闭第二个 writer。

- [ ] **Step 3: 仅在消息开关开启时构造消息运行时**

在 Redis、account event writer 创建成功后，按以下依赖组装：

```go
messageStates := NewRedisMessageCommandStateStore(
	redisClient,
	normalized.ContextTTL,
	normalized.MessageProcessingTimeout,
)
messageEvents := NewKafkaMessageEventPublisher(
	normalized.Brokers,
	normalized.MessageEventTopic,
	normalized.SecurityProtocol,
)
messageExecutor := &MessageCommandExecutor{
	States:   messageStates,
	Sender:   NewZhuanMessageSender(NewWaAppMessageClientResolver()),
	Events:   messageEvents,
	WorkerID: normalized.WorkerID,
	Now:      time.Now,
}
handler := newCommandHandler(lifecycleExecutor, messageExecutor, true)
```

开关为 false 时 `messageExecutor=nil`、不创建 message writer，并调用 `newCommandHandler(lifecycleExecutor, nil, false)`。不要复制第二套 Kafka reader：生命周期和消息继续共享 `CommandTopic`、consumer group、显式提交与同分区顺序。

- [ ] **Step 4: 收敛所有失败和 Stop 路径**

使用局部 `closeMessageEvents()` 空操作函数统一条件关闭，最终 Stop 顺序固定：注销 callback observer -> 停 group runtime -> cancel adapter -> 停 consumer -> 关闭 message writer -> 关闭 account writer。使用 `errors.Join` 保留全部关闭错误，不因第一个 Close 失败跳过后续资源。

启动日志增加低敏字段：`messageCommandsEnabled`、`messageEventTopic`、`messageProcessingTimeout`；开关关闭时不记录不存在的 writer。不得输出 brokers 明文列表、账号或正文。

- [ ] **Step 5: 运行 armada package 完整测试并提交**

```bash
gofmt -w internal/armada/start.go internal/armada/start_test.go internal/armada/options_test.go
go test ./internal/armada
git add internal/armada/start.go internal/armada/start_test.go internal/armada/options_test.go
git commit -m "feat: wire android marketing message runtime"
```

Expected: PASS；`git status --short` 仍显示但未暂存群快照并行修改。

## Task 8: 跨层回归、契约对账与灰度门禁

**Files:**

- Modify: `/Users/daishuaishuai/IdeaProjects/armada/.harness/changes/2026-07-15-android-marketing-message-kafka.md`
- Test: Android Zhuan 本计划涉及的所有 package
- Test: Armada `ProtocolMessageEventConsumerTest`

- [ ] **Step 1: 运行格式、聚焦测试和静态检查**

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
gofmt -w internal/armada/command.go internal/armada/message_command.go \
  internal/armada/message_event.go internal/armada/message_publisher.go \
  internal/armada/message_state.go internal/armada/message_sender.go \
  internal/armada/message_executor.go internal/armada/options.go internal/armada/config.go \
  internal/armada/start.go internal/configs/configs.go \
  internal/service/entity/message.go internal/service/entity/message_context.go \
  internal/service/app/group.go internal/service/app/waapp.go \
  internal/service/node/message_payload.go internal/service/node/node_processor.go \
  api/service/message.go
go test ./internal/armada ./internal/configs ./internal/service/entity \
  ./internal/service/node ./internal/service/app ./api/service
go vet ./internal/armada ./internal/configs ./internal/service/entity \
  ./internal/service/node ./internal/service/app ./api/service
```

Expected: 所有命令退出 0。只格式化本计划文件，不执行仓库级 `gofmt`。

- [ ] **Step 2: 运行完整 Go 回归**

```bash
go test ./...
```

Expected: PASS；记录 packages 数、失败/跳过情况。若失败，先按系统化调试定位根因，不把既有失败直接归为无关。

- [ ] **Step 3: 做安全数据扫描和工作区检查**

```bash
rg -n 'imageBase64|"base64"|8613800138000|营销正文' internal/armada --glob '*.go'
git diff --check
git status --short
git diff -- internal/armada internal/configs configs deploy/configs \
  internal/service/entity internal/service/app internal/service/node api/service
```

Expected: base64 只出现在命令 wire/校验测试，不出现在 Redis state、事件、日志字段；测试手机号只出现在 `_test.go`；`git diff --check` 无输出；diff 不包含群快照两个在途文件的任何本次编辑。

- [ ] **Step 4: 固定三类 Android 结果 fixture 并让 Armada 消费**

从 `message_event_test.go` 的 JSON fixture 复制成功、`MENTION_ALL_RESOLUTION_FAILED`、`SEND_RESULT_UNKNOWN` 三类事件到 Armada `ProtocolMessageEventConsumerTest` 参数化测试，分别覆盖普通营销和建群营销。运行：

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=ProtocolMessageEventConsumerTest,MarketingSendResultServiceImplTest test
```

Expected: PASS；Android 不需要专用 Controller/Service，现有 sink 能消费全部字段。

- [ ] **Step 5: 运行 Web 协议契约回归**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- src/commands/types.test.ts src/commands/worker-consumer.test.ts \
  src/messages/card-content.test.ts --runInBand
```

Expected: Jest 退出 0；Web 五类型、1–3 按钮及 mention-all 行为不变。

- [ ] **Step 6: 记录验收 evidence，不擅自部署**

更新 Armada change 记录，写入两仓 commit、实际测试命令/结果、Redis key TTL、消息开关默认 false、未运行的真库/远程验证及原因。然后只在 Armada 仓库暂存该文件：

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add .harness/changes/2026-07-15-android-marketing-message-kafka.md
git commit -m "docs: record android marketing message verification"
```

- [ ] **Step 7: 按确认环境执行灰度和回滚门禁**

没有用户明确确认目标环境时到此停止，不运行 SSH、部署或共享 Redis/Kafka 写入。确认测试环境后按顺序：

1. 部署 Android Zhuan，保持 `messagecommandsenabled=false`，验证生命周期命令不回退。
2. 部署 Armada routing/backend，但先关闭 Android 营销任务入口或确保没有 Android 目标出队。
3. 对账 command topic、message event topic、consumer group、Redis TTL 后，把 Android 开关改为 true 并重启。
4. 单 Android 账号依次灰度 TEXT、IMAGE caption、LINK、LINK_CARD、BUTTON_CARD、mentionAll。
5. 验证每条 command 的 Redis 终态为 PUBLISHED，Armada attempt/item 只收敛一次。

回滚顺序固定：先停止 Armada 生成新的 Android outbox，再把 Android `messagecommandsenabled` 关闭并重启；Web backend/topic 不变，已有 Android outbox 和 Redis 审计状态不删除、不改写。
