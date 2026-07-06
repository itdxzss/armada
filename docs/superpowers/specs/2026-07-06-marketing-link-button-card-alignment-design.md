# 营销普通超链/按钮超链卡片对齐 wheel - 设计规格

日期：2026-07-06

范围：`armada-api`、`armada-protocol`、`wheel-saas-pure-web`

## 背景

Armada 当前营销任务发送链路已经切到 outbox/Kafka 到协议层：

- `MarketingRoundWorker` 生成 `message.send.requested` 命令。
- `ProtocolCommandOutboxServiceImpl` 写出协议命令 payload。
- `armada-protocol` 消费 Kafka 后用 Baileys 发送 WhatsApp 消息。

现状问题是营销模板有三种消息方式，但发送侧没有完全对齐 wheel 旧链路：

- 普通超链：Armada 只生成 `LINK`，协议层最终仍是 `{ text }` 文本消息。
- 按钮超链：Armada 发送侧没有按钮分支，当前会落到 `TEXT`，按钮不会进入协议 payload。
- 图文：当前 `IMAGE_TEXT + 图片` 走 `IMAGE`，本次不调整。

wheel 已验证过的行为：

- `NORMAL + 有图 + http(s) 推广链接` 走 `sendLinkCard`，调用协议层 `/v1/messages/link-card`。
- `BUTTON + button_items` 走 `sendButtonCard`，调用协议层 `/v1/messages/button-card`。

本设计覆盖 Armada 对齐 wheel 的后端、协议层、前端保存校验。它替换 `2026-07-04-marketing-kafka-round-send-design.md` 中“按钮模板暂时降级文本”的旧描述。

## 目标

1. 保持 Armada 现有 outbox/Kafka 异步协议命令链路，不改回同步 HTTP 调用。
2. `NORMAL + 有图 + http(s) 推广链接` 发送真实 link card，协议层使用 WhatsApp link preview payload，而不是纯文本降级。
3. `BUTTON + 有效按钮` 发送真实 button card，按钮必须进入协议层 payload。
4. `BUTTON` 模板必须在前端和后端保存阶段要求 1-3 个有效按钮；没有按钮不允许保存。
5. 历史脏数据或运行时异常导致 `BUTTON` 无有效按钮时，不允许静默降级为文本，应作为模板配置错误处理，不发送错误形态的消息。
6. 保持现有任务轮次、发送尝试、结果回传和统计链路不变。

## 非目标

- 不把 Armada 营销任务发送改成直接调用 `/v1/messages/link-card` 或 `/v1/messages/button-card`。
- 不在本次引入 wheel 的 `phone` 按钮类型；Armada 仍只支持现有 `LINK_JUMP`、`COPY_CONTENT`、`QUICK_REPLY`。
- 不调整营销任务调度、轮次分组、发送间隔、outbox 发布状态模型。
- 不改变普通文本、普通图片、`IMAGE_TEXT` 的既有发送语义。

## 业务规则

### NORMAL

当同时满足以下条件时生成 `LINK_CARD`：

- 模板 `linkMode = NORMAL`。
- 存在可用图片文件和 mimetype。
- `promotionLink` 是 `http://` 或 `https://` 链接。

`LINK_CARD` 字段映射：

- `text`：优先使用 `content`；为空时使用 `bodyText`；再为空时使用推广链接。
- `url`：`promotionLink`。
- `title`：优先使用 `content`；为空时使用模板名称；再为空时使用推广链接。
- `description`：`bodyText`，允许为空。
- `thumbnail`：模板图片 bytes 和 mimetype。

协议层构造 WhatsApp link preview 时必须保证最终发送文本中包含 `url`，用于满足 link preview 的 matched text 要求。如果 `text` 不含 `url`，协议层在发送前追加该 URL。

不满足 link card 条件时沿用现有行为：

- 有 `promotionLink` 时继续生成 `LINK`，协议层按文本消息发送。
- 无 `promotionLink` 时生成 `TEXT`。

### BUTTON

保存阶段：

- 前端模板表单在 `linkMode = BUTTON` 时必须要求 1-3 个按钮。
- 后端模板保存接口必须继续校验 1-3 个按钮。
- 非 `BUTTON` 模板不允许携带按钮。

发送阶段：

- `linkMode = BUTTON` 且存在有效按钮时生成 `BUTTON_CARD`。
- `BUTTON` 没有有效按钮时视为模板配置错误，不生成 `TEXT` 降级消息。
- 如果模板存在图片，图片作为 button card header thumbnail；没有图片时发送无图 button card。

按钮映射：

| Armada 类型 | 协议按钮类型 | displayText | value |
| --- | --- | --- | --- |
| `LINK_JUMP` | `link` | `text` | `param` |
| `COPY_CONTENT` | `copy` | `text` | `param` |
| `QUICK_REPLY` | `quick` | `text` | 空 |

按钮有效性：

- `text` 必须非空。
- `LINK_JUMP` 的 `param` 必须是 `http://` 或 `https://`。
- `COPY_CONTENT` 的 `param` 必须非空。
- `QUICK_REPLY` 不要求 `param`。

### IMAGE_TEXT

保持现状：

- 有图片时生成 `IMAGE`。
- 没图片时生成 `TEXT`。

## Armada API 设计

### 消息类型

扩展营销发送内部消息类型：

- `TEXT`
- `LINK`
- `IMAGE`
- `LINK_CARD`
- `BUTTON_CARD`

`TEXT`、`LINK`、`IMAGE` payload 维持兼容；新增卡片消息使用结构化 payload，避免把按钮或图片压进纯文本。

### Composer

`MarketingMessageComposer` 负责从模板生成 `ComposedMessage`：

- `NORMAL` link card 条件成立时返回 `LINK_CARD`。
- `BUTTON` 按钮有效时返回 `BUTTON_CARD`。
- `BUTTON` 按钮无效时抛出模板配置异常。
- 其它分支保持现有行为。

`ComposedMessage` 增加可选结构：

```json
{
  "messageType": "LINK_CARD",
  "text": "测试123",
  "linkCard": {
    "url": "https://google.com",
    "title": "测试123",
    "description": "测试",
    "thumbnail": {
      "base64": "...",
      "mimetype": "image/png"
    }
  }
}
```

```json
{
  "messageType": "BUTTON_CARD",
  "text": "测试123",
  "buttonCard": {
    "title": "测试123",
    "footer": null,
    "buttons": [
      {
        "type": "link",
        "displayText": "立即领取",
        "value": "https://google.com"
      }
    ],
    "thumbnail": {
      "base64": "...",
      "mimetype": "image/png"
    }
  }
}
```

### Outbox Payload

`ProtocolMarketingMessageCommandRequest` 和 `ProtocolCommandOutboxServiceImpl` 需要序列化新增结构：

- 顶层保留现有任务追踪字段：`taskId`、`roundId`、`attemptId`、`recipient` 等。
- `messageType` 支持 `LINK_CARD`、`BUTTON_CARD`。
- `LINK_CARD` 携带 `linkCard`。
- `BUTTON_CARD` 携带 `buttonCard`。

`MarketingRoundWorker` 只负责拿到 composer 结果并写 outbox，不直接调用协议层 HTTP。

### 错误处理

Armada 保存阶段应拦截绝大多数非法模板。运行时遇到历史脏数据时：

- `BUTTON` 无有效按钮：记录发送失败原因，当前发送尝试不下发协议命令。
- 不能把非法按钮模板降级为 `TEXT`。

具体实现可以沿用现有发送尝试失败记录能力；如果当前失败记录只覆盖协议层回传，需为 composer 配置错误补充本地失败落库路径。

## armada-protocol 设计

### Kafka Worker

扩展 `message.send.requested` payload 校验：

- `messageType` 支持 `TEXT`、`LINK`、`IMAGE`、`LINK_CARD`、`BUTTON_CARD`。
- `LINK_CARD` 必须有 `url`、`title`、可选 `description`、可选 `thumbnail`。
- `BUTTON_CARD` 必须有 1-3 个按钮，可选 `thumbnail`。

协议层发送分支：

- `TEXT`：维持 `{ text }`。
- `LINK`：维持当前文本发送，不生成 card。
- `IMAGE`：维持 `{ image, caption }`。
- `LINK_CARD`：构造 link preview 并 `sock.sendMessage(jid, { text, linkPreview })`。
- `BUTTON_CARD`：构造 interactive native flow message 并 `sock.relayMessage(...)`。

可以优先迁移旧项目 `laqunxitong/protocol-layer/src/routes/messages.ts` 中已经验证过的 link-card/button-card helper：

- link card 使用 `sharp` 标准化缩略图。
- link card 使用 `prepareWAMessageMedia` 生成 high quality thumbnail。
- button card 使用 native flow button 映射。
- button card 带图时构造 header image；无图时发送无图 interactive message。
- relay 时保留 `biz/native_flow` additional nodes。

### 协议按钮映射

| 协议类型 | WhatsApp native flow |
| --- | --- |
| `link` | `cta_url` |
| `copy` | `cta_copy` |
| `quick` | `quick_reply` |

本次不支持 `phone`。

### HTTP Routes

Armada 营销任务不会使用 HTTP routes。是否恢复 `/v1/messages/link-card` 和 `/v1/messages/button-card` 可作为低成本兼容项处理：

- 如果恢复，应复用 Kafka worker 的同一套 helper，避免两套协议构造逻辑。
- 如果暂不恢复，不影响本次 Armada 营销任务对齐目标。

### 协议层错误

Armada 应尽量在入 Kafka 前保证 payload 合法。协议层仍需要防御：

- 若 payload 可解析出任务追踪字段，但卡片字段非法，发布失败结果事件，reason 使用 `INVALID_MESSAGE_PAYLOAD` 或现有等价失败码。
- 若 payload 连基本追踪字段都无法解析，按现有 worker 异常处理进入重试或死信路径。

## 前端设计

项目：`wheel-saas-pure-web`

模板表单在 `linkMode = BUTTON` 时：

- 按钮列表最少 1 个、最多 3 个。
- 没有按钮时保存按钮不可提交，或提交时阻断并提示。
- 每个按钮按类型校验必填项：
  - 跳转链接：按钮文本、http(s) 链接。
  - 复制内容：按钮文本、复制内容。
  - 快速回复：按钮文本。

前端校验要和后端保存校验一致。后端仍是最终保护线。

## 测试计划

### Armada API

新增或调整：

- `MarketingMessageComposerTest`
  - `NORMAL + 图片 + http(s) promotionLink` 生成 `LINK_CARD`。
  - `NORMAL + 图片 + 非 http(s) promotionLink` 不生成 `LINK_CARD`。
  - `BUTTON + 有效按钮` 生成 `BUTTON_CARD`，按钮类型映射正确。
  - `BUTTON + 无按钮` 抛出模板配置错误，不生成 `TEXT`。
  - `IMAGE_TEXT` 现有行为不变。
- `ProtocolCommandOutboxServiceImplTest`
  - `LINK_CARD` payload JSON 包含 `linkCard`。
  - `BUTTON_CARD` payload JSON 包含 `buttonCard.buttons`。
- `MarketingRoundWorkerTest`
  - link card/button card 场景会写出对应协议命令。
  - composer 配置错误时不会写出错误协议命令，并记录失败。

验证命令：

```bash
cd armada/armada-api
mvn -Dtest=MarketingMessageComposerTest,MarketingRoundWorkerTest,ProtocolCommandOutboxServiceImplTest test
```

### armada-protocol

新增或调整：

- `worker-consumer.test.ts`
  - `LINK_CARD` 生成 link preview 发送。
  - `BUTTON_CARD` 生成 interactive native flow message。
  - 无效按钮 payload 产生失败结果或 worker 预期错误。
- helper 测试可参考旧项目：
  - `messages.linkpreview.test.ts`
  - `messages.buttoncard.test.ts`

验证命令：

```bash
cd armada-protocol/protocol-layer
npm test -- --runTestsByPath src/commands/worker-consumer.test.ts
```

### wheel-saas-pure-web

新增或调整模板表单校验测试；如果项目当前没有对应单测，至少运行类型检查或现有前端校验命令。

## 验收标准

1. Armada `NORMAL + 有图 + http(s) 推广链接` 的营销发送 outbox payload 为 `LINK_CARD`，协议层发送真实 link preview。
2. Armada `BUTTON + 有效按钮` 的营销发送 outbox payload 为 `BUTTON_CARD`，协议层发送真实 interactive button card。
3. Armada `BUTTON` 无按钮在前端不能保存；后端保存接口也拒绝；历史脏数据运行时不降级文本。
4. 原有 `TEXT`、`LINK`、`IMAGE` 发送测试继续通过。
5. 协议层发送结果回传仍能驱动 Armada 发送尝试状态更新。

