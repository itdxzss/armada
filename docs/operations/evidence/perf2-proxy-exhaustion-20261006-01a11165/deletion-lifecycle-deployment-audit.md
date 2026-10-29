# 第二套环境注销与上下线：部署及剩余缺陷审计

审计日期：2026-10-06；运行制品与数据库快照为 14:28 UTC（北京时间 22:28）附近，历史日志核对到 14:29 UTC。用户要求“先分析”，本轮只读查询远端、检查代码并运行本地聚焦测试；未修改业务代码、账号数据、配置或部署。

## 结论

三项既有补丁都已经在 perf2 生效：后端批量生命周期拒绝按账号隔离、前端按真实受理结果提示、Android 登录准备错误不再统一转换成代理失败。原先 1001 → PROXY_FAILED → 换代理重登的直接路径已修复。

但注销生命周期仍没有处理完整，不能承诺以后注销账号不会影响上下线。已确定的缺口包括注销完成后旧 NORMAL 状态残留、结束任务的 RESERVED 预留不释放、受保护账号手动下线被拦、协议在注销未知阶段仍可能回报旧在线/下线成功。异步登录与并发注销之间还存在超时转 302 的风险，本轮未证明这条竞态在现场发生。

这三个已移除账号的当前止损有效：1038、1736、2755 均已软删除、期望离线；补丁发布后没有它们的新上线命令或失败诊断。当前未软删除账号中，命中 account_creator_deletion 身份门禁的数量为 0。这是此刻数据状态，不代表生命周期缺陷已修。

## 运行产物验证

| 层 | 运行证据 | 判断 |
|---|---|---|
| 后端 | 容器启动 13:12:52.961 UTC；JAR SHA256 `3c072c843299ff1ba13a539bc64db7c391a8f6c4e3ab78b78117a6dbd0670939` | 批量隔离修复已部署 |
| 前端 | 实际首页引用 `index-CSpkRA-j.js`，该文件引用账号 chunk `index-cF17f5C8.js`；三文件逐一与本地修复产物哈希一致 | 错误/部分受理提示已部署 |
| Android | 容器启动 13:54:53.074 UTC；运行 `/proc/1/exe` 与 `/app/whatsapp-server` 均 SHA256 `2f9b0943ddeeac87dbab7a5dac86e177ca3866acbf3cf1e2ada52f48216ca4be` | 注销准备错误 1011 分类补丁已部署 |

后端从运行容器 JAR 读取的以下 class 与当前本地 target/classes 的 SHA256 逐一相同：AccountBatchLifecycleServiceImpl、AccountOnlineCommandServiceImpl、ProtocolCommandOutboxServiceImpl、ProtocolAccountCommandRejectedException、AccountCreatorDeletionServiceImpl、PullTaskStandardLifecycleServiceImpl。ProtocolCommandOutboxMapper、AccountMapper、AccountCreatorDeletionMapper 三个 XML 同时与当前源码一致。因此既能确认隔离补丁存在，也能确认下文指出的旧生命周期 SQL 仍在运行。

前端 SHA256：

- 首页：`2d4afdd43afc9e23038e62b8323a5d0e5164a430a3c7121147e4fd6aa575d80a`。
- 主入口：`02974ec8afb58aead7250cee67679dc7b1acdce72a8d41146346445f21a531ce`。
- 账号 chunk：`23807649a822d96bb40cd32523e0337471b96707e190c34b9fe62d3dccc75134`；包含 accepted===0、部分受理、未受理、batchErrors，不含旧“下线请求已提交”。

Android 镜像为 `sha256:cb33c778ce6f3d8d2b7abb89334df54307b2c9b673d6fa5e62fda1e4edf0fc59`，与前次发布记录一致；login.go、vo.go、executor.go 的远端源码哈希与本地补丁一致。两端容器 restartCount 均为 0。本次核验的是运行产物及关键类，不把尚未提交的工作区补丁虚称为已推送 Git 提交。

## 已修行为的真实日志证据

- 13:25:22 UTC（21:25）：下线 282 个账号，受限 1 个，剩余 281 个全部受理，最终 accepted=281/failed=1。相同情况在 13:39 和 13:47 各出现一次。这证明隔离已在现场执行，不只是本地单测。
- 13:56:00 UTC：单独操作被锁账号，仍 accepted=0/failed=1。隔离补丁只保护其余账号，没有解除该账号自身的门禁。
- 14:03:25 UTC：批量上线 requested=280、accepted=264、skipped=16、failed=0。受理表示命令入队，不等于所有账号最终成功在线。
- 13:54:53 发布后至 14:28:18 数据库快照：没有 raw_code=1001/1011 的失败诊断；1038、1736、2755 没有新命令或诊断。另有 16 条 raw_code=302 的 PROXY_FAILED；仅凭代码 302 不能判定每条真实原因，更不能将它们作为注销竞态已经发生的证据。
- 补查协议发布后日志的脱敏样本：账号 2580、2142、2560 的 PROXY_FAILED 附近为 quick reconnect/read_error；2561、2133、2081 附近为 heartbeat_timeout。它们属于连接/快速重连路径的观测，不能据此认定所有 302 都是代理自身损坏。14:29 又观察到账号 2701 的 heartbeat_timeout，晚于上面的数据库快照。

## 已部署代码仍有的缺口

### 1. 注销完成仍可能留下“正常”，并继续挡住系统删除

[AccountCreatorDeletionServiceImpl.java:125](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountCreatorDeletionServiceImpl.java:125) 完成注销后调用 markOffline；[AccountCreatorDeletionMapper.xml:114](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountCreatorDeletionMapper.xml:114) 仅修改登录状态、期望状态、来源和失效时间，没有更新 account_state。旧 NORMAL 会保留。

后续事件也不能自动补救：[AccountStateEventServiceImpl.java:133](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountStateEventServiceImpl.java:133) 的账号查找排除 DELETING/DELETED，收到这类账号事件会按不存在跳过。前端没有注销状态字段，正常标签和删除限制仍依赖旧 account_state。

### 2. 结束任务后预留不释放，手动停止也被当作禁止操作

[PullTaskStandardLifecycleServiceImpl.java:136](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardLifecycleServiceImpl.java:136) 结束任务只释放拉手，没有处理创建者 RESERVED；结束单个执行也存在同类缺口。[ProtocolCommandOutboxMapper.xml:419](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/platform/protocol/ProtocolCommandOutboxMapper.xml:419) 不看任务是否已结束，普通手动下线没有对应 taskId/executionId，因此仍被拒绝。2755 已实际验证这一因果链，临时移除账号只消除了当前影响。

已发起永久注销、结果未知与尚未提交注销必须区分，不能统一清除保护并允许重新登录；但停止连接和注销状态收口应有明确可用路径。

### 3. 注销未知阶段，协议仍可能回报假在线/假下线

[login.go:165](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/api/service/login.go:165) 在注销门禁检查之前，只要缓存 LoginStatus 为 Online 就发在线回调并成功返回。Begin、UNKNOWN，以及任务 NOT_SENT 隔离确实可能保留这个缓存值和连接；本地临时测试已经确认该组合。

[login.go:93](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/api/service/login.go:93) 下线使用 GetValidWSApp；它对受注销保护实例返回 nil，导致下线回“已下线/不存在”却没有调用 StopNetwork；[executor.go:315](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/executor.go:315) 将其作为幂等离线成功。该问题针对仍保留连接的注销未知/隔离实例；不适用于 ACCEPTED 且已完成本地清理的实例。正常后台门禁可能先阻止命令进入协议，因此不能把协议缺口描述为当前每次页面下线都发生。

协议 CompleteAccountDeletion 本身也没有向 Armada 发布注销终态，只停网络、清本地数据、清缓存与 fleet 归属；直接协议注销路径尤其缺少后台状态同步。

### 4. 并发注销与异步登录间仍有转成代理失败的风险

若 CreateWSApp 首次校验之后、WALogin 二次校验之前，注销门禁生效，[waapp.go:367](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/app/waapp.go:367) 返回已拒绝 Promise。[login.go:366](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/api/service/login.go:366) 的异步分支不观察拒绝，仍报“登录已发起”；没有相应失败回调时，[lifecycle_scheduler.go:412](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/lifecycle_scheduler.go:412) 的验证超时仍产生 302 / PROXY_FAILED。

本地已分别复现“拒绝 Promise 却成功受理”和“验证超时报 302”，尚未在真实环境触发整条并发链。它是否进一步误伤后台代理，还取决于事件是否被消费：正式任务处于 DELETING/DELETED 时，后台会丢弃事件；没有后台注销账本的直接协议路径更需要覆盖。不能据此宣称现场已经重新发生大规模代理失效，也不能保证原补丁覆盖了所有路径。

### 5. 隔离补丁的接口与前置错误边界

当前页面的 ids/query 路径已使用隔离逻辑。但 AccountController 的 accounts/protocolBackend 兼容接口直接进入旧命令服务，仍可整批回滚；本轮未发现当前前端使用此分支，属于 API 覆盖缺口。

RESERVED 账号在 outbox 阶段才被剔除，上线代理分配发生得更早。代理不足等前置异常仍可能导致内部批次受理失败；它与“注销被误判为代理坏了”是不同故障，应分别处理和验收。

## 测试与验证边界

- 后端现有聚焦测试 83 项全部通过：14 批量编排、8 H2 事务、12 生命周期 H2、49 outbox。使用 JDK17 及已缓存 Byte Buddy 启动 agent；最初 JVM/agent 初始化错误已排除，不是业务断言失败。
- 前端现有相关测试 34 项全部通过。
- Android api/service、internal/armada、internal/service/app 聚焦测试通过。另用 /private/tmp 的 Go overlay 增加三个只读审计复现，确认 UNKNOWN 旧在线、任务 NOT_SENT 旧在线、立即拒绝 Promise 仍返回启动成功；未修改仓库测试源码。
- 现有测试未覆盖注销成功后 account_state 不再 NORMAL，也未覆盖任务结束释放创建者预留；“测试通过”不能证明上述缺口不存在。
- 本轮没有执行真实账号上下线、永久注销或并发故障注入。真实账号状态与代理存量会受其他现场操作继续变化。

## 后续修复范围

先统一注销结果回写与账号不可用状态，打通停止连接的语义，再补结束任务时未提交注销预留的安全收口；同时修复协议缓存在线捷径、下线假成功及异步拒绝/超时分类。最后覆盖所有批量入口，用“普通账号+注销账号混批”“结束任务后手动下线”“UNKNOWN/并发注销”做分层验证。当前仍处于分析结论，未执行这些修复。
