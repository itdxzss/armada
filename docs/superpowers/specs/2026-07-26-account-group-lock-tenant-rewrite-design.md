# 账号分组锁行查询租户改写修复设计

日期：2026-07-26
范围：`armada-api` 账号分组拆分、合并及拉群营销任务启动

## 背景与根因

第一套测试环境中，账号分组拆分和合并稳定返回“系统繁忙”。后端日志确认
`AccountGroupMapper.selectByIdsForUpdate` 的原始合法 SQL：

```sql
ORDER BY id
FOR UPDATE
```

经 MyBatis-Plus `TenantLineInnerInterceptor` 注入当前租户条件后，被重新排列为：

```sql
FOR UPDATE
ORDER BY id
```

MySQL 因子句顺序非法抛出 `SQLSyntaxErrorException`，全局异常处理器再将其转换为
`50000 / 系统繁忙`。异常发生在事务内第一个锁行查询处，账号迁移和分组软删除尚未执行。

## 目标

- 保留 `FOR UPDATE`，继续串行化同一分组上的结构变更和营销任务启动。
- 保留 `ORDER BY id`，让多分组事务按统一顺序取得行锁，降低死锁风险。
- 保持租户隔离，租户 ID 只能来自服务端 `TenantContext`，不得由前端传入。
- 修复拆分、合并以及共用该 Mapper 的拉群营销任务启动。
- 不改表结构、不增加配置项、不改变接口请求或响应。

## 方案比较

### 方案一：单条 SQL 关闭租户改写并显式限定租户（采用）

为 `selectByIdsForUpdate` 添加 `@InterceptorIgnore(tenantLine = "true")`，Mapper 方法新增
`tenantId` 参数，XML 显式写入 `tenant_id = #{tenantId}`。两个调用方均从
`TenantContext.get()` 取得当前租户。

该方案沿用 `AccountImportDetailMapper.selectQueuedForUpdate` 已验证的项目模式，只绕过
存在兼容问题的锁行 SQL，不影响其他 Mapper 的全局租户隔离。

### 方案二：删除 `ORDER BY id`（不采用）

可以绕开当前语法错误，但失去明确的多行加锁顺序，并发拆分、合并和任务启动更容易发生死锁。

### 方案三：升级或替换 MyBatis-Plus SQL 解析组件（不采用）

可能从框架层解决问题，但影响所有 SQL，回归面明显大于本次缺陷，不适合作为测试环境故障的
最小修复。后续如统一升级依赖，应另行验证全量 Mapper SQL。

## 详细设计

### Mapper 接口

`AccountGroupMapper.selectByIdsForUpdate`：

- 增加 `@InterceptorIgnore(tenantLine = "true")`；
- 参数调整为 `tenantId` 与 `groupIds`；
- Javadoc 明确租户 ID 来自 `TenantContext`，以及关闭自动改写的原因。

### Mapper XML

锁行查询保持以下固定结构：

```sql
SELECT *
FROM account_group
WHERE tenant_id = #{tenantId}
  AND deleted_at IS NULL
  AND id IN (...)
ORDER BY id
FOR UPDATE
```

### 调用方

以下调用点传入 `TenantContext.get()`：

- `AccountGroupServiceImpl.lockExistingGroups`；
- `GroupPullMarketingTaskServiceImpl.lockTaskAccountGroups`。

HTTP 鉴权链路已在进入业务方法前建立 `TenantContext`。若上下文缺失，显式 SQL 不会命中真实
租户行，调用方按“分组不存在”失败，不能跨租户读取或加锁。

## 测试设计

先补真库 DbTest 复现修复前的 SQL 语法异常，再实施代码修改：

1. 在租户上下文中调用 `selectByIdsForUpdate`，确认 SQL 可执行且按 ID 升序返回；
2. 准备其他租户分组 ID，确认查询不会返回或锁定其他租户数据；
3. 更新现有 Service 单测中的 Mapper 参数断言，保证两个业务调用点使用当前租户 ID；
4. 运行账号分组聚焦测试、相关真库 DbTest，最后运行 `mvn test`。

真库测试只在已确认允许使用的本地 DbTest 环境执行；若 `.env` 指向共享或远程数据库，执行前
必须再次确认目标环境。

## 风险与回滚

- 风险集中在遗漏调用点或显式租户条件缺失；编译、Mockito 交互断言和真库租户隔离测试共同覆盖。
- 不涉及 Flyway、数据迁移或接口契约。
- 回滚只需恢复 Mapper 方法签名、XML 和两个调用点；没有数据层回滚动作。
