# 超链任务 H1 列表

## 范围

- 新增 `GET /api/hyperlink-tasks` 与 `GET /api/hyperlink-tasks/export`。
- 列表只联表读取 `hyperlink_task`、`hyperlink_task_content`、`hyperlink_task_runtime` 三张 1:1 表。
- 支持任务名、运行状态、任务模式、冻结目标国家、创建时间筛选，固定按 `created_at DESC, id DESC`。
- 仅展示 `provision_status IN (0, 2)` 的完整任务，分页上限 200。
- CSV 固定 26 列、UTF-8 BOM、全筛选导出与浏览器可读附件响应头。
- 新增 H1 列表菜单和 create/edit/action/export 按钮权限，不创建删除或 H6 敏感权限。

## 边界

- 不读取 `hyperlink_task_recipient`，不增加统计表、小时表或缓存表。
- 不实现 H2 编辑器、H4 详情或 H6 趋势内部接口。
- 集成时已将列表菜单迁移顺排为 `V159__hyperlink_task_list_menu_rbac.sql`，避免与账号画像 `V158` 撞号。

## 验证

- 定向测试执行 7 项：生产代码编译通过，首次发现并修复 H2 alias 测试类可见性和测试页码夹具两项问题。
- 相关回归：`mvn -Dtest='com.armada.hyperlink.task.*Test,com.armada.hyperlink.HyperlinkMenuMigrationSqlTest' test`，108 项通过，0 failure/error，4 项 MySQL profile 测试按预期跳过。
- `xmllint --noout` 校验 `HyperlinkTaskMapper.xml` 通过；`git diff --check` 通过。
