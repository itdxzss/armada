# 变更记录：超链任务 H4 收信人流水统计及导出

- 日期 / 分支 / worktree：2026-08-29 / `codex/hyperlink-task-h4-recipient-stats` /
  `armada-h4-recipient-stats`
- 需求来源：`2026-08-28-hyperlink-task-recipient-stats-design.md` 与用户指定 H4 范围
- 状态：已完成

## 目标（一句话）

交付详情抽屉公共摘要、唯一 recipient 流水数据库分页筛选、异步 CSV 导出和公共作业外壳，不实现 H5/H6。

## 缺口拆解 / 任务清单

- [x] 增加 summary 和 recipients API，列表只读取冻结模型的 task/runtime/recipient 事实。
- [x] 四项筛选、状态时间和分页排序下推数据库，并保持租户与任务双重隔离。
- [x] 复用 `marketing_task_export_job`，按 RECIPIENTS 隔离 Worker，冻结筛选与 snapshotAt。
- [x] UTF-8 BOM CSV 固定八列，按主键游标每批最多 2000 行。
- [x] 完成 Mapper H2、API/CSV 合同测试和相关回归。

## 关键设计决策

- 不新增 recipient round、delivery attempt、重复流水或新的导出作业表；任务内一个 recipient 仍是一行。
- 既有营销导出 Worker 只领取 COUNTRY_ENTRY/FULL；H4 Worker 只领取 RECIPIENTS，避免交叉消费。
- 公共导出表仅增加 `request_payload_json`，`export_mode` 承载冻结契约中的公共业务类型。
- 导出创建沿用 H3 持久审计端口并失败关闭，不用应用日志冒充审计。

## 验证（evidence-before-done）

- 定向后端：`HyperlinkTaskDetailMapperH2Test`、`HyperlinkTaskExportMapperH2Test`、
  `HyperlinkTaskH4ContractTest`、`MarketingTaskExportMapperH2Test`，10 tests，0 failure/error/skip。
- 前端定向首次命令漏加载仓库既有 Node alias loader，8 项通过、2 项测试装配/静态扫描失败；按证据修正测试，
  产品代码未因该失败改变，最终相关回归使用仓库标准 loader 统一验证。
- Java main/test compile、Vue `tsc`/`vue-tsc`、H4 ESLint/Stylelint 与 Mapper XML 解析通过。
- 最终后端相关回归：H4、原超链 API shape 与既有营销导出 SQL/Mapper/Controller/Writer/Service，
  43 tests，0 failure/error/skip。
- 最终前端相关回归：H4 API/抽屉/公式及既有营销导出 API/轮询，26 tests，0 failure/skip。

## 部署

- 本任务不部署、不合并 integration。

## 遗留 / 跟进

- H5/H6 通过公共导出类型和真实 Tab 插槽后续接入，本分支不实现其内容。
