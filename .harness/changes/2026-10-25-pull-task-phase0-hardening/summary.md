# 变更概述：拉群止血（第 0 阶段）

- 日期 / 分支 / 工作目录：2026-10-25 / `1.0.3-snapshot` / `armada` 主工作树
- 需求来源：用户确认拉群重设计方案后，先在老引擎上修会让群变慢或卡住的问题；新引擎另行实施。
- 状态：已实现、已测试，本地提交，未推送、未部署。

## 问题与解法

| 问题 | 解法 | 提交 |
| --- | --- | --- |
| 自动暂停的执行行仍是 EXECUTING，照占并发名额；暂停数到上限后整个任务停摆 | 名额统计只数未暂停的行；暂停标记由 Java 传参 | a271618c、3fe9abd8 |
| 未知结果对账按 id 取 100 行，终态行的永久 UNKNOWN 挤掉进行中的行 | 先查进行中的行，剩余额度再给终态行；终态行仍可补收敛晚到结果 | 20f0dc54 |
| 待启动行抢不到名额只放锁、不推后，老任务的等待行每轮都排在启动池最前 | 抢不到名额时推后 5 秒（`SLOT_WAIT_BACKOFF_MS`） | 9a8b3f22 |
| 回调 CAS 要求执行行未锁，调度轮次慢时 Kafka 1s×3 重试落空进死信 | 回调读到执行行被调度租约占用时抛 `RetryableConflictException`，共享错误处理器对它用指数退避（1s 起翻倍，单次 ≤10s，累计 ≤60s）；其余 CAS 落空保持短重试 | a4645c9f |
| 注销发送前用本机时间比对“轮开始 + 30s”租约，慢轮必然过期，伪造 UNKNOWN 并暂停 | 持有者（每进程唯一 UUID）与版本在行锁下未变即视为未被接管，去掉时钟比对 | 321bfdc0 |
| 注销已接受后一次查询异常把记录改写成 UNKNOWN 并暂停 | 查询无结果且原状态为已接受时保持已接受，继续核验到原有截止时间 | 321bfdc0 |
| 任务列表按 task_id 统计可用拉手，`pull_task_group_account` 无对应索引 | V218 幂等新增 `idx_pull_task_group_account_task (tenant_id, task_id, role_type, availability_status)` | f3f1e5f3 |

## 未处理（需确认或留给新引擎）

- 管理员入群待审批（wait_resource_type=4）不被调度扫描：2026-08-08 用户确认的设计（不自动轮询，防止重复提交入群申请）。缺口：人工审批后“恢复群”无效，只能放弃。
- 注销前管理员实时核验查询异常即暂停：永久注销不可逆，保留保守行为。
- 推进池吃满 100 行时启动池为 0；波次收集每秒占一个推进名额。
- `.harness/wiki/数据模型.md` 未重新生成（生成脚本依赖真库 information_schema 转储，且文档已落后于 V209/V210）。

## 影响模块

- `task/mapper`：`PullTaskMapper.xml` 名额统计；`PullTaskExecutionSlotClaim.Policy` 增加 `runningManualPaused`
- `task/scheduler`：执行启动退避、未知结果对账顺序、注销发送栅栏、注销结果记录
- `task/service/impl`：10 处回调 CAS 落空改走 `PullTaskExecutionCasFailure`
- `platform/kafka/config`：错误处理器按异常选择退避
- `shared/exception`：新增 `RetryableConflictException`（继承 `IllegalStateException`）

## 数据库变更

- `V218__pull_task_group_account_task_index.sql`：纯加索引，information_schema 守卫幂等。回滚见 `rollback.sql`。
- 已在本地 MySQL 8.4 用临时表副本验证：可执行、重复执行跳过、EXPLAIN 选中新索引；临时表已删除。

## API / 前端 / Redis / Kafka 变更

- 无 API、Redis、Topic 变更。Kafka 只改消费端错误重试策略，仅影响抛 `RetryableConflictException` 的消息。
- 风险：冲突重试期间会阻塞同分区后续消息，最长约等于一轮调度持锁时长（上限 60s）；此前这类消息直接进死信并等 60s 对账兜底。

## 验证

- 每项先红后绿；相关测试类全部通过（JDK 17）。
- 全量非真库测试：5538 个，34 个失败/报错；在改动前提交 fae19e83 上跑同样的类，失败名单完全一致，无新增。
- 本机默认 JDK 27 下 Mockito 无法模拟具体类，会出现大量与改动无关的报错，测试需用 JDK 17。
