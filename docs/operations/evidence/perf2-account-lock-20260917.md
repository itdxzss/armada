# perf2 账号上线状态回写延迟取证

排查日期：2026-09-17。目标：第二套 3.110.124.52，数据库 armada_perf。仅只读取证，未改业务数据、配置或重启服务。

## 结论及证据边界

现场日志、真实批次参数的 EXPLAIN、数据库隔离级别和事务代码共同高度指向批量上线长事务的扩大锁范围：`AccountOnlineCommandServiceImpl.onlineBatch` 先批量更新 account_state，再在同一事务内分配代理和写 outbox。470 账号批次对应的 UPDATE 当前执行计划为扫描 PRIMARY 全索引，估计 2226 行；数据库默认为 REPEATABLE-READ。

未取得故障当时的 blocking_trx_id：wheel 用户缺少 performance_schema.data_lock_waits SELECT 和 PROCESS 权限，慢查询日志关闭，后续查询时故障长事务已结束。因此结论是历史日志和执行计划的交叉归因，不是保存了当时锁等待图的直接证明。EXPLAIN 只查看计划，未实际执行 UPDATE；计划可能随统计信息变化。

## 日志时间线（UTC，北京时间加 8 小时）

同一请求 traceId=a9c13a57c522473494668a599d815d94，HTTP 线程 http-nio-8080-exec-6。

| 时间 | 事实 |
|---|---|
| 08:14:49.955 | 开始 QUERY 批量上线，最终匹配 1294，提交 1237，跳过 57 |
| 08:14:49.969—08:15:18.438 | 第一批 500；代理分配 25781ms |
| 08:15:18.679—08:16:17.561 | 第二批 470；代理分配 55725ms |
| 08:15:21.026 | 状态线程 #5-1 收到账号 502 的 PROXY_FAILED |
| 08:15:21.106 | 状态线程 #5-2 收到账号 765 的 ONLINE |
| 08:15:21.138 | 状态线程 #5-3 收到账号 923 的 ONLINE |
| 08:15:21.234 | 状态线程 #5-0 收到账号 821 的 ONLINE |
| 08:16:12.242—08:16:13.643 | 四个状态线程报 CannotAcquireLockException / Lock wait timeout exceeded；栈落在 AccountStateEventServiceImpl.java:155 的 SELECT ... FOR UPDATE |
| 08:16:17.658—08:16:57.020 | 第三批 267；代理分配 36242ms |
| 08:16:57.025 | 整次批量编排完成 |

## 批次与数据库证据

从 protocol_command_outbox 的 payload_json.accountId 只读还原：

| 批次 | 数量 | accountId 范围 |
|---|---:|---|
| batch_a739179b653b448c934815c73cb091f4 | 500 | 2—1269 |
| batch_520240945315467e96593916588d71e8 | 470 | 1274—1977 |
| batch_0d8d6e83e304418a948919512decd761 | 267 | 1978—2589 |

被阻塞的 502/765/923/821 均低于第二批最小 ID，说明不能仅用同一账号的正常并发更新来解释。

将第二批真实 470 个 accountId 放入以下 EXPLAIN（不执行更新）：

```sql
EXPLAIN UPDATE account_state
SET desired_login_state=1, updated_at=updated_at
WHERE tenant_id=1 AND account_id IN (...第二批真实 ID...);
```

返回 `type=index, key=PRIMARY, rows=2226, Extra=Using where`。存在 `uq_tenant_account(tenant_id,account_id)`，但该计划未采用它逐账号定位。

数据库：`@@global.transaction_isolation=REPEATABLE-READ`、诊断会话相同；`innodb_lock_wait_timeout=50`；`slow_query_log=OFF`。应用静态配置和相关方法未发现隔离级别覆盖。

## 代码入口

- AccountOnlineCommandServiceImpl.java:287：onlineBatch 整体 @Transactional。
- 同文件 :292：先 updateDesiredLoginStateOrThrow，对整批 account_state 写锁。
- 同文件 :294—297：仍在事务内调用 enqueueOnlineBatch / 代理分配。
- AccountStateMapper.xml:79：updateDesiredLoginStateInternal 批量 UPDATE。
- AccountStateEventServiceImpl.java:155：消费者通过 selectByTenantAndAccountIdForUpdate 争用状态行。
- IpProxyServiceImpl.java:442：代理分配沿用外层事务，470 次 CAS 分配现场耗时 55.7 秒。

群同步走独立 topic/group；它确实有 wa_group_participant 死锁，但未据此认定其是 account_state 的直接持锁方。

## 已验证的用户可见影响

截图前三个账号 2587/2588/2589 在 08:17:03 已由协议层发布 ONLINE。后端分别到约 08:23:23 / 08:21:32 / 08:22:06 才收敛在线。08:23:29 数据库复查均 login_state=1。当时全库待上线已降至 78。时间较晚的统计不代表当前仍有 78。

## 建议修复方向（尚未实施）

使 account_state 批量更新按唯一键精确定位，缩小并限制每个批次事务的范围，避免在持有大批账号锁期间完成数十秒代理分配。不能直接删除状态事件 FOR UPDATE；它用于保护事件时间水位检查与更新的原子性。修复需覆盖批次间并发回写、旧事件乱序及代理分配失败的一致性验证。
