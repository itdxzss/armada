# 变更记录：普通拉群管理员设置阶段

- 日期 / 分支 / worktree：2026-08-06 / `1.0.2-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源：`docs/superpowers/specs/2026-08-06-pull-task-manager-admin-stage-design.md`
- 实施计划：`docs/superpowers/plans/2026-08-06-pull-task-manager-admin-stage-implementation.md`
- 状态：本地实现完成，未提交

## 目标（一句话）

在任务管理员进群后，由群内现有我方群主或管理员完成提权并实时确认权限，再继续邀请拉手，同时避免重复占用已入群失败的拉手。

## 缺口拆解 / 任务清单

- [x] 数据迁移、阶段/角色/动作枚举与动作重试事实
- [x] 群关系管理员候选查询
- [x] PROMOTE Outbox、协议回调和实时事实收敛
- [x] 阶段路由、资源恢复、详情投影
- [x] 失败拉手换号
- [x] 聚焦测试与本地质量门

## 关键设计决策

- `account_group_membership` 只产生候选，提交前仍以实时成员列表确认群主/管理员权限。
- 协议 PROMOTE 成功只唤醒复核；目标任务管理员被实时确认为 admin/owner 后才能推进。
- PROMOTER 仅作执行审计，不占任务管理员或拉手资源。
- V101 用持久化检查点和事务保护阶段重编号、活动执行行回退，repair 后重跑不会重复偏移。
- 详情接口统一脱敏账号号码、料子号码和群/成员 JID；异常原因只展示稳定安全文案。
- 本次不新增建群人分组，不访问远程或真实数据库。

## 验证（evidence-before-done）

- 改动前聚焦基线：82 项后端测试中 81 通过；既有失败为
  `AccountGroupMembershipMapperSqlTest#selectGroupExecutionAccount_prefersOnlineAdminThenMostRecentlySeen`，
  测试仍断言 `membership_status IN (1, 2)`，当前生产 XML 已在 2026-08-05 改为 `= 1`。
- 本次所有变更关联测试最终一次性执行：195/195 通过；包含迁移 SQL、真实 Mapper、管理员设置
  状态机、调度 claim、完整端到端、回调关联、资源恢复和失败拉手换号。
- 候选 SQL 新增合同测试单独执行：1/1 通过。
- 审查补强用例覆盖：迁移断点重跑合同、管理员/拉手事务 CAS 回滚、详情号码与 JID 脱敏。
- 修正后复审：无剩余 Critical / Important。
- 最后阶段字面量清理后的三项事务集成测试：12/12 通过。
- `mvn test` 全量门禁在既有 DB-backed Spring 测试尝试不可用的本地外部数据源时受阻；
  该环境问题发生在进入本次拉群测试前。上述 191 项本次影响范围测试均不依赖该外部数据源。
- `git diff --check`：通过。

## 部署

- 未提交、未部署、未连接远程或真实数据库。

## 遗留 / 跟进

- 首套环境部署前必须重新确认目标环境，并确保旧调度器停机后再执行 V101 和新版本发布。
- 首套环境按实施计划 Task 10 验证任务 2；本地阶段不访问远程、不迁移真实数据。
- 后续具备本地 MySQL/Testcontainers 门禁时，补 V101 提交前失败与 repair 后重跑的执行级测试；当前为 SQL 合同测试。
