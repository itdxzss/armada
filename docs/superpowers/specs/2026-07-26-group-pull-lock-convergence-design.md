# 拉群营销显式行锁收敛设计

## 背景

拉群营销资源分配最初在候选账号、任务、任务扩展、材料、账号额度、执行记录和释放候选上使用了多处
`SELECT ... FOR UPDATE`。候选账号联表锁已经移除，但材料查询仍会被租户插件改写成 MySQL 非法语序；同时现有
事务存在反向锁序：

```text
释放流程：marketing_task -> group_pull_marketing_task -> group_pull_marketing_execution
执行收口：group_pull_marketing_execution -> group_pull_marketing_task
群名冻结：group_pull_marketing_execution -> marketing_task
```

高并发下，上述 `task -> execution` 与 `execution -> task`、`extension -> execution` 与
`execution -> extension` 具备形成死锁环的条件。

## 目标

- 拉群营销运行期只保留 `marketing_task` 主键行作为任务级显式互斥锁。
- 删除重复的任务扩展、材料、账号额度和执行记录预读锁。
- 使用已有条件更新、唯一键和影响行数校验决定并发胜负。
- 释放流程不再一次锁定多条执行记录，并避免 MySQL RR 旧快照导致误释放。
- 不改变任务状态、五并发上限、账号筛选顺序、额度口径、材料顺序和释放业务语义。

## 方案

### 保留任务级互斥锁

保留 `selectTaskForUpdate`。该查询按 `marketing_task.id` 主键精确锁定一行，用于串行化同一任务的资源分配、
启动、暂停、恢复、请求释放和群名序号生成。不同任务之间不会因该锁互相阻塞。

### 删除重复预读锁

1. `selectTaskByIdForUpdate` 改为已有的普通 `selectTaskById`。拉群扩展配置启动后不可编辑；资源状态变更由
   持有任务锁的生命周期事务和带前置状态的条件更新完成。
2. `selectAvailableMaterialsForUpdate` 改为 `selectAvailableMaterials`。同一任务的分配已经由任务行锁串行；
   `reserveMaterials` 继续使用 `WHERE status = 1`，且影响行数必须等于候选数。
3. `selectAccountStatForUpdate` 改为普通查询。统计行由 `(tenant_id, task_id, account_id)` 唯一键保证唯一，
   实际额度预留继续由带上限条件的原子 `UPDATE` 裁决。
4. 删除 `selectExecutionByIdForUpdate`。Finalizer 普通读取执行快照，使用
   `markExecutionTerminal WHERE execution_status IN (1, 2)` 抢占唯一结算权；影响行数为零时不执行任何后续副作用。
5. 群名冻结事务先锁 `marketing_task`，再普通读取执行记录，最后用
   `saveGroupNameIfAbsent` 的状态条件保存群名，统一成 `task -> execution` 顺序。

### 释放流程改为条件取消

`selectCancelableExecutionsByTenantForUpdate` 改为租户插件保护下的普通候选查询。任务锁阻止新的执行分配；
现有 Worker、Finalizer 和释放事务通过 `cancelPreGroupExecution` 的
`group_name IS NULL AND execution_status IN (1, 2)` 条件更新竞争同一执行。

若任一候选条件取消失败，说明它在当前事务期间已经冻结群名或进入终态。本轮释放立即结束并返回 `false`，
不继续执行正式执行计数、营销命令取消或资源总释放。下一轮新事务重新读取最新快照后继续处理。已经成功取消的
执行及其材料、额度和账号占用可以随当前事务提交，释放过程保持幂等和渐进收敛。

## 最终锁顺序

```text
资源分配：task -> 精确条件 INSERT/UPDATE
群名冻结：task -> execution 条件 UPDATE
任务释放：task -> execution 条件 UPDATE
执行收口：execution 条件 UPDATE；成功路径需要时再更新 task
```

Finalizer 在更新 `task` 前已经把 execution 置为未提交终态；释放事务的普通计数会看到上一个已提交活动状态并
结束本轮，不会在持有 task 时等待该 execution，因此不再构成相互等待。

## 保留的外围锁

- 账号分组启动、拆分、合并继续按分组 ID 升序锁定最多两个或请求涉及的分组，关闭账号迁移与任务启动之间的
  检查—修改竞态。
- 创建任务和批量删除模板继续锁定模板事实源，关闭创建任务与软删除模板之间的竞态。

这些锁只位于低频生命周期操作，不属于拉群执行阶段的高频扫描锁。

## 测试

- SQL 形状测试断言拉群营销 Mapper 只剩 `selectTaskForUpdate` 包含 `FOR UPDATE`。
- H2 MySQL 模式加载真实 Mapper XML 和租户插件，执行普通材料、额度、任务扩展和释放候选查询。
- Service 测试断言 Finalizer 只有条件终态更新成功时才处理材料、额度、账号和营销目标。
- Service 测试断言释放候选取消失败时本轮立即返回 `false`，不释放任务级资源。
- Worker 测试断言群名冻结按 `selectTaskForUpdate -> selectExecutionById -> saveGroupNameIfAbsent` 顺序执行。
- 聚焦测试通过后运行 `mvn test`，并执行 XML 校验和生产打包测试。

## 非目标

- 不调整调度线程数、扫描频率或单任务五并发上限。
- 不修改数据库结构和索引。
- 不修改账号分组和模板锁语义。
- 不部署、不修改远程数据库或任务状态。
