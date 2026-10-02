# 变更记录：管理员不可用时从冻结分组自动换号

- 日期 / 分支 / worktree: 2026-09-17 / 1.0.3-snapshot / 主仓库 armada
- 需求来源: 用户要求受限、离线、封禁等不可用管理员自动从选定分组取号；明确主仓库修改、不部署。
- 状态: 已完成（本地验证；未部署）

## 目标
普通群链接拉群在管理员不可用时自动换号，保留失败历史，并沿用正常进群、提权和后续执行链路。

## 缺口拆解 / 任务清单
- [x] 管理员资格统一核对在线、生命周期、协议身份与风险/操作限制。
- [x] WAIT_RESOURCE/MANAGER 按冻结 manager_group_id 选本执行行未尝试账号，移除旧角色并新增自动补充角色。
- [x] 多角色历史下正确选择现任管理员，旧角色回执不得推进执行行。
- [x] 覆盖真实 H2 Mapper/事务回归和账号域资格过滤。

## 关键设计决策
- 无新增表、列、API、Redis。使用已有 source=SUPPLEMENT、selection=AUTOMATIC、availability=REMOVED。
- 自动补充走初始管理员的持久化 outbox 入群和正常提权链路；人工补充路径保留。
- 不跨选定分组，不重复使用本执行行已选择过的账号；全部耗尽继续等待，分组新增健康号后恢复。
- 未知结果、等待审批且账号仍健康时不因“未进群”换号；缺少提权执行人时也不能靠盲目换目标管理员解决。
- 替换后回到 MANAGER_JOIN，后续用既有动作幂等记录继续；暂停/终止与并发槽位仍按原规则。
- 回滚仅撤销本次补丁，保留其他会话在途修改。无共享库变更。

## 验证
- 原缺陷红测：`PullTaskResourceRecoveryTransactionIntegrationTest#failedManagerIsReplacedFromConfiguredGroupWithoutDeletingHistory`，真实 H2 下 expected ADVANCED / actual DEFERRED。
- JDK 17 + Byte Buddy 显式 javaagent 运行 13 个聚焦测试类，累计 128 个测试，0 failure / 0 error / 0 skipped；含账号资格 H2、恢复事务 H2、普通进群/提权/联系人/邀请/料子管理员及端到端闭环。
- 新增 Web / Android / MIXED 自动替换端到端用例均完成父任务 COMPLETED，旧角色保留并 REMOVED，替补角色已进群且管理员成功，真实 outbox 命令路由与回执收口通过。
- `xmllint --noout armada-api/src/main/resources/mapper/account/AccountMapper.xml` 与本次文件 `git diff --check` 通过。
- 本机 JDK 23 下 Mockito 自附加失败是测试运行器问题；改用项目要求 JDK 17 和已有 javaagent 后完成验证。
- 设计见 `docs/business/pull-task-manager-auto-replacement-20260918.md`。日志保存在 `/private/tmp/manager173-perf2-diag/`。
- 主要运行命令：`JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -q -f armada-api/pom.xml -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='PullTaskResourceRecoveryTransactionIntegrationTest,AccountPullerEligibilityMapperH2Test,PullTaskManagerJoinTransactionServiceTest,PullTaskManagerJoinTransactionIntegrationTest,PullTaskManagerAdminTransactionIntegrationTest,PullTaskSupplementManagerTransactionIntegrationTest,PullTaskManagerPullerContactTransactionIntegrationTest,PullTaskPullerInviteTransactionIntegrationTest,PullTaskMaterialAdminTransactionIntegrationTest,PullTaskManagerJoinResultServiceImplTest,PullTaskManagerAdminResultServiceImplTest,PullTaskExecutionEndToEndIntegrationTest,AccountProtocolLookupServiceTest' test`。后续新增三协议替补端到端用例，单独复跑端到端类与进群/提权回执类通过；128 为各类最新报告总数。
- 当前主仓库存在其他会话改动；仅增量修改本需求相关文件，未提交、未推送。

## 部署
按用户要求不部署；不恢复或重启已结束的远程任务。

## 遗留
无线上业务验收；仅本地代码和测试交付。
