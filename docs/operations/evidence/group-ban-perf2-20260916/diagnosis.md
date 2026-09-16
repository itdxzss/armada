# 第二套群封禁与营销状态不一致

日期：2026-09-16。目标：perf2 后端 3.110.124.52，Android 3.111.245.182。只读排查，未修改业务数据、未发送探测消息、未部署。

## 现场证据

- 账号 acc_23591800356（后端日志 accountId=1779）使用 Android，workerId=android-zhuan-perf。
- 120363433010872755@g.us：北京时间 22:44:01.302 收到 CHAT_SUSPENDED/BANNED，22:44:01.334 服务记录健康状态回写 banned=true，groupLinkId=12954。
- 120363423758581377@g.us：北京时间 22:58:38.512 收到 CHAT_SUSPENDED/BANNED，22:58:38.540 服务记录健康状态回写 banned=true，groupLinkId=12956。
- 同两个群在 taskId=276 的 23:25:06（roundNo=18，与截图对应）以及 23:34:07（roundNo=27）仍收到 success=true 的营销结果。
- 原始日志见 backend-evidence.log，日志时间为 UTC。

## 当前代码解释

1. GroupLinkHealthReportServiceImpl.applyHealthReported → GroupCurrentInvitePersistence.applyHealth → GroupCurrentInviteMapper.updateGroupHealth 更新 wa_group_profile 的 banned/health_status/last_error_code。
2. MarketingTaskMapper.selectAccountGroupStatsByTaskId 的协议状态来自 marketing_task_send_attempt 的 latest_protocol；不读取 wa_group_profile.banned。
3. MarketingGroupExecutionNormalizer.normalize 在 attemptStatus=SUCCESS 时直接返回 NORMAL/SUCCESS，先于群封禁判断。
4. Android internal/armada/message_sender.go CompleteSend 在 ACK outcome.Success 时直接生成 NORMAL/GROUP_SEND_ALLOWED；封禁状态细化位于失败路径，成功分支不读取群终态。
5. MarketingTaskMapper.selectDynamicTargetGroups 虽关联 current_profile，但不读取/过滤 banned。MarketingRoundWorker.partitionTargets 按账号占用和在群关系分类，未基于群封禁排除。
6. GroupLinkHealthReportedSinkAdapter 对封禁调用 PullTaskGroupBanTerminationService；该服务合同为普通拉群执行行，不是营销状态展示或普通营销发送的统一拦截。

## 版本与边界

Android 部署目录 message_sender.go 与本地文件 SHA-256 相同：aa413458ce7e408270270167004d38fa87c695debe890cf810e1a3fbff77ebe8。部署目录不是 Git 仓库；该比对不是运行二进制全量源码一致性证明。

首轮数据库核查被自动审批拒绝。用户随后明确授权使用现有连接配置执行只读查询；本轮已在 armada_perf 的只读事务中完成 SELECT，凭据只在进程内使用，没有保存或展示。两个群当前持久化封禁值已直接确认，见下文。

## 修复方向

- 营销详情独立读取权威群健康状态，明确封禁事实应优先于发送结果推导的正常；保持历史成功/失败统计原义。
- 营销轮次/发送前统一拦截明确 banned 的群；在群关系与封禁状态分开保留。
- Android 成功 ACK 不应作为群健康正常证明，不能覆盖此前明确封禁事实；处理封禁与解除事件的时序。
- 验收需要覆盖：已收到封禁事件后，后续成功 ACK 不使状态变正常；普通营销停止对该群继续派发；真实解除封禁才恢复。


## 用户授权后的深入复核（北京时间 23:47 起）

### 数据库当前值

| groupJid | wa_group.id | banned | health_status | last_error_code | 事实时间（北京时间） | 数据库更新时间（北京时间） |
|---|---:|---:|---:|---|---|---|
| 120363433010872755@g.us | 19402 | 1 | 3 | CHAT_SUSPENDED | 22:44:00.358 | 22:44:01.333 |
| 120363423758581377@g.us | 19404 | 1 | 3 | CHAT_SUSPENDED | 22:58:37.501 | 22:58:38.540 |

事实时间到数据库更新时间分别为 975ms、1039ms。数据库核查时仍保留上述封禁值；后续发送没有把 wa_group_profile 改回正常。该延迟从本系统记录的 checkedAt 起算，不代表能确定 WhatsApp 平台最初封禁的绝对时间。

### 封禁后实际继续发送范围

按 attempted_at >= 对应群 last_checked_at 查询：

| 任务 | 群尾号 | 封禁后发送记录数 | 记录结果 |
|---|---|---:|---|
| 275（111） | 8581377 | 2 | SUCCESS / NORMAL / GROUP_SEND_ALLOWED |
| 275（111） | 0872755 | 3 | SUCCESS / NORMAL / GROUP_SEND_ALLOWED |
| 276（111，截图任务） | 8581377 | 27 | SUCCESS / NORMAL / GROUP_SEND_ALLOWED |
| 276（111，截图任务） | 0872755 | 27 | SUCCESS / NORMAL / GROUP_SEND_ALLOWED |

合计 59 条；截图任务中为 54 条。任务 276 最终总成功计数 108，包含另一个正常群对两个账号的 54 条。任务 275、276 查询时均为 status=8（已关闭）；276 最后发送在 23:34:08 左右，任务更新时间 23:34:18.551。诊断没有关闭任务，也未调查是谁关闭。

两任务 is_abnormal_group_skipped=1。当前代码 getter 仅在详情/列表返回时使用，调度未读取；动态目标查询和轮次分流也未读取 banned。故开启该选项仍无法拦住本案明确封禁群。

账号 1779（23591800356，ANDROID）在两个群的 presence_status=1、last_exit_type=NULL。封禁与成员在群事实独立；不能靠在群关系判定群正常。

### 线上制品与查询复现

- 容器 /app/app.jar SHA-256：f59f117275876ec67fffa6825b290a916addac90ef7b689075d82a5f9b088491。
- 与本地发布目录 /private/tmp/armada-ios-perf2-20260916-01a0a9dd/armada-api/target/armada-api-1.0.2-SNAPSHOT.jar 完全一致。
- 从此 JAR 提取 MarketingTaskMapper.xml，与主仓当前文件逐字节一致。
- 从此 JAR 提取 MarketingGroupExecutionNormalizer.class，javap 确认 SUCCESS 分支直接返回 NORMAL/SUCCESS，发生在封禁判断前。
- 提取 selectAccountGroupStatsByTaskId，绑定 taskId=276 进行只读查询（邀请链接输出置 NULL；不是经 HTTP 或租户拦截器执行）。返回两个封禁群 membershipStatus=1、latestAttemptStatus=1、groupStatus=NORMAL、groupStatusReason=GROUP_SEND_ALLOWED、各 sentMessageCount=27。与截图语义一致，证据 page-query-result.tsv。
- 该独立 SQL 复现耗时约一分钟以上，未重复执行；不据此推导实际 HTTP 接口性能，因为本次未通过租户 SQL 拦截器执行。

### 原始 ACK 证据

截图 23:25 对应消息：

- CC42B5401202468E936AD4AD45DB7FB5：23:25:05.119，WhatsApp message ACK accepted，tracked=true。
- 08B79657B65248498003075532FD57AF：23:25:05.803，同上。

因此 success=true 有服务器 ACK 依据，不是后端凭空生成。但 accepted ACK 不足以证明群健康恢复，也不能证明成员收到或阅读。当前 Android 成功分支把消息 ACK 进一步推导为 NORMAL，这一步越过了证据边界。

### 确定结论

封禁事件接收、消息队列消费和群资料落库在本案均成功，约一秒。断点在营销业务消费该事实：任务详情从发送结果推导群状态，发送筛选没有封禁门禁，Android 成功 ACK 分支也不检查既有封禁事实。封禁状态不是丢失，而是没有成为营销展示和后续发送的权威依据。

交付：只读诊断及证据文件。未修业务代码，未 commit/push，未部署，未修改第二套业务记录。
