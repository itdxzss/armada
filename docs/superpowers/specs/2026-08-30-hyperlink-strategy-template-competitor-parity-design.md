# 超链策略模板竞品对齐设计

- 日期：2026-08-30
- 状态：已实施并同步主仓，待部署验证
- 产品菜单：`超链营销 / 超链策略`
- 用户口径：竞品已有的字段、列表、交互、弹框和任务引用能力全部实现
- 实施范围：`armada` 后端 + `wheel-saas-pure-web` 前端；策略本身不新增协议命令
- 竞品证据：`hylbuiaxykfrontendsource/readable/assets/` 中合法取得的可读构建产物，仅做静态行为分析

> 名称说明：本设计将用户所说的“超链营销模版”理解为剩余菜单中的“超链策略 / 策略模板”。
> 已上线的“超链营销模板”是消息内容模板，二者不是同一个模块。菜单最终仍沿用竞品名称“超链策略”，
> 页面标题为“超链发送策略”。

---

## 1. 一句话结论

实现统一的“超链发送策略”领域实体：运营人员维护可复用模板，创建任务时把最终表单值保存为同一张表里的
任务专属策略快照，`hyperlink_task` 只保存策略 ID，不再重复保存六个策略字段。

本次严格对齐竞品并保持单一事实来源的实现已经完成三项 P0：

1. `最大执行账号数 = 0` 表示“均分/自动分配”，不能校验成 `1..100`；
2. 新策略默认勾选稳定系统编码为 `public`、`hyperlink` 的账号组，不能长期返回空默认值或按名称猜 ID。
3. 模板和任务使用同一个策略表、同一个 DTO/校验器；任务内嵌的六个旧字段完成迁移后删除。

---

## 2. 事实分级与证据覆盖

本文按三档描述事实：

- **Observed（已观察）**：竞品前端代码可直接确认的路由、请求、字段、默认值、校验和交互。
- **Inferred（推断）**：结合页面行为和 Armada 现状设计的后端实现，实施时必须由测试验证。
- **Unknown（未知）**：竞品后端算法或运行时行为无法从前端构建产物确认，不伪造结论。

### 2.1 竞品制品身份

| 制品 | 用途 | 定位/摘要 |
|---|---|---|
| `readable/assets/strategy-D2fnr_pX.js` | 策略列表、编辑抽屉、字段、校验、请求 | 1,732 行；SHA-256 `d1d883a1f4cccf6773a880654485ea2c286470f394f2c8c723c6e9ced314d041` |
| `readable/assets/router-CPQmbuR9.js` | 菜单、路由、API 封装 | 58,852 行；SHA-256 `7e06fb1419f879474eb9eb4c091c425a1e82606a92753a35ab1f5c0d98a6e2fa` |
| `readable/assets/account-filter-modal-BXDIvipG.js` | 完整账号筛选器 | 1,638 行 |
| `readable/assets/task-0vbZUOmq.js` | 新建任务引用策略 | 5,984 行 |
| `readable/assets/account-business-group-DEjomMoO.js` | 系统业务账号组默认值 | 静态常量与选项 |

### 2.2 关键证据索引

| 已观察事实 | 证据位置 |
|---|---|
| 菜单 `/hyperlink/strategy`、标题“超链策略”、顺序 3 | `router-CPQmbuR9.js:49550-49598` |
| CRUD 请求 | `router-CPQmbuR9.js:46038-46066` |
| 新建默认值 | `strategy-D2fnr_pX.js:443-453` |
| 保存字段与三个隐藏常量 | `strategy-D2fnr_pX.js:657-671` |
| 关闭抽屉二次确认 | `strategy-D2fnr_pX.js:727-790` |
| 三种任务模式卡片 | `strategy-D2fnr_pX.js:854-945` |
| 停用策略不进入任务选项 | `strategy-D2fnr_pX.js:951-974` |
| 列表列、零值展示、删除确认 | `strategy-D2fnr_pX.js:1380-1586` |
| 页面说明、标题、启停数量、新建按钮 | `strategy-D2fnr_pX.js:1636-1693` |
| 账号筛选数据模型 | `account-filter-modal-BXDIvipG.js:345-374` |
| 任务加载和复制策略字段 | `task-0vbZUOmq.js:1201-1247` |
| 任务页策略选择器及“不影响”边界 | `task-0vbZUOmq.js:2277-2303` |

这些制品足以覆盖竞品前端可见能力；竞品后端未取得，因此“均分”的具体调度公式属于 Unknown。

---

## 3. 用户故事与业务边界

### 3.1 核心用户故事

作为超链运营人员，我可以：

1. 按名称、启停状态、任务模式查找策略；
2. 新建、编辑、删除一份发送策略；
3. 在策略中保存完整账号筛选条件，并实时看到匹配账号数；
4. 按即时、预发布、周期三种模式配置不同的账号限制；
5. 在创建超链任务时选择一个启用策略，一键带入参数后继续独立编辑任务；
6. 保存任务时生成任务专属策略快照，后续修改或删除模板不改变已经创建或正在运行的任务。

### 3.2 明确不属于策略的内容

策略不保存、不覆盖：

- 消息类型、正文、图片、链接卡片、按钮、短链开关；
- 超链数据包及号码；
- 立即/延后/定时启动时机；
- 单账号内部发送并发 `accountSendConcurrency`；
- 消息间隔 `messageIntervalMinSec/MaxSec`；
- 报价、余额、冻结金额；
- 任务运行状态、统计结果和协议命令。

竞品请求里虽然提交了 `account_send_concurrency=20`、两个消息间隔为 `0`，但页面没有写入控件，
任务引用也不复制它们。Armada 不为这三个隐藏常量建策略列，避免死字段。

---

## 4. 信息架构与页面设计

### 4.1 菜单与权限

- 一级菜单：超链营销
- 二级菜单：超链策略
- 路由：`/hyperlink/strategy`
- 前端组件：`hyperlink/strategy/index`
- 图标：`solar:tuning-2-bold-duotone`
- 排序：位于“超链营销模板”之后、“图片素材”之前

权限键：

| 权限 | 用途 |
|---|---|
| `tenant:hyperlink_strategy:view` | 查看列表和详情 |
| `tenant:hyperlink_strategy:create` | 新建 |
| `tenant:hyperlink_strategy:edit` | 编辑 |
| `tenant:hyperlink_strategy:delete` | 删除 |

任务编辑者获取“启用策略选项”不应被迫拥有策略管理页查看权限。后端 options 接口另允许
超链任务 create/edit 权限访问，只返回任务引用需要的快照字段。

### 4.2 页面线框

```text
超链发送策略                         已启用 8  已停用 2   [新建策略]
┌──────────────────────────────────────────────────────────────┐
│ 策略用于复用发送规则，不绑定消息内容、数据包和启动时机。       │
└──────────────────────────────────────────────────────────────┘

策略名称 [____________]  状态 [全部⌄]
任务模式 (全部) (即时) (预发布) (周期)             [重置] [搜索]

┌──┬──────────┬────┬────────────┬──────┬──────┬──────┬────┬────┐
│ID│策略名/模式│状态│账号范围     │执行号│用号上限│单号上限│周期│操作│
└──┴──────────┴────┴────────────┴──────┴──────┴──────┴────┴────┘
                                      [列设置] [刷新]  分页 20/页
```

### 4.3 顶部说明与统计

顶部信息条使用竞品同等语义：策略用于复用任务模式、账号范围、最大执行账号数、账号使用上限、
单账号发送上限和周期间隔；不绑定消息内容、链接卡片、数据包和启动时间。

“已启用 / 已停用”按竞品显示当前页数量，不冒充全库统计。若未来产品需要全量数量，应新增明确的
后端聚合字段并修改文案，本期不额外扩张。

### 4.4 搜索和表格

搜索条件：

| 条件 | 控件 | 行为 |
|---|---|---|
| 策略名称 | 文本框 | 模糊搜索，回车执行 |
| 状态 | 下拉 | 全部、已启用、已停用 |
| 任务模式 | 单选组 | 全部、即时、预发布、周期 |

搜索或重置后回到第 1 页。分页默认 20，支持 10/20/50/100。

表格列与显示规则：

| 列 | 显示规则 |
|---|---|
| ID | 策略 ID |
| 策略名称 | 名称 + 任务模式标签 |
| 状态 | 已启用/已停用标签；不做行内开关 |
| 账号范围 | 最多显示 3 个摘要标签，剩余显示 `+N` 并提供 Tooltip |
| 最大执行账号数 | `0` 显示“均分”，其他显示数字 |
| 最大使用账号数 | `0` 显示“不限”，其他显示数字 |
| 单账号最大发送数 | `0` 显示“不限”，其他显示数字 |
| 周期间隔 | 周期模式显示“每 X 分钟/小时/天”，其他显示 `—` |
| 创建时间 | 本地时间格式化 |
| 操作 | 编辑、删除，受权限控制 |

保留 pure-admin 表格的列设置和刷新能力。删除使用 Popconfirm：
`确认删除策略「{name}」？此操作不可恢复。` 删除成功后刷新当前列表；若删除后当前页为空且页码大于 1，回退一页。

---

## 5. 新建/编辑抽屉

### 5.1 容器与关闭行为

- 右侧抽屉，宽度随视口在 560px 至 820px 之间响应式变化；
- 点击遮罩或按 ESC 不直接丢弃编辑；
- 表单有变化时弹出确认：`取消后已修改内容将丢失，确定要关闭吗？`，按钮为“关闭 / 继续编辑”；
- 未修改时可直接关闭；
- 新建成功提示“新建成功”，编辑成功提示“保存成功”，随后关闭并刷新列表；
- 保存中禁用重复提交。

### 5.2 分区

抽屉分两个区块：

1. 基础信息：策略名称、任务模式、状态；
2. 发送策略：周期间隔（条件显示）、账号范围、最大执行账号数、最大使用账号数/每轮最大账号数、
   单账号最大发送数。

### 5.3 字段、默认值、校验

| 字段 | 新建默认值 | 控件/取值 | 校验和动态行为 |
|---|---:|---|---|
| 策略名称 | 空 | 文本，最多 128 字 | 必填；仅后台识别；同租户未删除名称唯一 |
| 任务模式 | 即时 `1` | 三张模式卡片 | 必填 |
| 状态 | 启用 | 启用/停用 | 停用后不出现在任务引用选项 |
| 周期间隔 | 60 分钟 | 数字，步长 10 | 仅周期显示；周期必填且 `>=30`，同时显示可读时长 |
| 账号范围 | 公共+超链组 | 摘要 + 设置/修改 + 清空 | 完整筛选器；允许保存匹配数为 0 的策略 |
| 最大执行账号数 | 10 | 数字 `0..100` | `0=均分`；若使用上限大于 0，则固定值不得大于使用上限 |
| 最大使用账号数 | 0 | 数字 `>=0` | 即时/预发布：`0=不限`；周期：标签改为“每轮最大账号数”，必须 `>=1` |
| 单账号最大发送数 | 0 | 数字 `>=0` | `0=不限`；达到上限后该账号停止继续发送 |

切换到周期模式时：若周期间隔无效则设为 60；若最大使用账号数小于 1 则设为 50。切回其他模式不删除用户已填的间隔，
但后端只在周期模式使用该值。

任务模式卡片文案：

| 模式 | 说明标签 | 行为说明 |
|---|---|---|
| 即时 | 一次性、快速 | 尽快完成当前数据包 |
| 预发布 | 持续、自动获取 | 运行至计划结束或数据耗尽，新匹配账号可自动加入 |
| 周期 | 风控、时间对比 | 按周期分轮执行，便于分散风险和比较时段效果 |

账号匹配数提示同时展示实际匹配数与配置上限。例如匹配 320 个账号、最大使用 100 时，提示“当前匹配 320 个，本次最多使用 100 个”；
上限为 0 时提示“当前匹配 320 个，不限使用账号数”。

---

## 6. 账号范围筛选器

复用超链任务已有 `HyperlinkAccountFilterDrawer` 和后端 `HyperlinkAccountFilterDTO`，不得复制第二套筛选模型。
策略场景使用相同标准化、相同匹配查询、相同空值语义，保证“页面看到的匹配数 = 任务运行时可选账号集合”。

### 6.1 可见筛选项

| 分区 | 字段 |
|---|---|
| 账号组 | 账号组多选，可按名称/标签搜索，组间取并集 |
| 地域 | 大洲、包含国家、排除国家；包含与排除互斥 |
| 账号基础 | 手机号模糊、导入批次、在线状态、轮号状态 |
| 账号画像 | 账号类型、导入模式、WID 类型、平台、号码来源 |
| 能力与规模 | 允许拉群、好友数范围、留存天数范围、注册天数范围 |
| 协议归属 | 协议、渠道 |
| 创建时间 | 起止时间，提供今天、近 7 天、近 30 天快捷项 |

其中范围字段必须校验 `min <= max`。注册天数快捷值支持 90/180/365/730/1095 天；留存天数允许一位小数。

### 6.2 策略场景固定条件

策略筛选时后端固定注入：

- `accountStatus = normal`
- `isExported = false`
- `strangerMuted = false`

“允许拉群”仍是用户可见筛选项，不锁死。`hyperlinkTaskCountMin/Max` 仅属于导出/转移等其他业务场景，
不在策略筛选器显示。Armada 内部的 `contactNamedNumMin/Max` 也不在竞品策略页显示，本期不扩张 UI。

### 6.3 实时匹配数

- 筛选条件变动后 250ms 防抖请求；
- 只接受最后一次请求结果，旧响应不得覆盖新条件；
- 请求失败显示重试态，不能将失败误显示成 0；
- 竞品策略场景允许匹配数为 0，因此 0 只警告、不阻止保存；
- 预览计数与任务实际选号必须共享 `HyperlinkAccountSelector`，禁止各写一套 SQL。

### 6.4 未知画像值的语义

账号画像事实缺失时保留 `null/unknown`。只要用户配置了对应筛选条件，未知值就不匹配；不得把未知账号类型、好友数、注册天数、
允许拉群等强转为 `0` 或 `false`，避免错误扩大账号集合。

### 6.5 默认账号组的 P0 前置

竞品新建或清空策略后默认选择系统业务组 `public` 与 `hyperlink`。Armada 当前账号组只有名称和 `system_builtin`，没有稳定业务编码，
也只有“系统默认分组”，无法可靠表达竞品默认值。

实施前由账号域完成全局评审，推荐方案：

1. 为 `account_group` 增加可空、同租户唯一的稳定 `system_code`；用户自建组为空；
2. 通过幂等初始化确保 `public`、`hyperlink` 两个系统组存在；
3. options 接口返回 `id/name/systemCode`；
4. 策略 account-context 依据编码返回 `defaultAccountGroupIds`；
5. 禁止前端按中文名称猜 ID。

若账号域暂不批准该共享表变更，只能把“默认组为空”明确标为竞品偏差，不能宣称完整复刻。

---

## 7. 统一策略事实源与任务引用

### 7.1 引用入口

超链任务新建/复制页的“发送策略”区域增加 `引用策略...` 下拉框：

- 只加载已启用、未删除、当前租户的策略；
- 搜索结果标签为“策略名 · 任务模式”；
- 选择后提示 `已带入策略「{name}」`；
- 严格按竞品在新建/复制时显示，查看页和编辑已有任务时不显示。若产品后续要求编辑时重套策略，作为 Armada 增强另行确认。

### 7.2 只复制这些字段

| 模板策略字段 | 任务专属策略快照字段 |
|---|---|
| `taskMode` | `taskMode` |
| `accountFilter` | `accountFilter` 深拷贝 |
| `maxExecutingAccounts` | `maxExecutingAccounts` |
| `maxUseAccounts` | `maxUseAccounts` |
| `maxSendPerAccount` | `maxSendPerAccount` |
| `cycleIntervalMinutes` | `cycleIntervalMinutes` |

不得覆盖消息内容、数据包、启动时机、消息间隔、单账号内部发送并发。

### 7.3 单一事实来源

模板策略与任务发送策略不是两套模型，最终都保存在 `hyperlink_strategy`。通过 `strategy_scope` 区分用途：

```text
TEMPLATE（菜单维护，可复用、可启停）
       │ 选择后带入表单，用户仍可调整
       │ 保存任务时复制最终值
       ▼
TASK_SNAPSHOT（任务专属，不出现在模板菜单）
       ▲
       │ hyperlink_task.hyperlink_strategy_id，强关联
hyperlink_task（不再存六个策略字段）
```

这样既满足“一份策略事实只落一个地方”，又保证模板修改不会污染历史任务：

- `hyperlink_task.hyperlink_strategy_id` 指向 `TASK_SNAPSHOT`，是任务运行必需的强关联，不再是“仅追溯”；
- `TASK_SNAPSHOT.source_strategy_id` 可记录最初选中的 `TEMPLATE`，只用于来源追溯；
- 即使用户选中模板后又修改字段，保存的是最终表单值对应的任务快照；
- 任务运行、列表、详情、选号、报价均读取该快照，不回读模板行；
- 模板修改、停用或软删除不影响任务快照；
- 菜单 CRUD 只能操作 `TEMPLATE`，不得通过猜测 ID 读取或修改 `TASK_SNAPSHOT`。

### 7.4 新建、复制和编辑事务

新建任务：

1. 后端用共享 `HyperlinkSendStrategyDTO` 规范化最终表单值；
2. 插入 `strategy_scope=TASK_SNAPSHOT` 的策略行，写入可空的 `source_strategy_id`；
3. 插入任务并把 `hyperlink_strategy_id` 指向新策略行；
4. 任一步失败，策略行和任务一起回滚，不能留下孤儿快照。

复制任务时始终复制源任务的策略值生成新快照，不能让两个任务共享一条 `TASK_SNAPSHOT`。

编辑未开始任务时，在同一事务内按 version 更新其专属策略行和任务自身字段；任务一旦进入不可编辑状态，
策略快照也不可单独修改。模板编辑仍只更新模板行。

### 7.5 共享合同

前后端各自只定义一份共享策略合同：

- Java：`HyperlinkSendStrategyDTO`、`HyperlinkSendStrategyFields`、`HyperlinkStrategyValidator`；
- TypeScript：`HyperlinkSendStrategy` 和一份表单校验规则；
- 策略模板接口和任务保存接口都组合这份合同，禁止再声明字段相同但名称/默认值/校验不同的 DTO；
- 周期模板下限 30 分钟与任务现有下限若需保持竞品差异，必须由 `validationContext` 明确表达，不能复制两个校验器。

---

## 8. `0 = 均分` 的全链路设计

### 8.1 已观察与未知

已观察：竞品策略输入允许 `0..100`，提示“0 = 均分逻辑（按号数自动分配）”，列表将 0 显示为“均分”；任务页也接受这份策略。

未知：竞品后端如何结合协议台数、匹配账号数和每轮上限计算最终并发，静态前端无法证明。

### 8.2 Armada 适配（Inferred）

为保证功能不缩水，Armada 将 `0` 持久化为 AUTO，而不是在保存时改写成某个固定数。任务准备执行时解析：

```text
可用账号数 = 本次实际入选账号数
业务上限   = maxUseAccounts > 0 ? maxUseAccounts : 可用账号数
协议上限   = 当前可用协议台数 × 15
安全上限   = 调度器配置的系统硬上限

effectiveMaxExecutingAccounts = min(可用账号数, 业务上限, 协议上限, 安全上限)
```

有可用账号时结果至少为 1；无可用账号时任务进入既有“无可执行账号”分支。即时/预发布在账号准备或启动时计算；周期模式每轮重新计算，
使新增可用账号和协议容量能参与下一轮。固定值 `1..100` 继续执行现有校验，并受协议容量及账号上限约束。

报价/终审接口同时返回：

- `configuredMaxExecutingAccounts`：保留用户填写的 0；
- `effectiveMaxExecutingAccounts`：按当前快照解析的预估值；
- `effectiveConcurrencyReason`：AUTO 或 FIXED。

这套公式是 Armada 设计，不冒充竞品后端事实。若能在授权环境对竞品提交 `concurrent_num=0` 并观察任务创建/执行响应，应优先用实测结果校正。

### 8.3 需要同步修改的现状限制

- 统一策略表约束使用 `BETWEEN 0 AND 100`；
- 策略前端校验从 `1..100` 改为 `0..100`；
- 任务前端和 `HyperlinkTaskConfigurationFactory` 接受 0；
- 报价、终审、任务调度、周期轮次测试覆盖 AUTO；
- 数据库账号上限约束允许 `concurrent_num=0`：固定值才要求不大于 `max_use_account`；
- 任务运行链路改为从 `TASK_SNAPSHOT` 读取 AUTO 配置，不能继续读取任务表旧列。

如果只改策略页、不改任务引擎，用户能保存却不能创建/执行任务，是不可接受的半成品。

---

## 9. 后端接口合同

沿用 Armada `/api/<resource>`、camelCase、`ApiResponse<T>` 与 `PageResult<T>` 规范。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/hyperlink-strategies` | 分页列表 |
| GET | `/api/hyperlink-strategies/{id}` | 编辑详情 |
| POST | `/api/hyperlink-strategies` | 新建 |
| PUT | `/api/hyperlink-strategies/{id}` | 编辑，带乐观锁 version |
| DELETE | `/api/hyperlink-strategies/{id}` | 软删除 |
| GET | `/api/hyperlink-strategies/options` | 任务引用的启用策略选项 |
| GET | `/api/hyperlink-strategies/account-context` | 筛选选项与默认账号组，不依赖钱包 |
| POST | `/api/hyperlink-strategies/account-match-count` | 账号匹配数 |

### 9.1 列表请求

```text
page=1
pageSize=20
name=<可空>
taskMode=<1|2|3，可空>
enabled=<true|false，可空>
```

默认排序：`updated_at DESC, id DESC`（Armada 推断设计，竞品前端未证明后端排序）。

### 9.2 保存模型

```text
name: string                         # 模板元数据
enabled: boolean                     # 模板元数据
strategy: HyperlinkSendStrategyDTO { # 模板与任务共用
  taskMode: 1 | 2 | 3
  accountFilter: HyperlinkAccountFilterDTO
  maxExecutingAccounts: integer 0..100
  maxUseAccounts: integer >= 0
  maxSendPerAccount: integer >= 0
  cycleIntervalMinutes: integer
}
version: integer                     # 编辑必填
```

后端必须重做所有前端校验。更新时采用 `id + tenant_id + version + deleted_at IS NULL`，成功后 version+1；
重复名称和版本冲突返回可区分的业务错误，不能都吞成“操作失败”。

建议错误映射：

| 场景 | HTTP/业务码 | 文案 |
|---|---|---|
| 字段或跨字段校验失败 | 400 / `40001` | 返回具体字段原因 |
| 策略不存在或越租户 | 404 / `40401` | 策略不存在 |
| 同租户名称重复 | 409 / `40901` | 策略名称已存在 |
| 乐观锁冲突 | 409 / `40902` | 策略已被他人修改，请刷新后重试 |

### 9.3 options 合同

仅返回启用策略，支持 `keyword` 和受控 `limit`，不得由前端固定拉取 10,000 条。返回字段足以完成一次性复制：

```text
id, name, taskMode, accountFilter,
maxExecutingAccounts, maxUseAccounts, maxSendPerAccount, cycleIntervalMinutes
```

这里的 `id` 是 `TEMPLATE` 行 ID，只用于页面选择与来源追溯；任务保存成功后真正关联的是服务端创建的
`TASK_SNAPSHOT` 行 ID。

### 9.4 account-context 合同

返回账号组、国家/大洲、协议、渠道等筛选选项，以及 `defaultAccountGroupIds`。该接口不读取钱包，
策略 CRUD 也不应因租户未开通钱包而不可使用。

### 9.5 任务保存合同调整

任务保存 DTO 将六个平铺字段收敛成同一份 `strategy`，另带可空的 `sourceStrategyId`：

```text
sourceStrategyId: long | null        # 用户选择的 TEMPLATE ID
strategy: HyperlinkSendStrategyDTO   # 用户调整后的最终值
strategyVersion: integer | null      # 编辑任务时的 TASK_SNAPSHOT 乐观锁
```

后端不能信任客户端传入的快照策略 ID，也不允许任务直接关联 `TEMPLATE`。新建时由服务端创建快照；编辑时从任务关系反查
自己的 `TASK_SNAPSHOT` 并按 version 更新。任务详情返回 `strategyId`、`sourceStrategyId`、`strategyVersion` 和共享 strategy 对象。

---

## 10. 数据模型

新表 `hyperlink_strategy` 是所有超链发送策略的唯一事实源。一行代表一份模板策略或一份任务专属策略快照，
两类行使用完全相同的六个业务字段。

| 字段 | 类型 | 约束/语义 |
|---|---|---|
| `id` | BIGINT | 主键 |
| `tenant_id` | BIGINT NOT NULL | 租户隔离 |
| `strategy_scope` | TINYINT NOT NULL | 1=`TEMPLATE`，2=`TASK_SNAPSHOT` |
| `owner_task_id` | BIGINT | 模板为空；任务快照绑定后必填，租户内唯一 |
| `source_strategy_id` | BIGINT | 快照来源模板 ID；模板行为空，只追溯不强依赖 |
| `strategy_name` | VARCHAR(128) | 模板必填；任务快照为空 |
| `task_type` | TINYINT NOT NULL | 1 即时、2 预发布、3 周期 |
| `account_filter` | JSON NOT NULL | 标准化后的完整筛选快照 |
| `concurrent_num` | INT NOT NULL DEFAULT 10 | 0=AUTO/均分，1..100=固定 |
| `max_use_account` | INT NOT NULL DEFAULT 0 | 0=不限；周期必须 >=1 |
| `account_max_send_num` | INT NOT NULL DEFAULT 0 | 0=不限 |
| `task_interval_minutes` | INT NOT NULL DEFAULT 60 | 模板周期 >=30；任务快照周期 >=1 |
| `is_enabled` | TINYINT(1) NOT NULL DEFAULT 1 | 模板是否可选；任务快照恒 1 且不作为任务状态 |
| `version` | INT NOT NULL DEFAULT 1 | 乐观锁 |
| `created_by` | BIGINT | 创建人 |
| `created_at` / `updated_at` | BIGINT NOT NULL | epoch 毫秒 |
| `deleted_at` | BIGINT | 模板软删除；任务快照按任务生命周期保留 |
| `template_active` | 生成列 | `scope=TEMPLATE AND deleted_at IS NULL` 时为 1，否则 NULL |

索引：

- 唯一：`tenant_id, strategy_name, template_active`，只约束未删除模板名称；
- 任务快照唯一：`tenant_id, owner_task_id`，保证一个任务一份独占快照；
- 模板列表：`tenant_id, strategy_scope, deleted_at, updated_at, id`；
- 启用选项：`tenant_id, strategy_scope, is_enabled, deleted_at, id`；
- 来源追溯：`tenant_id, source_strategy_id, id`。

检查约束：

- `strategy_scope IN (1,2)`
- `task_type IN (1,2,3)`
- `concurrent_num BETWEEN 0 AND 100`
- `max_use_account >= 0`
- `account_max_send_num >= 0`
- 模板周期模式时 `task_interval_minutes >= 30 AND max_use_account >= 1`
- 任务快照周期模式时 `task_interval_minutes >= 1 AND max_use_account >= 1`
- `max_use_account = 0 OR concurrent_num = 0 OR max_use_account >= concurrent_num`
- 模板行 `strategy_name` 必填且 `owner_task_id` 为空；任务快照行 `strategy_name` 为空、
  `source_strategy_id` 可空、绑定后 `owner_task_id` 必填且 `is_enabled=1`

不增加 `remark`：竞品无备注控件，现阶段没有写入方。也不增加三个隐藏常量列。

### 10.1 `hyperlink_task` 收敛

最终态 `hyperlink_task.hyperlink_strategy_id` 改为 NOT NULL，指向本任务独占的 `TASK_SNAPSHOT`。以下六列从任务表删除：

```text
task_type
task_interval_minutes
account_filter
max_use_account
concurrent_num
account_max_send_num
```

任务独有字段继续留在任务表：启动方式、延后时间、计划结束时间、数据包、内容来源、消息间隔、单账号内部发送并发、
短链投影、生命周期版本等。不能因为名称中带“发送”就把竞品模板不管理的字段硬塞进统一策略。

任务模式筛选、详情和执行查询统一 JOIN `hyperlink_strategy`；调度器使用的任务聚合对象应包含 `task + strategy`，
业务服务不再从 `HyperlinkTask` 实体直接读取六个旧字段。原 `idx_hyperlink_task_planned_end` 去掉 task_type，模式条件通过策略 JOIN 过滤。

### 10.2 存量迁移与最终一致性

采用 expand/contract 两阶段迁移：

1. 创建统一策略表；为每条存量任务按原六列插入一条 `TASK_SNAPSHOT`；
2. 回填 `hyperlink_task.hyperlink_strategy_id`，校验每个任务恰好关联一条、字段逐项相等；
3. 发布兼容版本，读取优先走策略表；滚动发布期间可短暂双写旧列，但策略行为的逻辑入口只能有一个共享对象/校验器；
4. 全部实例切换且对账通过后停止旧列写入；
5. 后续收缩迁移将策略 ID 设为 NOT NULL，并删除六个旧列及其检查约束。

双写只允许作为有明确截止点的迁移手段，不是最终架构。回填脚本必须幂等，并提供“任务数、快照数、空关联数、字段差异数”对账。

Flyway 版本在实施开始时依据目标分支动态分配。当前工作区已有未合入的 `V167` 变更，不能提前把候选 `V168` 当作最终编号。

---

## 11. 后端模块设计

建议在 `com.armada.hyperlink.strategy` 下按现有超链域模式组织：

- `HyperlinkStrategyController`：HTTP、权限、参数转换；
- `HyperlinkStrategyCommandService`：新建、更新、删除、乐观锁；
- `HyperlinkStrategyQueryService`：列表、详情、options、account-context；
- `HyperlinkStrategyValidator`：模式和跨字段规则；
- `HyperlinkStrategyMapper` + XML：显式租户条件、软删条件、分页查询；
- `HyperlinkTaskStrategyService`：任务快照创建、复制、可编辑态更新和所有权校验；
- `HyperlinkTaskAggregateMapper`：用任务 + `TASK_SNAPSHOT` 一次查询装配运行配置；
- 复用 `HyperlinkAccountFilterNormalizer` / `HyperlinkAccountSelector`：标准化、选项、计数和运行时选号。

关键要求：

- 所有查询和修改显式限制 `tenant_id`，不能仅依赖前端 ID；
- 菜单模板删除是软删，任务快照不级联；任务绝不直接引用模板行；
- account filter 先拒绝未知字段，再标准化后存储；
- options、详情返回深拷贝/独立 DTO，不能让调用端修改缓存对象；
- 不打印完整手机号、账号 ID 集合或筛选结果明细；日志只记录 tenant、strategyId、模式、匹配数量、耗时和错误码；
- 匹配数查询做限流/超时保护，必要时只缓存标准化条件哈希对应的短时计数，不缓存账号明细。

现有读取 `task.getTaskType()/getAccountFilter()/getConcurrentNum()` 等位置必须改为读取聚合对象的 `strategy()`：
任务列表与详情 Mapper、账号候选选择、轮次账号选择、首轮创建、轮次生命周期、任务动作、报价与 quote guard。
不能保留“新代码有时读 strategy、有时读 task 旧列”的隐性双事实源。

策略 CRUD 不触发 Kafka、不操作协议层、不创建任务、不扣费。

---

## 12. 前端模块设计

建议结构：

```text
src/api/hyperlink-strategy.ts
src/views/hyperlink/strategy/index.vue
src/views/hyperlink/strategy/components/HyperlinkStrategyDrawer.vue
src/views/hyperlink/strategy/composables/useHyperlinkStrategyPage.ts
src/views/hyperlink/shared/HyperlinkAccountFilterDrawer.vue
src/views/hyperlink/shared/hyperlink-account-filter.ts
```

若当前筛选组件仍在任务目录内，先以不改变行为的方式提升到 `hyperlink/shared`，任务和策略共同引用；
不得复制组件形成两套字段与校验。API 只能放 `src/api`，页面不直接调用 axios。

状态分层：

- URL/query state：搜索条件、页码、pageSize；
- page composable：列表请求、删除、刷新、列设置；
- drawer local state：表单草稿、原始快照、dirty 判断、保存状态；
- account drawer：筛选草稿、options、匹配计数与防抖；
- domain helper：模式标签、时长格式、摘要标签、交叉校验。

生产菜单以 `/api/tenant/me/menus` 为准；本地 mock 只用于开发 fallback。单个 `.vue` 控制在 400 行左右，
不能把列表、策略表单和完整账号筛选器塞进一个大文件。

---

## 13. Armada 当前差距清单

| 能力 | 当前主线 | 本地候选 | 本设计结论 |
|---|---|---|---|
| 策略菜单/CRUD | 未上线 | 已有候选 | 保留并按本设计复核 |
| 完整账号筛选 | 任务已具备 | 可复用 | 必须共用一套组件/DTO/查询 |
| 实时匹配数 | 任务已有基础 | 候选有独立入口 | 复用同一 Selector，无钱包依赖 |
| `0=均分` | 任务当前要求 >0 | 候选策略为 1..100 | P0 改为全链路支持 0 |
| 公共+超链默认组 | 无稳定编码，返回空 | 仍为空 | P0：账号组稳定系统编码 |
| 策略字段事实源 | 六字段在 task | 候选模板另建同字段 | P0：统一移入 strategy，task 只存 ID |
| 任务引用策略 | UI 已有部分预留 | 候选可带入 | 最终表单值生成同表 TASK_SNAPSHOT |
| `hyperlink_strategy_id` | 预留但无写入 | 候选多作来源 ID | 改为任务快照强关联 |
| 模板来源追溯 | 当前没有 | 候选用 task 字段 | 改存快照行 `source_strategy_id` |
| 停用策略过滤 | 主线 options 有预留 | 候选支持 | 后端强制只返回 enabled |
| 关闭二次确认 | 无策略页 | 候选需复核 | 必须按竞品实现 |
| 隐藏常量列 | 宽方案已删除 | 候选未落列 | 继续禁止 |

本地候选分支只能作为代码参考，不能直接认定完成。实施时应在目标基线上重新核对迁移编号和共享文件冲突，
并保留当前工作区中与账号类型校验相关的未提交改动。

---

## 14. 异常、并发与边界态

| 场景 | 预期 |
|---|---|
| 同名策略并发创建 | 数据库唯一键兜底，后发请求返回名称重复 |
| 两人同时编辑 | version 乐观锁；后保存者收到冲突，不覆盖 |
| 编辑期间策略被删 | 保存返回不存在/已删除，保留用户草稿便于复制 |
| 删除作为来源的模板 | 允许软删；任务关联的是同表独立快照，继续运行 |
| 账号选项加载失败 | 抽屉显示重试，不用空 options 覆盖已保存 ID |
| 匹配数为 0 | 警告但允许保存策略；任务创建/启动走现有空账号校验 |
| 匹配数请求乱序 | 只显示最后一次条件的响应 |
| 账号组后来被删除 | 详情保留原 ID；摘要显示“已失效账号组”，编辑保存前要求用户处理 |
| 已停用策略 | 管理页可见，任务 options 不可见 |
| 策略选中后被停用 | 已复制到任务草稿的参数可继续保存；保存只依赖快照，不重读策略 |
| 任务创建中途失败 | task 与 TASK_SNAPSHOT 同事务回滚，无孤儿行 |
| 两个任务引用同一模板 | 各自生成独占快照，不能共享同一 TASK_SNAPSHOT |
| 访问任务快照策略 ID | 模板 CRUD 拒绝 scope 不符；只能由所属任务聚合读取/更新 |
| `maxUse=0, concurrent=0` | 两者分别表示不限与 AUTO，按运行时容量解析 |
| 周期改为即时 | 隐藏周期控件，任务不使用间隔；保存值可规范化为 60 |
| 越租户 ID | 对外表现为不存在，不泄露资源存在性 |

---

## 15. 验收矩阵

### 15.1 列表与权限

- [ ] 菜单位置、路由、图标和页面标题与竞品一致；
- [ ] 名称、状态、模式搜索及 Enter/重置行为正确；
- [ ] 分页支持 10/20/50/100，默认 20；
- [ ] 表格所有列、摘要 `+N`、零值文案、时间格式齐全；
- [ ] 列设置、刷新、新建、编辑、删除交互齐全；
- [ ] 四个权限既控制按钮，也由后端强制校验；
- [ ] 租户 A 无法读取或修改租户 B 的策略。

### 15.2 表单与账号筛选

- [ ] 新建默认值完全符合 §5.3；
- [ ] 三种模式卡片、状态说明和周期字段动态行为齐全；
- [ ] 名称长度、周期下限、上下限关系均有前后端校验；
- [ ] `maxExecutingAccounts=0` 可保存且列表显示“均分”；
- [ ] 即时/预发布 `maxUse=0` 显示“不限”，周期强制至少 1；
- [ ] 完整筛选字段可设置、回填、清空，包含/排除国家互斥；
- [ ] 固定条件 normal/not-exported/not-muted 无法被绕过；
- [ ] 匹配数 250ms 防抖、旧响应丢弃、失败重试、0 可保存；
- [ ] public + hyperlink 默认账号组由稳定编码解析；
- [ ] 表单 dirty 时关闭有二次确认，未修改时直接关闭。

### 15.3 任务引用与运行

- [ ] 新建/复制任务只显示启用策略，可按名称搜索；
- [ ] 选择后只带入 6 个业务参数，不覆盖内容、数据包、启动时间和消息节奏；
- [ ] 任务保存最终值为独占 `TASK_SNAPSHOT`，task 只保存该策略 ID；
- [ ] `TASK_SNAPSHOT.sourceStrategyId` 仅用于模板来源追溯；
- [ ] 修改、停用、删除策略后，存量任务数据与运行行为不变；
- [ ] `0=均分` 在即时、预发布、周期三种模式均能创建和运行；
- [ ] 周期模式每轮重新解析 AUTO，有账号时有效值至少 1；
- [ ] 报价/终审同时展示配置值 0 和当前解析的有效值；
- [ ] 协议容量不足、0 匹配账号、达到账号/单账号上限均进入明确业务态。

### 15.4 数据与质量

- [ ] Flyway 在 MySQL 目标版本通过，所有新列有 COMMENT；
- [ ] H2 使用真实 Mapper XML、租户插件和 Spring 事务验证 CRUD/唯一键/乐观锁；
- [ ] JSON 未知字段 fail closed，未知账号画像不被当成 0/false；
- [ ] 删除软删且名称可在旧记录删除后重用；
- [ ] 存量任务均已生成唯一快照，空关联数和六字段差异数均为 0；
- [ ] 收缩迁移后 task 不再保留六个策略列，运行链路无旧列读取；
- [ ] 任务创建/复制/编辑失败时不产生孤儿快照；
- [ ] 前端 typecheck、lint、build 通过；
- [ ] 后端策略单测、Mapper 测试和相关超链任务回归通过；
- [ ] 生成并复核数据模型文档；
- [ ] 日志无手机号、账号明细、消息正文或敏感筛选结果。

---

## 16. 实施顺序

每个任务控制在半天左右，可独立验证：

1. 冻结共享策略 DTO、唯一校验器、AUTO 语义和 TEMPLATE/TASK_SNAPSHOT 边界；
2. 经账号域全局评审增加稳定系统组编码并补 public/hyperlink 初始化；
3. 新增统一策略表、TEMPLATE CRUD、唯一键和乐观锁测试；
4. 回填每个存量任务的 TASK_SNAPSHOT 与策略 ID，完成逐字段对账；
5. 将任务 Mapper、运行、选号、报价和生命周期切到 task+strategy 聚合；
6. 接入 account-context、完整账号筛选标准化与匹配数；
7. 实现策略列表、新建/编辑抽屉、权限、关闭确认和匹配提示；
8. 接入任务 options、最终值快照、`sourceStrategyId` 和事务回滚；
9. 打通任务/报价/调度的 `0=AUTO`，覆盖三种模式；
10. 全量对账后删除 task 六个旧列，完成前后端构建与全链路验收；
11. 更新 `.harness/changes` 和生成数据模型文档。

不建议把第 8 步延期：只完成管理页而不能正确执行 `0=均分`，不满足“竞品有的我们也要有”。

---

## 17. 上线、回滚与观测

上线顺序：扩展迁移和存量快照回填 → 后端聚合读取与兼容双写 → 前端菜单/页面 → 全量对账 → 停止旧列写入 →
收缩迁移删除 task 六列。前端后开放 `0=AUTO`，避免旧后端拒绝 0。

灰度观测：

- 策略 CRUD 成功率、409 冲突数；
- account match count P95 和超时率；
- 被任务引用的策略次数；
- TASK_SNAPSHOT 与任务一对一完整率、孤儿快照数；
- 迁移期新旧六字段差异数；
- AUTO 任务配置值、解析值和受限原因的聚合分布；
- 因无账号、协议容量、账号上限导致的任务阻塞数。

删除 task 旧列之前可回滚到兼容读版本；完成收缩迁移后不能回滚到依赖旧列的版本。回滚时先隐藏菜单和模板入口，
保留统一策略表和任务快照，不删除用户数据。AUTO 已写入的快照必须继续兼容，不能简单回滚到拒绝 0 的旧版本。

---

## 18. 仍需实测的 Unknown

| 未知项 | 为什么未知 | 最低成本验证 |
|---|---|---|
| 竞品 `0=均分` 的精确调度公式 | 前端只有文案和取值范围 | 授权环境创建 0 并发任务，观察创建响应、终审和实际并发 |
| 竞品列表默认排序 | 请求只暴露筛选和分页 | 创建两条策略后编辑旧记录，观察顺序变化 |
| 删除已被任务引用的竞品策略是否有后端保护 | 前端仅显示通用删除确认 | 创建任务引用后尝试删除策略并观察任务快照 |
| public/hyperlink 组在不同租户的初始化时点 | 前端仅使用稳定编码 | 观察新老租户 account-group options 与初始化接口 |

这些未知项不阻塞 Armada 设计：统一策略事实源、模板来源弱引用、任务快照强关联、稳定系统组编码和 AUTO 公式均有明确、可测试的本地语义；
但对外表述时应说“功能对齐、后端按 Armada 规则实现”，不能说已经证明算法逐字相同。

---

## 19. 完成定义

只有在以下条件同时满足时，才可以把“超链策略”标为完成：

1. §15 所有前端可见项逐条验收通过；
2. P0 的 `0=均分` 与默认 public/hyperlink 账号组不是空实现；
3. 任务引用确实在统一策略表生成独占快照并可执行，而非只有下拉框；
4. task 最终不再保存六个策略字段，模板与任务共享 DTO、校验器、账号筛选器和匹配查询；
5. 租户、权限、乐观锁、软删、名称唯一和敏感日志均有自动化证据；
6. 未知项被标注，未用猜测冒充竞品已观察事实。
