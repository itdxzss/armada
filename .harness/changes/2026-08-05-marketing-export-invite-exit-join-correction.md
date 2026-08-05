# 变更记录：营销任务导出群链接、退出方式与进群事实修正

- 日期 / 分支 / worktree: 2026-08-05 / `codex/simple-whatsapp-group-member-export` / 独立 worktree
- 需求来源: 用户再次明确营销任务导出中的群链接、空链接和退出方式规则，以本次逻辑为准
- 状态: 本次修订开发、验证、提交与推送完成，未部署

## 目标（一句话）

营销任务导出按当前在群账号实时读取 WhatsApp 邀请链接，读取失败统一展示“无权限获取”；主动退群、明确移除和无法识别严格三态展示。

## 缺口拆解 / 任务清单

- [x] 查明 `wa://group/<JID>` 被当作邀请链接导出的数据来源。
- [x] 导出使用当前在群账号实时查询邀请链接，只接受标准 `https://chat.whatsapp.com/<code>`。
- [x] 实时查询失败时正常生成导出，并将群链接统一填写“无权限获取”，不再留空。
- [x] 即使历史快照中存在标准链接，本次无权限获取时也不使用历史值兜底。
- [x] 群名称缺失时显示“未命名群组”，群链接和群名称均不使用群 JID 兜底。
- [x] 阻止发送结果回填用内部 `wa://` 覆盖已有真实邀请链接。
- [x] 查明 WGP2 `remove` 缺少足够证据却被映射为“被移出群”。
- [x] 兼容新旧 WGP2 事件，将不确定退出原因导出为“退出原因未识别”。
- [x] 系统主动退群命令成功后直接记录 `LEFT / BUSINESS_COMMAND`，不依赖含义不明确的后续 remove 通知推断。
- [x] 导出展示统一为 `主动退群`、`被移出群组`、`退出原因未识别`。
- [x] 查明 LID-only add 事实无法与 PN/手机号成员匹配，是进群时间为空和累计人数为 0 的共同原因。
- [x] Android 在上报前使用可信 LID→PN 映射；映射失败时保持 LID 未知，禁止伪造手机号。
- [x] Android 群成员 HTTP 响应保留原始 `jid` 和可选 `phone_number`，Java 快照与实时事件统一以 LID 为稳定身份。
- [x] 完成后端与 Android 专项正向测试及 Java、SQL、Go 专家评审。

## 关键设计决策

- `wa://group/<JID>` 是内部稳定定位符，不是用户可分享的邀请链接，任何导出分支都不得回退输出该值。
- SQL 中的历史邀请链接只作为内部参考；本次导出最终值必须由当前在群候选账号通过协议端口实时确认，失败即写“无权限获取”。
- 实时 WGP2 `remove` 未携带可证明管理员移除的操作者/原因，统一按 `UNKNOWN`；HistorySync 明确给出的 `REMOVED` 展示“被移出群组”。
- 业务端通过系统成功执行的主动退群命令本身就是明确证据，立即写入 `LEFT / BUSINESS_COMMAND` 事实并同步成员缓存。
- 滚动发布兼容历史 `WGP2_NOTIFICATION + REMOVED`：该组合与 `UNKNOWN` 同属不明确证据，不能压过同一时间点的 HistorySync 明确事实。
- 退群事实使用“先保证身份并单调补手机号、再以单条 `UPDATE ... WHERE winner` 整体更新事件”的两阶段事务写入，避免 MySQL `ON DUPLICATE KEY UPDATE` 左到右赋值产生混合事实。
- Java 端不得把 LID 数字当手机号；事实表和成员缓存都以稳定原始 LID 为主键，可信 PN 只作为 phone alias。映射从 miss 变为 hit 时只补手机号，不把同一成员拆成 LID/PN 两条状态。
- Android 群成员 HTTP 响应必须同时保留原始 `jid` 与可选 `phone_number`；完整快照保留 unresolved LID，不能过滤后再标记缺失，否则会破坏成员数与在群状态。

## 验证（evidence-before-done）

- 本次 Armada 聚焦正向回归覆盖协议退出事件、成员事实与缓存、导出、数据库契约和拉群主动退群流程：

  ```bash
  mvn -q '-Dtest=ProtocolAccountEventConsumerTest,ProtocolGroupJoinSinkImplTest,ProtocolGroupDepartureSinkImplTest,AndroidGroupMemberMapperTest,AndroidNativeFixedAccountGroupMetadataAdapterTest,AccountGroupMembershipStatusServiceImplTest,WhatsappGroupDepartedMemberServiceImplTest,WhatsappGroupMemberCacheServiceImplTest,WhatsappGroupMemberCacheMapperH2Test,WhatsappGroupDepartedMemberMapperH2Test,WhatsappGroupMemberJoinFactMapperH2Test,WhatsappGroupBusinessDepartureServiceTest,MarketingTaskWhatsAppMemberProviderTest,MarketingTaskExportSqlContractTest,MarketingTaskMapperSqlShapeTest,MysqlModeMapperInMemoryTest,FlywayAppliedMigrationCompatibilityTest,FlywayMigrationHistoryContractTest,FlywayMigrationSqlContractTest,FlywayMigrationVersionContractTest,GroupPullMarketingExecutionWorkerTest,GroupPullMarketingFirstMaterialDelayTest,GroupPullMarketingInviteCaptureTest,GroupPullMarketingSchedulerTest' test
  ```

  结果：24 个测试类，`Tests run: 134, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。

- `mvn -q -DskipTests verify` 退出码为 0。
- `git diff --check` 通过，仅有工作区 LF/CRLF 提示。
- 扩展运行 140 项时，139 项通过；唯一失败为目标分支既有的 `AccountGroupMembershipMapperSqlTest` 第 39 行断言仍要求 `membership_status IN (1, 2)`，而未改动的当前 SQL 已是 `membership_status = 1`，与本次变更无关，故未扩大范围修改。
- `WhatsappGroupDepartedMemberMapperMysqlTest` 已补 MySQL 8.4 Testcontainers 行为用例；本机 Docker 探测超时，未取得真实容器执行结果，当前以 H2 SQL 契约、Java 回归和专家静态审查为门禁。

## 部署

- commit / 环境 / 部署后验证结果: `c493419c` 已推送至远端目标分支，未部署。
- 兼容顺序要求：先部署可接收 `UNKNOWN` 的 Armada，再滚动部署三台 Android 节点。

## 遗留 / 跟进

- 历史 LID-only 进群事实无法从 LID 数字安全推断手机号；需要重新产生或可靠重放真实 add 事件。
- 本次修复上线前已经形成的 LID/PN 双行不会自动批量合并；若存量数据已受影响，需单独设计带时序仲裁的 alias merge/migration，禁止无条件删除。
- 导出失败原因的既有 `GROUP_CONCAT` 仍受 MySQL `group_concat_max_len` 限制，属于本次三个问题之外的历史技术债。
