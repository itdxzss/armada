# 超链任务六方案 test1 验收测试用例

> 状态：测试设计已冻结，尚未执行。
> 套件版本：`hyperlink-task-test1-v1`。
> 设计提交：`03aeca98afd4870b83e5727f6b3d8de353266844`。
> 设计束 SHA-256：`434757e6c2d1565bea0fddc57a380b4a888c5aca9a87cce972449bc16df09ca4`。
> 适用环境：第一套测试环境 `test1`；不得直接用于生产。

本文把超链任务公共契约及 H1～H6 六份设计转换为部署后可执行的验收案例。案例验证的是 test1 上实际部署的
四仓制品，不以本地单元测试、代码存在、进程存活或 `quick PASS` 代替业务验收。

设计基线：

- [公共契约 v1.1](./2026-08-28-hyperlink-task-shared-contract.md)
- [H1 任务列表](./2026-08-28-hyperlink-task-list-design.md)
- [H2 新建、编辑、查看与复制](./2026-08-28-hyperlink-task-editor-design.md)
- [H3 发布与运行生命周期](./2026-08-28-hyperlink-task-lifecycle-design.md)
- [H4 收信人流水统计](./2026-08-28-hyperlink-task-recipient-stats-design.md)
- [H5 发信账号纬度统计](./2026-08-28-hyperlink-task-account-stats-design.md)
- [H6 归因、访问趋势与封号原因](./2026-08-28-hyperlink-task-attribution-analysis-design.md)

当前代码尚未实现公共契约中的 `/api/hyperlink-tasks` 全套接口；现有
`/api/hyperlink-tasks/click-analysis` 是更早的数据包点击分析入口，不能冒充 H6。故本文当前只能标记为
`TEST_DESIGNED`，所有执行结果保持 `NOT_RUN`。

## 1. 验收门禁与判定

### 1.1 执行顺序

```text
冻结 scope_hash、测试套件和四仓候选版本
→ 部署 test1
→ quick：版本、readiness、只读 UI、Kafka/Redis/资源检查
→ fixture-preflight：测试数据、权限、钱包、协议账号和清理能力
→ integration：H1/H2/H3/H4/H5/H6 业务案例
→ release-canary-web / release-canary-android：真实 WhatsApp 私聊
→ soak-60m：恢复、ACK、投影和运行时收敛
→ perf-readonly：10 万级查询与导出
→ 独立验证者复核证据
→ ACCEPTED 或 FAIL/BLOCKED
```

`quick` 只是后续测试的前置条件。即使 `quick=PASS`，只要任一 required 案例未执行，候选状态仍只能是
`STAGING_BLOCKED` 或 `STAGING_VERIFIED_PENDING_ACCEPTANCE`，不能记为 `ACCEPTED`。

### 1.2 Profile 定义

| Profile | 是否产生状态 | 用途 |
|---|---:|---|
| `quick` | 否 | 四仓版本、服务、页面只读主路径和运行态前置检查 |
| `integration` | 仅隔离夹具 | 对 test1 实际 API/UI/数据库/Kafka/Redis 投影执行功能验收 |
| `release-canary-web` | 是，严格限量 | Web 协议真实个人 JID 私聊、ACK、幂等和短链 |
| `release-canary-android` | 是，严格限量 | Android 协议真实个人 JID 私聊、ACK、幂等和短链 |
| `soak-60m` | 低频 | 状态恢复、分钟级投影、重复事件和资源趋势 |
| `perf-readonly` | 否 | 10 万 recipient、1,000 账号桶、导出和执行计划 |

### 1.3 PASS / FAIL / BLOCKED

- `PASS`：案例的所有必需步骤和断言均真实执行，证据完整且绑定同一候选版本、`scope_hash` 和测试束。
- `FAIL`：系统可测，但返回、页面、数据库、事件、统计、权限、性能或恢复行为违反设计。候选缺接口、返回 404、
  空按钮、假数据、第二次物理发送或错误状态均属于 FAIL。
- `BLOCKED`：无法形成可信判断，例如候选版本混用、夹具不可用、真实测试账号离线、钱包沙箱不可用、证据采集失败。
- required 案例不允许 `SKIPPED`。未实现测试执行器属于 BLOCKED；不能删除案例或改成 optional 让候选变绿。
- 需求或设计口径变化必须更新公共契约/设计、生成新 `scope_hash` 和测试束版本；候选冻结后不得为了适配失败结果
  修改期望值。

### 1.4 每次运行必须绑定的证据

每个案例至少记录：

- `runId`、`caseId`、profile、开始/结束时间、执行器版本。
- `scope_hash`、本文 SHA-256，以及后端、前端、Web 协议、Android 协议四份实际 full SHA/制品 digest。
- 环境别名、租户/用户/账号/数据包等脱敏资源别名；不得记录密码、Token、二维码、PEM、完整手机号或消息正文。
- 请求方法和脱敏路径、响应状态/业务码、关键字段断言。
- 需要时保存脱敏截图、SQL 结果摘要、Kafka/Redis 水位、协议 command/result/ACK 关联及执行计划。
- cleanup 结果和证据文件 SHA-256。缺少 cleanup 证据时，产生状态的案例不能 PASS。

## 2. 夹具、安全信封与清理

### 2.1 固定夹具别名

| 别名 | 说明 |
|---|---|
| `TENANT_A` / `TENANT_B` | 两个完全隔离的测试租户，用于越权和相同 ID 场景 |
| `USER_ALL` | 拥有 view/create/edit/action/export/attribution_sensitive |
| `USER_VIEW` | 仅 view |
| `USER_EXPORT` | view+export，无敏感归因权限 |
| `USER_NO_ACCESS` | 无超链任务权限 |
| `DP_EMPTY` | 0 条可领取号码的数据包 |
| `DP_SMALL` | 6 条输入、含 1 个重复号码，最终 5 个唯一 recipient，覆盖多国家与未知国家 |
| `DP_GENERATION` | 可在报价后新增下一代号码，用于代次、上界和 stale quote 验证 |
| `DP_LARGE_100K` | 10 万唯一 recipient，只用于受控准备/只读性能，不触发真实发送 |
| `ASSET_JPEG_OK` | 同租户、未删除、JPEG、≤500KB |
| `ASSET_BAD_TYPE` / `ASSET_TOO_LARGE` / `ASSET_OTHER_TENANT` | 素材拒绝案例 |
| `TEMPLATE_ALL_FIELDS` | 覆盖消息内容、两张图、按钮和短链开关 |
| `STRATEGY_ALL_FIELDS` | 只包含设计允许导入的六项策略字段 |
| `ACCOUNT_WEB` / `ACCOUNT_ANDROID` | 已授权、在线、具备 PRIVATE 超链能力的真实 test1 账号 |
| `ACCOUNT_OFFLINE` / `ACCOUNT_BANNED` / `ACCOUNT_OTHER_TENANT` | 账号筛选和能力门禁负例 |
| `RECIPIENT_REGISTERED` | 已授权真实收信号，能产生发送结果和可提供的 ACK |
| `RECIPIENT_UNREGISTERED` | 明确未注册 WhatsApp 的受控测试号码 |
| `WALLET_OK` / `WALLET_LOW` / `WALLET_RECOVERABLE` | 余额充足、不足和外部结果未知的测试钱包状态 |

夹具只能由 test1 专用、受审计的准备器创建；不得在生产代码中增加 mock、测试接口或内存兜底。需要直接准备数据库
事实的案例由 Runner 上的受限 fixture stage 完成，并在开始前冻结快照、结束后按 `runId` 清理。

### 2.2 真实 WhatsApp 安全信封

- 只允许 `TENANT_A` 的 `ACCOUNT_WEB`、`ACCOUNT_ANDROID` 向 `RECIPIENT_REGISTERED` 发送。
- 单个 profile 每种消息类型最多 1 条；同一次重放必须复用原 `commandId`，不能产生第二条物理消息。
- 不允许给非白名单联系人发消息，不允许加群、改代理、重登、扫码或修改真实账号资料。
- 执行前再次确认 test1 和资源租约；租约冲突返回 BLOCKED。
- canary 结束后停止任务、等待在途命令收口、释放 claim/余额/账号租约并保留脱敏证据。

### 2.3 基础种子任务

fixture stage 建立下列隔离任务或等价事实，ID 在证据中只记录别名：

| 别名 | 状态/用途 |
|---|---|
| `TASK_DRAFT` | `enabled=false, runStatus=0`，无 claim/billing/recipient/round |
| `TASK_WAITING` | `enabled=true, runStatus=0, provisionStatus=READY`，延后启动 |
| `TASK_RUNNING` | `enabled=true, runStatus=1`，含 PENDING/SENDING/各终态 recipient |
| `TASK_PAUSED` | `enabled=true, runStatus=3` |
| `TASK_COMPLETED` | `enabled=true, runStatus=2` |
| `TASK_STOPPED` | `enabled=true, runStatus=4`，含 `TASK_STOPPED` recipient |
| `TASK_DISABLED_HISTORY` | `enabled=false` 且保留历史 runStatus，验证停用展示优先级 |
| `TASK_OTHER_TENANT` | TENANT_B 下的同类任务 |

## 3. 公共契约用例

| ID | Profile | Given / When | 必须断言 | 证据 |
|---|---|---|---|---|
| `TC-C-001` | quick | 候选部署后读取四仓 runtime manifest | 四份实际 full SHA/digest 与候选完全一致；任一错版即 BLOCKED/VERSION_MISMATCH | runtime manifest、候选 manifest |
| `TC-C-002` | integration | TENANT_A 用户依次用 TENANT_B 的 task/job/resource ID 调用所有租户接口 | 统一 NOT_FOUND/403；响应和日志不泄露另一租户是否存在；无跨租户写入 | API 矩阵、DB 前后快照 |
| `TC-C-003` | integration | USER_ALL/VIEW/EXPORT/NO_ACCESS 遍历公共接口和前端按钮 | view/create/edit/action/export/sensitive 权限严格独立；无 delete 权限、按钮和 API；后端鉴权不依赖前端隐藏 | 权限矩阵、403、截图 |
| `TC-C-004` | integration | 对分页、pageSize、时间和空值提交边界值 | page 从 1；只允许 10/20/50/100/200；时间左闭右开；单端时间或 start≥end 返回 40001；数组/对象/计数空值合同一致 | 请求响应、边界数据 |
| `TC-C-005` | integration | 向每个排序接口提交白名单、未知字段和 SQL 片段 | 白名单排序稳定；未知/注入字符串返回 40001，Mapper 不拼接原始 sort；同值用设计规定的稳定键分页 | 响应、SQL 日志摘要 |
| `TC-C-006` | integration | 分别制造字段非法、资源不存在、状态冲突、报价过期、余额不足、账号/协议不足和计费不可用 | 返回 40001/40401/40910/40911/40912/42210/42211/50310/50311；前端不解析中文 message 分支 | 错误码矩阵、UI 提示 |
| `TC-C-007` | integration | 对任务双状态和 provisionStatus 全组合查询列表/详情/动作 | enabled=false 展示优先；准备中/失败不进正常列表；200/202 语义正确；READY/FAILED 后轮询停止 | API、轮询 trace、截图 |
| `TC-C-008` | integration | 使用包含 0 分母、子集计数和小数的指标夹具 | 单钩/双钩/点击/封号/号均/预计落地/人均公式与 HALF_UP 精度一致；UNREGISTERED 同时属于失败子集 | 公式输入输出、导出对账 |
| `TC-C-009` | integration | 创建四类详情导出，覆盖 0 行、跨租户、猜 jobId、过期和 10 万行 | 202 作业、snapshotAt 冻结、用户+租户隔离、终态停轮询、UTF-8 BOM、空数据仅表头、批量流式且不暴露文件路径 | 作业状态、CSV、内存曲线 |
| `TC-C-010` | integration | 执行创建/编辑/动作/导出/敏感读取/计费状态变化 | 审计事件齐全；日志和报告不含完整号码、token、shortCode、IP、UA、目标参数、凭据或正文 | 审计索引、脱敏扫描 |
| `TC-C-011` | integration | 快速切换筛选、分页、趋势范围并让旧请求晚返回 | 旧响应不能覆盖新条件；任务列表和详情无常驻自动刷新；只允许 provision 有终点短轮询 | 浏览器 trace、请求序列 |
| `TC-C-012` | integration | 把任一 required 执行器、夹具或证据采集器移除 | 顶层为 BLOCKED 且指出缺失项；不得空数组、默认 0、静默 SKIPPED 或沿用旧证据 | Runner 结果、缺失证据演练 |

## 4. H1 任务列表用例

| ID | Profile | Given / When | 必须断言 | 证据 |
|---|---|---|---|---|
| `TC-H1-001` | integration | USER_ALL 打开任务列表，create-context 正常 | 标题、Hyperlink 徽标、普通/超级模式、参考价、国家价、三模式说明和一分钟提示完整；金额来自服务端 | 页面截图、context 响应 |
| `TC-H1-002` | integration | create-context 失败但列表接口正常 | 列表仍可用；价格区显示失败和重试，不显示 0/假价格 | 网络 trace、截图 |
| `TC-H1-003` | integration | 依次使用任务名、五状态、三模式、国家、创建时间和组合筛选 | trim/LIKE 转义、国家快照、左闭右开、重置/Enter/回第 1 页准确；空态区分无任务和无结果 | 查询响应、截图 |
| `TC-H1-004` | integration | 切换 10/20/50/100/200，输入/清空任务名 | 默认 20；任务名非空自动 200，清空恢复 20；分页固定 createdAt DESC,id DESC，无重复漏行 | 请求序列、分页集合 |
| `TC-H1-005` | integration | 当前页混合短链开/关和 0 success 任务 | 六张卡只汇总当前页；点击 UV/率只含 shortLinkEnabled=true 行；0 分母显示 `-`；不请求全库聚合 | API 次数、卡片对账 |
| `TC-H1-006` | integration | 打开含全部 accountFilter、三模式、四消息类型和空值的列表 | 15 个逻辑列、tooltips、最多 3 标签+N、未知值回退、横向滚动、列设置/恢复默认完整 | 截图、DOM/列设置状态 |
| `TC-H1-007` | integration | 展示五 runStatus 与 TASK_DISABLED_HISTORY | 状态文案、颜色和 enabled=false 优先级准确；操作矩阵严格匹配设计且没有删除 | 行操作截图、DOM 权限 |
| `TC-H1-008` | integration | 对未开始/运行中/暂停任务执行 START/PAUSE/RESUME/STOP，并制造 version 冲突 | 确认文案准确；START 先报价；按钮只锁本行；成功刷新回执；40910 后刷新事实状态 | Action trace、状态前后快照 |
| `TC-H1-009` | integration | 点击编辑/查看/详情/复制和短链点击率 | 分别进入 H2 四模式/H4 recipients/H6 默认 24h visit-trend；未追踪显示 `-` 且不可点击 | 路由/抽屉状态、截图 |
| `TC-H1-010` | integration | 手动刷新、搜索失败、快速连续搜索 | 刷新保留条件/页码/列设置；失败保留旧表和条件并可重试；晚响应不覆盖新查询 | 浏览器 trace、页面状态 |
| `TC-H1-011` | integration | 按筛选导出全量、有四消息类型、中文、0 行 | CSV 固定 26 列、1～4 类型映射正确、忽略分页、完整筛选、UTF-8 文件名/BOM、0 行仅表头；错误 JSON 不下载 | CSV、响应头、筛选对账 |
| `TC-H1-012` | perf-readonly | TASK_LARGE 关联 10 万 recipient，列表含多租户数据 | 列表只读 task/content/runtime 1:1，不 JOIN recipient、不 N+1；执行计划命中租户/排序路径，延迟满足实现阶段冻结门槛 | EXPLAIN ANALYZE、SQL 数、P95 |

## 5. H2 新建、编辑、查看与复制用例

| ID | Profile | Given / When | 必须断言 | 证据 |
|---|---|---|---|---|
| `TC-H2-001` | integration | 从 H1 依次打开新建/编辑/查看/复制 | 同一右侧抽屉、左侧预览、四段表单；标题、可编辑性、类型锁定、主按钮和 sourceTaskId 符合四模式矩阵 | 四模式截图、detail 请求 |
| `TC-H2-002` | integration | 新建类型 1/3/4，读取历史类型 2，并切换类型 | 1/3/4 可保存；2 只读兼容且新建拒绝；单图文告警存在；切换不丢用户输入，保存只落当前类型有效字段 | 表单 trace、DB content 快照 |
| `TC-H2-003` | integration | 编辑各类型所有内容字段并观察实时预览 | 字段必填/长度和类型条件正确；预览即时更新；“最终效果以客户端为准”存在；查看模式完整回填 | UI 输入、预览截图、detail 对账 |
| `TC-H2-004` | integration | 引用 TEMPLATE_ALL_FIELDS | 只覆盖 messageType/messageContent/两图/按钮/短链；任务名、策略、数据包、账号范围和启动方式不变 | 导入前后表单快照 |
| `TC-H2-005` | integration | 引用 STRATEGY_ALL_FIELDS | 只覆盖 taskMode/accountFilter/maxExecuting/maxUse/maxSend/cycleInterval；消息、数据包、名称、间隔和启动方式不变 | 导入前后表单快照 |
| `TC-H2-006` | integration | 选择、上传、更换、清空素材，并提交坏类型/超限/跨租户 AssetId | 素材库搜索/上传交互完整；只接受同租户未删除 JPEG≤500KB；任务只保存 AssetId，不暴露路径 | UI trace、API 拒绝、DB 快照 |
| `TC-H2-007` | integration | 添加/删除 CTA，校验文字、URL和 useShortLink | 恰好 1 个 CTA；30 字限制；仅 http/https；拒绝 javascript/协议相对/控制字符；追踪说明和风险提示存在 | 组件 trace、40001 矩阵 |
| `TC-H2-008` | integration | 切换 instant/rolling/cycle、now/scheduled、三种间隔预设和并发边界 | plannedEnd/cycle/delay 条件正确；0.1 秒规范化为整数毫秒；四条并发约束和默认值真实生效 | 请求/DB 映射、边界错误 |
| `TC-H2-009` | integration | 打开账号筛选并遍历公共契约全部字段 | 包含/排除互斥、数组去重、ISO2 大写、区间校验、三个固定条件不可取消；取消不落值、清空仅清可编辑条件 | Filter JSON、UI 标签、SQL 摘要 |
| `TC-H2-010` | integration | 快速修改筛选让旧 match-count 晚返回 | 250ms 防抖和取消生效；匹配数/协议数/并发上限来自最终条件；未知键 40001；失败不伪装为 0 | 请求序列、响应覆盖检查 |
| `TC-H2-011` | integration | 选择可用/失效/空数据包并切换 enabled | enabled=true 必选可用包；仅保存可空；历史失效包可回显但重新启用前必须替换；即时启用零账号拒绝，rolling/cycle 可等待 | UI/API/DB 结果 |
| `TC-H2-012` | integration | 纯新建 enabled=true 走 quote 与最后核对 | 弹框展示服务端余额、人数、金额、国家明细和关键配置；7 秒前禁用；报价过期原框刷新并重新倒计时；前端不重算金额 | quote/倒计时 trace、截图 |
| `TC-H2-013` | integration | 复制 TASK_COMPLETED 并提交 | 名称追加“副本”、dataPackage/quote/version 清空、sourceTaskId 正确；不复制 claim/billing/recipient/round/runtime/usage/stat/click；越权源失败 | POST、DB 表对账 |
| `TC-H2-014` | integration | 编辑 TASK_DRAFT，同时由第二会话更新 version 或让任务开始 | PUT 必带 version；40910 后保留用户表单并允许重载；已产生 command 或 runStatus!=0 不可编辑 | 并发 trace、DB 版本 |
| `TC-H2-015` | integration | 在非查看模式用关闭按钮/遮罩/ESC/取消，提交得到 200/202/错误 | 关闭确认文案正确；查看直接关闭；错误保留表单；202 关闭抽屉并短轮询，READY/FAILED 停止；重复点击不重复创建 | 浏览器 trace、任务行数 |

## 6. H3 发布与运行生命周期用例

| ID | Profile | Given / When | 必须断言 | 证据 |
|---|---|---|---|---|
| `TC-H3-001` | integration | 对 CREATE/START 获取报价并篡改租户、用户、purpose、包、代次、上界、模式、并发、金额、task/version或过期时间 | token 对全部绑定项 fail-closed；客户端单价/人数/余额不被接受；stale=40911、余额不足=40912 | 报价矩阵、签名拒绝 |
| `TC-H3-002` | integration | 创建 enabled=false 草稿 | 单事务仅建 task/content/runtime；NOT_REQUIRED、runStatus=0；claim/recipient/billing/round/usage/stat 全部为 0 行 | API 回执、十表快照 |
| `TC-H3-003` | integration | 用 DP_SMALL+WALLET_OK 创建 enabled=true | HTTP 202 PROCESSING；建壳后分批冻结/领取/计费/首轮；最终 READY 且正常列表可见，recipient 唯一数=5 | 阶段事件、十表快照 |
| `TC-H3-004` | integration | DP_LARGE_100K 每个准备批次后随机 kill worker 并 resume | 每批≤50；游标续跑；最终不重不漏；HTTP 无大事务；报告保留恢复点和相同 runId | kill/resume trace、计数/唯一约束 |
| `TC-H3-005` | integration | DP_SMALL 含重复号码，重复执行 claim、回放同一批次并并发释放 | task+phone 只有一行；代次活动 claim 唯一；新导入超 upperPhoneId 不加入；释放不碰已有 command 的事实 | claim/phone/recipient 对账 |
| `TC-H3-006` | integration | WALLET_RECOVERABLE 在 reserve/adjust/settle/release 各阶段返回未知并恢复 | 每种操作复用稳定幂等键；一任务一 reservation；无重复扣款；未确认计费前不派发；最终金额与唯一实际发送一致 | 钱包调用/幂等键、账务对账 |
| `TC-H3-007` | integration | instant 分别使用零账号、延后和正常账号 | 零账号 42210/FAILED 且不运行；延后按 scheduledAt；只建 round1；无剩余/在途/未结算后自动完成 | 状态时间线、round/recipient |
| `TC-H3-008` | integration | rolling 初始零账号，后加入匹配账号，同时给数据包导入新号码并越过 plannedEndAt | 后加入发信账号可进入；新导入收信人不吸收；已分配 recipient 不换号；结束后剩余按停止语义收口 | 轮次/账号/recipient 差异 |
| `TC-H3-009` | integration | cycle 运行多轮、某轮零账号并模拟宕机错过周期 | 只分配剩余 recipient；同号码不重发；maxUseAccount 每轮生效；漏周期不补跑、轮次不重叠；仍有剩余才建下轮 | round 时间线、唯一发送计数 |
| `TC-H3-010` | integration | 设置消息间隔、concurrentNum、maxUseAccount、accountMaxSendNum、delay和周期 | 字段不仅落库且真实限制调度/在途/账号选择/时间；不存在页面可填但运行丢弃 | 调度采样、usage/round 证据 |
| `TC-H3-011` | integration | 对同一 recipient 并发派发、重投 outbox、重放结果 | 固定 `hl:{tenant}:{task}:{recipient}` commandId；一个 recipient/round/account/command；禁止跨账号重试和第二次逻辑发送 | outbox/recipient/协议结果关联 |
| `TC-H3-012` | release-canary-web | ACCOUNT_WEB 对 RECIPIENT_REGISTERED 发送类型 1、3、4 | PRIVATE JID 不走群 metadata/权限；图片/标题/正文/卡片/唯一 CTA 完整；真实收信端内容与冻结配置一致 | 脱敏真机截图、wire/command 证据 |
| `TC-H3-013` | release-canary-android | ACCOUNT_ANDROID 对 RECIPIENT_REGISTERED 发送类型 1、3、4 | Android peer 私聊分支、资源缓存和 serializer 正确；不发群 typing/mention；真实内容完整 | 脱敏真机截图、wire/command 证据 |
| `TC-H3-014` | release-canary-web/android | 将同一成功 commandId 连续投递两次 | 两协议均只产生一条物理消息并返回缓存/既有结果；任务 sendTotal 不增加第二次 | 收信端计数、协议 command 状态 |
| `TC-H3-015` | integration+canary | 发送 success、未注册、账号失效，并注入重复/乱序单钩双钩已读 ACK | 状态单调；终态失败不被迟到 ACK 复活；UNREGISTERED 属于 failed 子集；事件准确关联 task/recipient/command | recipient 时间线、事件流 |
| `TC-H3-016` | integration | TASK_RUNNING 执行 PAUSE，等待一个派发周期 | runStatus=3、累计时长；不领取/派发新命令；已入 outbox 自然收口；暂停时长不计 executionDuration | 状态/命令/时长差分 |
| `TC-H3-017` | integration | TASK_PAUSED 执行 RESUME | 从原 round/usage/recipient/游标继续，不重新 claim/计费/分配既有 recipient，不创建第二 command | 前后表快照、命令集合 |
| `TC-H3-018` | integration | TASK_RUNNING/TASK_PAUSED 执行 STOP 并重复执行 | 状态立即终态且不可恢复；未提交行分批 FAILED/TASK_STOPPED；已有 command/ACK/点击保留；料子和余额释放；重复动作不重复清理 | 状态、recipient、claim、billing |
| `TC-H3-019` | integration | 对全部非法 Action、旧 version、双击和并发动作测试 | 仅合法状态转移成功；40910 后事实不被前端覆盖；任一并发组合最多一个获胜 | 并发结果、条件更新行数 |
| `TC-H3-020` | soak-60m | 持续注入重复结果/ACK、投影器 kill/restart、usage/recipient 人为可恢复差异 | runtime/round/account_stat 分钟级幂等收敛；metricsUpdatedAt 只由发送投影推进；reconciliation 可重建且不重复计数 | 60m 水位、投影/重建报告 |
| `TC-H3-021` | soak-60m | claim/round/billing/outbox worker 在租约前后崩溃并由恢复器接管 | 仅租约过期后接管；从事实表续跑；退避有界；无双 worker、重复领取、重复扣费和重复发送 | 租约时间线、worker/DB 证据 |
| `TC-H3-022` | integration | 尝试自动完成含 PENDING/SENDING/活动 round/未结算任务，再清除条件 | 任一条件存在时不得 COMPLETED；全部满足后一次性完成、累计最后时长、finishedAt 正确；cycle 无不可恢复空档 | 完成条件矩阵、状态事务 |

## 7. H4 收信人流水统计用例

| ID | Profile | Given / When | 必须断言 | 证据 |
|---|---|---|---|---|
| `TC-H4-001` | integration | 从 H1 详情打开 TASK_RUNNING，关闭并切换任务 | 1300px 抽屉、标题、遮罩+按钮关闭、五 Tab 顺序和默认 recipients；切换任务清空全部旧状态 | 页面截图、请求取消 trace |
| `TC-H4-002` | integration | summary 含 0 分母、运行中时长和全部指标 | 六张卡顺序/颜色/公式、单钩双钩解释和预计落地提示准确；summary 不受 Tab 筛选影响 | summary 响应、卡片对账 |
| `TC-H4-003` | integration | 使用号码、收信国家、发信国家、完整失败原因单独/组合筛选 | 号码转义 LIKE；原因严格等值；Enter/搜索/重置/回第 1 页准确；只查当前租户任务 | 请求响应、SQL 摘要 |
| `TC-H4-004` | integration | 准备七种状态及各状态时间 | 标签、颜色、statusAt 优先级准确；UNREGISTERED 显示失败+号码未注册；失败原因只在失败时显示且全文 tooltip | 状态截图、API 映射 |
| `TC-H4-005` | integration | 同 recipient 经周期、outbox 重放、ACK 重复和恢复 | 流水永远一行；account/sender 快照稳定；无账号显示 #id 或 `-`；无 attempt/round 重复行 | recipient 唯一查询、页面行数 |
| `TC-H4-006` | integration | ACK 后立即查 recipient，再等待投影查 summary | 行事实可先变化；页面显示分钟级提示/metricsUpdatedAt；投影后摘要与 recipient 重建值一致 | 前后时间线、对账报告 |
| `TC-H4-007` | integration | 切 Tab、分页、刷新和列设置后返回 | Tab 内条件/页码保留；换任务/关闭重置；刷新当前 Tab+summary 且 500ms 内摘要去重 | 浏览器 trace、状态快照 |
| `TC-H4-008` | integration | 按四筛选导出，覆盖 PENDING/PROCESSING/SUCCESS/FAILED/EXPIRED 和关闭抽屉 | 8 列顺序、号码文本、statusAt、snapshotAt 和筛选一致；前端只取消轮询不取消作业；成功自动下载 | 作业/CSV/筛选对账 |
| `TC-H4-009` | integration | USER_VIEW/EXPORT/另一用户和 TENANT_B 猜 jobId | 查看与导出权限分离；作业状态/下载同时校验当前租户+创建人；不泄露文件路径 | 权限矩阵、403/404 |
| `TC-H4-010` | perf-readonly | 10 万 recipient 测第一页、末页、组合筛选和导出 | pageSize≤200；显式小字段、不读取 IP/UA/内容 JSON；执行计划命中任务/国家/账号路径；导出分批≤2000 | EXPLAIN ANALYZE、P95、内存曲线 |

## 8. H5 发信账号纬度统计用例

| ID | Profile | Given / When | 必须断言 | 证据 |
|---|---|---|---|---|
| `TC-H5-001` | integration | 首次切入 accounts Tab，随后切出/返回/换任务 | 懒加载；Tab 文案严格为“发信账号纬度统计”；本抽屉内保留条件，关闭/换任务恢复默认 | 页面截图、请求计数 |
| `TC-H5-002` | integration | 不传时间与传全范围时间查询同一已收敛任务 | 无时间只读 account_stat；有时间只聚合 recipient；结果单钩/双钩/失败一致且 SQL 路径不混用 | SQL 路由、结果对账 |
| `TC-H5-003` | integration | 时间边界、国家、成功 min/max 和组合筛选 | 时间成对且左闭右开；min≤max；国家排除未分配桶；日期改变立即搜、其他条件按搜索/Enter 应用 | 请求/响应边界 |
| `TC-H5-004` | integration | 三个指标排序、同值多页和清除 sorter | 默认 success desc；success/delivered/failed 远程 asc/desc；bucketKey 稳定；清除恢复默认，无重复漏行 | 分页集合、请求序列 |
| `TC-H5-005` | integration | recipient 覆盖 SUCCESS/DELIVERED/READ/FAILED/UNREGISTERED/TASK_STOPPED | 包含式计数正确；STOP 未提交只进默认累计未分配失败桶，不因伪造 submittedAt 进入时间范围 | account_stat/recipient 对账 |
| `TC-H5-006` | integration | 删除/改号真实账号并跨时间查询 | 手机、国家、类型和创建时间来自 usage 冻结快照；retentionDays 以查询 snapshot 计算；未分配固定 0.0/`-` | API、DB 快照、截图 |
| `TC-H5-007` | soak-60m | 重复投影、乱序 ACK、kill projector 并 reconciliation | account_stat 差量不重复；usage 仍控制调度且不被统计写覆盖；全量校准后两路径一致 | 投影水位、表对账 |
| `TC-H5-008` | integration | 按累计/时间范围、国家、区间和排序导出 | 8 列、未分配文本、号码文本、snapshotAt 固定；筛选/排序与页面一致；异步完成 | CSV、作业、页面对账 |
| `TC-H5-009` | integration | 检查六列、国旗、个人/商业、颜色、刷新/导出/列设置和所有页大小 | 竞品可见交互完整且真实工作；不存在空按钮或第二套详情抽屉 | 全页截图、交互 trace |
| `TC-H5-010` | perf-readonly | 1,000 账号桶默认查询；10 万 recipient 的 24h 时间查询及导出 | 累计 P95≤200ms；区间 GROUP BY P95≤800ms；命中设计索引；不新增 hourly 表；导出内存不线性增长 | EXPLAIN ANALYZE、P95、堆曲线 |

## 9. H6 归因、访问趋势与封号原因用例

| ID | Profile | Given / When | 必须断言 | 证据 |
|---|---|---|---|---|
| `TC-H6-001` | release-canary-web/android | 对短链消息点击一次 | 短链替换唯一 CTA；公网入口不需认证；首访 recipient UV+1/PV+1、首触字段和 runtime 原子更新；302 到冻结 HTTP(S) 目标 | 302/Location、DB 前后、收信端 |
| `TC-H6-002` | integration | 重复点击和两个并发首访 | 首触环境不覆盖；重复只 PV+1/lastVisit；并发最终 UV=1、PV=2；跨 recipient 各自 UV | 并发响应、recipient/runtime 对账 |
| `TC-H6-003` | integration | 使用大小写变体、无效码、失效内容、非法 scheme 和 query 目标覆盖 | shortCode 大小写精确；无效 404、失效/脏目标 410；不能开放重定向；失败不伪造统计或 302 | HTTP 矩阵、DB 无变化 |
| `TC-H6-004` | integration | 受信/非受信代理、伪造 XFF、IPv4/IPv6、UA/Geo 解析失败和数据库失败 | 只信任配置代理；本地解析失败仍跳转并留空派生；DB 失败返回 503；日志无 shortCode/IP/UA/目标参数 | 请求头矩阵、日志脱敏扫描 |
| `TC-H6-005` | integration | 深度归因用两个手机号筛选并排序/分页 | 只返回 clickCount>0；筛选 LIKE 转义；total=筛选后 UV 行数而非 PV；分母固定任务 successNum；visitCount desc 默认且稳定 | API/页面统计条对账 |
| `TC-H6-006` | integration | USER_VIEW/USER_ALL 查看 11 列，执行 90 天清理 | 无敏感权限 IP/UA=null 但列存在；有权限返回并审计；清理后 attributionPurged=true，永久 UV/PV/时间保留且不伪装未访问 | 权限响应、审计、清理前后 |
| `TC-H6-007` | integration | USER_EXPORT 与 USER_ALL 创建/下载归因导出并猜 jobId | 创建和下载都要求 export+sensitive；13 列顺序、文本字段、筛选/排序/snapshot 一致；越权不可下载 | 作业/权限矩阵、CSV |
| `TC-H6-008` | integration | 首访为 22:04:01，分别请求 5 range×3 granularity | 第一桶从 22:04:01 精确开始；左闭右开；桶数正确并补零；range 必须整除 granularity | 15 组响应、边界对账 |
| `TC-H6-009` | integration | 同 recipient 多次访问并分布多个首访桶 | 所有 PV 归各 recipient 首访桶；new/cumulative UV、clickRate、pvTotal、pvPerUv 公式准确 | 原始 recipient 与 series 对账 |
| `TC-H6-010` | integration | 无 UV、0 success、并列高峰、少于 3 个非零桶、连续 surge | 无 UV 返回 null/0/空数组；并列峰取最早；Top3 排序；surge 阈值和连续去重符合设计 | 纯事实夹具、响应 |
| `TC-H6-011` | integration | 操作范围/粒度、图表/表格、刷新并让旧响应晚返回 | 默认 24h/30m；六卡、三 series、PV 默认隐藏、insights、Top3、五列表格完整；旧响应不覆盖新选择 | 图/表截图、请求 trace |
| `TC-H6-012` | integration | 导出当前趋势窗口/粒度 | CSV 五列逐行等于 series；列名标注 PV 首访桶近似；无额外 OTP；export 权限和审计正确 | CSV/series diff、审计 |
| `TC-H6-013` | integration | 同一账号重复封号、补空原因、普通网络失败和未注册 recipient | 明确失效只首次计数；补原因不改时间/计数；普通失败和未注册不进入封号分布 | usage/runtime 前后、事件矩阵 |
| `TC-H6-014` | integration | 准备四个已知英文原因、未知原因、空原因和无封号任务 | 四条中文说明大小写兼容；未知原样；空归未知原因；count/一位百分比/排序/颜色/空态准确 | API、页面截图 |
| `TC-H6-015` | soak-60m | runtime invalidAccountCount 与 usage 分组暂时漂移再 reconciliation | ban-stats 现场事实不被错误 runtime 覆盖；可观测报警；投影收敛后二者相等 | 漂移/恢复报告 |
| `TC-H6-016` | perf-readonly | 10 万 recipient、72h/30m 趋势，深度归因分页和导出 | 趋势 P95≤800ms；命中 click/visit 索引；最大 144 行；不建 click/30m bucket/task_ban 表且可见能力无损 | EXPLAIN ANALYZE、P95、表清单 |

## 10. 设计证据追踪矩阵

设计中的每条竞品证据必须至少由一个测试案例验证。以下映射同时是覆盖率门禁；删除或合并案例时必须保持所有证据 ID
仍有 required 执行路径。

### 10.1 H1

| 设计证据 | 验收案例 |
|---|---|
| `E-H1-01` | `TC-H1-003`、`TC-H1-004` |
| `E-H1-02` | `TC-H1-004` |
| `E-H1-03` | `TC-H1-001`、`TC-H1-002` |
| `E-H1-04` | `TC-H1-005` |
| `E-H1-05` | `TC-H1-006` |
| `E-H1-06` | `TC-H1-007`、`TC-H1-008` |
| `E-H1-07` | `TC-H1-009` |
| `E-H1-08` | `TC-H1-011` |
| `E-H1-09` | `TC-H1-010` |
| `E-H1-10` | `TC-H1-008` |

### 10.2 H2

| 设计证据 | 验收案例 |
|---|---|
| `E-H2-01` | `TC-H2-001`、`TC-H2-014` |
| `E-H2-02` | `TC-H2-013` |
| `E-H2-03` | `TC-H2-001`、`TC-H2-003` |
| `E-H2-04` | `TC-H2-002` |
| `E-H2-05` | `TC-H2-003`、`TC-H2-006`、`TC-H2-007` |
| `E-H2-06` | `TC-H2-007` |
| `E-H2-07` | `TC-H2-004`、`TC-H2-005` |
| `E-H2-08` | `TC-H2-008` |
| `E-H2-09` | `TC-H2-009`、`TC-H2-010` |
| `E-H2-10` | `TC-H2-011` |
| `E-H2-11` | `TC-H2-011` |
| `E-H2-12` | `TC-H2-012` |
| `E-H2-13` | `TC-H2-015` |

### 10.3 H3

| 设计证据 | 验收案例 |
|---|---|
| `E-H3-01` | `TC-H3-002`、`TC-H3-003` |
| `E-H3-02` | `TC-H2-012`、`TC-H3-001` |
| `E-H3-03` | `TC-H1-008`、`TC-H3-019` |
| `E-H3-04` | `TC-H3-016`、`TC-H3-018` |
| `E-H3-05` | `TC-H3-017`、`TC-H3-018` |
| `E-H3-06` | `TC-H1-008` |
| `E-H3-07` | `TC-H1-008`、`TC-H3-016` |
| `E-H3-08` | `TC-H1-008`、`TC-H3-017` |
| `E-H3-09` | `TC-H1-008`、`TC-H3-018` |
| `E-H3-10` | `TC-H3-007`、`TC-H3-008`、`TC-H3-009` |
| `E-H3-11` | `TC-H3-010` |
| `E-H3-12` | `TC-H3-020` |

### 10.4 H4

| 设计证据 | 验收案例 |
|---|---|
| `E-H4-01`、`E-H4-02` | `TC-H4-001` |
| `E-H4-03`、`E-H4-04` | `TC-H4-002` |
| `E-H4-05` | `TC-H4-001`、`TC-H4-007` |
| `E-H4-06` | `TC-H4-003` |
| `E-H4-07` | `TC-H4-003`、`TC-H4-007`、`TC-H4-008` |
| `E-H4-08`、`E-H4-09`、`E-H4-10` | `TC-H4-004`、`TC-H4-005` |
| `E-H4-11` | `TC-H4-003`、`TC-H4-010` |
| `E-H4-12` | `TC-H4-001`、`TC-H4-004` |

### 10.5 H5

| 设计证据 | 验收案例 |
|---|---|
| `E-H5-01` | `TC-H5-001` |
| `E-H5-02`、`E-H5-03`、`E-H5-04` | `TC-H5-003` |
| `E-H5-05` | `TC-H5-009` |
| `E-H5-06` | `TC-H5-006`、`TC-H5-009` |
| `E-H5-07` | `TC-H5-004` |
| `E-H5-08`、`E-H5-09` | `TC-H5-005`、`TC-H5-006` |
| `E-H5-10` | `TC-H5-009` |

### 10.6 H6

| 设计证据 | 验收案例 |
|---|---|
| `E-H6-01`、`E-H6-02` | `TC-H6-005` |
| `E-H6-03`、`E-H6-04` | `TC-H6-005` |
| `E-H6-05`、`E-H6-06`、`E-H6-07` | `TC-H6-005`、`TC-H6-006` |
| `E-H6-08`、`E-H6-09` | `TC-H6-008`、`TC-H6-011` |
| `E-H6-10`、`E-H6-11` | `TC-H6-011`、`TC-H6-012` |
| `E-H6-12`、`E-H6-13`、`E-H6-14`、`E-H6-15`、`E-H6-16` | `TC-H6-009`、`TC-H6-010`、`TC-H6-011` |
| `E-H6-17`、`E-H6-18`、`E-H6-19`、`E-H6-20` | `TC-H6-013`、`TC-H6-014`、`TC-H6-015` |

## 11. 跨方案端到端主旅程

下列旅程不是用来替代单项案例，而是确认六个方案组合后没有接口和事实断层。

### `E2E-HL-001` 草稿到停止

1. H2 创建 disabled 草稿，验证 H1 列表显示“已停用”。
2. 编辑补齐数据包和账号筛选，取得 START quote 并启动。
3. H3 等待 PROCESSING→READY→RUNNING，验证 H1 操作切换为暂停/停止。
4. H4 查看唯一 recipient 流水；H5 查看账号累计；H6 在未点击前显示真实空态。
5. PAUSE 后确认不再派发，RESUME 后继续原游标。
6. STOP 后确认未提交行变为 TASK_STOPPED、已提交命令收口、余额和 claim 释放。

### `E2E-HL-002` Web 短链真实闭环

1. H2 创建短链普通按钮任务，使用 DP_SMALL 的受控单号码子集与 ACCOUNT_WEB。
2. H3 Web 私聊实际发送，确认唯一 CTA 和唯一 command。
3. 收信端点击短链两次，确认第一次 UV、两次 PV 和 302。
4. H4 流水从 SENDING 单调推进到通道可提供的最终 ACK。
5. H5 账号统计和 H6 深度归因/趋势在投影窗口后与 recipient 事实一致。
6. 导出收信人、账号、归因和趋势，逐项与页面筛选和快照对账。

### `E2E-HL-003` Android 卡片真实闭环

重复 `E2E-HL-002`，但使用 ACCOUNT_ANDROID 和卡片按钮消息；必须证明 Android PRIVATE serializer、图片、卡片文字、
CTA、短链、结果/ACK 和重复 command 幂等均成立。

### `E2E-HL-004` 多租户与权限闭环

1. TENANT_A/TENANT_B 建立相同业务名称和相近 ID 的任务。
2. USER_VIEW 只能查看；USER_EXPORT 不能导出完整归因；USER_NO_ACCESS 看不到入口且直接请求被拒绝。
3. 交叉使用 taskId/jobId/sourceTaskId/dataPackageId/assetId 均不可读取或写入。
4. 审计和错误不泄露另一租户资源存在性。

## 12. 完成标准与输出

六方案候选只有同时满足以下条件才能标记 `ACCEPTED`：

- `quick`、fixture preflight 和 `TC-C-*` 全部 PASS。
- H1～H6 所有 required 案例全部 PASS；没有 NOT_RUN/SKIPPED。
- 两个真实协议 profile 均 PASS；若某协议尚未交付，必须在账号匹配中 fail-closed 禁用，并由业务 owner 明确调整
  本期范围和 `scope_hash`，不能默认放行。
- `E2E-HL-001`～`004` 全部 PASS。
- `soak-60m`、三项设计冻结的性能门槛及四类大导出通过。
- 独立验证者复核版本、案例结果、脱敏、证据哈希和 cleanup，无高优先级未决问题。

Runner 最终至少输出：

```text
summary.json
report.md
checksums.sha256
case-results/<caseId>.json
http/<caseId>.ndjson
ui/<caseId>/
db/<caseId>-before.json
db/<caseId>-after.json
events/<caseId>.ndjson
performance/<caseId>.json
cleanup/<caseId>.json
```

本文没有授权部署、连接生产、修改真库或操作非白名单真实 WhatsApp 资源。执行 real canary 前仍需按 D3 流程确认
目标环境和安全信封。
