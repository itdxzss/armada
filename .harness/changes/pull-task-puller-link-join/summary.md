# 变更记录：拉手踩链接进群

- 日期 / 分支 / worktree: 2026-08-14 / 1.0.3-snapshot / 主工作区
- 需求来源: 拉群任务增加“拉手是否踩链接进群”开关；联系人互加仍保留
- 状态: 已完成

## 目标（一句话）

普通群链接任务可按任务冻结拉手进群方式，默认保持管理员邀请，开启后联系人准备完成再由拉手踩链接进群。

## 缺口拆解 / 任务清单

- [x] 创建与回读合同增加 `pullerJoinByLink`
- [x] Flyway 增加默认关闭的配置列
- [x] 拉手选号冻结 `entry_mode`
- [x] 阶段 5 按 `entry_mode` 提交邀请或踩链接动作
- [x] 复用现有 `group.join.requested` 回调收敛拉手在群状态
- [x] 前端增加开关和已保存配置展示

## 关键设计决策

- 不新增阶段、不改协议 wire source，复用 `JOIN_BY_LINK` 动作和现有进群回调。
- 联系人双向保存顺序与失败非阻断策略不变。
- 数据库默认值为 0，历史任务和未传新字段的旧客户端保持管理员邀请。

## 验证（evidence-before-done）

- `mvn -Dtest=PullTaskStandardSettingWriterTest,PullTaskStandardReadServiceTest,PullTaskStandardSettingMapperInMemoryTest,PullTaskManagerPullerContactTransactionIntegrationTest,PullTaskPullerInviteTransactionIntegrationTest,PullTaskManagerJoinResultServiceImplTest,PullTaskPullerJoinByLinkMigrationSqlTest,PullTaskStandardCreateDTOTest,PullTaskStandardCreateServiceTest test`
  - 结果：52 tests，0 failures，0 errors。
- `pnpm typecheck`
  - 结果：通过。
- `node --test --experimental-strip-types --loader ./src/api/__tests__/node-test-loader.mjs src/api/pull-task.test.ts src/views/task/pull-task/composables/useStandardPullTaskCreate.test.ts`
  - 结果：28 tests，0 failures。
- 创建页开关和详情回读的两个聚焦源码测试
  - 结果：2 tests，0 failures。
- `pnpm build`
  - 结果：生产构建通过。
- `mvn test`
  - 结果：仓库内真实数据库测试持续等待不可用数据库连接，手动停止；本次相关测试不依赖该环境且已单独通过。

## 部署

- 先随 Armada 服务发布执行 V116，再发布前端；未部署。

## 遗留 / 跟进

- `.harness/wiki/数据模型.md` 从真库 `information_schema` 生成，迁移实际落库并重新导出 TSV 后再刷新。
