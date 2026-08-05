# 变更记录：营销任务导出群链接、退出方式与进群事实修正

- 日期 / 分支 / worktree: 2026-08-05 / `codex/simple-whatsapp-group-member-export` / 主工作树
- 需求来源: 用户反馈营销任务导出中的群链接、退出方式、进群时间与累计进群数不准确
- 状态: 已完成代码与专项回归，未提交、未部署

## 目标（一句话）

营销任务导出只展示可打开的 WhatsApp 邀请链接，无法证实的退出原因保持未知，并让 Android LID 成员通过稳定 JID 与手机号别名正确关联进群时间和累计人数。

## 缺口拆解 / 任务清单

- [x] 查明 `wa://group/<JID>` 被当作邀请链接导出的数据来源。
- [x] 导出按真实邀请链接快照生成标准 `https://chat.whatsapp.com/<code>`，无真实链接时留空。
- [x] 阻止发送结果回填用内部 `wa://` 覆盖已有真实邀请链接。
- [x] 查明 WGP2 `remove` 缺少足够证据却被映射为“被移出群”。
- [x] 兼容新旧 WGP2 事件，将不确定退出原因导出为“退出原因未识别”。
- [x] 查明 LID-only add 事实无法与 PN/手机号成员匹配，是进群时间为空和累计人数为 0 的共同原因。
- [x] Android 在上报前使用可信 LID→PN 映射；映射失败时保持 LID 未知，禁止伪造手机号。
- [x] Android 群成员 HTTP 响应保留原始 `jid` 和可选 `phone_number`，Java 快照与实时事件统一以 LID 为稳定身份。
- [x] 完成后端与 Android 专项正向测试及 Java、SQL、Go 专家评审。

## 关键设计决策

- `wa://group/<JID>` 是内部稳定定位符，不是用户可分享的邀请链接，任何导出分支都不得回退输出该值。
- 邀请链接优先读取拉群执行快照和营销目标快照，其次使用预览邀请 code 或本身已是标准 HTTPS 邀请链接的群组池值。
- 实时 WGP2 `remove` 未携带可证明管理员移除的操作者/原因，统一按 `UNKNOWN`；HistorySync 明确给出的 `REMOVED` 仍可展示“被移出群”。
- 滚动发布兼容历史 `WGP2_NOTIFICATION + REMOVED`：该组合与 `UNKNOWN` 同属不明确证据，不能压过同一时间点的 HistorySync 明确事实。
- 退群事实使用“先保证身份并单调补手机号、再以单条 `UPDATE ... WHERE winner` 整体更新事件”的两阶段事务写入，避免 MySQL `ON DUPLICATE KEY UPDATE` 左到右赋值产生混合事实。
- Java 端不得把 LID 数字当手机号；事实表和成员缓存都以稳定原始 LID 为主键，可信 PN 只作为 phone alias。映射从 miss 变为 hit 时只补手机号，不把同一成员拆成 LID/PN 两条状态。
- Android 群成员 HTTP 响应必须同时保留原始 `jid` 与可选 `phone_number`；完整快照保留 unresolved LID，不能过滤后再标记缺失，否则会破坏成员数与在群状态。

## 验证（evidence-before-done）

- Armada 聚焦正向回归：

  ```bash
  mvn -q '-Dtest=ProtocolAccountEventConsumerTest,ProtocolGroupJoinSinkImplTest,ProtocolGroupDepartureSinkImplTest,AndroidGroupMemberMapperTest,AndroidNativeFixedAccountGroupMetadataAdapterTest,AccountGroupMembershipStatusServiceImplTest,WhatsappGroupDepartedMemberServiceImplTest,WhatsappGroupMemberCacheServiceImplTest,WhatsappGroupMemberCacheMapperH2Test,WhatsappGroupDepartedMemberMapperH2Test,WhatsappGroupMemberJoinFactMapperH2Test,MarketingTaskWhatsAppMemberProviderTest,MarketingTaskExportSqlContractTest,MarketingTaskMapperSqlShapeTest,AccountGroupMembershipMapperSqlTest,MysqlModeMapperInMemoryTest,FlywayAppliedMigrationCompatibilityTest,FlywayMigrationHistoryContractTest,FlywayMigrationSqlContractTest,FlywayMigrationVersionContractTest' test
  ```

  结果：20 个测试类，`Tests run: 117, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。

- `mvn -q -DskipTests verify` 退出码为 0。
- Android WGP2 与 `api/service` 定向测试通过；Linux 目标 `go vet`、`go build` 通过；Windows 原生测试受仓库既有 Linux-only syscall 限制，采用 Linux 交叉编译后在 WSL 实际执行测试。
- Armada 与 Android 仓库 `git diff --check` 均通过，仅有工作区 LF/CRLF 提示。
- `WhatsappGroupDepartedMemberMapperMysqlTest` 已补 MySQL 8.4 Testcontainers 行为用例；本机 Docker 探测超时，未取得真实容器执行结果，当前以 H2 SQL 契约、Java 回归和专家静态审查为门禁。

## 部署

- commit / 环境 / 部署后验证结果: 未提交、未部署。
- 兼容顺序要求：先部署可接收 `UNKNOWN` 的 Armada，再滚动部署三台 Android 节点。

## 遗留 / 跟进

- 历史 LID-only 进群事实无法从 LID 数字安全推断手机号；需要重新产生或可靠重放真实 add 事件。
- 本次修复上线前已经形成的 LID/PN 双行不会自动批量合并；若存量数据已受影响，需单独设计带时序仲裁的 alias merge/migration，禁止无条件删除。
- 导出失败原因的既有 `GROUP_CONCAT` 仍受 MySQL `group_concat_max_len` 限制，属于本次三个问题之外的历史技术债。
