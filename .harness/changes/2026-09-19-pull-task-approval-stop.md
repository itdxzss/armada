# 变更记录：新群链接遇入群审批立即换群

- 日期：2026-09-19
- 分支 / worktree：主仓 1.0.3-snapshot；保留既有在途改动。
- 需求来源：用户要求“这个群就不可用了……和其他群组封禁策略一样，料子继续换群组拉”。
- 状态：主仓实现与聚焦验证完成；未部署。

## 目标与范围

DIRECT_LINK 的拉手入群明确返回 PENDING_APPROVAL 时，立即终止当前群，释放拉手，并沿用封群的整份 TXT 换群策略。已存在的待审批执行由下一轮持租约调度收尾，不要求群 JID 或拉手在线。旧模式管理员审批、一般 UNKNOWN 和结果未返回仍遵循原合同。

## 实现

- 执行行写 FAILED / GROUP_JOIN_APPROVAL_REQUIRED，原因显示“群入群需要审批，已停止本群执行”。协议动作及成员申请仍保留 PENDING_APPROVAL，不伪造群封禁或申请被拒。
- 在同一事务内释放拉手并调用原 ParentCompletion / GroupRetry 链路：旧群移出来源分组，同一 TXT 创建唯一下一轮，回到 DIRECT_PULLER_JOIN，提交后唤醒调度。
- 已知审批等待不再通过成员查询或迟到的批准回调恢复；已结束的审批群不再参与未知结果扫描。重复回调不能重复建立下一轮。
- 来源分组耗尽时沿用现有等待群资源行为；只粘贴链接而没有来源分组的旧流程边界不变。

## 数据与接口

无表结构、Mapper SQL、API、Redis 或协议契约变更。仅新增执行原因码，复用现有执行终态、released_at、attempt_no 及换群复制 SQL。生产依然使用 MySQL/MyBatis。

## 验证

- 在 /private/tmp/armada-approval-stop-20260919/armada-api 固定快照使用 JDK 17 + Byte Buddy agent 运行聚焦测试，避免共享 target 干扰。
- 红灯：旧实现 60 项中 4 个断言失败、2 个缺少后继执行错误，明确覆盖审批不结束、迟到回调写入和未换群；成员核验的补充测试也复现旧路径重新确认审批成员。
- 绿灯：6 个聚焦测试类共 90 项，失败 0、错误 0、跳过 0，Maven 退出码 0；其中生命周期 28、拉手入群事务 13、执行链集成 17。日志：/private/tmp/armada-approval-stop-20260919/green.log。
- 命令：`JAVA_HOME=<本机 JDK 17> mvn -q -DargLine=-javaagent:<byte-buddy-agent-1.14.19.jar> -Dtest=PullTaskManagerJoinResultServiceImplTest,PullTaskPullerInviteTransactionIntegrationTest,PullTaskStandardExecutionLifecycleServiceTest,PullTaskUnknownResultReconciliationCoordinatorTest,PullTaskExecutionEndToEndIntegrationTest,PullTaskUnknownResultReconciliationServiceTest test`。
- `git diff --check` 通过；逐文件核对主仓与测试快照一致。人工检查本任务增量：沿用原事务/CAS、换群服务和租户 Mapper；未覆盖开始前的在途修改。
- H2 使用真实 Mapper、租户插件与事务验证：审批回调至同料子下一轮的完整链路、唯一后继、其他执行行不受影响、空群 JID / 离线拉手的历史收尾，以及收尾失败时状态与释放一起回滚。

## 部署与回滚

未提交、未推送、未部署，未连接真库或操作第二套在跑任务。本次仅完成主仓行为修复。回滚只撤销本任务 diff，不覆盖既有改动；已生成的下一轮仍沿用当前 DIRECT_LINK 模型，无反向数据迁移。
