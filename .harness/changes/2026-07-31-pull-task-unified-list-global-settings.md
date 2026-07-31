# 变更记录：拉群任务统一列表与全局设置

- 日期 / 分支 / worktree: 2026-07-31 / `1.0.2-snapshot` / 当前 checkout（本地实施）
- 需求来源: 用户确认口径、《拉群营销 PRD V1.2》第 2、3、4、20.2、20.13 节
- 状态: 本地实施完成，未提交、未部署
- 执行约束: 用户明确要求直接在当前前后端 checkout 编码，不创建 worktree，不提交 commit。

## 目标（一句话）

在“拉群任务”菜单实现普通任务与拉群营销任务共用的九列一级列表，并补齐租户级拉群营销全局设置，不混入独立“拉群营销”旧业务。

## 缺口拆解 / 任务清单

- [x] 核对 PRD、现有前端列表、`/api/pull-tasks` 和独立拉群营销数据模型。
- [x] 确认公共主表 + 类型专属统计表方案。
- [x] 确认恢复三个全局设置且首次无默认值。
- [x] 形成设计文档。
- [x] 用户审核并确认书面设计。
- [x] 编写前后端实施计划。
- [x] 通过 TDD 实施数据库、后端接口和前端页面。
- [x] 完成前后端验证与范围审计。

## 关键设计决策

- 现有 `pull_task` 作为公共拉群任务主表，不新建重复的任务索引表。
- 新增 `pull_task_group_marketing_summary` 作为任务级聚合读取模型；真实执行明细仍是事实源。
- 独立 `marketing_task` / `group_pull_marketing_task` 是不同业务，不做运行时合并或历史迁移。
- 一级列表严格保持九个合并列，时间与操作不拆列。
- 全局设置恢复营销静默时间、群组封控时间和单群营销账号上限数量；首次未配置，不硬编码默认值。
- 当前拉群营销创建页没有后端保存能力；本列表切片不伪造任务快照或执行统计。

## 验证（evidence-before-done）

- 后端 `mvn -Dtest='PullTask*' test`：23 项通过，0 失败、0 错误、0 跳过。
- 后端 `mvn -DskipTests compile`：`BUILD SUCCESS`。
- 前端计划内 Node 测试：38 项通过，0 失败、0 跳过。
- 前端 `pnpm typecheck`：通过。
- 前端 `pnpm build`：通过，Vite 生产构建完成。
- 本次前端文件 ESLint、Stylelint、Prettier 检查：通过。
- 两个仓库 `git diff --check`：通过。

## 已实施路径

- 数据库：`armada-api/src/main/resources/db/migration/V088__pull_task_unified_list_and_global_settings.sql`。
- 后端：`armada-api/src/main/java/com/armada/task/` 下的统一列表、聚合统计、全局设置和删除策略。
- 后端 Mapper：`armada-api/src/main/resources/mapper/task/PullTask*.xml`。
- 前端 API：`wheel-saas-pure-web/src/api/pull-task.ts`。
- 前端页面：`wheel-saas-pure-web/src/views/task/pull-task/`。

## 部署

- commit / 环境 / 部署后验证结果: 按用户要求未提交；未连接远程或真实数据库，未执行迁移，未部署。

## 遗留 / 跟进

- 书面设计：`docs/superpowers/specs/2026-07-31-pull-task-unified-list-global-settings-design.md`
- 实施计划：`docs/superpowers/plans/2026-07-31-pull-task-unified-list-global-settings.md`
- 拉群营销任务提交、任务配置快照、执行器接入和聚合统计生产者仍需后续独立实施。
- 目标环境确认后再执行 Flyway，并从真实结构重新生成 `.harness/wiki/数据模型.md`。
