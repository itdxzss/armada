# 超链 463 有限重试与单条失败分析

状态：已在后端与前端主仓实施，完成本地验证；未提交、推送、部署或操作线上任务。
依据：本次用户明确要求“463 重试三次后标记失败，控端展示原因，任务不能停”；本轮已读取后端与前端源码。此前方案中的自动暂停建议不能覆盖本次要求。

## 业务规则

- 463 按逻辑收件人有限重试，耗尽后 FAILED(6)，不写 UNREGISTERED，不触发任务 PAUSED/STOPPED；剩余收件人继续。
- 次数已由用户明确：统一首次发送 + 3 次重试，总共最多 4 次；不再作为待确认项。
- 建议退避 30/60/120 秒；不阻塞其他目标派发。换账号不重置逻辑收件人预算，重复回执不增加预算。未知发送结果继续原 command 对账，不套用 463 明确拒绝重试。
- 用户后续明确：S1对A返回463后，A换其他发信人重试，S1仍可发B/C。保留平台触达限制通知作为事实展示，但超链不因该通知整体排除账号；原有其他来源限制及删除/封禁筛选保留。没有其他符合策略的发信人时等待资源，不空耗次数，不放宽账号数量上限。
- 本次聚焦 463，不顺带改变其他错误的业务口径；现有其他错误的自动暂停属于单独待清理范围，若要求所有单条错误都不能暂停需统一分类后落实。

## 已确认缺口与改动点

1. HyperlinkSendFailurePolicy.nextRetryAt 对 463 直接返回 Long.MAX_VALUE；HyperlinkRoundLifecycleService.advance 发现任意 hold 就暂停整任务。463 必须改走明确 RETRY / FINAL_FAILURE 决策，绝不产生 hold；仅把 463 放入现有可重试集合仍会在第三次触发暂停，不足以解决问题。
2. HyperlinkProtocolResultService 的 handleSendResultReported 和 handleAck 均能进入 requeueSystemFailure。两入口共用原子策略：锁定当前 command + SENDING 行，未耗尽则重入队；耗尽则保留最后发送账号、command、协议消息ID和463错误，设置 FAILED/failed_at。
3. 最终失败须走现有失败事实链：数据包 RETRYABLE_FAILED、统计投影任务/轮次/账号失败数、释放账号在途槽位和 dispatch guard。不能重入队清空归属后再直接置失败；应确保同一回执只处理一次。重试不重复计费或扩大唯一目标总数。
4. 现有 dispatch_attempt 是逻辑收件人的全原因累计尝试号，且重入队时先递增。按用户统一首次+3次重试口径，复用逻辑收件人的累计尝试号，跨换号/错误类型不重置，不新增463独立预算或表。使用attempt < 4重试、attempt >= 4终结，不能用模3代替。
5. HyperlinkRecipientStatus.businessCode/businessMessage 把 FAILED 屏蔽为 INCOMPLETE/未完成；HyperlinkTaskDetailService 和 HyperlinkRecipientCsvWriter 都调用它。已增加安全、明确的463业务原因映射，保留可辨识错误码，详情和CSV一致，不直接暴露任意原始协议文本。
6. 前端 HyperlinkRecipientStatsTab.failureReason 只硬编码两类说明，没有使用 row.failReason；recipient-stats 将 FAILED 标为未完成。改为显示发送失败和服务端的安全说明，待重试显示自动重试进度，并移除463对应的任务暂停提示。
7. 若展示实际重试进度，需在后端 Row/VO/查询与前端API类型增加 retryCount/retryLimit 等可选字段，不能从 failReason 文本猜次数。成功后不展示旧463失败文案。

## 展示建议

重试中：WhatsApp 拒绝发送（463），正在自动重试 1/3。
最终失败：WhatsApp 拒绝发送（463），已重试 3 次仍未成功。
已匹配明确账号限制事实时可补充：发送账号主动触达受限。不能仅凭所有463记录都写死具体限制时长。
任务列表失败数增加1，失败原因筛选、详情与CSV同口径；其他目标继续。

## 历史任务13

新代码不会自动让已PAUSED任务恢复。实施时独立制定限定 tenant=1/task=13 的恢复步骤：核对当前状态/审计，保留成功记录和已有尝试号，将463的Long.MAX_VALUE挂起按新策略归一化，再通过任务操作服务恢复并留审计。不能批量恢复所有暂停任务，也不能给已有重试清零。本次未执行恢复。

## 验证和交付

- 463连续拒绝：达到上限只终结该目标，其他目标继续，最后可正常完成任务。
- 第1/2/3次重试成功：停止重试，统计只算一个目标且显示成功。
- 两回执入口、重复回执、旧command迟到、并发调度、服务重启：不多扣槽位、不超限、不跨租户。
- H2真实Mapper验证失败落库、数据包状态、账号/轮次/任务投影与释放；覆盖无可用账号和任务选号上限。
- UI与CSV显示463和准确原因；成功行无旧错误，失败筛选一致。
- 必须验证当前暂停历史记录恢复后没有hold再次触发整批暂停。
- 预计后端+前端修改；协议层已上报463，本需求不要求改协议包或隐私token实现。
- 部署和业务恢复分开。回滚代码不能删除已经产生的失败事实；回退旧策略会重新引入自动暂停风险。

## 最终实施与配对事实模型

- 新增 V205 hyperlink_recipient_sender_rejection，主键 tenant_id/recipient_id/account_id；reason_code 和 created_at 保留拒绝事实。它属于超链收件人聚合，不是全局账号黑名单。现有recipient重试会清除account_id，usage是任务账号级，outbox不是可靠的收件人拒绝历史，均不能表达“此账号对A拒绝但仍能发B”的持久关系；不在已有宽recipient表内追加JSON名单。
- 三个结果入口（发送回执、ACK、同步enqueue拒绝）都记录配对，463耗尽后走真实FAILED、数据包RETRYABLE_FAILED、唯一目标统计和占用释放。
- lockPending增加账号配对排除，selectAvailable在LIMIT之前筛掉没有可派发目标的账号，避免前20个不兼容账号饿死后面的可用账号。
- 仅当当前轮次账号无在途且所有待发目标均拒绝过时，将轮次assignment_status设为已有的5=释放；账号usage状态不失效。轮次可在原策略上限内补选其他账号。只要还有B/C可发，S1不会被轮换。
- 平台来源触达限制、且无其他fallback消息限制的账号可继续参与超链；不清除账号限制事实，也不放开其他业务。
- 控端展示等待换号重试/发送失败及463安全原因；成功行不再展示上次463。此次未增加准确逐次重试进度字段，未新增过滤器；原有三项筛选保留。
- 历史已暂停任务没有自动恢复；其旧配对历史可能需要从原命令/日志核实。部署前执行Flyway，新表依赖先于业务代码运行。

## 本地验证记录

- 红灯：HyperlinkSendFailurePolicyTest.ack463GetsThreeRetriesWithoutPausing 期望31000，原逻辑实际Long.MAX_VALUE。
- 绿灯：Hyperlink*Test + AccountHyperlinkCandidateMapperH2Test，共415例，409通过，6个可选真库用例跳过，0失败/错误；含H2真实SQL、租户插件、Spring事务。新增463双回执9例、配对/选号6例、统计投影17例均通过。
- 最后小改（轮次释放状态、最终消息ID保留）分别补跑相关H2/回执用例。XML校验通过。
- 前端14例通过；typecheck、定向ESLint、Vite build通过。系统pnpm会尝试自动安装且受网络影响，采用现有node_modules通过npm脚本执行等价检查，没有升级或重装依赖。
- H2不等同MySQL InnoDB；未进行真实WhatsApp发送验收、未做线上历史恢复。
