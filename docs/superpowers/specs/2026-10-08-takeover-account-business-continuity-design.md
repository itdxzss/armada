# 抢登账号业务连续性设计（自动抢登 + 熔断 + 拉群角色离线等待）

- 日期：2026-10-08（修订版 r2，同日）
- 状态：待实现（Claude 起草，交 Codex 实现）
- 适用仓库：`armada/armada-api`（只改后端；Android 协议层已正确上报 303 → `LOGIN_REPLACED`，不改）
- 证据环境：perf2（`armada_perf`），只读排查结论见第 1 节

## 修订记录（r2）

r1 经源码复核发现 4 处技术缺口，本版已补齐：

| # | 缺口 | 本版处理 |
|---|---|---|
| 1 | 总开关只控制账号侧，任务侧超时换号、建群人失败不受控，无法完整回滚 | 拆成账号侧、任务侧两个开关，逐项列出受控范围；两个都关闭 = 2026-10-08 现有行为（第 6 节） |
| 2 | 排除条件不闭合：抢登中账号主动下线后仍可能被续上线；封禁/解绑/受限账号收到被挤事件会被改成 6 再被扫描上线；可用性判定先判 ONLINE，跳过了禁言、熔断 | 5.3 增加终态粘性与“期望离线即停止抢登”；`reonlineForTakeover` 增加防线；5.5 调整判定顺序（第 5 节） |
| 3 | “建群后注销”模式的建群人处于 `RESERVED`，扫描排除它；上线命令不带任务/执行行，被 outbox 隔离规则拒绝 | 预留建群人不走全局自动抢登，新增由所属执行行发起、带归属信息的恢复路径（5.6） |
| 4 | `PULL_EXECUTION` 阶段拉手全部移出后只会等资源，没有自动补号 | 资源恢复在名额不足且有可选拉手时退回进群阶段，复用现有自动选拉手逻辑（7.2） |

> **给实现者（Codex）的硬性要求**
> 1. 开工前先读 `armada/AGENTS.md`、`.harness/rules/编码规范.md`、`.harness/rules/工程结构.md`、`.harness/rules/数据模型规范.md`、`.harness/rules/开发流程规范.md`。
> 2. 按 `.harness/changes/_TEMPLATE.md` 新建 `.harness/changes/2026-10-08-takeover-business-continuity.md`，持续更新进度和“必须核实”项的结论。
> 3. **主目录有其他会话的未提交修改**：`AccountOnlineCommandServiceImpl.java`、`AccountBatchLifecycleServiceImpl.java`、`ProtocolCommandOutboxServiceImpl.java` 及其测试，以及新文件 `ProtocolAccountCommandRejectedException.java`。**在独立 worktree 实现**，不得覆盖、回退或格式化主目录的这些改动。合并前先确认那批改动是否已提交，并在其基础上 rebase，因为 5.6 与 `ProtocolCommandOutboxServiceImpl.insertPendingRows` 的拒绝逻辑相关。
> 4. 严格按第 9 节的阶段顺序实现。每个阶段先写失败测试再实现（TDD），测试默认用 H2 内存库加真实 Mapper XML（模板见第 8 节）。
> 5. 不要发明新的协议命令 `source` 值，上线命令统一复用现有常量 `login_replaced_takeover`。
> 6. 所有数值和开关都从配置读取，禁止写死（第 6 节）。
> 7. 标注“必须核实”的地方，先读代码确认再动手，结论写进 change 记录。

---

## 1. 背景与证据（perf2，2026-10-08）

- 批次 109「印度全参_10.txt」的 10 个 Android 号 20:02 同时上线，20:08 起陆续收到 WhatsApp `stream:error conflict="replaced"`（Zhuan 上报 code 303 → Armada `LOGIN_REPLACED`）。我方同一账号只有一条连接，属外部同凭据登录挤号。
- 抢登中账号被挤后约 **3 秒**即自动重连成功（918091233442：20:58:07.3 被挤，20:58:09.6 重连成功）。
- 互挤严重的号：918091233442 最近 30 分钟被挤 69 次（约每 8 秒一次）；偶发的号：919914576400 30 分钟 2 次。
- 拉群任务 82（执行行 324）：建群人 918943736217（account_id=2794）20:15:09 建群成功，20:15:12 被挤。任务卡在 `GROUP_CREATE / APPLY_PROFILE`，每 30 秒报一次 `GROUP_CREATOR_UNAVAILABLE`，**无限等待**。
- perf2 的 252 个 Android 号中有 69 个被抢登（account_state=6），其中 42 个离线。

## 2. 现状（代码事实，2026-10-08 主干）

| 环节 | 位置 | 现状 |
|---|---|---|
| 被挤事件收敛 | `AccountStateEventServiceImpl.applyLifecycleTransition` | `LOGIN_REPLACED` 分支**排在所有终态判断之前**：当前为 7 → 保持 7 并离线；否则一律 `markLoginReplaced`（包括封禁 3、解绑 5、受限 8 的账号也会被改成 6；导出 4 在更早处单独返回） |
| 抢登中续上线 | `AccountTakeoverAutoReonlineSideEffect` → `AccountOnlineCommandServiceImpl.reonlineForTakeover` → `isTakeoverEligible` | 只判断“7、离线、未禁言”，**不判断 `desired_login_state`**；`login_replaced_takeover` 来源无冷却、无次数上限；在**状态事件同一事务内**同步写 outbox |
| 用户下线停止抢登 | `isUserOfflineStop` | 只有 OFFLINE 事件且 `source` 为 `manual_offline`/`batch_offline` 时才把 7 回落为 6 |
| 一键抢登 | `takeoverBatch` + `AccountStateMapper.markTakingOverByAccountIds` | 人工把 6 转为 7 并上线 |
| 被抢登 6 离线 | — | 没有任何机制让它自动上线 |
| 建群人注销隔离 | `ProtocolCommandOutboxMapper.creatorDeletionCommandBlocked`（XML 约 419 行），调用点：`ProtocolCommandOutboxServiceImpl.insertPendingRows`、`ProtocolCommandPublisher`（约 254 行） | 账号存在 `account_creator_deletion` 记录时，除非 `lifecycle='RESERVED'` 且命令 payload 中的 `pullTaskId`、`groupExecutionId` 与记录一致，否则拒绝。`ProtocolOnlineCommandRequest` **没有这两个字段** |
| 状态 SQL 的注销守卫 | `AccountStateMapper.xml` 中的 `notCreatorDeleting` | 只排除 `DELETING/DELETED`；`RESERVED` 的账号可以正常更新状态 |
| 代理失败扫描 | `ProxyFailedRecoveryDispatcher` + `selectProxyFailedRecoveryCandidates` | 排除任何存在 `account_creator_deletion` 记录的账号 |
| 执行行调度 | `PullTaskGroupExecutionMapper.xml` 中的 `DueScanConditions` | 认领条件为 `next_run_at <= now`，**0 表示立即到期**；资源等待态由 `PullTaskResourceRecoveryTransactionService.recover` 处理，未就绪时 `defer` 到 `now + retryDelayMs`（默认 30 秒）再查 |
| 拉手离线 | `PullTaskPullerAccountStateServiceImpl.markUnavailable(OFFLINE)`、`PullTaskPullerSlotPolicy.occupiesSlot` | 标记为暂离线后**一直占着名额**，不会补新拉手 |
| 拉手补充 | `PullTaskResourceRecoveryTransactionService.pullerCheck`（约 256–300 行）、`recoveryStage`（约 383–410 行） | 只有 `MANAGER_PULLER_CONTACT`/`DIRECT_PULLER_JOIN` 阶段可以选新拉手；`PULL_EXECUTION` 阶段只有存在“可用且未入群”的拉手行时才退回进群阶段。**拉手全部移出后会一直等 `PULLER_UNAVAILABLE`** |
| 自动选拉手 | `PullTaskManagerPullerContactTransactionService`（约 330–365 行，`insertPuller` 循环） | 进群阶段按名额插入新拉手行，跳过本执行行已出现过的账号，并 `retireReplacedPuller` |
| 人工补拉手 | `PullTaskPullerSupplementService` | 仅供页面人工操作，不是自动链路 |
| 管理离线 | `managerCheck` → `replaceManager` | 立即换号 |
| 建群人离线（建群前） | `PullTaskGroupCreateTransactionService`：建群命令用 `findActiveProtocolRef`，不校验在线 | 离线也会发建群 → `ACCOUNT_NOT_ONLINE` → `GROUP_CREATE_FAILED`，同一个号每 30 秒重试，没有上限 |
| 建群人离线（建群后） | `prepareProfile`/`repairProfile`/邀请链接准备 | defer `GROUP_CREATOR_UNAVAILABLE`，无限等待 |
| 新群提权阶段 | `managerAdminCheck` | 建群人离线 → `MANAGER_ADMIN_ACTOR_UNAVAILABLE`，无限等待 |
| 建群后注销模式 | `prepareRoles` | `creatorDeleteAfterTakeover=1` 时只选 Android 建群人，并通过 `reserveCreator` 写入 `RESERVED` 记录 |

## 3. 目标口径（用户已确认）

1. **被抢登（6）**：自动转为抢登中（7）并立即上线，不再需要人工点一键抢登。
2. **抢登中（7）掉线**：短时间等待重连。重连成功后**立即**继续派命令，不设稳定在线门槛。
3. **可替换角色**（拉手、管理）掉线：等待 **30 秒**，没回来就换号；原账号回来后回到号池（不恢复本执行行的旧角色）。
4. **建群人**（不可替换）掉线：等待 **3 分钟**；超时或不可恢复时，这一行执行失败并写明原因。
5. **熔断**：同一账号 **10 分钟内被挤 10 次** → 停止自动抢登，回落为被抢登（6），按第 3、4 条立即换号或失败。熔断后不自动恢复，人工“一键抢登”时清零熔断。
6. **封禁、解绑、注销、导出、受限**：保持现状，不参与业务，不等待，**也不能被被挤事件改写成被抢登**。
7. **用户主动下线**（`desired_login_state=2`）的账号：任何自动路径都不能再把它拉上线。
8. 因离线被明确拒绝（`ACCOUNT_NOT_ONLINE`，确定未执行）的命令不计业务失败，账号回来后由原账号重发。
9. **两个开关都关闭时，行为与 2026-10-08 主干完全一致**（回滚手段，见第 6 节）。

## 4. 术语

- **offlineSince**：账号本次连续离线的起点（毫秒），在线时为 NULL。
- **账号角色可用性**（新，由账号域对外提供，判定顺序见 5.5）：`ONLINE` / `RECOVERING` / `TERMINAL`。
- **预留建群人**：存在 `account_creator_deletion` 记录且 `lifecycle='RESERVED'` 的账号。匹配方式与 `creatorDeletionCommandBlocked` 一致：`account_id` 相同，或 `creator_deletion_identity_phone` 相同。
- **等待判定**：ONLINE → 使用；RECOVERING 且 `now - offlineSince < grace` → 等到 `offlineSince + grace`；其余情况 → 放弃（换号或失败）。offlineSince 为 NULL 且账号不在线时，视为已超时。

---

## 5. 账号域设计

### 5.1 数据模型（Flyway，一个迁移文件）

版本号：实现时执行 `ls armada-api/src/main/resources/db/migration | sort -V | tail -3`，取下一个未占用的号（撰写时最新为 V214，perf2 已应用到 214）；跨分支提交前再核对一次，防止撞号。写法按 `V077__account_desired_login_state.sql` 的 `information_schema` 守卫，保证幂等。

**(1) `account_state` 新增 1 列**

```sql
offline_since BIGINT NULL COMMENT '本次连续离线起点(epoch毫秒);在线时为NULL;供任务角色离线等待计时'
```
- 为什么必须新增：`last_state_sync_time` 在离线期间会被 RECONNECTING、待上线等事件反复刷新；`pull_task_group_account.updated_at` 每次重新标记离线也会被刷新。两者都不能表示“掉线了多久”。这是登录态事实，归属 `account_state` 聚合。该表现有 30 列，新增 1 列需在 change 记录中写明上述理由。
- 回填：`UPDATE account_state SET offline_since = COALESCE(last_state_sync_time, updated_at) WHERE (login_state IS NULL OR login_state <> 1) AND offline_since IS NULL;`

**(2) 新表 `account_takeover_breaker`（一账号一行）**

```sql
CREATE TABLE IF NOT EXISTS account_takeover_breaker (
  id                BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  tenant_id         BIGINT NOT NULL COMMENT '租户ID',
  account_id        BIGINT NOT NULL COMMENT '→account.id',
  window_started_at BIGINT NULL     COMMENT '当前计数窗口起点(epoch毫秒,窗口内首次被挤时间)',
  kick_count        INT    NOT NULL DEFAULT 0 COMMENT '当前窗口内被挤(LOGIN_REPLACED)次数',
  tripped_at        BIGINT NULL     COMMENT '熔断时间(epoch毫秒);非空=熔断中,不再自动抢登,人工一键抢登清空',
  created_at        BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
  updated_at        BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
  PRIMARY KEY (id),
  UNIQUE KEY uq_tenant_account (tenant_id, account_id),
  KEY idx_tenant_tripped (tenant_id, tripped_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号自动抢登熔断计数';
```
- 为什么单独建表：熔断是“自动抢登”这一独立关注点的运行态，不属于账号生命周期；`account_state` 已到 30 列宽表阈值。表有 `tenant_id`，不需要登记租户忽略白名单。
- 窗口语义（固定窗口）：窗口为空，或 `kickedAt - window_started_at >= 10 分钟` → 重开窗口，`kick_count=1, window_started_at=kickedAt`；否则 `kick_count+1`。`kick_count >= 10` 时写入 `tripped_at=kickedAt`。
- 改完 schema 后重跑 `wiki/gen_datamodel.py`，刷新 `.harness/wiki/数据模型.md`。

**(3) 回滚脚本**：`.harness/changes/2026-10-08-takeover-business-continuity/rollback.sql`（删除新表、删除新列）。

### 5.2 维护 `offline_since`（不受开关控制，纯数据）

以下 SQL 都会写 `login_state`，**每一处都要同步维护 `offline_since`**。用参数判断，不要引用同一个 SET 里刚赋值的列，避免 MySQL 从左到右求值与 H2 语义不一致。

| 文件 | statement | 新增 SET 片段 |
|---|---|---|
| `mapper/account/AccountStateMapper.xml` | `updateLoginState`、`updateLoginAndAccountState`、`updateLifecycleState` | `offline_since = CASE WHEN #{loginState} = 1 THEN NULL ELSE COALESCE(offline_since, #{lastStateSyncTime}) END` |
| 同上 | `markPendingOnlineInternal`、`claimPendingOnlineInternal`、`claimProxyFailedReonlineInternal`（写入待上线=3） | `offline_since = COALESCE(offline_since, <该语句的时间参数>)` |
| `mapper/account/AccountCreatorDeletionMapper.xml`（约 116 行，注销时置 login_state=2） | 同一条 UPDATE | `offline_since = COALESCE(offline_since, <该语句的时间参数>)` |
| 5.6 新增的 `claimReservedCreatorReonline` | — | 同“待上线”写法 |

实现后执行 `grep -rn "login_state" armada-api/src/main/resources/mapper`，复核没有遗漏的写入点。`AccountState` 实体增加 `offlineSince` 字段（有 resultMap 的同步修改）。

### 5.3 被挤事件：自动抢登 + 熔断（事件路径）

新增组件（包 `com.armada.account.takeover`）：
- `AccountAutoTakeoverProperties`：`@ConfigurationProperties(prefix = "armada.account.auto-takeover")`，写法参考 `task/scheduler/PullTaskExecutionDispatchProperties`（setter 校验参数大于 0）。
- `AccountTakeoverBreakerMapper`（含 XML）：`recordKick(...)`、`selectByAccountId`、`reset(accountIds, now)`。如果 H2 不支持 upsert 语法，改为“先 `SELECT ... FOR UPDATE`，再 update 或 insert”两步。
- `AccountTakeoverBreaker`（Service）：`KickResult recordKick(Account account, long kickedAt)` 返回 `CONTINUE` 或 `TRIPPED`；`boolean isTripped(accountId)`；`void reset(List<Long> accountIds, long now)`。
- `AccountCreatorReservationLookup`（或在现有 Mapper 上新增方法）：判断账号是否存在 `account_creator_deletion` 记录，并返回 lifecycle、task_id、group_execution_id。匹配条件照抄 `creatorDeletionCommandBlocked`。

把 `AccountStateEventServiceImpl.applyLifecycleTransition` 中的 `isLoginReplaced(event)` 分支改为：

```
if (isLoginReplaced(event)) {
    if (!autoTakeover.enabled()) {                       // 账号侧开关关闭：逐字保持现状
        if (isTakingOver(currentState)) markTakingOverLogin(OFFLINE) else markLoginReplaced();
        return true;
    }
    // ① 终态粘性：封禁3/导出4/解绑5/受限8/注销9 不被被挤事件改写生命周期，只记离线
    if (isTerminalLifecycle(currentState)) {
        stateMapper.updateLoginState(updateRow(id, OFFLINE, null, SOURCE_LOGIN_REPLACED, null, occurredAt, updatedAt));
        return true;                                     // 不计熔断、不改 account_state、不触发续上线
    }
    // ② 建群人注销流程（DELETING/DELETED）：照旧写法（状态 SQL 自带守卫），不计熔断
    if (reservation != null && reservation.lifecycle() != RESERVED) { markLoginReplaced(); return true; }
    // ③ 用户主动下线 或 禁言：停止抢登，回落 6
    if (desiredOffline(currentState) || muted(currentState)) { markLoginReplaced(); return true; }
    // ④ 只有 NULL/1（新增）走旧逻辑：落 6，由 5.4 扫描接手
    if (!inStates(currentState, NORMAL, LOGIN_REPLACED, TAKING_OVER)) { markLoginReplaced(); return true; }
    // ⑤ 计熔断
    KickResult r = breaker.recordKick(account, occurredAt);
    if (r == TRIPPED) { markLoginReplaced(); log.warn("自动抢登熔断 ..."); return true; }
    // ⑥ 预留建群人：全局路径不能发上线命令（会被 outbox 拒绝），保持 6，由所属执行行恢复（5.6）
    if (reservation != null) { markLoginReplaced(); return true; }
    // ⑦ 其余：置 7 离线，由 AccountTakeoverAutoReonlineSideEffect 立即续上线
    markTakingOverLogin(account, OFFLINE, stateSource, occurredAt, updatedAt);
    return true;
}
```

同时修改 `isTakingOver(currentState)` 后面那段处理 7 号离线（`isTakeoverContinuableOffline`）的分支。账号侧开关开启时，如果 `desiredOffline(currentState)` 为真，按“用户下线停止抢登”处理，即落为 6 离线，即使事件的 `source` 不是 `manual_offline`。

**续上线防线**（`AccountOnlineCommandServiceImpl.isTakeoverEligible`，账号侧开关开启时追加以下条件，关闭时保持原样）：
- `desired_login_state IS NULL OR <> 2`
- 熔断未触发
- 不是预留建群人，也没有任何 `account_creator_deletion` 记录（日志写明“由所属执行行恢复”）

**必须核实（事务安全）**：`reonlineForTakeover` 是在状态事件事务内同步调用的。如果它内部调用的 `@Transactional` Bean（代理分配 `allocateOnlineEndpoint`、outbox 入队）抛出运行时异常，即使在外层 catch，共享事务也已被标记为 rollback-only，状态事件会回滚并进入 Kafka 重试。实现时先确认这些调用在“无空闲代理”“outbox 拒绝”时是否会抛异常。如果会，账号侧开关开启时把**事件路径触发的续上线改到提交后执行**（参考 `ProxyFailedRecoveryCoordinator` 的事务外编排与 `PullTaskExecutionDispatchTrigger.dispatchAfterCommit` 的注册方式），失败只记日志，由 5.4 的“7 号离线补偿”兜底。必须加测试证明：代理分配失败时状态事件仍然提交成功，账号保持 7 离线。

### 5.4 补偿扫描（存量被抢登 + 卡住的抢登中）

完全参照 `account/recovery/ProxyFailedRecoveryDispatcher` 与 `account/job/ProxyFailedRecoveryScheduler`：
- `account/takeover/AccountAutoTakeoverDispatcher.dispatchOnce(now)`：账号侧开关关闭时直接返回 0。否则跨租户查询候选，逐个设置 `TenantContext` 后处理；单个账号出错只记 warn，不中断整轮。
- `account/job/AccountAutoTakeoverScheduler`：`@Profile("kafka")`；`@ConditionalOnProperty(prefix="armada.account.auto-takeover.scan", name="enabled", havingValue="true", matchIfMissing=true)`；`@Scheduled(fixedDelayString="${armada.account.auto-takeover.scan.fixed-delay-ms:60000}")`。
- 新 SQL `AccountStateMapper.selectAutoTakeoverCandidates`：照抄 `selectProxyFailedRecoveryCandidates` 的跨租户写法，**包括 Mapper 接口方法上绕过租户拦截的注解（必须核实并照抄）**。返回 `tenantId, accountId, accountState`：

```sql
FROM account_state s
INNER JOIN account a ON a.id = s.account_id AND a.tenant_id = s.tenant_id AND a.deleted_at IS NULL
LEFT JOIN account_takeover_breaker b ON b.tenant_id = s.tenant_id AND b.account_id = s.account_id
WHERE NOT EXISTS (SELECT 1 FROM account_creator_deletion d
                  WHERE d.account_id = s.account_id OR d.creator_phone = a.creator_deletion_identity_phone)  -- 含 RESERVED，预留建群人走 5.6
  AND s.login_state = 2
  AND s.mute_status IS NULL
  AND (s.desired_login_state IS NULL OR s.desired_login_state <> 2)
  AND b.tripped_at IS NULL
  AND (
        s.account_state = 6                                                   -- 存量被抢登
     OR (s.account_state = 7 AND s.last_state_sync_time <= #{stuckBefore})    -- 续上线失败卡住的抢登中（now - 30s）
  )
ORDER BY s.last_state_sync_time ASC, s.id ASC
LIMIT #{limit}
```
- 状态为 6 → `AccountOnlineCommandService.autoTakeover(accountId)`；状态为 7 → `reonlineForTakeover(accountId, null, "login_replaced_takeover")`。
- 每轮最多 `scan.batch-size` 个（默认 20），避免存量账号同时冲上线。

`AccountOnlineCommandServiceImpl` 的改动（最小增量，不改其他会话改过的方法体）：
- 把现有 `takeoverBatch` 的方法体抽成私有方法 `takeover(List<Long> ids)`。
- `takeoverBatch(ids)`（人工一键抢登）= `breaker.reset(ids, now)` + `takeover(ids)`。人工抢登时清零熔断（账号侧开关关闭时不调用 reset）。
- 新增 `autoTakeover(Long accountId)`，并在 `AccountOnlineCommandService` 接口上同步增加方法和 Javadoc：`@Transactional`；账号侧开关关闭时返回跳过结果；重新读取状态，复核 5.4 的全部条件（状态必须为 6、熔断未触发、没有注销记录）；满足则执行 `takeover(List.of(accountId))`。不清零熔断。

### 5.5 账号角色可用性查询（给任务域）

在跨域边界 `AccountProtocolLookupService` 上新增：

```java
/** 查询任务角色账号的在线可用性快照，用于离线等待/换号判定；结果只含输入中仍属当前租户的账号。 */
Map<Long, AccountRoleAvailability> findRoleAvailability(Collection<Long> accountIds);
```

新增 record `account/model/AccountRoleAvailability(Long accountId, Kind kind, Long offlineSince, Integer loginState, CreatorReservation reservation)`，其中 `enum Kind { ONLINE, RECOVERING, TERMINAL }`，`reservation` 可为 null，结构为 `(String lifecycle, Long taskId, Long groupExecutionId)`。

**判定顺序（严格按此顺序，先排除，后判在线）**：
1. 账号不存在或已软删 → TERMINAL
2. 存在注销记录且 lifecycle ∈ {DELETING, DELETED} → TERMINAL（RESERVED 不算终态，填入 reservation 后继续往下判）
3. account_state ∈ {3, 4, 5, 8, 9} → TERMINAL
4. `desired_login_state = 2` → TERMINAL
5. 熔断中（`tripped_at` 非空）→ TERMINAL（即使当前在线；用户想继续用该号，需先人工一键抢登）
6. `login_state = 1` → ONLINE。**ONLINE 只表示已连接**：派命令前仍必须走各角色现有的资格查询（如 `findEligiblePullerProtocolRefs`、`findEligibleManagerProtocolRefs`），禁言、拉人受限等由这些查询判定，本方法不替代它们
7. `mute_status` 非空 → TERMINAL（离线且禁言的账号不会被自动拉起）
8. 账号侧开关关闭且 account_state = 6 → TERMINAL
9. 其余（NULL/1/2/6/7 离线或待上线，含预留建群人）→ RECOVERING

输入中查不到的账号不出现在返回 Map 里，调用方按 TERMINAL 处理。

### 5.6 预留建群人恢复路径（由所属执行行发起）

适用于“建群后注销”模式（`creatorDeleteAfterTakeover=1`）中、注销记录 lifecycle 为 `RESERVED` 的建群人。这类账号**不走全局自动抢登**（5.3 的第⑥步、5.4 的扫描都排除它），只能由它所属的执行行带着归属信息拉起。

**(1) 上线命令携带归属信息**
- `ProtocolOnlineCommandRequest` 新增两个可空字段 `Long pullTaskId`、`Long groupExecutionId`。序列化到 payload 时，键名必须正好是 `pullTaskId`、`groupExecutionId`（outbox 和发布端的隔离检查读的就是这两个键），为 null 时**不输出**。普通账号的 payload 必须与现在逐字节一致，要加测试证明。
- 现有构造方法保留，为 null 时用重载补齐，不改动现有调用方。
- 协议层兼容性：建群后注销模式只选 Android 建群人（见 `prepareRoles`）。Zhuan 只在 `internal/diag/status_probe.go` 使用了 `DisallowUnknownFields`。**必须核实** `internal/armada` 中上线命令的解码会忽略未知字段，并在 change 记录中注明。Web 协议层不涉及。

**(2) 账号域新方法** `AccountOnlineCommandService.reonlineReservedCreator(long accountId, long pullTaskId, long groupExecutionId)`
- `@Transactional`。账号侧开关关闭时返回跳过结果。
- 全部条件满足才继续，否则返回跳过结果（**不抛异常**）：
  - 注销记录 lifecycle 为 `RESERVED`，且 tenant、task、execution 与入参一致；
  - account_state ∈ {2, 6}；
  - `login_state = 2`；
  - `mute_status IS NULL`；
  - `desired_login_state` 不为 2；
  - 熔断未触发。
- 用新 SQL `claimReservedCreatorReonline` 原子抢占，防止重复发命令，写法参照 `claimProxyFailedReonlineInternal`：
  `UPDATE account_state SET login_state=3, offline_since=COALESCE(offline_since,#{now}), last_state_sync_time=#{now}, state_source='RESERVED_CREATOR_REONLINE', updated_at=#{now} WHERE account_id=#{accountId} AND login_state=2 AND mute_status IS NULL AND (desired_login_state IS NULL OR desired_login_state<>2) AND (account_state IN (2,6))`。
  更新行数为 1 才继续。
- 用来源 `login_replaced_takeover` 加 `pullTaskId`、`groupExecutionId` 构造上线命令，复用 `onlineWithSource` 的代理分配与 outbox 写入（抽出带归属参数的私有重载）。**不把账号改成 7**，保持 6，避免全局续上线去碰它。
- 上线失败时，协议层回报 OFFLINE，账号回到 2，任务下一轮会再次触发；如果命令丢失，账号停在待上线，任务侧 3 分钟宽限到期后放弃。两种情况都有上限。

**(3) 由任务侧触发**（见 7.1）。任务侧开关关闭时不触发。

---

## 6. 开关与配置

### 6.1 开关受控范围

| 开关 | 控制的行为 | 关闭后 |
|---|---|---|
| `armada.account.auto-takeover.enabled` | 5.3 新分支（终态粘性、期望离线停止抢登、熔断计数、自动置 7）；7 号离线分支的期望离线判断；`isTakeoverEligible` 新增的防线；`takeoverBatch` 清零熔断；5.4 扫描；`autoTakeover`；`reonlineReservedCreator`；5.5 第 8 条 | 被挤事件、续上线、一键抢登与 2026-10-08 一致；熔断表不写入；扫描返回 0 |
| `armada.task.offline-role-wait.enabled` | 7.1 建群人闸门（含建群前不发命令、超时失败、预留建群人触发）；新群提权阶段的建群人判定；7.2 拉手超时移出、等待截止时间、`PULL_EXECUTION` 自动补号；7.3 管理宽限；7.0 新原因码的上线唤醒 | 建群人、拉手、管理的处理逻辑与 2026-10-08 一致 |
| 不受开关控制 | Flyway 结构变更；5.2 `offline_since` 维护；`ProtocolOnlineCommandRequest` 新增的可空字段（为 null 时 payload 不变） | 不影响行为 |

**完整回滚 = 两个开关都设为 false。** 第 8 节有专门的开关回归用例。

### 6.2 `application.yml` 新增配置（全部可用环境变量覆盖）

```yaml
armada:
  account:
    auto-takeover:
      # 账号侧开关；false 时与 2026-10-08 行为一致（见设计 6.1）。
      enabled: ${ACCOUNT_AUTO_TAKEOVER_ENABLED:true}
      # 熔断：10 分钟内被挤 10 次。
      breaker-window-ms: ${ACCOUNT_AUTO_TAKEOVER_BREAKER_WINDOW_MS:600000}
      breaker-max-kicks: ${ACCOUNT_AUTO_TAKEOVER_BREAKER_MAX_KICKS:10}
      scan:
        enabled: ${ACCOUNT_AUTO_TAKEOVER_SCAN_ENABLED:true}
        fixed-delay-ms: ${ACCOUNT_AUTO_TAKEOVER_SCAN_FIXED_DELAY_MS:60000}
        batch-size: ${ACCOUNT_AUTO_TAKEOVER_SCAN_BATCH_SIZE:20}
        # 抢登中账号离线超过该时长仍未重连，视为续上线失败，由扫描补偿。
        stuck-taking-over-ms: ${ACCOUNT_AUTO_TAKEOVER_STUCK_MS:30000}
  task:
    offline-role-wait:
      # 任务侧开关；false 时与 2026-10-08 行为一致（见设计 6.1）。
      enabled: ${PULL_TASK_OFFLINE_ROLE_WAIT_ENABLED:true}
      # 拉手、管理等可替换角色掉线后等待原号的时长。
      replaceable-grace-ms: ${PULL_TASK_REPLACEABLE_ROLE_GRACE_MS:30000}
      # 新群建群人（不可替换）掉线后等待原号的时长。
      creator-grace-ms: ${PULL_TASK_CREATOR_GRACE_MS:180000}
```
任务侧对应新类 `task/scheduler/PullTaskOfflineRoleWaitProperties`（`@ConfigurationProperties(prefix="armada.task.offline-role-wait")`）。**不设“重连后稳定在线 N 秒”的门槛。**

---

## 7. 拉群任务域设计

### 7.0 公共部件

- `task/model/PullTaskOfflineRoleWaitPolicy`（纯函数，100% 单测）：`Decision decide(AccountRoleAvailability a /*可为 null*/, long graceMs, long now)` 返回 `USE`、`WAIT(waitUntil)` 或 `GIVE_UP`。规则见第 4 节的“等待判定”；`a == null` 时返回 GIVE_UP。
- 新原因码（加到 `PullTaskExecutionReasonCode`，附中文说明）：
  - `GROUP_CREATOR_RECONNECTING("建群人掉线，等待重新上线")`
  - `GROUP_CREATOR_OFFLINE("建群人离线超时或不可恢复，本群执行失败")`
  - `MANAGER_RECONNECTING("管理员掉线，等待重新上线")`
- 拉手角色级原因：`PullTaskPullerAccountStateService.Unavailability` 新增 `OFFLINE_TIMEOUT("PULLER_OFFLINE_TIMEOUT")`，走与 BANNED/UNBOUND 相同的 REMOVED 分支。
- **等待截止时间**：调度认领条件是 `next_run_at <= now`（`DueScanConditions`），所以等待时把 `next_run_at` 设为 `min(now + retryDelayMs, waitUntil)`，到点会被重新认领。
- **上线唤醒**（任务侧开关开启时）：`PullTaskPullerAccountStateChangedSideEffect` 收到 ONLINE 事件后，**先于拉手资格判断**调用新方法 `pullTasks.wakeRoleWaiters(tenantId, accountId, occurredAt)`。新 SQL `PullTaskGroupExecutionMapper.wakeForReconnectedRole`：

```sql
UPDATE pull_task_group_execution e
SET next_run_at = LEAST(e.next_run_at, #{now}), version = version + 1, updated_at = #{now}
WHERE e.execution_status IN (2, 3)
  AND e.reason_code IN ('GROUP_CREATOR_RECONNECTING', 'MANAGER_RECONNECTING')
  AND e.manual_paused = 0 AND e.lock_owner IS NULL
  AND EXISTS (SELECT 1 FROM pull_task_group_account ga
              WHERE ga.group_execution_id = e.id AND ga.tenant_id = e.tenant_id
                AND ga.account_id = #{accountId} AND ga.role_type IN (1, 4)
                AND ga.availability_status <> 4)
```
  更新行数大于 0 时调用 `dispatchTrigger.dispatchAfterCommit()`。

### 7.1 建群人（新群模式，PROMOTER 首槽）

在 `PullTaskGroupCreateTransactionService` 中新增私有方法 `CreatorGate creatorGate(candidate, now)`：读取 `roles(candidate.getId(), PROMOTER)` 的首个账号，调用 `findRoleAvailability`，再用 `PullTaskOfflineRoleWaitPolicy.decide(..., creatorGraceMs, now)` 判定。返回 `READY(ref)`、`WAIT(waitUntil, requestReservedReonline)` 或 `GIVE_UP`。其中 `requestReservedReonline = a.reservation 为 RESERVED 且与本执行行一致 && a.loginState == 2`。

**任务侧开关关闭时，下列各处保持原分支，不调用 creatorGate。** 开启时替换以下分支（行号对应 2026-10-08 版本，以方法名为准）：

| 方法 | 现状 | 改为 |
|---|---|---|
| 建群命令准备（约 165–203 行，`findActiveProtocolRef` 之后） | creatorRef 为 null 才 defer，离线也发命令 | READY 才构造命令；WAIT → `defer(candidate, GROUP_CREATOR_RECONNECTING, min(now+retryDelayMs, waitUntil), now)`；GIVE_UP → 终止 |
| `prepareProfile`（约 299 行，`onlineProfileCreator` 为空） | defer `GROUP_CREATOR_UNAVAILABLE` | 同上三个分支 |
| `repairProfile`（约 393 行） | 同上 | 同上 |
| 邀请链接准备（约 485–498 行，`findActiveProtocolRef`） | 离线也继续 | 同上三个分支 |

- `SELECT_ROLES`（`prepareRoles` 选在线建群人）**不改**；`prepareProfile` 中 `action == null` 的分支**不改**。
- **预留建群人触发**：WAIT 且 `requestReservedReonline` 为真时，事务方法本身不调用账号服务，而是注册提交后回调（`TransactionSynchronization.afterCommit`，写法参照 `PullTaskExecutionDispatchTrigger`），在回调中调用 `accountOnlineCommandService.reonlineReservedCreator(accountId, taskId, executionId)`，捕获所有异常并记 warn。这样任何失败都不会回滚执行行的 defer。
- **终止**：调用 `PullTaskGroupExecutionFailureService.terminate(tenantId, executionId, GROUP_CREATOR_OFFLINE, now)`。**必须核实**：
  - (a) 在持有调度租约的事务里调用时，`transitionTerminal` 的 version 条件能否命中（该方法内部会 `selectById` 取最新 version）；
  - (b) 建群后注销模式下，执行行变成 FAILED 后，`RESERVED` 记录如何释放。参照 GROUP_BANNED 终止路径的现有处理；如果没有释放逻辑，就补上并加测试。
- **新群提权阶段**：在 `PullTaskResourceRecoveryTransactionService.managerAdminCheck` 中，任务侧开关开启、`candidates` 为空且任务为新群模式时，对 `creatorAccountIds(parent, executionId)` 得到的建群人做同样判定：
  - WAIT → waiting `GROUP_CREATOR_RECONNECTING`，nextRunAt=waitUntil（`ResourceCheck` 需要新增可携带 nextRunAt 的变体），需要时同样在提交后触发预留建群人恢复；
  - GIVE_UP → 终止，原因 `GROUP_CREATOR_OFFLINE`。`ResourceCheck` 必须携带终止结果，`recover()` 收到后直接返回，不能再对已终止的执行行执行 defer、deferForSlot 或 resume；同组 H2 测试验证只发生一次终止转换。
  普通链接模式保持现状。

### 7.2 拉手（PULLER）

目标：离线拉手最多占名额 30 秒，超时后移出；**名额不足时自动补号**，包括 `PULL_EXECUTION` 阶段。以下全部只在任务侧开关开启时生效。

**(1) 超时移出**
- `PullTaskPullerAccountStateService` 新增 `expireOfflineRole(PullTaskGroupAccount row, long now)`。实现时把 `PullTaskPullerAccountStateServiceImpl` 中 REMOVED 分支抽成私有方法复用，依次执行：`markUnavailable(REMOVED, "PULLER_OFFLINE_TIMEOUT")`、`stickyPullers.invalidateCurrentRole`、发布 `PullTaskPullerUnavailableEvent`。
- `PULLER_OFFLINE_TIMEOUT` 必须加入 `PullTaskStickyPullerTransactionService.INVALIDATING_REASON_CODES`，保证过期角色是当前粘性拉手时真正清空指针。
- **清理后的事务一致性（用户确认补充）**：仅当任务侧开关开启且本轮确实执行了超时移出，才在同一事务中重读执行行。必须复核 `execution_status=WAIT_RESOURCE`、stage 与最初认领时相同、`manual_paused=0`、`lock_owner` 仍为本调度实例、`lock_expires_at > now`。任一不满足，与 `transitionWaiting` 一样 `setRollbackOnly()` 并返回 `LOST`。全部满足后，defer、deferForSlot、resume、recoveryStage 一律使用重读的执行行及最新 version，不再使用最初的 candidate。原因是清理当前粘性拉手会使 version 增加，继续使用原 version 会让推进 CAS 失败并回滚超时移出。任务侧开关关闭、或本轮没有实际移出时均不执行此重读，保持原行为。
- 在 `pullerCheck` 计算 `occupied` 之前，找出满足 `waitingForOnline(row) && !awaitingJoinResult(row)` 的行，批量查询 `findRoleAvailability`。判定为 GIVE_UP 的（超过 30 秒或 TERMINAL）调用 `expireOfflineRole`，然后重新执行 `selectByExecutionAndRole`。
- 仍在宽限内的：返回 `ResourceCheck.waiting(ACCOUNT_NOT_ONLINE, …)`，nextRunAt 取 `min(now + retryDelayMs, 最早的 waitUntil)`。
- `PullTaskStickyPullerTransactionService.waitForPuller` 与 `PullTaskPullerInviteTransactionService.waitForResource` 保持现状（next_run_at=0，立即进入资源恢复）。超时判定统一在 `pullerCheck` 里做。
- 在途进群的拉手（`JOINING/UNKNOWN/PENDING_APPROVAL`）不移出。

**(2) `PULL_EXECUTION` 自动补号**（修正 r1 的错误假设）
- 在 `pullerCheck` 中计算 `selectable`：取 `findOnlineEligiblePullersByGroupId(setting.getPullerGroupId())` 的结果，排除本执行行所有 PULLER 行中出现过的 accountId（不论什么状态）。这与进群阶段 `insertPuller` 循环中 `existingIds` 的跳过规则一致。
- 就绪条件扩展为：`ready = available > 0 || (stageCanSelect || stage == PULL_EXECUTION) && occupied < planned && !selectable.isEmpty()`。
- `recoveryStage`：当 `waitResourceType = PULLER`、`stage = PULL_EXECUTION`，且 `occupied < planned && !selectable.isEmpty()` 时，与现有 `needsEntry` 分支一样返回进群阶段：`usesDirectPullerFlow()` 为真返回 `DIRECT_PULLER_JOIN`，否则返回 `MANAGER_PULLER_CONTACT`。这条“`PULL_EXECUTION` → 进群阶段”的跳转现有代码已在使用（人工补充后的 NOT_JOINED 行就走这条路），本次只是放宽触发条件，**不新写补号逻辑**。进入进群阶段后，由 `PullTaskManagerPullerContactTransactionService` 现有的 `insertPuller` 循环选号、补齐名额，之后按原流程回到 `PULL_EXECUTION`。
- **必须核实**：从 `PULL_EXECUTION` 退回进群阶段时，活跃波次（`active_pull_wave_id`）、粘性拉手（`active_puller_group_account_id`）和计划中的调用都能保持一致。如果现有的人工补充路径在跳转时做了额外处理，自动补号也要照做。
- 没有可选拉手时，保持现状：waiting `PULLER_UNAVAILABLE`，每 30 秒重查。
- 说明：封禁、解绑的拉手被移出后，同样会因此自动补号（属于行为变化，受任务侧开关控制）。

### 7.3 管理（MANAGER，第 4 阶段）

任务侧开关开启时，`managerCheck` 在 `usable.isEmpty()` 的情况下，先查询未 REMOVED、admin 未 FAILED 的已存管理员的可用性：
- 任一为 WAIT → waiting `MANAGER_RECONNECTING`，nextRunAt 取最早的 waitUntil，不调用 `replaceManager`；
- 全部为 GIVE_UP → 走现有 `replaceManager`。

**必须核实**：`PullTaskManagerJoinResultServiceImpl` 对 `ACCOUNT_NOT_ONLINE`（MANAGER_FAILED）是如何标记角色行的。要确保同一个管理员在宽限期内重新上线后，能重新提交进群，而不是被当作失败。

### 7.4 离线拒绝不计失败

执行 `grep -rn ACCOUNT_NOT_ONLINE armada-api/src/main/java/com/armada/task`，逐个检查分支，确保没有任何分支把它计入失败次数或直接终止执行行。如果有，任务侧开关开启时改为按本设计等待。

---

## 8. 测试要求（H2 + 真实 Mapper XML，先写失败测试）

模板类：`account/service/impl/AccountStateEventServiceImplTest`、`AccountStateEventServiceConcurrencyH2Test`、`AccountOnlineCommandServiceImplTest`、`account/recovery/ProxyFailedRecoveryDispatcherTest`、`account/state/PullTaskPullerAccountStateChangedSideEffectTest`、`platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest`、`task/scheduler/PullTaskResourceRecoveryTransactionIntegrationTest`、`PullTaskGroupCreateTransactionIntegrationTest`、`PullTaskStickyPullerTransactionServiceTest`、`task/service/impl/PullTaskPullerAccountStateServiceImplTest`、`PullTaskManagerJoinResultServiceImplTest`。迁移 SQL 测试参照 `hyperlink/*MigrationSqlTest`。

每条用例写一个测试方法。

**A. 自动抢登与熔断（账号侧开关开启）**
1. 状态 2 的在线账号被挤 → 落 7 离线；outbox 出现来源为 `login_replaced_takeover` 的上线命令。
2. 窗口内第 9 次被挤仍落 7；第 10 次 → 落 6，`tripped_at` 非空，不入队。
3. 距窗口起点超过 10 分钟后再被挤 → `kick_count` 重置为 1。
4. 熔断账号不会被扫描选中；人工 `takeoverBatch` 后熔断清零并上线。
5. 扫描：6 号离线、未禁言、未熔断 → 转 7 并入队；每轮不超过 batch-size；单个账号异常不影响其他账号。
6. 扫描补偿：7 号离线超过 30 秒 → 调用 `reonlineForTakeover`；未超过 30 秒 → 不选中。
7. `offline_since`：从在线到离线时写入；离线期间 RECONNECTING 或待上线不刷新；上线后清空；迁移回填正确。

**B. 排除条件闭合（缺口 2 回归）**

8. 7 号账号、`desired_login_state=2`，收到 LOGIN_REPLACED → 落 6，不入队。
9. 7 号账号、`desired_login_state=2`，收到 source 不是 `manual_offline` 的 OFFLINE → 落 6，不入队。
10. 7 号账号、`desired_login_state=2`，直接调用 `reonlineForTakeover` → 跳过。
11. 状态为 3/5/8/9 的账号收到 LOGIN_REPLACED → `account_state` 不变，login_state 变为离线，不写熔断表，之后扫描也不会选中。
12. 禁言账号收到 LOGIN_REPLACED → 落 6；禁言解除后会被扫描选中。
13. `findRoleAvailability`：在线且熔断 → TERMINAL；在线且期望离线 → TERMINAL；在线且禁言 → ONLINE；离线且禁言 → TERMINAL；RESERVED → RECOVERING 并带 reservation；DELETING → TERMINAL；3/4/5/8/9 → TERMINAL。

**C. 预留建群人（缺口 3 回归）**

14. RESERVED 建群人被挤 → 落 6、计熔断，不写全局上线命令，状态事件正常提交。
15. RESERVED 建群人不会被扫描选中；`reonlineForTakeover` 对它直接跳过。
16. `reonlineReservedCreator` 归属一致 → 抢占成功，outbox 写入成功（`creatorDeletionCommandBlocked=false`），payload 含 `pullTaskId` 和 `groupExecutionId`。
17. `reonlineReservedCreator` 执行行不一致，或账号已在线/待上线 → 返回跳过，不抛异常，不写 outbox。
18. 普通账号上线命令的 payload 与改动前逐字节一致，不含 `pullTaskId`、`groupExecutionId` 键。
19. 建群人闸门：RESERVED 建群人离线且在宽限内 → 执行行 defer `GROUP_CREATOR_RECONNECTING`，提交后触发一次 `reonlineReservedCreator`；触发失败不影响 defer 已提交。

**D. 任务侧角色等待**

20. `PullTaskOfflineRoleWaitPolicy` 覆盖全部分支（包括 offlineSince 为 null）。
21. 建群人离线不到 3 分钟 → defer `GROUP_CREATOR_RECONNECTING`，并且**不发建群命令**；超过 3 分钟或 TERMINAL → 执行行变为 FAILED `GROUP_CREATOR_OFFLINE`；建群后注销模式下 RESERVED 记录按 7.1 (b) 的核实结论处理。
22. 建群人上线事件 → 等待中执行行的 next_run_at 被提前到当前时间。
23. 拉手离线不到 30 秒 → 继续占名额；超过 30 秒 → 移出，原因 `PULLER_OFFLINE_TIMEOUT`；在途进群的拉手不移出。
24. **`PULL_EXECUTION` 自动补号（缺口 4 回归）**：计划 2 个拉手，2 个都已移出，分组里有 1 个在线可选账号 → 资源恢复退回 `MANAGER_PULLER_CONTACT`（直连模式退回 `DIRECT_PULLER_JOIN`），并插入新的拉手行。分组里没有可选账号 → 保持 `PULLER_UNAVAILABLE`。
25. 管理离线在宽限内 → 不换号；超时 → 执行 `replaceManager`。

**E. 开关回归（缺口 1 回归）**

26. 账号侧开关关闭：2 号账号被挤 → 落 6；7 号账号被挤 → 保持 7 并续上线（旧行为）；3 号账号被挤 → 落 6（旧行为）；不写熔断表；扫描返回 0；`takeoverBatch` 不调用 reset；`reonlineReservedCreator` 跳过。
27. 任务侧开关关闭：建群人离线时仍发建群命令，失败后 defer `GROUP_CREATOR_UNAVAILABLE`（旧行为）；建群后建群人离线时无限 defer；拉手不会超时移出；`PULL_EXECUTION` 不自动补号；管理离线立即换号；上线事件不触发新的唤醒 SQL。
28. 两个开关都关闭：对 A–D 中的代表性场景各跑一遍，结果与 2026-10-08 主干一致。

**F. 事务安全**

29. 事件路径续上线时代理分配失败 → 状态事件仍提交成功，账号保持 7 离线，之后由扫描补偿（仅当 5.3 的“必须核实”结论为会抛异常时才需要；无论结论如何，都要在 change 记录中写明）。

**G. 资源恢复事务一致性（用户确认补充，同组真实 Mapper H2 测试）**

30. 过期的正是当前粘性拉手：清空粘性指针、角色标记 REMOVED、执行行推进一起提交，`active_puller_group_account_id` 为 NULL。
31. 推进前租约丢失：重读复核失败返回 LOST，整个事务回滚，角色不是 REMOVED，粘性指针未被清空。状态、阶段、暂停及租约期限不符的复核分支也应覆盖。
32. 过期的不是当前粘性拉手：执行行 version 仅在最后推进时增加 1，其他行为保持不变。
33. 任务侧开关关闭：不执行超时移出，也不执行新增的重读路径。
34. `managerAdminCheck` 因建群人不可恢复终止执行行：`recover()` 直接返回终止结果，FAILED 及 `GROUP_CREATOR_OFFLINE` 一起提交，不再 defer 或 resume。

验证命令：`cd armada-api && mvn -Dtest='<相关测试类>' test`，全部完成后跑 `mvn test`。改 Mapper XML 前先 `xmllint --noout`。没有真实输出，不得宣称测试通过。

## 9. 实现顺序（每个阶段单独提交、可单独验证）

1. **阶段 1：数据与开关**。Flyway 迁移、实体字段、两个 Properties、`offline_since` 维护（5.1、5.2、第 6 节）。测试：A7、E 中的配置加载。
2. **阶段 2：自动抢登**。熔断组件、事件路径改造（含终态粘性、期望离线）、续上线防线、补偿扫描、`autoTakeover`/`takeoverBatch` 改造、事务安全核实（5.3、5.4）。测试：A1–A6、B8–B12、E26、F29。
3. **阶段 3：建群人、拉手与预留建群人**。`findRoleAvailability`、等待策略、上线唤醒、上线命令归属字段、`reonlineReservedCreator`，以及 7.1、7.2（含自动补号）、7.4。测试：B13、C14–C19、D20–D24、E27。
4. **阶段 4：管理**。7.3。测试：D25、E28。

## 10. 上线与验收（perf2）

- 部署前按 `deploy-verify` 流程确认目标环境是 perf2；上生产需要另行确认。
- 建议灰度：先只开账号侧开关，观察 1 天熔断量和上线命令量；再打开任务侧开关。
- 验收：
  - 被挤账号在 5 秒内自动重连（看 Zhuan 日志，从 `conflict replaced` 到 `tcp connected`）；
  - 互挤账号约 80 秒内熔断（`account_takeover_breaker.tripped_at` 非空，状态为 6）；
  - 封禁、解绑账号收到被挤事件后状态不变；
  - 构造建群人掉线：3 分钟内恢复则任务继续；超时则执行行变为 FAILED `GROUP_CREATOR_OFFLINE`；
  - 建群后注销模式：建群人被挤后由执行行拉起，outbox 没有拒绝记录；
  - 拉手掉线超过 30 秒后被替换，`PULL_EXECUTION` 阶段能自动补到新拉手，任务继续。
- 观察指标：每小时熔断账号数；`GROUP_CREATOR_OFFLINE`、`PULLER_OFFLINE_TIMEOUT` 数量；自动抢登上线命令量；outbox 拒绝数。
- 回滚：`ACCOUNT_AUTO_TAKEOVER_ENABLED=false` 加 `PULL_TASK_OFFLINE_ROLE_WAIT_ENABLED=false`，无需发版；必要时再回滚镜像并执行 rollback.sql。

## 11. 不在本期范围

- 普通链接模式下提权执行者（PROMOTER）的离线等待；站台（STATION）角色；`CREATOR_DELETE` 及之后阶段建群人离线的处理。
- 建群前建群人超时后自动改选其他建群人（本期直接失败）。
- 熔断后定时自动恢复。
- 前端改动：原因说明随 `reason_message` 落库展示；如果前端有原因码映射表，另行补充。
