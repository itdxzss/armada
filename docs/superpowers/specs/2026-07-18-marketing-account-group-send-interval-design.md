# 普通营销任务单账号下群组发送间隔设计

> 状态：已确认并按简单方案实施
> 日期：2026-07-18
> 范围：`wheel-saas-pure-web`、`armada/armada-api`

## 1. 需求

在新增普通营销任务页面的“单轮发送数量”下方增加字段：

- 名称：`单账号下群组发送间隔`
- 默认：`0.5` 秒
- 范围：`0.5～3.0` 秒
- 步长：`0.1` 秒
- 精度：最多一位小数

该间隔表示 Armada 向 Kafka 推送同一账号下相邻群消息命令的固定时间差，不等待上一条 WhatsApp 消息完成。
Web 和 Android 都生效，协议层不改。

## 2. 简单方案

链路如下：

```text
页面秒数
  -> marketing_task 保存整数毫秒
  -> MarketingRoundWorker 按账号计算 notBeforeAt
  -> outbox.next_retry_at
  -> 事务提交后到点交给原有 dispatcher
  -> Kafka
```

不增加以下内容：

- 不增加 outbox 间隔列。
- 不增加独立账号节流表。
- 不修改 dispatcher 的锁定、发布、重试状态机。
- 不修改 Kafka payload。
- 不修改 Web 协议层或 Android 协议端。

## 3. 前端

创建表单、请求类型和任务行类型增加：

```ts
accountGroupSendIntervalSeconds: number;
```

控件使用 `ElInputNumber`：

```vue
<el-input-number
  v-model="form.accountGroupSendIntervalSeconds"
  :min="0.5"
  :max="3"
  :step="0.1"
  :precision="1"
/>
```

提交前校验有限数字、范围和一位小数。错误文案固定为：

```text
单账号下群组发送间隔必须为0.5到3秒，最多一位小数
```

## 4. 后端任务字段

API 使用秒：

- `CreateMarketingTaskDTO.accountGroupSendIntervalSeconds`: `BigDecimal`
- `MarketingTaskVO.accountGroupSendIntervalSeconds`: `BigDecimal`
- `MarketingTaskDetailVO.accountGroupSendIntervalSeconds`: `BigDecimal`

数据库使用毫秒：

```sql
account_group_send_interval_ms INT NOT NULL DEFAULT 500
```

空值按 500ms 处理，合法值精确换算为 500～3000ms。旧任务通过列默认值使用 500ms。

## 5. 每轮排期

`MarketingRoundWorker` 在一轮内维护 `accountId -> position`。对某账号第 `n` 个实际群目标：

```text
notBeforeAt = roundStartedAt + n * accountGroupSendIntervalMs
```

`n` 从 0 开始，所以每个账号的第一个群可立即投递。不同账号独立计数，例如间隔 500ms：

```text
账号 A: 0ms, 500ms, 1000ms
账号 B: 0ms, 500ms
```

账号位置在同一轮的多个 outbox 批次之间连续，不会因批量大小重置。

## 6. Outbox 与定时触发

`MessageSendCommand` 增加 Armada 内部字段：

```java
long notBeforeAt
```

backend 编码 payload 时忽略该字段；`ProtocolCommandOutboxServiceImpl` 只把它写入现有
`protocol_command_outbox.next_retry_at`。

`ProtocolCommandDispatchTrigger` 在事务提交后：

1. 按本批 rows 的 `next_retry_at` 分组。
2. 已到期的分组立即提交给现有 dispatch executor。
3. 未到期的分组复用应用现有 `TaskScheduler` 在对应时间提交。
4. 本机调度失败或服务重启时，由原有低频 outbox 扫描兜底。

dispatcher 保持原逻辑，只处理 trigger 交给它的到期 rows。

## 7. 作用范围与限制

- 只作用于普通营销任务。
- 建群营销和历史群营销传 `notBeforeAt=0`，保持立即投递。
- 暂停或关闭任务仍只阻止新轮次，已进入 outbox 的命令按现有语义完成。
- 这是用户确认的简单实现，不提供多实例共享节流状态。
- 服务重启会丢失内存定时任务，恢复时间由原有周期扫描决定。

## 8. 测试重点

- 前端默认值、边界、步长、重开抽屉和请求字段。
- 后端默认 500ms、合法换算、非法范围和一位小数限制。
- 同账号群目标按 0/500/1000ms 排期。
- 不同账号分别从 0ms 开始。
- 跨 outbox 批次账号位置连续。
- outbox 保存 `next_retry_at`，payload 不含 `notBeforeAt`。
- future rows 到点才调用 dispatcher。
- Web/Android payload 保持不变。
