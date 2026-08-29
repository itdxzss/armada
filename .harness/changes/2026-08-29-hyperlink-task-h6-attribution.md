# 变更记录：超链任务 H6 深度归因与访问分析

- 日期 / 分支 / worktree: 2026-08-29 / `codex/hyperlink-task-h6-attribution` / `armada-h6-attribution`
- 需求来源: 用户 H6 任务、shared contract、`2026-08-28-hyperlink-task-attribution-analysis-design.md`
- 状态: 已完成

## 目标（一句话）

交付短链公网点击事实、深度归因、首访趋势和封号原因分布，且不新增点击流水、趋势桶、封号或短码映射表。

## 缺口拆解 / 任务清单

- [x] 公网短码事务、302/404/410、并发 UV/PV。
- [x] 深度归因筛选、分页、排序、敏感字段脱敏与异步导出 writer。
- [x] 首个 UV 起算的 12～72 小时动态分桶、解读和 Top 3。
- [x] 封号原因按 usage 唯一账号分组和稳定未知回退。
- [x] 90 天首触环境清理。
- [x] 定向测试与最终相关回归。

## 关键设计决策

- 公网唯一跨租户查询仅允许 shortCode 精确反查并锁 recipient；取得 tenantId 后立即恢复租户上下文，内容与 runtime 更新继续受租户拦截器保护。
- 竞品旧实现把 recipient 累计 PV 放进首访桶，但当前模型没有逐次访问时间，无法证明实际半小时区间。按用户红线，趋势只对 firstVisitAt 聚合新增 UV，真实总 PV 取 runtime，桶 PV 返回 null 和 `UNAVAILABLE_CUMULATIVE_ONLY`。
- 详情导出复用 `marketing_task_export_job`，H6 worker 只领取 `ATTRIBUTION/VISIT_TREND`；普通营销 worker 增加模式过滤，避免交叉领取。状态与下载仍由 H4 公共外壳负责。
- 不新增表或迁移；归因从 recipient、封号从 account_usage、总点击从 runtime 读取。

## 验证（evidence-before-done）

- 定向测试：后端 7 个用例中 6 个通过，H2 对重复绑定参数的 GROUP BY 表达式报方言错误；改为派生表按 bucket_no 分组。前端 worktree 缺依赖，pnpm 安装因沙箱网络不可达停止。
- 最终相关回归：`mvn -f armada-api/pom.xml -Dtest=HyperlinkH6PublicAndMapperH2Test,HyperlinkTaskAnalysisServiceTest,HyperlinkTaskExportServiceTest,HyperlinkH6EndpointContractTest test`，10/10 通过。
- 前端 H6 合同回归：7/7 通过；`vue-tsc --noEmit --skipLibCheck` 通过。
- 静态验证：相关 Mapper XML 可解析，`git diff --check` 与前端 Prettier check 通过，禁止表名未出现在任何新增 DDL 中。

## 部署

- commit / 环境 / 部署后验证结果: 仅功能分支提交，不部署、不合并 integration。

## 遗留 / 跟进

- 当前累计模型无法恢复历史逐次 PV 时序；如未来产品必须展示逐时 PV，需要单独冻结新的合规事实模型，不能在查询层猜测。
