# 变更记录：管理员进群恢复丢失租户上下文

- 日期 / 分支 / worktree：2026-09-18 / 1.0.3-snapshot / armada 主仓库。
- 需求：用户要求按已说明方案在主仓库修复第二套任务 #36 的恢复异常。
- 状态：本地修复及聚焦验证完成；本次不包含部署或线上任务操作。

## 目标与范围

管理员进群恢复在后台线程中也必须携带持久化工作项的租户身份，内部错误不能转为管理员资源不足。

仅调整 PullTaskManagerJoinProtocolExecutor、PullTaskManagerJoinProcessor 两个生产类。无数据库结构、Mapper SQL、接口、Redis 或协议契约变更；保留既有调度规则及动作幂等标识。

## 实施

- 在 join 入口保存原 TenantContext，设置 work.tenantId，finally 恢复/清理；覆盖刷新邀请码、进群、绑定群 JID。
- Processor 仅捕获 ProtocolException 做协议业务分类。其他异常冒泡到已有 coordinator，记录原始错误并释放租约、退避；不调用 complete 写入伪造的在群未知状态。
- 保持 GroupCurrentInvitePersistence 的租户校验，不放宽隔离、不使用默认租户。
- 测试覆盖无 HTTP 上下文、原上下文恢复、各步骤异常、真实群绑定与两租户连续执行。

## 验证

事故与运行制品证据见 docs/operations/evidence/perf2-pull-task-36-waiting-20260918.md。

- 先红：Executor/Processor 18 项中 6 项失败，复现缺少工作租户及内部错误被吞；日志 `/private/tmp/pull-join-tenant-red.log`。
- H2 夹具补齐字段后，3 项中 1 failure / 1 error，明确复现真实服务的 TENANT_MISSING 及线程携带错误租户导致未正确绑定；日志 `/private/tmp/pull-join-tenant-h2-red.log`。
- 首次修复后测试编译受到另一项在途 AccountExportArchiveTest 引用尚未创建类的影响。临时 POM 排除该文件、独立输出目录运行相关 80 项通过；未改动仓库 POM 或该在途测试。
- 对方补齐类后，重新使用主仓库原始 POM，以下命令 exit 0：80 tests / 0 failures / 0 errors / 0 skipped。日志 `/private/tmp/pull-join-tenant-final.log`。

```bash
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home \
mvn -q -f armada-api/pom.xml \
  -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar \
  -Dtest=PullTaskManagerJoinProtocolExecutorTest,PullTaskManagerJoinProcessorTest,PullTaskManagerJoinTenantH2Test,PullTaskManagerJoinTransactionServiceTest,PullTaskManagerJoinTransactionIntegrationTest,PullTaskExecutionDispatchCoordinatorTest,PullTaskResourceRecoveryTransactionIntegrationTest,PullTaskManagerJoinResultServiceImplTest test
```

新增/扩展 Executor、Processor、TenantH2 三类测试，合计新增 10 项。TenantH2 使用真实 GroupInviteLinkServiceImpl / GroupCurrentInvitePersistence、Spring 事务、Mapper XML 和生产租户插件；H2 不支持 FORCE INDEX，测试内仅去除此索引提示，保留查询条件与 FOR UPDATE，不作为 MySQL 锁行为验证。

人工检查生产差异只涉及上下文作用域及异常捕获类型；保留原 operationId、协议错误分类和调度退避实现。`git diff --check` 通过；本次五个 Java 文件指纹保存于 `/private/tmp/pull-join-tenant-source.sha256`。未运行全量测试或远程验收。

## 交付边界

仅本地主仓库修改。未提交、未推送、未部署、未改任务状态；线上业务恢复尚待发布后验证。

回滚只回退本次两个生产类的差异，保留仓库其他在途修改，无数据迁移。
