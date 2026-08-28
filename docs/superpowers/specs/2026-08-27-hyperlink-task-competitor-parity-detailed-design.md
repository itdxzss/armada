# 超链任务竞品对齐详细设计

- 日期：2026-08-27
- 状态：设计已冻结（2026-08-28 按查询/运行效率二次收口）
- 本轮菜单：超链营销 → 超链任务
- 竞品事实源：`hylbuiaxykfrontendsource/readable/assets/`
- 取证账：`docs/superpowers/reviews/2026-08-27-hylb-hyperlink-task-reverse-evidence.md`
- 上位设计：`docs/superpowers/specs/2026-08-27-hyperlink-task-strategy-asset-analysis-design.md`
- 公共合同：`docs/superpowers/specs/2026-08-28-hyperlink-task-shared-contract.md` v1.1；HTTP、JSON、API 枚举、指标、权限和跨方案边界以该合同为准

## 0. 结论与边界

本轮先详细设计「超链任务」。它处在业务中轴：上游消费超链模板、发送策略、图片素材、受众数据包和账号池，下游生产任务流水、账号统计、点击归因、访问趋势和封号原因。因此先做它，能把剩余三个菜单需要提供或消费的契约一次冻结。

本设计的对齐口径是：**竞品页面可见的字段、列表、功能、抽屉、弹框和导出一个不漏；竞品构建产物中能确认的隐藏请求契约也要承接。** Armada 暂无的数据或系统能力不通过隐藏控件规避，而是列成硬依赖并给出落地接口。

不机械复制以下竞品缺陷：

1. 账号筛选编辑回填漏掉 `rotation_status`、`source` 等字段；Armada 必须完整往返。
2. 筛选摘要只识别部分账号来源；Armada 展示全部五种来源。
3. CSV 把普通按钮、卡片按钮错误导成「单图文」；Armada 按真实消息类型导出。
4. 深度归因把 UV 行数标成「点击总数」；Armada 同时给出点击 UV 与访问次数 PV，避免混淆。
5. 名称搜索时把 `page_size` 临时改为 200 是竞品前端实现细节；Armada 保持服务端模糊查询和用户选定分页大小，页面能力等价。

本轮不包含四个菜单的代码实施。竞品构建产物无法证明数据库表数，也没有暴露领号、周期去重和账务
内部算法；这些不写成“竞品事实”。为避免开发期继续悬空，Armada 实现规则已经在
`docs/business/hyperlink-marketing-data-model.md` §4 冻结：首次启用通过 recipient_claim 分批冻结 recipient，
尚未开始的编辑只能按 claim owner 分批释放并重建快照；同一任务内一个收信号码最多发送一次，recipient
就是唯一发送事实，round 只负责按周期选择账号并分配剩余收信人。协议超时查询或重放同一 `command_id`，
任务按整份冻结受众一次预约余额，不为轮次重复计费。
以后即使拿到竞品真实 API 样例，也只做兼容性验证，不再让未证实的竞品内部实现反向破坏已冻结的数据边界。

## 1. 本次纠正的关键误判

| 原误判 | 竞品真实行为 | 本设计结论 |
|---|---|---|
| 超链任务支持四类按钮 | 通用按钮编辑器支持四类，但任务页传入 `locked-type="cta_url"` | 普通按钮、卡片按钮任务均只允许一个 CTA URL 按钮；不为任务补电话、复制、快捷回复 |
| 账号筛选隐藏好友数、注册天数、允许拉群 | 任务打开的是完整账号筛选抽屉，三项均可选 | 三项全部实现；缺数据采集能力时菜单不算完成 |
| 允许拉群被固定 | 任务只固定「未被陌生人禁言」，允许拉群仍可筛选 | `groupInviteAllowed` 是可选条件 |
| 页面有单账号并发输入框 | 页面只有「最大执行账号数」；`account_send_concurrency=20` 是隐藏契约 | 前端不展示单账号并发；服务端默认 20 并参与总并发校验 |
| 列表每分钟自动刷新 | 页面只有手动刷新；文案说明后端数据约每分钟同步 | 不加前端定时器，保留手动刷新和同步时效提示 |
| 任务可删除 | 竞品任务 API 和行操作均没有删除 | 不提供任务删除按钮、接口、权限 |
| 可省略余额/单价/冻结金额 | 新建任务有 7 秒「最后核对」弹框并真实展示余额和预计冻结 | 计费报价、冻结、结算/释放是完整上线的硬依赖 |
| 编辑条件由启用状态决定 | 竞品按 `task_status == 0` 决定编辑/查看 | 未开始可编辑，开始后只读；消息类型在编辑态仍不可改 |

## 2. 用户流程和跨菜单关系

```text
超链模板 ──一次性带入消息内容──┐
图片素材 ──选择预览图/正文图───┤
超链策略 ──一次性带入发送参数──┤
超链数据包 ──提供本代未使用号码─┼─> 新建/复制任务
账号池/协议 ──筛选、试算、并发上限┤        │
价格/钱包 ──报价、冻结余额──────┘        │
                                           v
                           任务调度 → 发信 → 回执/封号 → 短链访问
                                           │
                     ┌─────────────────────┼─────────────────────┐
                     v                     v                     v
                 任务详情              访问趋势             超链市场分析
```

模板和策略都是「一次性带入」，不是运行时绑定。模板后续修改不反写任务，策略后续修改也不改变已创建任务。任务保存消息快照、账号筛选快照、数据包代次快照、价格报价快照，保证历史可复盘。

## 3. 枚举、状态与可操作矩阵

### 3.1 消息类型

| 值 | 名称 | 新建可选 | 历史展示 | 物理结构 |
|---|---|---:|---:|---|
| 1 | 单图文 | 是 | 是 | 链接预览卡片，可选预览图 |
| 2 | 双图文 | 否 | 是 | 只兼容竞品历史数据 |
| 3 | 普通按钮 | 是 | 是 | 正文图 + 标题/底部文字 + 1 个 CTA URL |
| 4 | 卡片按钮 | 是 | 是 | 标题/副标题消息 + 卡片图/卡片文字 + 1 个 CTA URL |

### 3.2 任务模式

| API 值 | 竞品值 | 页面名称 | 已确证语义 |
|---|---:|---|---|
| `instant` | 1 | 即时群发 | 按计划快速发完整个数据包，速度最快 |
| `rolling` | 2 | 预发布 | 到计划结束时间或数据包发完即结束，先到为准；期间符合筛选条件的新账号自动加入 |
| `cycle` | 3 | 周期循环 | 按间隔重复跑同一任务，用于比较不同时段封控；用户手动停止 |

上表最后一行是竞品页面文案，不足以证明同一收信人会被重复发送。Armada 的冻结执行口径是：每个周期重新
选择发信账号并继续分配尚未发送的 recipient；数据包内 recipient 全部进入终态后自动完成，用户可在此前手动停止。

### 3.3 双状态

| 字段 | 值 | 展示 |
|---|---:|---|
| `enabled` | 0 | 已停用，仅保存不发送 |
| `enabled` | 1 | 启用，按启动方式入队 |
| `runStatus` | 0 | 未开始 |
| `runStatus` | 1 | 进行中 |
| `runStatus` | 2 | 已完成 |
| `runStatus` | 3 | 已暂停 |
| `runStatus` | 4 | 已停止 |

展示优先级：`enabled=false` 时一律显示「已停用」，否则显示运行状态。

| 当前运行状态 | 行操作 | 说明 |
|---|---|---|
| 未开始 | 启动、编辑、详情、复制 | 编辑时消息类型不可改；启动需确认 |
| 进行中 | 暂停、停止、查看、详情、复制 | 暂停和停止均二次确认 |
| 已暂停 | 继续、停止、查看、详情、复制 | 继续从已有进度恢复 |
| 已完成 | 查看、详情、复制 | 终态不可重启 |
| 已停止 | 查看、详情、复制 | 终态不可重启 |

任务没有删除功能。Action 必须做数据库条件更新和幂等校验，禁止仅依赖前端状态。

行操作确认文案与竞品一致：

| 操作 | 确认内容 |
|---|---|
| 启动 | `确认启动任务「{任务名}」？` |
| 暂停 | `暂停后可在「已暂停」状态下恢复执行` |
| 恢复 | `恢复后任务将继续按原策略发送` |
| 停止 | `停止后任务将被终止，且无法恢复` |

## 4. 任务列表页

### 4.1 顶部信息和筛选

页面标题区展示当前运行模式徽标和单价：普通模式读取 `hyperlink_task` 价码，超级并发模式读取 `concurrent_hyperlink_task` 价码。超级模式来源于全局配置 `protocol_use_concurrency`。

筛选项：

| 字段 | 控件 | 行为 |
|---|---|---|
| 任务名称 | 可清空输入框 | 模糊匹配 |
| 任务状态 | 下拉 | 未开始/进行中/已完成/已暂停/已停止；已停用不作为独立查询值 |
| 任务类型 | 下拉 | 全部/即时群发/预发布/周期循环 |
| 目标国家 | 国家下拉 | 来自任务/数据包国家选项 |
| 创建时间 | 日期范围 | 起止时间 |

按钮：查询、重置、新建任务、列设置、导出、刷新。分页大小为 10/20/50/100/200，默认 20。刷新是手动行为；页面提示「聚合数据约每分钟同步一次」。

### 4.2 当前页汇总卡

汇总范围与竞品一致，取当前已加载页，不伪装成全库统计：

1. 任务数。
2. 总发送数。
3. 单钩数。
4. 双钩数。
5. 点击 UV。
6. 点击率。

点击率只统计当前页 `shortLinkEnabled=true` 的任务，分子汇总这些任务的点击 UV，分母汇总这些任务的
成功发送数；卡片 tooltip 明示口径。

### 4.3 表格列

默认横向滚动，支持用户列设置。列顺序和信息必须完整：

1. ID。
2. 任务：任务名、消息类型、任务模式、推广链接。
3. 数据包：名称、受众数量。
4. 账号筛选：最多展示 3 个摘要标签，余下显示 `+N`。
5. 目标国家。
6. 状态：停用优先，否则运行状态。
7. 账号统计：已用账号、封号账号、账号平均发送数。
8. 发送进度：单钩、失败、总量、未注册数量、进度条。
9. 双钩：数量、双钩率、预计落地率；竞品公式固定为 `min(99%, 双钩率 + 20 个百分点)`。
10. 点击：UV、点击率；点击率可点开访问趋势。
11. 实际并发。
12. 执行时长。
13. 计划结束时间；周期任务显示执行间隔。
14. 创建时间。
15. 操作。

### 4.4 导出

列表导出当前筛选命中的任务，至少包含：ID、任务名、推广链接、消息类型、目标国家、数据包、数据包号码数、账号筛选、状态、双钩数/率、点击 UV/率、单钩数、失败数、未注册数、总数、已用账号、封号账号、账号平均发送、实际并发、执行时长、任务模式、计划结束、执行间隔、创建时间。

文件名包含导出时间。消息类型按 1/2/3/4 正确映射，不复制竞品 CSV 的错误分支。

## 5. 新建、编辑、查看和复制抽屉

### 5.1 容器和模式差异

- 右侧抽屉，自适应宽度 820～1240px；点击遮罩和 ESC 不关闭。
- 左侧为实时 WhatsApp 消息预览，右侧为四段表单。
- 有未保存内容时关闭，标题「确认关闭？」；编辑提示未保存修改将丢失，新建/复制提示已填写内容将丢失，按钮为「关闭 / 继续编辑」。
- 查看模式整页只读，仅有关闭按钮。
- 编辑模式仅 `runStatus=0` 可进入，消息类型禁用。
- 复制以源任务填充，任务名追加「副本」，清空数据包；仍可重新引用模板和策略。
- 纯新建提交前显示 7 秒最后核对弹框；竞品复制/编辑直接提交，Armada 保持这一交互。

默认值：普通按钮、即时模式、立即执行、启用、最大执行账号 10、最大使用账号 0、每账号最大发送 0、周期间隔 60 分钟、消息间隔 0.5～0.7 秒；服务端隐藏默认 `accountSendConcurrency=20`、`defaultSubTaskNum=50`。

竞品新建时会从内置样例池随机填充任务名、标题、正文、描述、推广链接和 CTA URL，并给预览图/正文图放随机 picsum 图片。Armada 保留「随机样例开箱即见预览」的体验，但样例图片改为随前端发布的本地静态资源，避免向第三方图片站泄露访问和产生不稳定依赖；这些样例与普通表单值一样可编辑、可提交。

### 5.2 第一段：基础信息

| 字段 | 规则 |
|---|---|
| 消息类型 | 新建/复制可选普通按钮、卡片按钮、单图文；编辑禁用；双图文只读兼容。选择单图文时提示「可能在大部分手机型号上无法正常显示，建议优先选择其他消息类型」 |
| 任务名称 | 必填，最多 128 字；可从当前数据包名/全部数据包名获得自动完成建议 |

### 5.3 第二段：消息内容

顶部提供「引用模板」，仅列启用模板，最多一次拉取 10000 条。选中后加载模板详情并一次性覆盖消息内容，不影响任务名、策略、数据包和启动方式。

| 字段 | 消息类型 | 必填 | 规则 |
|---|---|---:|---|
| 链接预览图 | 单/双图文 | 否 | 从素材库选；JPG/JPEG ≤500KB，建议 16:9 |
| 标题 | 全部 | 是 | 最多 1024 字；普通按钮用多行输入，其他类型单行；任务实施时同步扩容现有模板列和校验器 |
| 链接描述 | 单/双图文 | 是 | 链接预览描述 |
| 推广链接 | 单/双图文 | 是 | 最多 2048 字 |
| 正文图片 | 双图文/普通按钮/卡片按钮 | 否 | 从素材库选；JPG/JPEG ≤500KB |
| 正文/底部文字/副标题 | 单/双图文为正文，普通按钮为底部文字，卡片按钮为副标题 | 单/双图文必填，按钮类型可空 | 单/双图文最多 2000；按钮类型最多 200 |
| 卡片正文 | 卡片按钮 | 是 | 最多 500 字 |
| 按钮 | 普通按钮/卡片按钮 | 是 | 恰好 1 个，类型锁定 CTA URL；按钮文案最多 30，URL 必填 |
| 深度追踪 | CTA URL 按钮 | 否 | 按钮级开关；开启后发送短链并记录访问归因 |

通用模板模型可保留其他按钮类型用于历史兼容或其他业务，但任务 UI、任务 DTO 和任务校验器只接收 CTA URL。模板页用于超链任务的按钮同样锁 CTA URL，并隐藏任务运行时才填写的推广 URL 时，任务引用后必须补齐 URL。

### 5.4 实时 WhatsApp 预览

预览组件接收消息类型、标题、正文、链接描述、推广链接、两类图片、按钮、卡片正文。必须分别渲染：

- 单/双图文的链接预览卡。
- 普通按钮的图片、标题、底部文字和按钮。
- 卡片按钮的标题/副标题消息气泡，以及图片、卡片正文和按钮卡。

预览包含 WhatsApp 会话壳、当前时间和消息状态勾，只用于视觉反馈，不参与提交。图片选择与文字编辑要实时反映。

### 5.5 第三段：发送策略

顶部提供「引用策略」，只列启用策略。一次性带入：任务模式、账号筛选、最大执行账号数、最大使用账号数、每账号最大发送数，以及周期模式下的执行间隔；不改变消息、数据包、启动方式和消息间隔。

| 字段 | 显隐/规则 |
|---|---|
| 任务模式 | 即时/预发布/周期三张卡 |
| 计划结束时间 | 仅预发布；必填，晚于当前至少 1 分钟；切换时默认当天结束 |
| 执行间隔 | 仅周期；必填，整数 ≥1，默认 60，步长 10，单位分钟/轮 |
| 账号范围 | 打开完整账号筛选抽屉；展示条件摘要和实时可用数 |
| 消息间隔 | 0～10 秒，步长 0.1；最小值≤最大值；快捷预设 0～0.3、0.5～0.7、1.0～1.2 |
| 最大执行账号数 | 整数 ≥1，默认 10 |
| 最大使用账号数 | 即时/预发布可为 0 表示不限 |
| 每轮最大账号数 | 周期必填且 ≥1；同一个字段 `maxUseAccount` |
| 每账号最大发送数 | 整数 ≥0；0 表示直到封号/失效 |
| 启动方式 | 立即执行/延后执行 |
| 延迟分钟 | 延后执行且启用时必填，整数 ≥1；显示预计本地开始时间 |

并发硬校验：

```text
maxExecutingAccounts <= protocolCount * 15
maxUseAccount > 0 时，maxExecutingAccounts <= maxUseAccount
maxExecutingAccounts * accountSendConcurrency <= 10000
accountSendConcurrency 服务端固定默认 20，允许配置范围 1..100，但本页不展示
```

即时任务可用账号为 0 时禁止启用；预发布和周期可为 0，前者等待新账号进入，后者到下一轮重新试算。

### 5.6 第四段：受众与发布

| 字段 | 规则 |
|---|---|
| 受众数据包 | 调用 `forTask=true` 的数据包选项；展示名称、当前代未使用数、备注；不可用的历史值在编辑/查看时仍可回显 |
| 任务状态 | 「启用并按启动方式执行」或「仅保存不发送」两张卡 |

启用时数据包必填；仅保存时允许暂不选。启用并立即执行时提交后入队，延后执行时按 `delayMinutes` 入队。

### 5.7 提交归一化

前端发送 camelCase，服务端保存规范化快照：

- 任务名、标题、正文去除首尾空白。
- 账号筛选始终附加 `accountStatus=NORMAL`、`exported=false`、`strangerMuted=false`。
- 单/双图文提交链接描述和推广链接；按钮消息提交按钮 JSON。
- 卡片按钮额外提交卡片正文。
- 非延后启动将 `delayMinutes` 置 0。
- 非预发布清空计划结束；非周期清空执行间隔。
- 图片使用素材稳定 ID；素材先由素材接口上传，任务保存只提交素材 ID，不提供 multipart 兼容入口。
- `accountSendConcurrency=20`、`defaultSubTaskNum=50` 由服务端默认，禁止客户端任意覆盖。

## 6. 账号筛选抽屉

### 6.1 交互

- 右侧抽屉，自适应宽度 520～960px；遮罩和 ESC 不关闭。
- 标题「账号范围」，展示「仅有效账号」。
- 底部：清空、取消、确定。
- 顶部实时展示匹配数量；字段变化 250ms 防抖后调用试算接口。
- 即时任务匹配数为 0 时不可确认；预发布/周期允许 0。
- 默认业务组包含公共组和超链业务组。
- 国家包含与国家排除互斥，同一国家不能同时出现。

### 6.2 全量筛选字段

| 分组 | 字段 | 取值/控件 | Armada 数据来源或改造 |
|---|---|---|---|
| 业务组 | 账号业务组 | 名称/标签多选 | 账号组关系；保留公共组、超链组 code |
| 地域 | 大洲 | 多选 | 国家元数据 |
| 地域 | 国家包含 | 多选 | `account.ws_phone` 区号映射到国家 |
| 地域 | 国家排除 | 多选 | `account.ws_phone` 区号映射到国家 |
| 基础 | 手机号 | 模糊 | `account.ws_phone` |
| 基础 | 导入批次号 | 精确 | 直接使用 `account_import_batch.id` 作为稳定导入编号，经 `account_import_detail.batch_id` 圈号；不新增第二个 `import_no` |
| 状态 | 在线状态 | 全部/在线/离线 | `account_state.login_state` |
| 状态 | 轮号状态 | 未轮号/轮号中/成功/失败 | 新增账号营销画像字段 |
| 属性 | 账号类型 | 个人/商业 | `account.account_type` |
| 属性 | 导入方式 | 六段参数/全参数 | `account_credential.cred_format` |
| 属性 | 类型 | 分身设备 `web5`/主设备 `native6` | 由 `account.protocol_id` 映射 `ProtocolBackend`，禁止前端猜 `protocolId` |
| 属性 | 设备类型 | 单选：安卓个人、安卓商业（主/分身）、苹果个人、苹果商业（主/分身） | 由 `account.device_os + account.account_type + ProtocolBackend` 组合成竞品六值 `platform`，不另落列 |
| 来源 | 账号性质 | 买量/自登/买入/转入/群扫码 | `account_profile.marketing_source` 五类运营来源，不能拿现有三值硬凑 |
| 能力 | 允许拉群 | 全部/允许/不允许 | 新增账号营销画像字段 |
| 画像 | 好友数 | 最小/最大 | 新增账号营销画像字段；协议侧定期同步 |
| 画像 | 留存天数 | 最小/最大，允许小数 | 从入库/运营起始时间计算或保存快照，口径统一为天 |
| 画像 | 注册天数 | 最小/最大；90/180/365/730/1095 快捷值或正整数 | 新增注册时间/注册天数快照 |
| 路由 | 协议 | 单选 | `account.protocol_id` |
| 路由 | 渠道 | 多选 | `promotion_channel_id` / 渠道关系 |
| 时间 | 创建时间 | 日期时间范围；今天/近7天/近30天 | `account.created_at` |

固定条件不显示为可编辑控件：账号状态正常、未导出、未被陌生人禁言。任务不锁「允许拉群」。

按既有数据模型新增一对一 `account_profile`，承载 `rotationStatus`、`groupInviteAllowed`、`friendCount`、
`registeredAt` 和五类 `marketingSource`。每个异步画像字段有自己的 `*SyncedAt/*UpdatedAt`，不用一个
`syncedAt` 掩盖字段间的新鲜度差异。账号上线后仅对空值或超过 24 小时的画像异步刷新；试算和圈号不在
请求内主动探测协议。共享 `account` 表不因单一营销业务无限加列。

账号筛选 JSON 使用白名单 DTO，未知键拒绝或忽略并记录告警。创建、复制、编辑、详情、策略引用全过程必须无损往返；端到端测试覆盖每个字段。

## 7. 素材库选择和上传弹框

任务内图片字段统一复用素材能力：

### 7.1 选择弹框

- 居中弹框，宽 960px，遮罩不可关闭，标题「从素材库选择」。
- 名称搜索 300ms 防抖；标签任意匹配；默认 12 条/页。
- 网格卡显示图片、名称、标签、尺寸、文件大小。
- 单选，底部显示总数、分页和「使用所选素材」。
- 提供「上传素材」入口，上传成功后刷新并可选中。

### 7.2 上传弹框

- 宽 640px；上传期间禁止关闭；单次最多 100 张。
- 仅 JPG/JPEG，每张不超过 500KB；可为本批文件设置统一标签。
- 支持选择/拖拽、文件列表、进度、成功/失败状态和移除。
- 逐文件顺序上传，单个失败不回滚已经成功的文件；保留失败项供重试。

这两个弹框是图片素材菜单和模板菜单的共享组件。任务只消费稳定素材 ID，不复制图片字节。

## 8. 新建任务「最后核对」弹框

纯新建提交时弹出，宽 520px，遮罩和 ESC 不关闭，确认按钮倒计时 7 秒后可点。展示内容：

1. 当前可用余额：账户余额 + 赠送余额。
2. 当前运行模式和价码；超级并发模式显示徽标。
3. 预计冻结金额：数据包当前可发送号码数 × 对应单价。
4. 任务名称。
5. 数据包名称和剩余号码数。
6. 当前筛选匹配的可用账号数。
7. 推广链接；按钮消息同时显示按钮链接和深度追踪状态。
8. 启用会建立数据包、内容和计费快照；尚未开始时编辑会整笔释放并重建，进入运行后不可变的提示。
9. 返回修改、确认创建；创建成功后刷新任务列表和当前余额。

### 8.1 计费契约

Armada 当前没有现成钱包/冻结体系，因此这不是可删 UI，而是任务上线硬依赖。任务领域只依赖 `HyperlinkBillingGateway`，不把钱包实现耦合进调度器。

```java
interface HyperlinkBillingGateway {
    TaskPricingContext getPricingContext(long tenantId);
    TaskQuote quote(long tenantId, TaskBillingBasis basis);
    BillingReservation reserveTask(long tenantId, long taskId, String quoteToken);
    BillingReservation queryTaskReservation(long tenantId, long taskId);
    BillingReservation adjustTask(long taskId, String quoteToken, int reservationVersion);
    void settleTask(long taskId, long billableRecipientCount);
    void releaseTaskRemainder(long taskId, String reason);
}
```

`TaskBillingBasis` 由服务端根据任务模式、运行模式和按国家冻结的 recipient 数量构造，前端不能提交金额。
`hyperlink_billing_reservation` 与任务 1:1，保存整份冻结受众的报价、预计冻结、外部预约单号和本地状态；
同一行另用 `pendingOperation`、`operationIdempotencyKey`、`nextRetryAt` 明确当前待恢复的是冻结、调整、
结算还是释放，不能只靠含义不明确的 `PROCESSING` 状态猜操作类型；
周期后续轮次只是继续分配尚未发送的 recipient，不重新报价或冻结。真实钱包余额和逐笔账务仍留在外部提供方。

报价返回 `priceCode`、普通/超级模式、单价、号码数、预计冻结、可用余额、`quoteToken`、过期时间。
纯新建启用任务先用短事务锁定数据包代次、写不可见任务头和 recipient_claim，再按固定批次领取 phone、
写 recipient 并提交游标；禁止在一个事务中锁 50 万号码。实际领取数与 quote 一致后写 `PROCESSING` 预约，
同时持久化 `pendingOperation=RESERVE`、幂等键和恢复时间，再以 `taskId` 调 Gateway 幂等冻结；成功后创建首轮
round、切为 `RESERVED` 并清空待操作字段后入队；明确失败则按 claim owner
分批释放 CLAIMED，删除尚无 `command_id` 的 recipient 和未完成任务头。进程在外部冻结成功后宕机时，恢复任务用同一任务键
查询/重放 Gateway，再完成本地提交或补偿释放，禁止把远程调用伪装成 MySQL 原子事务。余额不足返回明确
错误，列表不暴露处理中半成品。复制和编辑按竞品不再弹最后核对，但服务端仍必须重新报价：复制创建新预约，
编辑未开始任务原子调整原预约，余额不足则整笔失败。完成时按可计费号码结算，停止/失败释放剩余冻结；
预约创建以 `taskId` 幂等；调整、结算、释放以外部预约号 + 本地 reservation version 生成操作幂等键，且每次
外部调用都必须先保存对应待操作字段，最终本地状态提交后再清空。

若钱包由外部系统提供，Gateway 用防腐层接入，钱包余额和逐笔流水仍由提供方持有；Armada 只落
`hyperlink_billing_reservation` 每任务一行，保存任务报价、冻结/结算/释放状态，不复制第二套总账。周期任务
零可用账号轮次只记录 skipped，不新增资金预约；任务初次预约余额不足则准备失败，不进入派发。本菜单验收
不接受恒定 0 元或伪造余额的占位实现。

## 9. 任务详情抽屉

抽屉宽 1300px，可点击遮罩关闭。顶部展示 6 张摘要卡：单钩总数、双钩总数/率、失败数/未注册数、已用账号、封号账号、账号平均发送；并显示状态图例。

### 9.1 收信人流水统计

筛选：收信号码模糊、收信国家、发信国家、失败原因精确（预设含「号码未注册」）。按钮：查询、重置、导出。

列：收信号码/国家、发信账号手机号/ID/国家、发送状态/失败原因。状态优先级：有双钩时间显示双钩，有单钩时间显示单钩，有失败时间显示失败，否则显示 pending/sending/sent/delivered/read/success/failed，并展示对应时间和失败原因。

停止任务时，尚未提交协议且没有 `commandId` 的待发 recipient 不设“跳过”状态，统一保存为
`sendStatus=FAILED`、`failCode=TASK_STOPPED`、`failReason=任务已停止` 并记录失败时间；这样明细、失败总数和
未分配账号桶与竞品“失败 / 原因：任务已停止”的展示一致。

详情抽屉顶部摘要统一读取 `GET /api/hyperlink-tasks/{id}/summary`；收信人分页只返回 `PageResult`，不再在
每个 Tab 响应里复制一套统计字段。摘要 DTO、字段名和公式以公共合同 §4.4/§5 为准。

### 9.2 发信账号维度统计

筛选：发送时间范围、发信国家、成功数最小/最大。默认按成功数降序；成功、双钩、失败列可排序。按钮：查询、重置、导出。

列：发信账号手机号/ID/未分配标识、国家、个人/商业、留存天数（1 位小数）、成功数、双钩数、失败数、最后发送时间。

无发送时间范围时从 `hyperlink_task_account_stat` 按指标索引直接排序分页，再 LEFT JOIN
`hyperlink_task_account_usage` 取得号码、国家、类型和入库时间这组唯一展示快照；有范围时按 recipient 的任务×
`submitted_at` 索引精确聚合，同样 JOIN account_usage 取展示快照。当前单任务最多 50 万 recipient，不为
只优化完整小时另建投影；大范围导出走异步任务。本 Tab 按 recipient 冻结的实际发信账号计数，一个 recipient
不跨账号重试，因此账号行合计应与任务头对应指标一致。任务停止前尚未分配账号的终态失败统一进入
account_stat 的 `account_id=NULL` 汇总桶，页面显示“未分配”。

本 Tab 的任务域表固定为两张：`hyperlink_task_recipient` 是唯一发送事实，`hyperlink_task_account_stat`
是无时间范围累计查询投影；不再增加账号小时表。

### 9.3 深度归因

筛选：收信号码、发信号码、排序；默认访问次数降序。按钮：查询、重置、导出。

列：收信号码、发信号码、访问次数、国家、设备、操作系统、浏览器、语言、IP、首次访问、最后访问；IP tooltip/详情附带 user-agent。顶部同时显示点击 UV、访问 PV 和点击率，避免竞品标签歧义。

竞品前端只能证明“一位收件人一行”，不能证明多次访问时单值字段取首访还是末访。Armada 冻结为首触口径：
发信号码取 recipient 冻结的 `sender_phone_snapshot`，国家/设备/系统/浏览器/语言/IP/UA 取该收件人的第一条
访问；访问次数和最后访问时间继续累计全部点击。同一任务内该号码只有一个短码和一个实际发信账号。上述
首触环境、累计次数与首末时间都直接保存在 recipient，不建逐次点击流水。

为满足竞品功能，recipient 必须保存首次 IP 与 user-agent。实施时增加
`tenant:hyperlink_task:attribution_sensitive` 权限、导出审计和租户隔离；首触敏感环境保留 90 天，页面和
导出是否脱敏由权限决定。超过 90 天后仍展示 UV/PV、首次/最近时间和发信账号，但 IP、UA、浏览器、
系统、设备和访问国家显示“首触环境已过保留期”，不得伪造为空字符串或继续承诺可导出。

### 9.4 访问趋势

时间窗：12/24/36/48/72 小时，默认 24；粒度：30 分钟/1 小时/2 小时，默认 30 分钟。提供图表/表格切换、刷新、导出。

摘要：UV 总数、点击率、任务开始时间、首次访问时间、峰值时间/新增 UV、PV 总数、人均 PV。图表序列：新增 UV、累计点击率，辅助展示 PV；另有趋势洞察和访问峰值列表。表格列：桶开始、桶结束、新增 UV、累计 UV、累计点击率、PV。

时间范围以第一个 UV 的首次访问时间为起点，向后取所选 12~72 小时，不是从当前时刻向前滚动；尚无访问时
展示空态。竞品 tooltip 还明确规定桶内 PV “按首次访问所在时间段近似归集”：同一收件人的后续访问仍累加
到其首访桶，不按后续点击的真实时间迁移。列表页点击率可直接打开该 Tab。
后端直接读取 `hyperlink_task_recipient`：以任务第一个 UV 为窗口起点，按 `first_visit_at` 分成 30 分钟、
1 小时或 2 小时桶，`COUNT(*)` 得到新增 UV，`SUM(click_count)` 得到竞品近似口径的辅助 PV；应用层补空桶、
计算累计 UV 和点击率。查询命中 task+first_visit_at 索引，只扫描已访问 recipient；当前业务单任务发送量
不超过 10 万，因此不额外维护 30 分钟聚合表。

### 9.5 封号原因分布

展示封号账号总数，以及按占比降序的原因列表。每项包含原因说明、数量、占比和进度条；无数据时显示空态。未知错误保留后端原始说明，不硬塞入「其他」。

已知原因文案包括：中途禁言马上封号、中途强制被掐掉封号、从主设备登录出被强制下线、主设备直接掉线/封号、未知原因；后端错误码映射集中维护，不在 Vue 组件散落判断。

## 10. API 设计

Armada 延续 `/api`、camelCase、`ApiResponse<T>` / `PageResult<T>`；下列端点覆盖竞品能力，不新增任务删除。

### 10.1 页面和任务生命周期

```text
GET  /api/hyperlink-tasks
GET  /api/hyperlink-tasks/{id}
POST /api/hyperlink-tasks                         application/json
PUT  /api/hyperlink-tasks/{id}
GET  /api/hyperlink-tasks/{id}/provision-status
POST /api/hyperlink-tasks/{id}/action             body: { action, version, quoteToken }
GET  /api/hyperlink-tasks/export

GET  /api/hyperlink-tasks/create-context          模式、价码、余额、协议数
POST /api/hyperlink-tasks/quote                    CREATE/START 报价、数据包人数、预计冻结、quoteToken
POST /api/hyperlink-tasks/account-match-count      完整账号筛选试算
```

列表查询：`page`、`pageSize`、`taskName`、`runStatus`、`taskMode`、`countryIso2`、`createdAtStart`、`createdAtEnd`。不增加竞品没有的 `enabled` 筛选。

### 10.2 任务详情和导出

```text
GET /api/hyperlink-tasks/{id}/summary
GET /api/hyperlink-tasks/{id}/recipients
POST /api/hyperlink-tasks/{id}/recipients/export

GET /api/hyperlink-tasks/{id}/account-stats
POST /api/hyperlink-tasks/{id}/account-stats/export

GET /api/hyperlink-tasks/{id}/clicks
POST /api/hyperlink-tasks/{id}/click-attribution/export

GET /api/hyperlink-tasks/{id}/visit-trend
POST /api/hyperlink-tasks/{id}/visit-trend/export

GET /api/hyperlink-tasks/{id}/ban-stats

GET /api/hyperlink-task-exports/{jobId}
GET /api/hyperlink-task-exports/{jobId}/download
```

四类详情导出沿用当前 Tab 筛选和排序并统一创建异步作业；状态、快照时间、下载与过期规则见公共合同 §2.3。
所有查询先校验任务属于当前租户。

### 10.3 依赖选项

```text
GET /api/hyperlink-templates/options?enabled=true
GET /api/hyperlink-templates/{id}
GET /api/hyperlink-strategies/options?enabled=true
GET /api/data-packages/options?forTask=true
GET /api/resource-assets
GET /api/resource-assets/tags
POST /api/resource-assets
GET /api/account-business-groups/options
GET /api/channels/options
GET /api/protocols/summary
```

## 11. 核心请求与响应契约

`HyperlinkTaskSaveRequest` 的完整公共字段以公共合同 §4.1 为准，核心字段包括：

```text
version, sourceTaskId, taskName, messageType,
messageContent {
  linkPreviewAssetId, title, linkDescription, promotionLink,
  bodyMainAssetId, content, cardText,
  buttons[{ type=CTA_URL, displayText, url, useShortLink }]
},
taskMode, plannedEndAt, cycleIntervalMinutes,
accountFilter, messageIntervalMinSeconds, messageIntervalMaxSeconds,
maxExecutingAccounts, maxUseAccounts, maxSendPerAccount,
startMode, delayMinutes,
dataPackageId, enabled,
quoteToken  // 纯新建且启用时必填；sourceTaskId 标识复制，复制/编辑由服务端重新报价
```

`HyperlinkTaskListItemVO` 除基础字段外，要直接返回列表所需聚合，避免前端 N+1：数据包名称/人数、目标国家、账号筛选摘要、单钩/双钩/失败/未注册/总数、点击 UV/PV/率、已用/封号账号、平均发送、实际并发、执行时长、计划结束、周期间隔、创建时间和 `metricsUpdatedAt`。执行时长由 runtime 的累计秒数与当前运行段起点计算；`metricsUpdatedAt` 只表示发送指标投影新鲜度，点击原子更新不得刷新它。

数字字段用整数或 `BigDecimal`，不返回格式化字符串；时间统一 epoch 毫秒，前端按系统时区显示。

## 12. 数据与服务边界

### 12.1 主要事实表

| 事实 | 最终承载 | 必要约束 |
|---|---|---|
| 任务配置 | `hyperlink_task` | tenant + id；数据包代次/国家、筛选和来源快照；人数取 runtime.`recipient_total` |
| 双状态和列表计数 | `hyperlink_task_runtime` | task 1:1；累计运行秒数+当前运行段起点；provision 1/3 不进租户列表/调度，ACK 不争抢配置行，分钟级投影 |
| 消息快照 | `hyperlink_task_content` | task 1:1；标题 1024、CTA URL 校验、素材稳定 ID 反查索引；与 task 的短链派生开关同事务保存 |
| 账号筛选快照 | `hyperlink_task.account_filter` JSON | 白名单版本号 `filterSchemaVersion` |
| 唯一收信人发送事实 | `hyperlink_task_recipient` | task+号码唯一；受众/实际账号/唯一 command/结果/短码和点击投影同一行 |
| 轮次执行 | `hyperlink_task_round` | task+roundNo 唯一；业务 due 时间与 worker 租约分离，支撑选号、剩余 recipient 分配和崩溃接管 |
| 任务账号执行用量 | `hyperlink_task_account_usage` | task+account 唯一；同步占成功槽/在途并发、跨轮成功上限及首次封号/失效事实 |
| 轮次账号分配 | `hyperlink_task_round_account` | round+account 唯一；固化每轮选号集合与稳定顺序 |
| 受众领取作业 | `hyperlink_task_recipient_claim` | task 1:1；代次操作互斥只覆盖准备/领取/释放/恢复，OWNED 后靠号码 owner 隔离归属 |
| 任务账号累计投影 | `hyperlink_task_account_stat` | task+account桶唯一（含未分配桶）；只存指标，展示快照 JOIN account_usage，可从事实重建 |
| 计费预约 | `hyperlink_billing_reservation` | task 1:1；待操作类型、幂等键和重试时间使冻结、调整、结算、释放可独立恢复 |
| 账号营销画像 | `account_profile` | account 唯一；各画像字段独立同步时间 |

任务域按工作负载固定为上述 10 张表，另依赖 1 张 account 共享表。表数不是目标：recipient_claim 解决
50 万号码的批量冻结/释放，round、account_usage、round_account 解决选号上限、调度恢复与同步限额，账号累计
投影解决默认排序分页。访问趋势按 recipient 首访索引直接聚合。recipient 已经完整表达“一位收信人一次发送”，因此不再建 recipient_round、attempt、
独立短链或封号表。目标国家使用 task JSON 数组快照承接多国家数据包。

### 12.2 服务拆分

```text
HyperlinkTaskApplicationService   创建/编辑/复制/查看/Action 编排
HyperlinkTaskValidator            模式、状态、内容、并发、时间和数据包校验
HyperlinkAccountSelector          完整筛选、试算、每轮选号
HyperlinkTaskScheduler            round due scan、延后启动、预发布截止、周期轮询
HyperlinkRecipientClaimService    按数据包当前代幂等领取号码
HyperlinkAccountConcurrencyGuard  task account_usage 条件占槽 + Redis 跨任务 TTL 信号量
HyperlinkDeliveryService          账号分配、唯一命令发送、同 command 恢复、回执
HyperlinkShortLinkService         短码替换与访问归因
HyperlinkTaskQueryService         列表及五个详情读模型
HyperlinkTaskProjectionService    分钟级 runtime/round、账号累计投影与低频校准
HyperlinkBillingGateway           报价、冻结、结算、释放
```

即时/预发布/周期的内部轮次规则必须单独做状态机测试：同一号码不得因调度重入重复建 recipient；
暂停不释放终态数据且冻结执行时长；继续不重置成功进度并从新的运行段续算；停止把未提交 recipient 记为
`TASK_STOPPED` 失败并释放未消费冻结；预发布只吸收新合格发信账号，不吸收
数据包后续导入号码；周期每轮只创建 round、固化 round_account 并分配任务内尚未分轮的 recipient，不重复
生成号码或追加计费。周期本轮账号为 0 时只记 skipped round，尚有待发号码时下轮按间隔继续试算。每账号
任务内成功上限由 account_usage 跨轮同步占槽，不能拿分钟级 account_stat 做派发判断。发送/失败等业务指标
按 `recipientId` 计数；发送中恢复必须查询或重放 recipient 已有 `commandId`，不创建第二个业务命令；点击短码
属于 recipient，乱序 ACK 只能单调推进该行状态。

## 13. 前端组件拆分

```text
views/hyperlink/tasks/index.vue
  components/TaskSearchForm.vue
  components/TaskSummaryCards.vue
  components/TaskTable.vue
  components/TaskEditorDrawer.vue
    components/WhatsAppMessagePreview.vue
    components/HyperlinkButtonEditor.vue       任务态锁 CTA_URL
    components/AccountFilterDrawer.vue
    components/ResourceAssetField.vue
    components/TaskFinalConfirmModal.vue
  components/TaskDetailDrawer.vue
    tabs/RecipientStatsTab.vue
    tabs/AccountStatsTab.vue
    tabs/ClickAttributionTab.vue
    tabs/VisitTrendTab.vue
    tabs/BanReasonTab.vue
```

`AccountFilterDrawer`、`ResourceAssetField`、`WhatsAppMessagePreview`、`VisitTrendTab` 必须设计成可复用组件，分别给策略、模板/素材、模板、市场分析留口子。表单模式用明确的 `create|copy|edit|view` 枚举，不用多组布尔值互相推断。

## 14. 权限、审计和错误

建议权限：

```text
tenant:hyperlink_task:view
tenant:hyperlink_task:create
tenant:hyperlink_task:edit
tenant:hyperlink_task:action
tenant:hyperlink_task:export
tenant:hyperlink_task:attribution_sensitive
```

没有 delete 权限。创建、编辑、开始、暂停、继续、停止、导出敏感归因、余额冻结/释放全部写审计日志。

关键错误：启用未选数据包、即时任务可用账号为 0、账号筛选字段非法、消息类型/按钮非法、预发布结束时间非法、周期参数非法、协议数不足导致并发超限、余额不足/报价过期、状态冲突、任务开始后不可编辑、素材不存在或已删除。

## 15. 剩余菜单的预留契约

### 15.1 超链策略

策略只需稳定提供：模式、周期间隔、完整账号筛选、最大执行账号、最大/每轮使用账号、每账号最大发送数。任务通过 options + detail 一次性导入。账号筛选 schema 由任务模块发布版本，策略不得复制另一套 DTO。

### 15.2 图片素材

提供名称/标签检索、单选、批量上传和稳定素材 ID；任务保存引用和必要快照。素材删除保护必须统计任务内容与模板引用。

### 15.3 超链市场分析

直接消费任务、recipient 和账号快照，落 90 天日聚合 + 8 天小时聚合；页面不实时扫描 recipient。
任务详情的访问趋势组件和指标口径与市场分析共用 query service，避免同名指标两套算法。

### 15.4 已有数据包和模板

数据包提供 `forTask` 可选项、当前代未使用数量、国家和导入快照；模板提供启用选项和完整内容详情。历史不可用项仍需回显，不能导致旧任务详情空白。

## 16. 实施顺序

1. 实现已冻结的账号筛选 schema、`account_profile` 分字段同步和协议平台映射。
2. 接入真实计费 Gateway，落任务 1:1 预约表和报价/冻结/结算/释放幂等。
3. 扩容模板标题到 1024，先落 task/content/runtime/round/account_usage/round_account/recipient_claim/
   recipient/billing 核心执行链及 data_package_phone claim owner。
4. 完成私聊发送、CTA URL、短链访问事件、账号累计查询投影及幂等回执聚合。
5. 完成列表、任务抽屉、账号筛选、素材选择、最后核对。
6. 完成五个详情 Tab、全部导出和敏感数据审计。
7. 用竞品逐屏对照验收，再推进策略、素材和市场分析独立菜单。

## 17. 完整性验收清单

- [ ] 五个列表筛选项、六张汇总卡、十五组表格列全部存在。
- [ ] 五种运行展示状态和开始/暂停/继续/停止/编辑/查看/复制/详情操作矩阵一致。
- [ ] 不出现任务删除按钮或 API。
- [ ] 三种新建消息类型、历史双图文兼容、任务按钮锁定 CTA URL。
- [ ] 四段表单、模板引用、策略引用、实时预览完整。
- [ ] 三种任务模式、消息间隔预设、并发和启动方式校验完整。
- [ ] 完整账号筛选抽屉所有可见字段都能创建、编辑回填、复制和计数。
- [ ] 素材选择与上传两个弹框完整。
- [ ] 启用/仅保存两种发布状态完整。
- [ ] 7 秒最后核对弹框包含余额、单价、预计冻结和所有任务摘要。
- [ ] 五个详情 Tab 的筛选、列、指标、排序、分页和导出完整。
- [ ] 列表、收信人、账号、归因、趋势五类导出完整。
- [ ] 手动刷新和「数据约每分钟同步」提示存在，不增加前端自动刷新。
- [ ] 10 张任务表职责与索引按工作负载落地；账号累计投影可从 recipient 事实重建，访问趋势直接聚合 recipient，短链和逐次点击不另造表。
- [ ] 50 万号码按批领取/释放可断点恢复；创建途中宕机不留无 owner 的 CLAIMED 行，也不暴露半包任务。
- [ ] claim 进入 OWNED 后释放代次操作锁，第二个任务可领取同代剩余号码；释放/失败恢复仍按号码 owner 精确隔离。
- [ ] 50 万 recipient 跨 3 个调度轮分配压测下，调度不全扫 recipient，账号默认查询命中累计投影、时间范围查询命中任务×时间索引；按当前业务不超过 10 万的任务验证 72 小时趋势命中首访索引并满足详情页延迟目标。
- [ ] 同任务同号码最多发送一次；周期轮只分配剩余 recipient，短码准确绑定唯一 recipient，余额按任务一次冻结。
- [ ] round worker 崩溃后只在租约过期时接管；round_account 重放不突破每轮选号上限；account_usage 并发占槽后，跨轮成功数不突破每账号任务上限。
- [ ] 停止任务把未提交 recipient 聚合为 `TASK_STOPPED` 失败；明细、runtime、未分配账号桶及已分轮行对应的
  round 口径一致，未分轮行不虚构 round 指标。
- [ ] ACK 不逐条争抢 runtime 热行；投影延迟符合“约每分钟同步”，重放与校准不重复计数；点击更新不污染发送指标同步时间。
- [ ] 冻结、调整、结算、释放四种外部计费调用在远端成功、本地提交前宕机后，均能按持久化操作类型和同一幂等键恢复。
- [ ] 计费、账号画像、私聊协议、短链归因任何一个硬依赖未完成时，不宣称菜单已完整复刻。

## 18. 竞品证据定位

关键证据及行号集中维护在 `docs/superpowers/reviews/2026-08-27-hylb-hyperlink-task-reverse-evidence.md`。设计评审只接受三类结论：构建产物明确可见的「已确认」、由多个调用点支持但需运行态验证的「高可信推断」、以及显式标注的「待真实 API/产品确认」。不得把推断重新写成竞品事实。
