# 变更记录：超链任务账号画像筛选后端前置

- 日期 / 分支 / worktree: 2026-08-29 / `codex/hyperlink-task-account-profile` / `.codex-worktrees/hyperlink-task/armada-account-profile`
- 需求来源: `docs/business/hyperlink-marketing-data-model.md` §8、超链任务公共契约与 H2 表单设计
- 状态: 已完成（未部署）

## 目标（一句话）

落地共享 `account_profile` 画像事实、按事实水位幂等写入，并让超链账号候选及数量查询完整支持已冻结画像条件。

## 缺口拆解 / 任务清单

- [x] Flyway 新建且仅新建共享 `account_profile`，并补账号组合筛选索引。
- [x] 在 account 域提供好友数、拉群权限、轮号、注册时间和五类营销来源的最小写入缝。
- [x] 各异步事实按独立水位更新，旧事件和同水位冲突事件不得覆盖已落事实。
- [x] 候选与数量 SQL 下推画像筛选，未知画像不匹配已配置条件，保持显式租户隔离。
- [x] 移除任务域对已有画像能力的 fail-closed 拒绝，保留枚举、范围和白名单校验。
- [x] 真实 H2 Mapper/XML、Flyway 结构与聚焦业务回归通过。

## 关键设计决策

- `account_profile` 是 10 张超链任务表之外的 account 域共享一对一画像聚合，不把高频画像事实塞回身份主表。
- 好友数、拉群权限、轮号、营销来源分别使用冻结的独立同步/更新时间作水位；只有时间严格更新的事件可覆盖，同水位重复或冲突事件保持先到值。
- 冻结模型没有 `registered_at_updated_at`；注册时间是低频静态来源事实，写入缝采用首次已知值胜出，避免用整行 `updated_at` 冒充独立水位并错误阻塞其他事实。
- `NULL` 一律表示未知。只有未配置相应筛选时未知画像才可进入候选；未来注册时间也不参与号龄筛选。不做默认值、自动回填或三类 `number_source` 到五类 `marketing_source` 的伪映射。
- 写入 SQL 通过当前租户的有效 `account` 行 `INSERT ... SELECT`，不允许跨租户账号 ID 生成画像孤儿行。
- 候选列表和数量统计复用同一个 MyBatis SQL 片段，账号试算不会与正式圈号形成第二套条件。

## 影响

- 数据库: 新增共享 `account_profile`；新增 `account.idx_account_hyperlink_platform` 组合索引；不回填历史画像。
- API: 不新增 HTTP 接口；扩展 account 域候选 Service 的画像查询和数量统计能力。
- Redis: 无变更。
- 回滚: 停止画像写入和超链画像筛选后，执行 `rollback.sql` 删除组合索引与画像表；画像数据会丢失。

## 验证（evidence-before-done）

- JDK 17 执行 `mvn -q -DargLine=<byte-buddy-agent> -DskipTests=false -Dtest='*Hyperlink*Test,AccountProfile*Test,FlywayMigrationVersionContractTest,FlywayMigrationSqlContractTest,FlywayMigrationHistoryContractTest' test`：52 个测试类、209 个用例，0 failure / 0 error / 4 skipped。
- 真实 H2 Mapper/XML 覆盖：五类画像字段、范围边界、`NULL` 语义、`false` 布尔条件、租户隔离、旧/同水位不覆盖，以及未知/未来注册时间不误命中。
- 候选列表与数量统计复用同一 `hyperlinkFilteredCandidateRows` SQL 片段，并在 H2 同时断言 select/count 结果。
- `xmllint --noout` 校验 `AccountMapper.xml` 与 `AccountProfileMapper.xml` 通过；`git diff --check` 通过。

## 部署

- commit / 环境 / 部署后验证结果: 不部署，不连接真实数据库或远程环境。

## 遗留 / 跟进

- Web/Android 协议侧好友数、拉群权限采集及轮号/号源事件接线不在本变更范围，完成前超链任务仍有外部数据覆盖率依赖。
- `.harness/wiki/数据模型.md` 只能从真实 `information_schema` 转储生成；本地未连接真实数据库，因此本变更不手工改写该自动生成文件。
