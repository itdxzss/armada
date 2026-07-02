# 不可用 IP 定时重检闭环

## 变更概述
- 协议层账号状态事件出现 `PROXY_FAILED` 时,armada 不再把账号当前绑定 IP 释放为空闲,而是标记为不可用并解绑。
- 新增不可用 IP 定时重检任务,默认每 15 分钟拉取一批不可用 IP 复用现有检测逻辑。
- 检测成功的不可用 IP 恢复为空闲;检测失败继续保持不可用,等待下一轮。

## 影响模块
- `account`:账号状态事件 `PROXY_FAILED` IP 释放分支。
- `resource`:IP 代理池状态更新、不可用候选查询、定时任务。

## 数据库变更
- 无 schema 变更。
- 新增 MyBatis SQL:
  - 按账号当前绑定标记 IP 不可用。
  - 跨租户拉取不可用 IP 重检候选。

## API 变更
- 无前端/HTTP API 变更。
- `IpProxyService` 新增内部服务方法:
  - `recheckUnavailableProxies(int batchSize)`
  - `markBoundProxyUnavailableByAccount(Long accountId, long occurredAt, String reason)`

## Redis 变更
- 无。

## 关键约束
- `PROXY_FAILED` 标记只命中仍处于 `IN_USE` 且 `bound_at <= event.occurred_at` 的当前绑定,避免迟到事件误伤新分配 IP。
- 定时任务线程跨租户扫描候选后,按每条 IP 的 `tenant_id` 恢复 `TenantContext` 再写回检测结果。
- 单条 IP 重检异常只计入本轮失败,不阻塞同批其它 IP。

## 回滚方案
- 回滚本次 Java/XML 配置改动即可恢复旧行为。
- 如需临时关闭定时重检,设置 `IP_PROXY_UNAVAILABLE_RECHECK_ENABLED=false`。
