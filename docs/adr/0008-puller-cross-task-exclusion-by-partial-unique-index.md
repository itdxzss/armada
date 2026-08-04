# ADR-0008：拉手跨任务互斥用 `pull_task_group_account` 部分唯一索引，不建租约表

日期：2026-08-02
状态：已接受

## 背景

需求确认：同一拉手账号允许在不同群链接之间复用，但同一时间最多服务一条运行中的执行行；该互斥必须覆盖不同父任务，不能只靠 JVM 内存锁。执行行完成、失败或进入资源等待时释放拉手；恢复执行时重新竞争可用拉手。

第一版数据模型为此建了 `pull_task_puller_lease` 表，字段包括 `lease_token`、`acquired_at`、`heartbeat_at`、`expires_at`、`released_at` 和一个 `active_key` 生成列，靠 `UNIQUE (tenant_id, account_id, active_key)` 保证互斥。

问题是拉手账号在某条执行行中的选择本来就已经有一行记录——`pull_task_group_account` 里 `role_type=2` 的那一行。租约表等于为同一个事实维护第二份记录，两份还必须保持同步：账号被移出执行行、进入风控冷却、执行行终态，三处都要同时改两张表。任一路径漏改就会出现"租约还在但角色行已经没了"或反之的悬挂状态。

## 决定

- 不建 `pull_task_puller_lease` 表。
- 在 `pull_task_group_account` 上增加 `occupied_at`、`released_at` 两列和一个生成列：

  ```sql
  occupancy_key BIGINT GENERATED ALWAYS AS (
      CASE WHEN role_type = 2 AND released_at IS NULL THEN account_id ELSE NULL END
  ) STORED
  ```

- 建 `UNIQUE (tenant_id, occupancy_key)`。同一租户内，一个拉手账号最多有一行"未释放"的角色记录，跨父任务生效。
- 释放 = 写 `released_at`。执行行完成、失败、被人工暂停或进入资源等待时释放。
- 恢复 = 把 `released_at` 置回 NULL。若该账号已被其他任务占用，唯一键直接拒绝，正是"恢复时重新竞争拉手"要求的行为。
- 生成列的 else 分支必须是 NULL。写成 0 会让唯一索引把所有已释放记录也纳入约束，一个账号一生只能有一条释放记录。此前 `V089` 的 `active_key` 和 `V005` 的 `is_active` 已确立该写法。
- 站台和管理账号不参与占用：`occupancy_key` 只在 `role_type=2` 时取值。站台同执行行唯一由 `UNIQUE (tenant_id, group_execution_id, role_type, account_id)` 保证，允许跨执行行复用。

## 影响

- 占用与角色选择是同一行，不存在两表不同步的悬挂状态。
- 丢失了"该账号历史上被占用和释放过几次"的时间线。当前没有需求消费这条信息；需要时可由后续的动作或审计记录补充。
- 进程异常退出会留下 `released_at IS NULL` 的行，账号被永久占住。需要一个回收规则：执行行处于终态或其调度锁 `lock_expires_at` 已过期且无人接管时，把该行的 `released_at` 补写为回收时间。这条规则要在调度器实现里覆盖，不能只依赖正常路径释放。
- 唯一键冲突是预期路径而不是异常：竞争失败时执行行进入"等待拉手"，不能当成系统错误抛给用户。

## 被否决方案

- **独立租约表**：见背景中的双写同步问题。
- **只用 JVM 内存锁**：需求明确要求覆盖跨父任务，单实例内存锁在多实例部署下无效。
- **在 `account` 表上加占用列**：会把任务域的瞬时状态写进账号身份主表，且无法表达"被哪条执行行占用"。
