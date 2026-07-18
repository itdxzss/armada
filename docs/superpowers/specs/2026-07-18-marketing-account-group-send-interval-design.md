# 普通营销任务单账号下群组发送间隔设计

> 状态：已确认，待实施计划
> 日期：2026-07-18
> 范围：`wheel-saas-pure-web`、`armada/armada-api`

## 1. 背景与当前事实

普通群组营销任务当前由 `MarketingRoundWorker` 在每轮为全部实际群目标生成
`marketing_task_send_attempt` 和 `message.send.requested` outbox 命令。事务提交后，
`ProtocolCommandDispatchTrigger` 会触发 dispatcher 把本批命令发往 Kafka。

当前链路有以下事实：

- `send_interval_seconds` 表示轮与轮之间的间隔，不是同一账号下逐群发送间隔。
- `send_per_round` 在当前轮次设计中不限制一轮最多发送多少个群；每轮仍覆盖全部实际群目标。
- Web 协议 worker 虽然按账号顺序执行同一批命令，但没有配置化等待间隔。
- Android Zhuan 消息消费者允许并发执行，同一账号的多条消息也可能同时在途。
- 普通营销命令使用账号对应的 Kafka key，但 Kafka key 只能保证分区归属和记录顺序，不能保证消费者逐条完成后再处理下一条。

因此，仅在页面增加字段而不改变 Armada 的 Kafka 投递节奏，不能消除同一账号瞬间收到全部群消息命令的风险。

## 2. 已确认需求

1. 在“新增营销任务”页面的“单轮发送数量”下方新增字段，页面名称固定为：
   **单账号下群组发送间隔**。
2. 默认值为 `0.5` 秒。
3. 可选范围为 `0.5～3.0` 秒，步长为 `0.1` 秒，最多一位小数。
4. Web 和 Android 协议账号都生效。
5. 采用简单口径：Armada 对同一账号的群消息 Kafka 命令执行固定推送间隔，不等待上一条 WhatsApp 发送结果。
6. 不同账号互不阻塞，可以并行向 Kafka 推送。
7. 协议层和 Android Zhuan 不修改；节流只在 Armada 内实现。
8. 旧营销任务迁移后使用 `0.5` 秒，避免旧任务继续瞬间推送。

## 3. 目标与非目标

### 3.1 目标

- 同一普通营销任务中，同一账号的相邻群消息 Kafka 推送时间至少相隔任务配置值。
- 不同账号保持并行，不能因为一个账号群多而阻塞其它账号。
- 节流在 Armada 多实例、任务轮次重叠和应用重启后仍然有效。
- 保留 outbox 的事务后投递、失败重试和 Kafka 结果回写模型。
- Web 和 Android 使用同一套 Armada 节流逻辑，不向协议 payload 暴露节流字段。

### 3.2 非目标

- 不等待上一条 WhatsApp 消息成功或失败后再开始计时。
- 不修改 Web 协议层、Android Zhuan 或 Kafka consumer 并发模型。
- 不改变 `send_interval_seconds` 的轮次间隔语义。
- 不改变当前 `send_per_round` 的既有轮次语义。
- 不把建群营销、历史群拉人营销或其它协议命令纳入本字段。
- 不取消已经进入 outbox 的命令；暂停或关闭任务仍沿用现有“停止生成新轮次，已投递命令自然完成”语义。

## 4. 方案选择

### 4.1 采用：Armada outbox 投递前按账号节流

任务保存配置值，轮次 worker 把该值转成 outbox 内部投递元数据。dispatcher 在真正调用 Kafka producer 前，
按“租户 + 协议后端 + 协议账号”执行持久化节流。

选择该方案的原因：

- Kafka 推送点统一位于 Armada，Web/Android 共用，协议端无需两套实现。
- 节流发生在 Kafka producer 之前，能直接消除 Armada 同账号批量瞬时推送。
- 持久化账号节流状态可覆盖多实例、应用重启和轮次重叠。
- 不在营销事务或轮次线程中长时间 `sleep`，避免占用数据库事务和 round executor。

### 4.2 不采用：协议端按账号等待

协议端最接近真实发送完成时间，但需要同时修改 Web 和 Android，并改变已确认的 Armada-only 范围。

### 4.3 不采用：Armada 等结果后再发下一条

该方案能实现“上一条完成后再等待”，但需要新增按账号逐条推进和结果驱动状态机，超出本次固定推送间隔口径。

## 5. 前端设计

### 5.1 表单

`GroupMarketingCreateDrawer.vue` 在“单轮发送数量”下方增加：

- 标签：`单账号下群组发送间隔`
- 控件：Element Plus `ElInputNumber`
- 默认值：`0.5`
- `min=0.5`
- `max=3`
- `step=0.1`
- `precision=1`
- 单位：`秒`

### 5.2 前端模型与请求

创建表单和 `CreateMarketingTaskRequest` 增加：

```ts
accountGroupSendIntervalSeconds: number;
```

提交前显式校验：

- 必须是有限数字；
- 必须在 `0.5～3.0`；
- 乘以 10 后必须为整数，拒绝 `0.55` 等超过一位小数的值。

页面校验只负责即时反馈，后端继续执行相同规则作为最终门禁。

## 6. 后端 API 与任务聚合

### 6.1 API 字段

`CreateMarketingTaskDTO`、`MarketingTaskVO` 和 `MarketingTaskDetailVO` 增加：

```text
accountGroupSendIntervalSeconds
```

Java 使用 `BigDecimal` 接收秒数，避免二进制浮点误差。后端校验：

- 空值按 `0.5` 秒处理，兼容滚动发布期间的旧前端请求；
- 小于 `0.5` 或大于 `3.0` 时返回校验错误；
- `stripTrailingZeros().scale() > 1` 时拒绝，确保最多一位小数；
- 通过校验后精确转换为整数毫秒。

稳定错误文案：

```text
单账号下群组发送间隔必须为0.5到3秒，最多一位小数
```

### 6.2 `marketing_task` 字段

在任务聚合增加：

```sql
account_group_send_interval_ms INT NOT NULL DEFAULT 500
  COMMENT '单账号下相邻群消息Kafka推送最小间隔(毫秒)'
```

选择整数毫秒而不是 `FLOAT/DOUBLE` 的原因：

- UI 的 0.1 秒可精确映射成 100 毫秒；
- dispatcher 使用毫秒时间戳，无需重复小数换算；
- 避免 `0.1` 的浮点比较和持久化误差。

该配置属于营销任务聚合：创建任务时写入，轮次 worker 读取。现有任务由 Flyway 默认并回填为 `500`。

## 7. Outbox 投递元数据与持久化节流

### 7.1 Outbox 快照字段

`protocol_command_outbox` 增加：

```sql
dispatch_interval_ms INT NOT NULL DEFAULT 0
  COMMENT '同一投递节流键的Kafka最小推送间隔(毫秒);0=不节流'
```

普通营销 `message.send.requested` 写入任务的 `account_group_send_interval_ms`；其它命令继续写 `0`。

该列是命令投递快照，不是第二份可编辑业务配置。它确保任务后续删除、结束或代码滚动发布时，已经生成的 outbox
仍按生成时口径发送。字段不进入 Kafka envelope 或 payload。

### 7.2 账号节流状态

新增平台基础设施表 `protocol_command_dispatch_pace`：

```text
tenant_id
protocol_backend
protocol_account_id
next_allowed_at
created_at
updated_at
```

唯一键：

```text
(tenant_id, protocol_backend, protocol_account_id)
```

该表保存运行时投递水位，不复制营销配置。它属于 protocol outbox dispatcher 的基础设施状态，不能放到营销域
或 `marketing_account_occupancy`。

### 7.3 轮次命令初始排期

轮次 worker 对本轮实际目标按账号分组，并保持每个账号内的稳定目标顺序。对某账号本轮第 `n` 条命令：

```text
initialNextRetryAt = roundStartedAt + n * accountGroupSendIntervalMs
```

其中 `n` 从 `0` 开始。不同账号的第 1 条命令都可立即到期。

`MessageSendCommand` 使用 Armada 内部投递策略对象携带：

- `notBeforeAt`
- `dispatchIntervalMs`

Web/Android backend 只把这两个字段写入 outbox 元数据，不写入协议 payload。

## 8. Dispatcher 算法

### 8.1 节流键

只对 `dispatch_interval_ms > 0` 的行使用：

```text
tenantId + protocolBackend + protocolAccountId
```

不能只使用手机号或任务 ID：手机号不是所有协议后端的稳定路由事实，任务 ID 又无法覆盖轮次重叠。

### 8.2 投递步骤

dispatcher 抢占到期 outbox 行后：

1. `dispatch_interval_ms=0` 的行走现有发布路径。
2. 对需要节流的行按节流键分组，并按 `next_retry_at, id` 排序。
3. 在短事务中锁定对应 `protocol_command_dispatch_pace` 行。
4. 当 `next_allowed_at <= now` 时，只允许该账号最早的一条命令进入本次 Kafka publish，并把水位推进到
   `now + dispatch_interval_ms`。
5. 同账号其余已抢占行释放回 `PENDING`，不增加失败重试次数，并按新水位重新设置 `next_retry_at`。
6. 当水位尚未到期时，本账号所有行都释放回 `PENDING`，最早一条设置为水位时间，其余继续顺延。
7. 不同节流键独立判断，因此同一批次中的不同账号可以一起进入 Kafka publish。

账号水位的读取和推进必须使用数据库行锁或等价的原子 SQL，保证多个 Armada 实例不能同时取得同一账号发送资格。

### 8.3 精确唤醒与兜底扫描

正常 after-commit 路径根据本批最早 `next_retry_at` 注册下一次 dispatcher 唤醒，不使用业务事务内 `sleep`。
同一进程只保留最早的有效唤醒任务，避免为每条群消息创建一个定时线程。

现有低频 outbox 扫描继续作为应用重启和定时任务丢失时的恢复路径。恢复扫描发现多条同账号过期命令时，
仍必须先经过持久化节流水位并重新排期，禁止一次性追赶发送。恢复后的首条可能延迟，但不能早于安全间隔。

### 8.4 发送失败

Kafka producer 调用失败后沿用现有 `RETRY/DEAD` 状态机。节流水位不回退：失败命令已经占用一次推送时隙，
下一条仍需等待间隔，避免 Kafka 抖动恢复时形成突发。

## 9. 生命周期与轮次语义

- `send_interval_seconds` 继续决定下一轮生成时间。
- 如果一轮逐账号排期耗时超过轮次间隔，后续轮次命令可以生成，但 dispatcher 水位会把同账号命令继续串行排队。
- 现有 unfinished attempt backlog 保护继续限制持续积压，不新增第二套营销积压阈值。
- 暂停、关闭、任务结束只阻止新轮次；已经进入 outbox 的命令继续按节流规则发送。
- 普通营销后续若生成新的发送命令，仍从任务读取间隔快照，不允许绕过同账号节流；本次不新增结果驱动的即时重发路径。

## 10. 错误处理与可观测性

- 表单和 API 参数非法：返回业务校验错误，不创建任务。
- 节流水位读取或更新失败：当前 outbox 行保持或恢复为可重试状态，不直接发布 Kafka。
- deferred 不是发送失败，不增加 `retry_count`，不写 `last_error`。
- 日志增加安全字段：`taskId`、`roundNo`、`commandId`、脱敏/内部账号 ID、`dispatchIntervalMs`、
  `scheduledAt`、`deferredUntil`。
- 日志不得输出消息正文、图片 base64、完整手机号、凭据或代理信息。

## 11. 测试设计

### 11.1 前端

- 创建表单默认值为 `0.5`。
- `0.5`、`0.6`、`3.0` 可提交。
- `0.4`、`3.1`、`0.55`、非有限数字被拦截。
- 创建请求包含 `accountGroupSendIntervalSeconds`。
- 重开抽屉后恢复默认值，不沿用上一次输入。

### 11.2 后端 Service

- DTO 空值归一为 `500ms`。
- 合法边界和一位小数精确转换成毫秒。
- 越界和超过一位小数抛稳定业务错误。
- 创建、列表、详情均返回秒数字段。
- 普通营销命令携带内部 pacing 策略；其它营销来源不携带。

### 11.3 轮次 Worker

- 同账号三群初始排期为 `0ms/500ms/1000ms`。
- 两个账号各自从 `0ms` 开始，不能全局串行。
- 固定群和账号动态群都按解析后的实际账号归组。
- 混合 Web/Android 账号使用同一规则，backend payload 不出现 pacing 字段。
- 轮次重叠时生成仍成功，实际投递由持久化水位串行化。

### 11.4 Dispatcher 与数据库

- 同账号同时到期多行只发布一行，其余无重试计数地延期。
- 不同账号同时到期可在同批发布。
- 同一账号不同任务/轮次共享同一个协议账号水位。
- 多实例并发抢占只能有一个实例取得账号时隙。
- 应用重启后的过期行重新排期，不突发补发。
- Kafka 发布失败后水位不回退。
- `dispatch_interval_ms=0` 的生命周期、群同步、进群、建群营销和历史群命令保持现有行为。
- Flyway 真库测试验证两个新增列、旧任务 `500ms` 回填、pace 表唯一键和 mapper SQL。

### 11.5 验证命令

实施计划阶段按实际测试类收敛精确命令，至少包含：

```bash
cd armada-api
mvn -Dtest='MarketingTaskServiceImplLifecycleTest,MarketingRoundWorkerTest,ProtocolCommandDispatcherTest' test
./dbtest.sh 'MarketingTaskCreateReadDbTest'
mvn test
```

```bash
cd wheel-saas-pure-web
node --test src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
pnpm typecheck
pnpm build
```

若真库环境或全量测试被既有环境问题阻塞，必须报告具体命令和真实错误，不能以聚焦测试代替完成声明。

## 12. 发布与回滚

建议发布顺序：

1. 先发布 Armada 数据库迁移和后端。
2. 确认旧任务读取默认 `0.5` 秒、outbox 非营销命令保持立即发送。
3. 再发布前端字段。

滚动发布期间，旧前端不传字段时后端使用 `0.5` 秒；新前端请求旧后端不应先上线，因此后端必须先发布。

代码回滚时保留新增列和 pace 表是安全的：旧代码会忽略额外列；如需彻底回滚，使用变更记录中的 rollback SQL。
回滚不得删除已有 outbox 业务行或发送尝试。

## 13. 事实、推断与未确认项

### 已确认事实

- 页面字段名称、默认值、范围、步长已由用户确认。
- Web/Android 都需要生效。
- 用户选择固定 Kafka 推送间隔，不等待 WhatsApp 发送完成。
- 用户确认仅修改 Armada 投递端，协议端不改。

### 设计推断

- “没有 Kafka 积压”说明正常路径不需要为现有 consumer backlog 设计动态降速；持久化水位仍用于多实例、重启和轮次重叠安全。
- 旧任务统一回填 `0.5` 秒符合本次降低封号风险的目标。

### 未确认项

无。实施阶段不得把本字段扩展到建群营销或历史群营销。
