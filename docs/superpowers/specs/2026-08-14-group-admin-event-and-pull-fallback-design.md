# 群管理员事件事实与拉群缺失兜底设计

日期：2026-08-14  
状态：已确认，进入实施

## 1. 背景与已确认口径

普通链接拉群的管理员设置阶段严格从 `account_group_membership.is_admin=1` 选择我方在线管理员。
任务 #122 的完整成员快照已经存在多个我方管理员，但账号群关系里的 `is_admin` 仍为 `NULL`，
因此执行行在创建提权动作前就进入 `MANAGER_ADMIN_ACTOR_UNAVAILABLE`。

当前 Web 协议层可以收到 Baileys `group-participants.update`，并发布包含
`groupJid + action + participants + operator` 的 `group.participant_changed`。后端尚未消费该事件；
协议层同时把成员变化退化成不含目标和动作的 metadata 同步请求，后端只能等待完整群 metadata
查询后间接修复管理员事实。

Android Zhuan 已能解析 WGP2 群通知和 PN/LID 成员身份，但当前解析器只放行 `add/remove/leave`，
`promote/demote` 被过滤；后续群快照协调器也没有角色事件发布分支。Android 已有
`group.members.query.requested/result_reported` 定点成员查询，无需新增查询接口。

本次已确认以下口径：

1. Web 与 Android 都通过 `group.participant_changed` 提供管理员角色实时增量事实。
2. `group.action_result_reported` 只负责拉群任务动作状态，不额外双写全局管理员事实。
3. `promote/demote` 事件不再触发常规完整 metadata 查询；本地找不到管理员时，拉群任务才异步查询一次当前群成员事实作为兜底。
4. 已成功群不再按固定周期自动查询完整 metadata；群资料同步改为首次建档、事件和人工操作按需触发。

## 2. 目标与非目标

### 2.1 目标

- Web 或 Android 观察到 `promote` 时，把对应受控账号关系更新为 `is_admin=1`。
- Web 或 Android 观察到 `demote` 时，把对应受控账号关系更新为 `is_admin=0`。
- 同步更新现有 `whatsapp_group_member_state` 和群详情成员快照，避免列表、详情和任务选号继续分歧。
- 拉群任务本地找不到管理员时，复用现有 Outbox + Kafka 异步成员查询，查询所有当前在线、正常、在群的受控账号角色，基于新鲜结果更新关系后重新选号。
- 移除 `SUCCEEDED` 群默认 60 秒再次到期的后台轮询，避免受控群规模扩大后持续消耗协议流量。
- 一次性唤醒部署前已因 `MANAGER_ADMIN_ACTOR_UNAVAILABLE` 等待的活动执行行，使 #122 类型任务进入新兜底。
- 重复、迟到事件不得覆盖更新事实；调度线程不得同步等待协议网络请求。

### 2.2 非目标

- 不让 `group.action_result_reported` 写 `account_group_membership`，避免命令回执和 WhatsApp 观察事件形成两条实时写入口。
- 不对历史成员快照执行静态批量回填；旧快照可能晚于真实降权或退群，不能直接作为当前事实。
- 不删除新群首次快照、`add/remove`、`groups.update`、用户手动刷新等按需 metadata 同步能力。
- 不让 Android 角色事件触发全量群快照；Android 与 Web 一样只发布本次变化成员的增量事实。
- 不新增同步 HTTP 查询，不增加拉群派发线程数。

## 3. 总体数据流

### 3.1 实时角色事件主链

```text
WhatsApp GROUP_PARTICIPANT_PROMOTE / DEMOTE
  -> Web: Baileys group-participants.update
     Android: WGP2 notification promote/demote
  -> protocol group.participant_changed（统一契约）
       tenantId / accountId / protocolAccountId / protocolBackend
       groupJid / action / participants(PN/LID) / operator / source
  -> Armada ProtocolGroupEventConsumer
  -> GroupParticipantRoleService
  -> whatsapp_group_member_state
  -> whatsapp_group_member_snapshot（已有行即时更新）
  -> account_group_membership.is_admin
```

Web 只对 `promote/demote` 停止发布 `account.group_metadata_sync_requested`；`add/remove` 仍沿用现有
metadata 刷新。Android 解析器新增 `promote/demote`，群快照协调器发布统一角色事件后立即返回，不安排
`GetAllGroup(true)`。`groups.update` 的群名、设置和邀请链接处理保持不变。

### 3.2 拉群本地缺失兜底

```text
MANAGER_ADMIN 阶段本地管理员候选为空
  -> 查询本群在线、正常、仍在群内的受控账号（不要求 is_admin=1）
  -> 选择其中一个账号作为只读查询 actor
  -> group.members.query.requested
       purpose = MANAGER_ADMIN_DISCOVERY
       targetJids = 本轮所有可执行受控账号，最多 500 个
  -> Web/Android 在账号群操作闸门内读取一次完整 metadata
  -> 只返回 targetJids 对应的 inGroup/admin 事实
  -> GroupParticipantRoleService 写入成员状态和账号群关系
  -> 精确唤醒原 MANAGER_ADMIN 执行行
  -> 重新按 account_group_membership.is_admin=1 选号
```

成员查询继续使用现有异步表、Outbox、Kafka 命令与结果事件。协议端虽然读取一次完整 metadata，
但只回传最多 500 个受控目标的事实，不回传无关成员，也不阻塞拉群派发线程。

查询成功但没有任何可用管理员时，执行行进入既有 `WAIT_RESOURCE + MANAGER`；查询临时失败时按现有
成员查询退避创建下一次尝试。成功结果使用稳定业务键复用，禁止在无管理员时高频循环查询。

### 3.3 群 metadata 改为按需同步

```text
新群创建/首次纳管 -> BASELINE_CAPTURED -> 完整 metadata
成员 add/remove    -> PARTICIPANT_CHANGED -> 完整 metadata
群名/设置变化      -> METADATA_CHANGED -> 完整 metadata
用户点击刷新       -> MANUAL_REFRESH -> 完整 metadata
同步成功           -> SUCCEEDED，next_run_at=NULL，不再自动重排
```

保留现有 `GroupMetadataSyncJob`，因为它同时承载首次快照、事件刷新、失败重试和手动刷新；只删除
`SUCCEEDED` 任务按默认 60 秒进入后台刷新候选的分支。部署前已经带有 `next_run_at` 的成功任务也不再
被调度，不对一万行存量任务做批量更新。

## 4. 协议事件契约

`group.participant_changed` 保持现有事件名和 group topic，Web/Android 使用同一正文；以下以 Web 为例：

```json
{
  "tenantId": 1,
  "accountId": 100,
  "protocolAccountId": "acc_100",
  "protocolBackend": "WEB",
  "groupJid": "120363xxx@g.us",
  "action": "promote",
  "participants": [
    {
      "id": "100001234567890@lid",
      "lid": "100001234567890@lid",
      "phoneNumber": "8613800000000@s.whatsapp.net"
    }
  ],
  "operator": "8613900000000@s.whatsapp.net",
  "source": "wa_group_participants_update"
}
```

后端校验：

- envelope `accountId` 必须等于 `data.protocolAccountId`；
- `tenantId/accountId` 必须为正数，`protocolBackend` 只接受 `WEB/ANDROID`；
- `groupJid` 必须以 `@g.us` 结尾；
- `action` 接受 Baileys 现役集合，但只有 `promote/demote` 写管理员事实；
- participants 最多 500 个，每项至少包含合法 `id/lid/phoneNumber` 之一；
- 事实时间使用 envelope `occurredAt`，事件 ID 用于同时间确定性裁决和日志关联。

协议层仍可靠投递该事件；事件发布失败沿用现有 producer 重试与 DLQ，不阻塞 Baileys 事件循环。

两端生产规则：

- Web 复用现有 event bridge，补齐 socket 绑定的租户、Armada 账号和协议账号上下文；
- Android WGP2 解析器放行 `promote/demote`，复用现有 PN/LID resolver，保留通知 ID、发生时间和可选操作人；
- Android 群快照协调器通过已维护的 `phone -> CommandContext` 映射补齐业务上下文，写入
  `protocol.group.events.v1`；无法关联在线业务上下文时不发布，等待任务定点查询兜底；
- 两端角色事件都不触发完整 metadata 或全量群列表查询。

## 5. 群成员与账号群关系事实

复用现有 `whatsapp_group_member_state`，不新增表或列。新增状态来源：

- `ROLE_EVENT`：WhatsApp `promote/demote`；
- `MEMBER_QUERY`：拉群定点成员查询。

同一成员的优先级保持“精确退出 > 精确角色事件 > add > 定点查询/完整快照”。先比较
`state_updated_at`，时间相同时再比较来源优先级和 `source_event_id`，确保重复事件幂等、旧事实不覆盖新事实。

成员身份规范化规则：

- 优先保留协议给出的 `id/lid` 作为 `participant_jid`；
- 使用 `phoneNumber` 或 PN JID 提取纯数字手机号；
- LID 事件没有手机号时，复用同一成员状态行已有的 phone；
- 仍无法解析到受控账号时只保存成员状态，不猜测账号；后续定点查询或完整快照补齐 phone 后再对齐关系。

角色事实写入成员状态后，只对本次变更成员按当前租户、群 JID 和手机号匹配未软删受控账号：

- 在群管理员：`membership_status=IN_GROUP, is_admin=1`；
- 在群普通成员：`membership_status=IN_GROUP, is_admin=0`；
- 定点查询确认不在群：使用既有非在群状态并清除管理员角色。

账号群关系继续复用现有 `status_updated_at + status_source` 时序保护。新增
`WGP2_PROMOTE/WGP2_DEMOTE/GROUP_MEMBER_QUERY` 来源优先级，不新增第二套管理员列。

## 6. 拉群状态机改动

`PullTaskManagerAdminTransactionService.prepare` 保留原本的严格管理员候选查询。只有候选列表为空时：

1. 查询最多 500 个在线、正常、未风控、未禁言、协议身份完整且本地仍在群的受控账号；
2. 列表为空则沿用 `MANAGER_ADMIN_ACTOR_UNAVAILABLE` 等待；
3. 列表非空则返回管理员发现工作项，不创建 PROMOTER 角色或 PROMOTE_MANAGER 动作；
4. Processor 使用 `PullTaskMemberQueryAwaitService` 发起 `MANAGER_ADMIN_DISCOVERY`；
5. PENDING 时原子释放执行租约，结果事件提交后精确唤醒；
6. 新鲜结果已在回调事务中写入全局关系，重新 prepare 时自然选到管理员；
7. 查询成功仍无管理员时进入既有资源等待，不循环发送查询。

现有 `MANAGER_ADMIN_MEMBERSHIP` 保持不变，继续用于提权动作提交后的目标权限确认。
`group.action_result_reported` 继续只更新 `pull_task_account_action`、任务角色状态和执行阶段。

## 7. 数据迁移与历史修复

新增下一可用 Flyway 版本，仅做一次性状态迁移，不变更 schema：

- 仅处理父任务仍在执行、执行行非终态、阶段为 `MANAGER_ADMIN`、原因为
  `MANAGER_ADMIN_ACTOR_UNAVAILABLE` 的行；
- 从 `WAIT_RESOURCE` 恢复为 `EXECUTING`，清除资源等待字段，`next_run_at=0`；
- 不修改已完成、失败、放弃或其他原因等待的执行行；
- 不从旧 `whatsapp_group_member_snapshot` 静态回填管理员。

这样 #122 类型存量任务会执行一次新鲜管理员发现查询；其他历史群在下次角色事件或业务确实需要
管理员时逐步修复，不依赖全群周期轮询。

## 8. 错误、并发与性能

- 多个 Web/Android 账号可能观察到同一次角色变化；数据库按成员、群和事实时间幂等收敛。
- 不依赖“被提权账号本人一定在线并收到事件”；任一携带完整业务关联的 Web/Android 观察者都可以更新目标。
- LID 暂不可解析不是消费失败，保存成员状态后正常确认 Kafka；避免毒消息阻塞同 topic。
- 不存在可查询账号时不创建 Outbox，执行行进入资源等待。
- 定点查询沿用账号级群操作闸门和现有超时/退避；同一执行行同时最多一个发现查询。
- 正常事件路径只有有界 JSON 解析和少量数据库 upsert，不发起网络查询。
- 管理员兜底新增的完整 metadata 网络读取仅在本地管理员候选为空时发生，且异步执行，不占用拉群派发线程；
  其他完整 metadata 查询只由首次建档、成员/群资料事件、失败重试或用户手动刷新触发。
- 一万群若都按 60 秒刷新，目标吞吐约为每秒 167 次完整 metadata 查询；现有“每轮最多一个后台刷新”
  虽然限制了瞬时压力，却只会形成持续查询和不断积压。删除成功群周期重排后，空闲系统不再产生全群轮询流量。

## 9. 测试与验收

### 9.1 协议层

- Web `promote/demote` 发布含租户、Armada 账号、协议账号、PN/LID 和来源的完整事件；
- Web `promote/demote` 不再发布 metadata 同步请求；
- `add/remove` 仍发布原 metadata 同步请求；
- 无业务引用、旧 socket generation 和 terminating socket 不发布业务事件；
- Android WGP2 解析器放行 `promote/demote`，保留 PN/LID、通知 ID、时间和可选操作人；
- Android 协调器发布 `protocolBackend=ANDROID` 的同契约事件且不安排全量群快照；
- Android 无账号业务上下文时安全跳过；原有 `add/remove/leave` 群快照与成员事件不回归；
- 两端 Kafka 路由均为 group topic 且保持可靠投递。

### 9.2 后端事件与群事实

- consumer 拒绝账号关联不一致、非法协议后端、非法群 JID、空身份和超限 participants；
- Web/Android 同契约事件分别通过并落到同一群事实服务；
- promote/demote 分别更新成员状态、详情快照和受控账号关系；
- 外部号码不创建账号群关系；
- LID 可复用已有 phone 映射；无法解析的 LID 安全保存且不误绑账号；
- 重复、旧事件和同时间低优先级快照不覆盖新角色事实；
- H2 MySQL 模式加载真实 Mapper XML，覆盖租户隔离和 SQL 时序。

### 9.3 拉群兜底

- 本地存在管理员时不创建发现查询；
- 本地无管理员但存在在线在群账号时创建一次 `MANAGER_ADMIN_DISCOVERY`；
- PENDING 释放租约，结果成功精确唤醒原阶段；
- 新鲜结果把受控管理员写入关系，下一轮选择该账号创建提权动作；
- 查询成功无管理员时进入 WAIT_RESOURCE，且不循环创建查询；
- 查询失败按退避重试，不提交 PROMOTE；
- 迁移只唤醒符合条件的历史活动行。

### 9.4 metadata 调度

- 新群 `BASELINE_CAPTURED`、事件触发、手动刷新和失败重试仍可被调度；
- 同步成功后保持 `SUCCEEDED + next_run_at=NULL`；
- `SUCCEEDED` 历史行无论 `next_run_at` 是否已到期，都不会仅因时间到期再次执行；
- 删除周期候选不会影响运行中任务的完成、租约恢复和事件触发的重新入队。

### 9.5 #122 验收

部署到明确确认的 test1 后：

1. 观察执行行 169 从管理员资源等待恢复；
2. 只产生一条管理员发现成员查询 Outbox；
3. 查询结果识别当前在线的我方管理员并更新 `account_group_membership.is_admin`；
4. 执行行重新选择提权 actor，创建 `PROMOTE_MANAGER` 动作；
5. 设置成功后继续进入联系人和拉手阶段；
6. 确认没有因本次角色事件再产生完整 metadata 查询风暴。

## 10. 部署与回滚

部署顺序：先部署后端事件 consumer、群事实服务、拉群兜底及 Flyway。Flyway 唤醒的历史任务可以立即
使用两端已经存在的成员定点查询，不依赖新角色事件；再部署 Web 与 Android 的完整事件生产。后端先兼容
`WEB/ANDROID` 并跳过缺少业务关联的旧事件，因此新协议事件开始发布时已有消费者接收。

回滚时先停止新版本调度器，再回滚后端和协议层应用。回滚到旧后端会恢复 `SUCCEEDED` 群的周期
metadata 查询，需在确认协议流量可接受后执行。迁移只把符合条件的等待行恢复为可执行；旧版本会
再次按原逻辑将其放回等待，因此不需要反向数据迁移。新增的成员状态来源字符串对旧代码无破坏性。

## 11. 方案取舍

- 否决“命令成功回调同时写全局管理员”：会形成两条实时写入口，且无法覆盖手机端或其他管理员操作。
- 否决“每个角色事件后查询完整 metadata”：网络成本高，并会再次制造 metadata 队列积压。
- 否决“所有成功群固定周期查询 metadata”：一万群规模下持续耗费流量，且限流后数据仍会过期数小时甚至数天。
- 否决“Android 只靠拉群时点查”：任务可以自愈，但普通群列表和后续任务仍会长期持有旧管理员事实。
- 否决“直接回填全部历史快照”：旧快照可能复活已降权或已退群关系。
- 采用“事件增量主链 + 业务按需 metadata + 拉群缺失时异步点查”：正常路径最轻，同时给活跃业务提供新鲜事实修复能力。
