# 第二套任务 36 第二群等待与恢复失败

取证：2026-09-18，北京时间。目标为 perf2 / 3.110.124.52 / armada_perf。仅只读数据库、容器状态与日志；未修改业务状态、部署或代码。查询采用 READ ONLY 事务、MAX_EXECUTION_TIME=5000。

## 结论

昨天的名额竞争死锁修复已包含在当前运行版本。此次明确发现另一个后端缺陷：管理员进群恢复成功后绑定群 JID 时，线程缺少 TenantContext，抛出 BusinessException；上层将此程序异常转换为 MANAGER_MEMBERSHIP_UNCONFIRMED，执行再次等待管理员资源，反复重试仍失败。等待行不占执行并发名额，后序群可以先运行；并发数 1 不等于严格逐群完成顺序。

不能用这次证据声称昨天死锁复发，也不能把“等待并发名额”当作第二群最初失败的原因。

## 运行制品与历史范围

容器启动：2026-09-18 03:05:09.921 北京时间，restartCount=0。
运行中 /app/app.jar SHA256：7b264357990509f94d0472d9412f9fcb2a4e807aa29a561e0b113802d8a2fdd5。
该哈希匹配 group-creator-release-20260918 的 perf2 发布证据；发布源快照 PullTaskMapper.xml 含 countAvailableExecutionSlot + advanceExecutionSlotVersion 两步实现。
昨天的最小修复文档明确未修改公平排队。最初“未部署”记录是该修复会话结束时状态；后续凌晨整体发布已带入，不能据旧文档认定现在没上线。

## 任务及时间线

截图两个群 JID 精确匹配任务 #36（任务_13350516）、执行 #185/#187。并发群数=1，PASTED_LINK，source_group_folder_id=NULL。

- 14:36:50.237：第一群 #185 启动；14:37:41.573 收到 GROUP_BANNED 并终止。
- 14:37:41.839：第二群 #186（9-12AK13.txt）已经启动。
- 14:37:52.927：后端收到其管理员进群失败回执，actionId=3224、attemptNo=1、outcome=FAILED；同一管理员随后出现 RECONNECTING→ONLINE。当前动作行被后续恢复覆写，日志未打印首次失败详细原因码，故不认定首次失败的具体协议原因。
- 14:38:22.571：第三群 #187 启动；14:39:20.558 收到 GROUP_BANNED 并终止。
- 14:38:25.007：#186 恢复路径明确报“缺少租户上下文，请重新登录”。该错误出自后台线程，不能据此要求用户重新登录。
- 至14:46:40.423，采集日志包含该执行的同类异常54次；采集窗口14:20至14:46:42未见 deadlock 文本。
- 14:47:11 快照：#186 为 WAIT_RESOURCE / MANAGER_JOIN / MANAGER_MEMBERSHIP_UNCONFIRMED，仍非终态；其余六行均为 GROUP_BANNED 终态。

## 代码调用链

PullTaskManagerJoinTransactionService.prepare 设置租户上下文，但 finally 恢复原上下文；事务外调用 PullTaskManagerJoinProtocolExecutor.join。

该方法65行得到 confirmed 进群结果，68行调用 inviteLinkService.bindGroupJid；GroupCurrentInvitePersistence.bindGroup 58-60行发现 TenantContext 为空并抛错。运行日志完整栈与该路径吻合。

PullTaskManagerJoinProcessor.exceptionOutcome 174-175行将非协议异常统一转为 unconfirmed(null, MANAGER_MEMBERSHIP_UNCONFIRMED)，导致已确认结果被程序异常遮盖。恢复阶段缺少可持久化的群JID，后续仍走同一故障路径。

PullTaskResourceRecoveryTransactionService.deferForSlot 在资源检查通过但名额竞争失败时写 EXECUTION_SLOT_UNAVAILABLE，因此截图中的“等待并发名额”是恢复过程中的当时等待原因，不能覆盖底层异常。

修复应覆盖恢复调用链的租户上下文传递及恢复后成功落库，并防止将内部程序异常伪装成管理员资源不足。严格按页面次序、前一群等待时是否阻塞全任务属于另一项调度规则，不能用死锁修复替代。

原始只读取证：/private/tmp/pull-skip-perf2-20260918/detail-result.txt、runtime-and-logs.txt。原始日志含业务标识，仅本机保留；本文不收录凭据或协议payload。
