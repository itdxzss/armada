# 账号列表统计与待上线登录态设计

## 目标

账号列表统计卡按业务最新口径调整：

- 离线统计只统计账号状态为正常、登录状态为离线的账号。
- 原“封禁账号”统计扩展为异常账号统计，包含封禁、解绑、禁言、导出，并展示总计。
- 新增待上线账号统计。用户点击上线后，到协议 Kafka 回传结果前，账号处于待上线。

本次不新增 `account_state` 字段。待上线进入现有 `login_state` 登录状态。

## 状态口径

`account_state.login_state` 扩展为三态：

| 值 | 含义 |
| --- | --- |
| `1` | 在线 |
| `2` | 离线 |
| `3` | 待上线 |
| `NULL` | 未上报/未发起上线 |

`login_state=3` 是 Armada 本地运行态，不是协议层事实状态。协议层最终事实仍通过 `account.state_changed` Kafka 事件回填为在线或离线。

账号业务状态仍由 `account_state.account_state` 和 `mute_status` 表达。业务确认一个账号同一时间只会属于封禁、解绑、禁言、导出中的一种状态，异常总计可直接按分项相加。

## 数据流

### 用户手动上线或批量上线

1. Armada 校验账号、凭据并分配代理。
2. 上线命令成功写入 `protocol_command_outbox` 后，将对应账号 `login_state` 更新为 `3`。
3. 前端刷新账号列表和统计时，展示为待上线。
4. 协议层回传 Kafka：
   - `ONLINE`：更新 `login_state=1`，账号生命周期按现有逻辑收敛为正常。
   - `OFFLINE` / `PROXY_FAILED` / `NEED_REAUTH` / `LOGGED_OUT` / `DEVICE_REMOVED`：更新 `login_state=2`，账号生命周期按现有逻辑收敛为正常离线、封禁或解绑。

### 协议通知 IP 不可用并触发换 IP 重上线

1. Armada 收到 `PROXY_FAILED`，现有 side effect 释放/标记代理并发起自动重上线。
2. 自动重上线命令成功写入 outbox 后，再次将账号 `login_state` 更新为 `3`。
3. 后续协议成功或失败回传时，再收敛为在线或离线。

## 统计接口

`GET /api/accounts/stats` 返回结构增加：

- `pendingOnline`：`login_state=3`
- `banned`：`account_state=3`
- `unbound`：`account_state=5`
- `muted`：`mute_status IS NOT NULL`
- `exported`：`account_state=4`
- `restrictedTotal`：`banned + unbound + muted + exported`

既有字段调整：

- `offline`：`account_state=2 AND login_state=2`
- `online`：`login_state=1`
- `risk`、`assigned`、`unassigned` 保持现有口径。

为了兼容前端平滑升级，保留旧字段 `banned`，但前端卡片标题不再叫“封禁账号”，而是展示异常账号总计和分项。

## 前端展示

账号列表顶部统计卡调整为：

- 总账号数
- 异常账号：展示总计，并在卡片内显示封禁/解绑/禁言/导出分项
- 在线账号
- 离线账号
- 待上线账号
- 风控账号
- 已分配账号
- 未分配账号

账号列表登录列新增“待上线”标签。登录状态筛选下拉新增“待上线”，传参仍使用 `loginState=3`。

前端不再使用 `total - online - offline` 推导待上线，必须使用后端返回的 `pendingOnline`。

## 数据库与文档

不新增表和字段。需要补齐以下常量和文档：

- `AccountLoginStateCode.PENDING_ONLINE = 3`
- `AccountStatsVO` / `AccountStatsVoRow` 字段说明
- `docs/business/account-data-model.md` 中 `login_state` 口径
- 新增 Flyway 迁移只更新 `account_state.login_state` 列注释，不改变数据。实施前按仓库当前最新版本号选择下一号，避免跨分支撞号。

## 乱序与失败处理

当前不新增 `online_attempt_id` 字段，因此不能按 attempt 精确判断某条 Kafka 结果是否属于当前上线请求。系统继续依赖现有 `last_state_sync_time` 防止明显旧事件覆盖新状态。

风险边界：

- 如果协议层发来时间更晚但属于旧 attempt 的事件，Armada 无法只靠 `login_state` 区分。业务已接受不新增字段的简化方案。
- 如果 outbox 写入失败，不应置为待上线。
- 如果代理分配失败，不应置为待上线。
- 如果上线命令写入成功但 Kafka 发布暂时失败，仍属于待上线，因为命令已进入本地可靠 outbox。

## 测试

后端测试：

- `AccountStatsMapperDbTest` 覆盖正常离线才计入离线，封禁/解绑/导出/禁言分项，待上线计数。
- `AccountOnlineCommandServiceImplTest` / DbTest 覆盖单号和批量上线 outbox 成功后写 `login_state=3`，失败时不写。
- `AccountStateEventServiceImplDbTest` 覆盖协议 ONLINE/OFFLINE/PROXY_FAILED/NEED_REAUTH 回传后清出待上线并收敛为在线或离线。

前端测试：

- `account-display.test.ts` 覆盖统计卡使用 `pendingOnline`，不再本地推导。
- API 类型和账号列表展示覆盖登录状态 `3=待上线`。
