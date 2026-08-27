# 变更记录：群维度首次唯一分类

- 日期 / 分支 / worktree: 2026-08-26 / `codex/group-canonical-first-classification` / `armada/.worktrees/group-canonical-first-classification`
- 需求来源: 当前会话，业务 owner 2026-08-26 15:01:51 CST 确认“全部按 A”
- 状态: `LOCAL_VERIFIED`（待 D3 test1 联调）
- `change_id`: `2026-08-26-group-canonical-first-classification`
- `scope_hash`: `316839dc898e558b494ff6835abf15cd55d942e8a730c927ba76a6c25be61825`

## 目标（一句话）

把历史群/上控后群从账号累积双标签改为租户内规范群的唯一、首次原子写入后不可变分类。

## 缺口拆解 / 任务清单

- [x] 完成准入、四轮事实对账和一次性决策包
- [x] 业务 owner 确认全部 A 并冻结 `scope_hash`
- [x] 建立后端、前端独立 worktree
- [x] 先补后端分类、Mapper 并发、迁移和查询失败测试
- [x] 实现 `wa_group` 唯一分类与旧 API 互斥兼容
- [x] 群列表和依赖历史分类的查询切读唯一事实
- [x] 前端改为单分类并移除 `BOTH`
- [x] 完成 Web / Android 协议事实核对，确认两仓都需补最小可靠事件契约
- [x] Web 补齐快照完整性、查询边界和稳定 self-membership 证据
- [x] Android 补齐 sync 边界并透传原始 self-membership 证据
- [x] 运行本地聚焦与全量相关门禁
- [x] 专家评审并修复高优先级问题
- [ ] 申请 test1 D3 授权后运行 Runner quick/canary/soak

## 关键设计决策

- 最终事实落 `wa_group`，作用域为 `(tenant_id, group_jid)`。
- 链接导入、预览和普通 metadata 不定类；完整 baseline 与 baseline 后可靠新增事实提交候选。
- 第一个成功原子写入获胜，已定分类不因晚到旧事件改写。
- 旧 BOTH 先 dry-run；有证据取最早，无证据或平局保守归 `HISTORICAL`。
- `groupClassification` 为 canonical API；旧双布尔仅兼容一个发布窗口且必须互斥。
- 账号 baseline/binding 保留，独立历史群页面不改语义。

## 验证（evidence-before-done）

- 后端 canonical/迁移/查询/API/协议边界聚焦套件：136/136 PASS。
- 真实 MySQL 8.4 Testcontainers：V140 迁移 3/3、列表切读 4/4、批量/锁序/性能（含相反候选并发）30/30；合计 37/37 PASS。
- 400 群批量分类实测 SQL 数：cold=6、warm=3；完整 phase cold/warm 均为 10，未退化成逐群 N+1。
- `xmllint --noout`：四个本期 Mapper XML PASS。
- 前端分类聚焦测试 15/15、`pnpm typecheck`、`pnpm build:staging` PASS。
- Web Jest 全量单测 1186/1186、竞态聚焦 76/76、`npm run build` PASS。
- Android `go vet ./...`、`go build ./...`、相关包普通测试与 `-race` PASS。
- 四仓 `git diff --check` PASS。
- 后端全量 `mvn test` 未通过：默认 Spring 测试连接本机 MySQL 失败，且既有 H2 fixture 缺表/不支持 `FORCE INDEX`；运行到 663 tests、0 failures、59 errors 后停止重复环境重试。该结果不冒充全量通过。
- Android 全量 `go test ./...` 仍有两个既有环境/基线障碍：worktree 路径部署根校验、`pkg/noise` 缺向量文件；业务相关包均通过。

## 专家复核修正

- 把 `postControlObservedAt` 从“按 cut 所在整秒放宽”收紧为精确毫秒边界，避免查询前同秒观察被永久误标为上控后。
- 独立历史群手工刷新在账号 baseline 尚未捕获时，同事务按既有锁顺序补写 `HISTORICAL` 候选。
- `skippedGroupCount` 缺失时快照 fail-closed，负数直接拒绝；post-control 证据必须同时具备完整且一致的查询边界。
- 400 群路径由逐群 SQL 改为稳定排序的批量 ensure/current-read/CASE update，并在真实 MySQL 发现、修复交叉冷启动 deadlock：先按兼容句柄主键锁定，再按 canonical JID 锁定。
- 删除为适配新 wire 临时增加的内部兼容构造器，直接更新调用方；旧双布尔兼容只保留在对外 API 边界。
- V140 在归约前补绑确定性的 `wa://group/{jid}` 句柄，保证迁移完成后列表立即读到 canonical 分类。

## 专家评审结论

- 阻断项：0（已修复批量冷启动 deadlock、快照边界误分、内部兼容 shim 和迁移后句柄不可见问题）。
- 重要项：0。
- 剩余风险：尚未做四仓 test1 联调；协议旧版本不带查询边界或 `sourceEventId` 时会 fail-closed，发布必须先升级 Web/Android 协议，再升级后端和前端。

## 部署

- commit / 环境 / 部署后验证结果: 尚未部署；D3 未申请。

## 遗留 / 跟进

- test1 当前缺 soak/CloudWatch wrapper 与 Runner CloudWatch IAM；未补齐只能判 `STAGING_BLOCKED`。
- 自动数据模型文档必须由真实数据库元数据生成；D3 前不连接共享库、不手工伪造，故留到 test1 Flyway 后刷新。
