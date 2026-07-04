# 账号抢登状态与一键抢登设计

## 目标

Armada 需要把 WhatsApp / Baileys `440 connectionReplaced` 从“解绑/需重登”中拆出来，表达为明确的抢登语义：

- 协议层 440 统一上报为 `LOGIN_REPLACED`，不再上报 `NEED_REAUTH`。
- Armada 账号状态新增「被抢登」「抢登中」。
- 账号列表、筛选和批量操作支持这两个状态。
- 用户对「被抢登」账号执行「一键抢登」后，Armada 持续参与抢登闭环；再次被抢登时自动继续上线，直到用户手动离线或账号进入停止状态。

本设计采用事件驱动，不新增轮询 scheduler。协议层只负责发现 440 并上报事实；Armada 负责业务判断、状态落库、代理分配和再次投递上线命令。

## 现状与根因

当前协议层在 `translateDisconnect` 中把 Baileys `DisconnectReason.connectionReplaced`，也就是 raw code `440`，翻译为：

- `semantic=NEED_REAUTH`
- `needReauth=true`
- `reconnectClass=C`

`AccountManager` 收到 `needReauth=true` 后会发布 `account.state_changed`，目标状态为 `NEED_REAUTH`。Armada 后端消费该事件时，`AccountStateEventServiceImpl` 现有规则是：

- `NEED_REAUTH + rawCode=403` 收敛为封禁。
- 其它 `NEED_REAUTH` 收敛为解绑。

所以 440 被误落成解绑。真实语义不是账号凭据失效，也不是当前租户主动解绑，而是另一端登录占用了同一 WhatsApp 账号，需要独立表达和处理。

## 状态口径

### 协议层状态

协议层新增状态和语义码：

| 名称 | 含义 |
| --- | --- |
| `LOGIN_REPLACED` | WhatsApp 返回 440，当前连接被另一端登录替换。 |

协议层处理规则：

- `440 connectionReplaced` 翻译为 `LOGIN_REPLACED`。
- `needReauth=false`。440 不代表 creds 作废。
- 不发布 `account.need_reauth`。
- 当前 socket 和 worker runtime slot 释放，避免旧连接继续占资源。
- 不删除 creds / keys。
- 通过现有 `account.state_changed` 事件回传 Armada，`to=LOGIN_REPLACED`，`semantic=LOGIN_REPLACED`，`rawCode=440`。

协议状态机需要允许正在连接或在线中的账号进入 `LOGIN_REPLACED`。最小转换：

- `VERIFYING -> LOGIN_REPLACED`
- `ONLINE -> LOGIN_REPLACED`
- `RECONNECTING -> LOGIN_REPLACED`
- `STALE -> LOGIN_REPLACED`

如果现有代码路径可能从 `OFFLINE` 处理延迟 close，也允许 `OFFLINE -> LOGIN_REPLACED`，避免丢掉已经确认的 440 事实。

### Armada 账号状态

`account_state.account_state` 新增：

| 值 | 含义 |
| --- | --- |
| `6` | 被抢登 |
| `7` | 抢登中 |

现有状态保留：

| 值 | 含义 |
| --- | --- |
| `1` | 新增 |
| `2` | 正常 |
| `3` | 封禁 |
| `4` | 导出 |
| `5` | 解绑 |

`login_state` 不新增值，继续使用：

| 值 | 含义 |
| --- | --- |
| `1` | 在线 |
| `2` | 离线 |
| `3` | 待上线 |

含义边界：

- 「被抢登」是账号生命周期状态，表示最近一次协议事实是 440。
- 「抢登中」是 Armada 本地业务状态，表示用户要求系统持续把该账号抢回。
- 上线命令写入 outbox 后，`login_state` 仍按现有规则变为 `3=待上线`。
- 抢登中账号收到 `ONLINE`，登录状态变为 `1=在线`，账号状态继续保持 `7=抢登中`，因为抢登流程不会自动停止。
- 非抢登中账号收到 `ONLINE`，账号状态按现有逻辑收敛为 `2=正常`。
- 再次 440 时，如果账号当前是 `7=抢登中`，保持抢登中并再次上线；如果不是抢登中，落为 `6=被抢登`。

## 数据流

### 普通账号被抢登

1. 协议层 socket close，raw code 为 440。
2. 协议层状态变更为 `LOGIN_REPLACED`，发布 `account.state_changed`。
3. Armada 消费事件：
   - 更新 `login_state=2`。
   - 更新 `account_state=6`。
   - `state_source=LOGIN_REPLACED`。
   - `last_state_sync_time=event.occurredAt`。
4. 账号列表展示「被抢登」。

该路径不自动上线，因为用户尚未明确发起抢登。

### 用户一键抢登

1. 前端用户在账号列表勾选账号。
2. 前端只在全部选中账号都是「被抢登」时允许点击「一键抢登」。
3. 前端调用 `POST /api/accounts/batch-takeover`，请求体沿用账号 ID 列表：

```json
{
  "ids": [100, 101]
}
```

4. Armada 后端校验：
   - ID 列表不能为空。
   - 一次最多 500 个，沿用账号批量生命周期命令上限。
   - 所有账号必须存在且未软删。
   - 所有账号 `account_state=6`。
5. 校验通过后，Armada 在本地事务中把这些账号改为 `account_state=7`。
6. Armada 复用现有批量上线 outbox 编排，写入 `account.online.requested` 命令。
7. 写入 outbox 成功后，`login_state=3`。
8. 前端刷新列表，展示「抢登中」和「待上线」。

后端必须做完整校验，前端禁用按钮只做体验优化。

### 抢登中再次被抢登

1. 协议层再次收到 440，继续发布 `LOGIN_REPLACED`。
2. Armada 消费事件，查询当前账号状态。
3. 如果当前 `account_state=7`：
   - 保持 `account_state=7`。
   - 写 `login_state=2`，记录本次 `last_state_sync_time`。
   - 立即调用抢登续上线服务，重新分配代理并写上线 outbox。
   - 上线命令成功写入 outbox 后，账号回到 `login_state=3`。

该路径就是持续抢登逻辑，不需要 scheduler。15-20 秒心跳或 socket close 只负责让协议层发现 440；是否继续上线由 Armada 基于账号状态决定。

### 抢登中普通离线

抢登流程不会因为普通离线自动停止。如果账号当前 `account_state=7`，Armada 收到协议层 `OFFLINE`、`PROXY_FAILED`、`RATE_LIMITED` 等非在线事件时：

- 保持 `account_state=7`。
- 更新 `login_state=2`。
- 在状态落库后再次投递上线命令。

`PROXY_FAILED` 继续优先走现有代理失败自动重上线逻辑，标记旧代理不可用并重新分配代理。普通 `OFFLINE` 和 `RATE_LIMITED` 走抢登续上线逻辑。`RECONNECTING` 是协议层短暂态，不直接触发 Armada 重新上线，避免和协议层内部快速重连打架。

### 抢登成功

1. 协议层连接成功，发布 `ONLINE`。
2. Armada 消费 `ONLINE`：
   - `login_state=1`。
   - 当前 `account_state=7` 时保持「抢登中」。
   - 当前不是抢登中时按现有逻辑收敛为「正常」。
   - 只在收敛为正常时清空 `invalidated_at`。
3. 前端对抢登中账号展示「抢登中」「在线」。

成功后如果再次被抢登，因为账号仍是「抢登中」，Armada 会继续投递上线命令。只有停止条件发生时才退出抢登中。

## 停止条件

抢登持续逻辑只在 `account_state=7` 时生效。以下动作或状态会停止：

| 触发 | 结果 |
| --- | --- |
| 用户手动离线 | `account_state=6`，`login_state=2`，停止续上线。 |
| 封禁 | `account_state=3`，停止续上线。 |
| 导出 | `account_state=4`，停止续上线。 |
| 禁言 6 小时 | `mute_status=1`，停止续上线。 |
| 禁言 24 小时 | `mute_status=2`，停止续上线。 |
| 解绑 | `account_state=5`，停止续上线。 |

手动离线回到「被抢登」，不是「正常」。离线只是用户停止继续抢登，不代表账号已经恢复健康。

手动离线与普通协议离线都可能表现为 `to=OFFLINE`。实现必须读取协议事件 data 中的命令 `source` 字段：

- `source=batch_offline` 或 `source=manual_offline`：视为用户停止抢登。
- 其它 source 或空 source：视为协议事实离线，抢登中账号继续上线。

如果抢登中账号上线失败但不是 440，例如代理失败或普通离线，仍保持 `account_state=7` 并继续上线。为避免异常网络造成无限快速重投，续上线实现需要同账号短窗口冷却。

## 后端接口

### 新增一键抢登

`POST /api/accounts/batch-takeover`

请求体：

```json
{
  "ids": [100, 101]
}
```

响应沿用 `AccountBatchOnlineVO`，表示本次上线命令 outbox 受理结果。

失败：

- 空列表：`VALIDATION`，账号 ID 列表不能为空。
- 存在非被抢登账号：`VALIDATION`，消息为「当前所选账号存在非被抢登状态，请重新选择」。
- 账号不存在、凭据不存在、代理分配失败、outbox 写入失败：沿用现有上线链路错误。

### AccountOnlineCommandService

新增抢登专用入口：

- `takeoverBatch(List<Long> accountIds)`
- `reonlineForTakeover(Long accountId, String failedOnlineAttemptId, String source)`

`takeoverBatch` 负责校验全部 `account_state=6`，改为 `7` 后复用批量上线 outbox。

`reonlineForTakeover` 只允许当前 `account_state=7` 的账号调用。它不改变账号状态，只重新投递上线命令。source 按触发原因传入：

- `login_replaced_takeover`：收到 `LOGIN_REPLACED` / rawCode 440。
- `offline_takeover`：收到普通 `OFFLINE`。
- `rate_limited_takeover`：收到 `RATE_LIMITED`。

`PROXY_FAILED` 继续使用现有 `proxy_failed_reonline` source，避免重复建立第二条代理失败重试语义。

## Armada 事件处理

`AccountStateEventServiceImpl` 新增 `LOGIN_REPLACED` 分支，优先于普通非在线登录态更新。

`ProtocolAccountStateChangedEvent`、`AccountStateChangedEvent` 需要新增 `source` 字段，从协议事件 data 中读取。该字段用于区分用户手动离线和协议普通离线。

伪代码：

```text
if event.to == LOGIN_REPLACED or event.semantic == LOGIN_REPLACED or rawCode == 440:
    current = select account_state
    if stale: skip
    if current.account_state == TAKING_OVER:
        update login_state=OFFLINE, keep account_state=TAKING_OVER
        release current bound IP if needed
        after commit enqueue reonlineForTakeover(accountId, event.onlineAttemptId, login_replaced_takeover)
    else:
        update login_state=OFFLINE, account_state=LOGIN_REPLACED
        release current bound IP if needed
    apply side effects

if current.account_state == TAKING_OVER and event.to == OFFLINE and event.source in (batch_offline, manual_offline):
    update login_state=OFFLINE, account_state=LOGIN_REPLACED
    stop takeover

if current.account_state == TAKING_OVER and event.to in (OFFLINE, PROXY_FAILED, RATE_LIMITED):
    update login_state=OFFLINE, keep account_state=TAKING_OVER
    apply state-specific side effects
    after commit enqueue takeover reonline when not already handled by PROXY_FAILED side effect

if current.account_state == TAKING_OVER and event.to == ONLINE:
    update login_state=ONLINE, keep account_state=TAKING_OVER
    do not mark normal
```

重上线要在状态更新事务提交后执行，避免 outbox 成功但状态回滚，或者状态未提交时另一个事件读到旧状态。实现使用 Spring `TransactionSynchronizationManager` 注册 after-commit 回调；如果当前方法后续拆分为无事务调用，也要保持“状态落库成功后再发上线命令”的顺序。

IP 处理：

- 440 发生时当前连接已经被替换，旧代理绑定需要释放。
- 抢登续上线会重新分配代理，避免复用可能已经失效或被风控的旧代理。

## 协议层实现点

需要改动：

- `types/api.ts`：`SemanticErrorCode` 和 `ACCOUNT_STATES` 加 `LOGIN_REPLACED`。
- OpenAPI `AccountState` 枚举加 `LOGIN_REPLACED`，并重新生成类型。
- `state-machine.ts`：合法转换矩阵加 `LOGIN_REPLACED`。
- `semantic-codes.ts`：440 翻译为 `LOGIN_REPLACED`。
- `account-manager.ts`：对 `LOGIN_REPLACED` 使用独立分支发布 `account.state_changed` 和 `account.offline_diagnosed`，释放 runtime slot，不发布 `account.need_reauth`，不删除 creds。
- `offline-diagnosis.ts`：把 440 诊断为 `LOGIN_REPLACED`，诊断类型使用 `AUTH_CONFLICT`。

注意：`LOGIN_REPLACED` 不是 `NEED_REAUTH`，也不是 `LOGGED_OUT`。后续 `reconnect` 接口如果看到该状态，应继续按终态拒绝协议层内部重连，让 Armada 重新投递上线命令。

## 前端设计

前端仓库为 `wheel-saas-pure-web`。

### 状态展示

`src/api/account.ts`：

- `AccountState` 扩展为 `1 | 2 | 3 | 4 | 5 | 6 | 7`。

`src/views/account/index/account-display.ts`：

- `6` 显示「被抢登」。
- `7` 显示「抢登中」。
- 标签颜色区分异常和进行中：「被抢登」建议 `danger`，「抢登中」建议 `warning`。

`AccountListTable.vue`：

- 列标题从「状态」改为「账号状态」。

### 搜索

`useAccountListPage.ts`：

- `accountStatusOptions` 增加「被抢登」「抢登中」。
- `accountStateMap` 增加：
  - `被抢登: 6`
  - `抢登中: 7`

### 批量操作

`AccountListTable.vue` 批量下拉增加「一键抢登」。

按钮规则：

- 未选择账号：按钮禁用。
- 选择账号但存在非「被抢登」：按钮禁用。
- 用户点禁用态附近或通过下拉触发时，提示「当前所选账号存在非被抢登状态，请重新选择。」

Element Plus `el-dropdown-item` 支持 disabled；页面仍在 `handleBatchAction` 中二次校验，避免绕过 UI。

API：

- 新增 `batchTakeoverTenantAccounts(ids: number[])` 调用 `/api/accounts/batch-takeover`。

成功提示：

- 「抢登请求已提交，已受理 x/y」。

## 数据库迁移

新增 Flyway 迁移，更新 `account_state.account_state` 注释：

```sql
ALTER TABLE account_state
    MODIFY COLUMN account_state TINYINT DEFAULT NULL
    COMMENT '1新增 2正常 3封禁 4导出 5解绑 6被抢登 7抢登中;NULL=未上报';
```

不需要新增字段和索引。现有 `idx_tenant_state (tenant_id, account_state)` 可支持按账号状态筛选。

## 乱序与幂等

继续使用 `last_state_sync_time` 防止旧事件覆盖新状态。

事件幂等规则：

- 重复 `LOGIN_REPLACED` 对「被抢登」账号重复落库为「被抢登」。
- 重复 `LOGIN_REPLACED` 对「抢登中」账号可能重复触发上线。第一版接受该风险，因为同账号 Kafka 分区和 outbox 顺序通常会收敛；实现时可用短窗口冷却减少重复投递。
- 如果 `ONLINE` 事件时间晚于 `LOGIN_REPLACED`，按现有水位规则更新登录态；抢登中账号保持「抢登中」，非抢登中账号收敛为正常在线。
- 如果旧 `ONLINE` 事件时间早于最新 `LOGIN_REPLACED`，跳过。

为避免无限快速重投，抢登续上线需要增加 15 秒内同账号 `login_replaced_takeover` 冷却。该冷却不改变业务语义，只降低重复事件带来的 outbox 风暴。

## 测试

### 协议层

- `translateDisconnect(440)` 返回 `LOGIN_REPLACED`、`needReauth=false`、`rawCode=440`。
- 440 close 发布 `account.state_changed`，`to=LOGIN_REPLACED`。
- 440 不发布 `account.need_reauth`。
- 440 释放 runtime slot 但不删除 creds。
- `offline-diagnosis` 把 rawCode 440 分类为抢登。

### Armada 后端

单测：

- `AccountStateEventServiceImplTest` 覆盖 `LOGIN_REPLACED` 普通账号落为「被抢登」。
- 覆盖抢登中账号收到 `LOGIN_REPLACED` 后保持「抢登中」，并 after-commit 触发续上线。
- 覆盖抢登中账号收到 `ONLINE` 后保持「抢登中」并更新为在线。
- 覆盖抢登中账号收到普通 `OFFLINE` 后保持「抢登中」并触发续上线。
- 覆盖抢登中账号收到 `OFFLINE + source=batch_offline` 后停止抢登并回到「被抢登」。
- 覆盖状态事件 adapter 能透传 `source`。
- 覆盖非抢登中账号不会自动上线。
- 覆盖 `NEED_REAUTH + rawCode=440` 兼容老协议事件，也按抢登处理，避免协议和 Armada 灰度期间继续误解绑。
- `AccountOnlineCommandServiceImplTest` 覆盖 `takeoverBatch` 全部被抢登才通过。
- 覆盖存在非被抢登账号时返回指定错误信息。
- 覆盖手动离线对抢登中账号停止并回到「被抢登」。

DbTest：

- 账号列表按 `accountState=6/7` 能筛选。
- Flyway 注释迁移可执行。

Controller：

- `POST /api/accounts/batch-takeover` 委托 service 并返回 `AccountBatchOnlineVO`。

### 前端

- `account-display.test.ts` 覆盖 6/7 文案和 tag type。
- `api/account.test.ts` 覆盖 `/api/accounts/batch-takeover` 请求。
- `useAccountListPage` 相关测试覆盖状态筛选映射 6/7。
- 表格/页面测试覆盖批量操作有「一键抢登」。
- 选择非「被抢登」账号时提示「当前所选账号存在非被抢登状态，请重新选择。」

## 非目标

- 不新增 scheduler。
- 不让协议层自行分配代理或自行循环上线。
- 不改变普通上线、批量上线、代理失败自动重上线的既有语义。
- 不把非抢登中账号的所有离线都解释为抢登续上线。
- 不处理跨租户同 WhatsApp 账号抢登的所有权仲裁，本次只修正 440 语义和当前租户内一键抢登流程。
