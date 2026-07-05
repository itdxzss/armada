# 账号抢登状态与一键抢登实施计划

> **给执行 agent：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 按任务逐项执行本计划。步骤使用 checkbox（`- [ ]`）追踪进度。

**目标：** 将 WhatsApp / Baileys `440 connectionReplaced` 从解绑/重认证逻辑中拆出来，新增 Armada 账号状态「被抢登」「抢登中」，并支持账号列表筛选、一键抢登、抢登中持续自动上线。

**架构：** 协议层只负责识别 440 并通过现有 Kafka 事件上报 `LOGIN_REPLACED` 事实，同时释放当前 runtime socket slot；Armada 后端负责业务状态落库、校验、代理分配、上线 outbox 投递和抢登续上线；前端只负责展示、筛选和批量操作交互。

**技术栈：** `armada-protocol/protocol-layer` 使用 TypeScript/Jest/OpenAPI；`armada/armada-api` 使用 Java 17/Spring Boot/MyBatis/JUnit/DbTest；`wheel-saas-pure-web` 使用 Vue 3 + TypeScript + Element Plus。

---

### 任务 0：执行保护

**文件：**
- 阅读：`/Users/daishuaishuai/IdeaProjects/armada/docs/superpowers/specs/2026-07-04-account-login-replaced-takeover-design.md`
- 阅读：`/Users/daishuaishuai/IdeaProjects/armada-protocol/README.md`
- 阅读：`/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/AGENTS.md`

- [ ] **步骤 1：确认仓库状态和项目规则**

执行前先确认三个项目的当前工作区状态：

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol
git status --short
cd /Users/daishuaishuai/IdeaProjects/armada
git status --short
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git status --short
```

不要回滚无关改动。计划编写时已知：

```text
armada 有无关 .claude 工作区项和 marketing 计划文档
wheel-saas-pure-web 有无关 marketing-template API 改动
```

- [ ] **步骤 2：保持事件驱动边界**

本需求不新增 scheduler。抢登循环固定为：

```text
协议层发现 440/离线 -> account.state_changed -> Armada 落状态 -> account_state=7 时 Armada 写上线 outbox
```

协议层不自行循环上线。Armada 是凭据、代理、租户状态和 outbox 的唯一编排方。

### 任务 1：协议层契约和 440 语义翻译

**文件：**
- 修改：`/Users/daishuaishuai/IdeaProjects/armada-protocol/openapi/protocol-v1.yaml`
- 生成：`/Users/daishuaishuai/IdeaProjects/armada-protocol/openapi/generated/types.ts`
- 生成：`/Users/daishuaishuai/IdeaProjects/armada-protocol/openapi/generated/aliases.ts`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/types/api.ts`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/error/semantic-codes.ts`
- 测试：`/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/error/semantic-codes.test.ts`

- [ ] **步骤 1：先写失败测试**

新增或扩展 `semantic-codes.test.ts`，覆盖 `DisconnectReason.connectionReplaced`：

```ts
expect(translateDisconnect(DisconnectReason.connectionReplaced, 'connection replaced')).toEqual({
  semantic: 'LOGIN_REPLACED',
  reconnectClass: 'C',
  needReauth: false,
  rawCode: 440,
  rawReason: 'connection replaced'
})
```

预期 RED：当前实现返回 `NEED_REAUTH` 且 `needReauth=true`。

- [ ] **步骤 2：补充协议类型和 OpenAPI 枚举**

在 `src/types/api.ts` 中把 `LOGIN_REPLACED` 加入：

```text
SemanticErrorCode
ACCOUNT_STATES
```

在 `openapi/protocol-v1.yaml` 中同步补充所有涉及状态和语义码的枚举。断线码表使用以下描述：

```text
440 | LOGIN_REPLACED | 当前连接被另一端登录替换 | 业务侧可发起抢登；凭据不视为失效
```

重新生成 OpenAPI 类型：

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/openapi
./regenerate-types.sh
```

- [ ] **步骤 3：修改 440 翻译逻辑**

在 `translateDisconnect` 中将 440 改为：

```ts
case DisconnectReason.connectionReplaced:
  return {
    semantic: 'LOGIN_REPLACED',
    reconnectClass: 'C',
    needReauth: false,
    rawCode: 440,
    rawReason: reason ?? 'connection replaced'
  }
```

保留 `reconnectClass: 'C'`，因为当前 socket 对协议 worker 来说需要终止；但不能设置 `needReauth=true`，440 不代表 creds 作废，也不能发布 `account.need_reauth`。

- [ ] **步骤 4：验证协议翻译**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- src/error/semantic-codes.test.ts --runInBand
```

预期 GREEN：440 返回 `LOGIN_REPLACED`，原有登录失效类断线仍返回 `NEED_REAUTH`。

### 任务 2：协议层状态机和 AccountManager 行为

**文件：**
- 修改：`/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/state-machine.ts`
- 新增：`/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/state-machine.test.ts`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/offline-diagnosis.ts`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.ts`
- 测试：`/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/offline-diagnosis.test.ts`
- 测试：`/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.heartbeat.test.ts`

- [ ] **步骤 1：先写状态机和诊断失败测试**

新增 `state-machine.test.ts`，断言这些转换合法：

```text
VERIFYING -> LOGIN_REPLACED
ONLINE -> LOGIN_REPLACED
RECONNECTING -> LOGIN_REPLACED
STALE -> LOGIN_REPLACED
OFFLINE -> LOGIN_REPLACED
```

在 `offline-diagnosis.test.ts` 增加测试：`target=LOGIN_REPLACED` 或 `semantic=LOGIN_REPLACED` 时，诊断结果必须是独立的 `LOGIN_REPLACED`，不能落到宽泛 `NEED_REAUTH`。

- [ ] **步骤 2：先写 AccountManager 回归测试**

在 `account-manager.heartbeat.test.ts` 中模拟 Baileys 440 close，并带上 online business reference。断言发布：

```ts
expect(published).toContainEqual(expect.objectContaining({
  event: 'account.state_changed',
  accountId: 'acc_login_replaced',
  data: expect.objectContaining({
    to: 'LOGIN_REPLACED',
    semantic: 'LOGIN_REPLACED',
    rawCode: 440,
    source: 'batch_online',
    onlineAttemptId: 'oa_login_replaced'
  })
}))
```

同时断言没有发布：

```ts
expect(published).not.toContainEqual(expect.objectContaining({
  event: 'account.need_reauth',
  accountId: 'acc_login_replaced'
}))
```

还要断言 runtime slot 被标记为 `LOGIN_REPLACED`，并且没有删除 creds store 和 keys store。

- [ ] **步骤 3：加入 `LOGIN_REPLACED` 状态**

更新 `state-machine.ts` 的状态注释和 `VALID_TRANSITIONS`。不要把 `LOGIN_REPLACED` 加入：

```text
TERMINAL_RECONNECT_STATES
isTerminal()
needsReauth()
```

原因：它终止当前 socket，但不能阻止 Armada 后续下发新的 online 命令。

- [ ] **步骤 4：在 AccountManager 中优先处理 `LOGIN_REPLACED`**

在 `handleConnectionUpdate` 里、`translation.needReauth` 分支之前加入：

```ts
if (translation.semantic === 'LOGIN_REPLACED') {
  const from = ctx.state.state
  if (this.publishStateChange(ctx, 'LOGIN_REPLACED', reason, translation)) {
    this.publishOfflineDiagnosed(ctx, from, 'LOGIN_REPLACED', reason, translation)
  }
  await this.releaseRuntimeSlot(ctx, 'LOGIN_REPLACED', reason)
  return
}
```

该分支不得：

```text
发布 account.need_reauth
调用 credsStore.delete
调用 keysStore.clear
调用 registry.unassign
```

- [ ] **步骤 5：验证协议层行为**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- src/error/semantic-codes.test.ts src/worker/state-machine.test.ts src/worker/offline-diagnosis.test.ts src/worker/account-manager.heartbeat.test.ts --runInBand
npm run lint
```

预期 GREEN：440 只产出 `LOGIN_REPLACED`，不会产出 `account.need_reauth`，TypeScript 编译通过。

### 任务 3：Armada 状态常量、迁移和 Mapper

**文件：**
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/entity/AccountStateCode.java`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/vo/AccountListVO.java`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/model/vo/AccountListVoRow.java`
- 新增：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/db/migration/V039__account_login_replaced_takeover_state.sql`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/mapper/AccountStateMapper.java`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountStateMapper.xml`
- 测试：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/AccountStateEventServiceImplDbTest.java`

- [ ] **步骤 1：新增状态常量**

```java
public static final int LOGIN_REPLACED = 6; // 被抢登
public static final int TAKING_OVER = 7;    // 抢登中
```

同步修正仍把 440 描述成封禁/解绑的旧注释。

- [ ] **步骤 2：新增 Flyway 迁移**

创建 `V039__account_login_replaced_takeover_state.sql`，只更新字段注释，不改现有数据：

```text
1新增 2正常 3封禁 4导出 5解绑 6被抢登 7抢登中
```

- [ ] **步骤 3：新增 Mapper 原子操作**

在 `AccountStateMapper` 和 XML 中新增：

```java
List<AccountState> selectByAccountIds(@Param("accountIds") List<Long> accountIds);

int markTakingOverByAccountIds(@Param("accountIds") List<Long> accountIds,
                               @Param("expectedState") Integer expectedState,
                               @Param("targetState") Integer targetState,
                               @Param("updatedAt") Long updatedAt);

int updateLoginAndAccountState(AccountState row);
```

SQL 行为：

```text
selectByAccountIds: 读取 account_id、account_state、login_state、mute_status、last_state_sync_time
markTakingOverByAccountIds: 只把 account_state=6 且 mute_status IS NULL 的账号改成 7
updateLoginAndAccountState: 同时更新 login_state 和 account_state，不改代理快照字段
```

- [ ] **步骤 4：补 DbTest**

在 `AccountStateEventServiceImplDbTest` 或独立 mapper DbTest 中验证状态值 `6`、`7` 能写入并读回。

### 任务 4：Armada Kafka 状态事件透传 `source`

**文件：**
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountStateChangedEvent.java`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/AccountStateChangedEvent.java`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumer.java`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/kafka/consumer/account/AccountStateChangedSinkAdapter.java`
- 测试：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumerTest.java`

- [ ] **步骤 1：先写解析失败测试**

扩展 `onMessage_stateChangedEnvelope_dispatchesParsedStateChangedEvent`，在 JSON 的 `data` 中加入：

```json
"source": "batch_offline"
```

断言：

```java
assertThat(event.source()).isEqualTo("batch_offline");
```

预期 RED：当前事件 record 没有 `source`。

- [ ] **步骤 2：record 和 adapter 透传 source**

给 `ProtocolAccountStateChangedEvent` 和 `AccountStateChangedEvent` 增加可空 `source` 字段，并在 `AccountStateChangedSinkAdapter` 中传递。

兼容旧事件：当 `data.source` 不存在时，`source()` 为 `null`。

- [ ] **步骤 3：验证事件解析**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=ProtocolAccountEventConsumerTest test
```

预期 GREEN：原有事件解析仍通过，`source` 能被保留。

### 任务 5：Armada 状态收敛逻辑

**文件：**
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountStateEventServiceImpl.java`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/state/ProxyFailedAutoReonlineSideEffect.java`
- 测试：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/AccountStateEventServiceImplDbTest.java`
- 测试：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/impl/AccountStateEventServiceImplTest.java`

- [ ] **步骤 1：先写 440 DbTest**

新增：

```text
applyStateChanged_loginReplaced_marksReplacedAndOffline
applyStateChanged_needReauthRaw440_marksReplacedAndOfflineForBackwardCompatibility
```

断言：

```text
login_state = OFFLINE
account_state = LOGIN_REPLACED
state_source = LOGIN_REPLACED
invalidated_at = occurredAt
```

兼容测试使用 `to=NEED_REAUTH`、`semantic=NEED_REAUTH`、`rawCode=440`，结果不能再落成 `UNBOUND`。

- [ ] **步骤 2：先写抢登中行为测试**

覆盖：

```text
抢登中 + ONLINE -> login_state=ONLINE，account_state 仍为 TAKING_OVER
抢登中 + LOGIN_REPLACED -> account_state 仍为 TAKING_OVER，并请求抢登续上线
抢登中 + 普通 OFFLINE 且 source=batch_online -> account_state 仍为 TAKING_OVER，并请求抢登续上线
抢登中 + OFFLINE 且 source=batch_offline -> account_state 变为 LOGIN_REPLACED，不续上线
抢登中 + PROXY_FAILED -> account_state 仍为 TAKING_OVER，只走现有代理失败重上线路径
```

数据库状态用 DbTest 断言；服务调用用 mock 单测断言。

- [ ] **步骤 3：实现 440 优先识别**

在 `AccountStateEventServiceImpl` 中，先于原有 `NEED_REAUTH` 分支判断：

```java
private static boolean isLoginReplaced(AccountStateChangedEvent event) {
    return equalsIgnoreCase(event.to(), "LOGIN_REPLACED")
            || equalsIgnoreCase(event.semantic(), "LOGIN_REPLACED")
            || Integer.valueOf(440).equals(event.rawCode());
}
```

非抢登中账号收到 440 时落库：

```text
login_state=OFFLINE
account_state=LOGIN_REPLACED
state_source=LOGIN_REPLACED
invalidated_at=occurredAt
```

`rawCode=403` 继续封禁；其它 `NEED_REAUTH` 继续解绑。

- [ ] **步骤 4：实现抢登中收敛规则**

当前 `account_state=TAKING_OVER` 时：

```text
ONLINE:
  login_state=ONLINE
  account_state 保持 TAKING_OVER
  不调用 markOnlineNormalState

LOGIN_REPLACED 或 rawCode=440:
  login_state=OFFLINE
  account_state 保持 TAKING_OVER
  释放当前绑定 IP
  以 login_replaced_takeover 请求续上线

OFFLINE 且 source=batch_offline/manual_offline:
  login_state=OFFLINE
  account_state=LOGIN_REPLACED
  不续上线

OFFLINE 或 RATE_LIMITED 且不是用户停止来源:
  login_state=OFFLINE
  account_state 保持 TAKING_OVER
  释放当前绑定 IP
  以 offline_takeover/rate_limited_takeover 请求续上线

PROXY_FAILED:
  login_state=OFFLINE
  account_state 保持 TAKING_OVER
  只让 ProxyFailedAutoReonlineSideEffect 调 reonlineAfterProxyFailure
```

禁言停止门槛：抢登续上线前读取 `mute_status`。`mute_status=1` 或 `2` 时不再投递抢登续上线。

- [ ] **步骤 5：保持副作用顺序**

状态先落库，再执行 `releaseIpIfOffline` 和 side effect。outbox service 已经在事务提交后 dispatch Kafka，所以同一事务内创建的续上线命令不会早于状态提交发布。

更新 `ProxyFailedAutoReonlineSideEffect`：当事件来源是用户停止来源时不自动重上线；当账号已不满足抢登/代理失败自动重上线条件时也不重投。

- [ ] **步骤 6：验证状态收敛**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
./dbtest.sh 'AccountStateEventServiceImplDbTest'
mvn -q -Dtest=AccountStateEventServiceImplTest test
```

预期 GREEN：440 不再解绑；抢登中 ONLINE 不自动恢复正常；手动离线停止抢登。

### 任务 6：Armada 一键抢登服务、冷却和接口

**文件：**
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/AccountOnlineCommandService.java`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`
- 新增：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountTakeoverReonlineCooldown.java`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/controller/AccountController.java`
- 测试：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java`
- 测试：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/service/AccountOnlineCommandServiceImplDbTest.java`

- [ ] **步骤 1：先写服务测试**

新增：

```text
takeoverBatch_allReplaced_marksTakingOverAndEnqueuesBatchOnline
takeoverBatch_emptyIds_throwsValidation
takeoverBatch_containsNonReplaced_throwsValidationWithSelectionMessage
reonlineForTakeover_currentTakingOver_enqueuesSingleOnlineWithTakeoverSource
reonlineForTakeover_notTakingOver_skipsWithoutOutbox
reonlineForTakeover_withinCooldown_skipsWithoutOutbox
```

后端非被抢登校验错误文案：

```text
当前所选账号存在非被抢登状态，请重新选择
```

- [ ] **步骤 2：扩展服务接口**

```java
AccountBatchOnlineVO takeoverBatch(List<Long> accountIds);

AccountOnlineVO reonlineForTakeover(Long accountId,
                                    String failedOnlineAttemptId,
                                    String source);
```

抢登续上线来源值：

```text
login_replaced_takeover
offline_takeover
rate_limited_takeover
```

- [ ] **步骤 3：实现一键抢登**

`takeoverBatch` 流程：

```text
复用现有 500 个账号上限和 ID 规范化
加载 active accounts
批量读取账号状态
要求每个账号 account_state=LOGIN_REPLACED 且 mute_status IS NULL
用 markTakingOverByAccountIds 从 6 原子改成 7
复用 enqueueOnlineBatch(ids, "login_replaced_takeover", allocateOnlineEndpoints(...))
返回 AccountBatchOnlineVO
```

该方法使用事务。状态更新和 outbox 插入必须一起提交；outbox 失败时状态回滚。

- [ ] **步骤 4：实现抢登续上线**

`reonlineForTakeover` 行为：

```text
读取账号状态
account_state 不是 TAKING_OVER 时返回跳过结果，不写 outbox
mute_status 为 1/2 时返回跳过结果，不写 outbox
通过 15 秒账号级冷却后，调用 onlineWithSource(accountId, source, failedOnlineAttemptId)
```

除现有代理失败路径外，不扩展 `previousOnlineAttemptId` 语义；`failedOnlineAttemptId` 先用于日志和后续扩展。

- [ ] **步骤 5：新增冷却组件**

新增内存冷却组件：

```java
boolean tryAcquire(Long accountId, long nowMillis)
```

窗口固定为 `15_000` 毫秒，key 为账号 ID。用户主动点击的一键抢登不走该冷却。

- [ ] **步骤 6：新增 Controller 接口**

在 `AccountController` 增加：

```text
POST /api/accounts/batch-takeover
```

请求体：

```json
{"ids":[100,101]}
```

响应沿用 `AccountBatchOnlineVO`。前端置灰只做体验优化，后端校验必须完整。

- [ ] **步骤 7：验证服务和接口**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=AccountOnlineCommandServiceImplTest,ProtocolAccountEventConsumerTest test
./dbtest.sh 'AccountOnlineCommandServiceImplDbTest'
```

预期 GREEN：批量抢登校验、状态更新、outbox 投递和事件解析全部通过。

### 任务 7：Armada 列表筛选和统计兼容

**文件：**
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountMapper.xml`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/mapper/AccountListMapperDbTest.java`
- 修改：`/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/test/java/com/armada/account/mapper/AccountStatsMapperDbTest.java`

- [ ] **步骤 1：验证列表筛选 6/7**

`AccountMapper.xml` 已有：

```xml
<if test="accountState != null">
  AND s.account_state = #{accountState}
</if>
```

补 DbTest：`account_state=6` 只返回被抢登账号，`account_state=7` 只返回抢登中账号。

- [ ] **步骤 2：保持统计口径稳定**

检查 stats SQL。本需求不要求把「被抢登」「抢登中」并入禁言/封禁/解绑/导出统计，除非现有 total 统计遗漏这两类账号，否则保持原统计口径。

- [ ] **步骤 3：验证列表和统计**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
./dbtest.sh 'AccountListMapperDbTest'
./dbtest.sh 'AccountStatsMapperDbTest'
```

预期 GREEN：列表能筛选 6/7，现有统计不回退。

### 任务 8：前端 API、展示、筛选和一键抢登

**文件：**
- 修改：`/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/account.ts`
- 修改：`/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/account.test.ts`
- 修改：`/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/account-display.ts`
- 修改：`/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/account-display.test.ts`
- 修改：`/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/composables/useAccountListPage.ts`
- 修改：`/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/components/AccountListTable.vue`
- 修改：`/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/constants.ts`
- 修改：`/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/index.vue`

- [ ] **步骤 1：扩展 API 类型和方法**

```ts
export type AccountState = 1 | 2 | 3 | 4 | 5 | 6 | 7;
```

新增：

```ts
export function batchTakeoverTenantAccounts(ids: number[]): Promise<TenantAccountBatchCommandResult>
```

请求：

```text
POST /api/accounts/batch-takeover
```

请求体：

```ts
{ ids }
```

在 `account.test.ts` 中按现有 node:test 风格增加 API 调用断言。

- [ ] **步骤 2：更新状态标签**

`account-display.ts` 增加：

```text
6 -> 被抢登
7 -> 抢登中
```

Tag 类型：

```text
被抢登 -> warning
抢登中 -> primary
```

禁言显示优先级不变：`mute_status=6h/24h` 时仍显示「禁言6小时」「禁言24小时」。

- [ ] **步骤 3：修改列名**

把账号列表原「状态」列统一改为「账号状态」，位置：

```text
constants.ts
AccountListTable.vue
```

- [ ] **步骤 4：扩展搜索条件**

`useAccountListPage.ts` 中：

```text
accountStatusOptions 增加 被抢登、抢登中
accountStateMap 增加 被抢登 -> 6，抢登中 -> 7
```

搜索行为：

```text
禁言6小时/禁言24小时 -> 继续走 muteStatus=1/2
被抢登/抢登中 -> 走 account_state=6/7
```

- [ ] **步骤 5：新增批量操作「一键抢登」**

批量操作下拉新增「一键抢登」。

禁用规则：

```text
未选择账号 -> 禁用
所选账号全部 account_state=6 且 mute_status 为空 -> 可点
存在任意非被抢登账号 -> 禁用
```

由于 Element Plus 禁用的 dropdown item 不会触发 command，`handleBatchAction("takeover")` 也必须兜底：

```text
未选择账号：直接返回
存在非被抢登账号：ElMessage.warning("当前所选账号存在非被抢登状态，请重新选择。")
```

前端提示文案必须精确为：

```text
当前所选账号存在非被抢登状态，请重新选择。
```

- [ ] **步骤 6：调用后端并刷新**

`handleBatchAction("takeover")` 调用 `batchTakeoverTenantAccounts(ids)`。成功后按批量登录的交互处理：

```text
展示 accepted/submitted 数
刷新账号列表
刷新统计
清理表格选择状态
```

不得在页面里直接使用 axios；API 请求只能放在 `src/api`。

- [ ] **步骤 7：验证前端**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm typecheck
pnpm build
```

预期 GREEN：TypeScript 和 Vue 编译通过。`account.test.ts`、`account-display.test.ts` 按现有 node:test 风格同步更新；实际执行阶段若本地已有 TS node:test runner，则额外运行这两个测试。

### 任务 9：跨项目验证和 diff 审查

**文件：**
- 阅读：`armada-protocol` diff
- 阅读：`armada` diff
- 阅读：`wheel-saas-pure-web` diff

- [ ] **步骤 1：协议层验证**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- src/error/semantic-codes.test.ts src/worker/state-machine.test.ts src/worker/offline-diagnosis.test.ts src/worker/account-manager.heartbeat.test.ts --runInBand
npm run lint
```

- [ ] **步骤 2：Armada 后端验证**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -q -Dtest=ProtocolAccountEventConsumerTest,AccountStateEventServiceImplTest,AccountOnlineCommandServiceImplTest test
./dbtest.sh 'AccountStateEventServiceImplDbTest'
./dbtest.sh 'AccountOnlineCommandServiceImplDbTest'
./dbtest.sh 'AccountListMapperDbTest'
```

- [ ] **步骤 3：前端验证**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm typecheck
pnpm build
```

- [ ] **步骤 4：行为 diff 审查**

```bash
git -C /Users/daishuaishuai/IdeaProjects/armada-protocol diff -- openapi protocol-layer/src
git -C /Users/daishuaishuai/IdeaProjects/armada diff -- armada-api/src/main armada-api/src/test docs/superpowers/plans/2026-07-04-account-login-replaced-takeover.md
git -C /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web diff -- src/api/account.ts src/api/account.test.ts src/views/account/index
```

审查清单：

```text
440 不再映射为 NEED_REAUTH
LOGIN_REPLACED 不发布 account.need_reauth
LOGIN_REPLACED 不删除 creds/keys
Armada 兼容 NEED_REAUTH + rawCode=440，且不再落成解绑
TAKING_OVER + ONLINE 仍保持 TAKING_OVER
manual/batch offline 停止抢登
抢登中普通 OFFLINE/RATE_LIMITED 会续上线
PROXY_FAILED 只走代理失败自动重上线路径
前端筛选能发送 account_state=6/7
前端一键抢登只允许选择被抢登账号
```

### 任务 10：部署和冒烟

**文件：**
- 若项目已有发布说明或 runbook，补充本次协议/账号状态变更说明。

- [ ] **步骤 1：部署顺序**

推荐部署顺序：

```text
Armada 后端 -> 协议层 -> 前端
```

原因：后端先兼容旧协议事件 `NEED_REAUTH + rawCode=440`，再让协议层开始发送 `LOGIN_REPLACED`，最后开放前端操作入口。

- [ ] **步骤 2：测试环境冒烟**

```text
选择一个账号并上线
模拟或触发 440
确认 account_state=6，前端显示「被抢登」
点击「一键抢登」
确认 account_state=7，login_state 先变「待上线」再变「在线」
再次模拟 440
确认生成新的 online outbox，account_state 仍为 7
点击「离线」
确认 account_state 回到 6，且不再写新的抢登续上线 outbox
```

冒烟过程中不得查询、打印或外发账号凭据内容。
