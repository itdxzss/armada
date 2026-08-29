# 变更记录：竞品超链任务反推与单菜单详细设计

- 日期 / 分支 / worktree: 2026-08-27 / `1.0.3-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户要求以 `hylbuiaxykfrontendsource` 为第一事实源，完整反推竞品超链任务的字段、列表、功能和弹框，再形成 Armada 单菜单详细设计
- 状态: 已完成（静态前端反推、公共契约、六份任务方案和按查询/运行负载拆分的物理模型均已冻结；外部协议/账务能力待实施）

## 目标（一句话）

完整还原竞品「超链任务」前端业务契约，并在不遗漏竞品功能的前提下形成可实施的 Armada 详细设计，同时为策略、素材和市场分析冻结必要接口。

## 缺口拆解 / 任务清单

- [x] 还原任务列表、筛选、汇总卡、表格列、行操作；确认页面为手动刷新、后端数据约每分钟同步。
- [x] 还原新建、编辑、查看、复制抽屉的全部字段、默认值、显隐和校验。
- [x] 还原账号筛选抽屉、模板/策略/数据包选择与可用账号试算。
- [x] 还原提交前 7 秒「最后核对」弹框与全部二次确认交互。
- [x] 还原任务详情的五个 Tab、筛选、列表、指标和导出。
- [x] 还原接口路径、方法、请求字段、响应字段与状态枚举；确认任务无删除 API。
- [x] 与 Armada 一期资源、发送协议、租户权限、号码池状态、账号画像和计费能力逐项对账。
- [x] 输出超链任务单菜单详细设计和跨菜单预留契约。
- [x] 冻结六份后续方案共用的 HTTP、DTO、枚举、指标、权限、错误、导出和职责边界合同。
- [x] 按 H1→H6 顺序完成列表、表单、生命周期、收信人、账号统计、归因与风险六份实施方案。
- [x] 交叉核对公共契约、数据模型、旧总设计和六份方案，修正趋势默认窗口、协议关联和权限/错误码残留。

## 关键设计决策

- 竞品静态前端是需求事实源；Armada 适配不得静默删字段、删交互或改变页面语义。
- 竞品存在但 Armada 暂无后端能力的功能，仍保留在完整性清单中，逐项标明依赖与实现路线。
- 本轮只详细设计「超链任务」菜单；策略、素材、市场分析只冻结被任务消费或生产的契约。
- 反推证据记录在 `docs/superpowers/reviews/2026-08-27-hylb-hyperlink-task-reverse-evidence.md`。
- 任务页调用通用按钮编辑器时锁定 `cta_url`，不把通用组件的四类按钮误判成任务能力。
- 完整账号筛选保留好友数、注册天数、允许拉群、轮号和五类来源；竞品编辑回填漏字段不复制。
- 余额、单价、预计冻结和 7 秒核对弹框是完整功能；通过 `HyperlinkBillingGateway` 留出真实计费实现。
- 任务列表不做前端定时刷新，不提供任务删除；未开始可编辑，开始后只读。
- 竞品前端不能证明库表数量；Armada 不以少表为目标，执行链仍按 50 万 recipient 跨多个调度轮分配设计，
  最终使用 10 张任务表 + 1 张共享 `account_profile`。领号作业、轮次、账号同步用量和账号累计统计按运行或
  查询负载独立落表；当前业务单任务发送量不超过 10 万，访问趋势直接聚合 recipient，不维护 30 分钟桶。
- 配置/长文本/运行态垂直拆为 `hyperlink_task`、`hyperlink_task_content`、
  `hyperlink_task_runtime`；任务无删除 API，因此主表不落死的软删列。
- task 的短链开关只作为 content 按钮配置的派生投影并与内容同事务保存；content 为两个素材引用补反查索引。
- runtime 以累计秒数 + 当前运行段起点表达真实执行时长，暂停冻结、继续续算；`metrics_updated_at` 只由发送
  指标投影推进，公网点击原子更新 UV/PV 但不污染发送指标新鲜度。
- 同一任务内一个收信号码最多发送一次；recipient 同时承载实际轮次/账号、唯一 command/ACK、最终状态与短码。
  round 只负责 due scan、每轮选号和分配剩余 recipient；超时恢复查询或重放同一个 command；任务停止前未提交
  的 recipient 按 `TASK_STOPPED` 失败落账，不另设跳过状态。
- round 的业务到期时间与 worker 租约分离，选号/派发进程宕机只在租约过期后按状态和 version 接管。
- `hyperlink_billing_reservation` 与任务 1:1，按整份冻结受众保存报价，并以待操作类型、当前幂等键和下次恢复时间
  区分冻结/调整/结算/释放；外部钱包继续保有总账。
- 深度归因不建逐次点击流水；recipient 保存点击累计、首末时间和首触环境，敏感首触字段保留 90 天。
  目标国家以 JSON 数组快照支持多国家数据包；任务/模板标题统一扩到 1024。
- 账号筛选按源码纠正为三个独立字段：`account_type`（账号类型）、`wid_type`（类型）、`platform`
  （设备类型六值）；后两者均从现有账号事实派生，不加冗余列，导入批次号直接使用现有 batch ID。
- 运行指标按 `recipientId` 计数；周期按状态/轮次为空索引认领剩余 recipient，不重复生成收信人，
  也不使用单一递增派发游标。
- `account_max_send_num` 按竞品 tooltip 是任务内跨轮成功上限；task_account_usage 同步占槽，round_account
  固化每轮选号，不能用分钟级账号统计或 recipient COUNT 参与派发判断。
- 50 万号码不使用单个长事务冻结；recipient_claim 记录代次操作互斥/批游标/租约，data_package_phone 保存
  claim owner，创建宕机或计费失败时可按任务精确续跑/释放；claim 进入 OWNED 后释放代次操作锁，使其他任务
  能继续领取同代剩余号码，号码级 owner 仍防止交叉释放。
- ACK 先推进 recipient/account_usage，runtime/round 和账号统计由幂等投影器批量维护，
  避免逐条争抢任务热行。
- 无时间范围的账号统计走累计投影并 JOIN account_usage 读取唯一账号展示快照；account_stat 不重复保存号码、
  国家、类型和入库时间。有范围按 recipient 任务×时间索引精确聚合；访问趋势按
  recipient.`first_visit_at` 分桶并汇总 `click_count`，命中任务×首访时间索引，当前单任务最多扫描 10 万行。
- 为市场分析预留 90 天日表 + 滚动 8 天小时表；小时页面不再实时扫描全租户 recipient。
- 任务封号/失效事实并入 `hyperlink_task_account_usage` 的首次失效字段，`/ban-stats` 按此分组，不另建 1:1 封号表。
- 访问趋势严格照竞品 tooltip：窗口从第一个 UV 起算，同一收件人的全部 PV 近似归入首访桶，
  不按每次 click 的真实访问时间分桶。
- 外部账务采用本地 PROCESSING + 待操作类型/幂等键/重试时间 + Gateway 恢复/补偿的 Saga，禁止假装远程冻结与 MySQL 原子提交。
- 后续实施拆为 H1 列表、H2 表单/查看/复制、H3 发布/生命周期、H4 收信人流水/详情外壳、H5 发信账号统计、
  H6 归因与风险六份方案；共同引用 `2026-08-28-hyperlink-task-shared-contract.md` v1.1，不各自重定义字段和指标。
- 详情顶部摘要使用独立 `/summary` 接口，由 H4 交付公共详情外壳；四类详情导出统一复用现有异步导出作业，
  任务列表导出保持同步 CSV。
- 启用任务分批准备时 POST/PUT 可返回 PROCESSING 回执和有终点的短轮询；准备中/失败待恢复半成品不混入正常列表。
- 任务保存统一 application/json + 稳定素材 ID；复制通过创建请求的 `sourceTaskId` 标识来源，不新增 `/copy` 接口。
- 创建上下文、CREATE/START 报价和 Action 请求/回执已进入公共契约；START 与启用保存共用准备回执，
  金额只由后端 BigDecimal 计算，7 秒按钮倒计时不冒充报价有效期。

## 验证（evidence-before-done）

- 主任务分块逐段复核：列表 `321-423, 4687-5895`；表单/弹框 `887-2965`；详情 `3008-4624`。
- 路由封装双向核对：`router-CPQmbuR9.js:45958-46120, 46266-46317, 46877-46895, 47112-47116`。
- 依赖组件复核：账号筛选、按钮编辑器、WhatsApp 预览、素材选择/上传、访问趋势。
- 使用 `rg` 复核旧总设计中的错误关键词：任务删除、四类按钮、隐藏账号画像、自动刷新、去掉计费。
- `git diff --check` 通过；权威文档的旧发送模型、任务删除权限、未定保留期和
  `import_no` 新列等定向扫描均为 0 命中。
- 反向核对账号筛选 `account-platform-BYq5VTkz.js` 与 `account-filter-modal-BXDIvipG.js`，补齐并纠正
  `account_type / wid_type / platform / source` 四个相邻字段的真实标签和六值映射。
- 完整证据与置信等级见反推证据文档；页面/服务契约见任务详细设计。
- 数据模型按列表、详情、调度、回流、计费恢复逐路径复核扫描上界；实施验收须用 50 万 recipient 跨 3 个调度轮
  数据做 `EXPLAIN ANALYZE` 和并发压测。

产出：

- `docs/superpowers/specs/2026-08-28-hyperlink-task-shared-contract.md`
- `docs/superpowers/specs/2026-08-28-hyperlink-task-list-design.md`
- `docs/superpowers/specs/2026-08-28-hyperlink-task-editor-design.md`
- `docs/superpowers/specs/2026-08-28-hyperlink-task-lifecycle-design.md`
- `docs/superpowers/specs/2026-08-28-hyperlink-task-recipient-stats-design.md`
- `docs/superpowers/specs/2026-08-28-hyperlink-task-account-stats-design.md`
- `docs/superpowers/specs/2026-08-28-hyperlink-task-attribution-analysis-design.md`
- `docs/superpowers/specs/2026-08-27-hyperlink-task-competitor-parity-detailed-design.md`
- `docs/superpowers/reviews/2026-08-27-hylb-hyperlink-task-reverse-evidence.md`
- 已纠偏 `docs/superpowers/specs/2026-08-27-hyperlink-task-strategy-asset-analysis-design.md`
- 已纠偏 `docs/business/hyperlink-marketing-data-model.md`：账号画像不隐藏，点击归因首触 IP/UA 并入 recipient，计费改为硬依赖

## 部署

- commit / 环境 / 部署后验证结果: 本轮仅反推与设计，不部署。

## 遗留 / 跟进

- `js-reverse` 技能引用的 `tool-index.md` 在当前技能安装目录缺失；本轮使用本地 readable 构建产物、路由/API 封装和静态依赖图完成证据化回退。
- 静态前端不能证明竞品内部领号、轮次和计费算法；这些未冒充竞品事实，Armada 自身规则已在
  `docs/business/hyperlink-marketing-data-model.md` §4 冻结。后续真实 API 样例只用于兼容性验证。
