# 群组检测后延迟发送方案

状态：已按方案实施，待联调
版本：V1.0
日期：2026-08-18

## 1. 目标

普通营销任务选择账号动态目标（`ACCOUNT_DYNAMIC`）后，Armada 检测到账号加入新群时，可按任务配置等待一段时间，再执行该群的第 0 轮首次营销发送。

本方案的重点不是建设另一条消息发送链路，而是在现有“新群首次发送”前增加一个可持久化的业务等待阶段。等待到期后继续复用现有消息组装、`MessageSendPort`、Outbox、Kafka、协议发送、结果回写和失败重试能力。

需求来源：

- `D:/业务相关流程图/群组列表相关需求/营销任务需求/群组检测后延迟发送需求_轻量需求卡_V1.0.docx`
- `D:/业务相关流程图/群组列表相关需求/营销任务需求/群组检测后延迟发送需求_产品需求文档_PRD_V1.0.docx`

## 2. 设计原则

1. 不新建第二套完整发送链路，只增加“等待记录”和“到期触发”能力。
2. 不新建延迟任务表，复用 `marketing_task_send_attempt` 记录第 0 轮等待和发送结果。
3. 不改 Kafka Topic、协议命令和发送结果事件。
4. 不复制消息组装、Outbox 入队和结果回写代码；即时发送和延迟到期发送必须调用同一个新群发送执行逻辑。
5. 延迟等待属于营销业务状态，不提前写入 Outbox；进入 Outbox 后继续完全复用现有可靠投递能力。
6. 普通轮次不增加延迟，只排除仍处于等待状态的新群。
7. 延迟配置随任务创建一次性保存，不增加任务配置编辑入口，避免等待记录批量重排和配置版本管理。

## 3. 已确认的业务规则

1. 功能只适用于普通营销任务的 `ACCOUNT_DYNAMIC` 新群首次发送，即 `round_no=0`。
2. 延迟关闭时，沿用当前新群即时发送逻辑。
3. 延迟开启时，检测时间使用 Armada 服务器接收并确认新增群的时间。
4. 计划发送时间为 `detected_at + delay`。
5. 同一批检测到的多个群使用相同业务计划时间；到期后继续使用现有账号群间发送间隔进行技术错峰。
6. 新群处于延迟等待期间，不参与普通轮次发送。
7. 如果普通轮次因并发或历史逻辑已经成功提交到协议层或发送成功，第 0 轮等待发送取消并标记为跳过。
8. 如果第 0 轮已经提交或发送，后续普通轮次仍然正常发送。
9. 任务暂停期间不发送，计划时间继续计算；恢复后对已经到期的记录尽快发送。
10. 延迟开启时，暂停期间新检测到的群仍创建第 0 轮 WAITING，但不进入 Outbox；计划时间仍按检测时间计算，恢复后由既有到期 Worker 尽快发送。延迟关闭时仍不创建记录，避免暂停期间即时发送。
11. 任务关闭或到达结束时间后，尚未提交 Outbox 的等待记录标记为跳过。
12. 到期时重新校验任务、账号、账号占用和群关系；不满足发送条件时标记为跳过，不进入 Outbox。
13. 到期正式发送时使用任务当前关联的最新营销素材。
14. 服务恢复后，如果任务仍有效、未暂停、未关闭，且账号和群仍可发送，则对已到期记录尽快补发，不重新计算计划时间。
15. `SKIPPED` 不计成功数和失败数，计入跳过数。
16. 本期不提供延迟配置修改入口；任务创建后只读，执行中自然不存在可操作的延迟按钮。
17. 正式提交后的协议失败沿用现有第 0 轮失败重试规则，不重新等待业务延迟。

## 4. 范围

### 4.1 本次改动

- 营销任务增加新群延迟配置。
- 第 0 轮 Attempt 增加等待状态、检测时间和计划发送时间。
- 新群检测后根据开关选择即时发送或创建等待记录。
- 增加一个轻量的到期扫描器，调用现有新群发送服务完成提交。
- 普通轮次排除 `WAITING` 新群。
- 关闭和自动结束任务时收口等待记录。
- 现有群明细查询展示 WAITING 为“等待发送”并置顶，且不改变成功、失败、跳过计数。
- `detected_at`、`scheduled_send_at` 等时间字段落库，用于后台调度、幂等判断和审计，但不在页面展示。
- 前端只在现有营销任务创建表单中，按原有风格增加“群组检测后延迟发送”配置区；不新增编辑入口、延迟发送详情页面或时间展示区域。

### 4.2 不在本次范围

- 普通轮次延迟或全局最小触达间隔。
- 固定群目标 `GROUP_FIXED`。
- 建群营销、拉群营销、历史群营销。
- 新 Kafka Topic、新协议命令、新结果事件。
- 新延迟队列表。
- 单个等待群手动取消。
- 运行中修改配置或批量重新计算计划时间。
- 修改 Outbox 核心状态机。

## 5. 能力复用

| 能力 | 处理方式 | 说明 |
|---|---|---|
| 新群识别 | 复用 | 继续由 `AccountGroupMembershipReportServiceImpl` 计算 `addedGroups` |
| 动态目标归属 | 复用 | 继续查询账号当前归属的发送中 `ACCOUNT_DYNAMIC` 目标 |
| 第 0 轮幂等 | 复用 | 继续使用 Attempt 的任务目标、群、轮次唯一约束 |
| 营销素材组装 | 复用 | 继续使用 `MarketingMessageCommandFactory` |
| 消息发送入口 | 复用 | 继续使用 `MessageSendPort.enqueue` |
| Android/Web 路由 | 复用 | 继续使用 `RoutingMessageSendPort` 和现有 Backend |
| 可靠投递 | 复用 | 继续使用 `protocol_command_outbox` 和 `ProtocolCommandDispatcher` |
| 发送结果回写 | 复用 | 继续使用 `ProtocolMessageEventConsumer` 和 `MarketingSendResultServiceImpl` |
| 新群失败重试 | 复用 | 正式提交后继续走现有第 0 轮重试逻辑 |
| 跳过数统计 | 复用 | 详情已有任务/账号/群三级 `skippedMessageCount`，继续按 Attempt 聚合 |
| 到期触发 | 新增轻量能力 | 只负责扫描到期 WAITING 并调用现有新群发送服务，不重复实现发送链路 |

## 6. 目标调用链

### 6.1 延迟关闭

```text
account.groups_reported
→ AccountGroupMembershipReportServiceImpl 识别 addedGroups
→ MarketingNewGroupImmediateSendServiceImpl.enqueueNewGroups
→ 现有第 0 轮 Attempt
→ 现有消息组装
→ 现有 MessageSendPort / Outbox / Kafka / 协议发送
→ 现有结果回写
```

### 6.2 延迟开启

```text
account.groups_reported
→ AccountGroupMembershipReportServiceImpl 识别 addedGroups
→ MarketingNewGroupImmediateSendServiceImpl.enqueueNewGroups
→ 创建第 0 轮 WAITING Attempt
→ 轻量 DelayScheduler 扫描到期 WAITING
→ 回调现有新群发送服务的共享提交方法
→ 现有消息组装
→ 现有 MessageSendPort / Outbox / Kafka / 协议发送
→ 现有结果回写
```

即时发送和到期发送的区别只在“什么时候调用共享提交方法”，后续处理必须相同。

## 7. 数据模型

### 7.1 `marketing_task`

新增任务级配置：

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---:|---|
| `is_new_group_delay_enabled` | `TINYINT(1) NOT NULL` | `0` | 是否开启新群延迟发送 |
| `new_group_delay_value` | `INT NOT NULL` | `30` | 延迟数值 |
| `new_group_delay_unit` | `TINYINT NOT NULL` | `1` | `1=分钟，2=小时` |

约束：

- 历史任务默认关闭。
- 开启后默认 30 分钟。
- 分钟允许 1～60，小时允许 1～24，只允许正整数。
- 切换单位时保留数字；如果数字不符合新单位范围，前后端提示校验错误并禁止保存。
- 关闭开关时仍保留数值和单位，方便开始前再次开启。

### 7.2 `marketing_task_send_attempt`

新增字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `detected_at` | `BIGINT NULL` | Armada 确认检测到新群的时间 |
| `scheduled_send_at` | `BIGINT NULL` | 业务计划发送时间 |
| `outbox_accepted_at` | `BIGINT NULL` | 命令首次被 Outbox 接受的不可变时间 |

新增 Attempt 状态：

```text
4 = WAITING（等待发送）
```

现有状态保持：

```text
0 = SUBMITTED
1 = SUCCESS
2 = FAILED
3 = SKIPPED
4 = WAITING
```

WAITING 初始字段：

```text
round_no = 0
attempt_no = 1
status = WAITING
command_id = NULL
detected_at = Armada确认时间
scheduled_send_at = detected_at + 延迟
outbox_accepted_at = NULL
submitted_at = NULL
result_at = NULL
attempted_at = detected_at（兼容既有非空约束，正式提交时更新为提交时间）
```

新增到期索引：

```sql
KEY idx_marketing_attempt_wait_due (status, scheduled_send_at, id)
```

不新增等待表，也不修改 `protocol_command_outbox` 表结构。

`marketing_task_send_attempt.outbox_accepted_at` 记录命令首次被 Outbox 接受的不可变时间。普通轮次即使之后收到协议失败结果，
该字段仍保留，用于准确判断其是否已经覆盖新群第 0 轮等待；本地入队拒绝不会写入该字段。
V130 通过租户和 `command_id` 关联既有 `protocol_command_outbox` 回填迁移前可验证的普通轮次接受事实。

### 7.3 只调整查询、不改结构的表

| 表 | 用途 |
|---|---|
| `marketing_task_target` | 确认动态目标、获取账号和过滤普通轮次 |
| `marketing_account_occupancy` | 到期时确认账号仍归当前任务占用 |
| `account` / `account_state` | 到期时检查账号状态 |
| `wa_account_group_binding` / `wa_group` / `wa_group_participant` | 到期时检查账号群关系 |
| `group_link` | 读取群链接和名称 |
| `marketing_template` | 到期时读取最新素材 |
| `protocol_command_outbox` | 到期校验通过后复用现有可靠投递 |

## 8. 接口

### 8.1 创建任务

现有接口：

```http
POST /api/marketing-tasks
```

请求增加：

```json
{
  "newGroupDelayEnabled": true,
  "newGroupDelayValue": 30,
  "newGroupDelayUnit": "MINUTE"
}
```

旧客户端不传时按关闭、30、分钟处理。

### 8.2 列表和详情

以下现有接口返回延迟配置：

```http
GET /api/marketing-tasks
GET /api/marketing-tasks/{id}
```

返回延迟配置字段用于查询和后续兼容；本期页面不增加任务编辑入口。`detected_at`、`scheduled_send_at`、`submitted_at`、`result_at` 属于后台内部调度和审计数据，本期不要求前端接口返回，也不在页面新增展示。

任务详情继续复用现有发送结果、失败原因和 `skippedMessageCount` 展示能力，不增加新群延迟发送专属明细结构，也不新增任务级冗余计数列。

### 8.3 生命周期接口

接口路径保持不变：

```http
POST /api/marketing-tasks/{id}/start
POST /api/marketing-tasks/{id}/pause
POST /api/marketing-tasks/{id}/resume
POST /api/marketing-tasks/{id}/close
```

只补充 WAITING 的状态处理，不新增生命周期接口。

## 9. 状态流转

### 9.1 任务状态

任务状态不新增：

```text
PENDING(1)
SENDING(2)
PAUSED(5)
COMPLETED(7)
CLOSED(8)
```

### 9.2 Attempt 状态

```text
检测到新群且开启延迟
    → WAITING

WAITING
    → SUBMITTED：到期、资格校验通过、Outbox 接受
    → SKIPPED：普通轮次已覆盖
    → SKIPPED：任务关闭或结束
    → SKIPPED：账号或群不再可发送
    → FAILED：到期提交时素材或协议适配本地拒绝

SUBMITTED
    → SUCCESS：协议发送成功
    → FAILED：协议发送失败
```

等待阶段不创建 Outbox，因此任务关闭时可以安全取消；已经 `SUBMITTED` 的记录继续按现有 Outbox 和结果回写语义收口。

## 10. 关键处理逻辑

### 10.1 检测新群

在现有 `MarketingNewGroupImmediateSendServiceImpl.enqueueNewGroups` 内增加分支：

```text
任务是 SENDING，延迟关闭
    → 调用现有即时提交逻辑

任务是 SENDING，延迟开启
    → 插入 WAITING Attempt，不组装素材，不写 Outbox

任务是 PAUSED，延迟开启
    → 插入 WAITING Attempt，不组装素材，不写 Outbox

任务是 PAUSED，延迟关闭，或任务为其他状态
    → 不创建第 0 轮记录
```

暂停只改变“是否可以正式提交”，不改变延迟开启时的新群登记。WAITING 的 `scheduled_send_at` 仍为
`detected_at + delay`；暂停期间到期也不投递，恢复后由既有到期 Worker 尽快处理。

### 10.2 轻量到期调度

只新增一个 `MarketingNewGroupDelayScheduler`，不再新增一套 Worker、消息工厂或发送端口。

职责：

1. 小批量扫描到期且任务为 `SENDING` 的 WAITING 记录。
2. 按租户、任务、Target、计划时间组成批次。
3. 调用现有新群发送 Service 的事务方法处理该批次。

现有新群发送 Service 内将“组装消息、生成命令、分批调用 `MessageSendPort`、处理本地拒绝”整理为共享提交方法：

```text
即时发送入口 ─┐
              ├→ sharedSubmitNewGroups(...)
延迟到期入口 ─┘
```

共享方法只保留一份，避免即时发送和延迟发送后续行为不一致。

### 10.3 到期资格校验

共享提交方法在延迟到期场景先检查：

1. 任务仍为 `SENDING` 且未超过结束时间。
2. Target 仍属于普通营销 `ACCOUNT_DYNAMIC`。
3. 账号仍由当前任务占用。
4. 账号登录、风控、禁言等状态仍允许发送。
5. 账号仍在群内，群状态允许发送。
6. 检测时间之后没有普通轮次已经覆盖该群。

任一业务资格不满足：

```text
WAITING → SKIPPED
不写 Outbox
不计成功数和失败数
计入跳过数
```

### 10.4 普通轮次过滤

普通轮次展开 `ACCOUNT_DYNAMIC` 群时排除：

```text
相同 target_id
+ 相同 group_jid
+ round_no = 0
+ status = WAITING
```

只排除 WAITING：

- 第 0 轮已经提交或完成时，普通轮次照常发送。
- 第 0 轮已跳过时，普通轮次照常发送。
- 延迟关闭且暂停期间没有第 0 轮记录的新群，恢复后可以参加普通轮次。

为处理“普通轮次先解析、WAITING 后提交”的并发窗口，延迟到期时再做一次普通轮次覆盖检查。两层判断共同保证 Q1，但不改变普通轮次本身的时间和发送规则。

### 10.5 同批错峰

同批新群的业务计划时间一致：

```text
scheduled_send_at = detected_at + delay
```

实际命令最早发送时间继续复用现有账号群间隔：

```text
notBeforeAt = max(当前处理时间, scheduled_send_at)
              + 同账号批次序号 × accountGroupSendIntervalMs
```

使用当前处理时间兜底，可避免服务恢复后多个已过期命令同时投递。

### 10.6 暂停、恢复、关闭和结束

| 操作 | WAITING 处理 |
|---|---|
| 暂停 | 保持 WAITING 和原计划时间，调度查询不选 PAUSED 任务 |
| 暂停期间检测新群 | 仅在延迟开启时登记 WAITING，不写 Outbox；延迟关闭时不登记 |
| 恢复 | 无需重新计算，暂停前及暂停期间登记的到期记录自动被下一次扫描选中 |
| 手动关闭 | WAITING 批量转 SKIPPED，原因 `TASK_CLOSED` |
| 自动结束 | WAITING 批量转 SKIPPED，原因 `TASK_EXPIRED` |

已进入 Outbox 的 `SUBMITTED` 记录不由本功能撤销，继续沿用现有可靠投递和结果回写规则。

## 11. 前端

### 11.1 页面改动范围

只在现有营销任务创建表单中，复用当前卡片、分隔线、表单行、数字输入框、下拉框和开关样式，增加截图所示配置区；不新增编辑入口、独立页面、路由、详情卡片或专属明细组件。

配置区内容：

```text
群组检测后延迟发送  [开关] 开启

延迟时长            [30] [分钟/小时]

检测到群组后延迟 30 分钟发送
延迟时间从系统检测到每个群组的时间开始计算，不以任务启动时间计算。
```

交互规则：

- 默认关闭；首次开启默认值为 30 分钟。
- 开启后显示延迟时长输入、单位选择、动态摘要和灰色说明文案。
- 数字只允许正整数；分钟范围 1～60，小时范围 1～24。
- 切换单位只保留输入数字，不自动换算；超出新单位范围时提示并禁止保存。
- 关闭后保留数字和单位，任务开始前再次开启时继续回显。
- 表单状态由当前创建/编辑组件本地维护，随现有任务表单统一提交，不为该配置引入新的全局状态。

### 11.2 可编辑状态

该配置只允许在现有任务创建入口操作，不增加“修改新群延迟”的页面、按钮或接口。

| 状态 | 是否可修改 |
|---|---:|
| PENDING 且未开始 | 是 |
| SENDING | 否 |
| PAUSED | 否 |
| COMPLETED | 否 |
| CLOSED | 否 |

后端创建接口仍执行单位和数值校验；由于没有修改接口，不存在绕过前端改写运行中配置的旁路。

### 11.3 不新增时间展示

- 检测时间和计划发送时间必须落库，但不在任务列表、任务详情或群明细页面展示。
- 页面不新增“等待发送”“检测时间”“计划发送时间”等字段。
- 现有任务详情中的发送结果、失败原因和跳过数继续保持原有展示方式。
- 不新增详情轮询逻辑或 WebSocket。

## 12. 幂等、事务和恢复

### 12.1 重复群事件

继续依赖现有第 0 轮唯一约束；同一个任务 Target、群和 `round_no=0` 只能存在一条 Attempt。

### 12.2 多实例调度

到期扫描可以重复发现记录，真正提交时通过行锁或条件更新确保只有一个事务处理同一条 WAITING。该场景必须使用 MySQL 8.4 集成测试验证。

到期事务固定按“任务主记录 → WAITING Attempt”顺序加锁。暂停、关闭和自动结束都需要更新同一任务主记录，
因此会与到期提交串行化；用户看到暂停成功后，不会再有该等待记录进入 Outbox。

### 12.3 Attempt 与 Outbox 一致性

到期提交必须在同一事务中完成：

```text
生成 commandId
→ 写 Outbox
→ WAITING 转 SUBMITTED
→ 提交事务
```

事务失败时保持 WAITING，由后续扫描重试；不能出现 Attempt 已提交但没有 Outbox，或 Outbox 已存在但 Attempt 仍在等待。

### 12.4 服务恢复

计划时间保存在 Attempt。服务恢复后重新扫描，仍有效且已到期的记录立即进入共享提交逻辑，业务计划时间保持原值。

## 13. 跳过原因

建议补充：

| 原因码 | 文案 |
|---|---|
| `ORDINARY_ROUND_COVERED` | 已被普通轮次覆盖 |
| `TASK_CLOSED` | 营销任务已关闭 |
| `TASK_EXPIRED` | 营销任务已结束 |
| `ACCOUNT_NOT_OWNED` | 账号不再由当前任务占用 |
| `ACCOUNT_NOT_ELIGIBLE` | 账号当前不可发送 |
| `GROUP_NOT_SENDABLE` | 当前群关系不允许发送 |

已有更精确的群关系原因时继续复用现有原因码。

## 14. 风险与控制

| 风险 | 控制 |
|---|---|
| 普通轮次和延迟发送并发 | 普通轮次过滤 WAITING；到期时二次检查覆盖 |
| 多实例重复提交 | 行锁/条件更新 + 第 0 轮唯一约束 |
| 暂停恢复后短时积压 | 分批扫描并复用账号群间发送间隔 |
| 任务结束后误发送 | 关闭/结束时立即把 WAITING 转为 SKIPPED |
| 账号或群状态变化 | 到期时重新校验，不提前缓存发送资格 |
| 素材修改 | 到期时重新读取最新素材 |
| 旧接口兼容 | 新配置缺省为关闭，历史数据无需回填 WAITING |
| 历史普通轮次覆盖 | V130 从仍保留的 Outbox 精确回填接受事实；滚动发布时先完成全部后端节点升级，再发布前端入口 |
| 历史回填大事务 | 上线前在近似数据量 MySQL 执行 EXPLAIN/耗时评估；大表窗口暂停营销调度与 Outbox 派发，必要时改为按 Attempt ID 分批的数据迁移 |
| 新状态影响统计 | WAITING 进入现有群明细并置顶显示“等待发送”，但不计成功、失败或跳过；转为 SKIPPED 后只计入跳过数 |

## 15. 验收标准

| 编号 | 场景 | 预期 | 验证方式 |
|---|---|---|---|
| AC-001 | 延迟关闭时检测到新群 | 沿用现有即时发送，不产生 WAITING | Service + 端到端测试 |
| AC-002 | 开启 30 分钟延迟 | 产生 WAITING，计划时间为检测时间加 30 分钟，未写 Outbox | Mapper + 集成测试 |
| AC-003 | 同批检测多个群 | 业务计划时间相同，到期后按现有群间隔错峰 | 时钟 + Outbox 测试 |
| AC-004 | WAITING 群遇到普通轮次 | 普通轮次不为该群生成命令 | 普通轮次回归测试 |
| AC-005 | 普通轮次已并发覆盖 | 第 0 轮转 SKIPPED，不再发送 | 并发测试 |
| AC-006 | 第 0 轮先提交后进入普通轮次 | 普通轮次照常发送 | 回归测试 |
| AC-007 | WAITING 期间暂停 | 暂停时不发送，恢复后到期记录尽快发送 | 生命周期测试 |
| AC-008 | 暂停期间检测到新群 | 延迟开启时产生 WAITING 且不写 Outbox，计划时间不变；延迟关闭时不产生记录 | Service 测试 |
| AC-009 | 关闭或自动结束任务 | 所有未提交 WAITING 转 SKIPPED | Mapper + Service 测试 |
| AC-010 | 到期时账号退群或失去占用 | 转 SKIPPED，不写 Outbox | 资格校验测试 |
| AC-011 | 等待期间修改素材 | 到期时使用最新素材 | 集成测试 |
| AC-012 | 服务停机后恢复 | 有效到期记录补发，计划时间不变 | 恢复测试 |
| AC-013 | 重复上报同一群 | 只有一条第 0 轮 Attempt | 唯一约束测试 |
| AC-014 | 多实例同时扫描 | 只产生一条协议命令 | MySQL 并发测试 |
| AC-015 | WAITING 转 SKIPPED | 跳过数加一，成功数和失败数不变 | 详情 Mapper 测试 |
| AC-016 | 任务创建后查看页面 | 无延迟配置操作按钮，不提供修改接口 | API + 前端静态测试 |
| AC-017 | 正式提交后协议失败 | 复用现有重试，不重新等待业务延迟 | 结果回写测试 |
| AC-018 | 前端展示延迟配置 | 只在现有任务表单中按原有风格增加配置区，无独立页面或详情区 | 前端组件测试 + 人工验收 |
| AC-019 | 检测和计划时间展示 | 两个时间正确落库，但任务列表、详情和群明细均不展示 | Mapper 测试 + 页面验收 |
| AC-020 | 迁移前普通轮次已入 Outbox 后失败 | V130 回填接受事实，WAITING 到期后转 SKIPPED | 迁移 SQL + Mapper 测试 |
| AC-021 | 群明细存在 WAITING | WAITING 置顶并显示“等待发送”，成功、失败、跳过计数均不增加，检测和计划时间不展示 | Mapper + API + 页面验收 |

## 16. 实施顺序

1. Flyway 增加任务配置、Attempt 时间字段、WAITING 状态和到期索引，并从既有 Outbox 回填可验证的接受事实。
2. 扩展任务创建接口及 VO，不新增修改接口。
3. 在现有新群发送 Service 中提取共享提交方法。
4. 延迟开启时创建 WAITING，延迟关闭时继续走即时提交。
5. 增加一个轻量 `MarketingNewGroupDelayScheduler` 调用共享提交方法。
6. 普通轮次排除 WAITING，并增加到期覆盖兜底判断。
7. 补充暂停期间 WAITING 登记、关闭、自动结束和现有详情统计查询；WAITING 置顶但不参与三类计数。
8. 在现有前端任务创建表单内增加配置区，不新增编辑入口和时间展示。
9. 完成 MySQL 并发、Outbox、生命周期和端到端验证。
10. 发布时先完成全部后端节点和 Flyway 升级，再发布前端创建入口；不得让旧后端节点处理已开启延迟的新任务。
11. 生产执行 V130 前验证历史 Outbox 回填的影响行数、锁时间、undo 和复制延迟；未通过容量门禁不得直接执行大事务回填。

## 17. 明确不实施的重复能力

后续开发不得为延迟发送单独建设以下内容：

- 第二套营销消息工厂；
- 第二套 Android/Web 路由；
- 第二张延迟队列表；
- 第二套 Outbox；
- 新 Kafka Topic；
- 新协议发送接口；
- 新发送结果 Consumer；
- 新的成功/失败统计体系；
- 与现有新群发送重复的失败重试服务。

如果开发过程中发现需要复制上述任一能力，应先回到本方案进行架构复核，而不是直接复制实现。
