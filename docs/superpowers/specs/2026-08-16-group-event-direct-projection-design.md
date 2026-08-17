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

---

## 17. 实施前核实结论与修订（2026-08-17 追加）

本节为实施前对三个仓库的代码核实结果。第 1-16 节为 2026-08-16 原稿，不修改；凡本节与原稿冲突处，**以本节为准**。

核实基线：`armada` 分支 `1.0.3-group` @ `9fb70bf1`（与 origin 一致，Flyway 已到 V125）；`armada-protocol` 分支 `1.0.3-group`；`whatsapp-server-feature-android-zhuan` 分支 `1.0.3-snapshot` @ `de7d18f`。

### 17.1 原稿仍然成立的事实

| 原稿断言 | 核实结果 |
|---|---|
| Web `add/remove` 后仍发 `account.group_metadata_sync_requested` | 成立，`account-manager.ts:1524-1529` |
| Web `groups.update` 后仍发 `METADATA_CHANGED` | 成立，`account-manager.ts:1582-1587` |
| `group.metadata_updated` 已预留、无生产逻辑、列为 best-effort | 成立，`subjects.ts:47,97` |
| `group.participant_changed` 已发布、CRITICAL、进 group topic | 成立，`event-bridge.ts:169`、`subjects.ts:41,81` |
| Armada 只放行 `promote/demote` | 成立，`ProtocolGroupEventConsumer:175-186` |
| Armada 无 `group.metadata_updated` consumer | 成立，`ProtocolGroupEventConsumer:163-172` 仅 6 个 case |
| Android WGP2 仅接受 9 个 action，其余返回 nil | 成立，`group_notification.go:31-128`，默认分支 `:92-94` |
| Android 明确忽略 `subject`；头像 handler 为空 | 成立，`notification.go` `handlePictureNotification` 空实现 |
| Android ACK 早于业务解析 | 成立，`notification.go:88` 先 ACK，`:92-93` 后分派 |

结论：原稿的问题诊断方向有效，第 4 节目标不需要改写。

### 17.2 原稿已过时，必须修订的五处

1. **§7.2 "迁移期继续双写 `group_link_preview`/`group_link` 兼容字段" —— 作废。**
   V123 已将 `group_link` 改为句柄引用（`group_link.group_id` / `group_invite_id`），`wa_group_profile` 是唯一权威。**不得双写**，实施范围相应缩小。

2. **§8.1.4 的工作量被低估 —— 由"改造映射"改为"新写映射"。**
   §3.1 列举的 `subject/desc/announce/restrict/inviteCode/memberAddMode/joinApprovalMode` 是 Baileys 事件**可能携带**的字段，不是协议层已实现的映射。当前 Web `groupsUpdateHandler` **只提取 `inviteCode`**（`account-manager.ts:1564-1571`）。§8.1.4 需从零编写 7 字段映射与属性存在性判断。

3. **§8.1.6 "修正 `Boolean(undefined) -> false` 未知值误报" —— 删除该项。**
   现有代码使用 `stringValue()` 与 `?? null`，未发现该缺陷。

4. **§7.2 `field_version_keys` 前置条件已确认成立 —— 本功能自带 Flyway。**
   `wa_group_profile`（V120:26-65）只有整行水位 `metadata_observed_at`（V120:40），**无 `field_version_keys`**。按 §7.2 兜底条款，本功能分配 **V126**（V125 为当前最新）。
   利好：该表已含全部 7 个业务字段列（`subject`/`description`/`announce_only`/`admin_only_edit_info`/`member_add_mode`/`join_approval_mode`/`ephemeral_duration_seconds`），§6.2 白名单无需建表。
   `wa_group_participant`（V120:105-155）已有 `presence_status`（:114）与 `role`（:120），§7.1 可直接落地。

5. **§9 "禁止入口"清单缺一条，且原有表述会误伤合法链路。**
   Android `GroupSnapshotCoordinator` 存在两种触发，必须分开对待：
   - ONLINE 后延迟 30-45s 首次全量快照（`group_snapshot_coordinator.go:145,197`）—— 属 §9 **允许**的"首次完整建档"，**保留**；
   - `dirty(groups)` 脏标记 + 1s 防抖刷新 —— 属 §9 禁止的"事件后置全量查询"，是本设计的处置对象。
   注意：脏标记链路兼任 `RemovalAuthoritative` 授权语义（`groups_fetcher.go:27-32,127`：只有读了成员明细才置真，armada 才据此判退群）。砍它必须同步解决"谁授权判退群"，不可单独删除。

### 17.3 原稿未覆盖的新缺口

1. **armada → Android 主动刷新通道断开。**
   `AccountGroupSyncJob:47` 定时下发 `ProtocolAccountGroupSyncCommandRequest`，**不按 backend 分叉**。Web 有对应 handler（`account.groups_sync.requested`，`worker-consumer.ts:143-145,614`）；Android 命令白名单（`start.go:377-420`）共 9 个命令，**不含群列表同步命令**，命令进入永久失败处理（`start.go:548-558`）。
   影响：不影响 Android 首次上线落库（该链路自主发起）；影响的是定时补偿同步——Android 只能靠"首次快照 + 脏标记事件"，事件漏投时无兜底。附带每轮定时任务产生永久失败命令，污染失败指标与 DLQ。
   **对本设计的意义**：§9/§10 把"低频对账"当作事件漏投的安全网，而这张网对 Android **当前不存在**。§12.1"Armada consumer 可先于生产端部署"的前提（两端能力对称）不成立。

2. **`group.members.result_reported` 已有独立落库路径**，而 §7.1 要求"完整快照也必须通过同一 reducer 写字段"。该现有链路是否纳入改造，决定 §13 是 8 项还是 9 项。**待定。**

3. **Android 首次基线缺 `description` 与 `ephemeralDuration`。** 见 17.4。

### 17.4 首次基线实际字段覆盖（本设计的地基）

本设计的增量维护模型隐含前提：**首次基线必须覆盖全部要展示的字段**。事件只报"变了什么"，不报"现在是什么"——基线缺失的字段不会被增量补回，存量群将永久为空。核实结果：

| 字段 | Web 首次基线 | Android 首次基线 |
|---|---|---|
| `subject` | 有 | 有 |
| 成员列表 / admin | **无**（补丁主动拒绝，收到 participant 节点抛错） | 有（仅上报 admin 布尔） |
| `announceOnly` | 无 | 协议已取得，**映射未上报** |
| `adminOnlyEditInfo`（`Locked`） | 无 | 协议已取得，**映射未上报** |
| `memberAddMode` | 无 | 协议已取得，**映射未上报** |
| `joinApprovalMode`（`GroupJoinState`） | 无 | 协议已取得，**映射未上报** |
| `description` | 无 | **IQ 响应不含该字段** |
| `ephemeralDurationSeconds` | 无 | **IQ 响应不含该字段** |

- Web 使用协议层自定义轻量方法 `groupFetchParticipatingSummaries()`（Baileys 补丁 `baileys+7.0.0-rc11.patch:9-12`），构造 `<participating/>` 空选择器，**故意**不请求 participants 与 description；上线触发链 `account-manager.ts:1678-1721 → :1721 → :2084`，延迟 30-45s（常量 `:80-81`）；payload 仅 `groupJid + subject`（`:2110-2114`）。
- Android `GroupInfo`（`entity.go:37-58`）已含 `Announce`/`Locked`/`MemberAddMode`/`GroupJoinState`/`Participants`，但 `ReportedGroup`（`event.go:37-41`）只序列化 3 个字段，映射处 `groups_fetcher.go:117-121` 将其余丢弃。

### 17.5 已确认决策（2026-08-17）

**决策 1：Web 首次上线改为拉取完整 metadata，并强制并发限流。**

推翻 `2026-07-11-lightweight-participating-group-sync-design.md` 的轻量结论（该文 `:5-11` 理由、`:98-99` 明文禁止回退完整查询）。改为在 `syncParticipatingGroups`（`account-manager.ts:2084`）使用 Baileys 原生 `groupFetchAllParticipating()`，一次取得 `GroupMetadata` 全部字段。

- 收益：Web 基线达成 **7/7 覆盖**，含 `desc` 与 `ephemeralDuration`，两个原本缺失的字段在 Web 侧自动解决。
- 已有可复用映射：`readGroupMetadata()`（`account-manager.ts:703-722`）。
- **约束（不可拆分）**：0711 指出的"批量账号上线时无全局并发限制"是本决策的主要风险。本项必须作为一个整体实施，只做前半句（改用完整查询而不补限流）视为未完成：
  1. 全局并发闸，限制同时进行的完整 metadata 查询数；
  2. 每账号内串行 + 群数分批；
  3. 大群成员数上限保护（`participants[]` 是 V8 heap 主要来源）；
  4. 查询失败不阻塞上线主链路；
  5. 按 0711 `:87-95` 的口径采集 groupCount / participantNodeCount / 查询耗时 / heap 摘要，作为验收证据。

**决策 2：Android 先补齐映射，把已取得的 4 个字段上报。**

扩展 `ReportedGroup`（`event.go:37-41`）并修改映射 `groups_fetcher.go:117-121`，上报 `announce/restrict/memberAddMode/joinApprovalMode`，armada 侧同步接收。成本低、与决策 1 无耦合，可并行。Android 基线由 1/7 提升至 5/7。

**悬置项**：Android 的 `description` 与 `ephemeralDurationSeconds` 不在 `GetAllGroup` IQ 响应内，补齐口径未定，留待 §13 第 6 项（Android producer）时决定。

### 17.6 与相邻设计的关系重排

原稿默认可直接实施。核实后确认存在前置依赖：

- 本设计的增量维护模型**要求基线足够厚**（17.4）；
- "如何在不触发 heap 峰值的前提下把基线做厚"（异步、限流、按群去重、账号轮换）是 `2026-08-16-group-snapshot-kafka-sync-design.md` 的主题；
- 因此两份 0816 设计**不是补集关系，而是前后依赖**：先解决基线建档，再上直接投影。在只有群名的基线上启用直投影，增量维护的将是一批空字段。
- `AccountGroupSyncJob` 当前实际承担"把基线补厚"的兜底职责。§9 要收缩它的触发范围前，替代物必须先到位。

### 17.7 已证伪的推测（留档避免重复排查）

- 曾疑 Android 不发 `snapshotComplete`、导致快照永不判完整、退群不落库 —— **证伪**。`event.go:96` 有 `SnapshotComplete *bool`，`:169` 计算 `RemovalAuthoritative && SkippedGroupCount == 0`，`:188` 赋值；首次上线 `GroupBaselineReady=false` → 读成员明细 → `RemovalAuthoritative=true`，无跳过群时为 `true`。armada `completeSnapshot():188` 的 WEB 特例 Android 走不到，但 Android 自带显式标记，不漏退群判定。
- 曾将 Android ONLINE 后 30-45s 首次快照误判为"应砍的事件后置查询" —— **纠正**，见 17.2 第 5 条。

### 17.8 修订后的任务顺序（替代 §13）

0. **基线策略前置**：决策 1（Web 完整查询 + 并发限流）与决策 2（Android 映射补齐）；armada 侧扩展 `account.groups_reported` 接收字段。
1. 契约 fixtures：Web `groups.update` / `group-participants.update`；Android WGP2 真实脱敏样本（§16.3 唯一未解前置项，卡第 6 项，不卡前面）。
2. Armada V126 `field_version_keys` + `GroupMetadataPatchService`（fieldMask + 逐字段版本）+ `group.metadata_updated` consumer 与 DTO。**不含双写**（17.2 第 1 条）。
3. 成员 reducer：放行 `add/remove/modify`，统一 Web/Android 增量。
4. Web producer：新写 7 字段映射（非改造），移除两处 metadata 请求。
5. Android producer：WGP2 群资料 patch 分支。
6. Android 群列表同步命令 handler（17.3 第 1 条），恢复 armada→Android 主动刷新与低频对账兜底。
7. 监控与开关。
8. test1 集成验收。

### 17.9 §14.3 验收标准的修订

原"上述正常事件不产生 metadata HTTP/IQ 查询"需限定范围：ONLINE 后首次全量建档属允许查询（决策 1 更使其成为**必需**的重量查询）。验收口径改为：**首次建档后，群成员与群资料变更不再产生 metadata 查询**；首次建档本身按决策 1 的限流指标单独验收。
