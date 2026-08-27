# hylb 超链任务前端反推证据

- 日期：2026-08-27
- 目标项目：`/Users/daishuaishuai/IdeaProjects/hylbuiaxykfrontendsource`
- 目标菜单：超链营销 → 超链任务
- 主业务分块：`readable/assets/task-0vbZUOmq.js`
- 路由/API 分块：`readable/assets/router-CPQmbuR9.js`
- 账号筛选分块：`readable/assets/account-filter-modal-BXDIvipG.js`
- 按钮编辑器：`readable/assets/hyperlink-button-editor-CcRhevR2.js`
- 消息预览：`readable/assets/wa-message-preview-BT1QVqmo.js`
- 素材选择/上传：`readable/assets/resource-asset-field-D7ze446Y.js`、`resource-asset-upload-modal-Cns3ms7s.js`、`resource-CF5a-p8A.js`
- 访问趋势：`readable/assets/visit-trend-tab-CVJUtu9z.js`
- 状态：静态取证完成；运行态响应样例不在本轮离线授权范围

## 1. 方法和证据等级

目标是 Vue 3 + Vite 生产构建产物，无 sourcemap。本轮使用格式化 readable 分块做静态数据流还原：页面渲染条件、表单默认值/校验、提交归一化、组件 props、API 调用点、路由封装相互核对。

当前环境未暴露 `js-reverse_*` MCP，安装的 `js-reverse` 技能所引用 `tool-index.md` 也不存在，因此没有伪造浏览器采样；回退路径符合技能的静态分析输出要求。

证据等级：

- **已确认**：页面文字、控件、分支、默认值、请求路径在构建产物直接可见。
- **高可信推断**：由提交体、多个调用点和显示模型共同支持，但缺真实响应样本。
- **待验证**：前端只表达意图，无法证明后端调度、事务或结算细节。

报告结构选择 `flavor = null`：这是用户授权的本地竞品前端业务反推，不是恶意软件、APT、漏洞利用或渗透测试，不适用 IOC 和 ATT&CK。

## 2. Scope、Evidence、Finding 与 Path

范围和授权记录在[本次变更记录](../../../.harness/changes/2026-08-27-hyperlink-task-competitor-reverse.md)：用户明确指定本地归档 `hylbuiaxykfrontendsource`，要求反推超链任务的字段、列表、功能和弹框。范围只包含本地静态文件读取和 Armada 设计对账；未登录目标站点、未发网络请求、未修改竞品归档。`network_profile=offline`。

除 E-005 明确指向 Armada 外，以下复现命令的工作目录均为 `/Users/daishuaishuai/IdeaProjects/hylbuiaxykfrontendsource`。

### E-001：超链任务主分块

- observed_at: 2026-08-27
- source_type: file
- source_ref: `/Users/daishuaishuai/IdeaProjects/hylbuiaxykfrontendsource/readable/assets/task-0vbZUOmq.js`
- content_hash: `sha256:f1e484ad0bcb1ea45f438839da4d0c780bb4a9e976b194489752185e1b2542ac`
- artifact_path: `../hylbuiaxykfrontendsource/readable/assets/task-0vbZUOmq.js`
- repro_command: `sed -n '887,2965p;3008,4624p;4687,5895p' readable/assets/task-0vbZUOmq.js`
- raw_excerpt: 可复核表单默认值/校验/显隐、四段抽屉、最后核对、五个详情 Tab、列表列和行操作。
- linked_workitem: n/a
- supersedes: none

### E-002：完整账号筛选抽屉

- observed_at: 2026-08-27
- source_type: file
- source_ref: `/Users/daishuaishuai/IdeaProjects/hylbuiaxykfrontendsource/readable/assets/account-filter-modal-BXDIvipG.js`
- content_hash: `sha256:c52234a07f15cb45a35f8ef16474e7f3dc474524925dafe3933d8acfbadb3a72`
- artifact_path: `../hylbuiaxykfrontendsource/readable/assets/account-filter-modal-BXDIvipG.js`
- repro_command: `sed -n '345,788p;833,1114p' readable/assets/account-filter-modal-BXDIvipG.js`
- raw_excerpt: 可复核筛选默认模型、五类来源、完整可见控件、250ms 试算和零账号确认规则。
- linked_workitem: n/a
- supersedes: none

### E-003：任务 API 路由封装

- observed_at: 2026-08-27
- source_type: file
- source_ref: `/Users/daishuaishuai/IdeaProjects/hylbuiaxykfrontendsource/readable/assets/router-CPQmbuR9.js`
- content_hash: `sha256:7e06fb1419f879474eb9eb4c091c425a1e82606a92753a35ab1f5c0d98a6e2fa`
- artifact_path: `../hylbuiaxykfrontendsource/readable/assets/router-CPQmbuR9.js`
- repro_command: `sed -n '45958,46120p;46266,46317p;46877,46895p;47112,47116p' readable/assets/router-CPQmbuR9.js`
- raw_excerpt: 可复核任务 CRUD/action、五类详情/导出、素材和业务价格接口；任务块没有 DELETE。
- linked_workitem: n/a
- supersedes: none

### E-004：任务依赖组件

- observed_at: 2026-08-27
- source_type: file
- source_ref: `hyperlink-button-editor-CcRhevR2.js`、`wa-message-preview-BT1QVqmo.js`、`resource-asset-field-D7ze446Y.js`、`resource-asset-upload-modal-Cns3ms7s.js`、`visit-trend-tab-CVJUtu9z.js`
- content_hash: n/a（多文件组件集；下述命令逐文件输出完整 SHA-256）
- artifact_path: `../hylbuiaxykfrontendsource/readable/assets/`
- repro_command: `shasum -a 256 readable/assets/hyperlink-button-editor-CcRhevR2.js readable/assets/wa-message-preview-BT1QVqmo.js readable/assets/resource-asset-field-D7ze446Y.js readable/assets/resource-asset-upload-modal-Cns3ms7s.js readable/assets/visit-trend-tab-CVJUtu9z.js`
- raw_excerpt: 通用按钮编辑器的四类能力、任务 CTA URL 锁定调用、WhatsApp 类型预览、素材选择/顺序上传、访问趋势均可交叉复核。
- linked_workitem: n/a
- supersedes: none

### E-005：Armada 能力对账

- observed_at: 2026-08-27
- source_type: command
- source_ref: `armada-api/src/main/java/com/armada/hyperlink`、账号表/状态表/凭据与导入批次模型、`wheel-saas-pure-web/src`
- content_hash: n/a（工作树会继续演进）
- artifact_path: n/a
- repro_command: `rg -n "class Hyperlink|record Hyperlink|enum Hyperlink|hyperlink" armada-api/src/main/java/com/armada && rg -n "friend|register|group_invite|rotation|balance|freeze|billing" armada-api/src/main/java armada-api/src/main/resources/db/migration`
- raw_excerpt: 当前仅有数据包和模板子域；任务、详情读模型、账号营销画像、任务计费和短链归因尚未完整落地。
- linked_workitem: n/a
- supersedes: none

### F-001：任务菜单必须全量对齐

- severity: n/a_re
- category: reverse_algo
- status: validated
- evidence_ids: [E-001, E-002, E-003, E-004]
- location: `task-0vbZUOmq.js:321-5895`
- impact: 只看主表单或通用组件会遗漏列表、详情、素材、计费和账号筛选能力，导致功能不完整。
- confidence: high
- repro_steps: 按 E-001～E-004 命令读取各分块，再按本文功能清单逐项核对。
- remediation: 以[超链任务竞品对齐详细设计](../specs/2026-08-27-hyperlink-task-competitor-parity-detailed-design.md)作为实施契约。

### F-002：任务只允许 CTA URL 单按钮

- severity: n/a_re
- category: design
- status: validated
- evidence_ids: [E-001, E-004]
- location: `task-0vbZUOmq.js:2246-2275`
- impact: 把通用编辑器的四类按钮当作任务能力，会错误扩大前端和协议开发范围。
- confidence: high
- repro_steps: 对照通用编辑器类型枚举和任务调用处的 `locked-type="cta_url"`。
- remediation: 任务 UI/DTO/服务校验固定 1 个 CTA URL；通用模型可保留历史兼容。

### F-003：账号筛选字段完整，竞品编辑回填存在缺陷

- severity: n/a_re
- category: design
- status: validated
- evidence_ids: [E-001, E-002]
- location: `account-filter-modal-BXDIvipG.js:345-1114`、`task-0vbZUOmq.js:1495-1529`
- impact: 若照抄不完整回填，会让轮号、来源等条件编辑后静默丢失；隐藏好友数/注册天数/允许拉群则会直接少功能。
- confidence: high
- repro_steps: 核对抽屉可见字段、提交归一化和任务详情回填键集合。
- remediation: 共享版本化筛选 DTO，所有可见字段做创建/编辑/复制往返测试；缺失画像作为硬依赖采集。

### F-004：任务没有删除和前端自动刷新

- severity: n/a_re
- category: design
- status: validated
- evidence_ids: [E-001, E-003]
- location: `task-0vbZUOmq.js:5369-5467,5893-5895`、`router-CPQmbuR9.js:45958-46120`
- impact: 增加 DELETE 或页面定时器会偏离竞品操作矩阵，并带来额外数据破坏/请求负载。
- confidence: high
- repro_steps: 检查所有行操作、API 封装和主分块 `setInterval` 命中。
- remediation: 仅提供启动/暂停/恢复/停止、编辑/查看、详情、复制和手动刷新。

### F-005：计费确认是任务创建硬依赖

- severity: n/a_re
- category: design
- status: validated
- evidence_ids: [E-001, E-003]
- location: `task-0vbZUOmq.js:1618-1665,2869-2965`、`router-CPQmbuR9.js:47112-47116`
- impact: 去掉余额、单价和预计冻结会使最后核对弹框失真，也无法复现竞品的资金约束。
- confidence: high
- repro_steps: 核对倒计时、余额计算、数据包人数×单价和业务价格请求。
- remediation: 任务依赖 Billing Gateway，真实报价/冻结/结算未接入前不能宣称完成。

### F-006：任务详情包含完整运营和敏感归因数据

- severity: n/a_re
- category: design
- status: validated
- evidence_ids: [E-001, E-003, E-004]
- location: `task-0vbZUOmq.js:3008-4624`、`visit-trend-tab-CVJUtu9z.js:425-749`
- impact: 只做任务列表会遗漏五个详情 Tab、四类导出以及 IP/user-agent 等深度归因字段。
- confidence: high
- repro_steps: 逐 Tab 核对筛选模型、列定义、摘要和 export 调用。
- remediation: recipient/attempt/visit 建立可查询快照；敏感归因加权限、审计和保留期，不丢竞品字段。

### P-001：新建到运营分析调用路径

- path_type: callflow
- start: 超链任务新建抽屉
- goal: 创建任务并在五个详情 Tab/后续市场分析消费结果
- steps:
  1. 用户引用模板/策略，选择素材和数据包 — evidence: E-001, E-004 — finding: F-001
  2. 完整账号筛选抽屉试算可用账号 — evidence: E-002 — finding: F-003
  3. 最后核对读取模式/价格/余额并估算冻结 — evidence: E-001, E-003 — finding: F-005
  4. POST 创建，随后通过 action 驱动启动/暂停/恢复/停止 — evidence: E-003 — finding: F-004
  5. recipient、账号、点击、趋势和封号接口生成详情读模型 — evidence: E-001, E-003, E-004 — finding: F-006
- residual_risks: 静态前端无法证明三种调度模式的号码重用和账务结算算法，实施前仍需真实 API 样例或产品确认。

### Timeline 摘要

| 日期 | 事件 |
|---|---|
| 2026-08-27 | 确认本地归档与主任务分块，限定静态离线范围 |
| 2026-08-27 | 逐项还原列表、四段表单、弹框和五个详情 Tab |
| 2026-08-27 | 用账号筛选、按钮、预览、素材、趋势组件和路由封装交叉验证 |
| 2026-08-27 | 与 Armada 已有数据包/模板/账号/协议/计费能力对账 |
| 2026-08-27 | 输出单菜单详细设计，并纠正四菜单总设计中的冲突结论 |

## 3. 页面入口和整体结构

- 动态路由名为 `hyperlink_task`，菜单标题「超链任务」。
- 主页面包含：筛选栏、当前页汇总卡、任务表格、任务编辑抽屉、任务详情抽屉。
- 编辑抽屉左侧为 WhatsApp 实时预览，右侧分四段：基础信息、消息内容、发送策略、受众与发布（`task-0vbZUOmq.js:1800-1907`）。
- 详情抽屉包含五个 Tab（`task-0vbZUOmq.js:4567-4624`）。

## 4. 任务列表证据

### 3.1 查询和分页

查询模型位于 `task-0vbZUOmq.js:4687-4696`：

```text
page, page_size, task_status, type, name,
created_at_start, created_at_end, country_iso2
```

- 搜索控件：任务名称、任务状态、任务类型、目标国家、创建时间（`task-0vbZUOmq.js:321-423`）。
- 名称搜索会把 page size 临时设成 200，重置恢复 20（`5625-5649`）；这是实现缺陷，不是业务要求。
- 分页选项 10/20/50/100/200（`4824-4835`）。
- 未发现页面 `setInterval` 自动刷新；页面底部只提示后端数据约每分钟同步，刷新按钮为手动（`5893-5895`）。

### 3.2 汇总卡

当前已加载任务数组在前端聚合（`task-0vbZUOmq.js:5651-5711`），卡片依次为：任务数、总发送数、单钩数、双钩数、点击 UV、点击率。

点击口径只纳入 `click_uv != null` 的行，并以这些行的成功发送数为分母；页面 tooltip 说明该口径（`5855`）。因此这是**当前页汇总**，不是全库总计。

### 3.3 表格列和行操作

| 列/能力 | 证据行 |
|---|---|
| ID | `4837` |
| 任务名、消息类型、模式、推广链接 | `4845-4938` |
| 数据包、受众数 | `4940-4964` |
| 账号筛选摘要，最多 3 个标签 +N | `4966-5080` |
| 目标国家 | `5082-5089` |
| 状态 | `5090-5132` |
| 已用/封号/账号平均发送 | `5133-5165` |
| 单钩/失败/总数/未注册/进度条 | `5166-5210` |
| 双钩数/率/预计落地率 | `5211-5263` |
| 点击 UV/率，点击率打开趋势 | `5264-5319` |
| 实际并发 | `5320-5332` |
| 执行时长 | `5333-5338` |
| 计划结束/周期执行间隔 | `5339-5360` |
| 创建时间 | `5361-5368` |
| 行操作 | `5369-5467` |

行操作矩阵：未开始可启动和编辑；进行中可暂停/停止；暂停可恢复/停止；非未开始显示查看；所有状态都有详情和复制。没有删除按钮。

状态展示先判断业务 `status=0` 显示已停用，否则按 `task_status=0..4` 显示未开始、进行中、已完成、已暂停、已停止（`5094-5119`）。编辑/查看依据 `task_status==0`，不是 `status`。

### 3.4 表头模式、价格、导出

- 页面请求 `protocol_use_concurrency` 全局配置，显示普通/超级模式徽标；价格码为 `hyperlink_task` 或 `concurrent_hyperlink_task`（`5723-5783`）。
- 前端本地 CSV 字段位于 `5477-5577`，覆盖全部核心列。
- 竞品导出消息类型的分支只把类型 2 映射为双图文，其他都映成单图文（`5491-5493`），会误导出类型 3/4，属于明确缺陷。

## 5. 新建/编辑/查看/复制抽屉证据

### 4.1 模式和默认值

- 抽屉宽度 820～1240px，右侧打开，遮罩/ESC 不关闭（`1701-1722`、`1800`）。
- 查看态整页只读；关闭、取消、创建/保存按钮分支位于 `1812-1859`。
- 关闭时有未保存确认（`1701-1715`）。
- 复制清空数据包，任务名追加「副本」（`1567-1569`、`1609-1645`）。
- 新建/复制可引用模板和策略；编辑/查看隐藏快速引用入口，条件分别见模板 `!j&&!M` 和策略 `!j&&!M`。
- 默认表单位于 `927-950`：消息类型 3、即时、立即执行、启用、并发账号 10、隐藏单账号并发 20、隐藏子任务量 50、最大使用账号 0、每账号最大发送 0、周期间隔 60、消息间隔 0.5～0.7。
- 默认 CTA URL 按钮和演示内容位于 `975-979`；新建用 picsum 随机示例图（`887-889`、`1723-1726`）。

### 4.2 基础信息和消息类型

- 新建可选类型顺序：普通按钮 3、卡片按钮 4、单图文 1；双图文 2 只兼容历史渲染（`1732-1747`、`1922-1968`）。
- 任务名必填、最多 128 字，可从数据包名获得自动完成建议（`1180-1196`、`1970-1992`）。
- 编辑态消息类型禁用（`1922-1942`）。

### 4.3 消息内容字段

| 字段 | 条件/规则 | 证据行 |
|---|---|---|
| 引用模板 | 启用模板，page_size 10000，一次性带入内容 | `1146-1178`、`1249-1270`、`2002-2020` |
| 链接预览图 | 类型 1/2，JPG≤500KB，建议 16:9 | `2021-2057` |
| 标题 | 必填，最多 1024；类型 3 使用 textarea | `2058-2092` |
| 链接描述 | 类型 1/2 必填 | `2094-2136` |
| 推广链接 | 类型 1/2 必填，最多 2048 | `2094-2136` |
| 正文图片 | 类型 2/3/4，可选，JPG≤500KB | `2137-2182` |
| 正文/底部文字/副标题 | 1/2 必填且最多 2000；3/4 可选且最多 200 | `2182-2218` |
| 卡片正文 | 类型 4 必填，最多 500 | `2218-2246` |
| 按钮 | 类型 3/4 必填，最多 1 | `2246-2275` |

关键纠正：任务向通用编辑器传了 `locked-type="cta_url"`（`task-0vbZUOmq.js:2246-2275`）。通用组件内部虽然实现 CTA URL、电话、复制、快捷回复四类（`hyperlink-button-editor-CcRhevR2.js:141-179`），但任务实际只能创建 CTA URL。

CTA URL 控件包含按钮文案（最多 30）、URL、深度追踪开关和短链归因提示（`hyperlink-button-editor-CcRhevR2.js:314-355`）。模板页也锁 CTA URL，且把 URL 留给任务配置（`templates-BLWMxusB.js:743-747`）。

### 4.4 WhatsApp 实时预览

组件 props 包括消息类型、标题、正文、链接描述、推广链接、预览图、正文图、按钮、卡片正文（`wa-message-preview-BT1QVqmo.js:402-435`）。

- 类型 1/2 显示链接预览卡。
- 类型 3 显示图片、标题、底部文字、按钮。
- 类型 4 先显示标题/副标题气泡，再显示图片、卡片正文、按钮卡。

主要渲染位于 `wa-message-preview-BT1QVqmo.js:611-872`，具体类型分支在 `797-856`。

### 4.5 发送策略

- 只列启用策略，page_size 10000；导入字段为模式、账号筛选、最大执行账号、最大使用账号、每账号最大发送，以及周期的执行间隔（`1201-1244`、`2285-2303`）。
- 模式卡和说明（`2304-2399`）：即时快速发完；预发布到截止时间/数据包发完先到为准并自动接纳新账号；周期按间隔反复执行、手动停止。
- 预发布计划结束必填，默认当天结束，必须晚于当前 1 分钟（`1032-1037`、`1491-1493`、`2401-2425`）。
- 周期间隔最小 1、默认 60、步长 10（`2425-2446`）。
- 账号范围、零账号提示和即时阻断（`2446-2530`）。
- 消息间隔 0～10、步长 0.1；三档预设 0～0.3、0.5～0.7、1.0～1.2（`895-911`、`2532-2610`）。
- 页面可见的并发字段只有「最大执行账号数」（`2612-2627`）。
- 最大/每轮使用账号、每账号最大发送（`2627-2707`）。
- 立即/延后启动和延迟分钟（`2707-2762`）。

协议数量通过 `GET /api/admin/protocols?page=1&page_size=1` 的 total 获取，最大执行账号数不超过协议数×15（`989-1013`）。隐藏 `account_send_concurrency` 默认 20，非法时归一到 30、上限 100（`891-894`）；总并发约束为 `maxExecuting × accountSendConcurrency ≤ 10000`（`1047-1066`）。

### 4.6 受众和发布

- 数据包请求 `page_size=10000&for_task=1`，选项显示名称、未使用号码数、备注；不可用历史项会临时插回选项用于回显（`1103-1140`、`2772-2804`）。
- 发布有「启用」和「仅保存」两张卡；数据包仅在启用时必填（`2805-2852`）。

### 4.7 提交和最后核对

提交归一化位于 `1577-1607`：账号筛选 JSON、固定正常/未导出/未禁言、不同消息类型字段、图片 multipart、启动延迟、模式专属时间、隐藏并发默认、按钮 JSON、启用状态。

只有纯新建进入最后核对（`1618-1622`）；倒计时 7 秒（`1650-1665`）。弹框宽 520，遮罩/ESC 不关闭（`2869-2879`），展示余额、预计冻结、模式/单价、任务名、数据包/剩余数、匹配账号数、推广链接、深度追踪和不可逆提示（`2875-2965`）。

价格接口是 `/api/admin/business_price/{code}`（`router-CPQmbuR9.js:47112-47116`），余额来自 userInfo 的 `balance + gift_balance`。这证明计费区不是装饰性占位。

## 6. 账号筛选抽屉证据

### 5.1 容器和固定规则

- 右侧抽屉宽 520～960，遮罩/ESC 不关闭（`account-filter-modal-BXDIvipG.js:902-917`）。
- 底部清空/取消/确定（`929-957`）。
- 匹配数量随条件变化 250ms 防抖查询（`750-788`）。
- 默认模型字段集中在 `345-373`；归一化提交在 `731-739`；有效条件判定在 `740-744`。
- 任务传 `allowZeroCount = task_mode != instant`、`lockStrangerMuted=true` 和默认公共+超链业务组。任务**没有**传 `lockGroupInvite`。
- 固定条件：有效、未导出、未被陌生人禁言。即时任务 0 个匹配时不可确认，预发布/周期允许 0（`833-856`）。

### 5.2 可见字段全集

1. 已选业务组；按名称/标签多选，默认公共组和超链组（`account-business-group-DEjomMoO.js:1-10`、账号筛选 `1020-1043`）。
2. 大洲、国家包含、国家排除；包含/排除选项互斥（`1044-1114`）。
3. 手机号模糊、导入编号精确。
4. 在线状态：全部/在线/离线。
5. 轮号状态：未轮号/轮号中/成功/失败。
6. 账号性质：个人/商业。
7. 导入方式：六段参数/全参数。
8. 账号类型：分身号 `web5`/主号 `native6`。
9. 设备平台。
10. 号码来源：买流量、自己登录、买入、转入、群扫码（枚举在 `581-619`）。
11. 允许拉群：全部/允许/不允许。
12. 好友数最小/最大。
13. 留存天数最小/最大，允许小数。
14. 注册天数最小/最大，支持 90/180/365/730/1095 快捷值或正整数。
15. 协议。
16. 渠道多选。
17. 创建时间范围，含今天/近7天/近30天快捷值。

`stranger_muted` 和超链任务寿命在筛选组件的其他业务模式中出现，但任务场景前者被锁为 false，后者不显示。

### 5.3 已确认缺陷

- 任务详情编辑回填 `1495-1529` 只恢复字段子集，漏掉轮号状态和来源；创建提交本身能保留这些键。
- 筛选摘要只映射来源 0/1，遗漏来源 2/3/4。
- 回填代码带了 `error_code/error_desc/logged_in_from/to` 等没有对应任务控件的幽灵字段。

Armada 只复制用户能力，不复制上述丢字段/死字段缺陷。

## 7. 素材弹框证据

### 6.1 选择

- 弹框宽 960，遮罩不可关闭，标题「从素材库选择」（`resource-asset-field-D7ze446Y.js:259-305`）。
- 查询 `page=1&page_size=12&name&tags`；名称 300ms 防抖，标签任意匹配（`150-155`、`306-379`）。
- 网格显示图片、名称、标签、尺寸、大小；单选；底部总数/分页/使用所选；有上传入口。

### 6.2 上传

- 弹框宽 640，最多 100 张；上传时禁止关闭。
- 仅 JPG/JPEG、每张 ≤500KB，统一标签；规则在 `resource-CF5a-p8A.js:54-75`。
- 支持拖拽、进度、状态、移除；用 `for` 循环逐文件上传，成功项保留，失败项可重试（`resource-asset-upload-modal-Cns3ms7s.js:157-320`、`334-447`）。

资源 API 位于 `router-CPQmbuR9.js:46266-46317`：列表、标签、详情、上传、编辑、删除。

## 8. 任务详情证据

### 7.1 外壳和摘要

- 右侧抽屉宽 1300，可点击遮罩关闭（`task-0vbZUOmq.js:4501-4512`）。
- 顶部卡片：单钩、双钩/率、失败、未注册、已用账号、封号账号、平均发送（`4514-4542`）。
- Tab：收信人流水统计、发信账号纬度统计、深度归因、访问趋势、封号原因分布（`4567-4624`）。竞品「纬度」是文案错字，设计使用「维度」。

### 7.2 收信人流水

- 筛选：收信号码模糊、收信国家、发信国家、失败原因精确，预设「号码未注册」（`3008-3042`、`3313-3399`）。
- 响应除 list/total 外带成功、双钩、失败、未注册、已用账号、封号账号、平均发送汇总（`3098-3133`）。
- 列：收信号码/国家、发信账号手机号/ID/国家、状态/失败。状态优先展示双钩时间、单钩时间、失败时间，否则按 send_status 映射（`3163-3262`）。

### 7.3 发信账号维度

- 筛选：时间范围、发信国家、成功数最小/最大；默认成功数降序，成功/双钩/失败可排序（`3467-3723`）。
- 列：发信手机号/ID/未分配、国家、个人/商业、留存天数、成功、双钩、失败、最后发送时间（`3580-3657`）。

### 7.4 深度归因

- 筛选收信号码、发信号码和排序，默认访问次数降序（`3869-4093`）。
- 列：收信号码、发信号码、访问次数、国家、设备、OS、浏览器、语言、IP、首次/最后访问；IP tooltip 包含 user-agent（`3949-4049`）。
- 竞品用响应 `total` 显示「点击总数」，更接近点击用户/行数而非 `sum(visit_count)`，属于口径歧义。

### 7.5 访问趋势

- 时间窗 12/24/36/48/72 小时，默认 24；粒度 30m/1h/2h，默认 30m；图表/表格、导出、刷新（`visit-trend-tab-CVJUtu9z.js:425-448`、`577-655`）。
- 时间范围从首次 UV 起算（`461-465`）。
- 响应 `series/summary/insights/top_peaks/granularity`（`493-524`）。
- 摘要 UV、点击率、任务开始、首次访问、峰值、新增 UV、PV、人均 PV（`661-712`）。
- 表格桶开始/结束、新增 UV、累计 UV、累计点击率、PV（`713-749`）。

### 7.6 封号原因

响应包含 `banned_count` 和 `stats[]{error_desc,pct,count}`，按 pct 降序（`4279-4291`）；页面显示原因卡、数量/占比/进度和空态（`4303-4329`）。

## 9. API 清单

`router-CPQmbuR9.js:45958-46120`、`46877-46895` 确认：

| 方法 | 竞品路径 | 用途 |
|---|---|---|
| GET | `/api/admin/hyperlink-tasks` | 任务列表 |
| GET | `/api/admin/hyperlink-tasks/{id}` | 任务详情/回填 |
| POST | `/api/admin/hyperlink-tasks` | multipart 新建 |
| PUT | `/api/admin/hyperlink-tasks/{id}` | multipart 编辑 |
| POST | `/api/admin/hyperlink-tasks/{id}/action` | start/pause/resume/stop |
| GET | `/api/admin/hyperlink-tasks/{id}/recipients` | 收信人流水 |
| GET | `/api/admin/hyperlink-tasks/{id}/recipients/export` | 流水导出 |
| GET | `/api/admin/hyperlink-tasks/marketing-stats/accounts` | 账号维度，query 带 task_id |
| GET | `/api/admin/hyperlink-tasks/marketing-stats/accounts/export` | 账号导出 |
| GET | `/api/admin/hyperlink-tasks/{id}/clicks` | 深度归因 |
| GET | `/api/admin/hyperlink-tasks/{id}/click-attribution/export` | 归因导出 |
| GET | `/api/admin/hyperlink-tasks/{id}/visit-trend` | 访问趋势 |
| GET | `/api/admin/hyperlink-tasks/{id}/visit-trend/export` | 趋势导出 |
| GET | `/api/admin/hyperlink-tasks/{id}/ban-stats` | 封号原因 |

同时存在全局营销统计 `/marketing-stats` 和 `/marketing-stats/countries`，属于后续市场分析菜单。任务相关代码块没有 DELETE task API。

依赖请求：协议列表、`data-packages?for_task=1`、账号列表/计数、业务组、渠道、启用模板、启用策略、资源素材、全局超级模式配置、业务价格和用户余额。

## 10. 请求字段和状态契约

任务提交可确认字段：

```text
name, message_type,
link_preview_image, title, link_description, promotion_link,
body_main_image, content, card_text, buttons,
type/task_mode, task_planned_end_at, task_interval,
account_filter,
msg_interval_min_sec, msg_interval_max_sec,
concurrent_num, account_send_concurrency, default_sub_task_num,
max_use_account, account_max_send_num,
start_mode, task_delay_minutes,
data_package_id, status
```

双状态：`status=0/1` 表示停用/启用；`task_status=0..4` 表示未开始/进行中/完成/暂停/停止。Action 请求体是 `{action}`（路由封装 `46028-46035`）。

## 11. 与 Armada 当前能力对账

| 竞品能力 | Armada 当前状态 | 设计处理 |
|---|---|---|
| 数据包/号码池 | 一期已有数据包、当前代和号码池状态 | 直接复用，任务保存代次快照 |
| 超链模板/消息模型 | 一期已有，通用按钮枚举比任务更宽 | 任务 DTO/页面锁 CTA URL，不删通用历史兼容 |
| 任务/recipient/attempt | 尚未实现 | 新增完整领域和状态机 |
| 账号完整筛选 | 通用 `AccountQuery` 维度不足 | 新建 `HyperlinkAccountSelector`，共享筛选 schema 给策略 |
| 好友数/注册天数/允许拉群/轮号 | 缺可靠数据源 | 新增账号营销画像与协议同步；不隐藏控件 |
| 五类号码来源 | 当前来源枚举不足 | 扩展营销来源，不错误映射 |
| 私聊按钮/卡片发送 | 协议链路需实机验证/补齐 | CTA URL 单按钮为唯一任务协议目标 |
| 计费/余额冻结 | 未检索到现成任务计费体系 | 定义 Billing Gateway；完整上线硬依赖 |
| 短链和访问事件 | 未实现 | 保存 IP/UA/设备、UV/PV，权限和保留期控制 |
| 五个详情读模型 | 未实现 | 由 recipient/attempt/visit/账号快照聚合 |
| 前端任务页 | 当前前端未发现任务实现 | 按详细设计逐屏实现 |

## 12. 已确认事实、推断与待验证

### 已确认

- 列表字段、筛选、操作、汇总、分页、手动刷新和导出。
- 四段任务表单、三个新建消息类型、全部显隐/前端校验。
- 任务按钮锁 CTA URL，最多一个，深度追踪为按钮级。
- 完整账号筛选字段及即时/预发布/周期的零账号规则。
- 素材选择/上传两个弹框。
- 7 秒最后核对及余额/预计冻结。
- 五个详情 Tab 的筛选、字段、排序、汇总和导出。
- 生命周期 API 无删除。

### 高可信推断

- 模板/策略是一次性拷贝而非外键实时绑定。
- `total` 在深度归因中代表有点击的 recipient/UV 行数。
- 页面所称后端每分钟同步指聚合读模型的刷新频率。

### 待真实 API/产品确认

1. 即时模式的数据包领取批次、子任务拆分和重试边界。
2. 预发布模式「数据包发完」与新导入号码的代次关系。
3. 周期模式是否重复营销同一号码、每轮 recipient 的唯一键和结算口径。
4. `default_sub_task_num=50` 的后端真实语义。
5. 价格是否按国家/号码/成功发送计费，以及停止后的冻结释放公式。
6. 预计落地率的后端算法。
7. 封号原因统计的账号失效判定窗口。

这些待确认项不影响页面字段和接口外形冻结，但会影响调度、唯一键和账务实现，不得在编码时自行猜定。

## 13. 功能完整性结论

- [x] 任务列表查询筛选
- [x] 当前页汇总指标卡
- [x] 全部表格列和状态展示
- [x] 行操作、确认弹框、分页、手动刷新、导出
- [x] 新建/编辑/查看/复制模式差异
- [x] 基础信息、消息内容、发送策略、受众与发布
- [x] WhatsApp 实时预览
- [x] 模板、策略、数据包和素材选择
- [x] 完整账号筛选抽屉
- [x] 最后核对和计费信息
- [x] 收信人、账号、归因、趋势、封号五个详情 Tab
- [x] API 路径、HTTP 方法、请求字段和状态枚举
- [x] 竞品缺陷与 Armada 能力缺口已单列

结论：已经获得足以冻结页面与服务契约的完整静态业务证据；尚不能从前端构建产物证明的调度和计费算法已明确隔离为待验证项，没有冒充竞品事实。
