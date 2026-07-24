# 手动下线意图闸门设计

## 问题与目标

账号批量下线目前只写入 `account.offline.requested` outbox，没有持久化“用户要求保持离线”。
当较晚到达的 `PROXY_FAILED` 事件被消费时，即时恢复和 5 秒补偿扫描都会再次生成上线命令，
导致已经明确手动下线的账号被重新拉起。

目标是保留正常的代理失败换 IP 重试，同时保证最新的显式用户意图优先：

- 显式单账号/批量下线后，旧 `PROXY_FAILED` 及其自动补偿不得重新上线。
- 后续显式单账号/批量上线应解除闸门并正常上线。
- 协议状态事件只更新实际登录状态，不得改变用户期望状态。
- 不合并现有 PROXY_FAILED 的 A（状态）、B（精确释放代理）、C（换 IP 上线）事务，
  不增加 `READ_COMMITTED`。

## 方案比较

1. `desired_login_state` 持久化期望状态（采用）：语义直接，能跨进程重启并在 C 事务条件 UPDATE 中原子判定。
2. `manual_offline_at` 与失败时间比较：依赖跨系统时钟和 attempt 关联完整性，乱序边界更复杂。
3. 生命周期版本随命令下发并由协议拒绝旧版本：防护最强，但需要同时修改后端、协议和命令契约，当前范围过大。

## 数据模型

在 `account_state` 增加可空 `desired_login_state TINYINT`：

- `1`：期望在线，允许 `PROXY_FAILED` 自动恢复。
- `2`：期望离线，禁止自动恢复。
- `NULL`：历史账号尚未建立显式意图。为了不改变历史自动恢复行为，按“允许恢复”处理；账号下一次显式上线/下线后即收敛为 `1/2`。

该字段属于账号生命周期状态聚合，与高频协议回写的 `login_state` 含义不同：
`login_state` 是实际状态，`desired_login_state` 是控制面意图，二者不得互相覆盖。

## 命令与事务规则

### 显式下线

单账号/批量下线在默认隔离级别的同一短事务内：

1. 将目标账号 `desired_login_state` 更新为 `OFFLINE`。
2. 取消尚未发布的账号上线 outbox（仅 `PENDING`；已经 `LOCKED/SENT` 的命令不做不可靠撤销）。
3. 写入新的下线 outbox。

同账号 Kafka key 保序；已发送的旧上线命令排在新的下线命令之前。下线提交后，延迟到达的
`PROXY_FAILED` 即使更新实际状态，也无法再通过 C 事务生成新上线命令。

### 显式上线

单账号上线、批量上线和人工一键抢登在各自既有事务中先把
`desired_login_state` 更新为 `ONLINE`，再执行既有状态预占、代理分配和 outbox 写入。
因此用户在下线后再次明确点击上线时，新的上线意图正常生效。

### 自动恢复

即时恢复与调度补偿仍然只针对实际 `OFFLINE/PROXY_FAILED`，并新增期望状态条件：

```text
actual login_state = OFFLINE
and state_source = PROXY_FAILED
and desired_login_state in (ONLINE, NULL legacy)
```

自动恢复只读取期望状态，永远不能把 `OFFLINE` 改回 `ONLINE`。C 事务条件 UPDATE 是最终并发闸门；
调度扫描同步增加条件只是减少无效扫描。B 事务仍可按失败事件携带的 `proxyId` 精确释放旧绑定，
不会把代理标记为不可用。

## 并发与积压

- 显式上线/下线都先更新同一 `account_state` 行，数据库行更新顺序决定最后成功提交的显式意图。
- 下线事务取消未发布的旧上线命令，避免旧 `PENDING` 在下线之后才发布。
- 当前 perf2 生命周期命令 topic 已基本追平；不对 lag 仅个位数的 topic 重置 offset。
- 状态事件 topic 混有 ONLINE/OFFLINE/PROXY_FAILED，不能为了少量积压整体跳过。部署闸门后让其正常消费，
  旧 `PROXY_FAILED` 会被期望状态条件跳过，同时保留正常状态回写。

## 验证与回滚

测试至少覆盖：

1. 期望离线时，C 条件抢占返回 0，不分配代理、不写上线 outbox。
2. 历史 `NULL` 和期望在线时，PROXY_FAILED 仍可换 IP 重试。
3. 显式下线原子写期望状态、取消旧 PENDING 上线并写下线 outbox。
4. 显式上线解除闸门并正常写上线 outbox。
5. 调度扫描不选择期望离线账号。

回滚代码时保留新增列无害；若必须回滚 schema，通过后续 Flyway 删除列，禁止手工修改共享库。
