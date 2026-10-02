# 超链失败恢复评审修复（2026-09-18）

## 目标与边界

用户确认修复独立评审发现的三项问题。基于后端 6aa319cd、Android 27798369 的后续修改；不处理历史任务、不修改线上数据、不部署。本次前端无改动，不新增表或字段。

## 实施

1. 统一 `UNREGISTERED` / `RECIPIENT_UNREGISTERED` 两个精确错误码，回调及本地入队拒绝均归入目标未注册（recipient 7 / 数据池 UNREGISTERED），不阻塞任务恢复。
2. Android `ErrMessageDispatchReprepare` 证明 MessageNode 尚未进入发送队列。重建耗尽或重建准备失败输出 `NOT_SENT` 并保留具体准备错误；socket 副作用不确定、panic 仍输出 UNKNOWN，继续按原 commandId 对账。
3. 换号重试前结清单条 SENDING 投影；退回 PENDING 时撤回旧轮次/账号归属，已提交数暂存 NULL 账号桶；重新分配后同步迁移到新账号/轮次。任务总提交数保持唯一。移除空账号桶，更新旧范围发送时间边界。失败迁移及重新分配均与 recipient CAS 在同一事务内，异常全部回滚。

锁序为 runtime FOR UPDATE → round → usage → recipient → account_stat；回调先取得范围锁再读取并锁定当前 commandId，已成功或过期回调不重发。统计迁移方法要求已有事务（MANDATORY）。派发原本已通过轮次锁串行，本次将 runtime 的共享锁改为排他锁，避免更新统计时升级锁。

## 验证

- 修复前回归：Java 未注册码分类用例失败；Go 两种安全重建失败被错记 UNKNOWN；评审 H2 换号账号统计复现 send=0/success=1。
- Go vet/build 通过；发送链路专项 race 通过。全量 Go 测试存在本次范围外失败：正在被其它任务修改的 group_profile_reported_test，以及 pkg/noise 的 8 项失败，未改动它们。
- Java `Hyperlink*Test,ProtocolCommandOutboxMapperInMemoryTest`：388 项，382 通过、6 项 MySQL 环境测试跳过、0 失败/错误（日志 `/private/tmp/hyperlink-review-fix-java-final.log`）。
- 其中真实 H2 统计测试 16/16 通过：projected×submitted 矩阵、换号跨轮、连续本地拒绝、重复 attempt/分配 CAS、两阶段回滚、PENDING reconcile、时间边界、租户隔离、双事务锁等待、MANDATORY 约束。未连接真实 MySQL，因此不宣称已验证 InnoDB 锁行为。
- 独立 agent 编写 H2 回归并只读复核修复，未发现新的必要修改项。`git diff --check` 通过。
- Go 日志：`/private/tmp/hyperlink-review-fix-{vet,build,go-all,go-race-focused}.log`。

## 交付

修改位于主仓库工作区。尚未提交、推送、部署。工作区还有其它任务的在途修改，本次不得合并或回滚这些改动。
