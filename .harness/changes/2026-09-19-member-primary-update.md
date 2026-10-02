# 成员通知已知身份按主键更新

## 范围与结论

用户授权“在主仓库先改”。已在 Armada 主仓库 `1.0.3-snapshot` 修改成员事实持久化；保留工作区其他在途变更，未提交、推送、部署或恢复历史任务。

依据 [本地死锁复现](../../docs/business/2026-09-19-member-upsert-gap-reproduction.md)：同一成员已有 PN/phone、缺 LID 时，两个通知的 INSERT/phone 唯一键冲突/LID 补写可以形成 LID gap 与 insert-intention 环路，无需 FOR UPDATE。

## 实现

- 复用现有普通成员身份查询的主键结果。已找到成员执行 `UPDATE ... WHERE tenant_id + group_id + id`，不存在成员保留原 UPSERT。
- PN/LID 双行先按原规则归并，再向 canonical 主键更新；维护批次中的主键映射，避免更新已删除的重复行。
- 单账号观察和批量成员事实共用写入入口。纯身份合并、账号轻量快照的专用 UPSERT 不在本次替换范围。
- 新旧写路径共用事实赋值 SQL，保留 presence/role 来源和时间优先级；展开后的原 `upsertParticipantFacts`、`upsertParticipants` SQL 与修复前一致。
- 主键 UPDATE 带 PN/LID/phone 一致性条件。读取后身份边界变化或成员被其他事务归并时，抛 `ConcurrencyFailureException` 中止事务，交由既有事件失败处理；不静默丢事实，也不在当前事务中盲目退回 INSERT。
- 没有新增显式锁、队列、调度器或数据库结构变更。已知成员逐行 UPDATE 会增加批量写入的 SQL 数量；本次范围是消除已复现的已知成员 INSERT 冲突路径，不承诺消除所有其他事务死锁。

## 验证

JDK 17，Maven，显式 Byte Buddy agent 1.18.11。

1. 新增 `existingPhoneMemberDoesNotReenterInsertWhenNotificationCompletesLid` 先红：原实现仍调用 UPSERT；修复后通过。
2. `AccountGroupCurrentSnapshotPersistenceImplTest`、`AccountGroupCurrentSnapshotMapperH2Test`、`AccountGroupControlledBatchPersistenceTest`：31 项，0 失败，0 跳过，退出码 0。
3. `AccountGroupCurrentSnapshotPersistenceMySqlTest`：本机 Testcontainers MySQL 8.4.8、RR、真实 Mapper 和事务；41 项中 39 通过，2 项失败，0 跳过。
4. 新增 MySQL `concurrentObserversCompleteExistingPhoneMemberByPrimaryUpdate`：100 轮 × 2 个独立事务，两个 observer 同一通知；每轮重建 PN/phone 已有、LID 缺失的前置记录。全部提交成功，每轮最终只保留原主键成员，LID/phone/在群态/事件正确；JDBC 记录为 2 条主键 UPDATE，没有成员 INSERT 或 FOR UPDATE。
5. H2 验证身份补全、重复和晚到事件、管理员来源保护、错误租户/群/身份拒绝、事务回滚。XML 校验与改动文件 `git diff --check` 通过。

完整 MySQL 回归的两项失败：

- `completeSnapshotOf400GroupsUsesAtMostElevenStatementsAndClassifiesBaselineSafely`：初始群分类期望 200，实际 400。
- `preciseSelfMembershipDualWriteKeepsClassificationAndExitMeaning`：`was_in_initial_baseline` 期望 0，实际 NULL。

在 `/private/tmp/member-fix-baseline-20260919` 保留其他在途改动、仅撤销本次 Service/XML 修改后运行上述两项，均以相同断言失败（2 项、2 失败、0 错误、0 跳过）。确认不是本次修复新增的问题，未扩大范围修改。

原始本机日志：`/private/tmp/member-fix-red.log`、`member-fix-focused.log`、`member-fix-mysql.log`、`member-fix-baseline.log`。测试摘要保存在 `docs/business/evidence/2026-09-19-member-upsert-gap/implementation-verification.json`。

## 验收边界

本地实现与复现场景回归完成。尚未部署到 perf2，也没有通过线上新任务验证第二个账号进群、设管理员及后续清理的完整链路；历史失败记录未做恢复。
