# 建群营销换号重试设计

## 背景

建群营销任务中，一个 `group_creation_marketing_task` 表示整批任务，一个 `group_creation_marketing_item` 表示批次内的单个建群执行项。每个执行项会选择一个账号，创建一个新群，然后向该新群发送营销消息。

现有逻辑只在执行项开始处理时检查分配账号是否正常在线。如果账号离线或不可用，会换成账号组内第一个正常在线账号。如果账号在建群接口或营销发送阶段失败，执行项会直接失败，不会继续换号。

测试环境中 `acc_6285378444041` 的失败属于后者：账号在后端状态仍为正常在线，但协议层建群时 WhatsApp/Baileys 返回 `rate-overlimit`，所以没有触发当前的离线换号逻辑。

## 目标

建群营销执行项在建群失败或建群后营销发送失败时，只要账号组内仍有正常在线账号，就继续换号重试。

业务口径：

- 不设置固定重试次数上限。
- 重试范围限定在单个 `group_creation_marketing_item` 内，不影响整批任务的其他执行项。
- 单个执行项内已经失败过的账号不再重复选择，避免同一账号反复失败形成循环。
- 当账号组内没有可用账号时，执行项最终失败，提示 `没有可用账号`。

## 非目标

- 不做整批任务级别的账号拉黑。
- 不修改普通营销任务的自动重试逻辑。
- 不尝试用新账号复用旧群发送消息。发送失败后会换号重新建一个新群再发。
- 不把联系人预保存失败作为换号触发条件。联系人预保存仍是 best-effort。
- 不在本次设计中改变协议层账号状态收敛规则，例如把单次 `rate-overlimit` 直接标记账号封禁。

## 数据模型

在 `group_creation_marketing_item` 上增加重试历史字段，建议命名为 `retry_history_json`，JSON 结构记录执行项已经尝试过的账号和每轮失败原因。

示例：

```json
{
  "attempts": [
    {
      "accountId": 811,
      "accountPhone": "6285378444041",
      "protocolAccountId": "acc_6285378444041",
      "phase": "GROUP_CREATE",
      "reasonCode": "GROUP_CREATE_FAILED",
      "reasonMessage": "协议层错误 500 INTERNAL_ERROR: rate-overlimit",
      "groupJid": null,
      "failedAt": 1783395965156
    }
  ]
}
```

`participant_result_json` 继续保存协议执行摘要，不承载重试历史，避免把成员结果和执行调度历史混在一起。

## 账号选择

新增 mapper 查询：按账号组查找下一个可用账号，并排除当前执行项 `retry_history_json` 中已经尝试过的账号。

可用条件保持现有口径：

- `account.deleted_at IS NULL`
- `account.account_group_id = task.account_group_id`
- `account.protocol_account_id` 非空
- `account_state.login_state = ONLINE`
- `account_state.account_state = NORMAL`
- `risk_status IS NULL OR risk_status = 1`
- `mute_status IS NULL`

排序沿用账号 ID 升序，保持行为稳定。

如果没有可用账号，执行项进入最终失败：

- `status = FAILED`
- `reason_code = NO_AVAILABLE_ACCOUNT`
- `reason_message = 没有可用账号`
- 任务失败计数递增，任务完成状态按现有统计逻辑收敛。

## 状态流转

### 建群前账号不可用

现有 claim 后检查保留，但改为使用“排除已尝试账号”的选择逻辑。

如果原分配账号离线或不可用：

1. 记录该账号失败尝试，phase 为 `ACCOUNT_CHECK`，reason 为 `ACCOUNT_OFFLINE` 或 `ACCOUNT_UNUSABLE`。
2. 查找下一个可用账号。
3. 找到则更新执行项账号，继续建群。
4. 找不到则最终失败，提示 `没有可用账号`。

### 建群失败

当 `groupCreatePort.create` 抛异常，或协议未返回 `groupJid`：

1. 记录当前账号失败尝试，phase 为 `GROUP_CREATE`。
2. 查找下一个可用账号。
3. 找到则清空本轮建群和发送字段，更新账号，状态改回 `PENDING`，`next_run_at` 设置为当前时间。
4. 找不到则最终失败，提示 `没有可用账号`。

### 营销发送失败

当 `source=group_creation_marketing` 的消息发送结果回写 `success=false`：

1. 记录当前账号失败尝试，phase 为 `MESSAGE_SEND`，保留已建群的 `group_jid`。
2. 查找下一个可用账号。
3. 找到则清空 `group_jid`、`command_id` 等本轮字段，更新账号，状态改回 `PENDING`，等待 scheduler 重新建群并发送。
4. 找不到则最终失败，提示 `没有可用账号`。

发送失败不会复用原群，因为新账号不一定在原群内，也不一定具备发送权限。

## 并发与幂等

- 执行项只有在预期状态下才能被重置回 `PENDING`。
- 发送结果回写必须同时匹配 `item_id`、`command_id` 和当前状态，避免旧事件覆盖新一轮尝试。
- 如果旧发送结果在执行项已经切到下一轮后到达，应按 duplicate/stale 跳过。
- 重试历史写入和状态重置在同一事务内完成。

## 错误提示

最终无可用账号时统一返回：

- `reason_code = NO_AVAILABLE_ACCOUNT`
- `reason_message = 没有可用账号`

最后一轮的真实失败原因保留在 `retry_history_json` 中。

## 测试范围

单元测试：

- 建群失败后选择下一个正常在线账号，并把执行项重置为 `PENDING`。
- 建群失败且没有可用账号时，执行项最终失败为 `NO_AVAILABLE_ACCOUNT`。
- 营销发送失败后选择下一个正常在线账号，并重新进入 `PENDING`。
- 营销发送失败且没有可用账号时，执行项最终失败为 `NO_AVAILABLE_ACCOUNT`。
- 同一执行项不会重复选择已失败账号。
- 过期的发送失败事件不会覆盖已经进入下一轮的执行项。

Mapper/DB 测试：

- `retry_history_json` 字段迁移存在。
- 下一个可用账号查询会排除 retry history 中已尝试账号。
- 状态重置、最终失败和任务计数更新符合现有任务状态规则。

回归测试：

- 原有在线账号成功建群并发送的流程不变。
- 联系人预保存失败仍不阻断建群。
- 普通营销任务发送结果回写不受影响。
