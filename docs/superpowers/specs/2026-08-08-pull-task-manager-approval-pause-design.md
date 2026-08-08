# 普通拉群管理员入群审批暂停设计

## 目标

当普通群链接拉群任务的管理员踩链接后收到 `PENDING_APPROVAL` 时，暂停**该群执行行**并向操作者展示明确原因；不再次踩同一链接、不更换管理员、不启动后续拉人。其他群执行行继续运行。

## 当前事实

- Web 协议层的 `groupAcceptInvite` 返回群 JID 时，`POST /v1/groups/join` 返回 `{ groupJid, joined: true }`；返回空值时表示已提交管理员审批。
- Android 协议服务的 `executeNativeGroupJoin` 在 `InviteCode` 成功后读取 `GroupParticipants`：找到本账号才返回 `JOINED`，找不到返回 `PENDING_APPROVAL`。因此 Android 成员查询是协议结果判定，不能删除。
- `PullTaskManagerJoinResultServiceImpl` 当前只把 `JOINED`、群/链接不可用、管理员不可用分开处理；`PENDING_APPROVAL` 被归入 `UNKNOWN`，会写入重试时间并可能重新进入管理员入群链路。
- 补充管理员的踩链接路径同样会处理 `PENDING_APPROVAL`，因此必须使用同一暂停语义，不能退化为普通管理员资源不足。
- 任务详情 API 已返回 `reasonCode` 和 `reasonMessage`；前端详情抽屉已显示原因，但执行列表状态标签不识别等待审批。

## 目标状态机

| 协议结果 | 管理员动作事实 | 管理员在群事实 | 群执行行 | 后续动作 |
| --- | --- | --- | --- | --- |
| `JOINED` 且有群 JID | `SUCCESS` | `IN_GROUP` | `EXECUTING / MANAGER_ADMIN` | 继续管理员设置和拉人 |
| `PENDING_APPROVAL` | `PENDING_APPROVAL` | `PENDING_APPROVAL` | `WAIT_RESOURCE / MANAGER_JOIN / APPROVAL`，`next_run_at=0` | 停止该群调度与协议副作用 |
| `INVITE_INVALID`、`INVITE_REVOKED`、`INVALID_GROUP_LINK`、`GROUP_UNAVAILABLE` | `FAILED` | `JOIN_FAILED` | `FAILED / MANAGER_JOIN` | 终止该群；父任务继续其他群 |
| 账号不可用 | `FAILED` | `JOIN_FAILED` | `WAIT_RESOURCE / MANAGER_JOIN / MANAGER` | 等待或更换管理员 |
| 限流、超时、回调缺失、结果无法确认 | `UNKNOWN` | `UNKNOWN` | 保持现有未知结果收敛 | 退避或仅在有已知群 JID 时核实 |

`APPROVAL` 是 `wait_resource_type` 的新增枚举值，不是“管理员资源不足”。复用既有 `WAIT_RESOURCE` 执行状态可使该行天然不再被主调度器抢占；资源恢复扫描必须显式跳过该类型，避免它被错误恢复。

面向用户的稳定展示文案为：`管理员已提交入群申请，等待群主或管理员审批；该群拉群已暂停`。原因码保持既有 `MANAGER_JOIN_PENDING_APPROVAL`，避免改变 API 契约。

## 成员查询边界

1. Android 协议服务继续在原生入群后查询成员，这是 Android 能区分“已进群”和“待审批”的必要证据。
2. Web 的正常 `JOINED + groupJid` 直接作为成功结果；Armada 的 `PullTaskManagerJoinProcessor` 不再为此额外查询完整成员列表。
3. 仅当任务处于崩溃恢复/回调缺失等不确定状态且已持有 `knownGroupJid` 时，保留一次成员列表查询，用来防止重复点击链接。

## 暂停后的恢复边界

本次只实现“停止该群并反馈状态”，不实现获批后的自动唤醒，也不在后台轮询成员列表。原因是 Web 的待审批响应可能没有群 JID，不能安全地按群做自动核实；把它伪装为可重试会再次提交入群申请。

操作者可继续使用现有“结束单群”能力放弃该执行行；获批后的自动核实或显式“重新核实审批”需要单独设计为跨 Web/Android 的后续需求。

## 影响范围

- Armada 后端：管理员入群回调状态机、补充管理员入群、恢复调度隔离、同步恢复处理器和测试。
- 前端：普通拉群执行列表将 `WAIT_RESOURCE + APPROVAL` 显示为“等待审批”；详情沿用已有原因文案。
- Android 协议服务：无代码改动。
- Web 协议服务：无接口契约改动。
- 数据库/API：不新增表、列、Flyway 或接口字段；`TINYINT` 枚举使用新的未占用数值。

## 风险与回滚

- 风险：等待审批的单群会保持非终态，父任务不会在其余群完成后自动结束。这是“该群暂停、等待人工决定”的预期语义。
- 风险：旧代码或 SQL 若默认枚举穷尽，需要通过状态机和 read-model 测试保证 `APPROVAL` 不被当作普通管理员资源短缺。
- 回滚：回退本次后端和前端提交；持久化值仅为已有 `TINYINT` 字段中的新枚举值，回滚版本会将其视为非可调度等待行，需在回滚前先人工结束或恢复这些执行行，不能直接假定旧版本可理解该值。
