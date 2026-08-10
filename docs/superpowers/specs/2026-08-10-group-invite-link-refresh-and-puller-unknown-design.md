# 群邀请链接实时刷新与拉手 UNKNOWN 收敛设计

- 日期：2026-08-10
- 涉及仓库：`armada-protocol`、`whatsapp-server-feature-android-zhuan`、`armada`
- 需求来源：Web 与 Android 都要把 WhatsApp 群邀请链接变更报给 Armada；后续通过链接进群必须使用最新链接。同时排查并修复普通拉群任务 #49 的拉手在群状态长期 UNKNOWN。

## 结论

邀请链接变更使用统一关键事件 `group.invite_link_changed`。协议事件正文携带租户、Armada 账号、
协议账号、协议后端、群 JID、新邀请码、可选操作人和来源；完整邀请码只进入事件和数据库，
日志只允许记录群入口 ID、事件 ID、协议来源和邀请码后缀。

Armada 的 `group_link_preview.invite_code` 是当前邀请码权威事实，新增
`invite_code_observed_at` 独立记录观察时间。事件按 `tenant_id + group_jid` 定位群入口；
不存在时先登记 `wa://group/<jid>` 内部入口，再原子 upsert 当前邀请码。重复事件幂等，
晚到的旧事件不得覆盖更新事实。

普通拉群执行行继续保留创建时的 `normalized_link/invite_code` 作为输入审计快照，但
`group.join.requested` 在 Outbox 真正发送前按 `group_link_id` 读取当前邀请码；当前事实缺失时
才回退冻结邀请码。因此已创建但尚未执行的任务、补管理员等后续踩链接动作都会使用最新邀请码。

## 协议事件契约

```json
{
  "event": "group.invite_link_changed",
  "version": "v1",
  "accountId": "<protocolAccountId>",
  "occurredAt": "<RFC3339>",
  "data": {
    "tenantId": 1,
    "accountId": 123,
    "protocolAccountId": "protocol-account",
    "protocolBackend": "WEB|ANDROID",
    "groupJid": "....@g.us",
    "inviteCode": "<current-code>",
    "author": "<optional actor jid>",
    "source": "wa_groups_update|wgp2_notification"
  }
}
```

- Web：Baileys 已把 `GROUP_CHANGE_INVITE_LINK` 转成 `groups.update`，直接读取
  `inviteCode/author` 并发布。
- Android：`w:gp2` 的首个子节点为 `invite` 时读取 `code`，操作人优先取通知
  `participant_pn`，其次取 `participant`，转成内部事件后由 Armada 适配器发布。
- 两端都把事件放入群事件 Topic，并使用 broker ACK 语义；不得在日志、指标标签或错误文本中
  写完整邀请码。

## #49 证据与根因

test1 只读证据：

- 执行行 53 的两名拉手账号 1060、1065 均为 `membership_status=UNKNOWN`，对应邀请动作均为
  `action_status=UNKNOWN / PROTOCOL_RESULT_UNCONFIRMED`。
- UNKNOWN 协调器连续创建 131 次成员查询；第一次为 `ACCOUNT_NOT_ONLINE`，其后持续超时，
  查询账号始终为 738。
- 账号 738 当时已经 OFFLINE 且 BANNED；同群账号 908 为 ONLINE/NORMAL。
- 使用 908 实时查询 WhatsApp 群成员成功，群有 48 人，1060 与 1065 均明确不在群。

根因有两层：

1. `findActiveProtocolRefs` 只保证账号未删除且可寻址，不保证在线；UNKNOWN 复核又固定使用第一个
   候选，导致离线/封禁账号反复超时而不切换。
2. 即使成员快照成功，现有逻辑只处理“观察到目标在群”，不会把
   “快照成功且目标明确不在群”的 UNKNOWN 邀请收敛为失败。

修复后 UNKNOWN 复核只选择当前在线的群内账号，并按账号使用独立的一次性查询业务键：
某账号明确失败或超时后尝试下一个候选；任一查询等待中时不并发放大请求。成功快照中目标缺席时，
把邀请动作从 UNKNOWN 转为 FAILED，把拉手 membership 从 UNKNOWN 转为 JOIN_FAILED，沿用现有
拉手释放与补号状态机。

## 影响分析

- API：无新增 HTTP API；新增 Kafka v1 事件类型。
- 数据：Flyway 新增 `group_link_preview.invite_code_observed_at BIGINT NULL`，不改旧数据；
  旧邀请码仍可读取，新事件到达后才获得观察时间。
- 租户：Kafka 消费后必须恢复 `TenantContext`；所有群定位和 upsert 受租户拦截器限制。
- 状态：邀请链接变更不直接终止任务、不把群判为封禁或链接失效；#49 的成员缺席只收敛对应账号
  邀请事实，不改变群健康状态。
- 部署顺序：先部署 Armada（消费者兼容新事件且旧协议不受影响），再部署 Web/Android 发布端。
- 回滚：先回滚发布端停止新事件，再回滚 Armada 应用；新增 nullable 列可保留。若必须删除列，
  在确认无旧应用依赖后单独执行 `ALTER TABLE group_link_preview DROP COLUMN invite_code_observed_at`。

## 验证

- Web：事件类型路由、`groups.update` 邀请码发布、无邀请码时不发布。
- Android：`w:gp2/invite` 解析、事件构建/Topic 路由、协调器发布。
- Armada：Kafka 契约解析、邀请码新旧事件覆盖顺序、租户隔离、Outbox hydration 使用最新 code
  且缺失时回退、在线账号筛选与故障切换、成员缺席把 UNKNOWN 收敛为失败。
- 全量门禁：协议层 TypeScript 测试；Android `gofmt -> go vet ./... -> go build ./... -> go test ./...`；
  Armada 聚焦测试、H2 Mapper/Flyway 测试和 `mvn test`。
