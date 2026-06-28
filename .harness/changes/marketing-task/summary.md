# 变更记录：营销任务后台切片

- 日期 / 分支 / worktree: 2026-06-28 / main / armada
- 需求来源: `docs/business/requirements/一期需求.xlsx` 的「营销任务」「营销模版」页 + wheel 营销任务后台参考
- 状态: 后台接口切片已完成,未部署

## 目标（一句话）

补齐 armada 一期营销任务的后台基础能力:数据模型、创建、列表、详情、账号群树、启动、停止、批量删除和任务侧修改营销素材。

## 缺口拆解 / 任务清单

- [x] 确认 armada 当前只有营销模板模型,没有营销任务模型。
- [x] 对照 wheel 的 `group_marketing_task` / `group_marketing_task_detail` 及后续发送引擎扩列,避免重复踩坑。
- [x] 设计 `marketing_task`、`marketing_task_target`、`marketing_task_send_attempt` 三张任务核心表。
- [x] 设计 `account_group_baseline` 一账号一行 JSON 快照,并给 `account` 增加 `group_baseline_state`。
- [x] 新增 Flyway 迁移 `V014__marketing_task_data_model.sql` 和 schema DbTest。
- [x] 实现营销任务创建、列表、详情的 entity / DTO / VO / mapper / service。
- [x] 实现营销任务 controller 基础接口。
- [x] 实现启动、停止、批量删除及发送中任务删除保护。
- [x] 实现建任务抽屉的账号→可营销群树。
- [x] 实现通过任务修改其引用营销模板。
- [x] 刷新 `.harness/wiki/数据模型.md`。
- [x] 刷新 `.harness/wiki/接口协议.md`。
- [x] 补充 focused DbTest 和 controller DbTest。

## 影响模块

- `armada-api/src/main/java/com/armada/marketing/**`
- `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- `armada-api/src/main/resources/db/migration/V014__marketing_task_data_model.sql`
- `armada-api/src/test/java/com/armada/marketing/**`
- `.harness/changes/marketing-task/**`

## 数据库变更

- `account` 新增 `group_baseline_state`:营销树群基线状态,用于判断账号是否需要排除登录前已在群。
- 新增 `account_group_baseline`:一账号一行,`baseline_group_jids JSON` 保存首次拍基线时账号已在群 JID 数组。
- 新增 `marketing_task`:任务配置、状态、计数和时间字段。
- 新增 `marketing_task_target`:任务执行目标,粒度为账号×群组。
- 新增 `marketing_task_send_attempt`:后续发送引擎记录每次尝试结果使用,本轮只建模不写入。
- Redis 变更:无。

## API 变更

- `GET /api/marketing-tasks`:营销任务列表,支持 `id`、`keyword`、`status`、`startTime`、`endTime` 和分页。
- `POST /api/marketing-tasks`:新建营销任务,写入任务主表和账号×群组目标。
- `GET /api/marketing-tasks/{id}`:营销任务详情,返回任务主信息和 target 明细。
- `GET /api/marketing-tasks/account-tree?groupId=...`:建任务抽屉账号→可营销群树。
- `POST /api/marketing-tasks/{id}/start`:待启动/已停止任务进入发送中。
- `POST /api/marketing-tasks/{id}/stop`:发送中任务进入已停止。
- `POST /api/marketing-tasks/batch-delete`:批量软删,包含发送中任务时整批拒绝。
- `PUT /api/marketing-tasks/{id}/marketing-template`:通过任务定位并更新其引用的共享营销模板。

`.harness/wiki/接口协议.md` 已由 `.harness/wiki/parse_endpoints.py` + `.harness/wiki/format_api.py` 从 armada controller 生成。当前生成结果覆盖 11 个 controller、48 个 endpoint。

## 关键设计决策

1. 任务主表只保存任务配置、状态和任务级计数;账号状态继续来自 `account_state`,群事实继续来自 `group_link` / `group_link_preview` / `group_link_health`。
2. 执行目标按「账号+群组」落 `marketing_task_target`,不是单纯按群落一行,否则无法定位发言账号、重试次数和在线检测结果。
3. 发送尝试历史独立为 `marketing_task_send_attempt`,满足一期“记每次失败原因+重试结果”的口径;本轮不接协议层,不产生真实发送尝试。
4. 账号登录前群基线采用 `account_group_baseline.baseline_group_jids JSON`,一账号一行;不做一群一行,避免基线表按账号×群数膨胀。
5. `startMode=IMMEDIATE` 和启动接口当前只改变任务状态为发送中,不触发协议层真实发送。
6. 修改任务营销素材不复制任务内素材快照,而是更新任务引用的共享 `marketing_template`。
7. 账号群树基于“账号分组内在线可用账号 × 租户可用群池 - 登录前基线”生成。armada 当前没有“某账号当前在哪些群”与“某账号是否群管理员”的事实表,所以 `isAdmin` 暂固定为 `false`。
8. 本次迁移使用 V014。测试库已有已执行但本工作树缺失的 `V013__protocol_command_outbox.sql`,直接新增 V013 会撞号。

## 验证（evidence-before-done）

- RED: 新增 `MarketingTaskDataModelMigrationDbTest` 后先运行 schema 测试。
  - 结果: 失败,缺少 `marketing_task` / `marketing_task_target` / `marketing_task_send_attempt` / `account_group_baseline` 和 `account.group_baseline_state`。
- RED: 新增 `MarketingTaskCreateReadDbTest` / `MarketingTaskControllerDbTest` / `MarketingTaskMutationDbTest` / `MarketingTaskAccountTreeDbTest` / `MarketingTaskMaterialUpdateDbTest` 后,分别先运行 focused tests。
  - 结果: 编译或断言失败,缺对应 service / mapper / controller 能力。
- GREEN + smoke(临时干净 schema): 使用 `armada_codex_marketing_task` 从空库运行 Flyway 到 V014,再执行:

```bash
cd armada-api
mvn -q -Dtest=MarketingTaskDataModelMigrationDbTest,MarketingTaskCreateReadDbTest,MarketingTaskControllerDbTest,MarketingTaskMutationDbTest,MarketingTaskAccountTreeDbTest,MarketingTaskMaterialUpdateDbTest,MarketingTemplateServiceImplTest,MarketingTemplateConverterTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```

  - 结果: 通过,退出码 0。日志确认空 schema 成功应用 13 个迁移到 v014。
- Wiki: 从 `armada_codex_marketing_task` 导出 information_schema TSV 后运行 `.harness/wiki/gen_datamodel.py`。
  - 结果: `.harness/wiki/数据模型.md` 已包含 `account.group_baseline_state`、`account_group_baseline`、`marketing_task`、`marketing_task_target`、`marketing_task_send_attempt`。
- API Wiki:
  - `python3 .harness/wiki/parse_endpoints.py`: 通过,生成 11 个 controller、48 个 endpoint。
  - `python3 .harness/wiki/format_api.py`: 通过,生成 `.harness/wiki/接口协议.md`。
- `git diff --check`
  - 结果: 通过,退出码 0。

## 部署

- commit / 环境 / 部署后验证结果: 未提交,未部署。

## 回滚方案

- 未部署前:回退本次代码变更和 `V014__marketing_task_data_model.sql`。
- 已部署后:先停止使用营销任务入口;确认无业务数据需要保留后执行 `.harness/changes/marketing-task/rollback.sql` 删除新增表和 `account.group_baseline_state`。

## 遗留 / 跟进

- 当前 `armada` 测试 schema 已有 `V013__protocol_command_outbox.sql` 的 Flyway 历史,但当前工作树缺该迁移文件。对现有 schema 跑 DbTest 会被 Flyway 校验拦截,需要单独补齐或修复该迁移缺口。
- 本轮不接协议层发送引擎,不实际发 WhatsApp 消息。
- 账号群树暂不能证明“账号当前真的在该群内”或“账号是群管理员”,后续需要协议层成员事实回流后再收紧。
