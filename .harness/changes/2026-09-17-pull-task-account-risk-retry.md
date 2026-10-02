# 变更记录：拉手受限后原群局部补拉

- 日期 / 分支 / worktree：2026-09-17，`1.0.3-snapshot`，`/Users/daishuaishuai/IdeaProjects/armada` 主仓库。
- 需求来源：本会话确认仅修复代码，不处理旧任务；账号限频/触达受限后换健康拉手重试未成功人员。
- 状态：代码修复和本地验证完成，尚未提交、推送或部署。

## 范围

只放开 `UNKNOWN + UNCERTAIN` 且原批回执原因是 `RATE_LIMITED` / `ACCOUNT_REACHOUT_RESTRICTED` 的有界重试。原始协议事实仍保留 UNKNOWN，不伪造为未开始或明确失败。沿用最多四次（含首次）、60/120/240 秒波次间隔及健康拉手选择。没有资源进入等待，重试结束前保持拉人执行阶段。

不新增自动群成员查询，不扩大调用后封禁/离线/超时重试，不处理成员隐私失败，不迁移或恢复历史终态任务，不修改其他会话的群级换群实现。

## 实现清单

- [x] 复现回调已换号但料子未进入重试队列。
- [x] 统一回调、Mapper 候选和待执行归一化的受限重试条件。
- [x] 验证预算、等待资源、最终收口、重复/迟到回调和成功保护。
- [x] 聚焦测试、真实 Mapper H2 测试、XML 与差异校验。

回调将符合条件的 UNKNOWN attempt 标为 RELEASED，料子/站台回到待执行；原始 outcome、executionState、reason 均保留且不增加明确失败计数。候选 SQL 只接受 RELEASED 的受限记录，历史 CLOSED 不重新开启；归一化不会将有效的待重试人员改回终态。原因码筛选与回调保持大小写/空白归一一致，空原因仍按普通未知处理。

复用现有波次、调用、参与者台账和健康拉手选择；新补拉使用新 commandId，迟到成功可裁剪尚未提交的计划，旧失败不能覆盖成功。已提交的新请求无法由迟到回执撤回，不能把这项有界恢复策略理解为协议 exactly-once 保证。预算耗尽仍无确认结果时保留 UNKNOWN。

## 影响与回滚

后端任务重试策略、逐人回调、波次候选与投影归一化；无协议契约、前端、API、表结构或 Redis 变更。回滚代码不得删除已发命令和历史回执；原始未知事实保留。提交/部署分别待用户指令。

## 验证

测试使用 JDK 17（`ms-17.0.19`）及本机 Byte Buddy agent。测试仅连接 H2，不访问远程环境或实际拉人。

1. RED：修正协议投递序号测试夹具后，原实现运行两个目标类共 55 项，8 失败、0 错误、0 跳过；确认受限人员未释放、未创建重试波次及提前收口。
2. 聚焦验证：两类测试扩展至 58 项后全部通过，覆盖料子与站台真实回调/Mapper/调度、四次实际调用的 60/120/240 秒间隔、无健康资源、重复/迟到成功、历史结果保护。
3. 扩展回归：主仓库共享 `target/classes` 在运行期间消失导致 NoClassDefFound，故把当前 `pom.xml` 和 `src` 原样复制到临时目录独立验证，未更改主仓库构建配置。首次隔离回归仅一条旧策略断言不匹配，已更新为新白名单规则，并保留通用未知/历史 CLOSED 排除断言。
4. 最终隔离回归命令：

```bash
JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home \
mvn -q \
  -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar \
  '-Dtest=PullTaskPullCallParticipantResultServiceTest,PullTaskAccountRiskRetryIntegrationTest,PullTaskRetryPolicyTest,PullTaskParticipantResultRecoveryH2Test,PullTaskPullWave*Test,PullTaskPullCallMemberAttemptMapperInMemoryTest,PullTaskMaterialMemberMapperInMemoryTest,PullTaskGroupAccountMapperInMemoryTest,PullTaskSuccessfulAttemptCleanupH2Test,PullTaskUnknownResult*Test,ProtocolPullTaskBatchParticipantResultAdapterTest' test
```

退出码 0；19 个测试类、169 项测试，0 失败、0 错误、0 跳过。其中新 H2 集成类 17 项全部通过；调度异常隔离用例中的 ERROR 日志为预期注入，不是测试失败。

验证快照：`/var/folders/m9/6_vkj38n117c5rfr0cjw8zg40000gn/T/armada-risk-retry-s_ek0n6t`。日志：`/tmp/armada-risk-retry-regression-isolated.log`。完成后逐字节确认 7 个生产文件和 3 个测试文件均与主仓库一致。

3 个修改的 Mapper XML 通过 `xmllint --noout`，`git diff --check` 通过。人工检查没有新增跨域 Mapper 依赖、生产 mock、表列/索引或接口契约；保留执行行事务锁及租户过滤。未运行全仓测试、真实 MySQL 或现场业务验收。
