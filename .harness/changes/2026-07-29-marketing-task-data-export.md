# 变更记录：营销任务数据导出

- 日期 / 分支 / worktree: 2026-07-29 / `1.0.2-snapshot-export` / `D:\idea_project\armada`
- 需求来源: `D:\documents\营销任务数据导出_PRD需求文档_V1.1.docx`、`docs/superpowers/specs/2026-07-28-marketing-task-data-export-design.md`
- 状态: 代码实现与专项复审完成；待明确 MySQL 8 测试库执行迁移、真库查询和数据模型文档生成

## 目标（一句话）

在普通营销任务页面提供租户隔离、可审计、支持大数据量的两种 Excel 导出，并保证国家进群明细与全量统计均来自明确落库事实。

## 缺口拆解 / 任务清单

- [x] 逐项提取 PRD 的国家进群、任务汇总、群组明细字段。
- [x] 确认实际进群号码链路：营销成功群 → `join_task_result` 成功记录 → `account.ws_phone`。
- [x] 对账任务、发送 attempt、账号状态、群状态、发言权限和成员数现有字段。
- [x] 确认 P0：国家导出 scope 复用系统国家字典及字段编码，返回 249 个真实地区；共享区号由配置表唯一映射。
- [x] 确认 P0：使用后端持久化挂载目录，文件保留 7 天。
- [x] 确认 P0：历史进群时间采用方案 A，`joined_at` 为空时回退 `updated_at`。
- [x] 确认 P0：服务端记录统一 `snapshot_at`；事实查询按该时间截止，字段文字与时间格式沿用页面现有展示口径。
- [x] 冻结 P1：计划发送、发送结果、群状态、发言权限、账号异常、失败原因、备注均复用现有页面/落库事实口径。
- [x] 按 TDD 新增 Flyway、Service、Controller、Worker、Mapper 与流式 Excel Writer，并补充 H2 Mapper 生命周期测试和 SQL 契约测试。
- [x] 进入前端仓库读取其 `AGENTS.md` 后实现入口、弹窗、任务轮询和成功后自动下载；导出记录入口后续再开发。
- [x] 复审修复：Connector/J 逐行流式读取、请求集合排序防重、实际执行时间、多任务合计行、国家明细最新发送账号权限、最新可用协议群状态和所选群范围统计。
- [x] 导出 Worker 使用独立调度的 5 分钟心跳和 claim token 续租；即使 SQL 尚未返回首行也会续租，丢失租约后旧 Worker 立即停止且不能发布文件。
- [x] 按编码规范把 Mapper 成功更新封装为作业实体参数，并把导出运行依赖封装为运行时对象，新增普通方法和构造器均不超过 5 个参数。
- [x] 创建接口按冻结契约返回 HTTP `202 Accepted`；原始任务和国家集合分别限制为 100/249 项，ISO2 只允许两位 ASCII 字母，重复国家只校验一次，避免请求放大。
- [x] V083 使用生成列唯一索引限制同租户同用户同时最多一个活动作业；相同请求继续复用，不同请求明确提示先等待当前导出完成，防止不同筛选范围挤占 Worker 队列。
- [x] 回滚方案仅回滚应用并保留已执行 Flyway 的增量结构、审计记录和国家主数据，不执行破坏性 DDL/DML。
- [x] Java、安全、数据库和 TypeScript/Vue 专项评审均通过，代码层 P0/P1 为 0。
- [ ] 在明确的 MySQL 8 测试库完成 V082/V083、DbTest、`EXPLAIN` 和数据模型自动生成；未执行真实部署。

## 关键设计决策

- 导出入口只位于普通营销任务页面，两种模式共用一个异步导出任务能力。
- `account` 表只提供受控账号号码；进群成功和群归属由 `join_task_result` 证明，不能只查账号表。
- `group_jid` 是 WhatsApp 群唯一标识，不是手机号。
- 国家进群最终去重键为 `marketing_task_id + 规范化手机号 + group_jid`。
- 导出只读取落库事实，不调用协议层，不修改营销任务、账号或群组状态。
- 不修改任何已执行 Flyway；数据库变化只新增后续版本迁移。
- 国家接口新增 `scope=marketing-export`，不改变原 `scope=ip` 的 MIXED 与 IP 支持范围。
- 国家选项直接复用 `country` 主数据；相同规范化区号由 `country_phone_prefix_mapping` 配置唯一展示 ISO2。
- 当前业务为单宿主机部署，导出文件使用持久化挂载目录；数据库只保存相对 key 和审计元数据。
- 本期不接 OSS/S3/MinIO，不建设导出记录页面；前端仅轮询本次任务并在成功后自动下载。

## 验证（evidence-before-done）

- PRD 已完整提取全部段落和表格；本机缺少 LibreOffice，无法完成 DOCX 页面渲染，但字段表已通过 `python-docx` 结构化读取。
- 已核对 `MarketingTask`、`MarketingTaskTarget`、`MarketingTaskSendAttempt`、`JoinTaskResult`、`AccountState`、`GroupLinkPreview`、`GroupLinkHealth` 及相关 Mapper/状态枚举。
- 后端完整聚焦门禁：Controller、导出 Service、Writer、H2 Mapper、SQL 契约、国家服务和关联渠道回归共 72 项通过，失败 0、错误 0、跳过 0；覆盖 HTTP 202、输入上限、活动作业配额、独立心跳丢失令牌、无数据错误、最新协议群状态、合计行和流式读取配置。
- 后端 `mvn -q -DskipTests verify` 通过，确认完整编译和打包阶段无新增错误。
- HTTP 202、原始集合上限、ISO2 格式和国家去重查询新增聚焦回归；Controller 与导出 Service 聚焦测试通过。
- Java、安全和数据库专项复审均为 `APPROVE`，代码层 P0/P1 为 0；COUNTRY 与 FULL 的最新协议状态排序已统一为轮次、尝试次数和记录 ID 降序，V083 活动作业生成列与唯一索引的 MySQL 8 语义已通过只读复审。
- 前端聚焦测试：API、组合式函数与 UI 契约共 16 项通过；`pnpm typecheck`、局部 ESLint、Prettier 检查、`pnpm build` 均通过。
- 后端全量 `mvn test` 已尝试；本机无真实 MySQL，既有真库 `*DbTest` 大量报 `Communications link failure`，另有与本需求无关的 Windows 协议命令测试失败，因此不能声称全量测试通过。
- MySQL 8 专用 CTE、窗口函数、`REGEXP_REPLACE` 与执行计划仍需在明确的测试库执行 DbTest/`EXPLAIN`；当前仅完成 XML 解析、SQL 契约和 H2 可执行部分验证，未连接未知或生产数据库。
- 部署脚本 `bash -n` 语法检查通过；本地部署测试受限于 Git Bash 未安装 `rsync`，生产打包测试受限于仓库缺少 `armada-deploy/prod/protocol/.env.example`，未执行真实部署。

## 部署

- commit / 环境 / 部署后验证结果: 尚未进入部署阶段。
- 部署前必须先查询目标库 `flyway_schema_history`：若 V083 已执行，禁止修改其文件或触发 checksum 修复，需把活动作业唯一约束拆为新的后续 Flyway；当前仅能确认此前用户截图中的环境执行到 V080，不能代替目标环境核验。

## 遗留 / 跟进

- 容量上限和其余 P1 展示细节在压测与联调中确认。
- `.harness/wiki/数据模型.md` 必须在已应用 V082/V083 的明确 MySQL 测试库上重跑 `gen_datamodel.py` 后刷新；本机无 Docker、MySQL 客户端且未获授权连接共享库，当前只完成生成器业务分组登记，未手工修改自动生成文档，也不声称已刷新。
- 导出记录入口和历史记录可见范围留待后续需求开发。
