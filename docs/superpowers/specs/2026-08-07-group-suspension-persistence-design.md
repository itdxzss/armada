# WhatsApp 群封禁状态持久化设计

## 目标

当 Web 或 Android 协议明确收到 WhatsApp 群 `suspended` / `terminated` 信号时，立即把对应群写成封禁状态；后续账号群列表仍能看到该群时，不得把封禁覆盖回正常。

## 已确认事实

- 2026-08-07 第一套测试环境中，Web 管理员首次邀请拉手后收到了 `w:gp2` 的 `suspended` 通知，随后邀请失败。
- Web 当前只记录 `account.group_notification.raw_received`，没有发布业务事件。
- Android 当前的 WGP2 解析器只处理 `create/add/remove/leave`；HistorySync 的 `suspended/terminated` 只进入进程内发送能力判断。
- 协议契约已有关键事件 `group.health_reported`，Armada 后端也已支持把 `health=BANNED` 映射为 `group_link_health.health_status=UNAVAILABLE`、`is_banned=1`。
- 实时通知天然只有 `tenantId + groupJid`，没有 `groupLinkId`；后端当前要求 `groupLinkId`，因此不能直接消费该通知。
- `account.groups_reported` 会把可见群的健康行重写为 `AVAILABLE/is_banned=0`，会错误清除封禁事实。

## 方案

### 统一事件契约

Web 与 Android 复用现有 `group.health_reported`，不增加新 topic、新表或新状态枚举。实时封禁事件正文为：

```json
{
  "tenantId": 1,
  "accountId": 15,
  "protocolAccountId": "acc_919096944068",
  "groupJid": "120363428058767969@g.us",
  "health": "BANNED",
  "errorCode": "CHAT_SUSPENDED",
  "checkedAt": "2026-08-07T01:32:42.912Z"
}
```

`terminated` 对应 `CHAT_TERMINATED`。事件仍以 `protocolAccountId` 为 Kafka key，重复投递由健康行 upsert 幂等吸收。

### Web 协议

原始 `w:gp2` 通知的首个子节点为 `suspended` 或 `terminated` 且 `from` 是群 JID 时，立即发布 `group.health_reported`。缺少账号业务引用时只记录告警，不发布无法归属租户的事件。

### Android 协议

Android 同时覆盖两条明确事实来源：

1. WGP2 实时通知中的 `suspended/terminated`；
2. HistorySync 明确携带的 `GroupChatStateChangedEvent`。

两条来源归一为同一 `group.health_reported` 契约。只有值为 `true` 才发布封禁；字段缺失不解释为健康，避免误恢复。

### Armada 后端

- `group.health_reported` 允许实时事件不带 `groupLinkId`，但必须带 `tenantId + groupJid`。
- 群域服务在租户上下文内按 `groupJid` 解析有效 `groupLinkId`，找不到群时记录并跳过，不跨租户创建或更新数据。
- 解析成功后沿用现有健康映射和 upsert：`BANNED` 写 `UNAVAILABLE/is_banned=1`，错误原因写 `CHAT_SUSPENDED` 或 `CHAT_TERMINATED`。
- 普通 `account.groups_reported` 仅更新成员数和观测时间；已有 `is_banned=1` 时保留 `health_status/is_banned/last_health_error/health_failure_count`。
- 只有后续明确的 `group.health_reported` 健康结果可以解除封禁，单纯“群仍可见”不能解除。

## 错误处理与可观测性

- 协议层发布失败沿用现有 Kafka 重试/DLQ，并记录脱敏后的 `accountId/groupJid/reason`。
- 后端拒绝缺少 `tenantId`、`groupJid` 或 `health` 的事件。
- 事件中的 `groupLinkId` 与按 JID 解析结果同时存在但不一致时拒绝更新，避免串群。
- 日志记录 `eventId/tenantId/groupLinkId/groupJid/health/errorCode/protocolAccountId`，不记录凭据或原始节点。

## 测试

- Web：`suspended/terminated` 发布封禁健康事件；普通 WGP2 不发布；缺少业务引用不发布。
- Android：WGP2 和 HistorySync 的明确 `true` 状态均发布；`false/nil` 不误报封禁；事件路由到群 topic。
- 后端 consumer：缺少 `groupLinkId` 时按 JID进入服务；非法字段拒绝。
- 后端服务/Mapper：按租户 JID 定位并封禁；跨租户不命中；账号群同步保留既有封禁；明确健康报告可恢复。

## 被否决方案

- 从邀请返回的 403 推断封禁：403 也可能是权限或临时风控，容易误封。
- 新增 `group.status_changed`：当前 `group.health_reported` 已完整承载健康、封禁、错误原因，新增事件会形成重复契约。
- 在 `account.groups_reported` 内直接附带封禁：实时性较差，且 WGP2 已给出更直接的事实信号。

## 部署与回滚

部署顺序为后端 → Web 协议 → Android 协议，保证新协议事件先有消费者。无数据库迁移。

回滚时先回滚两端协议发布，再回滚后端；已写入的封禁事实保留，避免回滚过程把真实封禁恢复成正常。人工确认后可通过明确健康检测恢复。

