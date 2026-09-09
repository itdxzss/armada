# 超链任务压测与故障注入用例

> 版本：`v1.0`
> 状态：`DESIGNED / ANDROID_LOOPBACK_LOCAL_READY / WAITING_TEST1_CALIBRATION_AND_CAPACITY_CONTRACT`
> 安全边界：容量搜索、1×、2× 和 soak 只允许在完全隔离的 Stub 环境执行；普通真号和 HIGH 蓝标只执行固定小流量 canary。

## 1. 运行参数

每次运行先从已签署的 `capacityContractVersion` 读取下列变量，不得在压测脚本中另设“临时默认值”。

| 变量 | 含义 |
|---|---|
| `P` | 正式 1× 峰值的 C3 实际提交速率，单位 msg/s |
| `Qcreate` | 1× 任务创建/启用速率，单位 task/s |
| `Ttenant` / `Tglobal` | 单租户 / 全局并发运行任务数 |
| `Ntask` | 单任务唯一 recipient 数 |
| `NdayTenant` / `NdayGlobal` | 单租户 / 全局单日唯一 recipient 数 |
| `Aweb` / `Aandroid` / `Ahigh` | 测试窗口内独占且持续可用的账号数 |
| `Ltask` / `Lday` / `Llife` | 单账号每任务 / 每日 / 生命周期发送上限 |
| `Imin` / `Imax` | 单账号提交间隔范围及冻结分布 |
| `Rprotocol` | Web/Android 实际提交比例 |
| `Rmessage` | 五类消息实际提交比例 |
| `Rshort` | 开启短链的实际提交比例 |
| `Rinbound` | 配对 Signal peer 回推加密 inbound message 的比例；第一阶段固定为 0 |
| `Dsteady` | 1× steady 持续时间 |
| `Dshock` | 2× shock 持续时间 |
| `Dsoak` | soak 持续时间 |
| `Trecover` | shock 结束后积压恢复 SLO |
| `SLOlatency` | API、领取、C0→C3、ACK、DELIVERED/READ、普通队列等待的 p95/p99 |
| `SLOresource` | 应用、DB、Redis、Kafka、协议 Stub 的 CPU/内存/连接/lag 水位 |
| `Wmetric` / `Wfair` | 指标最终一致窗口 / 任一合格队列或任务最大无进展窗口 |
| `Rretention` | task/command 最长未决期、tombstone 与 ACK 关联保留期及清理策略 |
| `Topology` | 服务实例数、线程/连接池、DB 规格、Redis 拓扑、Kafka 分区数及 Stub 规格 |

## 2. 压测器与 Stub 必备能力

L1 开始前，运行器必须具备：

- 通过正式 API 创建/报价/启用任务，批量准备数据可走受控 fixture stage；不得绕过业务逻辑直接制造“成功”。
- 生成可复现的 Web/Android、消息类型、国家价格桶、短链开关和租户分布，随机种子写入 run manifest。
- Android 第一阶段必须走真实 protobuf/XMPP 构造、Signal 出站加密、Noise 加密与解密；禁止在发送函数或 C3 之前直接伪造成功。Web 已具备独立的 protobuf、Signal 双端解密、BinaryNode、双向 Noise 和密文 ACK 回环，但纳入正式 mix 前仍须接入隔离全链的 C2/C3/C4 事实。
- Android 回环记录 Noise 线长/明文长度、C2 接收和 C4 Server ACK。延迟、失败、迟到、重复、乱序、断连以及 DELIVERED/READ 在对应场景实现前保持 BLOCKED。
- Server ACK 必须由回环经过真实 Noise 加密回传。`Rinbound` 第一阶段固定为 0；后续配对 Signal peer 就绪后才执行实例入站 Signal 解密用例。
- 云控账号可按真实池规模启动，但 auth/Signal/app-state 必须复制到 runId 隔离命名空间，禁止污染正式账号 ratchet 与状态。
- 协议实例网络必须 fail-closed：WhatsApp/Meta 域名和公网 443 不可达，只允许连接隔离 Mock；任何真实 egress 立即判 P0 FAIL。
- 钱包 Stub 支持报价、冻结、调整、结算、释放及成功、余额不足、超时、结果未知、迟到成功、重复回调。
- 点击 Stub 只对本次 runId 的短码产生可复现 PV/UV，不扫描或点击其他资源。
- 能在指定阶段暂停/恢复业务 worker、协议 consumer、指标投影、Kafka、Redis、DB 和钱包 Stub。
- 能导出业务 DB、outbox、broker、协议 Stub、ACK、点击、账本和资源时序；所有记录能按 runId/commandId 关联。
- kill switch 能停止新任务、阻断新 C3、等待在途收口并输出尚未收敛集合。

若上述能力缺失，对应案例标为 `BLOCKED`，不能改用日志抽样或 task 卡片数字替代。每次对比运行还必须固定 `Topology`、warm-up、测量、drain 和 `Wmetric` 窗口，否则容量结果不可横向比较。

## 3. 通用计算与断言

### 3.1 零容忍集合

排空及 reconciliation 后计算：

```text
lost_commands = eligible_unique_C0 - unique_C3 - explicit_terminal_before_C3
duplicate_logical_sends = count(business_recipient_key with physical_submit_count > 1)
duplicate_physical_commands = count(commandId with C3_count > 1)
cross_tenant_rows = count(event/cache/outbox/bill whose resolved tenant != declared tenant)
billing_count_delta = wallet_billed_recipient_count - authoritative_billable_recipient_count
billing_amount_delta = wallet_settled_amount - recomputed_amount_by_frozen_price
task_counter_drift = task_projection - aggregate(recipient_fact)
round_counter_drift = round_projection - aggregate(recipient_fact)
account_counter_drift = account_projection - aggregate(recipient_fact)
click_counter_drift = click_projection - aggregate(valid_click_fact)
```

以上十项必须全部为 0。明确失败可以不进入 C3，但必须有可查询终态和唯一原因，不能从集合中静默消失。点击是旁路归因，不要求属于 READ；必须满足 `clickPV >= clickUV`，且 clickUV 不超过持有本次有效短链的唯一 recipient 数。

### 3.2 稳定与恢复

- steady 窗口内实际吞吐达到冻结目标，误差带由 CAP-19 定义；不能用生成器输入量冒充实际 C3。
- 稳态 backlog 使用线性回归或相同采样规则，斜率不得持续为正。
- 2× shock 可以积压；恢复时间从负载回落到 1× 的时刻起算，到所有关键 backlog 回到 shock 前基线带为止。
- `Cstable` 是在完整正式 mix、Dsteady/soak、故障恢复及零容忍断言同时通过的最高联合负载档。
- `Cdeploy = min(Cstable, 合格 Web 账号×安全速率, 合格 Android 账号×安全速率, 钱包容量, 回调/投影容量, DB/Kafka 容量)`。
- 发布配置必须满足 `P <= U × Cdeploy`；`U` 在查看结果前冻结为 60%～70% 内的单值、绝不高于 70%，同时最弱资源保留 30%～40% 余量。

### 3.3 每例证据

每个 case 至少保存：run/case ID、容量合同版本、四仓制品、环境、随机种子、时间窗、生成负载、C0～C7 时序、错误码分布、backlog、资源曲线、对账结果、停止/cleanup 结果及证据 SHA-256。

## 4. 用例总览

| ID | 层级 | 场景 | 是否允许真实发送 |
|---|---|---|---|
| `PERF-HL-001` | L1 | 0.1× 工具与观测校准 | 否 |
| `PERF-HL-002` | L1 | 1× 正式 mix 稳态 | 否 |
| `PERF-HL-003` | L1 | 2× 短时冲击与恢复 | 否 |
| `PERF-HL-004` | L1 | 长时间 soak | 否 |
| `PERF-HL-005` | L1 | 多任务并发与调度公平 | 否 |
| `PERF-HL-006` | L1 | 多租户隔离与 noisy neighbor | 否 |
| `PERF-HL-007` | L1 | 单账号上限、间隔与全局在途门禁 | 否 |
| `PERF-HL-008` | L1 | 账号池耗尽与无账号终态 | 否 |
| `PERF-HL-009` | L1/L2 | HIGH 强制筛选 | 否 |
| `PERF-HL-010` | L1/L2 | Web/Android 正式比例 | 否 |
| `PERF-HL-011` | L1/L2 | 媒体与短链正式比例 | 否 |
| `PERF-HL-012` | L1/L2 | 重复命令、rebalance 与幂等 | 否 |
| `PERF-HL-013` | L2 | ACK/回调重复、乱序、早到、迟到 | 否 |
| `PERF-HL-014` | L1/L2 | 协议结果超时与 UNKNOWN 恢复 | 否 |
| `PERF-HL-015` | L1 | 业务进程重启与 outbox 重放 | 否 |
| `PERF-HL-016` | L2 | C3 后协议进程崩溃 | 否 |
| `PERF-HL-017` | L1 | Redis holder 故障与恢复 | 否 |
| `PERF-HL-018` | L1 | DB 锁竞争、慢 SQL 与 failover | 否 |
| `PERF-HL-019` | L1 | Kafka 短断、积压与 rebalance | 否 |
| `PERF-HL-020` | L1 | 钱包延迟、UNKNOWN 与幂等结算 | 否 |
| `PERF-HL-021` | L1 | 指标投影暂停、追平与 reconciliation | 否 |
| `PERF-HL-022` | L1/L2 | 普通队列饥饿与资源公平 | 否 |
| `PERF-HL-023` | L1 | 容量阶梯搜索 | 否 |
| `PERF-HL-024` | L3 | 普通真号固定小流量 E2E | 是，白名单 |
| `PERF-HL-025` | L4 | HIGH 蓝标固定小流量 canary | 是，白名单；禁止增压 |
| `PERF-HL-026` | L1 | 正式量与并发边界值 | 否 |
| `PERF-HL-027` | L1 | 暂停、停止、截止与 drain | 否 |
| `PERF-HL-028` | L1/L2 | 幂等 tombstone/ACK 关联保留期边界 | 否 |

## 5. 基线与容量用例

### PERF-HL-001：0.1× 工具与观测校准

- 前置：Web/Android 加密回环、钱包 Stub、点击 Stub 和 C0～C7 事实可用；Android 共享节点必须用账号 User 的 SHA-256 精确选择并核对 active connection 数，只有专用隔离节点可用 `*`；先用受控账号状态副本或明确可重置的专用账号，Android 测试 recipient 必须已有可用 Signal session。
- 负载：按正式联合 mix 的 0.1× 运行至少一个完整任务生命周期，数量还应足以覆盖所有国家桶和消息类型。
- 注入：不注入故障；协议结果和 ACK 延迟使用固定、可复现的短分布。
- 断言：真实 WhatsApp egress 为 0；每个 C3 均有 Noise 密文，Noise 线长大于明文长度，Server ACK 数与提交成功数一致；生成器、API、DB、broker、回环、账本的唯一 ID 可一一关联；十项零容忍指标为 0；仪表盘数等于原始事实重算。Signal 对端解密与 inbound echo 不属于第一阶段通过声明。
- 退出：任一阶段缺事实、时钟偏差不可解释或 cleanup 失败，后续所有压测 BLOCKED。

### PERF-HL-002：1× 正式 mix 稳态

- 前置：001 PASS；正式容量合同完整。
- 负载：`P/Qcreate/Ttenant/Tglobal/Ntask/Rprotocol/Rmessage/Rshort` 全部取 1×，平滑 ramp 后持续 `Dsteady`。
- 断言：C3 达到目标误差带；API/C0→C3/普通队列等待 p95/p99 达标；backlog 无正增长；账号、DB、Redis、Kafka、协议资源水位达标；十项零容忍指标为 0。
- 证据：每分钟漏斗、账号利用率分布、任务完成分布、最慢 1% task/账号、全量差集而非采样。
- 停止：任何零容忍值非零或硬资源水位越线。

### PERF-HL-003：2× 短时冲击与恢复

- 前置：002 PASS，并记录 shock 前稳定 backlog 基线带。
- 负载：1× 稳态 → 在冻结 ramp 内升至 2×，维持 `Dshock` → 回落至 1×。
- 断言：冲击期可积压但不丢、不重、不串租户、不超账号上限、不错误扣费；回落后 `Trecover` 内回到基线；恢复期不靠持续拒绝合法任务制造假恢复。
- 证据：冲击前/中/后 C0～C7、lag、DB lock、Redis、GC/heap、账号队列、普通队列等待曲线。
- 停止：恢复期 backlog 仍正增长、预计超过 SLO、风控/真实网络出口被触发或任一零容忍值非零。

### PERF-HL-004：长时间 soak

- 前置：002、003 PASS。
- 负载：正式 1× mix 连续 `Dsoak`；按正式周期创建/完成任务，不能只维持固定存量。
- 断言：吞吐和延迟无持续劣化；heap/RSS/thread/connection/outbox/Kafka lag/临时文件无泄漏趋势；需按 `Rretention` 保留的 Redis key/tombstone 可线性增长，但实际增长率、所需容量、清理/归档和重建必须与模型一致；轮次、计费、点击和投影全部收口；重放比例无持续上升。
- 证据：全时序、开始/中段/结束 heap 与连接快照、最终 reconciliation、cleanup。
- 停止：资源趋势将越过冻结水位、数据保留/清理任务影响主链或观察缺口超过一个采样周期。

## 6. 账号、租户与工作负载形态

### PERF-HL-005：多任务并发与调度公平

- 负载：保持总 C3 为 1×，同时构造大/中/小任务、INSTANT/ROLLING/CYCLE，并达到 `Tglobal`。
- 注入：持续创建小任务穿插长任务；部分任务仅有少量匹配账号。
- 断言：每类任务在冻结上界内获得首次服务；无任务永久饿死；单任务不能独占全局 dispatcher；任务完成计数和账务独立准确。
- 证据：per-task 首次/最后提交时间、等待分位、调度份额和账号占用。

### PERF-HL-006：多租户隔离与 noisy neighbor

- 负载：TENANT_A 占 80% 生成流量，TENANT_B 占 20% 且含延迟敏感小任务；总量 1×，另交换占比复跑。
- 注入：两租户使用相同局部 task/recipient/command 后缀和同一协议 Stub 集群。
- 断言：跨租户串数为 0；B 的延迟/吞吐满足冻结 SLO；A 不能越过 B 的账号、短码、事件、账本、导出或缓存。
- 证据：按 tenant 分层的漏斗、资源与差集核对。

### PERF-HL-007：单账号上限、间隔与全局在途门禁

- 负载：一个账号被多个任务/租户候选同时争用，逐步逼近 `Ltask/Lday/Llife` 和账号在途硬门禁。
- 注入：Imin、Imax、区间中值、边界精度；协议 ACK 分别快、慢、超时。
- 断言：实际 C3 相邻间隔符合冻结分布；任何任务不能绕过跨任务全局门禁；上限达到后不再提交且产生明确原因；无负在途/超卖。
- 证据：按账号排序的 C3 时间戳、holder/DB in-flight、usage 和终态。

### PERF-HL-008：账号池耗尽与无账号终态

- 负载：分别在启动前为 0 账号、运行中账号降为 0、部分账号恢复、永久为 0。
- 覆盖：INSTANT、ROLLING、CYCLE；PRIVATE 白名单为空、账号 offline/restricted、协议 capacity 为 0。
- 断言：行为与 CAP-13 精确一致；达到等待时限后进入唯一终态，停止重查和计时，释放账号/recipient/余额并告警；不得长期 RUNNING。
- 证据：round 状态、30 秒重查序列、最终任务/计费/清理事实。

### PERF-HL-009：HIGH 强制筛选

- 前置：CAP-12 要求强制 HIGH 时执行；候选池同时含 HIGH、普通、UNKNOWN、认证状态变更账号。
- 负载：1× 选号/派发，可降低 recipient 量但不得降低筛选并发。
- 注入：试算后、启用前把部分 HIGH 改为普通或 UNKNOWN；运行中认证过期。
- 断言：C3 使用的账号 100% 满足冻结 HIGH 规则；UNKNOWN 不被当作 HIGH；重检与退出行为明确；若容量不足按 CAP-13 收口。
- 证据：认证事实来源、任务筛选快照、候选 SQL 结果、实际 C3 账号集合。

### PERF-HL-010：Web/Android 正式比例

- 负载：按 `Rprotocol` 在 1× 下运行，再分别运行 Web-only、Android-only 边界档。
- 注入：单端延迟、单端 owner 暂失、单端结果发布失败；不得自动跨协议换账号重发。
- 断言：实际 C3 比例在冻结误差带；两端漏斗和失败码可分列；单端故障不导致另一端重复、串 owner 或无界饥饿。
- 证据：protocolBackend、owner、commandId、protocolMessageId 的全量映射。

### PERF-HL-011：媒体与短链正式比例

- 负载：按 `Rmessage`、`Rshort` 运行 1×；图片大小/下载延迟按冻结分布。
- 注入：图片不存在、超时、格式非法；短链服务慢、重复点击、多浏览器同 UV、无点击。
- 断言：消息类型比例、payload 大小与吞吐符合合同；媒体失败有明确终态且不扣错费；click PV/UV 与注入值一致；短码跨租户不可访问。
- 证据：按类型/短链分层的 C0～C7、对象存储和点击事实。

## 7. 幂等、ACK 与恢复

### PERF-HL-012：重复命令、rebalance 与幂等

- 负载：1× steady；随机抽取冻结比例的 command，在 C1/C2 重复 2～5 次。
- 注入：consumer rebalance、ACK 输入消息前/后断连、同一批次重取。
- 断言：重复接收可增加但 C3 每 commandId 最多 1；业务 recipient 逻辑发送最多 1；明确终态/账务/聚合只计一次。
- 证据：重复投递列表与每个 command 的 C2/C3/result/ACK 次数。

### PERF-HL-013：ACK/回调重复、乱序、早到、迟到

- 负载：固定 command 集，分别生成 ACK→DELIVERED→READ、READ→DELIVERED→ACK、重复 N 次、C3 后立即早到、超出正常窗口迟到、无关联。
- 断言：状态单调且最终收敛；READ/DELIVERED 包含计数符合冻结语义；每级只计一次；无关联进入可恢复 inbox 或明确诊断，不能静默丢弃。
- Web/Android 必须分别执行，不能复用一端证据。
- 证据：原始回调顺序与最终 recipient/task/round/account 投影。

### PERF-HL-014：协议结果超时与 UNKNOWN 恢复

- 负载：1× 中冻结一组 C2 已接收的命令，分别在 C3 前超时、C3 后结果前超时、result 发布超时。
- 注入：迟到成功、迟到明确失败、永不返回，之后恢复 consumer。
- 断言：C3 前可安全重试；C3 后不得盲目二次物理发送；迟到结果能接管 UNKNOWN；永不返回按冻结超时终态收口；账务不提前结算。
- 证据：command 状态、lease/fence、C3、结果、reconciliation 重试与最终计费。

### PERF-HL-015：业务进程重启与 outbox 重放

- 负载：1×；在 DB 事务提交前、提交后 broker 前、broker ACK 后状态回写前分别终止业务实例。
- 断言：事务回滚不残留半条 recipient；已提交 outbox 最终发布；重放不重复 C3；调度恢复后任务/轮次继续且计数、账务为 0 差额。
- 证据：重启时间线、outbox 状态转换、broker offsets 和差集。

### PERF-HL-016：C3 后协议进程崩溃

- 负载：Web/Android 各选固定 command 集。
- 注入：在关联保存后/C3 前、C3 后/result 保存前、result 保存后/publish 前、publish 后/ack input 前崩溃。
- 断言：只有 C3 前场景允许再次物理提交；C3 后通过稳定状态、UNKNOWN 或迟到 ACK 收敛；owner 缺失必须形成明确结果/补偿，不得仅提交 offset。
- 证据：协议本地状态、关联、socket Stub、result outbox、broker 与业务 recipient。

### PERF-HL-017：Redis holder 故障与恢复

- 负载：多个任务争用同一账号并逼近在途门禁。
- 注入：Redis 超时、短断、主从切换、holder key 提前过期、恢复后旧 holder 重现。
- 断言：Redis 不可用时失败关闭；DB 发送中计数仍阻止超卖；恢复后无永久占槽、负数或重复 C3；等待在恢复 SLO 内消化。
- 证据：Redis 命令/keys、DB sending、account usage、C3 时间线。

### PERF-HL-018：DB 锁竞争、慢 SQL 与 failover

- 负载：1× 与 2× shock；最大任务/租户/账号竞争组合。
- 注入：候选/recipient/投影查询变慢、锁等待、死锁重试、短时只读/断连、获准的 failover。
- 断言：无重复领取、无跨租户、无负计数；超时有界并恢复；死锁/失败不会遗留半事务；查询计划与锁水位符合 SLO。
- 证据：slow query、执行计划、lock wait/deadlock、事务与最终 reconciliation。

### PERF-HL-019：Kafka 短断、积压与 rebalance

- 负载：1× → 2×；在 command、result、ACK 各主题分别注入。
- 注入：broker 短断、单分区暂停、consumer rebalance、重复 batch、恢复。
- 断言：生产失败留在 outbox/retry；消费 offset 不越过未可靠处理消息；恢复时间达标；重复 C3、丢命令均为 0。
- 证据：各 partition produce/consume offset、lag、outbox 和 C0～C4 差集。

## 8. 钱包、指标和公平性

### PERF-HL-020：钱包延迟、UNKNOWN 与幂等结算

- 负载：并发创建/启用/停止/完成任务达到 1× 控制面负载，recipient 跨多个国家价格桶。
- 注入：报价慢、余额不足、冻结超时但迟到成功、调整重复、结算未知、释放重复、钱包回调乱序。
- 断言：operation key 稳定；同一步只产生一个外部经济效果；任务状态与钱包结果一致；任务未收敛不提前结算；最终人数和金额差额为 0。
- 证据：每个 operation key 的调用/结果/重放、冻结/结算/释放余额和 recipient 计费集合。

### PERF-HL-021：指标投影暂停、追平与 reconciliation

- 负载：1× steady，先运行投影，再暂停一个冻结窗口，继续产生 result/ACK/click 后恢复。
- 断言：权威 recipient/event 不受投影暂停影响；恢复后以每批 500 或当前实现批次有界追平；重复投影不重复计数；全量 reconciliation 后 task/round/account/click 漂移为 0。
- 证据：暂停前/中/后 projector lag、批次速率、投影与事实差额。

### PERF-HL-022：普通队列饥饿与资源公平

- 前置：构造与超链共享实际 Web/Android worker、broker、连接或 prepare token 的普通消息队列。
- 负载：普通 1× + 超链 1×；普通 1× + 超链 2× shock；超链 1× + 普通 2× shock；热账号持续灌入。
- 断言：普通队列首服务和 p95/p99 等待满足 SLO，无无限饥饿；超链也能取得冻结份额；单热账号不耗尽全部全局 token；两类业务均不重复/丢失。
- 证据：按 queueType/账号/租户的到达、开始服务、C3、完成时间和调度份额。

### PERF-HL-023：容量阶梯搜索

- 前置：001～022 的相关正确性与恢复案例 PASS；只在 Stub 环境执行。
- 负载：保持正式联合 mix，从低档开始逐阶提高；每档 ramp、稳定窗口和降载窗口固定。接近拐点时缩小步长，不跨过硬保护继续蛮压。
- 稳定档：十项零容忍为 0、SLO 全达标、backlog 不增长、降载可恢复、最弱资源有可解释余量。
- 结果：取最高稳定档为 `Cstable`；再以正式 P 计算利用率。一次瞬时峰值、单 API QPS 或仅 C0 速率不得称为容量。
- 停止：首个不稳定档保留现场后降载；严禁把失败档搬到蓝标/真号复现。

### PERF-HL-026：正式量与并发边界值

- 负载：分别执行 `Ntask-1/Ntask/Ntask+1`、`NdayTenant-1/NdayTenant/NdayTenant+1`、`NdayGlobal±1`、`Ttenant±1`、`Tglobal±1`。
- 断言：边界内按合同接收，边界外明确拒绝；拒绝不残留部分 recipient/outbox/冻结余额，不通过拆任务、跨日时区或并发竞态绕过。
- 证据：API 结果、数据库前后快照、账本、按冻结时区的日累计和并发任务锁。
- 停止：任何超额接收、静默截断、部分落库或错误计费立即停止。

### PERF-HL-027：暂停、停止、截止与 drain

- 负载：在 backlog、在途 C2、已 C3 未 ACK 和钱包 UNKNOWN 四种窗口分别执行 PAUSE/STOP/计划截止/实例关闭。
- 断言：冻结截止点后不产生不允许的新 C3；在途按合同收敛；迟到 result/ACK 不复活禁止状态；最终无长期 RUNNING、无 holder/余额泄漏。
- 证据：动作时刻与最后允许 C3、在途集合、任务/轮次/recipient/账本终态和 cleanup。
- 停止：截止点后继续新增物理提交、重复补发或无法 drain。

### PERF-HL-028：幂等 tombstone/ACK 关联保留期边界

- 负载：对同一 command 在 `Rretention-1`、`Rretention`、`Rretention+1` 重放，并覆盖最长任务未决期、ACK 关联清理和服务重启。
- 断言：保留期内重放绝不产生第二次 C3；边界后按冻结合同明确拒绝、归档查询或可靠重建，不能把失忆当新命令发送；Web/Android/业务三端窗口兼容。
- 证据：tombstone/关联 key、归档、重放结果、C3 次数和 Redis 容量模型。
- 停止：保留期内重复 C3、边界后静默失忆或 key 增长超出容量模型。

## 9. 真号用例（非容量测试）

### PERF-HL-024：普通真号固定小流量 E2E

- 前置：L0～L2 全 PASS；普通测试账号、收件人、测试钱包、租约、硬上限、kill switch 获得书面授权。
- 负载：Web/Android 每种消息类型先 1 条；短链开/关覆盖；固定安全间隔；不 ramp、不并发摸高。
- 断言：实际消息内容/媒体/CTA 正确；C0～C7 可关联；同 commandId 重放无第二条消息；账务准确；任务、账号、余额和数据全部收口。
- 停止：任一风险信号、非白名单目标、重复、owner 异常、漏斗缺口、账务差额立即触发 kill switch。

### PERF-HL-025：HIGH 蓝标固定小流量 canary

- 前置：HIGH 上游强制筛选、实际账号证明、真实钱包模式和 024 全部 PASS。
- 负载：按发布评审签署的最小样本执行；不得复用 1×、2×、soak 或阶梯搜索脚本。
- 断言：实际发送账号全部为 HIGH；业务链路、ACK、送达/已读、点击和真实账务符合冻结口径；无风控或账号异常。
- 停止：任何异常立即停止全部 canary，保留在途集合，不自动换普通账号或跨协议补发。

## 10. 当前可执行状态

| 用例组 | 当前状态 | 原因 / 下一步 |
|---|---|---|
| test1 connectivity / deep-check / UI/API read-only smoke | `PASS` | 真实环境连通、深检、远端 UI smoke、browser/API 只读 smoke 与 DB 快照已完成；详见现场执行结果 |
| test1 runtime version gate | `BLOCKED` | 运行清单证据早于容器重建，尚不能证明当前制品与候选 SHA 一致 |
| Web crypto smoke / 分档校准 | `PASS_WITH_LIMITATION` | test1 协议机初始 1000 条及新增 10,000/20,000/100,000 三档均全量 ACK、0 error；10 万条档约 4756 msg/s、峰值 RSS 389,222,400 bytes；仅证明 Signal+Noise 密码学和实例资源，不是 C0～C7 全链 |
| `PERF-HL-001` | `BLOCKED_TEST1_CALIBRATION` | Web crypto smoke 已过；仍待 Android 单账号验证真实 Signal 出站、egress=0、状态保护和两端 C2/C3 事实后，才能运行 0.1× |
| `PERF-HL-002`～`004` | `BLOCKED` | 依赖 001、正式 1×/时长/SLO 未冻结 |
| `PERF-HL-005`～`023`、`026`～`028` | `DESIGNED` | 可并行实现运行器与故障注入；执行仍需各自前置 |
| `PERF-HL-024` | `BLOCKED_SAFETY_FIXTURE` | 尚无获准收件人、精确租户/账号租约和清理方案；test1 当前 Web 在线 0 |
| `PERF-HL-025` | `BLOCKED_NO_HIGH` | test1 当前 eligible HIGH 为 0，且环境为 `ZERO_TEST`；永不用于容量测试 |

当前已经执行 L0 定向回归、test1 真实环境只读 smoke，以及 Web 初始 1000 条与新增 10,000/20,000/100,000 三档 crypto 校准；执行结果记录在 [验收清单](./2026-09-03-hyperlink-task-acceptance-checklist.md) 与 [test1 真实环境执行结果](./2026-09-03-hyperlink-task-test1-live-execution-result.md)。正式业务 0.1×/1×/2×/soak 尚未开始，不得把独立 crypto harness 或 test1 现有任务数据反推为全链稳定容量。

协议侧实现和验收边界见 [WhatsApp 加密回环压测设计](./2026-09-03-whatsapp-crypto-mock-load-test-design.md)。
