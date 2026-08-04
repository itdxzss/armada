# 普通群链接任务 EX-01 调度骨架设计

日期：2026-08-03
状态：按已确认 PRD / ADR 落地，不新增产品口径
对应任务：EX-01（执行行 claim、检查点状态机、父任务内并发群数为 1）
前置切片：普通群链接数据层、创建链路 BE-01～BE-06

## 1. 范围

本切片把已冻结的普通群链接任务从“只能停在 `WAIT_START`”推进到可恢复的真实调度骨架：

1. 手动启动与 `autoStart=1` 共用同一个启动服务；
2. 任务启动时冻结管理分组当前可用账号数 `required_manager_count`；
3. 只有父任务处于 `EXECUTING` 的执行行可以参与跨租户 claim；
4. 同一父任务同时只允许一条执行行处于 `EXECUTING`；
5. 调度锁、租户上下文、乐观锁和检查点均由数据库事实驱动；
6. 对 `LINK_VALIDATION` 阶段执行真实公开邀请页复核：有效时推进到 `MANAGER_JOIN`，明确失效时本行失败，网络不可达时延后重试；
7. 成功推进到 `MANAGER_JOIN` 后停止，管理账号踩链接由 EX-02 接手。

本切片不做：管理账号选择和踩链接、协议命令、联系人、邀请拉手、站台/料子拉群、回调、资源等待、完整暂停恢复、详情读模型。

## 2. 已确认事实

- `pull_task_group_execution` 已有 `execution_status`、`stage`、`next_run_at`、`lock_owner`、`lock_expires_at`、`version` 及两个轮询游标。
- `claimDue` / `selectClaimed` / `updateCheckpoint` / `releaseLock` 已存在并有 H2 租户隔离与过期锁测试，但目前没有生产调用方。
- 父任务创建完成固定为 `WAIT_START`；`pull_task_standard_setting.auto_start` 决定是否在提交事务完成后调用启动入口。
- `concurrent_group_count` 已落库，但 M1 的 EX-01 明确先按并发数 1 实现；SC-01 再扩展为配置值 N。
- 任务启动时必须按管理分组当前可用账号数冻结 `required_manager_count`，运行中分组变化不能改变该值。
- 启动时必须重新校验群链接；明确失效的执行行终止且绑定 TXT 不重新分配，网络问题不能伪装成链接失效。
- 中间切片可以停在已持久化的真实阶段，但不得返回虚假成功或伪造协议结果。

## 3. 方案比较

### 方案 A：JVM 内存信号量

按 `taskId` 在单实例内维护一个 semaphore，实现简单，但多实例下两个进程仍可同时启动同一父任务的两条执行行，违反持久化恢复和并发要求，否决。

### 方案 B：新增“活跃槽位”生成列与唯一索引

数据库可直接保证并发 1，但 SC-01 要扩展为 N；届时唯一索引无法表达 N 个槽位，仍需迁移和重做，属于为临时 M1 约束污染 schema，否决。

### 方案 C：现有行租约 + 父任务行锁二次复核（采用）

先用现有 `claimDue` 做跨租户有界租约，再在单租户短事务内锁定父任务，复核父任务状态和当前 `EXECUTING` 行数。多个实例即便分别租到同一任务的不同候选，也会在父任务行锁处串行，只有第一条能获得并发槽位；其余释放租约。该方案不改 schema，并可在 SC-01 把“1”替换为 `concurrent_group_count`。

## 4. 启动合同

### 4.1 手动启动

新增：

`POST /api/pull-tasks/standard/{taskId}/start`

权限沿用 `tenant:pull_task:operate`。Controller 只传递可信用户身份和任务 ID，业务规则在 Service。

启动事务顺序：

1. 按当前租户读取并锁定 `pull_task`；只接受 `task_type=STANDARD`、`mode=NORMAL_LINK`。
2. `EXECUTING` 视为幂等成功；其他非 `WAIT_START` 状态返回业务冲突。
3. 读取冻结 setting；缺失视为数据冲突。
4. 通过 `AccountProtocolLookupService.findOnlineNormalByGroupId(managerGroupId)` 获取当前可用管理账号，数量为 0 时拒绝启动。
5. `freezeRequiredManagerCount` 写入本次可用账号数。
6. `updateStatusWithVersion` 原子执行 `WAIT_START -> EXECUTING`，写父任务 `started_at`。

启动成功只表示任务获得调度资格，不表示任何 WhatsApp 动作已成功。

### 4.2 自动启动

创建配置 `autoStart=1` 时，在创建事务成功提交后调用同一个启动服务。采用独立触发器封装 `TransactionSynchronization.afterCommit`，沿用仓库现有 after-commit 模式；事务回滚时不得触发。

自动启动因资源校验失败时，任务保留 `WAIT_START`，记录脱敏错误日志，不把失败伪装成已启动。用户仍可补足资源后手动启动。

## 5. 调度数据流

```text
周期线程
  -> claimDue（跨租户、短租约、仅父任务 EXECUTING + stage=LINK_VALIDATION）
  -> selectClaimed
  -> 对每行恢复 TenantContext
       -> 单租户事务锁父任务
       -> 复核并发槽位=1
       -> WAIT_START 行切为 EXECUTING；重试中的 EXECUTING 行复用原检查点
  -> 事务外调用 GroupInvitePageFetcher.probe
  -> 单租户事务按 id + version + lockOwner 回写
       -> 有群资料：stage=MANAGER_JOIN，释放租约
       -> 明确无群资料：execution_status=FAILED + LINK_INVALID，释放租约
       -> 不可达：保持 LINK_VALIDATION，next_run_at=now+退避，释放租约
```

外部 HTTP 必须在事务外，不能持父任务行锁或数据库连接等待网络。

## 6. 状态与并发规则

### 6.1 Claim 资格

执行行必须同时满足：

- 父任务 `task_type='STANDARD'`、`mode='NORMAL_LINK'`、`status='EXECUTING'`；
- 执行行 `execution_status IN (WAIT_START, EXECUTING)`；
- `stage=LINK_VALIDATION`；
- `manual_paused=0`、`next_run_at<=now`；
- 无有效租约，或原租约已过期。

因此 `autoStart=0` 且未手动启动的任务不会被扫描，已经推进到 `MANAGER_JOIN` 的执行行也不会在 EX-02 完成前空转。

### 6.2 父任务并发数 1

- 启动一条 `WAIT_START` 行前，锁父任务并统计同任务 `execution_status=EXECUTING` 的其他行。
- 已有运行行时，本候选不改状态并释放租约。
- 网络不可达重试的是同一条 `EXECUTING` 行，不重新占第二个槽位。
- 当前行明确失败后释放槽位，下一轮允许启动同任务下一条 `WAIT_START` 行。

### 6.3 乐观锁与租约

- 所有业务回写必须同时校验 `id + version + lock_owner`；过期 worker 不得覆盖新持有者结果。
- `selectClaimed` 继续要求 `lock_expires_at > now`。
- 回写与释放锁在同一条 UPDATE 中完成，避免“状态已推进但锁未释放”的中间态。
- 仅因父任务槽位不足而跳过时可以调用现有 `releaseLock`，不改变业务检查点。

## 7. 组件边界

新增或扩展：

- `PullTaskStandardStartService`：手动/自动启动的唯一业务入口。
- `PullTaskStandardStartTrigger`：创建事务 after-commit 自动启动触发器。
- `PullTaskExecutionDispatchProperties`：启用、轮询间隔、批量、租约、网络退避配置。
- `PullTaskExecutionDispatchScheduler`：独立单线程固定延迟调度，不做业务和 HTTP。
- `PullTaskExecutionDispatchCoordinator`：一轮 claim、逐行预占、事务外 probe、结果回写。
- `PullTaskExecutionTransactionService`：恢复租户上下文并承载父任务锁、槽位检查和状态更新。
- `PullTaskExecutionDispatchStats`：单轮 claimed/started/advanced/failed/deferred/skipped 统计。
- `PullTaskExecutionReasonCode`：`LINK_INVALID`、`LINK_PROBE_INCOMPLETE`，禁止原因魔法字符串。

不新增 Repository，不新增表，不修改协议层。

## 8. Mapper 变更

`PullTaskMapper`：

- `selectLifecycleForUpdate(taskId)`：当前租户内按主键锁父任务，不带 `LIMIT`。

`PullTaskGroupExecutionMapper`：

- 收紧 `claimDue`：增加父任务 `EXECUTING` 与 `stage=LINK_VALIDATION` 条件。
- `countExecutingSiblings(taskId, excludedId)`：父任务锁内复核并发槽位。
- `startClaimed(id, expectedVersion, lockOwner, now)`：`WAIT_START -> EXECUTING`，写 `started_at`，版本加一但保留租约。
- `advanceClaimed(...)`：有效链接推进到 `MANAGER_JOIN`，同时释放租约。
- `failClaimed(...)`：明确失效进入 `FAILED`，写原因和 `finished_at`，同时释放租约。
- `deferClaimed(...)`：网络不可达时写原因和 `next_run_at`，同时释放租约。

所有单租户方法保留租户拦截器；只有跨租户 claim/select 继续使用 `@InterceptorIgnore`。

## 9. 错误处理

- 父任务状态或版本竞争：跳过当前候选，不重试旧版本写入。
- 租约过期：回写影响 0 行，视为失去所有权，禁止再写。
- 邀请页明确无资料：本行 `FAILED/LINK_INVALID`，不重新匹配 TXT。
- 邀请页不可达：`LINK_PROBE_INCOMPLETE`，按配置退避后重试，不记失败。
- 单行异常由协调器记录并继续处理其他租户/任务；周期线程外层兜底，异常不能取消后续轮询。
- 日志只记录 tenantId/taskId/executionId/stage/reasonCode，不记录完整群链接、邀请码或号码。

## 10. 测试门禁

### H2 Mapper / 事务集成

- 未启动父任务不能被 claim；其他租户可被跨租户扫描。
- 两个 worker 分别租到同父任务候选时，父任务锁复核后只有一条进入 `EXECUTING`。
- 同父任务当前行失败后，下一条才能获得槽位。
- 旧版本或过期 lockOwner 的 advance/fail/defer 均影响 0 行。
- 状态回写与释放租约原子完成。
- `TenantContext` 在每行处理后恢复，不泄漏到下一租户。

### Service 单测

- 手动启动冻结管理账号数并推进父任务状态；重复启动幂等。
- 无可用管理账号时保持 `WAIT_START`。
- `autoStart=1` 仅 after-commit 触发；回滚不触发。
- probe 三态分别推进、失败、延后。
- 调度器禁用时不创建线程，启用时单轮异常不终止后续周期。

### 回归

- 普通群链接数据层、创建链路聚焦测试全部通过。
- Mapper XML 使用 H2 MySQL 模式真实加载；XML 语法校验通过。
- 全量非真库测试按仓库既有排除命令运行，并如实记录既有失败。

## 11. 验收边界

EX-01 完成后：任务可以手动或自动启动；后台可跨租户、安全地一次推进一个群链接执行行；链接失效和网络不完整有真实、可恢复结果；有效链接停在 `MANAGER_JOIN` 等待 EX-02。

这不等于真实拉群闭环完成。只有 EX-02～EX-08 与 QA-01 完成后，M1 才可验收。
