# 群管理员事件事实与拉群缺失兜底设计

日期：2026-08-14  
状态：待书面评审

## 1. 背景与已确认口径

普通链接拉群的管理员设置阶段严格从 `account_group_membership.is_admin=1` 选择我方在线管理员。
任务 #122 的完整成员快照已经存在多个我方管理员，但账号群关系里的 `is_admin` 仍为 `NULL`，
因此执行行在创建提权动作前就进入 `MANAGER_ADMIN_ACTOR_UNAVAILABLE`。

当前 Web 协议层可以收到 Baileys `group-participants.update`，并发布包含
`groupJid + action + participants + operator` 的 `group.participant_changed`。后端尚未消费该事件；
协议层同时把成员变化退化成不含目标和动作的 metadata 同步请求，后端只能等待完整群 metadata
查询后间接修复管理员事实。

本次已确认以下口径：

1. `group.participant_changed` 是管理员角色的实时增量事实入口。
2. `group.action_result_reported` 只负责拉群任务动作状态，不额外双写全局管理员事实。
3. `promote/demote` 事件不再触发常规完整 metadata 查询；本地找不到管理员时，拉群任务才异步查询一次当前群成员事实作为兜底。

## 2. 目标与非目标

### 2.1 目标

- Web 观察到 `promote` 时，把对应受控账号关系更新为 `is_admin=1`。
- Web 观察到 `demote` 时，把对应受控账号关系更新为 `is_admin=0`。
- 同步更新现有 `whatsapp_group_member_state` 和群详情成员快照，避免列表、详情和任务选号继续分歧。
- 拉群任务本地找不到管理员时，复用现有 Outbox + Kafka 异步成员查询，查询所有当前在线、正常、在群的受控账号角色，基于新鲜结果更新关系后重新选号。
- 一次性唤醒部署前已因 `MANAGER_ADMIN_ACTOR_UNAVAILABLE` 等待的活动执行行，使 #122 类型任务进入新兜底。
- 重复、迟到事件不得覆盖更新事实；调度线程不得同步等待协议网络请求。

### 2.2 非目标

- 不让 `group.action_result_reported` 写 `account_group_membership`，避免命令回执和 WhatsApp 观察事件形成两条实时写入口。
- 不对历史成员快照执行静态批量回填；旧快照可能晚于真实降权或退群，不能直接作为当前事实。
- 不删除群名称、成员数、邀请链接等既有 metadata 周期对账能力。
- 不改变 Android 协议事件契约；没有 Web 角色事件的群仍由拉群定点查询和既有 metadata 对账修复。
- 不新增同步 HTTP 查询，不增加拉群派发线程数。

## 3. 总体数据流

### 3.1 实时角色事件主链

```text
WhatsApp GROUP_PARTICIPANT_PROMOTE / DEMOTE
  -> Baileys group-participants.update
  -> protocol group.participant_changed
       tenantId / accountId / protocolAccountId / protocolBackend
       groupJid / action / participants(PN/LID) / operator / source
  -> Armada ProtocolGroupEventConsumer
  -> GroupParticipantRoleService
  -> whatsapp_group_member_state
  -> whatsapp_group_member_snapshot（已有行即时更新）
  -> account_group_membership.is_admin
```

协议层只对 `promote/demote` 停止发布 `account.group_metadata_sync_requested`；`add/remove` 仍沿用现有
metadata 刷新，避免本次管理员修复顺带改变成员列表维护口径。`groups.update` 的群名、设置和邀请链接
处理也保持不变。

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

## 4. 协议事件契约

`group.participant_changed` 保持现有事件名和 group topic，数据补齐业务关联：

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
- `tenantId/accountId` 必须为正数，`protocolBackend` 固定为 `WEB`；
- `groupJid` 必须以 `@g.us` 结尾；
- `action` 接受 Baileys 现役集合，但只有 `promote/demote` 写管理员事实；
- participants 最多 500 个，每项至少包含合法 `id/lid/phoneNumber` 之一；
- 事实时间使用 envelope `occurredAt`，事件 ID 用于同时间确定性裁决和日志关联。

协议层仍可靠投递该事件；事件发布失败沿用现有 producer 重试与 DLQ，不阻塞 Baileys 事件循环。

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

这样 #122 类型存量任务会执行一次新鲜管理员发现查询；其他历史群在下次角色事件、任务兜底或既有
metadata 对账时逐步修复。

## 8. 错误、并发与性能

- 多个 Web 账号可能观察到同一次角色变化；数据库按成员、群和事实时间幂等收敛。
- 不依赖“被提权账号本人一定在线并收到事件”；任一携带完整业务关联的 Web 观察者都可以更新目标。
- LID 暂不可解析不是消费失败，保存成员状态后正常确认 Kafka；避免毒消息阻塞同 topic。
- 不存在可查询账号时不创建 Outbox，执行行进入资源等待。
- 定点查询沿用账号级群操作闸门和现有超时/退避；同一执行行同时最多一个发现查询。
- 正常事件路径只有有界 JSON 解析和少量数据库 upsert，不发起网络查询。
- 完整 metadata 网络读取仅在本地管理员候选为空时发生，且异步执行，不占用拉群派发线程。

## 9. 测试与验收

### 9.1 协议层

- `promote/demote` 发布含租户、Armada 账号、协议账号、PN/LID 和来源的完整事件；
- `promote/demote` 不再发布 metadata 同步请求；
- `add/remove` 仍发布原 metadata 同步请求；
- 无业务引用、旧 socket generation 和 terminating socket 不发布业务事件；
- Kafka 路由仍为 group topic 且保持可靠投递。

### 9.2 后端事件与群事实

- consumer 拒绝账号关联不一致、非法群 JID、空身份和超限 participants；
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

### 9.4 #122 验收

部署到明确确认的 test1 后：

1. 观察执行行 169 从管理员资源等待恢复；
2. 只产生一条管理员发现成员查询 Outbox；
3. 查询结果识别当前在线的我方管理员并更新 `account_group_membership.is_admin`；
4. 执行行重新选择提权 actor，创建 `PROMOTE_MANAGER` 动作；
5. 设置成功后继续进入联系人和拉手阶段；
6. 确认没有因本次角色事件再产生完整 metadata 查询风暴。

## 10. 部署与回滚

部署顺序：先部署协议层完整事件载荷，再部署后端事件 consumer、群事实服务和拉群兜底，最后由 Flyway
唤醒历史等待行。后端 consumer 必须兼容部署窗口内缺少业务关联的旧事件：记录并跳过，不能阻塞 topic。

回滚时先停止新版本调度器，再回滚后端和协议层应用。迁移只把符合条件的等待行恢复为可执行；旧版本会
再次按原逻辑将其放回等待，因此不需要反向数据迁移。新增的成员状态来源字符串对旧代码无破坏性。

## 11. 方案取舍

- 否决“命令成功回调同时写全局管理员”：会形成两条实时写入口，且无法覆盖手机端或其他管理员操作。
- 否决“每个角色事件后查询完整 metadata”：网络成本高，并会再次制造 metadata 队列积压。
- 否决“直接回填全部历史快照”：旧快照可能复活已降权或已退群关系。
- 采用“事件增量主链 + 拉群缺失时异步点查”：正常路径最轻，同时给活跃业务提供新鲜事实修复能力。
