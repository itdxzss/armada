# 变更记录：普通拉群名额竞争死锁最小修复

- 更新日期 / 分支 / worktree：2026-09-18，1.0.3-snapshot，主仓库 armada；未创建 worktree。
- 需求：用户要求减少锁设计，直接在有其他 agent 在途修改的主仓库完成代码；测试环境验证后置。
- 状态：代码与聚焦本地测试完成，未提交、未推送、未部署。

## 实现范围

仅两个生产文件：PullTaskMapper.java、mapper/task/PullTaskMapper.xml。acquireExecutionSlot 接口不变，改为普通快照检查名额，再按同一父任务版本做单行条件 UPDATE。首次启动和资源恢复同时生效，不改它们所在的在途 Service 文件。

新增测试 PullTaskExecutionSlotMapperTest。更新设计文档 docs/business/pull-task-dispatch-deadlock-repair-20260917.md。本次不再实施此前扩大范围的统一锁协议、公平排队及界面改造；不新增锁/表/配置，不更改协议重发或业务换群规则。

## 验证

TDD 有效红灯：9 tests，1 failure（父任务 UPDATE 仍查询执行表），0 errors。

最终命令：

```bash
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home \
mvn -q -f armada-api/pom.xml \
  -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar \
  -Dtest=PullTaskExecutionSlotMapperTest,PullTaskExecutionTransactionServiceTest,PullTaskResourceRecoveryTransactionIntegrationTest,PullTaskExecutionDispatchCoordinatorTest test
```

退出码 0：57 tests，0 failures，0 errors，0 skipped（10 + 11 + 26 + 10）。真实 Mapper/H2/Spring 事务验证快照检查与版本竞争、两个事务的等待/提交、回滚和租户隔离。XML 与本次生产 diff 的空白检查通过。

日志：/private/tmp/pull-slot-red-20260918.log、/private/tmp/pull-slot-final-20260918.log。H2 不能证明 InnoDB 死锁已消失；本次不运行测试环境验证或全量 Maven 测试。

## 发布、回滚及剩余边界

未远程访问、未部署、未改业务数据。既有已人工结束 #26 不重启。发布时仅选授权的提交范围，避免带入同仓其他在途修改。回滚只回退本次两个生产文件的差异，无数据迁移。

并发上限回归覆盖自动首次启动和资源恢复。人工补充管理员存在绕过该名额方法的既有恢复路径，本次未扩展修改，不能把本次结果宣称为全入口并发门禁验收。未来须单独对账；具体代码边界见设计文档。
