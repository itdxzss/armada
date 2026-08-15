# 拉群任务（PullTask / NORMAL_LINK）优化任务清单

> 日期：2026-08-15
> 分支：1.0.3-snapshot
> 目标：单任务万级群规模可跑通，并把软件侧性能瓶颈拆除
> 状态：待评审；未编码、未迁移、未部署
> 实测基线：test1（i-06cf0d5fb86263860 / armada-test01-app-01），任务 134，只读 SSM 查询

---

## 0. 实测基线（2026-08-15，任务 134）

单群 201 料子：

| 项 | 值 |
|---|---|
| `pull_count_min` / `max` | 10 / 15（实测平均批量 10.4） |
| **`pull_interval_seconds`** | **15** |
| `concurrent_group_count` | 1 |
| 执行行 194 总耗时 | **628 秒（10.5 分钟）** |
| 拉人调用 | 32 次（正常波次 19 + 补拉波次 13） |
| 料子结果 | 成功 169 / 失败 32 |
| 失败原因 | **PRIVACY_BLOCKED × 32（100%）** |
| 协议响应延迟 | 1~3 秒 |
| 相邻调用实际间隔 | 稳定 15 秒（= 配置值，无调度抖动） |

全库规模（负载极低）：

- `pull_task_group_execution` 共 191 行，活跃 5 行
- **历史最大任务仅 10 个群**（任务 119）
- `protocol_command_outbox`：**status=2(SENT) 964,900 行，无归档**

## 0.1 实测对原模型的修正

**节拍是 15 秒，不是 3~5 秒。** `PullTaskOperationDelayPolicy` 的静默被
`PullTaskBatchAddTransactionService:158-161` 的
`Math.max(now + pullIntervalSeconds*1000, nextSideEffectAt(now))` 覆盖。

容量公式随之改写：

| 环节 | 单线程容量 | 饱和时的并发群数 C |
|---|---|---|
| 调度线程（20ms/行 → 50 行/秒） | 50 次/秒 | **C ≈ 750** |
| 结果消费（5ms/事件 → 200 事件/秒） | 200 事件/秒 | **C ≈ 300** |

结果消费仍是第一性能瓶颈，但**调度线程并行（原 P0-1）优先级下调**。

总时长 = `10000 / C × 10.5 分钟`：C=100 → 17.5h，C=300 → 5.8h，C=750 → 2.3h。

---

## 1. 阻塞项（不修则 10000 群跑不起来）

### T1｜claim 分池：待启动行饿死执行中行 ★★★ 阻塞

**现象**：单任务 WAIT_START 行数 ≥ `batchSize` 时，并发槽位一填满，
执行中的群再也拿不到调度名额，任务停止推进。

**证据链（全部已验证）**：

1. `PullTaskStandardDraftWriter:92` 创建执行行时写死 `nextRunAt = 0`；
   test1 实测：`execution_status=1` 的行 `next_run_at=0`，
   `execution_status=2` 的行 `next_run_at=1786696561983`（未来）
2. `PullTaskGroupExecutionMapper.xml:164`：`ORDER BY next_run_at ASC, id ASC LIMIT n`
   → `0` 恒小于未来时间戳 → 待启动行永远插队
3. `releaseLock`（同文件 :603-610）只清 `lock_owner`/`lock_expires_at`，
   **不改 `next_run_at`** → 抢不到槽位的行原地不动，下一轮仍是同一批
4. **本地 H2 已复现**：`PullTaskGroupExecutionMapperInMemoryTest`
   `#claimDueLetsWaitStartRowsStarveDueExecutingRows`，`limit=3` 时
   抢到的全是 WAIT_START 行，已到期的 EXECUTING 行一行没抢到

**未暴露原因**：历史最大任务 10 个群 < batchSize 100。

**方案**：`dispatchOnce()` 把一次 claim 拆两次，共用同一 `lockOwner`：

```java
int advancing = executionMapper.claimDue(
        criteria(EXECUTING + WAIT_RESOURCE, limit = batchSize, ...));
int remaining = batchSize - advancing;
if (remaining > 0) {
    executionMapper.claimDue(criteria(WAIT_START, limit = remaining, ...));
}
List<PullTaskGroupExecution> claimed = executionMapper.selectClaimed(lockOwner, now);
```

**不会反向饿死待启动行**：执行中行的到期速率 = `C / pull_interval_seconds`。
C=300、间隔 15s → 每秒仅 20 行到期，batchSize=500 时剩 480 个名额给待启动行。
条件 `batchSize > C / 间隔秒数` 在任何合理配置下都满足。

**额外收益**：现有 claim 用三组 `(execution_status, stage)` OR 拼接，
UPDATE 基本走不了 `idx_pull_task_execution_dispatch`；拆开后状态集合固定，
正好贴合索引前缀。同时解决多任务间"大任务霸占 batch"的饿死。

**被否方案**：
- 失败后推后 `next_run_at`：只要 backoff < 15s 仍会插队；> 15s 则槽位空转。治标。
- 排序加 `CASE`：隐式优先级、无配额可控、排序表达式使索引更难用。

**改动**：`PullTaskExecutionDispatchCoordinator` 拆 `claimCriteria`；
**Mapper XML 不用改**（`claimDue` 的状态条件本就参数化）。

**验证**：改上面那个特征测试的断言；新增"第一池占满时待启动为 0"、
"跨任务按 next_run_at 交错抢占"两个用例。

---

## 2. 高收益低风险

### T2｜PRIVACY_BLOCKED 不进补拉波次 ★★★

任务 134 的 32 个失败**全部**是 `PRIVACY_BLOCKED`（料子号自身隐私设置），
确定性终态，重试不可能成功。但补拉波次重试了 4 轮：

| 波次 | 类型 | 调用 | planned | 有效 |
|---|---|---|---|---|
| 1 | 正常 | 19 | 201 | 全部 |
| 2~5 | 补拉 | 13 | **131** | **0** |

**白烧 3.5 分钟 = 单群总时长的 33%。**

改动：把 `PRIVACY_BLOCKED` 归入不可重试原因码。
收益：单群 10.5 → 约 7 分钟，同比例减少协议调用与结果事件。
前置：需与协议层对齐"还有哪些原因码同属确定性终态"（见 T11）。

### T3｜补两个索引

```sql
-- 槽位 COUNT 子查询：idx_pull_task_execution_page 不含 execution_status，
-- idx_pull_task_execution_dispatch 不以 task_id 打头 → 每次启动扫任务全部行
ADD KEY idx_pull_task_execution_task_status (tenant_id, task_id, execution_status);

-- selectClaimed 每秒一次，WHERE lock_owner=? AND lock_expires_at>?，当前无索引
ADD KEY idx_pull_task_execution_lock (lock_owner, lock_expires_at);
```

纯加索引，零语义变更。

### T4｜`protocol_command_outbox` 归档

已 964,900 行 SENT 且持续增长，10000 群会再加数十万行。
影响 `idx_dispatch` 扫描与兜底 drain。保留窗口待定。

---

## 3. 容量天花板

### T5｜群事件消费者并发 + 分区 ★ 唯一跨仓

拉人逐成员结果走此路径（`ProtocolGroupEventConsumer:422` 的
`source == "pull_task_batch_add"` 分支），当前单线程，C≈300 饱和。

- **T5.1** `ProtocolGroupEventConsumer:147` 的 `@KafkaListener` 补 `concurrency` 配置
  （同目录 normal-group 写了 `:4`、account 写了 `:4`，只有这个漏了）
- **T5.2** `protocol.group.events.v1` 扩分区（部署脚本无建 topic 配置，疑为 broker 默认单分区）
- **T5.3** 分区 key 由**协议层生产端**按 `groupExecutionId` / `groupJid` 设置以保序
- **T5.4** 放并发前确认回写幂等（同 eventId 重放两次无重复副作用）

### T6｜参数扩容

- `batch-size` 100 → 500
- `DB_POOL_MAX_SIZE` 20 → 60

必须与 T1、T7 同批，否则并发放开后连接池立刻饱和。

### T7｜命令发送层（已降级）

**核实结论**：`ProtocolCommandPublisher` 的凭据/代理注入（`hydrateOnlineRows`）
**只对 `account.online.requested` 命令生效**（`:345` 的类型判断），
拉群命令（拉人、加好友、提权、进群）不走该路径；即便是上线命令也已按租户批量查
（`selectByTenantAndAccountIds` 传 ID 列表），不存在"每条命令三次查库"。

去掉凭据查询后，发送一条拉群命令只剩 JSON 序列化 + Kafka send（亚毫秒级）。
负载 = `C / pull_interval_seconds`，C=300 时约 20 条/秒，单线程绰绰有余。

- ~~T7.1 发送线程池配置化~~ → **不是瓶颈，暂不做**
- ~~T7.2 凭据缓存~~ → **删除**：不适用于拉群路径；且 `creds_json` 是会话密钥，
  不宜常驻内存
- **T7.3（保留）** `ProtocolCommandDispatchTrigger:submitDispatch` 队列满时
  fallback 到当前线程发送，而当前线程往往就是调度线程 → 改为不 fallback，
  交给 10 秒一次的兜底 drain

---

## 4. 调度并行（按实测已降级）

### T8｜lane 并行 + 租约余量

- **T8.1** lane 并行，键取 **execution id**（单任务场景不能按 task 分）；
  `lane-count` 默认 1 = 完全走现有串行路径，一键回退
- **T8.2** lane 处理每行前检查租约余量，过期的留给下一轮（现存缺陷，串行时靠运气）
- **T8.3** `PullTaskExecutionDispatchStats` 加 `plus()` 支持 lane 局部统计合并

拉手并发争抢：`pull_task_group_account.occupancy_key`
（`role_type=2 AND released_at IS NULL`）是**跨任务全局唯一键**，
并发选号会撞 `DuplicateKeyException` → `Coordinator:138-144` → `releaseLock` →
下一轮重试。代价可接受，选号需加随机偏移降低撞车率。

### T9｜批量启动（T1 之后可降级）

`acquireExecutionSlot`（`PullTaskMapper.xml:166-196`）每启动一个群
`pull_task.version + 1`，打同一行。T1 落地后待启动行取数被"剩余名额"限住，
CAS 冲突大幅下降，**本项可延后评估**。

---

## 5. 待查与待定输入

| 编号 | 事项 |
|---|---|
| T10 | call_seq 13/14 提交间隔异常（48s、65s，正常 15s），多花 83 秒 ≈ 单群 13%，原因未定位 |
| T11 | 除 `PRIVACY_BLOCKED` 外的确定性终态原因码清单（需协议层对齐，T2 前置） |
| T12 | `protocol.group.events.v1` 当前分区数与生产端消息 key（需查 armada-protocol，T5 前置） |
| T13 | 目标：10000 群要多久跑完 → 决定 C |
| T14 | 可用拉手号数量（`occupancy_key` 全局互斥，直接封顶 C） |
| T15 | `pull_interval_seconds` 是否下调（15→10 则单群时长降 1/3；封号风险由业务定） |

---

## 6. 埋点与压测

### T16｜埋点（压测前置，当前几乎没有）

| 指标 | 位置 |
|---|---|
| **积压深度**（最核心单一指标） | `next_run_at <= now` 且活跃未暂停的行数 |
| 调度单轮耗时 | `Coordinator.dispatchOnce` 已有 log.info，加 `costMs` 与 P95 |
| outbox 各状态行数 | `protocol_command_outbox` group by status |
| dispatch executor 队列深度 | `ThreadPoolTaskExecutor` 暴露 |
| consumer lag | Kafka group `armada-api-group-events` |
| Hikari 活跃/等待连接 | Actuator |

### T17｜分层压测

- **L0 SQL 层**：造 10 万行，`EXPLAIN ANALYZE` claimDue / selectClaimed / 槽位 COUNT
- **L1 调度吞吐**：协议层 stub，lane-count 1/4/8/16 对比曲线
- **L2 outbox**：灌 5 万条 PENDING，测发送速率与队列深度
- **L3 结果回传**：按真实比例回放事件，测单线程与并发后的消费速率、幂等性
- **L4 端到端爬坡**：500 → 2000 → 5000 → 10000 群，全 stub 协议层
- **L5 真号标定**：50~100 群，标定单账号安全频率与 429 触发点

急停开关：`armada.task.pull-execution-dispatcher.enabled`。
L0~L4 严禁连真实协议层。

---

## 7. 落地顺序

| 批次 | 任务 | 风险 | 跨仓 | 收益 |
|---|---|---|---|---|
| **1** | **T1 claim 分池** | 低 | 否 | **解除 10000 群阻塞** |
| **2** | T3 索引、T4 outbox 归档、T16 埋点 | 零 | 否 | 消除扫描退化、可观测 |
| **3** | T2 PRIVACY_BLOCKED（依赖 T11） | 低 | 否 | **单群 -33%** |
| **4** | T6 参数扩容 | 低 | 否 | 配合后续并发 |
| **5** | T5 消费者并发 + 分区（依赖 T12） | 中 | **是** | 天花板 C≈300 → 更高 |
| **6** | T8 lane 并行 | 中 | 否 | 天花板 C≈750 → 更高 |
| **7** | T7.3 取消同线程 fallback | 低 | 否 | 消除调度线程被阻塞的风险 |
| **8** | T9 批量启动（视 T1 效果决定是否做） | 低 | 否 | 启动期 CAS |

批次 1、2 无外部依赖，可立即开始。

---

## 8. 局限说明

test1 当前全库仅 191 个执行行、5 个活跃，调度器零压力
（任务 134 实测间隔稳定 15 秒、无抖动即证据）。

- **T1 的饿死机制**：已由本地 H2 测试直接复现，结论可靠。
- **其余关于"排队/饱和"的容量数字**：均为按代码与实测节拍**外推**，
  非当前观测现象，需 T17 压测验证。
