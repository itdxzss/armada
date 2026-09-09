# 拉群板块功能测试与压测方案

> 状态：`SIM_CORE_AND_LOCAL_LOAD_PASS / H2_OUTBOX_CHAIN_PASS / PL_R01_R02_PASS / PL_R06_FAIL / ARMADA_PERF_INTEGRATION_PENDING / LIVE_HAPPY_PATH_BLOCKED`
>
> 套件版本：`group-task-test-load-v2`
>
> 适用范围：有状态协议模拟环境、专用性能环境和 `test1`；容量结论不在 test1 形成。

## 1. 目标

对以下两条业务链完成可复核的功能、异常、幂等、恢复、资源收口和性能验证：

1. 普通拉群：管理员资源、邀请码失效、入群审批、提权、拉手进群、异常换群、任务收口。
2. 速拉群 / 建群营销：换号、联系人预保存、建群、禁言、营销发送、回执、父任务汇总和资源释放。

最终必须同时证明：

- 页面不会无原因卡住或反复跳状态；
- 超时、UNKNOWN、重复和乱序回调不会产生重复拉人、提权、建群或营销发送；
- `taskId -> executionId/itemId -> action/attemptId -> commandId -> 协议结果 -> 最终裁决` 可追踪；
- 页面、API、数据库、Outbox/Kafka、协议结果和真实群状态一致；
- Web、Android 和混合协议分别通过；
- 任务结束后账号、拉手、群链接、群占用和待发命令全部收口。

## 2. 当前决策与执行顺序

当前处于平台封控/高风险窗口，真实群操作门禁关闭。测试改为三层推进：

| 层级 | 目的 | 当前动作 | WhatsApp 真实副作用 |
|---|---|---|---:|
| Logic 逻辑层 | 验证状态机、恢复、幂等、父子汇总、追踪与资源收口 | 建设并运行 `stateful-protocol-sim` | 0 |
| Perf 性能层 | 验证调度、Outbox/Kafka、worker、数据库及协议计算成本 | 专用性能环境 open-loop 压测；Crypto Loopback 单独校准 | 0 |
| Live test1 金丝雀 | 验证真实 Web/Android 外部兼容性 | 封控解除且门禁重新批准后才恢复 | 严格限量 |

### 2.1 已有真实基线：test1 `#199`

`#199 / execution 449 / Android / 普通拉群 / 1 群 1 目标` 已于 2026-09-03 19:28 CST 执行。目标在
19:29:16.077 入群成功，随后收到独立 `GROUP_HEALTH / CHAT_SUSPENDED`，execution 于 19:29:19.035 以
`GROUP_BANNED` 失败。5 个同源封群事件（包含终态后的迟到事件）没有触发重复拉人、提权或新 command；8 个 Android
Outbox 均为 `SENT` 且 retry=0。调度占用已释放，但仍存在三类缺口：

- 父任务显示 `COMPLETED`、子 execution 为 `FAILED/GROUP_BANNED`，生命周期完成容易被误读为业务成功；
- 页面最初停在“进行中/0/1/无异常”，人工点击查询后才刷新为“已完成/1/1/异常群组 1”；
- 封禁群的协议成员残留尚在，当前邀请码仍可选，群封禁与邀请码健康没有同步收口。

因此 `#199` 只登记为 `PL-S01C/GROUP_BANNED` 风险路径回归证据，**不登记为 `PL-F02` Android 正常闭环 PASS**。
模拟器必须能精确复现“目标成功后 CHAT_SUSPENDED×5，含迟到事件”。当前建议的 `PROPOSED_ORACLE` 为：目标协议结果成功、execution
失败、父生命周期完成但业务 outcome 失败、processed=1/1、success=0/1、abnormal=1、终态后新增协议动作=0；封禁群及
邀请码进入隔离，不再分配。该判据须在 W0 经产品、协议和测试签字后才转为冻结 oracle。

### 2.2 当前允许与禁止

- 允许：只读 quick、证据/规则冻结、模拟器开发与运行、专用性能环境零外发压测、页面/API 无外部动作检查。
- 禁止：继续寻找“没封的真群”、复用 `#199` 群或邀请码、在 test1 做异常风暴、并发拉群、容量探顶或营销 soak。
- 门禁恢复前，所有真实正常链用例维持 `BLOCKED/RISK_WINDOW`；模拟器 PASS 不能冒充真实 E2E PASS。

### 2.3 执行依赖图

```text
W0 冻结状态/回调/副作用 oracle 和 #199 回放夹具
-> W1 模拟器内核、安全栅栏、账本和自动对账
   |-> W2 普通拉群正常链/P0 故障 ---------|
   |-> W3 速拉群/建群营销正常链/P0 故障 --|-> W4 冻结 K/执行器 -> W5 性能运行
   |-> W4 生成器与采集器骨架 -------------|
W5 + 对应 Logic P0 + 修复候选部署 + LIVE_GATE=OPEN
-> W6 按 lane 和 1/3/10 累计样本逐级跑 test1 金丝雀
-> 四平面对账、清理与验收报告
```

W2 与 W3 可并行，W4 骨架可在 W1 后并行开发，但必须等 W2/W3 冻结真实 command 放大 fixture 后才能完成 K 校准；
W6 必须等 W5 和对应业务的 Logic P0 通过。任一 required 用例未执行，整体结论只能是 `BLOCKED`，不能记为 PASS；
各层结论必须分别报告，不能互相替代。

## 3. 范围

### 3.1 包含

- Web 页面、Android 客户端对应的后端路由和混合协议调度。
- 任务创建、启动、暂停、恢复、停止、异常换群、回调收敛、父任务汇总和资源释放。
- API/DB 状态机、Outbox、Kafka command/result、协议物理动作及前端呈现。
- 单次请求幂等、worker 租约接管、服务重启恢复、超时/UNKNOWN、重复/乱序/迟到回调。
- 控制面性能、调度吞吐、Outbox/Kafka 积压、协议执行速率、查询页面性能和资源水位。

### 3.2 不包含

- 未经授权向真实客户、非白名单联系人或生产群发送消息或执行群操作。
- 在 test1 寻找系统容量极限、主动打封号阈值或绕过 WhatsApp 风控。
- 通过写 SQL 篡改任务状态完成测试；业务状态变更只允许走现有 API、受审计夹具或隔离故障注入器。
- 为制造积压而停止共享 Kafka、数据库或协议服务；这类破坏性测试只能在隔离环境执行。

## 4. 测试 Profile

| Profile | 环境 | 是否产生真实协议副作用 | 用途 |
|---|---|---:|---|
| `unit-contract` | 本地/CI | 否 | 状态机、Mapper、幂等键、重复/乱序回调、资源释放 |
| `stateful-sim` | 隔离环境 + 有状态协议模拟器 | 否 | 正常业务世界、邀请码/审批/成员/角色/封群、幂等和故障切点 |
| `load-sim`（原 `load-stub`） | 专用性能环境 + 有状态模拟器 | 否 | 100/300/500 活动 execution、命令突发、租约接管和容量拐点 |
| `crypto-loopback` | Web/Android 隔离进程 | 否 | Signal/Noise/ACK 的 CPU、内存和吞吐成本校准，不验证群业务状态 |
| `quick` | test1 | 否 | 候选版本、依赖、页面、DB/Kafka/Outbox/资源基线 |
| `canary-web` | test1 | 是，严格限量 | Web 单链真实群动作 |
| `canary-android` | test1 | 是，严格限量 | Android 单链真实群动作 |
| `canary-mixed` | test1 | 是，严格限量 | Web/Android 混合任务和跨协议汇总 |
| `fault-callback` | 默认仅隔离环境 | 否 | 超时、UNKNOWN、重复、乱序和迟到回调；test1 满足专用注入门禁后才可例外开放 |
| `load-live` | test1 | 是，需逐级授权 | 小流量爬坡和 30 分钟稳定性，不寻找极限 |

## 5. 候选版本与前置门禁

每次运行先冻结 `runId`、套件版本、用例文档 SHA-256，以及后端、前端、Web 协议、Android 协议四份实际
Git SHA 或制品 digest。任一运行制品无法绑定候选版本，测试记为 `BLOCKED/VERSION_UNKNOWN`。

前置检查：

1. test1 身份、域名、数据库 schema、Kafka topic/group、Redis prefix 和四个运行制品一致。
2. 后端、Web 协议、Android 协议、MySQL、Redis、Kafka 健康；不存在执行中的非本轮大任务。
3. Outbox/Kafka 基线连续 5 分钟稳定，旧积压为 0 或已独立标记，不能归因到本轮。
4. 测试租户和生产/运营租户隔离；测试任务统一命名 `GRP-<runId>-<caseId>`。
5. 测试账号在线、凭据有效、能力与协议后端明确；账号没有被其他任务占用。
6. 测试群、邀请链接、联系人、营销模板、图片素材全部属于白名单并可清理。
7. 清理脚本先 dry-run，能通过 API 停止任务、释放资源并只读证明无残留。
8. 告警和紧急停派入口已验证；值守人和停止口令已确定。
9. `manifest.json` 冻结副本数、容器 requests/limits、JVM/Go 参数、DB 规格、Kafka 分区数、调度参数和同机干扰负载；
   任一项变化必须重新建立空载基线，不能横向比较容量结果。
10. Outbox、Kafka、日志和指标可按 `runId + tenant + commandId` 归属。本轮与历史 backlog 无法分离时，只能把全局
    指标当环境背景，本轮性能结论记为 `BLOCKED/ATTRIBUTION_GAP`。

`stateful-sim` 和 `load-sim` 还必须通过网络级安全门禁：运行命名空间默认拒绝公网 egress、真实协议 endpoint 不可解析、真实凭据为空、
启动自检确认 outbound adapter 为 simulator，并用一个不可路由诱捕目标证明不会降级到真实协议。任一项不满足，不得发压。

test1 的回调故障注入默认关闭。只有同时具备以下条件才可例外启用：专用签名入口或隔离 topic/consumer group、
测试租户强路由、`runId + commandId` 双白名单、非本轮 command 硬拒绝、注入回调不得派生新协议命令，以及先执行一轮
拒绝测试。缺任一项，重复/乱序/迟到回调只在隔离协议桩执行。

## 6. 测试夹具与真实 WhatsApp 安全信封

| 别名 | 用途 |
|---|---|
| `TENANT_GRP_TEST` | test1 专用隔离租户 |
| `USER_OPERATOR` / `USER_VIEW` | 全权限操作员 / 只读用户 |
| `ACCOUNT_WEB_A/B` | 已授权 Web 测试账号；至少一主一备 |
| `ACCOUNT_ANDROID_A/B` | 已授权 Android 测试账号；至少一主一备 |
| `ACCOUNT_OFFLINE/RESTRICTED` | 离线和受限负例，不参与真实成功动作 |
| `GROUP_VALID_A/B` | 可控有效群，管理员和成员事实可核对 |
| `GROUP_APPROVAL` | 开启入群审批的可控群 |
| `GROUP_ROTATED_INVITE` | 可轮换邀请码且能保留 JID 的可控群 |
| `GROUP_BANNED_FIXTURE` | 隔离桩中的封群事实；test1 不主动制造真实封群 |
| `CONTACTS_5/10/20` | 明确授权的测试联系人集合 |
| `TEMPLATE_TEXT` | 带唯一 runId 的无害测试文本；每群至多发送一次 |
| `ASSET_VALID/INVALID` | 有效素材与只在协议发布前失败的无效素材 |

test1 每个 canary case 的默认安全上限：

- 新建群不超过 1 个；被测任务结束后必须清理或移交到指定测试分组。
- 每个群最多加入 5 个白名单测试联系人；更大数据量只在协议桩执行。
- 每群营销消息最多 1 条，正文必须带 runId；不得自动重复发送。
- 每个协议后端同时最多 1 个真实外部动作，爬坡级别另行逐级授权。
- 不记录完整手机号、邀请码、群 JID、消息正文、凭据、代理或密钥。

每次真协议运行还必须冻结以下预算，空值即不允许执行：

| 预算 | 本轮总上限 | 单账号上限 | 当日累计上限 | 服务端栅栏 |
|---|---:|---:|---:|---|
| 新建群 | `{{maxGroupsRun}}` | `{{maxGroupsPerAccount}}` | `{{maxGroupsDay}}` | 超额在协议发布前拒绝 |
| 拉人/加人 | `{{maxMemberAddsRun}}` | `{{maxMemberAddsPerAccount}}` | `{{maxMemberAddsDay}}` | 非白名单或超额拒绝 |
| 提权/禁言/退群 | `{{maxAdminOpsRun}}` | `{{maxAdminOpsPerAccount}}` | `{{maxAdminOpsDay}}` | operationId 去重并限额 |
| 营销消息 | `{{maxMessagesRun}}` | `{{maxMessagesPerAccount}}` | `{{maxMessagesDay}}` | target 白名单且超额拒绝 |

限额必须同时存在于生成器和服务端出站栅栏；账号最小命令间隔、冷却时间和单日额度由协议负责人冻结。每个层级开始前
核对当日已使用额度，不能仅依赖执行人口头授权。

## 7. 功能测试方法

每条用例使用约五分钟主观察窗，并保留 T0 前 30 秒基线与 T0 后最多两个业务超时周期的收敛窗。至少采集：

- 页面：任务列表、详情、状态变化、阻塞原因、操作入口和最终汇总。
- API：请求时间、业务码、task/execution/item/action/attempt ID 和关键状态。
- DB：前后快照、版本号、行数、唯一键、资源占用和释放时间；查询默认只读。
- Outbox/Kafka：commandId、backend、创建/发送/重试/终态、topic partition/offset 和结果顺序。
- 协议：同一 command 的实际调用次数、原始结果摘要和真实群/消息副作用计数。

每个动作类型同时记录 `expected`、`actual` 和 `duplicate`。重复副作用按逻辑操作账本计算：

```text
duplicate(action) = max(0, actualPhysicalSuccess(action) - expectedLogicalSuccess(action))
```

拉人、提权、建群、禁言、退群和营销发送的重复数必须分别为 0。只比较 DB 行数或 commandId 数不足以证明
没有真实重复副作用。账本还要按业务键（如 `group+member+ADD`、`group+account+PROMOTE`）分别记录
`dispatchCount/acceptCount/mutationCount/resultCount`，以识别“第二次调用被协议幂等成 no-op”和“数量相同但作用到错误群”这两类
仅靠 `actual-expected` 无法发现的问题。

## 8. 压测模型

### 8.1 容量单位

- 普通拉群：`task -> group execution -> role account -> material member -> pull wave/call -> command/result`。
- 速拉群：`task -> group execution -> material -> group operation -> marketing target/attempt -> command/result`。
- 建群营销：`task -> item -> 双向联系人准备 -> group create -> mute/settings -> marketing send -> result`。

至少同时报告任务数、活动 execution/item 数、成员数、逻辑操作数和实际 command 数，禁止只报 API QPS。

业务最低目标在评审时冻结为 `task/min`、`execution-or-item/min`、`command/s` 和 `marketing-target/s` 四组数值。
若没有最低目标，测试只能报告观测到的安全水位，不能声称“满足容量需求”。安全容量定义为：相同构建、配置和 seed 下，
连续 30 分钟 backlog 不增长且所有硬门禁和冻结 SLO 均满足的最高持续输入速率；建议发布水位不高于该值的 70%。

### 8.2 当前实现基线

- 普通拉群调度默认每 1 秒扫描，单轮最多 100 个 execution，租约 30 秒。
- 普通建群准入默认最多 20 个活动任务、5,000 个 in-flight group；租户 10 请求/分钟、用户 5 请求/分钟。
- 速拉群 scheduler 默认每 1 秒扫描。
- 协议 command publisher 默认 max-in-flight 100；Outbox dispatcher 单批 100、单次 drain 最多 5 批。

上述是当前配置事实，不是性能承诺；压测用于找到安全水位，不能直接把配置上限当验收容量。

### 8.3 有状态协议模拟器

固定返回 SUCCESS/FAIL 的 Stub 无法证明业务状态机和零重复副作用。Logic 层必须保留真实 UI/API、任务状态机、scheduler、
Outbox/Kafka、回调入口、DB 投影和页面查询，仅把 Web/Android 的公网出站适配器切到独立的
`stateful-protocol-sim`：

```text
真实 UI/API -> 真实调度/Outbox/Kafka -> Web/Android 测试适配器
-> Stateful Protocol Simulator -> 真实回调入口 -> DB/API/UI -> 独立 Oracle
```

模拟器的最小组件和状态：

| 组件 | 必须保存的事实 |
|---|---|
| World Store | 账号 online/restricted/risk/占用、联系人、群 JID/成员/角色/审批/禁言/封禁、邀请码版本及可选性、消息事实 |
| Operation Ledger | operationId、attemptNo、commandId、接受次数、物理 mutation、结果和重放次数；物理事实与回执状态分离 |
| Event Scheduler | protocolTime、deliveryTime、虚拟时间、延迟、丢失、重复、乱序和迟到 |
| Fault Controller | 仅按 `runId+commandId+action+cutPoint` 注入，非本轮命令硬拒绝 |
| Independent Oracle | 从协议世界事实推导子项终态、父 lifecycle/outcome、资源 release/retain/quarantine，不复制被测状态机结论 |

每个有副作用动作至少覆盖六个切点：接受前确定失败；接受后执行前断链；物理生效后响应丢失；结果发布后 offset 提交前
崩溃；旧 attempt 的重复/迟到/乱序/冲突结果；Outbox 在 PENDING/LOCKED/SENT 时 Stop。群健康事件必须作为独立风险事件，
不能伪装成某条 command 的失败结果。UNKNOWN 必须分成“确认未生效”“确认已生效”“尚未确认”，只有确认未生效才能
创建新 operation。

首批运行批次：

| 批次 | 范围 | 主要用例 | 进入下一批的出口 |
|---|---|---|---|
| A 正常链与追踪 | 普通拉群 Web、Android、混合正常闭环；父子汇总和全链 | `PL-F01~F03`、`PL-T01`、`PL-S03` | 三协议同语义、全链 100%、资源出口正确 |
| B 资源与恢复 | 管理员稳定等待/单次恢复、邀请码轮换、审批恢复 | `PL-R01~R02/R06`、`PL-I01~I03`、`PL-A01~A03` | 无空转、单次恢复、UNKNOWN 不盲重试 |
| C 封群与竞态 | 精确回放 #199、Stop、迟到/乱序、worker crash | `PL-S01C`、`PL-J02/J04`、`PL-S02/S04/S05` | 终态后新 command=0，mutation 不超过一次 |
| D 速拉群/营销 | Web、Android、双向混合；换号、建群、禁言、发送、父汇总 | `FG-F01~F03`、`CM-F01~F03`、`MK-I01A/W1/M/I02/I03` | 最终有效群最多 1，发送不重做，孤儿资源=0 |

每个 P0 场景以同一 seed 连续重放 10 次，再至少用 3 个 seed 跑并发场景。A 未通过不得进入 B/C，所有 P0 逻辑门禁
未通过不得进入性能故障风暴。

### 8.4 Crypto Loopback 与业务模拟器边界

| 能力 | Stateful Protocol Simulator | Crypto Loopback |
|---|---:|---:|
| 群成员、角色、审批、邀请码、禁言、封群状态 | 是 | 否 |
| 业务回调重复/乱序、operation 幂等、父子汇总 | 是 | 否 |
| Signal/Noise 编解码和 ACK 处理的真实 CPU/内存成本 | 否 | 是 |
| WhatsApp 公网、服务端风控和真实送达 | 否 | 否 |

两者分别出报告。Crypto Loopback 可作为营销发送阶段的成本校准插件，但其通过不能替代群业务逻辑、Armada 全链或
真实 test1 兼容性通过。

### 8.5 `load-sim` 可复现负载曲线

生成器采用 open-loop，到达率不随系统变慢而自动下降。默认业务比例为普通拉群 40%、速拉群 30%、建群营销 30%；
协议比例为 Web 40%、Android 40%、混合 20%。固定 seed 写入 manifest；同一候选至少重复 3 次并报告中位数和每轮值。
协议响应延迟分布默认 p50=300ms、p95=2s、p99=5s，另行执行故障分布。每个 logical execution/item 的阶段数、
成员数和 command 放大系数必须由同一 fixture 清单生成并保存，禁止运行中随机漂移。

端到端负载只能通过公开的任务创建/启动 API 注入，控制量为 `task/s`；不得直写业务表、Outbox 或 Kafka。先用校准轮得到
`K = 平均 execution/item/任务 × 平均 command/execution-or-item`，再以 `targetTaskRate = targetCommandRate / K`
设置 open-loop task 到达率，`command/s` 只作为实测结果报告。若需单测 dispatcher/consumer，必须使用独立命名的
`component-command` profile 和受签名的测试入口，结论不得外推为端到端容量。

| 阶段 | 活动 execution/item | API 注入与目标 command 速率 | 持续时间 | 目的 |
|---|---:|---:|---:|---|
| S0 | 0 | 0 task/s | 5m | 空载基线 |
| S1 | 100 | 经 K 换算，目标首 20s 约 5 command/s | 10m | 冒烟与单轮 burst 恢复 |
| S2A | 300 | 经 K 换算，目标首 15s 约 20 command/s | 10m | 多轮 burst、排队和租约恢复 |
| S2B | 按实测并发 | 经 K 换算，持续目标 20 command/s | 10m | 真正稳态与 >=10,000 样本前置 |
| S3 | 500 | 经 K 换算，目标首 10s 约 50 command/s | 15m | 调度与 Outbox drain 边界 |
| S5 / `PF-S05` | 0.8×S2B | worker crash 1%、随机 Stop 20% | 20m | Tfence、接管和在途收敛 |
| S4 / `PF-S04` | S2B 稳态 | 重复 10%、乱序 5%、TIMEOUT/UNKNOWN 5%、丢失 2% | 30m | 稳定性、幂等和资源趋势 |
| S6 / `PF-S06` | 从已通过速率逐级 +20% | 保持同一业务/协议/阶段比例 | 每级 10m | 找到首个持续软阈值或错误拐点 |

协议桩必须能分别返回 SUCCESS、确定失败、TIMEOUT、UNKNOWN、重复结果、乱序结果、审批等待和失效邀请码，
并记录每个 operationId/commandId 的物理调用次数。
PF-S04 只有在同 attempt 冲突回调优先级、UNKNOWN 人工核对及新 operation 规则冻结后才可执行；在此之前状态为
`BLOCKED/ORACLE_UNDEFINED`。

标准容量轮默认每个 execution 1 群、5 个 synthetic 目标，资源池按最大活动量的 1.5 倍准备，避免把资源不足混入容量
结论；资源争抢另跑 10% 共享资源的 contention profile。普通拉群还需单独跑 500->2,000->5,000->10,000 execution 的
宽任务 profile，固定总输入速率，避免把“单任务宽度”和“总并发”混成同一容量结论。

### 8.6 当前工具缺口

现有 `perf2-marketing-load-test.sh` 只支持列出并恢复 perf2 的暂停营销任务，默认 dry-run；它不是普通拉群、速拉群和
建群营销的通用负载生成器，不能据此声称端到端压测工具已具备。W4 必须补齐：公开 API 创建/启动、固定 seed、open-loop
到达率、三业务/三协议比例、runId 归因、Tfence 停派、四平面采集、协议 mutation 账本和只清理本轮资源。未补齐前
`PF-S01~S06` 为 `BLOCKED/TOOLING_GAP`。

### 8.7 `load-live` 分级

test1 只做兼容性和低流量稳定性，不用于形成容量结论。把“覆盖哪条 lane”和“该 lane 放到多少样本”作为两个独立
维度，避免先跑 9 条覆盖任务、再把 L2 错解成总量降回 3 条。

| Live lane | 首次可运行条件 | 单次安全信封 |
|---|---|---|
| `PL-W` / `PL-A` | 对应 Logic P0 通过、修复候选已部署、Live Gate 开放；先 Web 后 Android | 1 任务、1 独立群、1 目标、1 manager、1 puller、并发 1 |
| `FG-W` / `FG-A` | 对应 FG/MK Logic P0 通过；PL 两条 lane 至少各有 1 个干净样本；追加授权 | 1 任务/群、<=5 人、<=1 消息、并发 1 |
| `CM-W` / `CM-A` | 对应 CM/MK Logic P0 通过；FG 两条 lane 至少各有 1 个干净样本；追加授权 | 1 任务/item/群、<=5 人、<=1 消息、并发 1 |
| `PL-MIX` / `FG-MIX` / `CM-MIX` | 对应 Web、Android lane 都达到 L2，混合 Logic P0 通过；追加授权 | 每条 lane 使用独立 taskId/群，始终并发 1 |

| 放量级别 | 每条已获准 lane 的累计样本数 | 默认状态 | 观察门 |
|---|---:|---|---|
| L0 | 0 | `ACTIVE/NO-GO_EXPECTED` | 5m 只读基线、夹具隔离和停派验证 |
| L1 | 1 | `HOLD/LOCKDOWN_ACTIVE` | 主窗 5m，T+30m/T+24h 复核 |
| L2 | 3（在 L1 基础上新增 2） | `NOT_AUTHORIZED` | 始终最多 1 个活动；3/3 干净 |
| L3 | 10（在 L2 基础上新增 7） | `NOT_AUTHORIZED` | 再次书面授权；10/10 干净 |
| L4 | 不增加固定样本数 | `NOT_AUTHORIZED` | 用已通过水位做 30m 稳定性并复核迟到事件 |

每条 lane、每一级独立授权。运行卡必须保存机械门禁记录：`LIVE_GATE=CLOSED|OPEN`、批准人、允许 lane/rung、
`openedAt`、`expiresAt`；字段缺失、过期或范围不匹配一律按 CLOSED。L1 还要求运维/协议负责人明确给出
`LOCKDOWN_CLEARED`，候选群及其所有邀请码均无封禁矛盾，紧急停派可用。每例 T+30m 零迟到副作用、T+24h 无新增
账号/群风险并完成资源清理，才能增加该 lane 的累计样本；安全冷却值需由运维冻结，时间到不等于自动解封。容量极限和
破坏性故障只在 `load-sim` 或专用性能环境执行。

## 9. 指标与阈值

### 9.1 硬性业务阈值

| 指标 | 阈值 |
|---|---:|
| 拉人/提权/建群/营销的重复物理副作用 | 0 |
| 无 task/execution/command/result 归属的协议动作 | 0 |
| 父 lifecycle/outcome/processed/success 与子项按冻结聚合规则的差异 | 0 |
| 终态后未释放的拉手、账号、链接、群占用和 worker lease | 0 |
| 未按规则登记 `retain/quarantine` 的协议成员、封禁群或邀请码残留 | 0 |
| 无原因持续超过两个业务超时周期的活动行 | 0 |
| 稳态窗口内无业务事实变化却反复跳状态 | 0 |
| Web/Android 同语义结果不一致 | 0 |
| 跨租户数据或操作 | 0 |

### 9.2 建议性能阈值（评审后冻结）

当前没有已确认 SLA，以下仅作为首版建议值：

| 指标 | 建议阈值 |
|---|---:|
| 创建/启动/暂停/恢复 API P95 / P99 | <=1s / <=2s |
| 列表/详情 API P95 / P99 | <=1s / <=2s |
| 到期到首次 claim 调度延迟 P95 / max | <=5s / <=30s |
| Outbox oldest pending age（稳态） | <=30s |
| 负载停止后本轮 Kafka/Outbox 清空：S1~S6/test1 L1 | <=300s |
| test1 L4 30m 小流量稳定性结束后本轮 Kafka/Outbox 清空 | <=600s |
| 非注入系统错误率 | 样本 >=10,000/关键操作时 <0.1%；小样本要求 0 错误并报告计数/置信区间 |
| 后端/协议容器 CPU P95 / max | <70% / <85% |
| 容器内存 max | <80%；排空并经过固定 10m 空闲/自然 GC 窗后 <=空载基线+10% |
| DB 连接池使用率 max | <80%，无连接等待超时 |

延迟百分位只有在该关键操作样本量足够时才作为门禁；P99 每类至少 10,000 个样本，否则只报告原始分布和最大值。
软阈值以 1 分钟窗口计算，连续 3 个窗口超限才停止升级；硬门禁立即停止。内存泄漏以排空后固定 10 分钟空闲/自然
GC 观察窗相对空载基线的差值为准，连续 3 个 10 分钟窗口增长 >1% 也判失败；受控 JVM GC 如可用只作附加证据，
不作为跨服务前提。若基线已超过建议值，测试先 BLOCKED 并在运行前重新定阈值，不能在运行后修改门槛让结果通过。

## 10. 监控与证据

按成本分级采样，避免采集器反向污染压测：

- 每 1 秒：CPU、内存、重启、线程/goroutine、请求量/错误/延迟、worker 与 Kafka/Outbox 轻量计数。
- 每 5~10 秒：DB 连接/锁/慢查询聚合、按本轮归属的 backlog/oldest age、资源水位。
- 每个阶段边界：DB 前后快照、真实群成员/角色/禁言/消息差分和页面/API 状态。
- 页面截图按状态变化和异常事件采集，不做每秒全页截图。
- 同时记录采集器 CPU、查询耗时和丢样率；采样中断或观测开销不可量化时标记证据缺口。

证据目录按 `runId/caseId` 保存：`manifest.json`、`timeline.csv`、`api.jsonl`、`db-before-after.json`、
`outbox-kafka.jsonl`、`simulator-world-before-after.json`、`protocol-ledger.jsonl`、`ui/`、`metrics.csv`、
`cleanup.json` 和 `summary.json`。
所有文件脱敏并记录 SHA-256。

## 11. 停止条件

命中任一条件立即停止产生新任务/命令，保留在途事实并进入安全收口：

- 出现一次非预期真实拉人、提权、建群、退群或营销重复发送。
- 触达非白名单账号、群或联系人，或账号出现新增风控/封禁/重登录要求；真实金丝雀出现任一
  `CHAT_SUSPENDED/GROUP_BANNED/TERMINATED` 立即停止整条车道。
- 父子计数错误、跨租户写入、command 无法归属或清理接口不能安全工作。
- 5xx 连续 1 分钟超过 1%，或 P95 连续 3 分钟超过冻结阈值两倍。
- CPU >90% 持续 60 秒、内存 >90%、DB 连接池 >85%、出现死锁或复制延迟 >5 秒。
- 输入阶段 Kafka lag 连续 3 分钟增长、Outbox oldest age >60 秒或 DEAD 快速增加。停止输入进入排空后不再用 60 秒
  oldest age 触发，而要求本轮 backlog/oldest 连续下降或持平、不得连续 3 个采样点恶化，并在对应 300/600 秒 deadline 前清空。
- 证据采样中断超过 10 秒、候选版本变化、共享环境出现非本轮大任务。

停止触发时记录 `Tstop`；生成器停止并由服务端出站栅栏接受的时刻记为 `Tfence`，要求 `Tfence-Tstop <=5s`。
账本必须按 dispatch 接受时刻区分 `Tfence` 前已进入协议且不可撤销的 in-flight 动作与 `Tfence` 后动作。Tfence 后新增
dispatch/command 必须为 0；Tfence 前的在途动作即使在 Tfence 后才完成，也允许至多完成一次，但必须落事实、清理资源且
`actual <= authorized expected`、`duplicate=0`。前者只允许等待原 command 的结果/只读核对，不得盲目补发。

停止只通过现有暂停/停止/释放 API、生成器开关和紧急停派栅栏；不得盲目重试请求或直接写 DB。清理失败时保持资源
隔离，转人工处置并将运行标为 `INCOMPLETE/BLOCKED`。

## 12. PASS / FAIL / BLOCKED

- `PASS`：所有 required 断言真实执行，硬阈值全部满足，性能阈值满足，四平面证据齐全且 cleanup 完成。
- `FAIL`：系统可测，但业务状态、幂等、副作用、性能或资源结果违反冻结期望。
- `BLOCKED`：版本、夹具、账号、权限、故障注入、监控或清理能力不足，无法形成可信判断。
- `NOT_RUN`：尚未执行；不得用代码审计、历史任务或单元测试结果替代。

## 13. 开始排期

以下是首版工程估算，W0 冻结接口和规则后重估；不包含等待 test1 解封的自然时间。两个工程角色可让普通拉群与
速拉群/建群营销场景并行，预计 2~3 周形成可跑的 P0 隔离套件和第一轮性能结果。

| 工作包 | 交付物 | 依赖 | 粗估人日 | 出口 |
|---|---|---|---:|---|
| W0 规则与基线冻结 | 状态/回调/UNKNOWN/父子/资源 oracle；#199 replay fixture | 产品、协议、测试共同签字 | 2~3 | 所有 P0 expected 可机械判断 |
| W1 模拟器内核 | World Store、Operation Ledger、Event Scheduler、Fault Controller、独立 Oracle、egress deny | W0；隔离 DB/Kafka/Redis | 4~5 | 正常 mutation 与六类故障切点可控 |
| W2 普通拉群 P0 | 批次 A/B/C 的 Web、Android、混合运行卡和自动报告 | W1 | 4~5 | 正常链、#199 回放、恢复与竞态通过 |
| W3 速拉群/营销 P0 | 批次 D 的建群、联系人、禁言、发送、换号和父汇总 | W1，可与 W2 并行 | 5~7 | 有效群<=1、发送不重做、孤儿=0 |
| W4 压测执行器 | 三业务公开 API open-loop、K 校准、采集器、Tfence、cleanup | W1 后建骨架；W2/W3 fixture 冻结后完成 | 3~5 | `PF-B00-SIM` dry-run、`PF-S01` simulated smoke 与安全停派通过 |
| W5 首轮性能运行 | S0、K 校准、S1、S2A/S2B、S3、Stop/crash；oracle 冻结后故障风暴与 S6 爬坡 | W2~W4；专用性能环境 | 2~4 | 容量、拐点、排空和资源报告 |
| W6 test1 金丝雀 | L0 GO/NO-GO；解封后按 lane/rung 运行 | W5、对应 Logic P0、修复候选、`LOCKDOWN_CLEARED` 和全部真协议门禁 | 1~2 | 每例 T+30m/T+24h 均干净 |

首轮性能顺序固定为 `PF-B00-SIM 5m -> 每业务/协议 K 校准至少 10 任务 -> PF-S01 -> PF-S02A -> PF-S02B ->
PF-S03 -> PF-S05`；只有冲突回调 oracle 冻结后才执行 `PF-S04` 故障风暴，最后执行 `PF-S06` 每级 +20% 爬坡，
候选档再跑 30m。主轮现场约 4~6 小时，故障与爬坡另约 3~6 小时；任一硬门禁失败即停止，不为了跑完表格继续升压。

### 13.1 今天开始的 W0 输出

1. 把 #199 的事件顺序、父子终态、页面陈旧和资源残留固化为可重放 fixture 与独立 expected。
2. 冻结每类动作的业务键、operationId、允许的新 attempt 条件和 mutation 计数方法。
3. 冻结 parent 的 `lifecycle` 与 `outcome` 两套语义，禁止用 `COMPLETED/100%` 表示所有子项成功。
4. 冻结资源的 `release/retain/quarantine` 三种出口；封禁群、关联邀请码和账号风险对象默认 quarantine。
5. 输出模拟器 adapter 契约、事件 DSL、报告 schema 和 egress fail-closed 验收样例。

### 13.2 首批可领取任务（单项不超过 4 小时）

| 任务 ID | 内容 | 预计 | 前置/产物 |
|---|---|---:|---|
| `W0-01` | 将 #199 脱敏时间线转成 replay fixture | 3h | 产出事件序列、初始 world 和 expected |
| `W0-02` | 冻结 parent lifecycle/outcome 与 processed/success/abnormal oracle | 3h | 产品/测试签字 |
| `W0-03` | 定义各动作业务键、operationId、attempt 和四计数账本 schema | 4h | 协议/后端签字 |
| `W0-04` | 定义 release/retain/quarantine 与群/邀请码级联规则 | 3h | 清理和隔离 oracle |
| `W1-01` | 定义 Web/Android simulator adapter 契约及未知配置 fail-closed | 4h | W0-03；契约测试草案 |
| `W1-02` | 实现账号、群、成员、角色、审批、邀请码 World Store | 4h | W1-01；内存仅属于测试 simulator |
| `W1-03` | 实现联系人、ADD/PROMOTE/CREATE/MUTE/LEAVE/SEND mutation | 4h | W1-02；operation ledger |
| `W1-04` | 实现虚拟时钟、重复/乱序/丢失/迟到事件 DSL | 4h | W1-03；固定 seed |
| `W1-05` | 实现独立 oracle、全链导出和 egress 诱捕自检 | 4h | W1-02~04；summary.json |
| `W2-01` | 普通拉群 Web/Android 正常链运行卡 | 4h | W1 完成；`PL-F01/F02` |
| `W2-02` | 混合父汇总与 task->result 全链运行卡 | 4h | W2-01；`PL-F03/T01` |
| `W2-03` | 管理员、邀请码、审批单次恢复运行卡 | 4h | W1-04；批次 B |
| `W2-04` | #199 精确回放、Stop 和迟到回调运行卡 | 4h | W0-01、W1-04；批次 C |
| `W4-01` | 三业务公开 API open-loop 生成器骨架 | 4h | W1-01；只允许隔离环境 |
| `W4-02` | 固定 seed、业务/协议混合和各链 K 校准 | 3h | W4-01 |
| `W4-03` | API/DB/Outbox/Kafka/协议账本采集器 | 4h | W1-05、W4-01 |
| `W4-04` | Tstop/Tfence 急停、排空和本轮 cleanup | 4h | W4-01/03；fail-closed 测试 |

W3 的各速拉群/建群营销场景也按“每个阶段或故障切点一张 <=4h 运行卡”拆分；在 W0 的换号、UNKNOWN 与父汇总
规则签字前不领取对应实现任务。

### 13.3 三套独立门禁

| 门禁 | 必须确认 | 不依赖 |
|---|---|---|
| Logic | UNKNOWN 三分法、冲突结果优先级、审批事实、换号规则、父 `lifecycle/outcome/processed/success/abnormal`、群封禁与邀请码级联、`release/retain/quarantine`；隔离 DB/Kafka/Redis 和 simulator fail-closed | test1 账号、真群、真实动作预算、解封窗口 |
| Perf | 对应 Logic P0 通过；专用环境承担 S4/S5/S6 的资源与网络隔离；正式 SLA/主机预算；最低目标 `task/min`、`execution-or-item/min`、`command/s`、`marketing-target/s` | test1 解封、真实账号或群 |
| Live | 对应 Logic P0 和 W5 通过；`LOCKDOWN_CLEARED`；LIVE_GATE 批准人/范围/有效期；Web/Android 账号、群、白名单联系人；单轮/单账号/当日四级预算和冷却；紧急停派；群删除/归档及协议残留清理责任人 | S6 容量探顶结果达到某个更高档位 |

重复/乱序、worker kill 和租约接管默认只在 simulator/专用环境执行；test1 不因 Live Gate 开放而自动获得故障注入权限。
Logic 门禁冻结后即可进入模拟器实现，不得因 test1 尚未解封而阻断；Perf 与 Live 分别按自己的门禁推进。

### 13.4 首个实现切片（2026-09-03）

已完成 test-only `stateful-protocol-sim` 内核和本地 CLI：

- 仅接受 `sim://local`，其他 endpoint 全部 fail-closed；实现代码没有真实协议网络调用。
- 保存群成员/管理员、加人权限、审批开关、当前邀请码状态、execution 和 parent 聚合。
- 账本按 operationId 记录 dispatch/accept/mutation/result，并按业务键识别跨 operation 的重复副作用。
- 支持正常 execution 收口和 Web+Android 两 execution 的混合父任务聚合。
- 已将 `#199` 脱敏为 `PL-S01C-TASK-199` JSON fixture：7 个准备动作 + 1 个目标拉人动作后投递
  `CHAT_SUSPENDED×5`，输出 8 个唯一 command、8 次 mutation、1 次风险迁移、0 重复 mutation、终态后 0 新命令，
  群和邀请码进入隔离，父 lifecycle=COMPLETED、outcome=FAILED。

本切片已增加本地开放环/并发负载入口，并把 `#199` 的群健康回调路由接到真实生命周期服务与 H2 Mapper 回归。
无节流 100/300/500 execution 三档分别达到 10/30/50 max in-flight；开放环 5/20/50/60 command/s 四档实测速率为
4.996/19.968/49.988/59.853。两组共 955 execution、11,460 command、3,820 次重试，重复 mutation、终态后命令和失败
execution 均为 0，终态完整率 100%。结果见
`docs/operations/2026-09-03-group-task-local-simulator-load-result.md`。

普通拉群 Web、Android、混合正常链另已接入生产 `ProtocolCommandOutboxServiceImpl`、真实 Mapper XML、任务调度
协调器和结果服务：每个 profile 的 11 个唯一 command 全部经 H2 的
`PENDING -> LOCKED -> DISPATCHING -> SENT` 后才接收对应回调，并与 action/call/material 聚合记录一一对应，
父子任务收口；每个 profile 的 12 条业务结果均做即时重复和终态后迟到重放，Outbox 数量与 execution 版本不变；
Android topic 分流和混合 backend 同时存在也已断言。三个 profile 正常收口后 execution 租约、群链接占用键、
拉手账号占用键均已清空，拉手释放时间已写入。

管理员资源 H2 场景已验证：持续不足 5 个恢复周期状态稳定且 0 command；补入唯一管理员后只恢复一次并创建 1 条
JOIN Outbox，`PL-R01/R02` PASS。但 `PL-R06` 复现 P0：原 JOIN 回调为 `TIMEOUT/UNKNOWN` 且没有 groupJid 时，
下一次复核绕过 Outbox 直接调用 `GroupJoinPort.join`，存在重复踩链接及 command 链断裂风险。

`PL-A01` 也已复现 P0：JOIN 回调能正确暂停到 `WAIT_RESOURCE/APPROVAL`，但 APPROVAL 不在调度器的
自动恢复领取集合中；推进 5 个未来周期仍是 `claimed=0`，没有成员事实复核入口。

`PL-I01/I02` 在 Web/Android 均复现 P0：`INVITE_REVOKED` 回调携带已知 JID 时，即使邀请链接服务已有新码，
恢复分支仍只查成员而不刷新邀请码，并在 `WAIT_RESOURCE ↔ EXECUTING` 间反复跳转。

同 commandId 的 JOIN SUCCESS 后再到 3 次迟到失败均不能覆盖成功事实，该乱序子集 PASS。`PL-S04` 则复现 P0：
execution 终态后首个 SENT 成功回调会因 execution CAS 失败而整体回滚、抛异常，协议事实不可审计。
聚焦回归共 33/33 通过，其中 PL-R06、PL-A01、PL-I01/I02 和 PL-S04 均为锁定实际缺陷的 characterization test，
不代表业务验收通过。

上述结果仍未连接 Armada 公开 API、部署态 scheduler、真实 Kafka producer/broker/consumer，也未采集服务资源，因此
只能增加 `H2_OUTBOX_CHAIN_PASS`，不能签署 Armada 容量；`PF-S01~S06` 和全部 Live 用例仍未通过。下一切片先实现
simulator adapter 契约，再接入速拉群/建群营销与隔离 Armada 全链。
