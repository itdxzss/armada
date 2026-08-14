# 变更记录：普通群链接前期拉人调用计划

- 日期 / 分支 / worktree：2026-08-14 / `1.0.3-snapshot` / `armada` 主工作树
- 需求来源：用户要求在普通群链接拉群任务增加“前期单次拉人数”和“前期拉人执行次数”
- 设计：`docs/superpowers/specs/2026-08-14-pull-task-early-call-plan-design.md`
- 状态：本地实施与验证完成，未部署、未连接真实数据库

## 目标

让每群完整初始拉人计划的前 N 次调用使用固定小批量人数，后续调用继续使用原人数范围。

## 影响模块

- `wheel-saas-pure-web`：创建表单、创建请求、已保存配置回显。
- `armada-api`：创建合同、配置持久化/回读、初始波次规划。
- `pull_task_standard_setting`：新增两个非空配置列。
- `armada-protocol`：协议单次拉人合同不变，无修改。

## API / DB / Redis

- API：`POST /api/pull-tasks/standard` 增加 `earlyPullCount`、
  `earlyPullCallCount`；详情 `standardSetting` 增加同名字段。
- DB：Flyway `V115__pull_task_early_call_plan.sql`；新任务页面默认 1、2，历史任务迁移为 1、0 以保持旧计划。
- Redis：无变化。

## 关键约束

- 两个字段均为正整数。
- 升级前已存在任务允许 `earlyPullCallCount=0`，表示不启用前期分批；新建合同仍拒绝 0。
- 只在初始波次前缀生效；重试波次不重复应用。
- 前期批次不足配置人数时使用实际剩余人数，不创建空调用。
- 现有拉人间隔、站台、拉手切换、回执和重试筛选保持不变。

## 任务清单

- [x] 需求与历史排除口径对账。
- [x] 补创建合同和初始波次 RED 测试。
- [x] 实现数据库、后端和前端字段链路。
- [x] 实现初始波次前期分批并保持重试规则不变。
- [x] 完成聚焦回归、类型检查、构建和差异审查。

## 回滚

- 前端与 Java 代码回退本变更对应 diff。
- 数据库按 `rollback.sql` 先下线读取新列的代码，再删除两列。
- 已冻结任务的两个配置值随列删除，不可恢复；回滚前应确认不再需要保留。

## 验证

### 后端

- RED：创建 JSON 合同缺少两个字段；初始波次仍冻结为 `5/5/5/5/1`，新增期望失败且无编译/环境错误。
- GREEN：创建/回读、真实 H2 Mapper、初始/重试波次、端到端调度和迁移聚焦回归退出码 0。
- 扩大到 90 个相关测试时，仅 `PullTaskStationSupplementServiceTest` 的 3 个既有用例失败；
  干净 HEAD 单独运行该类得到完全相同的 1 failure / 2 errors，确认不是本次新增。
- `FlywayMigrationVersionContractTest`、`PullTaskEarlyCallPlanMigrationSqlTest`、Mapper XML 校验、
  API 文档生成测试、`git diff --check` 均退出码 0。

### 前端

- 新增字段布局与已保存配置回显 2 个聚焦测试通过。
- `pnpm typecheck` 与 `pnpm build` 退出码 0。
- 直接运行依赖统一 HTTP 层的 API/composable Node 测试时，当前 Node 23 测试加载器无法加载
  `nprogress.css`；该环境问题发生在测试文件加载阶段，未进入本次业务断言。类型检查和生产 Vite
  构建均已覆盖请求类型与组件编译。

### 未执行

- 未运行真实 MySQL/Flyway、未部署、未连接远程环境。
- 数据模型 wiki 由真实库元数据生成，本次未手工改写；应在 V115 应用到目标环境后运行生成器刷新。
