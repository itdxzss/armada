# 拉群任务统一列表与全局设置

## 变更概述

- 设计与逐步计划见 `../2026-07-31-pull-task-unified-list-global-settings.md`、`../../../docs/superpowers/specs/2026-07-31-pull-task-unified-list-global-settings-design.md`。
- `pull_task` 作为普通拉群与拉群营销的公共任务主表。
- 新增拉群营销任务级统计读取模型和租户级三项全局设置。
- 拉群任务一级页面统一为九列；独立拉群营销旧业务不迁移、不合并。

## 影响模块

- 数据库：`pull_task`、`pull_task_group_marketing_summary`、`pull_task_group_marketing_setting`、`sys_menu`。
- 后端：`com.armada.task` 列表、设置与删除契约。
- 前端：`wheel-saas-pure-web/src/views/task/pull-task/`。

## 数据库变更

- Flyway：`armada-api/src/main/resources/db/migration/V088__pull_task_unified_list_and_global_settings.sql`。
- 前向执行入口：`db-migrations.sql`。
- 审阅用回滚：`rollback.sql`；本地未执行。
- `.harness/wiki/数据模型.md` 是生成物。本地没有经用户确认的真实库结构转储，不手工修改；待确认目标环境并执行 Flyway 后再重新生成。

## API 变更

- `GET /api/pull-tasks`：增加任务类型、群组来源及九列分组数据。
- `GET /api/pull-tasks/group-marketing-setting`：读取当前租户设置，未配置返回空值。
- `PUT /api/pull-tasks/group-marketing-setting`：保存三项全局设置。
- `POST /api/pull-tasks/batch-delete`：按任务类型和状态校验软删。

## Redis 变更

- 无。

## 关键约束

- 统计行缺失表示未知，不能当作零。
- 设置首次无默认值，未配置时阻止进入拉群营销创建流程。
- 当前切片不实现拉群营销提交、执行器或统计生产者，不返回伪造结果。
- 不修改独立 `marketing_task`、`group_pull_marketing_task` 业务。
- 用户要求在当前 checkout 实施，所有改动保持未提交。

## 回滚方案

- 停止应用对新字段和新表的读写后，审阅并执行 `rollback.sql`。
- 回滚会删除拉群营销列表统计和租户全局设置，必须先确认数据是否需要导出。

## 验证

- [x] Flyway SQL 合同测试。
- [x] H2 MySQL 模式 Mapper、零值/未知语义与租户隔离测试。
- [x] 后端 `mvn -Dtest='PullTask*' test`：23/23 通过。
- [x] 后端 `mvn -DskipTests compile`：`BUILD SUCCESS`。
- [x] 前端计划内 Node 测试：38/38 通过。
- [x] 前端 `pnpm typecheck` 与 `pnpm build` 通过。
- [x] 本次前端文件 ESLint、Stylelint、Prettier 检查通过。
- [x] 两个仓库 `git diff --check` 通过，独立拉群营销目录与协议层未纳入本次改动。

## 延期项

- 拉群营销提交接口和任务配置快照落库。
- 任务执行器、暂停/恢复/停止命令和聚合统计生产者。
- 经目标环境确认后的 Flyway 执行、真实库验证和数据模型文档再生成。
