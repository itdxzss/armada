# 超链任务测试方案

> 版本：`v1.0`
> 状态：`TEST1_READ_ONLY_PASS / BLOCKED_BEFORE_STATEFUL_E2E`
> 编制日期：2026-09-03
> 测试原则：L0 可以立即执行；正式业务量未冻结、关键可观测性未补齐前，不进入容量结论、真号 E2E 或蓝标 canary。

## 1. 目标与结论边界

本方案验证超链任务从任务创建、账号选择、命令派发、Web/Android 协议执行、Server ACK、送达/已读、短链点击到计费结算的完整闭环，重点回答四个问题：

1. 功能是否正确：状态机、幂等、租户隔离、计费、计数均无逻辑偏差。
2. 链路是否可证明：命令接收、实际提交、Server ACK、DELIVERED、READ、点击可分别统计并逐层核对。
3. 容量是否可恢复：1× 正式峰值长期稳定，2× 短时冲击结束后积压在冻结时限内恢复，soak 无漂移。
4. 上线是否安全：正式峰值不超过稳定容量的 60%～70%，蓝标账号只做最终小流量 canary，不参与摸容量。

本方案中的代码上限只是当前实现事实，不等于正式业务量，也不构成容量承诺。容量结论只有在第 3 节的参数全部签字冻结后才有效。

## 2. 候选范围

| 层面 | 仓库 / 模块 | 候选分支与 SHA | 责任边界 |
|---|---|---|---|
| 业务后端 | `armada/armada-api` | `1.0.3-snapshot@4e26fe3146410e1a76c74cea9eb9e4505cdbd178` | 生命周期、选号、派发、幂等、租户、指标、计费 Saga |
| 管理前端 | `wheel-saas-pure-web-hyperlink-library-polish` | `feature/hyperlink-image-asset-library-polish@604068e95e56a7653bf1c1036b7117565b5a3c95` | API 合同、表单门禁、动作与指标展示 |
| Web 协议 | `armada-protocol/protocol-layer` | `1.0.3-snapshot@79d2faf286376fc0136a201a1ff41050c0417cb5` | owner 路由、私聊物理提交、结果与 ACK 回传 |
| Android 协议 | `whatsapp-server-feature-android-zhuan` | `1.0.3-snapshot@25b7fdb6714e4b0626d22b3d1a3e66b30ccb4e6c` | Android 私聊物理提交、结果与 ACK 回传 |

每次 L1～L4 运行必须重新记录四份运行制品 digest/full SHA；任一混版均判为 `BLOCKED_VERSION_MISMATCH`，结果不可用于验收。

## 3. 正式业务量冻结单

### 3.1 必须冻结的输入

下表的“冻结值”由业务、产品、运营、财务和技术共同确认。`TBD` 不允许被代码默认值代替。

| ID | 参数 | 单位 / 口径 | 当前实现观察 | 冻结值 | 决策责任人 | 状态 |
|---|---|---|---|---|---|---|
| `CAP-01` | 单任务号码量 | 去重后的 recipient 数 | 数据包安全阈值默认 500,000；TXT 单次导入另有 100,000 限制；未发现正式任务上限 | `TBD` | 业务 + 技术 | 待冻结 |
| `CAP-02` | 单租户单日号码量 | 自然日实际纳入任务的唯一号码数 | 未发现正式日限额 | `TBD` | 业务 + 风控 | 待冻结 |
| `CAP-03` | 全局单日号码量 | 自然日唯一 recipient 数 | 未发现正式日限额 | `TBD` | 业务 + 运维 | 待冻结 |
| `CAP-04` | 并发运行任务数 | 全局及单租户分别定义 | 未发现正式任务并发门禁 | `TBD / TBD` | 业务 + 技术 | 待冻结 |
| `CAP-05` | 账号池规模 | Web / Android / HIGH 分列，必须是可用账号而非库存 | 当前按筛选和 PRIVATE 能力动态选择 | `TBD / TBD / TBD` | 运营 | 待冻结 |
| `CAP-06` | 单任务执行账号上限 | 账号数 | 配置校验上限 100；AUTO 还受协议容量和账号匹配限制 | `TBD` | 业务 + 技术 | 待冻结 |
| `CAP-07` | 单账号发送上限 | 每任务、每日、生命周期分别定义 | `maxSendPerAccount=0` 当前表示不限制；账号全局在途硬门禁为 20 | `TBD / TBD / TBD` | 风控 + 业务 | 待冻结 |
| `CAP-08` | 发送间隔 | 秒，最小/最大及分布 | 代码接受 0～10 秒、0.1 秒精度；前端存在更激进预设 | `TBD～TBD` | 风控 + 业务 | 待冻结 |
| `CAP-09` | 协议混合 | Web : Android，按实际提交计 | 当前可按账号后端选择 | `TBD : TBD` | 运营 + 技术 | 待冻结 |
| `CAP-10` | 媒体混合 | TEXT/LINK/IMAGE/LINK_CARD/BUTTON_CARD | 五类均有协议实现，但正式占比未知 | `TBD` | 产品 + 业务 | 待冻结 |
| `CAP-11` | 短链占比 | 开启深度追踪的实际提交占比 | 支持开关；正式占比未知 | `TBD%` | 产品 | 待冻结 |
| `CAP-12` | HIGH 蓝标要求 | 全量强制 / 指定任务强制 / 不强制 | 当前超链筛选 DTO 与候选 SQL 未包含 HIGH 条件 | `TBD` | 业务 + 风控 | 待冻结 |
| `CAP-13` | 无账号终态 | 等待时限、终态、错误码、是否退费 | INSTANT 启用时拒绝；ROLLING/CYCLE 可进入 NO_ACCOUNT 并每 30 秒重查 | `TBD` | 产品 + 财务 + 技术 | 待冻结 |
| `CAP-14` | 正式计费口径 | 报价、冻结、计费事实、退款/释放 | 当前最终消费按 `sent_at IS NOT NULL` 的唯一 recipient；默认钱包模式 UNAVAILABLE | `TBD` | 财务 + 产品 | 待冻结 |
| `CAP-15` | 1× 正式峰值 | actual submit/s、并发任务、账号数和消息 mix 的联合向量 | 无正式定义 | `TBD` | 业务 + 技术 | 待冻结 |
| `CAP-16` | 2× 冲击 | 倍数、持续时间、上升时间 | 倍数已给出为 2×，时长未定义 | `2× / TBD min` | 技术 + 运维 | 待冻结 |
| `CAP-17` | 积压恢复 SLO | 冲击结束至 backlog 回归基线的时间 | 未定义 | `TBD min` | 技术 + 运维 | 待冻结 |
| `CAP-18` | soak 时长 | 连续稳定运行时间 | 未定义 | `TBD h` | 技术 + 运维 | 待冻结 |
| `CAP-19` | 稳定性 SLO | p95/p99、错误率、积压斜率、CPU/内存/DB/Kafka 水位 | 未定义 | `TBD` | 技术 + 运维 | 待冻结 |
| `CAP-20` | 上线利用率 | 正式峰值 / 稳定容量 | 目标范围已给出 | `60%～70%` | 技术 + 运维 | 原则已冻结 |
| `CAP-21` | 幂等与关联保留期 | task/command 最长未决期、tombstone/ACK 关联保留、归档与重建 | Web command tombstone 当前无 TTL；其他链路存在不同保留窗口 | `TBD` | 技术 + 运维 | 待冻结 |

### 3.2 冻结规则

- 使用联合负载向量定义 1×，不能只写一个 API QPS。至少包含任务创建率、recipient 领取率、命令发布率、实际提交率、ACK/回调率、点击率及五类消息比例。
- 账号池规模按测试窗口内持续可用且租约独占的账号计数，不使用总库存。
- `maxExecutingAccounts=100`、单账号在途 20、每协议 15 个账号等是实现门禁，不是生产安全容量。
- 任一 `CAP-01`～`CAP-19` 或 `CAP-21` 仍为 `TBD` 时，只能执行 L0、L2 的确定性合同测试及 L1 工具 smoke；不得宣布容量达标。
- 修改任何冻结值需生成新 `capacityContractVersion`，既有压测结果不得沿用。

## 4. 当前上线阻断项

| ID | 缺口 | 当前证据与影响 | 门禁 |
|---|---|---|---|
| `GAP-01` | 六段漏斗事实不完整 | 后端 `submitted_at` 在本地 outbox/adapter 接受后即写入；没有协议“命令已接收”和“物理实际提交”两个独立持久事实 | L1 容量与零丢失验收前必须补齐 |
| `GAP-02` | HIGH 未进入选号 | 账号筛选合同及超链候选 SQL 没有 `businessVerificationLevel/HIGH` | 若 CAP-12 要求强制 HIGH，则 L3/L4 前必须补齐 |
| `GAP-03` | 真实钱包缺失 | 当前生产代码仅有 ZERO_TEST 与 UNAVAILABLE，默认 UNAVAILABLE | 正式计费验收与上线前必须补齐 |
| `GAP-04` | Web owner-missing 无发送兜底结果 | owner 缺失时 message send 未形成可收敛的 send result，业务侧可能持续 SENDING/reconcile | L2 前必须给出明确失败结果或可证明的恢复策略 |
| `GAP-05` | Android owner-missing 丢结果 | coordinator 可拒绝并提交 offset，却不产生 `message.send_result_reported` | L2 前必须修复或建立可证明的补偿 |
| `GAP-06` | 无账号终态未冻结 | ROLLING/CYCLE 可长期 RUNNING/NO_ACCOUNT | L1 前先冻结 CAP-13，再实现和测试 |
| `GAP-07` | 普通队列公平性不可证明 | 协议侧共享并发资源，未发现普通业务保底或饥饿测试 | L1 稳定容量前必须验证并设 SLO |
| `GAP-08` | 前端汇总口径错误/缺失 | “发送总数”展示 `recipientTotal` 而非 `sendTotal`；未展示独立提交、ACK、READ | 运营验收前必须修复或提供权威观测面 |
| `GAP-09` | 真号默认安全信封不足 | 前端新任务默认启用、立即、0.5～0.7 秒且 0 表示不限；还提供 0～0.3 秒预设 | L3/L4 必须由服务端/运行器强制白名单与硬上限 |
| `GAP-10` | Android 边界覆盖不足 | 负 `sendIntervalMs` 未拒绝；private prepared-send 缺 one-shot/ACK-before-write 节点级测试 | Android L2 前补测试并明确校验 |
| `GAP-11` | 幂等保留策略未统一 | Web tombstone 无 TTL，Android/业务侧保留窗口不同；过早清理会破坏重放幂等，无限保留又会持续占用 Redis | soak 与上线前冻结 CAP-21 并做边界测试 |
| `GAP-12` | 前端复制/编辑报价门禁需闭环 | 当前仅 pure create 进入 7 秒核对，copy 最终调用 create API 却可能传空 quoteToken；尚无真实组件交互证据 | L0/L1 中验证后端 fail-closed，并统一 create/copy/edit 合同 |

任一阻断项未关闭时，相关更高层结果只能是 `BLOCKED`，不能以“未复现”或“低流量没出问题”转为 PASS。

## 5. 被测链路与权威漏斗

```text
任务/recipient
  → C0 命令生成并落业务 outbox
  → C1 broker 接受命令
  → C2 协议 consumer 接收命令
  → C3 物理 socket/API 实际提交
  → C4 Server ACK
  → C5 DELIVERED
  → C6 READ
  → C7 短链 click（PV/UV）
```

用户要求的六段统计对应 C2～C7；C0/C1 是为判断业务侧或 broker 是否丢命令而额外保留的前置审计点。click 是旁路归因，不要求属于 READ 子集。

每一级必须有独立、幂等、可重建的事实或审计事件，至少携带：

- `runId`、`capacityContractVersion`、`tenantId`、`taskId`、`roundId`、`recipientId`；
- 稳定 `commandId`、`protocolBackend`、`protocolAccountId`，C3 以后带 `protocolMessageId`；
- `eventId`、首次发生时间、最近重放时间、结果码；
- 脱敏账号/收件人别名，不落完整手机号、正文、Token、二维码或凭据。
- 一次逻辑发送的业务键 `K` 必须冻结；至少包含 tenant/task/recipient，是否跨 round、重试或复制任务去重必须明确定义。

逐层守恒关系：

- `unique(C1.commandId) = unique(C0.commandId) - 明确取消/DEAD`；
- `unique(C3.commandId) <= unique(C2.commandId) <= unique(C1.commandId)`；
- `unique(C4.protocolMessageId) <= unique(C3.protocolMessageId)`；
- `READ ⊆ DELIVERED ⊆ Server ACK/成功提交`，若平台语义允许跨级上报，投影必须按包含关系补齐；
- 任一稳定 `commandId` 的 `C3` 次数最多 1；任一 `(tenantId, taskId, recipientId)` 的逻辑发送次数最多 1；
- 账务消费集合必须与 CAP-14 冻结的权威级别精确相等，金额按冻结单价逐国家重算差额为 0。

## 6. 分层测试策略

| 层级 | 环境 | 测试内容 | 外部副作用 | 准入 | 退出条件 |
|---|---|---|---|---|---|
| L0 | 本地 H2/fake/miniredis/测试 double | 状态机、幂等、租户、并发围栏、账务 Saga、指标投影、前端合同 | 无 | 候选 SHA 冻结 | 聚焦回归全绿；零不变量均有确定性断言 |
| L1 | 完全隔离的全链模拟环境 | 真实 DB/Redis/Kafka/调度器 + 协议加密回环、钱包/点击 Stub；1×、2×、soak、故障恢复、公平性 | 无真实消息/扣费 | CAP 冻结；对应协议回环、网络阻断和漏斗齐备 | 所有压测门槛达标，积压恢复，计数/账务差额为 0 |
| L2 | Web/Android 加密协议回环 | 第一阶段验证真实出站 Signal、双向 Noise 和 Server ACK；后续再覆盖五类消息、owner 路由及回调异常矩阵 | 无真实消息 | 协议依赖可运行；按账号 SHA-256 精确选中压测账号；真实 WhatsApp egress 被硬阻断；C2/C3/C4 可观测 | 当前阶段密文闭环与合同一致、每 command 最多一次物理提交；高级回执用例按能力单独准入 |
| L3 | 隔离测试环境 + 普通真号 | 白名单收件人、极低流量五类 E2E | 有限真实消息；按授权钱包 | L0～L2 PASS；安全信封签字 | Web/Android 全链证据与清理完成，无风控信号 |
| L4 | 隔离/生产前 canary + HIGH 蓝标 | 最终小流量业务形态验证 | 有限真实消息/费用 | HIGH 筛选和真实钱包 PASS；明确授权 | 小样本功能通过；不输出容量结论 |

## 7. 环境与测试数据

### 7.1 固定夹具

- `TENANT_A`、`TENANT_B`：构造相同局部 ID，验证所有查询、锁、事件、缓存和账本不串租户。
- 账号：从云控账号池按真实规模和类型选择 `WEB_NORMAL`、`ANDROID_NORMAL`、`WEB_HIGH`、`ANDROID_HIGH`、`NO_OWNER`、`OFFLINE`、`RESTRICTED`、`OTHER_TENANT`，但协议 auth/Signal/app-state 必须复制到 runId 隔离命名空间，禁止推进正式账号状态。
- recipient：已注册、未注册、可产生 DELIVERED、可产生 READ、只点击不读、重复手机号、跨国家价格桶。
- 消息：TEXT、LINK、IMAGE、LINK_CARD、BUTTON_CARD，分别覆盖短链开/关和合法/非法素材。
- 钱包 Stub：余额充足、不足、超时、结果未知、迟到成功、重复回调、部分失败；L3/L4 使用获准真实测试钱包。

所有有状态夹具带 `runId`，运行前快照、运行后清理；清理失败则该运行不得 PASS。

### 7.2 L3/L4 安全信封

- 只允许列入 allowlist 的发送账号和收件人；所有资源由测试窗口租约独占。
- 服务端强制覆盖任务配置：默认 disabled、每任务/每账号硬上限、冻结的安全间隔、最长运行时间和 kill switch。
- 单 profile 每消息类型先 1 条；任何重放复用原 `commandId`，禁止以新命令模拟重试。
- 禁止加群、扫码、改代理、改资料、自动切换账号或跨协议猜测 owner。
- 出现 `ACCOUNT_REACHOUT_RESTRICTED`、`RATE_LIMITED`、`CHAT_SUSPENDED`、非白名单目标、重复物理提交或账务异常立即停测。

## 8. L0～L4 执行顺序

1. 固化四仓 SHA、测试计划、验收清单、压测用例和 `capacityContractVersion`。
2. 执行 L0 后端、前端、Web、Android 定向回归；将环境阻塞与断言失败分开记录。
3. 关闭 GAP-01～GAP-12 中对应层的阻断项，并为缺口先补失败测试。
4. 分别执行 L2 Web/Android Crypto Loopback smoke：Web 已完成 synthetic Signal 对端解密、双向 Noise 和密文 ACK 的 test1 低流量校准；Android 继续完成单账号真实发送路径校准。两端均通过后再补高级回执矩阵。
5. 部署全隔离 L1；运行 0.1× smoke、1× steady、2× shock、soak、恢复与故障注入。
6. 独立对账 SQL/事件核对零不变量，复核原始证据而非只看仪表盘。
7. 获得书面授权后执行普通真号 L3。
8. HIGH、真实钱包、owner 和安全信封全部通过后执行 L4 小流量 canary。
9. 按验收清单签署 `PASS / FAIL / BLOCKED`；任何 required 项不得标为 SKIPPED 后放行。

## 9. 通用通过标准

### 9.1 必须为零

- 命令丢失：0。
- 同一 recipient 的逻辑重复发送：0。
- 同一 command 的重复物理提交：0。
- 跨租户读、写、事件、缓存或账本串数：0。
- 按 CAP-14 口径重算的账务人数差额、金额差额：0。
- recipient 事实与 task/round/account 聚合的计数漂移：0。
- 未经允许的真实号码触达：0。

### 9.2 容量与恢复

- 1× 峰值按冻结时长稳定运行，吞吐不持续下降，backlog 斜率不持续为正，各 SLO 达标。
- 2× 冲击期间允许积压，但不得破坏零不变量；冲击结束后在 CAP-17 内恢复到基线。
- soak 期间无非预期的内存/连接/线程/Kafka lag 增长；按 CAP-21 保留的 Redis key/tombstone 允许随命令线性增长，但实际增长率、容量、归档/清理和重建能力必须与模型一致；结束后全量 reconciliation 差额为 0。
- 稳定容量 `Cstable` 是同时满足上述条件的最高联合负载档，不是一次瞬时峰值。
- 隔离全链容量不能单独外推为可部署容量。`Cdeploy = min(Cstable, 合格 Web 账号×安全速率, 合格 Android 账号×安全速率, 钱包容量, 回调/投影容量, DB/Kafka 容量)`。
- 正式峰值 `Pprod` 必须满足 `Pprod <= U × Cdeploy`，其中 `U` 在查看最终压测结果前冻结为 60%～70% 内的一个值，且绝不高于 70%。

## 10. 结果判定与停止规则

- `PASS`：所有 required 断言执行并通过，证据绑定同一候选与容量合同，cleanup 完成。
- `FAIL`：环境可测但任一正确性、性能、恢复、安全或口径断言不满足。
- `BLOCKED`：工具链、环境、账号、钱包、可观测性或决策缺失导致无法形成可信结论。
- `NOT_RUN`：尚未执行；不得与 BLOCKED 或 PASS 混用。

出现下列任一情况立即停止增压并保留现场：重复真实发送、跨租户数据、非白名单触达、账务差额非零、HIGH 账号约束失效、风控限制、错误率连续越线、DB/Kafka/Redis 硬水位、积压无恢复趋势、观测链断裂或 kill switch 失效。

## 11. 本轮已启动的测试

运行号：`HL-20260903-L0-001`、`HL-20260903-TEST1-001`。除 L0/L2 本地回归外，本轮已连接 test1 执行真实服务深检、UI/API 只读 smoke 与数据库快照核对；没有触发真实消息或钱包动作。

| 范围 | 结果 | 说明 |
|---|---|---|
| Armada 超链聚焦测试 | `PASS`，324 tests | 0 failure / 0 error / 0 skipped；覆盖状态机、Mapper、派发并发、UNKNOWN 恢复、ACK、计费 Saga、租户锁与指标投影。当前机器用 JBR 25，并加 Byte Buddy experimental workaround；仍须在正式 Java 17 CI 复跑 |
| 前端超链全依赖定向回归 | `PASS`，132 tests | 使用仓库 alias + HTTP double；0 fail，无真实 API 调用 |
| 前端 TypeScript 类型检查 | `PASS` | `tsc --noEmit` 与 `vue-tsc --noEmit --skipLibCheck` 均 exit 0 |
| Android 相关包完整 race 回归 | `PASS`，7 packages | 临时安装并校验 Go 1.25.1，依赖按 go.sum 进入临时缓存；`internal/armada`、service app/node/nodes、external、coordinator、fleet 全部通过，无真实协议动作；全仓 vet/build 通过 |
| Android 全仓 `go test ./...` | `FAIL`，15 assertions | 7 个 deployment contract 断言受本机 `envsubst`/文件期望影响，`pkg/noise` 8 项包含缺 `vectors.txt` 与向量不匹配；超链相关 7 包保持通过，但发布总门禁未绿 |
| Web 协议全量单元测试 | `PASS`，1,324 tests / 114 suites | 候选仓按正式 `package-lock.json` 执行 `npm ci` 与 `npm test`；维护 CLI 9 tests、crypto loopback 3 tests、typecheck 和 build 同时通过；owner-missing 收口缺口仍存在 |
| Web test1 加密回环 canary | `PASS_WITH_LIMITATION`，1,000/1,000 ACK | Baileys Signal+Noise 双端真实加解密，0 error，约 3236 msg/s；不创建 WASocket、不使用真实账号或 WhatsApp 网络，只作为密码学/实例资源基线 |
| test1 服务深检与远端 UI smoke | `PASS` | `runner-deep-check` 通过；远端 runId `20260903T062332Z-25867866` 通过 5 个路由，未发生业务写请求 |
| test1 超链任务 browser/API smoke | `PASS` | browser skill 复用具备权限登录态进入真实 `#/hyperlink/tasks`；看到 9 条任务；列表和 create-context GET 均 HTTP 200 |
| test1 数据库不变量快照 | `PASS_SNAPSHOT` | 跨租户关联、重复 command/ACK、计数漂移、终态账务差额均为 0；当前无 RUNNING 任务 |
| L1 容量 / L2 跨服务 Stub / L3 / L4 | `BLOCKED` | 容量合同、全链 Stub 和获准真号夹具未就绪；test1 为 `ZERO_TEST`，Web 在线 0、eligible HIGH 0，不能执行真实钱包、Web 真号或蓝标 canary |

WhatsApp 隔离对端及真实加解密要求见 [WhatsApp 加密回环压测设计](./2026-09-03-whatsapp-crypto-mock-load-test-design.md)。详细验收状态见 [超链任务验收清单](./2026-09-03-hyperlink-task-acceptance-checklist.md)，test1 现场证据见 [test1 真实环境执行结果](./2026-09-03-hyperlink-task-test1-live-execution-result.md)，压力与故障注入步骤见 [超链任务压测用例](./2026-09-03-hyperlink-task-load-test-cases.md)。
