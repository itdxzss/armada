# 超链任务上线验收清单

> 版本：`v1.0`
> 当前运行：`HL-20260903-L0-001`、`HL-20260903-TEST1-001`、`HL-20260903-ANDROID-BIZ-001`
> 当前结论：`ANDROID_SINGLE_CANARY_PASS / ENVIRONMENT_GATES_FAIL / LOAD_BLOCKED`
> 使用方式：每个勾选项必须绑定证据路径或查询摘要；required 项不得以 `SKIPPED` 放行。

## 1. 签署页

| 项目 | 填写值 |
|---|---|
| `capacityContractVersion` | `TBD` |
| 候选 manifest / 四仓 full SHA | 已记录于测试方案；部署后需重新核对 |
| 测试环境 | `test1 / 第一套环境`；L1 隔离 Stub 环境仍为 `TBD` |
| 测试 runId | `HL-20260903-ANDROID-BIZ-001`；前置检查 `HL-20260903-TEST1-001`；远端 UI smoke `20260903T062332Z-25867866` |
| 业务负责人 | `TBD` |
| 产品负责人 | `TBD` |
| 技术负责人 | `TBD` |
| 运维负责人 | `TBD` |
| 财务/钱包负责人 | `TBD` |
| 风控/账号负责人 | `TBD` |
| 最终结论 | `PASS / FAIL / BLOCKED` |

## 2. G0：正式业务量冻结

以下全部为 required；任一未完成，L1 只能做工具 smoke，不能形成容量结论，L3/L4 不得开始。

- [ ] 单任务去重号码量上限已冻结。
- [ ] 单租户单日、全局单日号码量已分别冻结。
- [ ] 单租户与全局并发运行任务数已分别冻结。
- [ ] Web、Android、HIGH 蓝标的可用账号池规模与测试租约已冻结。
- [ ] 单账号每任务、每日、生命周期发送上限已冻结；`0=不限` 是否允许已明确。
- [ ] 发送间隔上下限、分布、时区窗口与风控停止规则已冻结。
- [ ] Web/Android 占比已冻结。
- [ ] TEXT/LINK/IMAGE/LINK_CARD/BUTTON_CARD 占比已冻结。
- [ ] 短链开关占比与点击/UV 生成模型已冻结。
- [ ] HIGH 是全量强制、指定任务强制还是不强制已签字。
- [ ] 无账号等待时限、终态、错误码、告警、退款/释放行为已签字。
- [ ] 计费人数、计费时点、国家价格、失败/UNKNOWN/取消/重试口径已签字。
- [ ] 1× 峰值联合向量、2× 冲击持续时间、积压恢复 SLO、soak 时长已冻结。
- [ ] p95/p99、错误率、backlog、资源水位与普通队列等待 SLO 已冻结。
- [ ] task/command 最长未决期、幂等 tombstone/ACK 关联保留期、归档/清理和重建策略已冻结。
- [x] 正式峰值使用稳定容量 60%～70%、保留 30%～40% 余量的原则已冻结。
- [ ] 每个数量明确是唯一 recipient、逻辑命令、物理提交还是回调事件；禁止混用分母。
- [ ] 单日统计的时区、自然日边界和重试计数方式已冻结。
- [ ] “并发任务”分别冻结运行中任务数与正在派发任务数。
- [ ] 各协议/消息比例合计 100%，并冻结账号掉线、限流和 ACK/回调延迟模型。
- [ ] 60%～70% 范围内的具体利用率系数 `U` 在查看压测结果前签署，硬上限不得超过 70%。
- [ ] 任一冻结参数、拓扑或候选制品变化时，旧容量结论自动失效。

证据：`capacity-contract-<version>.md` 或等价签署记录：`TBD`。

## 3. G1：候选、环境与安全前置

- [x] 本地候选四仓分支和 full SHA 已记录。
- [ ] 部署环境四份制品 digest/full SHA 与候选 manifest 完全一致。
- [ ] 环境为隔离测试环境，数据库、Redis、Kafka、对象存储、短链域名与生产隔离。
- [ ] L1 协议 Stub 明确阻断所有外部 WhatsApp 网络出口。
- [x] Android 第一阶段回环已在本地证明真实 Noise XX、双向 Noise 加解密和密文 ACK；发送函数没有短路成功。
- [x] Android 回环支持按账号 User 的 SHA-256 精确选择；共享节点未命中账号保持生产链路，缺少或错误配置失败关闭。
- [x] test1 使用受控账号证明业务消息真实执行 Signal 出站加密，并由回环加密返回 Server ACK。
- [x] Web 独立 crypto harness 已证明 synthetic Signal 对端解密、双向 Noise 和密文 ACK；不创建 WASocket、不读取真实账号凭据。
- [ ] Web/Android 全链 DELIVERED/READ、真实账号 inbound Signal 解密仍属后续能力；对应高级用例保持 BLOCKED。
- [ ] 云控账号协议状态复制到 runId 隔离命名空间，正式 auth/Signal/app-state 无任何写入。
- [ ] DNS、连接目标和 egress 旁路审计证明 WhatsApp/Meta 域名及公网 443 出口为 0。
- [x] 钱包 Stub 与真实钱包配置可明确区分；本轮数据库和日志均证明为 `ZERO_TEST`。
- [ ] `TENANT_A`、`TENANT_B`、重复局部 ID、账号、recipient、价格桶和消息 mix 夹具准备完毕。
- [ ] 所有夹具带 runId，运行前快照和运行后 cleanup 均可审计。
- [ ] 真号测试发送方、收件人 allowlist 与账号独占租约经过书面授权。
- [ ] 服务端硬限制、最长运行时间和一键 kill switch 已演练。
- [x] 证据中手机号、正文、Token、二维码、PEM 和账号凭据均脱敏。

### 3.1 test1 实际环境预检（2026-09-03）

- [x] 已连接 `test1 / 第一套环境`，后端、Nginx 和公开入口在线；未认证 API 正确返回 401。
- [x] 远端 `runner-deep-check` 通过：前端、后端、Web 协议 readyz、Android coordinator 及 3 个在线节点通过固定检查。
- [x] 远端持久化 UI smoke 通过，runId=`20260903T062332Z-25867866`；5 个既有路由访问成功、无业务写请求、无应用控制台错误。
- [x] 使用具备菜单权限的登录态通过 browser skill 进入 `#/hyperlink/tasks`；列表展示 9 条任务，汇总为发送 28,553、单钩 371、双钩 340，列表和 create-context 两个 GET API 均为 HTTP 200。
- [x] test1 数据库只读快照中，task/runtime、recipient/task、round/task、usage/task、billing/task 的跨租户不一致均为 0；重复 command、重复 ACK identity、计数投影漂移和终态账务差额均为 0。
- [x] 授权后创建并完成任务 `13`：1 个 recipient、1 个 Android 账号、1 次 dispatch、1 个 Server ACK、0 retry、0 failure；未触达 WhatsApp。
- [x] 任务 `13` 的 runtime、recipient、round、account usage、account stat、outbox 和 `ZERO_TEST` 账务已完成数据库只读对账。
- [ ] 账号维度统计正式接口可用：当前查询使用 MySQL 保留字 `usage` 作为别名，页面显示“系统繁忙”。
- [ ] 小时营销统计可用：当前运行 schema 不含 SQL 引用的 `hyperlink_task.task_type`。
- [ ] Kafka DLT 可用：`protocol.message.events.v1.DLT` 与 `protocol.account.contact-sync.events.v1.DLT` 当前不存在，旧毒消息持续 seek/retry。
- [ ] 四服务运行制品与候选 manifest 可追溯一致：当前运行清单证据早于 9 月 1 日容器重建，不能用于版本验收。
- [ ] 真实钱包模式：test1 实际配置为 `ZERO_TEST`，只能证明零计费模式下金额为 0。
- [ ] Web/HIGH 真号准入：当前可用池中 Web 在线数为 0，eligible HIGH 数为 0。
- [ ] 专用自动 smoke 账号具备超链权限：该账号访问超链接口时得到业务码 `50000`；具备权限的人工登录账号访问正常，需补专用 RBAC 并把权限拒绝收敛为明确 403 类错误。

以上包含一次 Android 单账号加密回环业务 canary，仍不替代 L1 容量、真实 WhatsApp L3 或 L4 蓝标结论。

## 4. G2：L0 本地正确性

### 4.1 当前候选已执行

- [x] Armada 超链聚焦套件：324 tests，0 failure，0 error，0 skipped。
- [x] 前端任务定向套件：85 tests，0 fail，使用 HTTP double。
- [x] 前端超链全依赖套件：132 tests，0 fail，使用 HTTP double。
- [x] 前端 `tsc --noEmit`：exit 0。
- [x] 前端 `vue-tsc --noEmit --skipLibCheck`：exit 0。
- [ ] Armada 在正式 Java 17 CI 上复跑并通过；当前本机证据来自 JBR 25 + Byte Buddy experimental workaround。
- [x] Android 7 个相关包完整 `go test -race` 通过：armada、service app/node/nodes、external、coordinator、fleet；使用临时 Go 1.25.1/模块缓存与本机 test double。
- [x] Android 全仓 `go vet ./...` 与 `go build ./...` 通过。
- [ ] Android 全仓 `go test ./...` 绿灯：当前本机有 7 个 deployment contract 断言与 8 个 `pkg/noise` 断言失败；需修复或形成有证据的既有问题处置。
- [ ] Android 在正式 CI 执行全仓 `go test -race ./...` 并复核上述失败。
- [x] Web 协议发送/owner/幂等/ACK 聚焦子集：161 tests / 11 suites，0 fail；从同候选源码临时副本运行，无真实协议动作。
- [x] Web 协议全量单测：1,324 tests / 114 suites，0 fail；`tsc --noEmit` 与 build 均 exit 0。
- [x] Web 协议在候选仓按正式 `package-lock.json` 执行 `npm ci` 和 `npm test`；维护 CLI 9 tests、crypto loopback 3 tests 均通过。
- [ ] 真 MySQL/InnoDB 并发门禁测试通过，不能只用 H2 结论替代。

### 4.2 状态机与并发

- [ ] START/PAUSE/RESUME/STOP 的合法动作矩阵有正反例，非法转换不产生副作用。
- [ ] 重复点击、HTTP 重试、调度器重入和消息重放复用稳定 operation/command key。
- [ ] 同一个 `(tenantId, taskId, recipientId)` 最多形成一个逻辑发送。
- [ ] 同一个 `commandId` 最多一次物理提交；UNKNOWN 恢复不得创建第二次物理发送。
- [ ] recipient 状态只单调前进；重复、倒序、早到 ACK 不回退、不重复计数。
- [ ] task、round、recipient、account usage 的锁顺序固定，无死锁、超卖或负计数。
- [ ] 单账号跨任务在途数不超过冻结值和硬门禁；Redis holder 丢失时 DB 仍能兜底。
- [ ] 进程重启、事务回滚、outbox 重放和调度器并发不丢命令。

### 4.3 租户与权限

- [ ] 所有 API、Mapper、缓存 key、事件、outbox、账本、导出和短链事实均带正确租户边界。
- [ ] `TENANT_A` 使用 `TENANT_B` 的 task/recipient/account/job/resource ID 均失败关闭。
- [ ] 相同局部 ID 在两租户并发运行时，发送、ACK、点击和计费完全隔离。
- [ ] 前端隐藏按钮之外，后端权限仍独立拦截 view/create/edit/action/export/sensitive。

### 4.4 创建、复制、编辑与报价门禁

- [ ] create、copy、edit、view 四种模式均有真实行为测试，不以读取源码的正则断言替代。
- [ ] 所有可能启用或扩大消费的 create/copy/edit 路径都必须重新报价、携带有效 quoteToken 并完成冻结核对时长。
- [ ] copy 调用 create API 时，空/过期/配置不匹配 quoteToken 由后端 fail-closed，且不落 recipient、outbox 或冻结余额。
- [ ] 准备状态轮询覆盖 READY、FAILED、超时、关闭抽屉、重试和迟到响应；迟到结果不得污染已关闭/新打开的编辑器。
- [ ] 真号环境新任务默认 disabled；服务端强制安全间隔、每任务/每账号硬上限，不能依赖前端默认值。

## 5. G3：可观测性与六段漏斗

### 5.1 权威事实

- [ ] C0 命令生成/业务 outbox 有独立幂等事实。
- [ ] C1 broker 接受有独立事实。
- [ ] C2 Web/Android 协议 consumer 接收有独立事实。
- [ ] C3 物理 socket/API 实际提交有独立事实；不得以本地 enqueue 代替。
- [ ] C4 Server ACK 有独立事实。
- [ ] C5 DELIVERED 有独立事实。
- [ ] C6 READ 有独立事实。
- [ ] C7 click PV/UV 有独立事实。
- [ ] 每一级能用 runId、tenantId、taskId、recipientId、commandId、protocolMessageId 关联。
- [ ] 重放次数与唯一事实次数可同时查询，方便证明幂等而非隐藏重试。

当前阻断：业务后端在本地 adapter/outbox 接受时写 `submitted_at`；尚无 C2 与 C3 的独立持久事实，不能据此证明“协议收到”和“实际提交”。

### 5.2 守恒核对

- [ ] C0 至 C1 的差集全部能解释为明确取消/DEAD，没有无故丢失。
- [ ] `unique(C3.commandId) <= unique(C2.commandId) <= unique(C1.commandId)`。
- [ ] `unique(C4.protocolMessageId) <= unique(C3.protocolMessageId)`。
- [ ] READ、DELIVERED、ACK 的包含关系符合冻结平台语义，乱序后仍可收敛。
- [ ] task runtime 聚合与 recipient 权威事实逐项一致。
- [ ] round 聚合与 recipient 权威事实逐项一致。
- [ ] account stat 聚合与 recipient 权威事实逐项一致。
- [ ] reconciliation 前记录漂移，执行后差额必须为 0，且不掩盖根因。
- [ ] 前端分别展示冻结口径；“发送总数”不得使用 recipientTotal 冒充 sendTotal。
- [ ] click 作为旁路归因单独核对；不要求 click 是 READ 子集，只要求 `clickPV >= clickUV`，且 clickUV 不超过持有有效短链的唯一 recipient 数。

## 6. G4：账号选择、HIGH 与队列公平

- [ ] PRIVATE 能力白名单明确配置，空白名单能告警且不会静默长期运行。
- [ ] HIGH 认证事实来源、刷新频率、未知值行为和快照时点明确。
- [ ] 若强制 HIGH，候选 SQL、运行快照、派发前复核、详情页和审计证据均能证明未选普通账号；UNKNOWN 必须 fail-closed，`accountType=2` 不等于 HIGH。
- [ ] HIGH 只有在签署为“不强制”时才允许标为 N/A，并附签署证据；不得因字段缺失自动降为 N/A。
- [ ] 账号状态在试算与启用间变化时，启用前重检且结果可解释。
- [ ] `maxUseAccounts`、`maxExecutingAccounts`、`maxSendPerAccount` 与协议容量边界值均验证。
- [ ] Web/Android 比例和账号池耗尽时，系统行为符合冻结合同。
- [ ] INSTANT 零账号立即失败行为符合合同。
- [ ] ROLLING/CYCLE 零账号在冻结等待时限后进入明确终态，不长期 RUNNING。
- [ ] Web owner 存在、迁移、暂失、永久缺失分别有确定性结果/补偿。
- [ ] Android owner 缺失不会仅提交 offset 而丢失 send result。
- [ ] 超链满载时普通消息队列 p95/p99 等待仍满足 SLO，无无限饥饿。
- [ ] 普通消息满载时超链任务仍按权重前进，不通过抢占破坏普通业务。
- [ ] 热账号持续灌入时冷账号/其他租户能在冻结上界内获得服务。

## 7. G5：协议 Stub、ACK 与故障恢复

Web 与 Android 分别执行以下矩阵，不能以一端通过代表另一端：

- [ ] TEXT、LINK、IMAGE、LINK_CARD、BUTTON_CARD 的 wire 与物理提交映射正确。
- [ ] short link 开/关、图片下载失败、非法 payload、未注册号码均有确定性结果。
- [ ] consumer 重复投递同一 commandId，只产生一次 C3。
- [ ] consumer 在 C3 前崩溃，可安全重试且不丢；在 C3 后结果前崩溃，不盲目二次提交。
- [ ] 关联必须在 socket write 前持久化；早到 Server ACK 不丢失。
- [ ] send result 发布失败时输入消息不被错误确认，或有等价可靠 outbox。
- [ ] Server ACK 成功、超时、迟到、重复、乱序与无关联全部覆盖。
- [ ] DELIVERED/READ 成功、迟到、重复、乱序与早到全部覆盖。
- [ ] command tombstone 与 ACK 关联在冻结保留期边界前后重放均符合合同；清理后仍有明确拒绝或可重建策略。
- [ ] Web/Android owner 缺失返回可收敛结果；禁止跨协议猜测或换账号重发。
- [ ] Kafka 分区、consumer rebalance、broker 短断、Redis 短断、DB failover 后恢复且零不变量不破坏。
- [ ] Android 拒绝负数/越界 `sendIntervalMs`，private prepared-send one-shot 与 ACK-before-write 有节点级测试。

## 8. G6：计费与账务

- [ ] CAP-14 正式计费口径已映射到唯一数据库/事件事实，不能依赖展示字段猜测。
- [ ] 真实钱包适配器存在并在测试环境以真实沙箱/获准模式运行；不是 ZERO_TEST 或 UNAVAILABLE。
- [ ] 报价国家桶、单价、recipient 数和总额可重复计算。
- [ ] 冻结、调整、结算、释放的 operation key 稳定且重放幂等。
- [ ] 钱包成功、不足、超时、结果未知、迟到成功、重复回调均有测试。
- [ ] 未提交、明确失败、UNKNOWN、停止、取消、重试和跨国价格分别按冻结口径处理。
- [ ] 任一时点系统账本、钱包账单、recipient 权威事实的计费人数差额为 0。
- [ ] 任一时点金额差额为 0，精度和舍入规则一致。
- [ ] 任务有 SENDING/in-flight 时不得提前结算；最终清理后不残留冻结金额。

## 9. G7：L1 隔离压测

- [ ] `PERF-HL-001` 0.1× smoke 通过，压测器与业务指标能够互相核对。
- [ ] `PERF-HL-002` 1× steady 按冻结时长稳定运行。
- [ ] `PERF-HL-003` 2× shock 后在恢复 SLO 内清空增量积压。
- [ ] `PERF-HL-004` soak 通过，无资源趋势泄漏和计数漂移。
- [ ] 并发任务、跨租户、账号池耗尽、普通队列公平、协议混合、媒体/短链混合用例通过。
- [ ] Kafka/Redis/DB/协议/钱包故障注入与重启重放用例通过。
- [ ] 逐级漏斗差集、重复集合、账务差额与聚合差额全部为 0。
- [ ] 容量搜索只在 Stub 环境完成，找到最高稳定联合负载 `Cstable`。
- [ ] `Pprod <= 0.6～0.7 × Cstable`，最弱资源仍有 30%～40% 可用余量。
- [ ] 原始时序、负载器结果、数据库快照、Kafka lag、资源曲线、核对 SQL 与 cleanup 证据齐全。

## 10. G8：L3 普通真号 E2E

- [ ] L0、L1、L2 required 项全部 PASS，未借真号探索未知容量或协议语义。
- [ ] 普通测试账号和收件人白名单、窗口、租约、硬上限与 kill switch 已复核。
- [ ] Web 与 Android 各五类消息按“先 1 条”执行，短链开/关均有样本。
- [ ] 每条消息具备 C0～C7 中平台实际可产生的完整脱敏证据。
- [ ] 同一 commandId 重放不产生第二条真实消息。
- [ ] 任务停止、在途收口、余额释放、账号租约释放和测试数据清理完成。
- [ ] 无账号限制、触达限制、限流或非预期账号状态变化。

## 11. G9：L4 HIGH 蓝标 canary

- [ ] HIGH 强制筛选在 SQL、任务快照和实际账号证据上均已验证。
- [ ] 使用独占蓝标测试账号、获准收件人和真实钱包模式。
- [ ] 负载固定为最小业务样本，不做 ramp、阶梯增压、2× shock 或容量寻找。
- [ ] Web/Android、媒体和短链只按冻结 canary 样本执行。
- [ ] 任一风险事件、漏斗断点、重复、账务差异或 owner 异常立即停止。
- [ ] canary 结束后完成任务、在途、账务、账号和数据清理核对。

## 12. 零容忍验收表

| 指标 | 目标 | 实测 | 证据 | 结论 |
|---|---:|---:|---|---|
| 丢命令 | 0 | `0（n=1）` | task 13：1 recipient / 1 command / 1 outbox / 1 ACK | `PASS_SINGLE_CANARY` |
| 逻辑重复发送 | 0 | `0（n=1）` | recipient 仅 1 行，dispatch_attempt=1 | `PASS_SINGLE_CANARY` |
| command 重复物理提交 | 0 | `0（n=1）` | outbox 仅 1 行，retry_count=0；回环 ACK=1 | `PASS_SINGLE_CANARY` |
| 跨租户串数 | 0 | `TBD` | `TBD` | `NOT_RUN` |
| 计费人数差额 | 0 | `0（n=1）` | settled_send_count=1，recipient success=1 | `PASS_ZERO_TEST_ONLY` |
| 计费金额差额 | 0 | `0` | 报价/预约/结算/释放均为 0 USD | `PASS_ZERO_TEST_ONLY` |
| task 聚合漂移 | 0 | `0（n=1）` | runtime 与 recipient 一致 | `PASS_SINGLE_CANARY` |
| round 聚合漂移 | 0 | `0（n=1）` | round 与 recipient 一致 | `PASS_SINGLE_CANARY` |
| account 聚合漂移 | 0 | `DB=0；API不可验收` | account_stat 行正确；查询 SQL 报错 | `FAIL_INTERFACE` |
| 非白名单真实触达 | 0 | `0` | 回环连接 1；容器无已建立 443 连接 | `PASS_SINGLE_CANARY` |

## 13. 当前阻断清单

| 级别 | 定义 | 处置 |
|---|---|---|
| `P0-SAFETY` | 已发生重复真实发送、非白名单触达、跨租户串数、账务差异、HIGH 误选或 kill switch 失效 | 立即停测并保留现场，禁止发布 |
| `P1-RELEASE` | 合同、实现能力或证据缺失，尚不能证明可上线 | 可继续无副作用排障，不得形成容量结论、进入真号或发布 |
| `P2-SCOPE` | 只影响可被服务端硬关闭的协议/消息类型/功能 | 重新冻结缩小后的发布范围并签字后，其他范围才可继续 |
| `P3-FOLLOWUP` | 不影响正确性且已有权威替代观测面的体验问题 | 指定 owner 和期限；无权威替代面则升级 P1 |

| 优先级 | ID | 阻断 | 关闭标准 |
|---|---|---|---|
| P1-RELEASE | `BLK-01` | CAP-01～CAP-19、CAP-21 尚未冻结 | 形成有版本的签署合同 |
| P1-RELEASE | `BLK-02` | C2 协议接收、C3 实际提交缺独立权威事实 | 两端均可查询、可关联、可重建并有自动核对 |
| P1-RELEASE | `BLK-03` | HIGH 要求未签署；若要求 HIGH，当前上游选号无法强制 | 签署强制范围；如强制则完成合同、SQL、派发前复核、快照和测试 |
| P1-RELEASE | `BLK-04` | 正式真实钱包适配器不存在 | 真实模式及成功/失败/未知/幂等验收通过 |
| P1-RELEASE | `BLK-05` | Web/Android owner 缺失可能不产出收敛结果 | 明确失败或可靠恢复路径通过 L2 |
| P1-RELEASE | `BLK-06` | ROLLING/CYCLE 无账号终态不明确 | 合同冻结并自动终止/告警/退费验证 |
| P1-RELEASE | `BLK-07` | 普通队列饥饿没有 SLO 与测试 | 公平调度证据和压测通过 |
| P1-RELEASE | `BLK-08` | 前端指标展示不能承载权威漏斗 | 修复展示或交付同等权威观测面 |
| P1-RELEASE | `BLK-09` | Android 全仓仍有 15 个既存失败断言 | 修复/处置 Android 全仓失败，固定发布工具链并通过 CI |
| P1-RELEASE | `BLK-10` | 幂等 tombstone/ACK 关联保留策略未冻结 | CAP-21 签署，保留期边界与 soak 通过 |
| P1-RELEASE | `BLK-11` | copy/edit 报价与 7 秒复核合同未形成行为证据 | 后端 fail-closed 与真实 UI 行为测试通过 |
| P1-RELEASE | `BLK-12` | Web crypto canary和 Android test1 单条业务回环已通过；Android 1000 条和两端高级回执尚未完成 | Android 1000 条校准通过；再补全链 Web/Android 高级回执；真实 WhatsApp egress 为 0 |
| P1-RELEASE | `BLK-13` | 两个协议事件 DLT topic 不存在，毒消息持续 seek/retry | 创建并验证 DLT；毒消息成功隔离；consumer lag 恢复且不再循环 |
| P1-RELEASE | `BLK-14` | 账号维度统计 SQL 使用 MySQL 保留字别名 `usage` | 修复别名，接口和 UI 对 task 13 返回 1 条正确统计 |
| P1-RELEASE | `BLK-15` | 小时营销统计 SQL 与 test1 schema 漂移，`task.task_type` 不存在 | 对齐 SQL/schema，连续两个统计周期无错误且结果可对账 |
| P1-RELEASE | `BLK-16` | 单钩 UI 文案把 Server ACK 描述成发送到对方手机 | 文案改为 Server ACK；DELIVERED/READ 保持独立口径 |

P0 与 P1 都阻断上线。当前表记录的是尚缺能力/证据的 P1；若测试实际观测到重复、串租户、错账、HIGH 误选或越过安全信封，立即升级为 P0。零容忍项不得降级为 P2。

## 14. 本轮执行记录

| runId | 日期 | 层级 | 范围 | 结果 | 备注 |
|---|---|---|---|---|---|
| `HL-20260903-L0-001` | 2026-09-03 | L0 | Armada 业务后端聚焦 | `PASS 324/324` | JBR 25 workaround；待 Java 17 CI 复跑 |
| `HL-20260903-L0-001` | 2026-09-03 | L0 | 前端任务定向 | `PASS 85/85` | alias + HTTP double |
| `HL-20260903-L0-001` | 2026-09-03 | L0 | 前端超链全依赖 | `PASS 132/132` | alias + HTTP double |
| `HL-20260903-L0-001` | 2026-09-03 | L0 | 前端 TypeScript | `PASS 2/2 commands` | 均 exit 0 |
| `HL-20260903-L0-001` | 2026-09-03 | L0/L2 | Android 7 个相关包完整 race 回归 | `PASS 7/7 packages` | 临时 Go 1.25.1 + go.sum 模块缓存；待正式全仓 CI |
| `HL-20260903-L0-001` | 2026-09-03 | L0 | Android 全仓 vet / build | `PASS` | 两条命令均 exit 0 |
| `HL-20260903-L0-001` | 2026-09-03 | L0 | Android 全仓 test | `FAIL 15 assertions` | 7 deployment contract + 8 pkg/noise；非超链相关包，但总门禁未绿 |
| `HL-20260903-L0-001` | 2026-09-03 | L2 | Web 协议发送/owner/幂等/ACK 聚焦 | `PASS 161/161` | 同源临时副本 + 锁定依赖转换 |
| `HL-20260903-L0-001` | 2026-09-03 | L2 | Web 协议全量单测 / typecheck / build | `PASS 1324/1324` | 候选仓正式 npm lock 布局；两条编译命令 exit 0 |
| `HL-20260903-WEB-CRYPTO-001` | 2026-09-03 | L2 crypto | test1 Web Signal+Noise 回环 | `PASS 1000/1000` | 8 lanes；0 error；约 3236 msg/s；峰值 RSS 152,834,048 bytes |
| `HL-20260903-TEST1-001` | 2026-09-03 | test1 preflight | 真实服务深检 | `PASS` | `runner-deep-check`：前后端、Web readyz、Android coordinator 与 3 节点通过 |
| `20260903T062332Z-25867866` | 2026-09-03 | test1 UI smoke | 5 个既有路由、写阻断 | `PASS` | exit 0；console errors=[]；blocked writes=[] |
| `HL-20260903-TEST1-001` | 2026-09-03 | test1 browser | 超链任务列表与创建上下文只读 GET | `PASS` | browser skill 实际进入页面；9 条任务；两个 API HTTP 200 |
| `HL-20260903-TEST1-001` | 2026-09-03 | test1 DB snapshot | 租户隔离、幂等、计数与零计费核对 | `PASS_SNAPSHOT` | 已查不变量均为 0；只代表本次静态快照 |
| `HL-20260903-ANDROID-BIZ-001` | 2026-09-03 | test1 Android 业务 canary | 1 条超链任务完整链路与加密回环 ACK | `PASS_WITH_LIMITATION` | 1 command / 1 outbox / 1 ACK / 1 success / 0 retry / 0 failure；无真实 WhatsApp 触达 |
| `HL-20260903-ANDROID-BIZ-001` | 2026-09-03 | test1 计费 | `ZERO_TEST` 预约、结算、释放 | `PASS_ZERO_TEST_ONLY` | settled_send_count=1；全部金额 0；不能替代真实钱包 |
| `HL-20260903-ANDROID-BIZ-001` | 2026-09-03 | test1 环境门禁 | DLT、账号统计、小时统计 | `FAIL` | 见 BLK-13～BLK-15；禁止扩大到 1000 条 |

当前发布判定：`ANDROID_SINGLE_CANARY_PASS / ENVIRONMENT_GATES_FAIL / LOAD_BLOCKED`。单条 Android 业务加密回环已经闭环，但 DLT 和两处统计 SQL 未通过，上线、1000 条校准、容量、真实钱包和真号验收仍被阻断。完整现场结论见 [test1 真实环境执行结果](./2026-09-03-hyperlink-task-test1-live-execution-result.md)。
