# WhatsApp 群变更事件直投影设计

日期：2026-08-16  
状态：方案待评审，尚未实施  
涉及仓库：`armada-protocol`、`whatsapp-server-feature-android-zhuan`、`armada`

## 1. 结论先行

群成员变更与群资料变更的原始事件已经携带本次变化事实，正常路径不再查询完整 metadata：

- `group-participants.update` 直接更新成员在群状态、管理员角色和受控账号群关系；
- `groups.update` 按报文实际出现的字段直接更新群资料；
- Android WGP2 对等节点解析为同一套成员事实或群资料 patch；
- 完整 metadata 查询只保留给首次建档、人工刷新、异常修复和低频对账，不再作为每条群变更事件的后置动作。

实现必须保留“未出现、明确 false、明确清空”三种语义。字段未出现在事件中时不得覆盖数据库；
布尔值 `false` 必须落库；描述清空必须以 `fieldMask + null` 表达，不能因为 JSON `omitempty`
或 Java DTO 空值而丢失。

本方案只改变群事件后的同步方式，不取消 metadata 查询能力，也不改变现有页面 API。

协议 ACK 只表示 WhatsApp 节点已被客户端接收，不代表事件已经被业务接受。本文中的“处理完成”必须同时满足：

1. 协议端把原始节点解析成合法的成员事实或群资料 patch；
2. 事件成功写入 Kafka，或在 Kafka 重试耗尽后成功写入本地 DLQ；
3. Armada consumer 幂等落库成功并提交 Kafka offset；
4. 控端通过现有查询 API 能读取到最新值。

只发送 ACK、解析返回 nil、只打印日志或只更新协议内存缓存，均不算完成。

## 2. 已确认需求

用户已确认以下两类事件不需要再次查询 metadata：

1. 普通成员加入或退出：事件已有群 JID、动作、成员 PN/LID 和操作人，可以直接形成增量事实。
2. 群名、描述和群设置变化：`groups.update` 已携带发生变化的字段，可以直接形成字段级 patch。

进一步沿用 2026-08-14 已确认口径：`promote/demote` 已经走角色增量事件，不触发完整 metadata。

## 3. 当前事实与缺口

### 3.1 Web/Baileys 事件事实

当前安装的 Baileys 事件类型为：

- `group-participants.update`：`id`、`author/authorPn/authorUsername`、`participants[]`、`action`；
- `groups.update`：`Partial<GroupMetadata>[]`，只携带本次可观察到的字段；
- 成员动作包括 `add/remove/promote/demote/modify`；
- 群资料事件当前明确映射 `subject/desc/announce/restrict/inviteCode/memberAddMode/joinApprovalMode`；
  限时消息通过群通知中的 `EPHEMERAL_SETTING` 携带 `ephemeralExpiration`。

### 3.2 当前多余查询

Web `AccountManager` 当前存在两条事件后查询链：

1. `group-participants.update(add/remove)` 发布 `account.group_metadata_sync_requested`；
2. `groups.update` 发布 `account.group_metadata_sync_requested`。

后端收到请求后进入 metadata 任务队列，再选在线账号读取整群资料和完整成员数组。该流程增加协议流量、
写入延迟和队列积压，还可能让后返回的完整快照覆盖更新的增量事件。

### 3.3 当前控端行为

- `group.participant_changed` 已可靠写入 group topic，但 Armada 只处理 `promote/demote`，明确忽略
  `add/remove`；
- `group.metadata_updated` 已在 Web 事件枚举中预留，但没有生产逻辑，当前被列为 best-effort，
  Armada 也没有 consumer 分支；
- Android 已用独立事件上报 `add/remove/leave`、`promote/demote`、邀请码和终止状态；
- Android 对所有合法 `notification` 先发 ACK，再执行业务分派；因此当前 ACK 数量不能作为群事件解析成功率；
- Android WGP2 当前只接受 `create/invite/suspended/terminated/add/remove/leave/promote/demote`，
  `subject/description/announcement/not_announcement/locked/unlocked/member_add_mode/
  membership_approval_mode/ephemeral/not_ephemeral` 会在解析器返回 nil 后丢失；
- 当前 `wa_group_profile.metadata_observed_at` 是整行水位，不能安全承载多个独立字段的乱序 patch。

## 4. 目标与非目标

### 4.1 目标

- 群成员和群资料事件正常路径产生零次 metadata 网络查询；
- Web/Android 的同类事实进入 Armada 后复用同一个领域 reducer；
- 事件字段缺失不覆盖旧值，明确 false/0/空描述可以正确落库；
- 重复、迟到、跨观察账号重复事件幂等收敛；
- 群成员变化同时维护成员事实、成员缓存、成员数投影和受控账号群关系；
- 群名、描述以及发言权限、资料编辑权限、成员拉人权限、入群审批和限时消息设置必须由事件直接更新控端数据库；
- 事件无法直接解释时进入可观测的异常/校准流程，不在事件消费线程同步查询协议。

### 4.2 非目标

- 不删除首次建档、人工刷新、异常修复使用的完整 metadata 查询；
- 不要求事件携带完整群成员列表；
- 不用群事件直接推送前端页面，控端仍通过数据库投影和现有查询 API 展示；
- 本期不猜测头像等当前事件没有明确携带的字段；
- 不把 WhatsApp 本地显示名、备注与 WhatsApp `subject/description` 混写。

## 5. 总体数据流

### 5.1 成员增量

```text
WhatsApp member notification
  -> Web group-participants.update / Android WGP2
  -> group.participant_changed（统一成员增量）
  -> ProtocolGroupEventConsumer
  -> GroupParticipantEventReducer
       add       -> presence=IN_GROUP
       remove    -> presence=OUT_OF_GROUP
       promote   -> role=ADMIN
       demote    -> role=MEMBER
       modify    -> 合并 PN/LID 身份，不改变未观察到的角色
  -> wa_group_participant / 兼容成员状态表
  -> 受控账号匹配后更新 wa_account_group_binding / account_group_membership
  -> 更新成员缓存及可安全推导的 member_count
```

### 5.2 群资料字段级 patch

```text
WhatsApp group metadata notification
  -> Web groups.update / Android WGP2 metadata node
  -> group.metadata_updated（统一字段级 patch）
  -> ProtocolGroupEventConsumer
  -> GroupMetadataPatchService
  -> 按 fieldMask + 每字段版本，只更新被观察字段
  -> wa_group_profile + 迁移期旧表兼容投影
```

### 5.3 metadata 校准旁路

```text
首次建档 / 人工刷新 / 异常修复 / 低频对账
  -> GroupMetadataSyncTask
  -> 完整 metadata 查询
  -> 同一个字段 reducer + 完整成员快照 reducer
```

完整快照不是增量事件的后置步骤；两条链只在同一个后端 reducer 汇合。

## 6. 事件契约

### 6.1 `group.participant_changed`

沿用现有事件名和 `protocol.group.events.v1`，扩展 Armada 对 `add/remove/modify` 的消费，不新增 topic。

```json
{
  "eventId": "acc-100:group.participant_changed:...",
  "event": "group.participant_changed",
  "version": "v1",
  "accountId": "protocol-account-100",
  "occurredAt": "2026-08-16T04:30:00.000Z",
  "data": {
    "tenantId": 1,
    "accountId": 100,
    "protocolAccountId": "protocol-account-100",
    "protocolBackend": "WEB",
    "groupJid": "120363xxx@g.us",
    "action": "add",
    "participants": [
      {
        "id": "123456789@lid",
        "lid": "123456789@lid",
        "phoneNumber": "8613800000000@s.whatsapp.net"
      }
    ],
    "operator": "8613900000000@s.whatsapp.net",
    "source": "wa_group_participants_update"
  }
}
```

规则：

- action 接受 `add/remove/promote/demote/modify`；
- participants 最多 500 个，每项至少有一个合法 `id/lid/phoneNumber`；
- PN/LID 都按 user-level JID 规范化，禁止把 LID 猜成手机号；
- `remove` 的退出类型按 operator 与唯一目标的可靠身份比较：同一人为 `LEFT`，明确不同时为
  `REMOVED`，无法比较或批量 remove 为 `UNKNOWN`；无法判定原因不影响 presence 变为不在群；
- 目标参与者是当前观察账号本人时，同事务更新账号群关系；其他受控账号按可信 PN 匹配后更新关系；
- Web 不再为 `add/remove` 发布 metadata 同步请求。

Android 现有 `account.group_participant_joined/departed` 在滚动升级期间继续消费，并映射到同一个
`GroupParticipantEventReducer`。待两端稳定后再决定是否统一生产事件名，本期不强制删除兼容事件。

### 6.2 `group.metadata_updated`

复用 Web 已预留事件名，改为 critical，并路由到 `protocol.group.events.v1`。

```json
{
  "eventId": "acc-100:group.metadata_updated:...",
  "event": "group.metadata_updated",
  "version": "v1",
  "accountId": "protocol-account-100",
  "occurredAt": "2026-08-16T04:31:00.000Z",
  "data": {
    "tenantId": 1,
    "accountId": 100,
    "protocolAccountId": "protocol-account-100",
    "protocolBackend": "WEB",
    "groupJid": "120363xxx@g.us",
    "fieldMask": ["description", "announceOnly"],
    "description": null,
    "announceOnly": false,
    "author": "8613900000000@s.whatsapp.net",
    "source": "wa_groups_update"
  }
}
```

第一期字段白名单：

| 统一字段 | Web `groups.update` | Android WGP2 | 含义 |
|---|---|---|---|
| `subject` | `subject` | `subject.attrs.subject` | WhatsApp 群名 |
| `description` | `desc` | `description/body`；缺少 body 表示清空 | 群描述，null 表示明确清空 |
| `announceOnly` | `announce` | `announcement=true`；`not_announcement=false` | 仅管理员发言 |
| `adminOnlyEditInfo` | `restrict` | `locked=true`；`unlocked=false` | 仅管理员编辑资料 |
| `memberAddMode` | `memberAddMode` | `member_add_mode`: `all_member_add=true`、`admin_add=false` | 普通成员可添加成员 |
| `joinApprovalMode` | `joinApprovalMode` | `membership_approval_mode/group_join.attrs.state`: `on/off` | 开启入群审批 |
| `ephemeralDurationSeconds` | 群通知 `EPHEMERAL_SETTING.ephemeralExpiration` | `ephemeral.attrs.expiration`；`not_ephemeral=0` | 限时消息秒数，0 表示关闭 |

`inviteCode` 继续使用现有 `group.invite_link_changed`，避免一个事实形成两条写入口。头像只有取得明确事件
和内容获取口径后才能加入 fieldMask；当前不猜值。

生产规则：

- 使用属性存在性判断，不使用 truthy/`Boolean(value)` 判断；
- `false`、`0`、空字符串不能被过滤；
- `desc` 属性存在且值缺失时发布 `fieldMask=[description], description=null`；
- 一个 `groups.update` item 只发布一条 metadata patch，可同时包含多个字段；
- item 只有 `id/author`、没有受支持业务字段时不发布空事件，也不触发 metadata 查询；
- `inviteCode` 与 metadata 字段同时出现时分别发布邀请码事件和 metadata patch，但不查询 metadata。
- 正反设置节点必须成对实现并测试，不能只处理 `announcement/locked/on` 而遗漏
  `not_announcement/unlocked/off`；否则控端只能看到设置开启，无法看到关闭。
- `ephemeral` 的 `expiration` 必须是非负整数；`not_ephemeral` 明确发布 0，属性缺失或非法时不得猜值。

消费规则：

- `fieldMask` 非空、去重、只允许白名单字段；
- mask 中的字段必须具有合法 JSON 类型；未进 mask 的同名值一律忽略；
- envelope `accountId` 必须等于 `data.protocolAccountId`；
- tenant、Armada account、backend、groupJid、当前协议绑定都必须校验；
- 事件对应群尚未建档时，按 groupJid 创建最小群身份与空 profile，不创建虚假邀请码；
- 事件重复或较旧时确认消费但不覆盖更新事实。

## 7. 控端领域写入

### 7.1 成员 reducer

新增或收敛为一个 `GroupParticipantEventReducer`，复用已有：

- `GroupParticipantObservationService`；
- `WhatsappGroupMemberCacheService`；
- `AccountGroupMembershipStatusService`；
- 新群模型 participant/binding 持久化组件。

同一事件事务内完成：

1. 解析或创建群身份；
2. 规范化 PN/LID 并合并成员身份；
3. 按 action 更新 presence/role；
4. 对可信手机号匹配受控账号并更新账号群关系；
5. 更新旧成员缓存兼容投影；
6. 仅在增量前成员状态已知时计算 member_count `+1/-1`，否则保留旧人数并标记待校准，禁止盲加盲减。

### 7.2 群资料 patch reducer

新增 `GroupMetadataPatchService`。权威写入目标为 `wa_group_profile`，迁移期继续双写当前页面仍读取的
`group_link_preview/group_link` 兼容字段，但旧表不得反向覆盖新模型。

部分 patch 不能共用一个整行 `metadata_observed_at`。实现应沿用群数据模型设计中的
`field_version_keys`，为 `subject/description/announceOnly/adminOnlyEditInfo/memberAddMode/
joinApprovalMode` 分别保存版本；若目标迁移尚未包含该字段，则本功能的 Flyway 必须先补齐。

字段版本比较顺序：

1. `occurredAt`；
2. 同时间时精确事件优先于完整快照；
3. 再以 backend、observerAccountId、sourceEventId/eventId 形成确定性 total order。

完整 metadata 快照也必须通过该 reducer 写字段，不能绕过版本判断。这样较新的完整快照可以修正旧事件，
但迟到快照不会覆盖已经收到的新事件。

## 8. 协议侧改动

### 8.1 Web

1. `event-bridge` 保持发布 `group.participant_changed`，补全 `modify` 测试和发生时间/业务引用校验；
2. `AccountManager.groupParticipantsSignalHandler` 删除 `add/remove` 的
   `publishGroupMetadataSyncRequested`；
3. 保留本人识别，但本人 `add/remove` 由成员事件直接更新关系，不再因此刷新完整 metadata；账号当前群
   列表可保留低成本 participating 快照用于基线校准，不能成为成员事件的同步前置条件；
4. `groupsUpdateHandler` 将支持字段映射成 `group.metadata_updated`，删除
   `METADATA_CHANGED` metadata 同步请求；
5. `group.metadata_updated` 从 best-effort 调整为 critical，沿用 producer 重试和 DLQ；
6. 明确属性存在性，修正当前 metadata HTTP 返回中 `Boolean(undefined) -> false` 的未知值误报。

### 8.2 Android

1. 保留现有成员、角色、邀请码、终止状态事件；
2. 在 `parseWGP2GroupEvent` 增加独立的群资料 patch 分支，解析
   `subject/description/announcement/not_announcement/locked/unlocked/member_add_mode/
   membership_approval_mode/ephemeral/not_ephemeral`，不得再进入默认 nil 分支；
3. 每个节点只提取报文已有字段，形成 `fieldMask`，发布统一 `group.metadata_updated`，不读取完整群资料；
4. 当前明确忽略的 `subject` 改为字段级 patch；描述无 `body` 时明确发布 `description=null`；
   `not_ephemeral` 明确发布 `ephemeralDurationSeconds=0`；
5. 设置节点严格按第 6.2 节映射成布尔值，未知枚举值不得猜测 false，应拒绝该字段并计异常；
6. `GroupSnapshotCoordinator` 增加 metadata patch 分派，使用 Android 现有 Kafka 重试和本地 DLQ writer；
   Kafka 与 DLQ 都失败时打高优先级告警，不能以 notification ACK 掩盖业务投递失败；
7. `picture` 继续单列，先补真实报文和头像获取口径，不混入本期已确认字段；
8. 未识别 WGP2 action 记录低基数 action 分类指标，不记录敏感原始 payload；
9. 不因 metadata patch 触发 `GetAllGroup` 或单群 metadata 查询。

Android 的 notification ACK 仍应及时发送，避免 WhatsApp 重发和断链；但监控与验收必须分开统计
`node_received`、`parsed`、`delivery_confirmed`、`armada_applied` 四个阶段。ACK 不能推进任何控端业务状态。

## 9. metadata 查询保留范围

允许查询完整 metadata 的入口：

- 新群首次完整建档；
- 用户人工刷新；
- 业务明确需要完整成员快照且本地无可用快照；
- 消费到无法解释、字段校验失败或状态矛盾的事件后，由异步修复任务低频触发；
- 运维发起的低频抽样对账。

禁止入口：

- 每条 `add/remove/promote/demote` 后；
- 每条 `groups.update` 后；
- 为了更新单个群名、描述或布尔设置而读取完整成员列表；
- Kafka consumer 线程内同步调用协议 HTTP。

## 10. 幂等、乱序与异常

- Kafka message key 固定使用 protocolAccountId，保持同观察账号内顺序；跨账号观察同一群仍由字段/成员版本收敛；
- `eventId + participantJid` 或 `eventId + fieldName` 作为事实幂等键；
- 事件发生时间非法时拒绝消息并进入现有 Kafka 错误处理，不使用消费时间伪造旧事实；
- PN/LID 暂时无法对应受控账号时仍保存群成员事实，后续身份补齐再关联；
- 未知字段只计指标并跳过，不阻塞已识别字段；已知字段类型非法时整条 patch 拒绝，避免部分写；
- 目标群不存在时创建最小群身份，避免当前“找不到 group_link 就静默丢弃”；
- metadata 查询失败不影响已经接受的增量事实；修复任务失败按现有退避处理。

## 11. 配置、监控与告警

新增可回滚开关：

- Web `GROUP_EVENT_DIRECT_PROJECTION_ENABLED`：启用直投事件；
- Web `GROUP_EVENT_METADATA_FALLBACK_ENABLED`：紧急恢复旧 metadata 请求，正常为 false；
- Android 对应 direct projection 开关；
- Armada consumer 可以先于生产端部署，默认接受新事件。

建议指标：

- `group_event_received_total{backend,event,action}`；
- `group_event_parsed_total{backend,event,action,result}`；
- `group_event_delivery_total{backend,event,result}`，result 至少区分 `kafka/dlq/failed`；
- `group_event_applied_total{field_or_action,result}`；
- `group_event_stale_total{field_or_action}`；
- `group_event_unknown_total{backend,tag}`；
- `group_event_metadata_query_avoided_total{trigger}`；
- `group_metadata_repair_enqueued_total{reason}`；
- 事件到投影完成延迟直方图。

日志只记录 tenantId、Armada accountId、groupJid、action、字段名、eventId 和数量，不记录完整成员列表、
邀请 code 或原始 WGP2 payload。

## 12. 发布与回滚

### 12.1 发布顺序

1. Armada：先部署新事件 consumer、字段/成员 reducer、必要 Flyway 和指标；此时旧生产端行为不变；
2. Web：启用 direct projection，关闭两处事件后的 metadata 请求；
3. 观察 test1 事件落库、协议 metadata QPS、Kafka/DLQ 和页面查询结果；
4. Android：按已确认 WGP2 fixture 开启群资料 patch；
5. 稳定后清理仅服务旧事件后查询的代码分支，保留人工/修复任务。

### 12.2 回滚

- 协议端先打开 `GROUP_EVENT_METADATA_FALLBACK_ENABLED`，恢复旧 metadata 校准；
- 再关闭 direct projection 生产；Armada consumer 保持兼容，不需要立即回滚；
- 如需回滚后端，先确保协议端已停止生产新事件；
- 新增字段版本数据只作幂等水位，不做反向清除；回滚不删除已接受的群成员或群资料事实。

## 13. 任务拆分与执行顺序

每项控制在 4 小时内，按依赖顺序执行：

1. **契约 fixtures**：固定 Web `groups.update/group-participants.update` 与 Android WGP2 样本；
2. **Armada DTO/consumer**：接入 `group.metadata_updated`，放行 `add/remove/modify`；
3. **成员 reducer**：统一 Web/Android 增量，更新成员、缓存和账号关系；
4. **资料 reducer + Flyway**：fieldMask、逐字段版本、最小群建档及兼容双写；
5. **Web producer**：直接发布 patch，移除两处 metadata 请求；
6. **Android producer**：按明确的 WGP2 tag 映射解析并发布资料 patch，fixture 用于防回归而不是 metadata 查询前置；
7. **监控和开关**：查询避免量、延迟、异常与回滚开关；
8. **集成验收**：test1 逐项触发并核对 Kafka、数据库、页面和 metadata QPS。

## 14. 测试与验收

### 14.1 协议测试

- add/remove/promote/demote/modify 都发布完整成员事件；
- add/remove 不发布 metadata 同步请求；
- subject/desc/announce/restrict/memberAddMode/joinApprovalMode 分别生成正确 fieldMask；
- `false` 不丢失，描述清空发布 null，字段缺失不进入 mask；
- inviteCode 仍走专用事件且不重复进入 metadata patch；
- 无业务引用、旧 socket generation、terminating socket 不发布；
- Android 每种节点使用真实脱敏 fixture，未知节点安全计数。
- Android 收到但解析为 nil、Kafka 与 DLQ 均失败时测试必须失败；notification ACK 不得让该用例通过；

### 14.2 后端测试

- consumer 校验路由账号、租户、backend、groupJid、fieldMask、字段类型和人数上限；
- add/remove 更新 presence，promote/demote 更新 role，modify 合并身份；
- 本人和其他受控账号的 membership 正确更新；外部成员不创建账号关系；
- 单字段 patch 不覆盖其他字段；false/0/null 清空正确落库；
- 不同字段乱序互不阻挡，同字段旧事件不覆盖新事件；
- 重复事件幂等；跨 Web/Android 观察同一事实稳定收敛；
- 不存在的群可以最小建档且不创建虚假邀请；
- H2 加载真实 Mapper XML验证事务与租户隔离；MySQL 8.4 验证 JSON/版本比较和并发锁行为。

### 14.3 test1 验收

逐项由 WhatsApp 客户端触发：

1. 普通成员加入、主动退出、管理员移除；
2. 升管理员、降管理员；
3. 修改群名、设置描述、清空描述；
4. 开关仅管理员发言、仅管理员编辑、成员添加、入群审批；
5. 开启、修改和关闭限时消息；
6. 重置邀请链接；
7. 重复和快速连续修改同一字段。

验收标准：

- 事件到控端数据库投影 P95 小于 3 秒；
- 上述正常事件不产生 metadata HTTP/IQ 查询；
- 页面重新查询后与 WhatsApp 状态一致；
- 每个动作都能串起 `received -> parsed -> delivery_confirmed -> armada_applied`，不得只看到 ACK；
- 未变字段零误覆盖，迟到事件零回滚；
- Kafka 无持续重试，DLQ 无新增同类事件；
- 回滚开关可以恢复 metadata 校准链。

## 15. 影响清单

| 领域 | 影响 |
|---|---|
| API | 现有页面 API 不变 |
| Kafka | 复用 group topic；启用 `group.metadata_updated`；成员事件契约不破坏 |
| 数据库 | 可能补 `wa_group_profile.field_version_keys`；无新业务表 |
| Redis | 无新增 key |
| 前端 | 无代码改动，刷新/重查后展示新投影 |
| 协议流量 | 群变更正常路径不再查询完整 metadata，显著下降 |
| 隐私 | 继续只传最小 PN/LID 身份，不传完整群成员快照 |

## 16. 事实、推断与未确认项

### 16.1 已确认事实

- Baileys 两类事件已携带本次成员和资料变化；
- 当前 Web 在两处事件后发起 metadata 同步；
- Armada 当前忽略 Web `add/remove`，未消费 `group.metadata_updated`；
- Android 当前明确忽略 `subject`，头像 handler 为空；
- Android 当前 ACK 早于 WGP2 业务解析，未知群资料节点仍会被 ACK 后丢弃；
- 当前后端已有成员事实、账号群关系、群资料和邀请码写入组件可复用。

### 16.2 设计推断

- 字段级 patch 配合逐字段版本可以替代正常事件后的完整 metadata，同时保持乱序安全；
- 完整 metadata 保留为低频校准，可以覆盖无法解释或丢失事件，而不必进入每条事件主链。

### 16.3 实施前必须确认

- 用脱敏真实报文固化 Android 群名、描述、四类权限设置和限时消息 fixture，并核对第 6.2 节 tag 映射；该步骤用于防回归，
  不允许重新引入 metadata 查询；
- 确认 `wa_group_profile.field_version_keys` 随群模型迁移落地，或为本功能分配下一 Flyway；
- 确认 test1 的 Web/Android/Kafka topic 版本和启用开关；
- 头像和限时消息是否有稳定独立事件，若没有继续留在人工/修复 metadata 范围。
