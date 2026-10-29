# perf2 账号 2755 下线与删除被拒诊断

日期：2026-10-06。用户新截图红框账号尾号 6580，对应 tenant_id=1、account_id=2755。此次仅只读查询数据库、日志及协议本地状态；未更改该账号、任务或预留记录。

## 结论

任务 64 已结束，但创建者账号预留没有释放。普通手动下线没有任务上下文，被 account_creator_deletion 生命周期门禁拒绝。删除请求被独立的账号状态规则拒绝：当前 account_state=2（正常），不是允许删除的导出、封禁、解绑或被抢登状态。即使只把登录状态改成离线，也不会满足当前删除规则。

## 实时证据

- 14:03:08 UTC 数据库快照：账号 2755 未软删除、dispatched_at=NULL，account_state=2、login_state=1、desired_login_state=1。最后状态同步时间 08:34:31.129 UTC。
- account_creator_deletion：task_id=64、group_execution_id=303、lifecycle=RESERVED、create_operation_id=ptgc:1:303、operation_id=NULL；自 11:35:19.101 UTC 预留以来未更新。
- 14:05:59 UTC 数据库快照：任务 64 为 STANDARD/NEW_GROUP，开启接管后注销，status=ENDED，blocking_reason=人工结束，finished_at=13:33:30.217 UTC（北京时间 21:33:30）。
- 执行 303：execution_status=6（ABANDONED），stage=9（GROUP_CREATE），create_step=2，reason_code=GROUP_CREATE_FAILED。失败原因为 Android 协议 ACCOUNT_NOT_ONLINE，armadaAccountId=2755。
- pull_task_creator_deletion：status=0（RESERVED）、attempts=0、submitted_at=NULL、completed_at=NULL、deletion_result_status=NULL。本任务尚未记录提交永久注销。
- 创建者角色 4680：task_id=64、execution=303、role_type=4、released_at=NULL。旧任务 58 的拉手角色已释放，与本次残留创建者占用不同。
- 用户单账号下线请求：13:56:00.817—.827 UTC，traceId=0e8001038f2e4ea0b0f4943773409955。日志明确为 `operation=OFFLINE rejected=1 remaining=0`，最终 `accepted=0 failed=1`；数据库无当天该账号下线 outbox 行。
- 用户删除请求：13:56:09.367 UTC 和 13:59:03.835 UTC，后端 code=40001：仅导出/封禁/解绑/被抢登状态且不在任务的账号可删除，账号 2755 不满足条件。
- 14:05:37 UTC，在已确认的 perf2 Android 服务只读 GET `/ws/v1/auth/status/<账号>`：Code=1003，Msg=账号不存在或已下线。证明该节点当前没有在线实例，不能把页面数据库“在线”当协议在线。该服务在前一次代理分类补丁发布时重启过，因此此观测不用于推断最早下线时刻；执行 303 先前已记录 ACCOUNT_NOT_ONLINE。
- 协议注销查询 HTTP 接口需要专用授权；本次未提供令牌，因此没有获得删除记录。随后定向 Redis 只读查询因 SSH banner exchange 超时失败，未据此宣称该账号不存在任何其他注销记录或旧密钥一定可用。

## 源码原因

1. `PullTaskStandardLifecycleServiceImpl.endTask` 只结束执行行、取消未提交动作、调用 `releasePullers`；后者只释放 PULLER 角色，未收口创建者预留。
2. `ProtocolCommandOutboxMapper.xml:419` 的 `creatorDeletionCommandBlocked` 不核对所属任务是否终结。RESERVED 记录只允许携带相同 taskId、executionId 的命令；普通下线缺少这些字段，持续命中门禁。
3. `AccountServiceImpl.batchDelete` 严格校验 account_state；NORMAL 不属于可删集合。login_state 与 account_state 是两套状态，离线不自动变成解绑或已注销。

## 修复应覆盖的边界

任务结束/放弃时，需安全收口尚未提交注销的创建者预留与角色占用，并给手动停止命令正确的生命周期处理；已进入 DELETING/DELETED 或已提交/结果未知的注销不可套用简单释放。后台登录状态需要与协议重新对账。删除能力和页面提示需明确区分停止登录、本系统移除与 WhatsApp 永久注销，不能为让删除按钮通过而伪造解绑状态。

## 用户指出已注销后的补充核对

用户进一步确认“都注销了，还正常什么啊”。数据库 account_state=2 只能说明系统仍持有旧状态，不能用来证明账号正常，更不能把删除门禁的当前实现当作合理业务结果。

本轮重试成功读取 perf2 协议 Redis 中该账号的精确 account-delete 身份键，返回 null；因此仍未独立核实该账号通过哪条路径完成注销，也不能仅凭本任务 RESERVED 推断用户未注销。

源码另有已确定的状态回写缺陷：AccountCreatorDeletionServiceImpl.completeDeletion 在生命周期改成 DELETED 后调用 AccountCreatorDeletionMapper.markOffline；该 SQL 仅写 desired_login_state、login_state、state_source、invalidated_at 和 updated_at，完全没有更新 account_state。也就是说，即使正式任务注销链路走到完成，原来的 NORMAL 仍会留在账号状态字段。此缺陷成立，但不能在缺少 2755 注销结果证据时，把它直接断言为该账号全部现象的已证实原因。本轮未修改代码或账号数据。

## 用户授权移除账号后的执行结果

用户后续明确要求“把这个账号给我删了”。2026-10-06 14:24:09.464 UTC（北京时间 22:24:09.464），在已确认的 perf2 环境完成单事务软删除。事务前锁定并核对 tenant=1、account=2755、号码尾号 6580、任务 64 已结束、执行 303 已放弃、无发送中命令及其他活动执行占用；守卫命中 1。

- account.deleted_at 已设置，有效账号计数为 0。
- login_state=2、desired_login_state=2、state_source=ACCOUNT_DELETED，停止自动上线。未伪造 WhatsApp 注销成功或解绑状态。
- 没有待发送的上线命令，取消数量 0。
- 释放该账号在已结束任务中的创建者角色 4680，影响 1 行。
- 释放该账号专属代理 13019，按现有 releaseByAccount 语义由 USING 改为 IDLE 并清除绑定；未改代理检测结论，影响 1 行。
- 保留账号历史、account_creator_deletion 及协议注销审计，不发送 WhatsApp 注销请求，不删除协议持久保护。

事务前后脱敏快照：`account2755-removal-before-after-1791296666437473000.tsv`；执行 SQL：`account2755-removal.sql`；独立回查 SQL：`account2755-removal-verification.sql`。未修改业务代码或部署。

14:24:50 UTC 独立只读连接验证：有效账号 0、待执行上线命令 0、未释放角色 0、该账号代理绑定 0；生命周期审计保留 1 条。释放的代理 13019 已被其他现场业务重新分配给账号 2081，未干预新绑定。
