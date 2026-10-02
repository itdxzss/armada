# 变更记录：普通拉群群级失败换群重拉

- 日期 / 分支 / worktree：2026-09-17，1.0.3-snapshot，主仓库 `/Users/daishuaishuai/IdeaProjects/armada`。
- 需求来源：用户要求在原有封群换群行为上增加邀请码无效、邀请码撤销、非法群链接、群不可用。
- 状态：本地实现与聚焦测试完成；未提交、未推送、未部署。

## 目标与实现

将 `GROUP_BANNED`、`INVITE_INVALID`、`INVITE_REVOKED`、`INVALID_GROUP_LINK`、`GROUP_UNAVAILABLE` 的失败执行统一接入换群。邀请码仍优先尝试刷新恢复，最终失败后才换群。协议层已经归一到 `GROUP_UNAVAILABLE` 的群满结果也沿用同一处理。

- 抽取原有整份 TXT 复制逻辑到 `PullTaskGroupRetryService`，在执行终态聚合前创建后继执行，避免父任务先完成。
- 原执行行锁 + 查询后继轮次 + 既有唯一约束保证重复回调不重复创建执行。查询使用真实 Mapper XML，复用现有 `(tenant_id, task_id, seq, attempt_no)` 索引。
- 复制整份料子，重置执行进度，从管理员进群阶段启动；次数持续递增，没有新增上限。候选群分配及无群等待沿用原调度逻辑。
- 仅普通群链接任务且选择来源分组时生效。暂停父任务生成待启动行但不唤醒；已结束、已完成父任务不自动重启；纯手工链接、新群模式、非指定原因不扩大范围。
- 保留现有失败清理及迟到结果入口；数据包投影在后继行创建后执行，复制/投影失败随原事务回滚。
- 调度唤醒器采用延迟注入，避免终态聚合服务与调度阶段路由形成构造循环。

## 影响与回滚

- 后端普通拉群生命周期、父任务终态聚合、执行行 Mapper 和相关测试。
- 无表结构、API、Redis、前端、协议层变更。
- 回滚本变更的代码并重新构建部署；不删除已有执行记录。当前尚未操作远程环境或存量任务。

## 验证

1. 新增四个错误码的 H2 回归用例，改生产代码前均因缺失后继执行行而失败（`EmptyResultDataAccessException`）。
2. JDK 17 + 显式 Byte Buddy agent 运行下述测试，退出码 0：75 tests，0 failures，0 errors，0 skipped。

```bash
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home \
mvn -q -f armada-api/pom.xml \
  -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar \
  -Dtest='PullTaskStandardExecutionLifecycleServiceTest,PullTaskGroupBanTerminationServiceTest,PullTaskGroupExecutionFailureServiceTest,PullTaskManagerJoin*Test,PullTaskStandardLifecycleServiceTest,PullTaskExecutionEndToEndIntegrationTest' test
```

3. H2 加载真实 Mapper XML、租户插件、Spring 事务，覆盖整份料子复制、连续生成第 2/3/4 次执行、重复回调、两个独立事务的行锁等待与唯一后继、失败回滚、暂停父任务、已结束/已完成父任务及无来源分组边界。
4. 修正旧端到端测试 `revokedInviteWithKnownGroupJidCurrentlyNeverRefreshesInvite` 的过时断言：现有生产逻辑已经优先刷新邀请码，测试现在验证 Web / Android 使用新邀请码成功进入管理员权限阶段，不再断言旧的反复等待资源缺陷。
5. Mapper XML 解析、`git diff --check` 通过。

首次测试使用系统默认 JDK 23 时，Mockito 自附加被运行环境阻止；改用项目要求的 JDK 17 并显式加载 agent 后正常执行，未修改项目依赖。未运行完整 Maven 全量测试、真实 WhatsApp 业务验收或 MySQL InnoDB 并发验证；H2 并发结果不替代 InnoDB 验收。
