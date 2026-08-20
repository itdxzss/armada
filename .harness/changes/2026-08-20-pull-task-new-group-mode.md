# 变更记录：拉群任务新群模式剩余实现

- 日期 / 分支 / worktree: 2026-08-20 / `feat/pull-task-new-group-mode` / `armada/.worktrees/pulltask-new-group-mode`
- 需求来源: `docs/operations/2026-08-20-pull-task-new-group-mode-remaining-work.md`
- 状态: 本地实现与自动化验证完成；test1 真环境验收待账号资源恢复

## 目标（一句话）

完成新群模式从创建草稿、资源校验、建群阶段调度到前端创建与展示的闭环，并为真环境验收保留明确前置条件。

## 缺口拆解 / 任务清单

- [x] 新群模式按成功接收的 TXT 数量生成执行行并允许提交
- [x] 创建时校验站台分组可用数不小于 `max(initialStationCount, stationCountPerCall)`
- [x] 实现 `GROUP_CREATE` 七步阶段并接通认领、准入与分派
- [x] 列表与详情回读 `creationMode`、建群配置和当前建群步骤
- [x] 前端独立 worktree 启用新群模式 Tab、配置表单与展示
- [x] 完成单元、Mapper、组件和模块级验证
- [ ] 账号资源就绪后在 test1 以 WhatsApp 实时元数据完成闭环验收

## 关键设计决策

- 2026-08-20 用户确认：一个成功接收的 TXT 文件对应一个待创建群和一条执行行，不新增“建群数量”字段。
- 2026-08-20 用户确认：新群模式的群设置总开关默认开启；用户显式关闭时仍以 TXT 文件名作为建群必需的群名，开关只控制后续群资料与权限下发。
- 2026-08-20 用户确认：为满足建群协议至少一个参与者的硬约束，次管理员固定作为初始成员；`initialStationCount=0` 仍表示零个初始站台。次管理员成功回执落 `IN_GROUP`，后续 `MANAGER_JOIN` 按已在群事实继续推进。
- 建群阶段由 `role_type=4` 建群人应用群资料/权限，因为次管理员此时尚未提权；其它阶段仍由 `role_type=1` 次管理员执行。
- 后端只在本 worktree 修改；前端另建同名独立 worktree，不修改仓库主目录。
- 本机两个临时验证库未经单独授权不删除。

## 验证（evidence-before-done）

- 后端定向回归：`mvn -q -Dtest='PullTaskGroupProfileDispatcherTest,PullTaskGroupCreateProcessorTest,PullTaskGroupCreateTransactionIntegrationTest,PullTaskManagerJoinTransactionServiceTest,PullTaskExecutionTransactionServiceTest,PullTaskExecutionDispatchCoordinatorTest,PullTaskExecutionStageRouterTest,PullTaskStandardControllerTest,PullTaskStandardDraftServicePlanTest,PullTaskStandardSettingWriterTest,PullTaskStandardCreateServiceTest,PullTaskGroupSettingsApplyTimingIntegrationTest' test`，退出码 0。
- 后端 PullTask 模块回归（排除需真 MySQL 的 `PullTaskNormalLinkCollationDbTest`）：759 个测试，3 failures + 2 errors + 2 skipped；失败/错误逐项仍是施工单记录的既有基线：`PullTaskMapperBusinessConditionTest` 1 个、`PullTaskClosingTransactionServiceTest` 1 个、`PullTaskStationSupplementServiceTest` 3 个，没有新增红项。
- 前端改动文件 ESLint：退出码 0；`tsc --noEmit` 与 `vue-tsc --noEmit --skipLibCheck`：退出码均为 0。
- 前端定向 API/组件/composable 回归：60 tests，60 pass，0 fail。
- 前端以 `node --import ./src/api/__tests__/node-test-alias.mjs --test --experimental-strip-types --test-concurrency=1 "src/**/*.test.ts"` 重跑全部 153 个测试文件：645 tests，640 pass，5 fail。5 个失败均在未修改的 `src/api/group.test.ts`（1）、`GroupMemberDrawer`（1）、`GroupPermissions`（3）既有分支范围，本轮定向回归无新增失败。直接执行 package script 会因 Node 23 无法加载 `nprogress.css` 产生大量运行器假红，不作为业务回归结论。
- `git diff --check`：后端、前端均通过。

## 部署

- commit / 环境 / 部署后验证结果: 未部署；不主动推送远端分支。

## 遗留 / 跟进

- test1 当前缺在线且专用的建群人、管理员、拉手与站台账号分组；资源就绪前不能宣称真环境闭环通过。
