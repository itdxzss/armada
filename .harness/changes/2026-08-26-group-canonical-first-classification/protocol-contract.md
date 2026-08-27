# 群快照与 self-membership 事实合同

## `account.groups_reported`

顶层 `data` 必须携带：

- `snapshotId`：一次真实查询尝试的唯一身份；显式命令重放使用同一稳定身份。
- `queryStartedAt`、`snapshotCutoff`：RFC3339，当前版本二者必须相等。
- `snapshotComplete`：仅查询成功且没有跳过异常群时为 `true`。
- `skippedGroupCount`：非负整数；Community 排除不计入，非法普通群计入。
- `commandId`：仅显式同步命令携带。

查询期间出现可靠 self-add 的群仍必须保留在 `groups` 全集中，并在该群记录附加：

- `postControlObservedAt`：协议进程在查询 cut 后观察到该可靠事实的时间，epoch 毫秒。
- `sourceEventId`：原始 WGP2 stanza ID。

两字段必须同时存在，且毫秒级观察时间不得早于精确的 `snapshotCutoff`。查询开始前已观察到的通知属于 baseline，即使原始 WhatsApp stanza 时间只有秒精度也不能误标为上控后群。原始 WhatsApp 事实时间仍由独立 self-membership 事件的顶层 `occurredAt` 保留。这样后端既能保持完整成员全集，又不会把查询期间新增群拍成历史群。

缺少新快照边界、`snapshotId`、`eventId` 或 `skippedGroupCount` 的旧事件可以刷新可见资料，但必须 fail-closed 为不完整：不得捕获 baseline，也不得据缺失项判定退群。负数 `skippedGroupCount` 和携带 post-control 证据却缺少完整查询边界的事件直接拒绝。

## `account.group_membership_changed`

- `data.sourceEventId` 必填，来自原始 WGP2 stanza ID。
- 顶层 `occurredAt` 必须使用原始 WhatsApp 时间，不能使用发布时刻兜底。
- 稳定事件 ID 由事件名、协议账号、群 JID、动作和 `sourceEventId` 哈希生成。
- 原始 ID 或时间缺失时不发布精确关系事实，改由完整群快照兜底。
