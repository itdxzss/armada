# 变更记录：双写分支群权限设置使用群主账号执行

- 日期 / 目标分支 / worktree: 2026-08-16 / `1.0.3-group` / `D:\idea_project\armada\.codex-worktrees\group-permission-owner-20260816`
- 需求来源: 用户本次对话与群详情截图
- 状态: 已完成并通过专项评审（未部署）

## 目标（一句话）

让群详情中协议已支持的权限开关始终由对应 WhatsApp 群的群主账号执行，并确认该行为在群模型双写阶段不会漏写新模型。

## 双写影响结论

- `1.0.3-group` 当前仍处于“旧模型承载业务读取、旧/新模型同步写入”的阶段；群列表和详情继续使用 legacy `group_link_id`，新模型 reader 尚未接入业务 Service。
- 权限写入的执行账号在协议调用前选定；成功后才由 `persistConfirmedSetting` 更新 `group_link_preview`，并调用 `AccountGroupCurrentSnapshotPersistence.applyConfirmedMetadata` 写入 `wa_group` / `wa_group_profile`。因此把通用执行账号改为精确群主不会改变双写边界。
- 群主选择继续读取 metadata 已确认的 `group_link_preview.owner_phone`，符合当前旧模型业务读源；直接只从 `wa_group_participant` 选群主会漏掉仅经过账号群列表上报、尚未完成 metadata 回填的群。
- 本次没有切换新模型读流，也没有修改双写事务、回填或迁移逻辑。

## 关键设计决策

- “编辑群组设置”开启表示普通群成员也可编辑群资料；关闭表示普通成员不可编辑，但群主和管理员仍可编辑。协议映射为开启 `unlocked`、关闭 `locked`。
- 所有受支持的设置写操作必须根据目标群选择群主账号，不由页面指定，也不回退到其它管理员或普通成员。
- 群主身份以最新 metadata 写入的 `group_link_preview.owner_phone` 为准；账号手机号按去除 `+` 和 JID 后缀后的号码匹配，同时要求账号在线、正常且仍在群内。
- 群主未知、离线、异常或已离群时返回 `GROUP_EXECUTOR_UNAVAILABLE`，不发送可能由错误账号执行的协议请求。
- 写入和 metadata 回读使用同一个群主账号；回读阶段仅真实超时映射为“结果待确认”，权限拒绝等错误继续按原协议错误码返回。
- “通过链接邀请”是独立权限。当前协议 capability 为 unsupported 且没有对应写 API，因此继续禁用，不能复用“添加其他成员”或“入群审批”。

## 测试与证据

- TDD 红灯：生产实现前，目标 worktree 定向 Maven `testCompile` 因缺少 `requireOwner` 和 Mapper 群主查询产生 21 个预期编译错误。
- 定向单元测试：`AccountGroupMembershipMapperSqlTest` 11/11、`GroupExecutionAccountSelectorTest` 12/12、`GroupDetailServiceImplTest` 33/33、`HttpGroupSettingsAdapterTest` 5/5，共 61/61 通过。
- H2 真实 Mapper：`MysqlModeMapperInMemoryTest#groupOwnerExecutionAccountQueryUsesConfirmedOwnerWithoutFallback` 1/1 通过。
- Controller 合同：`GroupLinkControllerTest` 15/15 通过；三组相关门禁合计 77/77 通过。
- `mvn -Dmaven.test.skip=true verify` 通过，生成可执行 Spring Boot jar。
- 全量 `mvn test` 会首先连接仓库配置的外部数据库执行 `AccountSchemaDbTest`；因本次未确认任何真库目标，在反复等待连接时主动终止，未将其冒充为通过证据。
- 群主查询覆盖：本地 `is_admin=false` 的真实群主、另有在线管理员时仍不回退、群主离线不回退、跨租户不可见、手机号含 `+` / JID 后缀，以及没有 preview 行时既有普通/管理员查询仍正常。
- 双写断言覆盖：关闭成员编辑权限后，legacy `admin_only_edit_info=true`，传给 current snapshot persistence 的 `adminOnlyEditInfo=true` 且 observed=true。
- 协议反向语义覆盖：`enabled=false` 必须发送 `locked`。
- Java、数据库/MyBatis、静默失败三项专项评审均无本次阻断或高严重度问题。

## 部署

- 环境 / 部署后验证结果: 未部署；commit 与远端推送结果以本次 Git 交付记录为准。

## 遗留 / 跟进

- 独立“通过链接邀请”若要上线，需先获得真实 WhatsApp/Baileys wire 能力，再补协议 OpenAPI、写接口、metadata 回读和 capability；不能仅解除前端禁用。
- 双写分支既有的 legacy 更新与 current snapshot 写入不在同一外层事务；新模型写入异常时可能出现协议与 legacy 已更新、接口却失败的部分成功。这是现有双写一致性问题，不由本次执行账号优化引入，建议单独治理。
