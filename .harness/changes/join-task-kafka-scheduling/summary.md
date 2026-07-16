# 变更记录：批量进群改为 Kafka 到期调度

- 日期 / 分支 / worktree: 2026-07-16 / `1.0.1-snapshot` / 未创建 worktree，直接修改当前 checkout
- 需求来源: 本轮用户确认；多个账号并行，每个账号按任务记录的执行间隔区间随机等待；Web、Android 同时接入
- 状态: 进行中（代码与本地真库验证已完成，自动数据模型文档待刷新）

## 目标（一句话）

移除固定 16 个账号 lane 和同步 HTTP 等待，改为每账号独立排期、到期写协议 outbox、Web/Android 经
Kafka 执行并统一回写结果。

## 缺口拆解 / 任务清单

- [x] 任务启动时一次激活每个账号的首条明细，不按固定线程数截断账号并行度。
- [x] 新增独立单线程到期调度器；线程只扫描数据库和写 outbox，不等待 Kafka/WhatsApp。
- [x] 同任务同账号只允许一条 SUBMITTED；终态后按任务间隔闭区间随机激活下一行。
- [x] Web 命令路由 `protocol.master.commands.v1`，Android 命令路由 `protocol.android.commands.v1`。
- [x] Web/Android 统一向 `protocol.group.events.v1` 发布 `group.join_result_reported`。
- [x] 协议结果按 `resultId + commandId + attemptNo` 幂等回写。
- [x] outbox 传输重投继续复用原 commandId；进入 DEAD 后转为可重试 `KAFKA_PUBLISH_FAILED`。
- [x] Flyway 增加幂等列/索引检查和数据库列注释。
- [x] 补齐 Java/Mapper/协议层关键状态、事务边界和异常语义注释。
- [x] 在用户确认的本地 MySQL 临时 schema 从空库执行 55 个 Flyway 迁移及 Mapper DbTest。
- [ ] 按真库 information_schema 转储流程重新生成数据模型文档（禁止手改生成物）。

## 关键设计决策

- Kafka 负责可靠传输，不负责业务间隔。`join_task_result.next_execute_at` 保存每个账号下一行的业务到期时间。
- 调度器保持独立单线程，避免与其它定时任务互相阻塞；单线程不限制账号并行，单轮最多可批量入队 500 条。
- `status` 表达业务结果，`dispatch_state` 表达 WAITING/SUBMITTED/TERMINAL 传输阶段，避免把 Kafka 过程计入业务失败。
- outbox 与明细 SUBMITTED 必须同事务提交；Kafka 只能在事务提交后发送，禁止孤儿命令。
- Web/Android 都先按 commandId 持久化执行结果，再发布 Kafka；源消息重放只补发缓存结果，不重复执行同一命令。
- 否决“Kafka consumer sleep 控制间隔”：会长期占用消费分区并阻塞同分区其它账号命令。
- 否决“固定 16 个账号 lane”：账号总数会被进程内固定执行槽限制，不符合多账号并行业务要求。

## 状态与幂等

- `PENDING+WAITING`: 当前账号已激活且等待到期；未轮到的同行 `next_execute_at=NULL`。
- `PENDING+SUBMITTED`: outbox 已落库，等待传输或协议结果；该账号其它行不得派发。
- `SUCCESS/FAILED+TERMINAL`: 当前行结束，状态机才按随机间隔激活同账号下一行。
- 传输重投复用 commandId 和 attemptNo；业务重试清空旧 commandId，attemptNo 在下次派发时加一。
- 协议 PROCESSING 超时表示副作用结果未知，同一 commandId 不再执行，回写可重试 `JOIN_RESULT_UNCONFIRMED`。

## 数据库变更

- Flyway: `armada-api/src/main/resources/db/migration/V055__join_task_kafka_dispatch.sql`
- `join_task_result` 新增 `dispatch_state`、`next_execute_at`、`command_id`、`attempt_no`。
- 新增 `idx_jtr_dispatch` 和 `idx_jtr_task_account`。
- 运维执行参考见 `db-migrations.sql`；回退脚本见 `rollback.sql`。

## 验证（evidence-before-done）

- `cd armada-api && mvn -q -DskipTests package`: 通过（2026-07-16，补注释及 Flyway 幂等改造后）。
- 本地 MySQL 从空库迁移: Flyway 55/55 成功执行到 V055；本轮临时 schema 已删除。
- `./dbtest.sh 'JoinTaskMigrationDbTest,JoinTaskMapperDbTest,JoinTaskResultMapperDbTest,JoinTaskDispatchMapperDbTest' ...`:
  20 tests，0 failures，0 errors，0 skipped。
- 新增 DbTest 红→绿发现并修复两处 MyBatis-Plus 租户改写后的 MySQL 锁行语序错误：
  批量抢占改为显式 tenantId 隔离，主键锁定去掉冗余 `LIMIT 1`。
- `mvn -q -Dtest=JoinTaskDispatchTransactionServiceTest test`: 3 tests 全部通过。
- 当前本地 `armada` schema 的 V055 是开发中旧校验和；本轮未执行 Flyway repair，避免仅改历史校验和却掩盖 DDL 差异。
- Armada 聚焦测试：10 个相关测试类通过（账号协议查询、群事件消费、outbox、间隔/链接策略、结果状态机、调度器）。
- Web: 12 个 Jest suite、130 tests 全部通过；`npm run lint`（`tsc --noEmit`）通过。
- Android: `go test ./internal/armada` 通过；`go vet ./internal/armada` 通过。
- Web/Android 都有“幂等状态 TTL 必须大于 PROCESSING 窗口”的红→绿边界测试。
- 三仓库 `git diff --check`: 通过；`git diff --cached --name-only` 均为空。

## 部署

- commit / 环境 / 部署后验证结果: 未 commit、未部署；用户要求保留本地未提交 diff。

## 回滚

- 先关闭 `JOIN_TASK_DISPATCHER_ENABLED=false` 并确认没有新进群派发，再回退应用代码。
- 数据库字段和索引回退见 `rollback.sql`；脚本会先检查对象存在性。
- 已经发布的 Kafka 命令不能通过删列撤回，回滚前必须先让 outbox/消费链路静止。

## 遗留 / 跟进

- 本阶段按用户确认不实现服务重启后的存量 RUNNING 任务恢复。
- 本阶段不增加结果 watchdog；协议结果和 outbox DEAD 是当前两条收敛路径。
- `.harness/wiki/数据模型.md` 是真库生成物，未手工写入新字段；真库迁移验证后再运行生成脚本。
