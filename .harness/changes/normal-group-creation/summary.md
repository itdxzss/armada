# 新建普群：协议 Topic 架构修正版

## 目标

在 Armada 群组列表提供异步“新建普群”任务。任务状态机由 Armada 持有，所有 WhatsApp
副作用通过协议命令 Kafka 执行；Armada 不再消费联系人准备、建群、后处理三个内部业务
Topic，也不在自身进程内调用 Web/Android 协议 Port。

本记录覆盖本 change 早期的“三个 Armada 阶段 Topic”设计。历史设计只作背景，不得作为
当前实现和联调依据。

## Topic 拓扑

本功能只使用下列三个新建普群专用 Topic；不共享既有协议命令/结果 Topic：

| 方向 | Topic | 生产者 | 消费者 |
|---|---|---|---|
| Web 命令 | `protocol.web.normal-group.commands.v1` | Armada 协议 Outbox | Web normal-group consumer |
| Android 命令 | `protocol.android.normal-group.commands.v1` | Armada 协议 Outbox | Android normal-group consumer |
| 统一结果 | `protocol.normal-group.events.v1` | Web/Android 协议服务 | Armada `ProtocolNormalGroupCreationEventConsumer` |

Topic 路由只看本次动作实际执行账号冻结的 `protocolBackend`：

- `WEB` 只能进入 Web 命令 Topic；
- `ANDROID` 只能进入 Android normal-group Topic；
- backend 为空、未知或与消费者不匹配时必须在产生 WhatsApp 副作用前拒绝；
- 两套协议结果都回到同一个统一结果 Topic，由 `source=normal_group_creation` 分派。

三个 Topic 名必须两两不同。旧 Web master、Android group-action 和
`protocol.group.events.v1` 继续服务原有业务，但不再承载新建普群消息。

## 命令契约

- `commandType`: `group.normal_creation.requested`
- `aggregateType`: `NORMAL_GROUP_CREATION_ITEM`
- `source`: `normal_group_creation`
- 通用 action：
  - `CONTACT_PREPARE`
  - `GROUP_CREATE`
  - `GROUP_SETTINGS_APPLY`
  - `GROUP_LEAVE`
- 联系人方向：
  - `CREATOR_SAVE_MEMBER`
  - `MEMBER_SAVE_CREATOR`

每个副作用都有唯一 `commandId`。Outbox 只持久化租户、任务、明细、成员、方向和 action
等冻结引用，发布前由业务 hydrator 补齐手机号、群名、成员和权限，避免把可变或敏感执行
参数长期复制到 Outbox。

## 状态推进

```text
每个成员双向 CONTACT_PREPARE 全部 SUCCESS
  -> GROUP_CREATE SUCCESS 且带 groupJid
  -> GROUP_SETTINGS_APPLY SUCCESS
  -> creatorLeavePolicy=LEAVE 时 GROUP_LEAVE SUCCESS
  -> Armada 本地登记群、分组、成员关系和账号迁移成功
  -> item=CREATED
```

- 定向加人调用由 WhatsApp 返回成功即视为该方向好友准备成功，不查询或遍历完整通讯录。
- 任一联系人方向未成功时不得下发建群命令。
- 建群一次携带全部冻结成员；群已创建但成员未全部确认时返回 `FAILED + groupJid`，Armada
  收敛为 `CREATED_PARTIAL`，不得再次建群或使用 ADD 补齐。
- 权限动作必须整体成功后才进入可选退群。
- 自动退群必须先提权冻结成员，再查询实时群成员角色确认其为 `admin/superadmin`；未确认
  时保留建群人，不调用 leave。
- 只有全部必需协议 action 与 Armada 本地收尾都成功，计划群才是 `CREATED`，任务汇总才
  能是最终成功；`FAILED`、`RESULT_UNKNOWN`、`CREATED_PARTIAL` 均计入失败。
- 迟到、重复或串阶段结果必须同时通过 `itemId + currentStep + commandId + accountId +
  protocolAccountId + protocolBackend` 校验才能推进。

## 权限默认值

请求未传 `settings` 或单个字段为空时，服务端统一补齐：

| 控端字段 | 默认值 | 协议目标值 |
|---|---:|---|
| `sendMessagesAllowed` | `true` | `announce=false` / `not_announcement` |
| `editGroupSettingsAllowed` | `false` | 仅管理员可修改 / `locked` |
| `addMembersAllowed` | `true` | `memberAddMode=true` / `all_member_add` |
| `joinApprovalEnabled` | `false` | `joinApprovalMode=false` |
| `ephemeralDurationSeconds` | `0` | 关闭限时消息；Web/Android 均显式下发关闭节点，非 0 值按秒设置 |

以上默认值同时固化在 DTO 归一化、Flyway 表默认值和协议 payload 中。显式传入的非空值
仍按用户选择执行。

## 数据与重试

- V101 三张任务表继续保存业务事实；V102 为 item/member 增加当前 action 的真实
  `command_id` 和唯一索引。
- 明确 `FAILED` 的明细允许按当前 action 生成新 commandId 人工重试；不更换冻结账号和
  backend。
- `RESULT_UNKNOWN`、`CREATED_PARTIAL` 不允许直接重放可能已产生副作用的动作。
- 重试权限/退群时重置该步骤状态；退群也计入后处理尝试次数。
- 旧的内部阶段 consumer、publisher、execution service、账号锁与三个业务 Topic 配置已
  删除，不能重新接回正常链路。

## 并发与顺序

- Armada 对所有新建普群命令统一使用 `protocolAccountId` 作为 Kafka Key；同一 Topic 内
  同账号固定进入同一分区。
- Web 的 master 与 normal-group 两个 ingress 最终进入同一账号工作队列，并共用 Redis
  `OperationGate`；Android 的旧 group-action 与新 normal-group 两个消费池共用带唯一
  token、自动续租和 Lua 比较释放的 Redis 账号租约。
- 同一 `protocolAccountId` 跨 Topic、跨 consumer、跨进程只允许一个原生 WhatsApp 动作
  进入；锁冲突属于基础设施重试，源消息不得提交，也不得伪造成业务失败结果。
- Web/Android 原生调用即使先达到业务超时，账号租约也必须保持到不可取消的底层调用真实
  结束；超时结果按 `UNKNOWN` 回传，禁止下一命令与尚未结束的副作用重叠。
- Android 多节点模式下普通建群账号暂时没有 owner 时保留源 offset 无限重试，不按普通
  “账号未在线”拒绝提交，避免 Armada 永远收不到最终结果。
- 不同 `protocolAccountId` 使用不同锁键，仍可在各协议配置并发度内并行执行。
- commandId 状态机继续负责单命令幂等；账号租约负责不同 commandId 之间的互斥，两者不能
  互相替代。

## 验收锚点

1. Web/Android 混合联系人准备时，每个方向严格按 actor backend 进入各自 Topic。
2. Web consumer 在路由/执行前拒绝 Android payload；Android consumer 在解析/执行前拒绝
   Web payload。
3. 两套协议只向 `protocol.normal-group.events.v1` 发布统一最终结果。
4. action 不遗漏，且只使用上述四个通用 action；退群使用 `GROUP_LEAVE`。
5. 全部联系人方向成功前不建群；建群成功前不设置权限；权限成功前不退群。
6. 默认权限严格为 `true/false/true/false/0`，其中修改群资料默认关闭。
7. 错误 commandId、错误账号、错误 backend、迟到 action 都不能推进状态机。
8. 任一必需命令失败或未知时任务不得汇总为成功。
9. partial 建群保存 groupJid 并收敛为 `CREATED_PARTIAL`，不重复建群。
10. 建群人只有在其他成员管理员角色得到实时确认后才能退出。
11. 同一账号从旧/新 Topic 同时到达时只能串行执行，不同账号可并行。

## 关键实现

- Armada：
  - `NormalGroupCreationCommandDispatcher`
  - `NormalGroupCreationPayloadHydrator`
  - `NormalGroupCreationProtocolResultService`
  - `ProtocolNormalGroupCreationEventConsumer`
  - `V102__normal_group_creation_protocol_commands.sql`
- Web 协议：`normal-group-creation-executor.ts`
- Android 协议：`normal_group_creation_sender.go`

## 验证

- Armada：8 个定向测试类共 56 项通过，覆盖 Outbox Topic 路由、专用统一结果消费、重复
  回执幂等、失败方向重试、H2 Mapper SQL、准入并发、严格 backend 和 Topic 隔离；完整
  `testCompile` 通过。全仓测试中的真库 DbTest 仍依赖本地 MySQL，不计入本次通过结论。
- Web：完整 61 个测试套件共 548 项通过，覆盖配置隔离、手动 offset、Master/normal-group
  路由、Worker 执行、账号锁续租、超时持锁、专用结果与 DLQ；`tsc --noEmit` 与构建通过。
- Android：`internal/armada` Linux 测试二进制已在 WSL 实际执行并返回 `PASS`，包含错误
  backend、权限字段缺失拒绝、旧/新 Topic 账号互斥和超时等待底层结束；Coordinator 定向
  测试通过，`go vet ./internal/armada ./internal/coordinator` 通过。`-race` 因当前环境没有
  Linux CGO C 编译器未能执行，账号锁仍有同账号互斥、不同账号并行、错误后释放测试覆盖。
