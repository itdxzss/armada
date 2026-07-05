# 营销任务混合目标范围 — 规格

- 日期:2026-07-05
- 项目:armada
- 模块:`armada-api` / `armada-protocol` / `wheel-saas-pure-web`
- 范围:营销任务新增、目标保存、轮次发送前目标解析、前端账号/群组选择

## 1. 业务口径

同一个营销任务里,每个账号可以有不同的发送范围。

- 用户只选账号,没有选该账号下任何群组:按账号维度发送。每次发送前查询该账号当前进入云控后的所有群,排除账号导入云控前已经在的群,只给导入后进入的群发消息。
- 用户选择了账号下的具体群组:按群组维度发送。只给用户选择的群组发消息,不再查询该账号当前所有群。

这不是任务级模式,不能把 `target_mode` 放到 `marketing_task` 主表。范围必须落在账号目标行上。

## 2. 数据模型

不新建表。复用 `marketing_task_target`,新增账号维度范围字段:

```text
target_scope TINYINT NOT NULL DEFAULT 1
```

取值:

- `1 = GROUP_FIXED`: 固定群组目标。
- `2 = ACCOUNT_DYNAMIC`: 账号动态目标。

字段语义:

- `GROUP_FIXED` 行必须有 `group_link_id`、`group_jid`、`group_link_url`。
- `ACCOUNT_DYNAMIC` 行只要求 `account_id`、`account_phone`,群组字段允许为空;协议账号 ID 继续按现有方式从 `account.protocol_account_id` 读取或由账号手机号兜底派生。

需要前滚迁移:

- 给 `marketing_task_target` 增加 `target_scope`;
- 将 `group_link_id`、`group_jid`、`group_link_url` 改为可空;
- 调整唯一约束,保证同一任务同一账号只有一条 `ACCOUNT_DYNAMIC` 行,同一任务同一账号同一群只有一条 `GROUP_FIXED` 行;
- 查询和详情 VO 增加 `targetScope`,前端可以展示“账号维度”或“指定群组”。

`GROUP_FIXED` 仍然是每个账号×每个群一条 target。示例:

```text
A + 群1  GROUP_FIXED
A + 群2  GROUP_FIXED
B + 群2  GROUP_FIXED
C        ACCOUNT_DYNAMIC
```

## 3. 创建任务

前端提交的 `selections` 需要显式携带账号目标范围,不能只靠 `groupLinkIds` 是否为空推断。
原因是当前 Element Plus 树默认父子联动:用户点击账号父节点时,会默认把账号下所有群组也勾选上;
如果后端只看 `groupLinkIds`,会把“账号维度发送”误判为“固定群组发送”。

```json
[
  { "accountId": 101, "targetScope": "GROUP_FIXED", "groupLinkIds": [11, 12] },
  { "accountId": 102, "targetScope": "ACCOUNT_DYNAMIC", "groupLinkIds": [] }
]
```

后端规则:

- `targetScope=GROUP_FIXED`:要求 `groupLinkIds` 非空,对每个群调用现有候选校验,生成 `GROUP_FIXED` target 行。
- `targetScope=ACCOUNT_DYNAMIC`:忽略 `groupLinkIds`,校验账号属于本次账号分组、在线可用,生成一条 `ACCOUNT_DYNAMIC` target 行。
- 同一个账号不能同时提交 `GROUP_FIXED` 和 `ACCOUNT_DYNAMIC`;如果前端出现这种状态,后端第一版直接拒绝,避免用户选择语义不清。
- `selected_account_count` 统计去重账号数。
- `target_group_count` 和 `target_pair_count` 对动态账号在创建时无法确定,列表可先统计固定群组数量;动态账号实际发送数量由每轮 attempt 体现。

保存时不要求至少选择一个群组,但必须至少选择一个账号。

## 4. 每轮发送目标解析

Armada 仍然负责每轮生成发送命令,协议层仍接收逐群 `message.send.requested` 命令。

原因:

- 导入前群基线在 Armada 的 `account_group_baseline.baseline_group_jids`;
- 当前 `marketing_task_send_attempt` 和回写计数在 Armada;
- 按账号动态展开后,每个实际发送群仍需要可追踪的 attempt、commandId 和结果回写。

轮次 worker 流程:

1. 读取任务所有 target。
2. `GROUP_FIXED`:直接使用 target 上的 `group_jid` 生成发送 attempt 和协议命令。
3. `ACCOUNT_DYNAMIC`:发送前按 `account_id` 查询当前可营销群。
4. 当前可营销群必须满足:
   - 账号属于任务账号分组;
   - 账号在线、无风险、未禁言;
   - `account_group_membership` 当前存在且未删除;
   - `group_link` 未删除,关系态可营销;
   - `group_link_preview.group_jid` 非空;
   - 群健康未封禁、未异常;
   - 若账号 `group_baseline_state=2`,排除 `account_group_baseline.baseline_group_jids` 中已有的群。
5. 对解析出的每个群生成一条发送 attempt 和一条 `message.send.requested`。
6. 如果动态账号当前没有可发送群,本轮跳过该账号,记录日志;不把任务整体失败。

第一版用 Armada 当前同步到库的 `account_group_membership` 作为“当前账号群”事实源。协议层已有 `account.groups_sync.requested` 能力,后续可在发送前触发同步,但本次不把同步做成强依赖,避免每轮发送阻塞在协议群列表同步上。

## 5. Attempt 与结果

固定群组 target 已经一群一行,attempt 继续引用固定 target。

账号动态 target 是账号级行,一轮可能解析出多个群。为了保持每个实际群可追踪,`marketing_task_send_attempt` 需要补充本轮解析出的群快照:

- `group_link_id`
- `group_jid`
- `group_name`

唯一约束需要从仅按 `target_id + round_no` 调整为能容纳动态账号一轮多群:

```text
tenant_id, target_id, round_no, group_jid
```

协议结果回写仍按 `attemptId` 幂等更新。结果事件里继续带 `targetId`、`attemptId`、`roundNo`、`groupJid`。

## 6. 前端

新增营销任务抽屉保留账号树和群组展示,但保存规则改为账号优先:

- 勾选账号父节点即可提交,不要求勾选群组;
- 前端必须关闭或绕开树的父子级联,让“勾选账号”和“勾选账号下所有群”成为两种不同意图;
- 勾选账号父节点时提交 `targetScope: "ACCOUNT_DYNAMIC"` 和 `groupLinkIds: []`;
- 只勾选账号下群组时提交 `targetScope: "GROUP_FIXED"` 和具体 `groupLinkIds`;
- 同一账号不允许同时处于账号维度和固定群组维度;页面应阻止这种选择,后端也会兜底拒绝;
- 文案从“请至少选择一个账号和群组”改为“请至少选择一个发送账号”;
- 树上继续展示当前可选群,便于用户选择固定群组;
- “全选账号”只选择账号维度目标,不默认勾选所有群。

## 7. 错误处理

- 创建任务没有任何账号:返回“请至少选择一个发送账号”。
- 动态账号不可用:返回“账号不可用或不属于当前分组”。
- 固定群组不可用:沿用现有“账号或群组不可用”。
- 动态账号某轮没有新增可营销群:跳过该账号本轮发送,不停止任务。
- 固定群组发送失败:按现有协议结果回写失败原因。

## 8. 测试

后端 DbTest:

- `targetScope=ACCOUNT_DYNAMIC` 且 `groupLinkIds=[]` 时创建 `ACCOUNT_DYNAMIC` target。
- 传具体群组时创建多条 `GROUP_FIXED` target。
- 父子级联造成的“账号维度 + 固定群组”混合 selection 会被拒绝。
- 同一任务内混合动态账号和固定群组。
- 动态账号每轮解析当前群,排除 baseline 群,只生成导入后新增群 attempt。
- 动态账号一轮多个群时可以插入多条 attempt。

后端 worker 单元测试:

- `GROUP_FIXED` 不查询当前账号所有群。
- `ACCOUNT_DYNAMIC` 查询当前可营销群并生成逐群协议命令。
- 动态账号无可发送群时跳过且不影响其它 target。

前端测试:

- 只勾选账号可以提交。
- 勾选账号下具体群组时提交群组 ID。
- 保存校验文案不再要求群组。
