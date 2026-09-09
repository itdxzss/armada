# 变更记录：动态任务云端 LID 受众

- 日期 / 分支 / worktree: 2026-09-09 / codex/lid-status-20260909 / ../lid-status-20260909/backend
- 需求来源: 用户要求补齐 LID 动态发布的后端与页面，1714 已封禁，排除测试。
- 状态: 本地实现与验证完成，待环境部署及真实业务验收

## 目标（一句话）
缺少可用通讯录时，Android 动态任务自动获取完整云端 LID 受众，页面能观察准备状态。

## 缺口拆解 / 任务清单
- [x] Android 固定自身分页查询、严格响应与 LID 标量解析
- [x] 账号域完整快照、代次更新、过期与失败状态
- [x] 协议端口、任务等待就绪、失败原因
- [x] 页面受众来源/人数/状态与刷新
- [x] 离线测试、迁移与部署操作说明

## 关键设计决策
- 复用 ContactPort 路由；Web 明确不支持云端查询，保留现有通讯录受众。
- 新增 account_status_audience 一账号一行，聚合根是账号的动态候选受众快照。它拥有独立抓取代次、有效期和完整性，不是已解密通讯录联系人。account_contact 以非空 phone 唯一，account_contact_sync 是通讯录事件快照版本；混写会损失姓名并污染好友统计，故不能承载云端快照。
- 仅保存完整 LID 列表，不保存加密元数据；最多 5000 人，不截断超限快照。不能由云端记录推断双向好友或可见性。
- 完整分页在有界后台执行器中执行，不占用任务数据库事务。事务提交后才启动；租约与 request token CAS 拒绝旧结果，过期租约可恢复。失败不会无限自动重试。
- 优先已有通讯录，否则 Android 自动准备 LID；准备中保留 pending，不增加发送 retryNum。发送仍与实时动态隐私求交。
- 页面沿用动态发布任务及权限；刷新仅准备快照，不能重发已经终态的任务。

## 验证（evidence-before-done）
Java 账号联系人、动态任务、协议端口 83 个测试类共 371 项通过（0 失败/错误/跳过），包含新增 H2 真实迁移与租户 SQL、回滚不启动抓取、完整快照才入队、等待不消耗 retryNum、HTTP 路径与大小写字段。新增 collector 先以空实现获得 4 个断言失败，完成实现后转绿。
Go vet/build 通过，45 包通过，仅原有 pkg/noise 8 个基线失败；5 个相关包 race 通过。
前端 typecheck、ESLint、Stylelint、build，2 个单测，1 个浏览器交互验收通过。
API 文档生成测试通过；本功能 API 文档已由 parse_endpoints.py/format_api.py 生成。
全量 mvn test 会混入 DbTestBase 真库测试，发现后停止，后续明确选择本地域测试，不将该全量执行计为通过。

## 部署
- commit / push / 部署 / 真实发布: 尚未执行；目标环境和可用自有测试账号尚未指定。

## 遗留 / 跟进
真实接收端可见性需要可用自有发送/接收账号验证，1714 不参与。

## 文档生成限制

按数据模型规范尝试 gen_datamodel.py，因缺少 `/tmp/wheel_tables.tsv` 等真库 information_schema 导出失败；未手改全库自动生成文档。V181 和回滚已提供，部署后从选定环境导出再生成。不能用合成 H2 数据冒充当前全库模型。

## 交付文件

- 操作与部署说明：`2026-09-09-feed-status-lid/operation-guide.md`
- 自动生成任务 API：`2026-09-09-feed-status-lid/feed-task-api.md`
- 迁移与回滚：同目录 `db-migrations.sql` / `rollback.sql`
