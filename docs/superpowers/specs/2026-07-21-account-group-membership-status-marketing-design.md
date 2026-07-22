# 账号群关系状态保留与营销跳过设计

## 1. 背景

Android Zhuan 已能接收 WhatsApp `w:gp2` 群成员通知和 `dirty(groups)` 群列表失效信号，
并在信号到达后重新查询账号当前参与群、发布 `account.groups_reported`。Armada 当前把完整
快照中消失的账号群关系写成 `account_group_membership.deleted_at`，因此账号群选择列表不再
展示该群，但无法保留“被踢出”“主动退出”或“确认不在群”的当前状态。

营销任务明细目前主要从 `marketing_task_send_attempt` 的最后有效发送尝试派生群状态。
协议事件即使已经确认账号被踢，账号群关系变化与历史发送尝试之间也没有当前状态关联，
页面因此可能继续显示普通 `SEND_FAILED`，或者在后续不再生成发送尝试时完全看不到跳过事实。

本设计把账号群关系从“存在/软删”改为显式状态，并在营销执行前读取该状态：可发送状态
继续投递协议，不可发送状态保留群、生成 `SKIPPED` 明细但不调用协议。

## 2. 已确认业务口径

1. 账号群关系必须保留，不因完整快照中消失而软删除当前关系记录。
2. `UNCONFIRMED` 仍允许发送。
3. 被管理员移除记录为 `KICKED_OUT`；账号主动退出记录为 `LEFT`，两者不能合并。
4. 完整快照确认群已消失、但没有精确 `remove/leave` 原因时记录为 `NOT_IN_GROUP`。
5. `KICKED_OUT`、`LEFT`、`NOT_IN_GROUP` 不发送。
6. 新建营销任务展开账号时展示全部群状态，全部允许勾选；不可发送状态在执行阶段跳过。
7. 营销任务明细始终保留任务涉及的群，同时显示当前关系状态和最后执行结果。
8. `SKIPPED` 不计成功、不计失败，单独统计跳过次数；全部跳过的任务仍可正常完成。
9. 全局群组列表继续读取 `group_link`，不因某个账号退出群而移除群组。

## 3. 目标

1. 在账号维度保存可恢复、可展示的当前群关系状态。
2. 以精确 `remove/leave` 事件识别被踢和主动退出，以完整快照差异兜底未知原因的退出。
3. 新建营销任务时展示并允许选择所有群，执行前按本地最新状态决定发送或跳过。
4. 在营销任务明细中同时呈现当前关系状态、最后执行结果和成功/失败/跳过统计。
5. 保持现有 `UNCONFIRMED` 继续发送、协议发送前检测和发送结果回执兜底语义。
6. 对不完整快照、重复事件、乱序事件、查询失败和滚动部署提供明确保护。

## 4. 非目标

- 不修改全局 `group_link` 群组池的生命周期或群组列表筛选口径。
- 不把账号群退出状态上升为全局群封禁；同一个群可以同时对应多个账号和不同关系状态。
- 不在每条营销消息发送前实时请求 WhatsApp；执行前只读取 Armada 本地关系表。
- 不修改 Baileys `armada-protocol`；本次协议范围仅为 Android Zhuan。
- 不删除历史营销尝试，也不把过去的成功或失败结果改写成新的执行结果。
- 不记录或上报群 participant 手机号、PN、LID 明细。

## 5. 影响项目

### 5.1 Android 协议 `whatsapp-server-feature-android-zhuan`

- 发布精确的当前账号群成员变化事件。
- 在完整群快照事件中携带快照完整性和跳过条目数量。
- 保持 `w:gp2` participant 身份只在进程内分类，不写事件和日志。

### 5.2 Armada 后端 `armada`

- Flyway、账号群关系实体/枚举、Mapper 和状态转换服务。
- Android 群事件消费与完整快照校准。
- 营销任务账号群候选、执行前状态读取、`SKIPPED` 尝试和明细统计。
- 营销任务明细 API 增加当前关系状态和跳过数据。

### 5.3 前端 `wheel-saas-pure-web`

- 新建营销任务账号群节点展示当前关系状态，所有状态均允许勾选。
- 营销任务明细展示 `KICKED_OUT`、`LEFT`、`NOT_IN_GROUP`、`SKIPPED` 和跳过次数。

## 6. 方案选择

采用“账号群当前关系状态化”：每个账号与群保留一条当前关系记录，使用显式状态决定展示
和发送。现有更早的软删重复记录继续作为历史数据保留，不参与当前关系查询。

不采用以下方案：

- 继续只用 `deleted_at`：无法区分被踢、主动退出和未知原因退出，营销明细也难以关联当前状态。
- 在软删行增加 `exit_reason`：重新进群后会存在多条历史行，读取当前状态和乱序保护更复杂。
- 新增完整状态历史表：审计能力更强，但当前需求只需要可靠的当前状态和现有发送尝试历史，
  双表物化会增加写入一致性成本。
- 把退出状态写入 `group_link.membership_state`：该字段是租户全局群入口状态，不能表达每个账号
  对同一个群的不同关系。

## 7. 状态模型

### 7.1 账号群关系状态

| 状态 | 建议码 | 是否发送 | 含义 |
|---|---:|---:|---|
| `IN_GROUP` | 1 | 是 | 完整快照当前包含该群 |
| `UNCONFIRMED` | 2 | 是 | 关系存在但当前证据不足；按已确认口径继续发送 |
| `KICKED_OUT` | 3 | 否 | 精确 `remove self` 确认账号被移出群 |
| `LEFT` | 4 | 否 | 精确 `leave self` 确认账号主动退出群 |
| `NOT_IN_GROUP` | 5 | 否 | 完整快照确认账号不在群，但退出原因未知 |

`UNCONFIRMED` 与数据库读取异常不是同一概念。前者是业务状态，允许发送；后者是执行基础设施
失败，本轮应失败并重试，不能绕过状态检查。

### 7.2 发送尝试状态

继续复用现有 `MarketingSendAttemptStatus`：

| 状态 | 数据库值 | 含义 |
|---|---:|---|
| `SUBMITTED` | 0 | 已提交协议，等待结果 |
| `SUCCESS` | 1 | 协议发送成功 |
| `FAILED` | 2 | 协议发送失败 |
| `SKIPPED` | 3 | Armada 业务规则决定本轮不调用协议 |

关系状态和尝试状态必须独立。例如当前关系为 `LEFT` 时，本轮执行结果为 `SKIPPED`；过去的
最后一次协议发送仍可以是 `SUCCESS`。

## 8. 数据模型

通过新 Flyway 迁移扩展 `account_group_membership`：

| 字段 | 类型 | 约束 | 用途 |
|---|---|---|---|
| `membership_status` | `TINYINT` | 非空，默认 1 | 当前账号群关系状态 |
| `status_source` | `VARCHAR(64)` | 可空 | `WGP2_REMOVE`、`WGP2_LEAVE`、`GROUP_SNAPSHOT`、`LEGACY_MIGRATION` 等 |
| `status_updated_at` | `BIGINT` | 非空 | 当前状态事实时间，用于乱序保护 |

保留现有字段：

- `last_seen_at`：最近一次由完整快照或精确 `add` 确认仍在群的时间；精确 `remove/leave` 先于任何
  在群证据到达时允许为 null，禁止伪造时间。
- `joined_at`：当前一次入群关系开始时间。只有从非发送状态恢复到 `IN_GROUP` 时更新；普通
  快照重复看到该群时不改。
- `created_at`：首次建立这条当前关系记录的时间。
- `deleted_at`：迁移后不再表示“当前已退出群”，仅用于隔离旧的重复历史行或真正废弃的数据。

新增以租户、账号、关系状态为前缀的查询索引。现有 `is_active` 唯一键继续保证同一
`tenant_id/account_id/group_jid` 最多一条 `deleted_at IS NULL` 的当前记录。

### 8.1 历史迁移

1. 当前 `deleted_at IS NULL` 的关系初始化为 `IN_GROUP`，状态时间取现有 `updated_at`。
2. 对没有当前活跃行、但存在历史软删行的账号群，选择最新软删行恢复为当前记录，状态设为
   `NOT_IN_GROUP`，来源为 `LEGACY_MIGRATION`，状态时间取原 `deleted_at`。
3. 如果同一账号群已有当前活跃行，更早的软删行保持不变，不参与当前查询。
4. 迁移必须在真库 DbTest 中覆盖重复历史行、已重新进群、仅历史退出和跨租户隔离。

## 9. Android 协议事件

### 9.1 精确关系变化事件

新增 `account.group_membership_changed` 业务事件，用于不依赖群快照查询地传递精确事实：

```json
{
  "tenantId": 1,
  "accountId": 413,
  "protocolAccountId": "android-account-handle",
  "groupJid": "120363...@g.us",
  "action": "remove",
  "selfParticipation": "SELF",
  "occurredAt": 1784600000000,
  "source": "android_wgp2"
}
```

约束：

- 只发布 `add/remove/leave` 且明确涉及当前账号的事件。
- `remove` 映射 `KICKED_OUT`，`leave` 映射 `LEFT`，`add` 映射 `IN_GROUP`。
- `SelfParticipation=UNKNOWN` 只触发快照刷新，不发布精确原因事件。
- 事件不包含 participant 数组、手机号、PN、LID、operator 身份或原始 notification。
- 精确事件发布失败只记录安全错误分类；群快照刷新仍继续执行。

### 9.2 完整群快照事件

扩展 `account.groups_reported` 数据：

```json
{
  "tenantId": 1,
  "accountId": 413,
  "protocolAccountId": "android-account-handle",
  "reportedAt": 1784600001000,
  "source": "android_group_participant_self",
  "snapshotComplete": true,
  "skippedGroupCount": 0,
  "groups": [
    { "groupJid": "120363...@g.us", "subject": "群名称" }
  ]
}
```

`snapshotComplete` 仅在 IQ 成功、存在合法 groups 容器且 `skippedGroupCount == 0` 时为 true。
查询失败不发布伪造空快照。为支持滚动部署，应先部署 Android 的加法字段；旧 Armada 会忽略
未知 JSON 字段。

Armada 对字段缺失使用账号 `protocol_id` 做兼容判定，避免本次范围外的 Baileys 被误判：

- 显式 `snapshotComplete=true` 且 `skippedGroupCount=0`：完整快照。
- 显式 false 或 `skippedGroupCount>0`：不完整快照。
- 字段缺失且账号协议后端为 Web/Baileys：沿用旧契约，按完整快照处理。
- 字段缺失且账号协议后端为 Android：按不完整快照处理，防止旧版或回滚中的 Android 清空关系。

因此本次不需要修改 `armada-protocol`，Android 与 Armada 可安全滚动发布。

## 10. Armada 状态转换

### 10.1 精确事件

- `add self`：当前关系更新为 `IN_GROUP`，并在从非发送状态恢复时刷新 `joined_at`。
- `remove self`：更新为 `KICKED_OUT`。
- `leave self`：更新为 `LEFT`。
- 事件的 `occurredAt` 小于当前 `status_updated_at` 时忽略。
- 重复事件使用账号、群、动作和事实时间保持幂等。

### 10.2 快照校准

- 快照中出现的群 upsert 为 `IN_GROUP`，更新 `last_seen_at`。
- 完整快照中缺失的当前关系，如果当前不是 `KICKED_OUT` 或 `LEFT`，更新为
  `NOT_IN_GROUP`；已由精确事件确认的退出原因继续保留，不能被“原因未知”降级覆盖。
- 不完整快照只更新出现的群，不处理缺失群。
- 如果精确退出后又在事实时间更新的快照中出现，恢复为 `IN_GROUP`，表示账号已经重新进群。
- 同一事实时间发生冲突时，精确 `remove/leave` 的优先级高于快照缺失；快照出现只有在事实时间
  严格更新时才恢复 `IN_GROUP`。
- 查询失败、groups 容器缺失或账号离线不产生空快照，不改变当前关系状态。

状态更新和当前关系 upsert 在同一租户事务内完成。Kafka listener 按事件的 tenantId 重建
租户上下文，禁止载荷覆盖数据库租户边界。

## 11. 新建营销任务账号群列表

账号展开接口不再只返回可发送关系，而是返回所有当前关系记录，并增加：

- `membershipStatus`
- `membershipStatusText`
- `statusUpdatedAt`

前端对每个群显示状态标签。所有状态都允许勾选，不在创建阶段阻止 `KICKED_OUT`、`LEFT` 或
`NOT_IN_GROUP` 进入任务。全局 `group_link` 群组列表接口和页面保持不变。

## 12. 营销执行

### 12.1 执行前状态读取

每轮解析实际群目标后，按账号和群批量读取 `account_group_membership` 最新状态，不逐条执行
N+1 查询，也不调用 WhatsApp：

- `IN_GROUP`、`UNCONFIRMED`：进入现有协议发送路径。
- `KICKED_OUT`、`LEFT`、`NOT_IN_GROUP`：不创建协议 outbox，不发送 Kafka 命令，直接创建
  `SKIPPED` 尝试。
- 已选群找不到当前关系记录：按 `UNCONFIRMED` 继续发送，兼容迁移前任务和固定群目标。
- 批量状态查询本身失败：本轮执行失败并按现有调度重试，不按 `UNCONFIRMED` 放行。

状态读取应尽量靠近 attempt/outbox 写入边界。读取后状态再次变化的竞态，由协议发送前群状态
检测和 `message.send_result_reported` 回执继续兜底。

### 12.2 跳过尝试

`SKIPPED` 尝试要求：

- `command_id = NULL`
- `submitted_at = NULL`
- `result_at = 当前时间`
- 保留 task、target、account、groupLink、groupJid、roundNo 和 attemptNo
- `group_status` 与 `group_status_reason` 写入对应当前关系状态

稳定原因：

| 关系状态 | reasonCode | reasonMessage |
|---|---|---|
| `KICKED_OUT` | `KICKED_OUT` | `账号已被踢出群聊` |
| `LEFT` | `LEFT` | `账号已主动退出群聊` |
| `NOT_IN_GROUP` | `NOT_IN_GROUP` | `账号当前已不在群聊` |

跳过不增加发送成功数或发送失败数。每条 `SKIPPED` attempt 计入跳过次数；全部群均跳过时，
本轮和任务仍按正常完成推进，不标记发送失败。

## 13. 营销任务明细

任务明细的群集合以任务已解析的目标群为基础，并与该任务的历史发送/跳过尝试取并集，不能只
依赖 attempt 反推。固定选择的账号群从任务创建后即可展示；运行时才解析出的动态群在目标解析
后展示。不可发送群执行时会生成 `SKIPPED` 尝试，因此即使从第一轮起就被踢或退出，也会保留
目标群和执行结果。

每条群明细区分：

- `membershipStatus`：当前账号群关系，来自 `account_group_membership`；缺失时回退为
  `UNCONFIRMED` 或历史 attempt 的退出语义。
- `groupStatus`：最后协议发送尝试观察到的群发送状态，保留现有兼容字段。
- `executionResult`：最后一次已结束尝试的 `SUCCESS`、`FAILED` 或 `SKIPPED`；`SUBMITTED`
  不覆盖已结束结果。
- `sentMessageCount`、`failedMessageCount`、`skippedMessageCount`：分别按 attempt 状态聚合。
- `executionReason`：最后执行结果对应原因；跳过时显示稳定中文原因。

账号层和任务详情层的跳过数从群明细聚合，不把跳过计入失败。过去的成功/失败 attempt 不被
改写；当前关系状态可以随新事件变化，因此允许展示“当前 LEFT，最后一次发送 SUCCESS”。

## 14. 前端交互

### 14.1 创建任务

- 账号群树展示所有关系状态。
- 状态标签至少覆盖：在群、未确认、被踢出、已主动退出、已不在群。
- 所有状态均可选择；不显示“禁止选择”或创建拦截。

### 14.2 任务明细

- 所有任务群明细持续展示，不按当前关系过滤。
- 当前关系状态和最后执行结果使用两个独立标签。
- `SKIPPED` 显示“已跳过”，并显示稳定跳过原因。
- 群、账号和任务汇总增加跳过数量；成功、失败统计口径保持不变。
- 前端未知新枚举统一回退为“未确认/未知”，不得导致页面渲染失败。

## 15. 并发、幂等与失败处理

- 状态更新使用 `status_updated_at` 做条件更新，旧事实不能覆盖新事实。
- 相同事件重复消费不得重复创建关系记录或改变 `joined_at`。
- 完整快照存在关系的事实优先于更早的退出事件；这覆盖被踢后快速重新进群场景。
- `snapshotComplete=false` 不执行任何缺失关系批量更新。
- 精确退出事件与快照事件相互独立；快照查询失败不影响 `KICKED_OUT/LEFT` 事实落库。
- Worker 对同一轮重复执行继续依赖现有 attempt 唯一性和幂等规则，不能重复插入 skip 行。
- 数据库查询异常、事务异常和 Kafka 消费异常使用现有重试/DLQ，不降级为发送。

## 16. 兼容与部署

部署顺序：

1. Android 协议先发布加法事件字段和新精确事件；旧 Armada 忽略未知事件/字段，现有快照继续工作。
2. Armada 执行 Flyway 并部署新 consumer、状态模型、Worker 和 API。
3. 前端部署新类型和展示；旧前端对未知状态按现有未确认兜底短暂兼容。

后端部署完成后才启用“不软删当前关系”和执行前跳过逻辑。若 Android 精确事件尚未到达，
完整快照缺失先记录为 `NOT_IN_GROUP`；升级完成后新事件可以精确更新为 `KICKED_OUT/LEFT`。

### 16.1 回滚

- 前端可独立回滚，后端字段保持兼容。
- Android 可独立回滚，精确原因退化为完整快照的 `NOT_IN_GROUP`。
- 后端不得直接回滚到只按 `deleted_at IS NULL` 发送的旧版本。回滚前必须执行受控 SQL，将
  `KICKED_OUT/LEFT/NOT_IN_GROUP` 当前关系重新设置 `deleted_at`，恢复旧版本的安全筛选语义。
- Flyway 新增列不做破坏性删除；回滚脚本只恢复数据筛选语义，不丢当前状态字段。

## 17. 可观测性与安全

低基数日志/指标至少覆盖：

- Android 精确群关系事件发布成功/失败数，按 action 分类。
- 完整/不完整群快照数量和 skippedGroupCount。
- Armada 状态转换数量，按 from/to/source 分类。
- 营销每轮 sendable/skipped 数量，按跳过原因分类。
- 乱序事件忽略数、状态批量查询失败数和快照缺失更新数。

日志只允许输出 Armada accountId、群 JID、状态、来源、事件 ID、计数和耗时。禁止输出 participant
列表、手机号、PN/LID、营销正文、API key、凭据或原始 WhatsApp notification。

## 18. 测试设计

### 18.1 Android Go

- `remove self` 发布精确事件且不包含 participant 身份。
- `leave self`、`add self` 映射正确。
- Other 不发布精确事件，Unknown 只触发快照。
- `skippedGroupCount == 0` 才标记完整快照。
- 查询失败不发布伪造空快照，精确事件仍不丢失。
- 防抖和尾随刷新保留正确 source，事件解绑和账号下线不泄漏任务。

按仓库要求执行 `gofmt`、`go vet ./...`、`go build ./...`、`go test ./...`；涉及协调器并发改动
增加定向 `go test -race`。

### 18.2 Armada 后端

- Flyway/DbTest：历史活跃、历史退出、重复退出、重新进群和租户隔离迁移。
- 状态转换：remove、leave、add、完整缺失、不完整缺失、重新出现、重复和乱序。
- 候选接口返回全部状态并保留状态字段。
- Worker：`IN_GROUP/UNCONFIRMED` 发送，其余三种状态跳过。
- 状态行缺失按 UNCONFIRMED 发送；状态查询异常不发送并重试。
- skip attempt 字段、幂等、原因码和本轮完成语义。
- 明细：目标群与 attempt 取并集，全部群保留，SUCCESS/FAILED/SKIPPED 最新结果、三个计数和
  历史兼容回退。
- 全局群组列表回归不受账号状态变化影响。

### 18.3 Vue 前端

- 创建抽屉展示五种状态并允许全部选择。
- 明细展示当前关系与执行结果两个标签。
- LEFT、KICKED_OUT、NOT_IN_GROUP、SKIPPED 文案与颜色映射。
- 跳过数量不计入失败数量。
- 未知枚举安全回退；历史接口缺少新字段仍可渲染。

## 19. 验收场景

1. 管理员踢出账号：明细当前关系变为“被踢出”，下一轮写 SKIPPED，不出现协议发送命令。
2. 账号主动退群：明细显示“已主动退出”，下一轮写 SKIPPED。
3. 只收到完整群快照缺失：显示“已不在群”，下一轮写 SKIPPED。
4. 状态未确认：仍提交协议，并按真实回执显示成功或失败。
5. 被踢后重新进群：状态恢复“在群”，后续轮次恢复发送。
6. 不完整快照遗漏一个群：该群原状态不变，不误标退出。
7. 新建任务账号群树展示全部状态，退出群允许勾选，执行后明细出现已跳过。
8. 全部群均跳过：任务正常完成，失败数为 0，跳过数准确。
9. 全局群组列表仍保留群链接和群资料。
10. 事件及日志中不存在 participant 手机号、PN 或 LID。
