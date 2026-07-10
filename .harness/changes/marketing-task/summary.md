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

## 2026-07-04 需求回收:任务恢复模板必填,模板新增图文内容

- `marketing_task` 最终不保留 `send_content_type` 和 `text_content`;`marketing_template_id`、`marketing_template_name` 恢复为必填。
- 因 `V036__marketing_task_text_content.sql` 已在测试环境执行,不删除历史迁移;新增 `V037__marketing_template_only_and_image_text_mode.sql` 前滚删除两列。
- `POST /api/marketing-tasks` 恢复为必须选择营销模板,不再支持任务内填写文本内容。
- `marketing_template.link_mode` 消息类型新增 `3=图文内容`;仅 `2=按钮超链` 允许配置消息按钮。
- 新增/更新测试:`MarketingTaskDataModelMigrationDbTest`、`MarketingTaskCreateReadDbTest`、`MarketingTaskControllerDbTest`、`MarketingTaskMaterialUpdateDbTest`、`MarketingTemplateDeletionDbTest`、`MarketingTemplateServiceImplTest`。

## 2026-07-10 任务时间窗口与重新启动

- 普通 `POST /api/marketing-tasks/{id}/start` 不再改写任务开始、结束时间或账号群组发送时间。
- 等待中/已停止任务在计划开始时间前激活后继续保持等待;只有进入计划窗口才置为发送中。已过结束时间时要求使用重新启动接口。
- 已停止任务到达原计划结束时间后也会由生命周期调度归档为已结束,避免永久停在已停止而无法进入重新启动流程。
- 轮次 worker 增加开始时间防线:若历史脏数据提前处于发送中,退回等待并取消轮次调度,不生成 attempt/outbox。
- 轮次 worker 在目标解析和积压查询后使用新时间再次校验结束边界,避免耗时查询跨过结束时间后仍抢占轮次并生成新消息。
- 新增 `POST /api/marketing-tasks/{id}/restart`:仅已结束任务可提交新的开始、结束时间。接口清空 `finished_at`,但保留 `account_group_send_at`、累计计数、轮次、`started_at` 和发送明细历史。
- 关键状态流转新增任务 ID、租户 ID、原/新状态和原/新时间窗口日志,便于排查提前启动或重新启动问题。
- 定向单元/SQL 形状测试:
  - `mvn -Dtest=MarketingRoundWorkerTest,MarketingRoundSchedulerTest,MarketingTaskMapperSqlShapeTest test`
  - 结果:21 个测试通过,0 失败/错误。
- 真库测试已补到 `MarketingTaskMutationDbTest` 和 `MarketingTaskControllerDbTest`,但本机 `localhost:3306/armada` 的 Flyway V037、V041 校验和与代码不一致,应用上下文在测试执行前被校验拦截。本次未执行 `flyway repair`,未修改本机库历史。
- 未提交,未部署。

## 2026-07-10 模板删除后的启动门禁

- 普通启动和已结束任务重新启动在更新任务状态前统一查询任务引用的营销模板。
- `MarketingTemplateMapper.selectById` 会过滤软删除数据；查不到模板时返回明确提示
  `营销模板已删除，任务不可启动`，并保持任务原状态不变。
- 拒绝启动时记录 tenantId、taskId、templateId，方便定位模板删除后的误启动操作。
- 新增 `MarketingTaskServiceImplLifecycleTest`，覆盖普通启动与重新启动两个入口，并验证不调用状态更新 Mapper。
- `MarketingTemplateDeletionDbTest` 补充真实软删除联动场景；本机真库测试仍受前述 Flyway V037、V041 校验和不一致阻塞。
- 定向测试：
  - RED：实现前 2 个门禁用例均收到旧文案 `任务状态已变化,请刷新后重试`。
  - GREEN：`MarketingTaskServiceImplLifecycleTest` 2 个测试通过。
  - 联动回归：`MarketingTaskServiceImplLifecycleTest,MarketingTemplateServiceImplTest,MarketingRoundWorkerTest,MarketingRoundSchedulerTest,MarketingTaskMapperSqlShapeTest` 共 40 个测试通过。

## 2026-07-10 普通营销账号占用

- 占用粒度为账号，不新增分组锁。创建任务和加载创建页账号树时，只要所选分组内存在被发送中普通营销任务占用的账号，就拒绝并展示占用任务名称与预计结束时间；信息不完整时使用通用提示。
- 新增 `marketing_account_occupancy` 当前占用表，`(tenant_id, account_id)` 唯一键作为并发闸门；任务名称、结束时间仍从 `marketing_task` 联查，不重复保存。
- 任务正式进入发送中时抢占当前空闲目标账号。未到开始时间的任务只保持等待，不提前占用。
- 每轮发送前重新尝试抢占目标账号。仍被其它任务占用的账号按实际群目标写入 `SKIPPED / ACCOUNT_OCCUPIED` 明细，不创建协议 outbox、不累计失败；后续轮次会再次抢占，原任务释放后即可参与发送。
- 详情的“最近原因”只反映最新一条已完成 attempt：最新失败/跳过时展示原因，账号后续释放并发送成功后清空旧占用原因。
- 手动停止、自动到期、轮次检测到结束时间、提前误置发送中后退回等待、删除模板联动停止任务时，都会释放该任务持有的账号。已写入 outbox 的历史消息不撤回。
- 建群营销未接入此占用模型。
- TDD 证据：持久化、创建门禁、生命周期、每轮动态重抢、占用跳过和详情统计均先见到预期失败，再实现回绿。
- 租户 SQL 解析复核先发现 `INSERT ... SELECT` 由拦截器补出的未限定 `tenant_id` 在双表连接下存在歧义，随后改为显式写入 `mt.tenant_id`，并用项目实际 `TenantLineInnerInterceptor` 锁定解析结果。
- 聚焦回归：`MarketingAccountOccupancyMapperSqlShapeTest,MarketingAccountOccupancyServiceTest,MarketingTaskServiceImplLifecycleTest,MarketingAccountTreeRealtimeServiceTest,MarketingTaskLifecycleWorkerTest,MarketingRoundWorkerTest,MarketingRoundSchedulerTest,MarketingTaskMapperSqlShapeTest,MarketingTemplateServiceImplTest` 共 67 个测试通过，0 失败/错误。
- 真库测试已补充到 `MarketingAccountOccupancyMapperDbTest`、`MarketingTaskMutationDbTest`、`MarketingRoundWorkerDbTest` 和迁移模型测试；本机库验证仍需先处理既有 Flyway V037、V041 校验和不一致，本次不执行 `flyway repair`。
- 未提交、未部署。

## 2026-07-10 普通营销五态与创建即锁定（覆盖同日旧规则）

- 本节覆盖上方“重新启动”“仅发送中占用”“暂停/提前退回释放账号”等旧规则；建群营销、速拉群不在本次范围。
- 普通营销任务收敛为五态：`1=未启动`、`2=执行中`、`5=已暂停`、`7=已完成`、`8=已关闭`。
- 创建任务保存主表和目标后，在同一事务内立即锁定全部去重账号；唯一键冲突会回查占用任务并整单回滚，提示：
  `该账号正在被任务【任务名称】占用，请先关闭原任务后再使用。`
- 未到开始时间点击启动仍保持未启动；调度器到达 `task_start_at` 自动进入执行中。暂停只停止后续轮次，账号不释放；继续仅允许已暂停且仍在计划窗口内的任务。
- 新增 `POST /api/marketing-tasks/{id}/pause`、`/{id}/resume`、`/{id}/close`；删除普通营销 `restart` API 和 DTO，原 `stop` 不再对普通营销开放。
- 手动关闭写入 `8=已关闭` 和 `finished_at`，清空 `next_round_at` 并释放全部账号；已完成、已关闭不可再次操作。
- 正常到期写入 `7=已完成` 并释放账号；模板删除导致的异常终止同样写入已完成并释放。提前误置执行中时退回未启动但保留账号锁。
- 批量删除只接受已完成或已关闭任务，避免通过软删除绕过关闭与账号释放规则。
- 新增 `V050__marketing_task_five_state_lifecycle.sql`：旧 `3/4/6` 前滚为已完成、清理终态占用、按最早非终态任务回填历史账号锁并更新状态列注释。
- 账号树批量返回当前有效占用方，锁定账号首屏即禁选；保存事务的唯一键复查仍是最终并发闸门。
- 离线验证：普通营销相关非 DbTest 共 91 个通过；全部测试源码 `test-compile` 通过；Mapper XML 校验和 `git diff --check` 通过。
- 真库 DbTest 未执行：本机测试库存在既有 Flyway V037/V041 校验和不一致，本次未运行 `flyway repair`、未修改数据库。
- 未提交、未部署。

## 2026-07-10 创建任务与删除模板并发门禁

- 普通营销创建任务在校验模板时使用 `SELECT ... FOR UPDATE`，模板行锁持有到创建事务提交。
- 批量删除模板先对 ID 去空、去重并升序，再按相同模板行执行 `SELECT ... ORDER BY id ASC FOR UPDATE`；拿到锁后才结束关联非终态任务、释放账号并软删除模板。
- 该顺序覆盖两种竞态：创建先拿锁时，删除等待后能看到并结束新任务；删除先拿锁时，创建等待后会因模板已软删除而整单回滚。
- 按已确认范围仅实现模板行锁第一层，不处理历史重叠任务，也不扩展建群营销/速拉群。
- TDD 证据：实现前测试编译因缺少两类锁查询失败；实现后定向 26 个测试通过。普通营销相关非 DbTest 回归共 92 个通过，Mapper XML 校验和 `git diff --check` 通过。
- 真库 DbTest 未执行：继续受本机既有 Flyway V037/V041 校验和不一致限制，本次未修改数据库。
- 未提交、未部署。
