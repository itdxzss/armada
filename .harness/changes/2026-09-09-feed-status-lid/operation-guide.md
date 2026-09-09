# LID 动态发布：交付与操作说明

2026-09-09。本地实现与验收完成，尚未 commit、push 或部署。账号 1714 已封禁，排除实测。真实 WhatsApp 发布、ACK、接收端可见性尚未验证。

## 已完成内容

1. Android：固定自身云端联系人分页查询；解析已验证的 Argo schema 与 ADJID LID 标量，保留完整 `@lid`。普通 Status 独立发送路径不再把受众当成 mentions；实时隐私过滤、完整设备和 Sender Key 分发检查继续生效。
2. Java：ContactPort 路由与 Android HTTP adapter；独立 `account_status_audience` 完整快照；分页同版本、游标防循环、最多 5000 人，超限/缺页失败关闭。并发最多 4 个抓取工作（2 执行、2 等待），事务提交后才启动，120 秒收集截止、300 秒租约、24 小时快照有效期，旧代次不能覆盖新结果。
3. 动态任务：优先具名通讯录，没有时 Android 自动准备云端 LID。准备中保持 pending，不消耗发送 retryNum；完整快照才以 `status@broadcast` 和完整 JID 清单入队。Web 缺通讯录时明确失败，不假装支持云端恢复。
4. 页面：当前候选受众来源、状态、人数、更新时间、失败码；operate 权限可重新准备；打开明细自动刷新，关闭后停止。候选人数不是实际发送人数或送达人数。

云端联系人与现有通讯录独立，避免覆盖姓名及好友计数。没有对云端记录作互存好友或最终可见性的承诺。

## 部署顺序

先确认目标环境和可用自有测试账号，再使用各仓现有发布流程：

1. 合入并部署 Android 工作区改动到该环境实际承载账号的 worker 节点。普通构建即包含本功能，不需要 `statusprobe` 编译标签；协调器沿用既有按手机号转发路径。确认云端查询接口与 Status 路径来自同一新版本。
2. 通过 Flyway 执行 `V181__account_status_audience.sql` 并发布 Java 后端。发布前重新检查迁移版本未与其他分支冲突。确认新表和唯一键存在、账号受众准备与任务消费正常。
3. 发布前端构建产物。无需新菜单或权限码，沿用动态任务 view/operate 权限。
4. 使用可用自有发送账号和明确自有接收账号进行小范围真实验收，分别记录协议接受、ACK、接收端可见。1714 不参与。

V181 不修改现有联系人或任务数据。回退时先停用新增任务并回滚应用，再按 `rollback.sql` 删除新快照表。不得先删表导致运行中的新后端报错。

数据库模型文档生成器依赖真实 information_schema 的 `/tmp/wheel_{tables,columns,indexes}.tsv`，本机缺少该输入。部署后按现有导出流程重新生成全库文档；本次未手改或伪造全库模型。

## 页面操作

1. 进入「任务中心 → 动态营销 → 动态发布任务」，点「新建动态消息任务」。
2. 在「账号范围」选定发送账号。这里统计的是发动态的账号数，不是接收人数。排除被封禁或不在线账号；首次验收只选指定自有账号。
3. 填任务名称、推广标题、正文/链接和可选图片。首次验证将最大执行账号数设为 1、失败重试次数设为 0；需要先观察准备过程时使用延迟启动。
4. 开启任务开关后保存，系统圈定账号。仅保存的草稿尚未圈号，不会查询云端联系人或发送动态。
5. 在任务行打开「账号数据」。具名通讯录可用时显示该来源；Android 无具名通讯录时显示「云端 LID → 准备中 → 已就绪」。无需手填 LID，也无需额外点击某个 LID 发布开关。
6. 准备失败时先看受众失败码和账号状态，再点「重新准备」。该动作只更新候选快照：已经完成或失败的任务不会因此重发；需要重新发送时创建新任务。Web 账号应先完成原通讯录同步。
7. 同时检查发送状态与真实接收端。页面的“受众已就绪”只证明候选快照完整，不能替代真实可见性验收。

## 验证证据

| 检查 | 结果 |
| --- | --- |
| Java 联系人、动态任务、协议端口 | 83 类，371 项，0 失败/错误/跳过 |
| 新增迁移与快照 SQL | H2 MySQL 模式实际运行 V181、真实 Mapper、生产租户插件和 Spring 事务通过 |
| 任务完整 LID 入队 | H2 证明准备中 retryNum=0，完整后 status@broadcast + 10001@lid 入队 |
| Android go vet / go build | 通过 |
| Android go test ./... | 45 包通过，仅原有 pkg/noise 8 个基线失败 |
| Android Cloud/Status 竞态检查 | 5 个相关包通过 |
| 前端 typecheck / ESLint / Stylelint / build | 通过 |
| 前端状态单测 | 2 项通过 |
| Playwright 浏览器 | 1 项通过：失败→准备中→就绪，防重复、权限与关闭轮询 |
| API 文档生成测试 | 通过，本目录 feed-task-api.md 自动生成 |
| 真库迁移、线上部署、真实发布和接收 | NOT_RUN |

全量 `mvn test` 会混入 DbTestBase 真库测试，发现后停止，未将该次执行算作通过。后续回归明确选择本次相关领域并排除 DbTest。

合成数据页面截图：

![候选受众状态页面](/Users/daishuaishuai/IdeaProjects/.codex-worktrees/lid-status-20260909/backend/.harness/changes/2026-09-09-feed-status-lid/audience-page-synthetic.png)

## 工作区

- Android：`/Users/daishuaishuai/IdeaProjects/.codex-worktrees/lid-status-20260909/android`
- Java：`/Users/daishuaishuai/IdeaProjects/.codex-worktrees/lid-status-20260909/backend`
- 前端：`/Users/daishuaishuai/IdeaProjects/.codex-worktrees/lid-status-20260909/web`

三个分支均为 `codex/lid-status-20260909`，本次没有混入主工作区其他未提交改动。
