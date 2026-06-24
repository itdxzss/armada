# 变更：进群任务「批量删除」（软删）

## 变更概述
任务中心「进群任务」列表对齐菜单需求，补齐 P0「批量删除」：勾选一条或多条任务 → 二次确认 →
批量软删（置 `deleted_at`）。软删仅从列表隐藏，明细 / 统计数据保留可恢复。任意状态均可删
（DRAFT/RUNNING/PAUSED/STOPPED/DONE/FAILED）——需求原文「选一条或多条→删」无状态限制，
且 worker 每趟开头 `selectByTenantAndId`（带 `deleted_at IS NULL`）查不到即干净收手，不产生僵尸执行。

> 同批确认的另一条 P0「表单刷新」核查为**已实现**（列表工具条 `刷新` 按钮 → `loadTasks`），本次未改动。

## 影响模块
- 后端：`wheel-api-domain`（JoinTaskMapper + XML）、`wheel-api-tenant`（Service / Controller / DTO）
- 前端：`wheel-saas-web`（api/join-task.ts、JoinTaskList.vue）

## 数据库变更
**无 Flyway 迁移**。复用 `join_task.deleted_at`（V045 已建，含索引 `idx_join_task_tenant(tenant_id, deleted_at, id)`），
列表 / 详情 mapper 既有 `deleted_at IS NULL` 过滤天然生效。

## API 变更
新增 `POST /api/tenant/join-tasks/batch-delete`
- 权限：`tenant:pull_task:edit`（复用任务中心，无新种子）
- 请求体：`{ "ids": [number] }`（1..200）
- 响应：`{ "code":0, "data": { "deleted_count": number } }`
- 校验：ids 为空 / 超 200 → `VALIDATION`（业务异常，非 HTTP 200 吞错）

## Redis 变更
无。

## 关键约束
- 软删，非物理删；幂等（已删行不重复改、不计数）；单条 `UPDATE ... id IN(...)` 整批，显式 `tenant_id` 隔离。
- 批量上限 200（`BATCH_DELETE_MAX` 常量，与任务模板批量删除一致）。
- 翻页 / 刷新清空前端勾选（行已变，旧选作废）。

## 测试
- 后端单测 `TenantJoinTaskServiceTest`：26（+3：空/超限→VALIDATION、happy path 计数）
- 后端真库 `JoinTaskMapperDbTest`：11（+1：软删隐藏/查不到/幂等/跨任务安全，已连测试 RDS 跑通）
- 后端 tenant 全模块：556 绿，BUILD SUCCESS
- 前端 `JoinTaskList.test.ts`：19（+3：未勾禁用/勾选删+刷新、取消不调、全选）；前端全量 471 绿；vue-tsc clean

## 回滚方案
纯增量改动，无 DB 迁移。回滚 = `git revert` 本次提交即可；已软删的数据如需恢复，
执行 `UPDATE wheel_tenant.join_task SET deleted_at = NULL WHERE id IN (...) AND tenant_id = ?`。
