# 本地验证结果

2026-09-19；主仓源码，JDK 17 / Byte Buddy agent，隔离临时目录运行。

| 测试类 | 执行 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| GroupDataPackageTaskProjectionInMemoryTest | 16 | 0 | 0 | 0 |
| GroupDataPackageTaskResourceH2Test | 5 | 0 | 0 | 0 |
| MultipartUploadConfigurationTest | 3 | 0 | 0 | 0 |
| PullTaskClosingTransactionServiceTest | 4 | 0 | 0 | 0 |
| PullTaskCreatorLeaveProcessorTest | 5 | 0 | 0 | 0 |
| PullTaskDirectLinkContractTest | 3 | 0 | 0 | 0 |
| PullTaskDirectLinkControllerTest | 1 | 0 | 0 | 0 |
| PullTaskDirectLinkCreateInMemoryTest | 5 | 0 | 0 | 0 |
| PullTaskDirectLinkCreateServiceTest | 5 | 0 | 0 | 0 |
| PullTaskDirectLinkFinishArchiveInMemoryTest | 5 | 0 | 0 | 0 |
| PullTaskDirectLinkMigrationSqlTest | 1 | 0 | 0 | 0 |
| PullTaskDirectLinkPlannerTest | 3 | 0 | 0 | 0 |
| PullTaskExecutionDispatchCoordinatorTest | 10 | 0 | 0 | 0 |
| PullTaskExecutionEndToEndIntegrationTest | 17 | 0 | 0 | 0 |
| PullTaskExecutionTransactionServiceTest | 12 | 0 | 0 | 0 |
| PullTaskFactStatisticsSqlTest | 4 | 0 | 0 | 0 |
| PullTaskGroupExecutionMapperInMemoryTest | 28 | 0 | 0 | 0 |
| PullTaskGroupSettingsApplyTimingIntegrationTest | 8 | 0 | 8 | 0 |
| PullTaskManagerJoinResultServiceImplTest | 18 | 0 | 0 | 0 |
| PullTaskManagerPullerContactTransactionIntegrationTest | 19 | 0 | 0 | 0 |
| PullTaskManagerSupplementServiceTest | 5 | 0 | 0 | 0 |
| PullTaskMaterialTxtParserTest | 8 | 0 | 0 | 0 |
| PullTaskPullWavePlanningIntegrationTest | 19 | 0 | 0 | 0 |
| PullTaskPullerInviteProcessorTest | 2 | 0 | 0 | 0 |
| PullTaskPullerInviteTransactionIntegrationTest | 12 | 0 | 0 | 0 |
| PullTaskPullerSupplementServiceTest | 12 | 0 | 0 | 0 |
| PullTaskResourceRecoveryTransactionIntegrationTest | 27 | 0 | 0 | 0 |
| PullTaskStandardCreateServiceTest | 23 | 0 | 0 | 0 |
| PullTaskStandardExecutionLifecycleServiceTest | 25 | 0 | 0 | 0 |
| PullTaskStandardReadMapperInMemoryTest | 11 | 0 | 0 | 0 |
| PullTaskStandardStartServiceTest | 5 | 0 | 0 | 0 |
| PullTaskStationSelectionContactIntegrationTest | 7 | 0 | 0 | 0 |
| PullTaskUnifiedListMigrationTest | 3 | 0 | 0 | 0 |
| PullTaskUnknownResultReconciliationCoordinatorTest | 1 | 0 | 0 | 0 |
| PullTaskUnknownResultReconciliationServiceTest | 12 | 0 | 0 | 0 |

共 344 项：336 项通过；8 项旧测试因缺少既有投影服务 bean 不能初始化。该 8 项已在未修改 HEAD 0bb0a688 复现，不是新模式回归。

新旧执行端到端场景使用真实 H2 Mapper/事务与协议替身；未进行真实 WhatsApp 操作。

前端：151 项 Node 测试、2 项本地 API 拦截浏览器测试、类型检查、lint/stylelint/prettier 和 Vite 生产构建通过。

附加检查：Mapper XML、两仓 diff-check、接口文档生成 1 项通过。

预期红灯证据：旧补管理入口允许 direct、并发 auto-start 抛冲突、原上传容量不足；对应绿测均通过。
