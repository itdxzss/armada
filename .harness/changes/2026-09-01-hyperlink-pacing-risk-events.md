# 变更记录：超链账号发送节奏与协议风控事件留痕

- 日期 / 分支 / worktree: 2026-09-01 / 当前分支 / `/Users/daishuaishuai/IdeaProjects`
- 需求来源: 用户要求修复任务 8 暴露的发送间隔问题，并记录 `RATE_LIMITED`、`ACCOUNT_REACHOUT_RESTRICTED`、`CHAT_SUSPENDED` 后开展受控测试分析
- 状态: 本地实现与聚焦验证完成，待确认环境后部署和真实账号受控测试

## 目标（一句话）

让每条超链消息使用同一个均匀分布的实际间隔贯穿 Armada 与 Android 串行调度，并以正确作用域追加保存三类协议风控信号。

## 缺口拆解 / 任务清单
- [x] 连续 recipient ID 的发送间隔不再落入狭窄递增区间，且重试时结果稳定。
- [x] Armada 的 `next_send_at` 与发给 Android 的 `sendIntervalMs` 使用同一条实际间隔。
- [x] Android 私聊不使用群聊 typing 的 400ms 提前量。
- [x] Android 账号 worker 队列暂空、重建时，不在原间隔窗口内重置真实发送时钟。
- [x] 新增 append-only `protocol_risk_event`，幂等记录三类信号和账号/操作/会话作用域。
- [x] `ACCOUNT_REACHOUT_RESTRICTED` 才能形成账号触达投影；`RATE_LIMITED` 和 `CHAT_SUSPENDED` 不再一律冻结私聊账号。
- [x] Web / Android 的账号触达限制事件进入 Armada 已消费的账号状态事件主题，保留业务关联字段。
- [x] 完成聚焦测试、迁移结构测试和跨仓静态检查；不在本轮自动部署或发送真实 WhatsApp 消息。

## 关键设计决策

- 区间内间隔使用稳定散列映射，而不是直接对递增 recipient ID 取模；这样分布离散、同一 recipient 重试稳定，测试也不依赖随机数。
- 第四点不是永久保留 worker：只保留上次提交时钟到该条间隔窗口结束，兼顾正确节奏和资源回收。
- 风控事件表只追加、不覆盖；现有 `account_state` / task usage 继续作为调度投影，不能充当历史审计。
- 消息发送结果/ACK、账号状态/限制、群健康/进群/动作/批量成员/成员查询及普通建群结果统一转换为 `ProtocolRiskResultMetadata`；Kafka consumer 构造器强制依赖风控 sink，不能通过可选 NOOP 静默漏记。
- 风控事实必须在租户上下文内通过 `protocolAccountId` 解析 canonical account；事件声明的 `accountId` 仅用于一致性校验，错绑或跨租户缺失直接失败并进入 Kafka 重试/DLT。
- `RATE_LIMITED` 作用域为具体操作，`ACCOUNT_REACHOUT_RESTRICTED` 作用域为账号外联，`CHAT_SUSPENDED` 作用域为群聊。原始码与平台时限保留，业务层不根据文本猜测。
- `account_state` 将消息失败兜底、平台明确消息限制和拉人限制分源保存：平台解除只清平台来源；平台已给出截止后，截止前发生的迟到消息结果也不能重新建立固定 24 小时窗口。
- Web 侧保持兼容事件名 `account.restricted`，路由到账号状态主题；Android 对齐同一业务事件契约。
- “规避”仅指尊重平台时限、降低触发风险和调整业务调度，不尝试绕过生效中的平台限制。

## 验证（evidence-before-done）

- Armada：Java 17 下 compile 通过；间隔、消息结果/ACK、四类 consumer、风控 adapter、H2 mapper
  与迁移结构的组合聚焦测试共 155 个，0 failure / 0 error / 0 skipped；两个 MyBatis XML 和
  `git diff --check` 通过。
- Android：`go test ./internal/armada`、相关聚焦 `-race`、`go vet ./...`、`go build ./...`、
  `git diff --check` 通过。全仓仅既有 `pkg/noise` 8 项（含缺少 `vectors.txt`）失败。
- Web：required-ack replay、publisher、config、event bridge、subjects、错误归一和 worker consumer
  共 7 suites / 146 tests 通过；全量 111 suites / 1313 tests 通过，另有既有 3 suites / 8 tests
  因沙箱回环监听和当前 Baileys patch/export 缺口失败。lint/build 仅被既有
  `account-manager.ts:3206 resyncAppStateReadOnly` 类型缺口阻断；Compose 配置校验通过。
- 三仓 `git diff --check` 通过；独立二次审查未发现本次范围内遗留 P0/P1/P2。

## 部署
- commit / 环境 / 部署后验证结果: 未提交、未部署；真实 WhatsApp 测试需另行确认环境、测试账号和收件人。

## 遗留 / 跟进

- 真实测试使用全参 Android 测试账号和明确许可的测试收件人；同一账号不得并跑其它营销任务，
  这样账号级 `account.restricted` 才能按账号和时间窗无歧义关联到测试任务。
- 初测采用三组：任务 8 当前区间、上下限同时放大 1.5 倍、上下限同时放大 2 倍；账号随机分组，
  三天轮换早/中/晚时段，每个账号每天只进入一个测试窗口。初测只判断明显趋势，需连续七天再定长期默认值。
- 立即停止规则：`ACCOUNT_REACHOUT_RESTRICTED is_active=true` 停该账号并尊重 `restricted_until`；
  `CHAT_SUSPENDED` 只停对应群；`RATE_LIMITED` 结束该账号本窗口并记录具体操作，不尝试绕过平台限制。
- 分析使用 `hyperlink_task_recipient` 的唯一 `protocol_message_id`、`send_status`、`delivered_at`，以及
  `protocol_risk_event` 的首个风险事实；服务器 ACK、送达、已读分别统计。账号在首个限制前的成功数是
  “截至 `occurred_at` 的去重成功提交/送达数”，未触发限制的账号按测试结束时间作右删失样本。
