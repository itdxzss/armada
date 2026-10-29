# 第二套环境超链任务 14 未补号分析

2026-09-18 17:10—17:13 北京时间只读核查。目标 perf2 / 3.110.124.52 / armada_perf，任务 14「内部测试」，租户 1。未改业务代码、线上配置或数据，未重发、恢复或部署。

## 已确认事实

- 任务冻结策略：即时任务，concurrent_num=2、max_use_account=10、account_max_send_num=0，groupIds=[616]。
- 16:49:09 开始；只选入并使用账号 2454、2461，两者分别累计成功 27、36。
- 账号 2454 于 16:51:23.288 被标记 ACCOUNT_BANNED；2461 于 16:51:24.062 被标记 WA_403（账号被平台禁用）。两者 assignment_status=3，各保留 in_flight_count=1。
- recipient 12145 / command hl:1:14:12145 与 recipient 12148 / command hl:1:14:12148 一直保留 send_status=2，fail_code=SEND_RESULT_UNKNOWN，原因“等待 WhatsApp 服务器 ACK 超时；发送结果未知，不自动重发”。截至查询仍在约每 30 秒安排对账。
- 停止前日志：上述两个 command 分别从 16:51:23.281、16:51:24.518 起出现未知结果，直到 17:01:57.062、17:01:57.079 各记录 17 条 UNKNOWN 处理日志。日志次数不是消息数量。
- 审计 STOP：actor_user_id=11，时间 17:02:22.373。任务不是因配置并发 2 而自动完成。
- 查询时受众 3999：成功 63（双钩 51、仅单钩 12），TASK_STOPPED 3934，仍在途 UNKNOWN 2。3934 不是实际逐条发送失败，系停止后清理产生。
- 运行容器启动于 16:26:05，RestartCount=0；运行 /app/app.jar 与本地 target/armada-api-1.0.2-SNAPSHOT.jar SHA256 同为 1abc308dc1a35b4c5c15b084f425e4312533970269ca8e9b4e9fa8ccc8ea5e92。

## 原因链

HyperlinkProtocolResultService.reconcileIfStillSending 对 UNKNOWN 保留 SENDING 状态、续期 guard，30 秒后再对账，不调用 completeSlot。该保守处理避免把未知发送直接当作未发送重发。

HyperlinkRoundLifecycleService.advance 在仍有待发受众、available=0 时，先判断 sending>0；只要有一条，就 markWaitingResult 并 return。replenish 位于该分支之后，因此两个封禁账号的未知消息阻断整个任务后续补号。剩余 8 个是未使用的额度，未预先选成 8 个等待账号。

本次任务的直接问题是“已失效账号的未知回执阻断其他账号补位”，比首次只按并发数选号更具体。停止是之后的动作，不能解释停止前约 11 分钟没有补号。

## 修复边界与未确认项

应分开处理旧消息对账与任务执行账号补位：保留这两条未知消息原 command 的对账、防重复发送约束；在最大使用数、有效执行并发约束内，允许可用新号处理尚未发送的其他受众。不能简单把 UNKNOWN 改失败、清空在途计数或直接重发。

未重建分组 616 在 16:51 时全部候选账号状态，因此不声称当时一定有 8 个符合条件的备用号。已确认的是本轮未进入正常补号路径且只选入两个账号。周期任务语义、受限账号是否计入严格取号上限属于另外的修复对账点。

另见停止后 cleanup/billing 的 40910 重试日志；本次未展开，不能当作停止前未补号的原因。
