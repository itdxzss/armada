# 变更记录：超链任务 H5 发信账号维度统计

- 日期 / 分支 / worktree: 2026-08-29 / `codex/hyperlink-task-h5-account-stats` / `armada-h5-account-stats`
- 需求来源: 用户任务、`docs/superpowers/specs/2026-08-28-hyperlink-task-account-stats-design.md`、shared contract、`docs/business/hyperlink-marketing-data-model.md`
- 状态: 已完成

## 目标（一句话）

仅实现 H5 发信账号维度分页查询和异步 CSV 导出，保持默认累计查询走 `account_stat`、时间范围查询走 `recipient`。

## 缺口拆解 / 任务清单

- [x] 查询 DTO、响应 VO、白名单排序和输入校验
- [x] 默认累计与时间范围两条数据库聚合 SQL
- [x] 未分配桶、usage 展示快照、存活天数和租户隔离
- [x] ACCOUNT_STATS 异步 CSV writer 及公共作业轮询/下载复用
- [x] H2 真实 Mapper、控制器和 SQL 结构测试
- [x] 一次定向测试、一次最终相关回归和提交

## 关键设计决策

- 用户本次明确要求“成功率区间”，优先于旧 H5 设计稿中的“成功数区间”；成功率按 shared contract 的 `successNum / sendTotal`，输入百分比范围 `0..100`。
- 无时间范围只读 `hyperlink_task_account_stat`，有完整 `[startAt,endAt)` 才读 `hyperlink_task_recipient`；页面查询不回写投影、不做 Java 全量聚合。
- `account_id IS NULL` 统一为 `bucketKey=0` 的“未分配”行，usage 展示字段为空、存活天数为 `0.0`。
- CSV 导出继续复用既有持久作业表的租约/快照/过期能力，不新增 hourly 或导出业务表。

## 验证（evidence-before-done）

- 定向测试：`mvn -q -Dtest=HyperlinkAccountStatQueryH2Test,HyperlinkAccountStatsSqlShapeTest,HyperlinkAccountStatsCsvWriterTest,HyperlinkAccountStatControllerTest test`，11 项通过、0 失败。
- 最终相关回归：H5 查询/HTTP/CSV/作业、现有 metrics projector、既有 marketing export 共 44 项通过、0 失败；命令见本次提交终态记录。
- 编译证据：`mvn -q -DskipTests test-compile` 通过。

## 部署

- commit / 环境 / 部署后验证结果: 当前分支独立提交；未部署、未合并 integration。

## 遗留 / 跟进

- H4 详情抽屉与顶部 summary 由 H4 任务所有；H5 前端只发出 summary 刷新事件，不重复实现指标卡。
