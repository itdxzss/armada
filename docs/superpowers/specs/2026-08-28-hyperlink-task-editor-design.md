# 超链任务新建、编辑、查看与复制设计

> 状态：设计冻结候选，2026-08-28。本文是 H2，负责同一任务抽屉的四种打开模式、表单字段、实时预览、
> 模板/策略/数据包/素材选择、账号范围筛选、查看回填、复制预填和客户端校验。
> 公共 HTTP、Save DTO、枚举、权限和错误以
> [`2026-08-28-hyperlink-task-shared-contract.md`](./2026-08-28-hyperlink-task-shared-contract.md) v1.3 为准；
> 创建、编辑、报价、计费和生命周期事务由 H3 负责。

## 1. 目标与红线

Armada 使用一个右侧抽屉完整复刻竞品的“新建、编辑、查看、复制”四种形态。竞品存在的可见字段、按钮、
提示、选择器、弹框和联动都必须实现，不能因为后端采用更稳定的 JSON + AssetId 合同而删减前端能力。

本方案不新增独立 `/copy` 接口；复制读取源任务并以创建方式提交。图片仍允许“从素材库选择”和“上传”，
只是上传先落共享素材表，任务保存时提交稳定 AssetId。竞品构建产物里出现的随机示例默认文案不作为生产数据；
输入框保留示例 placeholder，真正新建默认只创建一枚可编辑的 CTA URL 按钮，避免误把示例广告发给真实用户。

### 1.1 竞品证据与结论

| ID | 观察到的能力 | 证据 | 结论 |
|---|---|---|---|
| E-H2-01 | 同一抽屉支持新建、编辑、查看、复制；查看整体只读；编辑锁定消息类型 | `task-0vbZUOmq.js:1795-1861, 1895-1968` | 原样实现 |
| E-H2-02 | 复制沿用源配置、名称追加“副本”、强制清空数据包 | `task-0vbZUOmq.js:1515-1575, 1813-1820` | 原样实现，增加 `sourceTaskId` 审计 |
| E-H2-03 | 左侧 WhatsApp 实时预览、右侧四段表单，底部提示以客户端效果为准 | `task-0vbZUOmq.js:1862-1905` | 原样实现 |
| E-H2-04 | 新建可选普通按钮、卡片按钮、单图文；历史双图文编辑时锁类型但内容可编辑；单图文有兼容性告警 | `task-0vbZUOmq.js:826-831, 1895-1905, 1937-1968, 2021-2246` | 原样实现 |
| E-H2-05 | 消息内容按类型切换，图片走素材选择/上传，按钮最多 1 个且锁定链接跳转 | `task-0vbZUOmq.js:2002-2275` | 全量实现 |
| E-H2-06 | 按钮具有文字、URL、深度追踪开关、删除和添加入口 | `hyperlink-button-editor-CcRhevR2.js:262-374` | 全量实现 |
| E-H2-07 | 引用模板只覆盖消息内容；引用启用策略只覆盖任务模式、账号范围、并发、限号和周期间隔 | `task-0vbZUOmq.js:1142-1269, 2285-2303` | 原样实现，不污染其他字段 |
| E-H2-08 | 三种任务模式、计划结束、周期间隔、账号范围、间隔、并发、限号和启动方式 | `task-0vbZUOmq.js:2304-2762` | 全量实现 |
| E-H2-09 | 账号范围右抽屉含匹配数、清空/取消/确定和完整筛选项，固定只圈有效且未导出、未禁言账号 | `account-filter-modal-BXDIvipG.js:900-1538` | 全量实现；固定条件由后端附加 |
| E-H2-10 | 数据包候选展示名称、未使用数和备注；已失效的当前数据包仍可回显 | `task-0vbZUOmq.js:1103-1140, 1273-1285` | 原样实现 |
| E-H2-11 | 启用并入队/仅保存二选一，启用时数据包必选；即时任务须至少有一个可用账号 | `task-0vbZUOmq.js:1576-1642, 2762-2852` | 前后端双重校验 |
| E-H2-12 | 纯新建在提交前有 7 秒“最后核对”，展示余额、预计冻结和关键任务信息 | `task-0vbZUOmq.js:1643-1737, 2870-3008` | 保留能力，金额改用服务端 Quote |
| E-H2-13 | 非查看关闭时二次确认；遮罩和 ESC 不能直接关闭 | `task-0vbZUOmq.js:1696-1737, 1795-1811` | 原样实现 |
| E-H2-14 | 新建及清空账号范围默认选择系统业务分组 `public + hyperlink`；用户可主动移除以表示不限分组 | `account-business-group-DEjomMoO.js:1-24; account-filter-modal-BXDIvipG.js:793-809; task-0vbZUOmq.js:1308-1321, 1394-1400, 1723-1726, 2862-2870` | 原样实现，ID 由服务端上下文返回 |

证据状态均为 **Observed**。应用 JSON、稳定 AssetId、服务端 Quote、租户权限、乐观锁和复制来源审计为
**Adapted**，不减少竞品能力。

## 2. 抽屉和四种模式

抽屉宽度为 `min(1240px, max(820px, viewport - 80px))`，右侧打开；遮罩不可直接关闭、ESC 不可直接关闭。
左侧预览固定可见，右侧表单滚动。窗口过窄时允许整体横向适配，但不能把实时预览删除。

| 行为 | 新建 | 编辑 | 查看 | 复制 |
|---|---|---|---|---|
| 标题 | 新建超链群发任务 | 编辑超链群发任务 | 查看超链群发任务 | 复制超链群发任务 |
| 获取详情 | 否 | 是 | 是 | 是 |
| 表单可编辑 | 是 | 是 | 否，整块 `inert` | 是 |
| 消息类型可改 | 是 | 否 | 否 | 是 |
| 引用模板/策略 | 是 | 否 | 否 | 是 |
| 数据包 | 用户选择 | 回填 | 回显 | 清空并要求重选 |
| 任务名 | 空 | 回填 | 回显 | `源名称 + " 副本"` |
| 底部主按钮 | 创建任务 | 保存修改 | 关闭 | 创建任务 |
| 提交前 7 秒复核 | 是（启用和仅保存均有） | 否 | 否 | 否，服务端重新报价 |
| `sourceTaskId` | `null` | `null` | 不提交 | 源任务 ID |

运行状态不是“是否显示编辑按钮”的唯一防线。`GET /{id}` 返回事实状态和 `editable`；前端即使通过旧页面打开编辑，
后端 PUT 仍只允许未开始任务。进入查看时显示“任务已开始/进行中/已完成/已暂停/已停止，仅可查看，不能修改”。

关闭规则：查看直接关闭；其余模式点击关闭、遮罩或 ESC 都弹“确认关闭？”，编辑文案提示未保存修改将丢失，
新建/复制提示已填写内容将丢失，按钮为“关闭 / 继续编辑”。底部“取消”同样走此确认，不能直接丢表单。

## 3. 页面结构与字段

### 3.1 基础信息

| UI 字段 | Save DTO | 必填/限制 | 表 |
|---|---|---|---|
| 消息类型 | `messageType` | 必填；新建/复制可选 1/3/4；编辑锁定类型但内容仍可编辑；2 仅历史任务存在 | `hyperlink_task_content.message_type` |
| 任务名称 | `taskName` | trim 后必填，最多 128 字；聚焦可显示数据包名称建议 | `hyperlink_task.task_name` |

选择单图文必须展示竞品同等告警：“单图文可能在大部分手机型号上无法正常显示，建议优先选择其他消息类型”。
选择消息类型会重排条件字段和实时预览，但不能清空用户已填的其他类型字段；最终保存时服务端只持久化当前类型有效字段，
无效字段规范化为 `null/[]`。

### 3.2 消息内容

右上保留“引用模板...”可搜索下拉，仅新建/复制显示。选择模板后覆盖 `messageType` 和全部
`messageContent`，包括两张图、按钮及深度追踪开关；不修改任务名、账号范围、数据包、发送策略、启动方式和发布开关。

| 字段 | 单图文 1 | 双图文 2（历史） | 普通按钮 3 | 卡片按钮 4 | 限制/落表 |
|---|---:|---:|---:|---:|---|
| 链接预览图 | 显示 | 显示 | — | — | 选填，JPG/JPEG ≤500KB，`content.link_preview_asset_id` |
| 消息标题 | 显示 | 显示 | 显示，多行 | 显示 | 必填，≤1024，`content.title` |
| 链接描述 | 显示 | 显示 | — | — | 必填，`content.link_description` |
| 推广链接 | 显示 | 显示 | — | — | 必填，≤2048，`content.promotion_link` |
| 正文主图 | — | 显示 | 显示 | 显示 | 选填，JPG/JPEG ≤500KB，`content.body_main_asset_id` |
| 正文/底部/副标题小字 | 正文 | 正文 | 底部小字 | 副标题小字 | 1/2 必填且 ≤2000；3/4 选填且 ≤200，`content.content` |
| 卡片正文 | — | — | — | 显示 | 必填，≤500，`content.card_text` |
| 消息按钮 | — | — | 显示 | 显示 | 恰好 1 个 CTA URL，`content.buttons` |

两处图片控件都必须提供：空态预览、点击打开“从素材库选择”弹框、按名称/标签搜索、选择一张并“使用该素材”、
批量上传入口、上传后选择、已选图预览、点击更换和清空。查看模式只展示图片，不出现更换/清空。
任务抽屉不直接保存二进制：`POST /api/resource-assets` 上传后使用返回 ID，内容 URL 为
`GET /api/resource-assets/{id}/content`；服务端校验素材同租户、未删除、JPEG 且 ≤500KB。

按钮编辑器不能简化成两个普通输入框，必须保留以下竞品交互：

- 空态“还没有按钮”、添加按钮、达到 `1/1` 上限提示、删除按钮。
- 类型固定显示“链接跳转”，不展示电话/复制/快捷回复选项。
- `displayText` 必填、最多 30 字；`url` 必填并由服务端校验 `http/https`。
- “深度追踪”开关 `useShortLink`、说明 tooltip 和风险警告。
- 开启深度追踪后，H3 为该任务生成可追踪短链；关闭时按原 URL 发送。

实时 WhatsApp 预览接收表单所有消息字段并即时更新；底部固定展示“最终展示效果以接收方 WhatsApp 客户端为准”。

### 3.3 发送策略

右上保留“引用策略...”可搜索下拉，仅新建/复制显示，只列启用策略。导入范围严格限定为：
`taskMode/accountFilter/maxExecutingAccounts/maxUseAccounts/maxSendPerAccount/cycleIntervalMinutes`。
不覆盖消息内容、数据包、任务名、消息间隔和启动方式。策略页没有消息间隔字段，因此任务继续保留当前间隔。

| 字段 | DTO | 规则 | 表 |
|---|---|---|---|
| 任务模式 | `taskMode` | 即时/预发布/周期三张卡，必填 | `hyperlink_task.task_type` |
| 计划结束时间 | `plannedEndAt` | 仅预发布；必填，至少晚于当前 1 分钟 | `hyperlink_task.task_planned_end_at` |
| 任务执行间隔 | `cycleIntervalMinutes` | 仅周期；整数 ≥1；默认 60 | `hyperlink_task.task_interval_minutes` |
| 账号范围 | `accountFilter` | 完整快照，见 §4 | `hyperlink_task.account_filter` |
| 消息间隔 | `messageIntervalMinSeconds/MaxSeconds` | 0～10 秒，0.1 精度，min≤max | `hyperlink_task.msg_interval_min_ms/max_ms` |
| 最大执行账号数 | `maxExecutingAccounts` | 整数 ≥1，默认 10 | `hyperlink_task.concurrent_num` |
| 最大使用账号数 | `maxUseAccounts` | 即时/预发布 0=不限；周期必须 ≥1 | `hyperlink_task.max_use_account` |
| 每账号最大发送数 | `maxSendPerAccount` | 整数 ≥0；0=不限制/封号为止 | `hyperlink_task.account_max_send_num` |
| 启动方式 | `startMode` | 立即执行/延后执行 | `hyperlink_task.start_mode` |
| 延迟时间 | `delayMinutes` | 延后且启用时必填，整数 ≥1 | `hyperlink_task.task_delay_minutes` |

三种模式提示必须保留：即时一次发完；预发布到结束时间或数据包发完先到为准且新号自动加入；周期按间隔循环、
用于时段风控观察并由用户手动停止。切入预发布且未选时间时默认当天 23:59；切入周期时默认间隔 60、每轮最大账号 5。

消息间隔保留三枚快捷预设和双输入+范围滑杆：激进 `0～0.3s`、常规 `0.5～0.7s`、稳健 `1～1.2s`，
并展示“在范围内随机等待”的说明。默认采用常规。服务端把秒换算成整数毫秒保存，不能用二进制浮点直接落库。

并发校验同时满足：

1. `maxExecutingAccounts <= protocolCount * 15`；无协议时不能启用。
2. `maxUseAccounts > 0` 时，`maxExecutingAccounts <= maxUseAccounts`。
3. 内部固定 `accountSendConcurrency=20`，故 `20 * maxExecutingAccounts <= 10000`。
4. 周期模式 `maxUseAccounts >= 1`，且不得小于 `maxExecutingAccounts`。

`accountSendConcurrency=20` 和 `defaultSubTaskNum=50` 是竞品存在但页面不可编辑的运行常量，来自
`create-context` 并由 H3 服务端写入/使用，不加入 Save DTO，避免客户端篡改。

### 3.4 受众与发布

| 字段 | DTO | 行为 | 表 |
|---|---|---|---|
| 受众数据包 | `dataPackageId` | 候选只取 `forTask=true`；显示名称、未使用数、备注 | `hyperlink_task.data_package_id` |
| 任务开关 | `enabled` | “启用并入队”/“仅保存（不发送）”二选一 | `hyperlink_task_runtime.is_enabled` |

启用时数据包必选；仅保存时数据包可空。编辑/查看遇到已不可用的历史数据包，选择器仍插入“当前任务数据包/已不可用”
占位以完整回显，用户启用或再次保存前必须换成可用数据包。复制始终清空数据包，不继承号码归属。

即时且启用时匹配账号必须 ≥1；预发布和周期允许 0：前者挂起等待符合条件的新号，后者每轮重新筛选。
账号数加载中禁止提交，失败显示“重新试算”，不能把失败当成 0。

## 4. 账号范围筛选抽屉

点击“设置筛选条件/修改筛选条件”打开右侧子抽屉；主抽屉保留。子抽屉遮罩/ESC 不直接关闭，顶部实时显示
匹配账号数，标题为“账号范围筛选”，并显示“仅圈定有效账号”。底部按钮固定为“清空条件 / 取消 / 确定”。
主表单还保留独立“清空”快捷按钮和已选条件标签摘要。

### 4.1 可编辑条件全集

| 分组 | 竞品字段 | API 字段/取值 |
|---|---|---|
| 所属分组 | 多选、名称/标签搜索 | `groupIds[]` |
| 地理范围 | 大洲、国家包含、国家排除 | `continent`、`countryIso2s[]`、`excludeCountryIso2s[]`；包含与排除互斥 |
| 账号画像 | 手机号模糊、导入批次精确 | `phone`、`importBatchId` |
| 账号画像 | 在线状态 | `onlineStatus=ONLINE/OFFLINE/null` |
| 账号画像 | 轮换状态 | `rotationStatus=0/1/2/3/null`（未轮换/轮换中/已完成/失败） |
| 账号画像 | 账号类型 | `accountType=1/2/null`（个人/商业） |
| 账号画像 | 导入方式 | `importMode=six_segment/full_param/null` |
| 账号画像 | 类型 | `widType=web5/native6/null`（分身设备/主设备） |
| 账号画像 | 设备类型 | `platform` 的六个稳定枚举或 null |
| 账号画像 | 账号性质 | `source=0..4/null`（买量/自登/买入/转入/群扫码） |
| 账号画像 | 允许拉群 | `groupInviteAllowed=true/false/null` |
| 账号画像 | 双向好友数范围 | `friendCountMin/Max`，整数，0 视为不限 |
| 账号画像 | 存活天数范围 | `retentionDaysMin/Max`，0.1 天精度，0 视为不限 |
| 账号画像 | 注册天数范围 | `registerDaysMin/Max`，正整数；支持 90/180/365/730/1095 快捷值 |
| 协议与渠道 | 协议、渠道多选 | `protocolId`、`channelIds[]` |
| 入库时间 | 时间范围，今日/近7日/近30日快捷 | `createdAtFrom/createdAtTo`，左闭右开 |

竞品通用弹框还有导出/转移场景的禁言、超链寿命字段，但任务调用明确使用 `mode=task` 且
`lockStrangerMuted=true`，所以任务账号范围不显示这三个导出专属控件。后端始终附加：账号有效、未导出、
未被陌生人禁言。`groupInviteAllowed` 在任务场景没有锁定，必须保留为可编辑字段。

所有数组去重，国家码转大写，区间上限不得小于下限。确认时写回一份带 `filterSchemaVersion=1` 的完整对象；
取消不修改主表单；清空只清其他可编辑条件并恢复 `create-context.defaultAccountGroupIds` 对应的
`public + hyperlink` 两个系统业务组，不去除三个固定条件。用户随后主动移除这两个组时，`groupIds=[]` 才表示
全部符合固定条件的账号。前端不得写死分组 ID；若上下文未返回完整默认组，须显示依赖错误并禁止启用提交，不能
静默扩大范围。

`POST /api/hyperlink-tasks/account-match-count` 使用与最终任务完全相同的白名单和 SQL 生成器；任何字段变化经
250ms 防抖并取消旧请求，后到的旧响应不得覆盖新条件。响应同时刷新匹配数、协议数和最大执行账号上限。

## 5. 数据读取、回填与保存映射

### 5.1 详情响应

`GET /api/hyperlink-tasks/{id}` 返回公共身份和完整 Save 字段，并额外返回显示/并发控制字段：

```typescript
interface HyperlinkTaskDetail extends HyperlinkTaskIdentity {
  version: number;
  editable: boolean;
  taskName: string;
  messageContent: HyperlinkMessageContent;
  plannedEndAt: number | null;
  cycleIntervalMinutes: number;
  accountFilter: HyperlinkAccountFilter;
  messageIntervalMinSeconds: number;
  messageIntervalMaxSeconds: number;
  maxExecutingAccounts: number;
  maxUseAccounts: number;
  maxSendPerAccount: number;
  startMode: "now" | "scheduled";
  delayMinutes: number;
  dataPackageId: number | null;
  dataPackageName: string | null;
  dataPackageAvailable: boolean;
  createdAt: number;
  updatedAt: number;
}
```

读取一次 JOIN `hyperlink_task` 和 `hyperlink_task_content`；不查 recipient/round/stat。AssetId 由前端组合成
受保护内容 URL。按钮 JSON 由后端反序列化并校验，不能把数据库 JSON 字符串直接返回浏览器。

### 5.2 字段落表总览

| 表 | 本方案保存内容 |
|---|---|
| `hyperlink_task` | 名称、数据包、账号范围 JSON、三种模式参数、消息间隔、并发/限号、启动方式、启用状态、版本 |
| `hyperlink_task_content` | 消息类型、标题/正文/描述/原始链接、两张素材 ID、卡片正文、按钮 JSON、短链开关汇总 |

H2 的表单字段事实直接涉及 **2 张表**；但一项完整保存的任务还必须由 H3 同事务创建
`hyperlink_task_runtime`，所以用户已确认的初始任务骨架仍是 task/content/runtime 三张表。recipient、round、
account usage/stat、claim 和 billing 都不是表单字段事实源，由 H3 启用运行链按需创建或更新。数据包、模板、策略、
素材、账号和协议表仅作为选择数据源，不复制到任务表之外；任务只保存必须冻结的 ID、内容和筛选快照。

### 5.3 候选数据与接口

| UI 能力 | 接口 | 要求 |
|---|---|---|
| 模式、价格、余额、协议数、筛选候选、固定运行常量 | `GET /api/hyperlink-tasks/create-context` | 分组/国家/渠道/协议通过对应业务域 Service 聚合，不要求其他菜单权限 |
| 数据包候选 | `GET /api/data-packages?forTask=true&pageSize=200` | 服务端可搜索/分页；不要一次拉 10000 |
| 模板候选/详情 | `GET /api/hyperlink-templates/options`、`GET /api/hyperlink-templates/{id}` | 候选显示名称+消息类型 |
| 策略候选 | `GET /api/hyperlink-strategies/options` | 只返回启用策略及六项可导入参数 |
| 素材选择/上传 | `/api/resource-assets` | 复用素材菜单完整选择和上传能力 |
| 账号匹配试算 | `POST /api/hyperlink-tasks/account-match-count` | 与最终 SQL 同源 |

候选请求失败时对应控件显示失败和重试，不能把下拉伪装成“暂无数据”。详情请求失败不打开空表单。

### 5.4 复制语义

复制只复制任务配置：任务/内容字段、账号筛选和策略参数。必须清空 `dataPackageId`、`quoteToken`，设置
`version=null`、`sourceTaskId=源任务 ID`、名称追加“副本”。不复制 task ID、计费冻结、recipient、round、
claim、runtime、账号使用/统计、封号和点击数据。源任务已删除的素材不能静默丢失：显示“素材已不可用”，要求替换或清空。

## 6. 校验、提交和最后核对

客户端校验只是即时反馈，H3 必须逐项重做服务端校验。通用规则如下：

- 名称、消息类型、标题必填；按 §3.2 校验类型条件字段。
- URL 必须是绝对 `http/https`，禁止 `javascript:`、协议相对地址和控制字符。
- 图片素材同租户、未删除且满足格式/大小；AssetId 不接受客户端 URL 替代。
- 消息间隔、模式时间、账号区间和并发满足 §3/§4；所有数值拒绝 NaN、小数越界和负数。
- 启用任务要求数据包可用；即时任务还要求试算账号数 ≥1。
- PUT 必带当前 `version`；冲突显示“任务已被更新”，保留用户表单并提供重新加载，不盲目覆盖。

所有纯新建都打开 520px 最后核对弹框。`enabled=true` 时先通过校验和账号试算，再请求 H3 `POST /quote`；
`enabled=false` 时不报价，但仍展示任务配置核对。弹框展示
当前余额（账户+赠送）、服务端预计冻结、超级模式徽标、任务名、数据包名称/未使用数、消息类型、匹配账号数、
推广链接或按钮 URL、深度追踪状态及多国家价格明细。按钮为“返回修改 / 确认无误提交”；确认按钮倒计时 7 秒，
倒计时结束才能提交。报价过期则原弹框内刷新报价并重新倒计时。

`enabled=false` 新建不报价，但倒计时结束并确认后才保存。复制和编辑不弹竞品不存在的第二次 7 秒框，H3 在同一保存事务前重新校验
数据包、价码和余额；失败则保留抽屉并展示明确原因。成功处理 HTTP 200/202 回执：202 显示准备状态并按公共契约
轮询，READY 后关闭抽屉并刷新列表。

## 7. 前后端实现边界

前端固定在 H1 同一业务目录：

```text
src/api/hyperlink-task.ts
src/views/hyperlink/task/components/HyperlinkTaskEditorDrawer.vue
src/views/hyperlink/task/components/HyperlinkTaskPreview.vue
src/views/hyperlink/task/components/HyperlinkMessageContentForm.vue
src/views/hyperlink/task/components/HyperlinkButtonEditor.vue
src/views/hyperlink/task/components/HyperlinkSendStrategyForm.vue
src/views/hyperlink/task/components/HyperlinkAccountFilterDrawer.vue
src/views/hyperlink/task/components/HyperlinkAssetPicker.vue
src/views/hyperlink/task/components/HyperlinkTaskFinalReview.vue
src/views/hyperlink/task/composables/useHyperlinkTaskEditor.ts
src/views/hyperlink/task/domain/editor-rules.ts
```

使用 Element Plus 的 `ElDrawer/ElForm/ElSelect/ElInput/ElInputNumber/ElDatePicker/ElSlider/ElDialog/ElUpload`；
不要自绘抽屉、表单和上传器。账号筛选和消息内容拆组件，父抽屉只负责编排、模式和提交。

后端 H2 范围：

```text
com.armada.hyperlink.task
  controller/HyperlinkTaskController        # GET detail/context/account-match
  service/HyperlinkTaskQueryService
  service/HyperlinkAccountFilterService     # normalize/count，H3 复用
  converter/HyperlinkTaskConverter
  model/dto|vo|enums
```

H2 不自行实现 POST/PUT 事务；只与 H3 共用一个 `HyperlinkTaskSaveRequest` 和同一校验器。

## 8. 权限、安全与可观测性

- 新建/详情/编辑分别校验公共契约 `create/view/edit`；复制同时要求 view+create。
- 引用模板、策略、数据包、素材、分组、协议和渠道均按当前租户重新验权；不能信任前端候选。
- 查看模式前端只读不等于授权，详情接口本身必须校验任务租户。
- 日志记录 taskId/sourceTaskId、用户、模式、版本、是否启用和结果；不记录完整手机号筛选或消息正文。
- 匹配数请求记录耗时和归一化条件哈希，便于发现慢筛选；禁止打印 `accountFilter` 原文。
- 素材内容使用认证接口并返回正确 MIME、缓存和防嗅探头；不能暴露磁盘路径。

## 9. 测试与验收

### 9.1 自动化测试

- 纯函数：四种模式初始化、类型条件字段清理、模板/策略导入白名单、复制字段白名单、间隔与并发校验。
- 组件：三种可选消息及历史双图编辑（类型锁定、内容可改）/查看、实时预览、素材选择/上传/更换/清空、按钮添加/删除/追踪。
- 账号筛选：全部字段双向映射、包含/排除互斥、区间错误、取消不落值、清空恢复默认业务组、主动移除默认组表示不限、旧请求不覆盖新请求。
- API：详情租户隔离、不可编辑事实、失效引用回显、固定账号条件、未知筛选键拒绝、AssetId 越权拒绝。
- 提交：数据包/即时零账号阻断、Quote 过期、7 秒倒计时、HTTP 202 准备、版本冲突和重复点击幂等。
- 复制：不继承数据包及任何运行数据，`sourceTaskId` 正确，源任务越权/不存在失败。

### 9.2 竞品逐项验收清单

- [ ] 新建、编辑、查看、复制四个入口均打开同一抽屉且行为符合矩阵。
- [ ] 左侧实时 WhatsApp 预览、最终效果提示和右侧四段表单完整。
- [ ] 普通按钮、卡片按钮、单图文可新建；历史双图文可完整查看；编辑锁定类型但允许修改内容。
- [ ] 模板、策略、数据包、素材库/上传入口均可用，导入边界无串字段。
- [ ] 所有消息字段、限制、图片动作、按钮动作和深度追踪开关齐全。
- [ ] 即时/预发布/周期、结束时间/间隔、账号筛选、消息间隔、三类限号和启动方式齐全。
- [ ] 账号范围抽屉显示匹配数、完整任务场景字段以及清空/取消/确定；新建/清空恢复 `public + hyperlink` 默认组。
- [ ] 启用并入队与仅保存均可选，零账号规则和数据包规则正确。
- [ ] 关闭确认、提交错误保留表单、纯新建 7 秒最后核对均存在。
- [ ] 查看回填无缺字段；复制名称追加副本并强制重选数据包。

只要任一竞品可见字段、按钮、功能、弹框或联动没有对应实现，本方案验收即失败，不能以“后续优化”关闭。
