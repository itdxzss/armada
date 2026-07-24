# 批量上线防重与代理乐观抢占设计

## 目标

解决大批量账号上线时前端 10 秒超时误报、重复点击继续写协议命令，以及后端逐账号
`SELECT ... FOR UPDATE` 和代理快照逐行更新导致同步请求耗时随账号数线性增长的问题。

本次同时修改 Armada 后端和 `wheel-saas-pure-web` 账号列表页。协议层命令格式不变，
数据库不新增表或字段，也不改变代理国家优先级和批量上线“已受理不等于已在线”的语义。

## 当前事实

- Web Axios 全局超时为 10 秒，批量上线接口没有单独覆盖超时时间。
- 批量上线超时后，浏览器中断请求并显示“批量登录失败”，但 Armada 会继续完成代理分配和 outbox 入队。
- 批量上线按钮只在当前请求进行期间禁用；超时退出后可立即再次提交相同范围。
- 单账号上线已有 30 秒冷却，批量上线没有对应冷却。
- Armada 批量上线按最多 500 个账号调用一次代理分配事务。
- 代理分配先释放旧绑定，再逐账号执行 `SELECT ... LIMIT 1 FOR UPDATE`。因为选中的代理直到循环结束才统一改成 `IN_USE`，查询还要携带不断增长的已选代理 `NOT IN`。
- 代理分配完成后，`account_state` 的代理展示快照按账号逐行更新。
- `VERIFYING` 事件在 Armada 中映射为 `login_state=PENDING_ONLINE`；数据库没有独立的 VERIFYING 登录态。
- 现有批量预估和执行不会按 `PENDING_ONLINE` 或 `ONLINE` 跳过账号，重复请求会再次释放代理、生成 attempt、写 outbox 并请求协议层。

## 总体设计

批量上线采用三层保护：

1. Web 在真正提交上线请求时启动 30 秒批量上线冷却，降低同一页面的重复操作概率。
2. Armada 把 `PENDING_ONLINE` 和 `ONLINE` 作为用户手动上线的幂等终点，预估和执行阶段均跳过；命令服务再使用条件更新作并发兜底。
3. 代理池不再逐账号悲观锁定候选行，改为普通查询候选代理后按 100 条一组执行带 `status=IDLE` 条件的批量 CAS UPDATE。

单账号手动上线与批量上线使用相同的状态防重规则。代理失败自动重登、抢登续上线和删除代理后的系统重登保留现有恢复能力，不被用户手动入口的幂等规则阻断。

## 前端行为

### 30 秒批量上线冷却

- 冷却仅作用于“批量登录”，不阻止批量离线、迁移分组、导出等其它操作。
- 冷却从用户确认后、真正发出批量上线请求前开始计算，不把预估和确认弹窗时间计入。
- 请求进行期间继续由 `batchSubmitting` 防止重复提交；请求在 10 秒超时后，批量登录菜单项继续禁用剩余冷却时间。
- 菜单项在冷却期间显示 `批量登录(29s)` 形式的剩余时间。
- 本次冷却属于当前页面会话状态；页面刷新后由后端状态防重承担最终保护。

### 超时提示

- 只把 Axios `ECONNABORTED`、`ETIMEDOUT` 或等价 timeout 错误识别为“请求结果未知”。
- 批量上线超时时使用 warning 提示：`正在上线，请稍后`。
- 超时后主动刷新账号列表，让已经进入 `PENDING_ONLINE` 或收到协议状态事件的账号及时显示。
- HTTP 业务错误、鉴权错误、参数错误和非超时网络错误仍显示真实错误或“批量登录失败”，不得把所有异常伪装成正在上线。
- 保留现有 10 秒 Axios 超时，不通过延长等待掩盖同步处理问题。

## 账号状态防重

### 批量预估与执行

批量目标查询补充 `login_state`。以下账号不进入代理分配：

- `login_state=PENDING_ONLINE`：包含 Armada 已入队待上线和协议 VERIFYING。
- `login_state=ONLINE`：已经在线。

批量跳过原因新增 `ALREADY_PENDING` 和 `ALREADY_ONLINE`，预估和最终结果中的 `executable`、`skipped` 与原因数量保持一致。原有封禁、解绑、抢登中和缺凭据跳过规则不变。

### 命令服务并发兜底

列表预估和 Java 分类只能消除正常重复请求，不能防止两个并发请求同时读取到 OFFLINE。命令服务因此在释放代理前执行一条条件更新，把本批账号预占为 `PENDING_ONLINE`：

```sql
UPDATE account_state
SET login_state = 3,
    updated_at = :now
WHERE account_id IN (:accountIds)
  AND (login_state IS NULL OR login_state = 2);
```

用户手动单账号和批量上线必须在同一外层事务内完成状态预占、代理分配、代理快照和 outbox 入队。更新行数与本批账号数不一致时，说明存在并发请求或状态已变化，本批事务整体回滚且不分配代理、不写 outbox。

这样即使两个浏览器同时提交，后到事务也会在条件更新重新检查状态后失败，不会继续请求协议层。后续代理分配或 outbox 入队失败时，事务回滚会同时恢复登录态和旧代理绑定，不留下错误的 PENDING。

系统恢复来源不套用“已 PENDING/ONLINE 即跳过”的用户幂等规则，但仍沿用各自现有状态校验、冷却和失败补偿。

## 代理乐观抢占

### 候选查询

- 按 `(preferredRegion, allowOtherRegionFallback)` 对待分配账号稳定分组，组内保持原账号顺序。
- 每次普通 SELECT 最多读取 100 个 `IDLE` 候选代理，不使用 `FOR UPDATE`。
- 继续保留现有国家优先级：首选国家、混合池、允许回退时的其它国家。
- 正常批量上线不再传递随已选数量增长的 `NOT IN`。
- 删除代理后的系统重登仍可携带一个固定的小型排除集合，确保不会重新选择正在删除的代理；该集合不随本批已分配数量增长。

### CASE WHEN 批量 CAS

候选代理与账号形成稳定的一对一映射，代理 ID 按升序生成 SQL，单次最多 100 对：

```sql
UPDATE ip_proxy
SET status = :inUseStatus,
    bound_account_id = CASE id
        WHEN :proxyId1 THEN :accountId1
        WHEN :proxyId2 THEN :accountId2
    END,
    bound_at = :now,
    updated_at = :now
WHERE id IN (:proxyIds)
  AND status = :idleStatus
  AND deleted_at IS NULL
ORDER BY id;
```

`status=IDLE` 是乐观抢占条件。UPDATE 自身仍会对真正修改的 InnoDB 记录加排他锁，但不再提前锁定候选查询扫描到的行。
实际 Mapper SQL继续应用现有租户隔离条件；`ORDER BY id` 明确统一并发事务的更新顺序，不能只依赖 Java 入参已经排序。

Mapper 不只依赖 JDBC 更新行数判断成功映射；每轮 UPDATE 后按候选代理 ID 查询实际 `proxy_id -> bound_account_id`，只接受与预期账号一致的映射。被其它事务抢占的账号进入下一轮候选查询。

每组未完成账号最多重试 3 轮；某轮没有取得任何新绑定或最终空闲代理不足时抛出业务异常，整个代理分配事务回滚。代理分配事务使用 `READ_COMMITTED`，保证重试查询能看到其它事务已经提交的抢占结果，也能看到本事务刚标记为 `IN_USE` 的代理，因此无需维护本批已选代理 `NOT IN`。

现有外部 500 账号上线分片语义保持不变；代理 CAS SQL 在该事务内部按 100 条拆分。这样先降低 SQL 往返和锁扫描，不在本次同时改变 500 账号分片的部分成功边界。

## 代理快照与日志

- `account_state` 代理快照从逐账号 UPDATE 改为批量更新，每条最多 100 个账号。
- 快照包含多个逐行变化字段时使用 `UPDATE JOIN` 映射账号 ID、真实出口信息、国家和来源，避免为每个字段复制一组 CASE。
- 删除每账号“写入 outbox 前准备 command”的 INFO 日志。
- 保留批次汇总日志：请求数、状态跳过数、候选数、CAS 成功数、冲突数、重试轮次、分配耗时、快照更新数和 outbox 受理数。
- 单账号异常和最终未分配账号可以记录 ID；日志不得包含凭据正文、代理用户名或密码。

## Kafka Outbox 成功回写

协议命令仍按每账号一条 Kafka Record 发送，应用层最大在途窗口保持 100。Publisher 在整批开始时
一次性批量准备上线凭据和代理，避免为了窗口回写重复查询；每个窗口内的 Kafka Future 全部收敛后，
立即把该窗口结果交给 Dispatcher，再开始下一窗口。

Dispatcher 对窗口内成功结果执行一次批量状态更新：

```sql
UPDATE protocol_command_outbox
SET status = :sentStatus,
    sent_at = :sentAt,
    last_error = NULL,
    updated_at = :sentAt
WHERE command_id IN (:commandIds)
  AND status = :lockedStatus
  AND locked_by = :lockedBy
  AND locked_at = :lockedAt
  AND deleted_at IS NULL;
```

- 正常 afterCommit 主路径和兜底扫描路径都使用 `command_id` 批量回写，因此不依赖内存行是否带数据库主键。
- `locked_by + locked_at` 继续校验当前发送锁，旧 Dispatcher 不能误更新后来重新抢占的行。
- 每个窗口最多 100 条成功记录，把 1000 条全成功命令从 1000 次单行 UPDATE/提交降为 10 次批量 UPDATE/提交。
- 窗口内失败结果仍逐条更新 RETRY 或 DEAD，因为失败通常稀少，且错误原因、重试时间可能不同。
- 批量回写命中数小于成功结果数时记录批次级告警，不恢复成逐账号 INFO 日志。
- 某窗口已获得 Kafka ACK、但数据库批量回写抛异常时，停止发送后续窗口；已 ACK 行保持 LOCKED，等待现有锁过期恢复。
  Outbox 仍是至少一次投递语义，协议消费端必须继续按 `commandId` 幂等。

Dispatcher 执行器保持单线程，不在本次增加并发，也不增加账号生命周期版本号。

## 事务与失败语义

- 用户手动上线的状态预占、旧代理释放、新代理 CAS、快照更新和 outbox 插入处于同一 Spring 事务。
- `IpProxyService` 的现有事务加入外层事务，不启动独立提交。
- 任一步骤失败时全部回滚，旧代理绑定仍然有效，账号不会卡在 PENDING，outbox 不会出现没有有效代理的命令。
- outbox 提交后的 Kafka dispatch 继续在事务提交后异步触发，不进入用户请求事务。
- InnoDB 死锁或锁等待超时按一个内部上线分片整体重试；重试次数耗尽后按现有批次错误汇总返回，不无限循环。

## 数据与接口影响

- 不新增数据库表、列或索引，不需要 Flyway。
- 批量上线和预估 URL、请求 DTO、响应 JSON 结构不变。
- `skipReasons` 新增 `ALREADY_PENDING`、`ALREADY_ONLINE` 键，前端当前按开放字典接收，无破坏性契约变更。
- 单账号上线继续返回现有 `AccountOnlineVO`。已经 PENDING 或 ONLINE 时不写命令，返回 `accepted=false` 并用 `stateSource` 区分 `ALREADY_PENDING` 或 `ALREADY_ONLINE`。
- 协议 Kafka 命令结构和协议层代码不变。

## 测试设计

### Web

- timeout 错误识别测试：只有 Axios timeout 被识别为结果未知。
- 批量上线确认后立即开始 30 秒冷却，超时后仍保留剩余冷却。
- 冷却只禁用批量登录，不影响批量离线等菜单项。
- timeout 显示“正在上线，请稍后”并触发列表刷新；业务错误仍显示失败。
- 组件卸载时清理计时器。

### Armada 单元测试

- 批量预估和执行跳过 PENDING_ONLINE、ONLINE，并汇总两个新增原因。
- 单账号手动上线处于 PENDING_ONLINE、ONLINE 时不调用代理服务和 outbox。
- 批量状态条件更新不足时事务失败，不调用代理分配。
- 代理按国家分组、每 100 条生成一轮 CASE CAS，并只重试冲突映射。
- 三轮无进展、代理不足和 CAS 映射不一致时抛出明确业务异常。
- 快照一次批量更新且不再逐账号调用 Mapper。
- Publisher 在一个 Kafka 窗口 ACK 完成后回调 Dispatcher，再提交下一窗口，同时整批凭据和代理只批量准备一次。
- Dispatcher 对每个窗口的成功结果只调用一次 `markSentBatch`；混合失败窗口仍逐条回写 RETRY/DEAD。
- 日志测试不依赖每账号 INFO 文本。

### 真库 DbTest

- CASE UPDATE 正确建立 100 组不同的代理与账号映射。
- 两个并发事务争抢同一批候选代理时，每个代理最多绑定一个账号，失败方能识别冲突。
- `status != IDLE`、软删和跨租户代理不能被抢占。
- READ_COMMITTED 下重试能看到已提交冲突，并且本事务已抢占代理不会再次成为候选。
- 代理不足或后续异常时旧绑定、PENDING 状态和新绑定全部回滚。
- 批量代理快照字段与账号映射准确，租户隔离有效。
- Outbox 批量 SENT 更新只命中同一 `locked_by + locked_at` 的 LOCKED 行，重复或过期锁更新命中 0 行。

## 性能验收

代码级验收首先确认代理分配 SQL 数量按“区域组数 + 每 100 条一个 CAS 批次 + 有界冲突重试”增长，不再按账号执行一条锁定查询和一条快照更新。
同时确认 1000 条 Kafka 全成功命令只执行约 10 次 Outbox SENT UPDATE，不再逐账号提交 1000 次。

perf2 部署后使用相同约 1000 账号范围对比：

- 批量接口总耗时和代理分配耗时。
- 单条 CAS UPDATE 的 P50/P95/P99。
- CAS 冲突率、重试轮次、InnoDB lock wait 和 deadlock 数量。
- outbox accepted 数量与协议命令去重情况。
- 最终 PENDING、ONLINE、PROXY_FAILED 和 RECONNECTING 分布。

默认每次 CAS 100 条。只有当 P95 低于 50ms、冲突率低于 5% 且没有明显死锁时，才单独评估提高到 200；本次不直接使用 500 或 1000 条单语句更新。

## 回滚

- Web 可独立回滚批量冷却和 timeout 文案，不影响 API。
- Armada 回滚为原代理锁定 Mapper、逐账号快照和原状态过滤即可；无数据库迁移，无结构回滚。
- 如果乐观抢占出现高冲突或死锁，先回滚后端实现，不通过提高锁等待超时掩盖问题。

## 非目标

- 不在本次把批量上线 API 改为 `202 + operationId` 的异步任务接口。
- 不调整协议 worker 并发、Kafka Topic、outbox dispatcher 线程数或 WhatsApp 连接参数。
- 不改变代理国家优先级、代理健康判定或 PROXY_FAILED 自动恢复策略。
- 不部署、不修改测试或生产数据库数据。
