# 建群营销账号受限状态设计

## 背景

测试环境建群营销任务 `24` 中，分组内仍有正常在线账号，但执行项最终显示“没有可用账号”。排查发现这些账号在建群阶段被 WhatsApp/Baileys 拒绝，协议层日志包含 `rate-overlimit` 和 `account_reachout_restricted`。这些账号连接状态仍可能是在线正常，但主动触达、建群、拉人能力已经受限。

当前建群营销只按 `account_state = NORMAL`、`login_state = ONLINE` 选择账号。若协议层只把受限错误作为普通建群失败处理，账号仍会留在正常在线池里，后续任务会继续选中它们，造成重复失败。

## 目标

- 新增账号生命周期状态 `账号受限`，用于表达 WhatsApp 已限制账号主动触达或建群能力。
- 建群营销遇到 `account_reachout_restricted` 或 `rate-overlimit` 时，将执行账号标记为账号受限并下线。
- 建群营销候选账号继续只选择正常在线账号，从而自动排除账号受限状态。
- 账号列表可展示并筛选账号受限状态。
- 账号统计“异常账号”包含账号受限，并显示受限子项。

## 非目标

- 不把 `ACCOUNT_BUSY` 归类为账号受限。它是协议层账号级并发锁，不代表 WhatsApp 限制账号。
- 不做自动恢复、定时解限或冷却到期恢复正常。恢复策略后续单独设计。
- 不改变普通消息发送失败的账号状态。
- 不把所有协议层 `500 INTERNAL_ERROR` 都归类为账号受限，只识别明确的 WA 限制原因。

## 状态模型

`account_state` 新增：

- `8 = 账号受限`

状态含义：

- 账号可能还能登录，但不适合继续主动触达、建群、拉人。
- 触发受限时同步写 `login_state = OFFLINE`，防止仍按在线账号被派单。
- `invalidated_at` 写入触发时间，作为进入受限状态的时间。
- `state_source` 使用 `GROUP_CREATE_RESTRICTED`。
- `block_reason` 写入原始限制原因：`account_reachout_restricted` 或 `rate-overlimit`。

## 协议层错误映射

后端只有在协议层响应体带明确错误码时才能可靠归类账号受限。当前协议层未捕获 Baileys 错误会返回通用 `INTERNAL_ERROR/internal server error`，真实原因只在协议层日志中。

因此协议层 `POST /v1/groups/create` 需要把以下 Baileys 错误转换成显式协议错误：

- `account_reachout_restricted` -> HTTP `422`，code `ACCOUNT_REACHOUT_RESTRICTED`
- `rate-overlimit` -> HTTP `429`，code `ACCOUNT_REACHOUT_RESTRICTED`

两个错误都表示该账号当前不能继续用于主动建群。`details.rawMessage` 保留原始消息，便于后端写入 `block_reason`。

## 后端建群营销处理

`GroupCreationMarketingWorker` 在 `groupCreatePort.create(...)` 抛出 `ProtocolException` 时：

1. 判断是否账号受限建群错误。
2. 如果是，先在本地事务内把执行账号标记为 `account_state = 8`、`login_state = 2`。
3. 再沿用现有换号重试逻辑，将当前账号写入该执行项 `retry_history_json`，选择下一个可用账号。
4. 如果没有下一个可用账号，执行项仍按现有逻辑进入 `NO_AVAILABLE_ACCOUNT`。

`ACCOUNT_BUSY` 仍按普通建群失败/重试处理，不更新账号生命周期状态。

## 账号选择

建群营销账号候选 SQL 已限定：

- `s.login_state = 1`
- `s.account_state = 2`
- `risk_status IS NULL OR risk_status = 1`
- `mute_status IS NULL`

新增 `account_state = 8` 后无需额外修改候选 SQL；受限账号自然不再可选。

## 账号列表与统计

账号列表：

- 状态标签支持 `8 -> 账号受限`。
- 状态筛选下拉增加“账号受限”，请求参数传 `accountState=8`。
- 受限状态标签使用 warning 风格。

账号统计：

- 后端统计增加 `restricted` 字段，表示 `account_state = 8` 的账号数。
- `restrictedTotal = banned + unbound + muted + exported + restricted`。
- 前端异常账号卡片子项增加“受限”。

## 数据迁移

数据库没有枚举约束，本次只更新列注释：

- `account_state.account_state` 注释追加 `8账号受限`。

代码常量、VO 注释、前端类型同步扩展到 `8`。

## 测试范围

后端：

- 协议 HTTP executor 能映射 `ACCOUNT_REACHOUT_RESTRICTED`。
- 建群遇到 `ACCOUNT_REACHOUT_RESTRICTED` 时标记账号受限并下线。
- 建群遇到 `rate-overlimit` 映射后的受限错误时标记账号受限并下线。
- 建群遇到 `ACCOUNT_BUSY` 时不标记账号受限。
- 账号列表可按 `accountState=8` 查询。
- 统计汇总包含 `restricted`，异常总数包含受限账号。

协议层：

- `/v1/groups/create` 遇到 Baileys `account_reachout_restricted` 时返回 `ACCOUNT_REACHOUT_RESTRICTED`。
- `/v1/groups/create` 遇到 Baileys `rate-overlimit` 时返回 `ACCOUNT_REACHOUT_RESTRICTED`，并保留原始原因。

前端：

- 账号状态标签显示“账号受限”。
- 账号状态筛选“账号受限”映射为 `accountState=8`。
- 异常账号卡片展示受限子项。
