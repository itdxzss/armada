# 拉群本地有状态模拟器首轮负载结果

> 时间：2026-09-03 CST
>
> 结论：`SIMULATOR_LOGIC_LOAD_PASS / H2_OUTBOX_CHAIN_PASS / MANAGER_WAIT_PASS / STALE_CALLBACK_PASS / UNKNOWN_RETRY_FAIL / APPROVAL_AUTO_RESUME_FAIL / INVITE_REFRESH_FAIL / TERMINAL_CALLBACK_AUDIT_FAIL / ARMADA_CAPACITY_NOT_MEASURED`
>
> 边界：负载数据仅来自本机进程内 `sim://local`；另有本地 H2 正常链回归。未访问部署态 Armada API、
> Kafka broker、test1 或 WhatsApp/Meta。

## 1. 覆盖内容

- 每条 execution 执行 8 个普通拉群业务动作：双向联系人、管理员入群、提权、开放成员加人、关闭审批、拉手入群、目标入群。
- Web/Android 按 1:1 混合。
- 每 2 个业务动作重放一次相同 operation、不同 commandId 的重试，共 4 次重试/execution。
- 核对 commandId 唯一性、业务 mutation 数、重复 mutation、终态后命令、execution 终态和父任务 outcome。

## 2. 无节流并发突发

首批 worker 使用并发闸门同时进入执行区，用于证明配置的活动 execution 数确实达到目标。

| execution | 并发 | command | 重试 | mutation | max in-flight | P99 execution latency | 结果 |
|---:|---:|---:|---:|---:|---:|---:|---|
| 100 | 10 | 1,200 | 400 | 800 | 10 | 7.372 ms | PASS |
| 300 | 30 | 3,600 | 1,200 | 2,400 | 30 | 20.836 ms | PASS |
| 500 | 50 | 6,000 | 2,000 | 4,000 | 50 | 19.892 ms | PASS |

三档合计 900 execution、10,800 command、3,600 次重试、7,200 次业务 mutation；失败 execution、重复 mutation、
终态后命令均为 0，终态完整率 100%。本结果包含首批闸门等待，吞吐只是 Python simulator 微基准，不代表 Armada 容量。

## 3. 开放环速率校验

目标速率包含重试 command。所有 execution 都在下一次到达前完成，所以 `maxInFlight=1`，表示当前速率未在本地模拟器
形成积压，不表示生产服务只支持单并发。

| 目标 command/s | execution | command | 重试 | 实测 command/s | P99 latency | P99 schedule lag | 结果 |
|---:|---:|---:|---:|---:|---:|---:|---|
| 5 | 5 | 60 | 20 | 4.996 | 0.544 ms | 8.536 ms | PASS |
| 20 | 10 | 120 | 40 | 19.968 | 1.718 ms | 10.368 ms | PASS |
| 50 | 20 | 240 | 80 | 49.988 | 1.367 ms | 10.169 ms | PASS |
| 60（+20%） | 20 | 240 | 80 | 59.853 | 1.962 ms | 10.175 ms | PASS |

四档合计 55 execution、660 command、220 次重试、440 次业务 mutation；失败 execution、重复 mutation、终态后命令
均为 0，终态完整率 100%。

## 4. #199 真实状态机本地回归

新增 H2 回归把 `GroupLinkHealthReportedSinkAdapter` 接到真实
`PullTaskStandardExecutionLifecycleServiceImpl` 和真实 Mapper：预置 8 条 SENT 命令、1 个 SUCCESS 目标结果和占用中的拉手，
再投递 5 个 `BANNED/CHAT_SUSPENDED` 事件。断言结果：

- execution 只迁移一次到 `FAILED/GROUP_BANNED`，版本在后 4 次回调中不再变化；
- 目标 `pull_status=SUCCESS` 和 attempt `protocol_outcome=SUCCESS/CLOSED` 保留；
- 8 条 command 保持唯一且全部 SENT，没有新增 command；
- 拉手释放，父任务 `COMPLETED`，人工链接不生成换群 retry；
- Outbox 取消入口只调用一次，未触发新调度。

聚焦测试共 22 个用例通过，其中本用例单独运行 1/1 通过。

## 5. 生产 Outbox 实现的 H2 正常闭环

普通拉群 Web、Android、混合正常链已从 mocked Outbox 升级为生产 `ProtocolCommandOutboxServiceImpl`、真实
`ProtocolCommandOutboxMapper.xml` 和 H2 表。调度协调器每次产生业务命令后，本地发送泵严格执行
`PENDING -> LOCKED -> DISPATCHING -> SENT`，对应回调在断言该 command 已 SENT 后才进入结果服务。

- 单个 task、单个 execution 最终均为 `COMPLETED`，目标成员拉入和管理员结果成功，pull-call 已回写；
- 每个 profile 产生 11 条业务 command，单次 11 个 commandId 唯一；3 个 run 合计验证 33 条，Outbox 与
  action/call/material 聚合记录一一对应；
- tenant、`batchId=pull-task:100`、Web backend、master topic、payload 和 trace 均完整；
- Web 全部走 master topic；Android 按 group-join/group-action topic 路由；混合 run 同时存在 Web、Android 命令；
- 每个 profile 的 12 条业务结果均即时重放一次，并在任务终态后再迟到重放一次；Outbox 行数和 execution 版本不变；
- 每个 run 结束时 11/11 Outbox 为 SENT。
- 每个 run 结束时 execution 租约和群链接占用键均为空，拉手 `released_at` 已写入且账号占用键为空。

该回归没有启动 Kafka producer/broker/consumer，`ProtocolCommandDispatchTrigger` 仍为 mock；因此证明的是生产
Outbox 落库与状态 SQL、业务回调和父任务收口的本地纵向一致性，不是部署态 Kafka E2E。

### 5.1 管理员资源等待与 UNKNOWN 恢复

- `PL-R01`：管理员为空后连续执行 5 个恢复周期，execution 始终保持
  `WAIT_RESOURCE/MANAGER_JOIN/MANAGER_UNAVAILABLE`；业务时间不刷新，账号、action、Outbox 和协议调用均为 0，PASS。
- `PL-R02`：补入唯一管理员后只恢复一次，只创建 1 个管理员角色、1 个 JOIN action 和 1 条 SENT Outbox；没有同步
  协议调用，PASS。
- `PL-R06`：原 JOIN Outbox 已 SENT，回调为 `FAILED/TIMEOUT`、`retryable=true` 且没有 groupJid 时，action 先进入
  UNKNOWN；到复核时点后执行器直接调用一次 `GroupJoinPort.join` 并推进到 MANAGER_ADMIN，没有创建第二条 Outbox。
  这意味着协议侧存在重复踩链接风险，且第二次动作无法通过 commandId/Outbox 追踪，业务验收 FAIL。

### 5.2 等待审批自动恢复

- `PL-A01`：管理员 JOIN 回调返回 `PENDING_APPROVAL` 后，execution 正确进入
  `WAIT_RESOURCE/MANAGER_JOIN/APPROVAL`，action 和 membership 均记录为 `PENDING_APPROVAL`，暂停部分 PASS。
- 随后推进 5 个未来调度周期，每轮 `claimed=0`；execution 版本和 Outbox 数量不变，
  成员查询从未执行。原因是调度器只领取 MANAGER/PULLER/STATION 三种资源等待，不包含 APPROVAL。
  因此批准后也没有自动复核入口，业务验收 FAIL。

### 5.3 失效邀请码恢复

- `PL-I01/I02`：Web 和 Android 初始 JOIN 均先通过 Outbox 发送；回调返回
  `FAILED/INVITE_REVOKED` 并携带已知 group JID 后，execution 进入恢复分支。
- 测试桩提供了与旧码 `AAAA` 不同的当前码 `BBBB`，但后续 6 轮调度只在
  `WAIT_RESOURCE ↔ EXECUTING` 之间交替，执行 3 次成员查询，`refreshCurrentInviteCode` 始终为 0 次。
  Outbox 仍只有原命令，既没有使用新码，也没有明确终止，两端业务验收均 FAIL。

### 5.4 重复、乱序与终态后回调

- 管理员 JOIN 先 SUCCESS，再重放 3 次同 commandId 的迟到 `INVITE_REVOKED`：全部被拒绝，
  action 保持 SUCCESS，execution 版本和 Outbox 数量不变，该乱序子集 PASS。
- `PL-S04`：execution 终态后到达首个已 SENT 的 JOIN SUCCESS 回调时，终态不会复活且没有新命令；
  但 action/membership 协议事实因 execution CAS 失败而整个事务回滚并抛异常，事实无法审计且有消费重试风险，FAIL。

Java H2 正常链、管理员资源、审批等待、邀请码恢复、回调冲突、取消和父子生命周期聚焦回归共 33/33 通过；
其中 PL-R06、PL-A01、PL-I01/I02 和 PL-S04 是锁定当前缺陷行为的 characterization test，测试绿色不代表这些业务用例通过。

## 6. 尚不能签署

- 未接 Armada 公开任务 API、部署态 scheduler 和真实 Kafka producer/broker/consumer，所以 `PF-S01~S06` 仍是集成待办。
- 未采集 JVM/DB/Kafka/worker CPU、内存、连接池、lag 和排空时间，不能给出生产容量或 SLA。
- 速拉群/建群营销仍未接入该纵向链。
- 审批恢复、管理员 JOIN UNKNOWN、已知 JID 的邀请码轮换和终态后首个回调已复现缺陷；
  同 commandId 的成功后迟到失败已验证不覆盖，丢失和 worker crash 尚未覆盖。
- test1 仍处于封控门禁关闭状态；本轮没有真实群或账号副作用。
