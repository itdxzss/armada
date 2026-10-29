# perf2 超链任务 10 / 11 失败统计与重发建议

查询时间：2026-09-18 约 04:00–04:15（北京时间）。范围：已获用户确认的 perf2 业务库 armada_perf（3.110.124.52）及 Android 协议主机（3.111.245.182）。全部为只读查询；未重发、修改线上数据、重启或部署。

## 任务关系与口径

- 任务 10：9-17修缘4000+，数据包 15，总数 3,999，至少单钩 3,049，至少双钩 2,525，失败 950，未注册 0。
- 任务 11：9-179-174qee222.6.6W_失败重发并发10，数据包 17，总数 950，至少单钩 845，至少双钩 710，失败 105，未注册 0。
- 用 tenant_id + recipient_phone_snapshot 联接逐号核对：任务 11 的 950 个号码全部匹配任务 10 的失败号码；没有包含任务 10 的成功号码。重发恢复 845/950=88.95%，剩余 105 个唯一号码。
- 两轮合并至少单钩 3,894/3,999=97.37%；单钩不等于接收设备送达。失败次数 950+105 不等于剩余失败号码数。
- 任务 10 执行时间：09-17 22:57:53–09-18 01:21:14；任务 11：09-18 01:25:35–01:31:52（北京时间）。

## 原因分布

| 原因 | 任务10失败 | 任务11剩余失败 | 任务10该原因在任务11恢复 |
|---|---:|---:|---:|
| 原生发送失败 `SEND_FAILED` | 449 | 35 | 401 |
| 发送账号封禁 `ACCOUNT_BANNED` | 352 | 40 | 325 |
| 发送结果未知 `SEND_RESULT_UNKNOWN` | 66 | 8 | 65 |
| 发送前准备多次失败 `SEND_PREPARE_FAILED` | 51 | 2 | 42 |
| 目标加密会话不可用 `RECIPIENT_SESSION_UNAVAILABLE` | 17 | 18 | 1 |
| 发送账号离线 `ACCOUNT_OFFLINE` | 12 | 0 | 11 |
| LID 目标密文缺失 `LID_TARGET_CIPHERTEXT_MISSING` | 2 | 2 | 0 |
| 目标能力查询失败 `RECIPIENT_CAPABILITY_QUERY_FAILED` | 1 | 0 | 0 |
| 合计 | 950 | 105 | 845 |

“任务10该原因在任务11恢复”按第一轮原因分组；第三列按第二轮最终原因分组，两轮失败原因可能改变。

## 进一步证据

1. 两任务全部使用 ANDROID 协议。全部失败 recipient 的当前 command Outbox 均为 status=2（已发布），retry_count=0；无 recipient 的 sent_at/delivered_at/protocol_message_id。不能把 Outbox 发布当作 WhatsApp 成功，也不能仅凭没有 messageId 推断没有实际发出。
2. 任务 11 的 40 条 ACCOUNT_BANNED 来自 5 个发送账号。任务内 invalid_account_count=5 是任务期间记录口径；本次查询时，任务 11 用过的 20 个账号已是 18 个 account_state=3（封禁）/login_state=2，另外 2 个在线但 ACCOUNT_REACHOUT_RESTRICTED。旧发送账号当前均不适合继续用于这轮重试。后续封禁不能倒推为所有历史失败的直接原因。
3. 18 个 RECIPIENT_SESSION_UNAVAILABLE 中，16 个连续两轮都是同类失败；另外两个分别由首轮能力查询失败、原生发送失败转来。2 个 LID_TARGET_CIPHERTEXT_MISSING 也连续两轮相同。不能据此归类为号码未注册，本次未注册计数为 0。
4. 8 个 SEND_RESULT_UNKNOWN 全来自 account_id=2518。协议日志按 traceId 显示 prepare 重试、SEND_PREPARE_FAILED，以及 `reject Armada account message before send: claim Armada message command: Armada message command is already processing`。数据库最终为 UNKNOWN。当前源码会将超时 PROCESSING / SENDING_OWNED 命令保守收敛 UNKNOWN；现有证据尚不足以逐条证明无发送副作用，应保留未知状态并单独对账。不能把 scheduler 的 dispatched 日志理解为 socket 发送成功。
5. 35 个 SEND_FAILED 是协议归一化后的通用错误；关联日志没有提供足够证据把全部 35 条继续细分为确定的 WhatsApp ACK 错误码、超时或封禁，不将其一律归因于接收号码或模板。该类首轮 449 条重发恢复 401 条，说明整体具有重试收益，但不保证单条无重复触达风险。
6. 数据包 15 仍有 pool_status=5 的 950 条；数据包 17 有 105 条。跨包重发不反写原数据包。原包失败重置会再次覆盖已经成功的 845 个号码。当前 resetFailed 实现把包内所有 RETRYABLE_FAILED 变回 UNUSED，未按失败码排除 UNKNOWN。
7. 任务 10 策略并发 1，任务 11 策略并发 10；两者单账号在途上限均为 20，发送间隔 1000–1200ms，单号成功上限为 0（不限）。这解释配置差异，不证明它是封禁原因。

## 下一轮建议（尚未执行）

- 仅以任务 11 的 105 个失败号码为母集合，保持原任务历史记录。
- 77 个优先候选：ACCOUNT_BANNED 40 + SEND_FAILED 35 + SEND_PREPARE_FAILED 2。先对原生失败抽样核对，再使用当前在线、无消息限制的账号，选 10–20 个做小批次，观察 ACK/双钩和账号状态后继续。小批量用于判断重试是否有效，不保证降低平台封禁。
- 20 个单独诊断：会话失败 18 + LID 构建失败 2。先核对目标能力、会话建立和 LID 地址/密文构建；对连续两轮相同的 18 个尤其避免原样循环重发。
- 8 个未知结果单独保留：按原 commandId / traceId 对账，确认未发出才转入重试；不能直接点击全量失败重置。
- 切勿重置数据包 15 的全部 950 条。即使对数据包 17 操作，也需先分离 UNKNOWN 和会话/LID 故障，不能将 105 条无差别自动重发。

## 本地证据

- read-only-detail.sql：逐号重合、首轮恢复、原因转移、Outbox、策略和 usage 查询。
- read-only-remaining.sql：剩余失败账号、未知命令、数据包池状态查询。
- failed-recipient-traces.tsv：105 个失败 recipient 的 ID/commandId/accountId/failCode/traceId 和当前账号状态汇总；不含手机号。
- protocol-log-summary.json：关联 105 个失败 traceId 的协议日志聚合。logLines 为日志条数，不能作为失败号码数量。
