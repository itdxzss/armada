# 变更记录：导入链接菜单改「以 WS 链接分组为维度」展示

- 日期 / 分支 / worktree: 2026-06-18 / 2.0.0-snapshot / `wheel`
- 需求来源: 用户口述（导入链接菜单逻辑调整）+ `0617V1.3一期需求终版（已确认）.html` groupImport 区
- 状态: 进行中

## 目标（一句话）
把「导入链接」列表的**行维度**从「导入批次（一次上传一行）」改成「WS 链接分组（一个分组一行）」：新增分组即出现一行（0 链接），导入群链接 = 往选中分组**追加**，同一分组可多次导入累积。

## 背景 / 现状
- 现状列表行 = `group_link_import_batch`（一次导入一行），`link_label`（WS 链接分组）只是下拉项，建分组不出行；一个分组多次导入 = 多行重复显示分组名 →「一个文件对应一个分组」错觉。
- 数据层**已支持**多导入/多链接归属同一分组：`group_link.label_id`、`group_link_import_batch.link_label_id` 均在；`createGroupLinkImportBatch` 无「一分组一批次」硬约束。故本次**零 Flyway 迁移**，纯查询聚合 + 端点语义 + 前端改造。

## 关键设计决策
> 含被否决方案与原因。

1. **列保持不变（用户拍板「现有的列不要动」）**：ID / WS链接分组 / 来源文件 / 总链接数 / 成功 / 失败 / 导入时间 / 状态，列集合一字不改，仅改每列在「分组维度」下的取值口径：
   | 列 | 分组维度口径 |
   |---|---|
   | ID | `link_label.id` |
   | WS链接分组 | `link_label.label_name` |
   | 来源文件 | 该分组最近一次导入的 `source_file`，多文件时后缀「等 N 个」；无导入显「-」 |
   | 总链接数 / 成功 / 失败 | 该分组**全部非删批次**的 `SUM(total_rows)` / `SUM(success_rows)` / `SUM(failed_rows)` |
   | 导入时间 | `MAX(batch.imported_at)`；无导入显 `link_label.created_at` |
   | 状态 | 存在 QUEUED/RUNNING 批次→`导入中`；否则 `SUM(failed_rows)>0`→`部分成功`；否则→`完成` |
   - 否决「重设计聚合列/去文件列」：用户明确不动列。
2. **明细抽屉 = 该分组下全部导入明细（跨批次平铺、SQL 分页）**：对齐 0617 明细表列（行号/群名称/群链接/**来源文件**/状态/失败原因/导入时间），顶部统计跨所有导入累加。替代原「单批次 details 一次性 load-all」（顺带消除其内存全量隐患）。
3. **编辑 = 维持现状（勾选链接迁移到别的分组），不动**。仅把弹窗内「该批次成功链接」改为「该分组成功链接」——直接复用既有分页端点 `GET /api/tenant/group-links?label_id=&page=&page_size=`（group_link 行 = 该分组成功链接），不新增查询。否决「编辑改成改分组名/备注」：用户选保持现状。
4. **删除 = 级联 + 占用保护（用户选 A）**：软删 `link_label` + 其 `group_link_import_batch`（及明细）+ 其 `group_link`；删除前占用校验——若分组下任一 `group_link` 被**未结束（非终态）**的拉群/进群/营销任务引用，则**全或无**拦截并抛 `BusinessException(VALIDATION)` 列出被占用分组。具体引用列实现期对真库核验（候选：拉群 `task_row.group_link_id` / 进群 `join_task_result` / 营销 `group_marketing_task_detail`）。否决「不保护」与「只删壳保留链接」。

## 影响模块
- 后端 `wheel-api-tenant`：`TenantBusinessBasicsController` / `TenantBusinessBasicsService` / `TenantBusinessBasicsRepository`(+ MyBatis 实现) / `TenantBusinessBasicsDtos`
- 后端 `wheel-api-domain`：`LinkLabelMapper`(+XML) / `GroupLinkImportDetailMapper`(+XML)（新增按 label 聚合/分页查询）
- 前端 `wheel-saas-web`：`views/tenant/group-import/GroupImportList.vue` / `api/group-import.ts`
- 测试：tenant 服务/仓储单测 + `wheel-api-app` 真库 DbTest + 前端 vitest + vue-tsc

## API 变更
**新增（group 维度）：**
- `GET /api/tenant/group-link-import-groups`（list 分页 + 聚合统计；query: page/page_size/keyword）
- `GET /api/tenant/group-link-import-groups/{labelId}/details`（明细分页：跨批次明细 + source_file）
- `GET /api/tenant/group-link-import-groups/{labelId}/export-failures`（导出该分组全部失败链接 CSV）
- `POST /api/tenant/group-link-import-groups/batch-delete`（级联软删 + 占用保护，全或无）

**删除（旧 batch 维度 UI 端点，删旧路径）：**
- `GET /group-link-import-batches`（列表）、`GET /group-link-import-batches/{id}`（单批详情）、`GET /group-link-import-batches/{id}/links`、`GET /group-link-import-batches/{id}/export-failures`、`POST /group-link-import-batches/batch-delete`
- 连带删除其专属仓储/Mapper：`findGroupLinkImportBatchesPage`/`selectPageByTenant`、`selectSuccessLinksByBatch`/`countSuccessLinksByBatch` 等仅服务旧 UI 的方法（核验无其他调用方后删）。

**保留不动：** `POST /group-link-import-batches`（导入，异步建 QUEUED 批次，多导入本就支持）、`GET /link-labels`、`POST /link-labels`、`POST /group-links/migrate`、`GET /group-links`（迁移弹窗复用）、导入 Job 写批次/明细链路。

**权限：** 沿用 `tenant:group_link:view` / `tenant:group_link:edit`。
**分页返回：** 沿用本控制器既有 `PageResponse`（与周边一致，最小 diff）。

## 数据库变更
无（零 Flyway）。所有列已存在：`group_link.label_id`、`group_link_import_batch.link_label_id`、三表 `deleted_at`。

## Redis 变更
无。

## 关键约束（红线对照）
- 分页/筛选/count 全 SQL 下推（group 列表聚合 + 明细均 LIMIT/OFFSET），**禁内存分页**。
- 改 Mapper XML 必先 `xmllint` / 真库 DbTest 再交付（裸 `<>` 会 crash-loop）。
- 删除占用保护属可恢复错误 → 抛 `BusinessException(ErrorCodes.VALIDATION)`，不落 HTTP 200 被前端吞。
- 删旧路径：旧 batch 维度 UI 端点删干净，不留死代码/兼容 shim。
- 最小 diff、匹配周边命名（`*Response`/record/构造器注入）；前端 `<script setup>`，弹框遮罩 z-index < 30000。
- 并发会话同 worktree：改文件前 re-read，提交前 `git diff` 防覆盖他人在途改动。

## 任务清单（任务拆分，≤4h/项，TDD 先红后绿）
- [ ] T1 后端·分组列表：`LinkLabelMapper.selectImportGroupsPage/countImportGroups`(+XML 聚合 SQL) → Repository → Service `listGroupImportGroups` → Controller 新端点；真库 DbTest（空分组 0 行 / 多批次累加 / 来源文件+N / 状态派生 / keyword / 分页）
- [ ] T2 后端·分组明细分页：`GroupLinkImportDetailMapper.selectDetailsByLabelPage/countDetailsByLabel`(+XML join batch 出 source_file) → Repository/Service/Controller；真库 DbTest
- [ ] T3 后端·分组导出失败：Service 聚合该分组失败明细 → CSV（复用既有 csv 工具）；Controller 端点
- [ ] T4 后端·分组级联删除 + 占用保护：Repository 级联软删 label/batch/group_link + 占用校验查询；Service 全或无 + `BusinessException`；真库 DbTest（含占用拦截 + 正常级联）
- [ ] T5 后端·删旧路径：移除旧 batch 维度 UI 端点 + 专属仓储/Mapper 方法（核验无其他调用方）；FakeMapper 同步
- [ ] T6 前端·列表改分组维度：`api/group-import.ts` 新增 group 端点 / 移除旧 batch 端点；`GroupImportList.vue` 列表 loadGroups、勾选=labelId、建分组后刷新列表出行、导入后刷新、轮询按分组状态
- [ ] T7 前端·明细抽屉分页 + 来源文件列；编辑弹窗迁移源改 `listGroupLinks({label_id})`；删除改 group batch-delete
- [ ] T8 校验：vue-tsc + 前端组件测 + tenant 单测 + 真库 DbTest 全绿；`git diff` 自检

## 验证（evidence-before-done）
全部真库 DbTest 连测试服 RDS 实跑（非 mock）：
- 后端编译：`mvn -DskipTests test-compile` 全 reactor 绿。
- 真库 DbTest（wheel-api-app，连测试服 RDS）：
  - `GroupImportGroupsPageDbTest` 1/1（多批次累加/空分组出行/状态派生/来源文件/keyword(名+文件名)/分页）
  - `GroupImportGroupDetailsPageDbTest` 2/2（跨批次平铺+来源文件+排序+分页；导出失败仅失败行+来源文件+文件名）
  - `GroupImportGroupDeleteDbTest` 5/5（无占用级联软删分组+批次+链接；拉群 RUNNING 批次直绑分组拦截；**拉群 RUNNING 批次经 task_row.group_link_id 占用拦截**；营销 SENDING 占用拦截；拉群 COMPLETED 终态放行——全或无）
- 后端单测：`MyBatisTenantBusinessBasicsRepositoryTest` 9/9、`TenantBusinessBasicsServiceTest` 9/9（已删旧 batch 维度测试）。
- 前端：`vue-tsc --noEmit` clean；`vitest run src/views/tenant/group-import src/api` 116/116；全量 `vitest run` 539/539（子 agent 跑）。
- 多 agent 对抗式评审（workflow group-import-dim-review，5 维并行 + 逐条核验）：5 raw → 4 confirmed（0 HIGH）。结论：
  - **【已修·MEDIUM×2】拉群占用漏判**：原占用 SQL 拉群分支只看 `task_batch.link_label_id`，但老群链接/手选(MANUAL_PICK)模式该列常为 NULL，真实占用记在 `task_row.group_link_id` → 删除分组会误删在跑任务正用的链接。**已修**：`selectInUseLabelIds` 拉群补第二分支 `group_link ⋈ task_row(group_link_id) ⋈ task_batch(未结束)`，并保留直绑 link_label_id 分支；新增 DbTest 用例验证（5/5 绿）。
  - **【已修·LOW】拉群终态白名单错**：原写 `NOT IN ('STOPPED','COMPLETED','FAILED','ENDED')`，但 `TaskBatchStatus` 枚举=PENDING/RUNNING/COMPLETED/PARTIAL_FAILED/STOPPED（'FAILED'/'ENDED' 是死值，漏 PARTIAL_FAILED）。**已修**：改正向 `status IN ('PENDING','RUNNING')`（未结束），免枚举漂移。
  - **【流程·LOW】工作树夹带并发改动**：git 工作树含他会话在途的 AccountList/GroupMarketingTaskList/JoinTaskList/BatchCreate/AccountImport（与本需求正交）。**提交时只 `git add` group-import 相关文件，勿 `git add -A`**。
  - 误报 1：已被核验否决。

> 注：真库 DbTest 多 context 并发时偶发 RDS 连接被掐（`connection closed`/`Failed to obtain JDBC Connection`），单类逐个跑即稳定绿——属测试服 RDS 连接上限抖动，非代码问题。

## 部署
- commit / 环境 / 部署后验证结果: 未提交未部署（snapshot）。零 Flyway，部署只需常规 build+冒烟。

## 遗留 / 跟进
- 占用保护口径（评审后定稿）：拉群=①`task_batch.link_label_id` 未结束 ②`task_row.group_link_id` 经未结束批次（覆盖老群链接/手选模式）；营销=`group_marketing_task_detail.group_link_id` 经未结束任务；未结束口径 拉群 status∈(PENDING,RUNNING)、营销 status∉(STOPPED,COMPLETED)。**进群任务按 group_jid 寻址、无 group_link_id 外键，不在占用口径内**（已在 LinkLabelMapper.selectInUseLabelIds javadoc 标注）。
- 空分组「状态」=「完成」属约定（无 pending 即完成）。
- `GroupLinkImportBatchMapper.selectByTenant`（无分页批次列表）原本就无调用方（非本次删除路径的一部分），随本次清理一并移除以免遗留死方法。
- wiki 接口协议/数据模型未变表结构（零迁移）；接口协议 wiki 现仍记旧 batch 维度端点，需部署后重跑 `.harness/wiki` 的 parse_endpoints.py+format_api.py 同步新 group 端点（只指出，不阻断）。
- **提交卫生**：工作树有他会话在途的 5 个无关前端文件（账号/营销/进群/批次/导入），提交本需求时只 `git add` group-import 相关文件，勿整树 add。
