# 拉群营销候选账号查询去锁设计

## 背景与目标

任务 157 在资源分配阶段持续失败。线上 SQL 显示 MyBatis-Plus 租户插件把候选建群账号查询改写为 `FOR UPDATE ORDER BY ... LIMIT 1`，导致 MySQL 语法错误。即使只修正尾句顺序，这条联表锁定读仍可能锁住 `account`、`account_state`、`account_group` 和账号占用表中扫描到的索引记录，锁范围大于业务需要。

本次目标是移除建群账号和营销账号候选查询的联表 `FOR UPDATE`，继续依赖已有的精确并发闸门，恢复任务分配并缩小锁范围。

## 方案比较

### 方案一：候选查询无锁，使用现有唯一键原子抢占（采用）

- 建群账号候选查询改为普通 `SELECT`。
- 候选返回后，继续通过 `INSERT IGNORE marketing_account_occupancy` 抢占账号。
- `marketing_account_occupancy` 的唯一键 `(tenant_id, account_id)` 决定抢占胜负；返回 0 时回滚本轮并重试其他候选。
- `group_pull_marketing_execution` 的唯一键 `(tenant_id, active_builder_account_id)` 保留第二层保护。
- 营销账号候选查询改为普通 `SELECT`；同一任务的分配仍由任务行锁串行化，账号额度仍由精确的额度行锁控制。

优点是锁范围最小，并复用现有并发控制。代价是并发任务可能同时读到同一建群账号，但已有原子抢占和重试会收敛冲突。

### 方案二：保留联表锁，显式租户并跳过租户插件

可以沿用释放 SQL 的做法，显式传入 `tenant_id` 并使用 `InterceptorIgnore`，只解决 SQL 改写错误。缺点是联表锁范围仍大，且当前索引不能同时满足分组过滤与创建时间排序，可能锁住多条扫描记录。

### 方案三：普通查询候选 ID，再精确锁定账号主键

先无锁选 ID，再执行 `SELECT id FROM account WHERE tenant_id = ? AND id = ? FOR UPDATE`。锁范围比方案二小，但仍需重新校验所有候选条件；同时现有占用唯一键已经能够完成原子抢占，增加一次查询没有提供额外正确性收益。

## 代码设计

修改范围仅包含两条候选账号查询及其命名：

- `selectBuilderCandidateForUpdate` 改名为 `selectBuilderCandidate`，删除 SQL 尾部 `FOR UPDATE`。
- `selectMarketerCandidateForUpdate` 改名为 `selectMarketerCandidate`，删除 SQL 尾部 `FOR UPDATE`。
- 更新 `GroupPullMarketingAllocator` 调用点和 Mapper 注释。

不修改以下精确锁：

- 统一营销任务行锁和拉群扩展任务行锁。
- 营销账号额度统计行锁。
- 料子选择锁及释放流程锁。

## 分配数据流

1. 事务锁定当前任务行，保证同一任务的分配串行执行。
2. 普通查询选出一个符合状态、分组和占用条件的建群账号。
3. 使用占用表唯一键原子抢占；冲突则回滚并按现有上限重试。
4. 普通查询选出当前任务专属营销分组内仍有额度的营销账号。
5. 使用账号额度统计行锁原子预留额度。
6. 按现有流程预留料子、创建执行记录并提交。

## 错误与并发处理

- 建群账号抢占返回 0：保持现有 `RETRY_BUILDER` 逻辑，不产生重复占用。
- 活跃建群账号唯一键冲突：保持现有 `DuplicateKeyException` 重试逻辑。
- 营销账号额度不足：保持现有条件更新和事务回滚逻辑。
- 任务状态或资源状态改变：任务行锁后的复核逻辑保持不变。

## 测试与验收

- SQL 结构测试确认两条候选查询都不包含 `FOR UPDATE`，并保留排序和 `LIMIT 1`。
- Mapper/分配器测试确认方法改名后调用链可编译，账号抢占冲突仍会回滚重试。
- 在 MyBatis-Plus 租户插件开启的 H2 MySQL 模式下执行两条候选查询，确认租户隔离和 SQL 语法有效。
- 运行相关单测、XML 校验和 Maven 打包。
- 部署第二套环境后，确认任务 157 出现“资源分配完成”或明确业务阻塞状态，不再出现候选账号 SQL 语法错误。

## 非目标

- 不调整调度频率和任务并发上限。
- 不重构整个资源分配事务。
- 不改变账号、营销额度或料子的业务筛选条件。
- 不修改其他 `FOR UPDATE` 查询。
