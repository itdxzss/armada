# 变更记录：进群结果回调移除显式锁定读

- 日期 / 分支 / worktree：2026-09-18，`1.0.3-snapshot`，主工作区；存在其他任务的在途修改，本次未提交、推送或部署。
- 需求来源：用户排查第二套测试环境 perf2 的进群任务 105 后，要求去掉当前链路的 `FOR UPDATE`。
- 状态：本地实现和聚焦回归完成，未部署、未做远程业务恢复。

## 目标

进群结果处理使用普通读取，避免显式锁定读扩大回调事务的持锁范围。

## 变更范围

- `JoinTaskResultMapper.selectSubmitted`：普通读取当前命令；成功、失败和重排 SQL 同时比较状态、命令 ID 和尝试序号。只有成功迁移的一方才执行成员落库、任务计数和后续推进。
- `AccountGroupCurrentSnapshotMapper`：群主键、旧群句柄、账号自身成员关系、PN/LID 身份查询去掉显式锁，共四处 SQL。共享调用方也使用普通查询。
- `GroupClassificationMapper.selectByGroupJids`：普通读取分类，保留首次分类的条件 UPDATE。
- 回调登记群入口改用 `selectAnyByUrl`，分类筛选入口改用 `selectActiveByIds`，延迟营销登记复用已经读取的任务，取消第二次锁定读取。
- 两个进群结果事务入口使用 `READ_COMMITTED`，避免普通读长期复用事务早期快照。无表结构或接口变更，无新分布式锁。
- 同步更新方法名、注释及测试。超过五个参数的重排条件收束为 `JoinTaskRetryTransition` 参数对象。

以上为六处 SQL 去锁和三处回调调用改用普通读取。其他调度、完整快照和邀请信息链路仍有 `FOR UPDATE`；本次不表示仓库全部显式锁已清空。`UPDATE`、`INSERT`、唯一键校验仍会产生数据库写锁，不承诺消除全部死锁。

## 验证

- 新增回调普通读测试先在旧实现上失败：另一事务持写锁时读取超过一秒等待；修改后通过。
- 新增真实 Mapper + 生产租户拦截器 + H2 独立事务测试：并发两次回调只有一次成功、旧尝试不能覆盖新命令、租户隔离、事务回滚后可重投、当前尝试只重排一次。
- 新增自身成员查询测试：其他事务持 participant/binding 写锁时，普通读取完成。
- `mvn -q -DskipTests test-compile`：退出码 0。
- 最终聚焦回归：17 个测试类、153 个测试，失败 0、错误 0、跳过 0；退出码 0。

```bash
cd armada-api
mvn -q -Dtest='JoinTaskResultConcurrencyH2Test,JoinTaskResultServiceTest,JoinTaskAdminMapperH2Test,JoinTaskDispatchTransactionServiceTest,AccountGroupCurrentSnapshotMapperH2Test,GroupClassificationMapperH2Test,GroupClassificationServiceImplTest,AccountGroupCurrentSnapshotPersistenceImplTest,AccountGroupControlledBatchPersistenceTest,GroupLinkRegistryServiceImplUnitTest,MarketingNewGroupImmediateSendServiceImplTest,GroupProfileReportedSinkAdapterTest,GroupMetadataSnapshotServiceImplTest,AccountGroupMembershipStatusServiceImplTest,GroupLinkRegistryPullTaskTargetTest,AccountGroupControlledBatchMapperH2Test,JoinTaskAdminTransactionsTest' test
```

- 三个修改的 Mapper XML 通过 `xmllint --noout`；涉及文件的 `git diff --check` 通过。
- MySQL 专用测试仅同步编译和旧锁预期，未执行真实 MySQL 验证。H2 不覆盖 InnoDB 的间隙锁和完整死锁行为。
- 初次非沙箱测试选择误包含依赖外部数据库的 `GroupLinkRegistryServiceImplTest`，已在连接初始化重试阶段取消，不计入验证结果；最终套件只使用单元测试及 H2。
- 本地日志：`/private/tmp/join105-perf2-diag/test-final.log`。改动前逐文件备份在同目录 `before-no-lock/`，便于区分其他任务的脏改动。

## 部署和遗留

- 未提交、推送、部署；perf2 运行中的制品尚未变化。
- 未修改任务 105 的线上记录，也未重投其回调；本地通过不等于该任务已恢复。
- 部署后仍需实际观察回调落库、任务完成状态及消息消费情况。
