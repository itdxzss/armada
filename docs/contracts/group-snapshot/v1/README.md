# Group Snapshot v1 contract fixtures

本目录是 Armada、Web 协议层与 Android 协议层共同遵循的按需单群快照契约基线。

- `group-snapshot-requested.json`：命令信封；`aggregateType` 与 `taskType` 是映射关系，不是同名关系。
- `group-profile-reported.json`：完整群资料事实，成员只在 `membersComplete=true` 时可作删除式对账。
- `group-invite-link-changed.json`：邀请码事实；日志、指标标签不得记录 `inviteCode`。
- `group-snapshot-result-reported.json`：只做任务结算，不携带群业务事实。

稳定错误码：`ACCOUNT_NOT_ONLINE`、`ACCOUNT_BUSY`、`ACCOUNT_BINDING_MISMATCH`、`INVALID_PAYLOAD`、
`GROUP_PERMISSION_DENIED`、`GROUP_NOT_JOINED`、`GROUP_UNAVAILABLE`、`TIMEOUT`、
`NETWORK`、`RESULT_PUBLISH_FAILED`、`PAYLOAD_TOO_LARGE`、`UNKNOWN`。
