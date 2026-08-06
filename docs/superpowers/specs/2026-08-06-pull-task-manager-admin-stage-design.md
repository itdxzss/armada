# 普通群链接拉群管理员设置阶段设计

日期：2026-08-06  
状态：已确认

## 1. 背景与问题

普通群链接拉群当前阶段为：链接校验、管理员进群、管理—拉手联系人、管理员邀请拉手、拉人执行、料子提权、执行收口。

现有 `MANAGER_JOIN` 只让管理分组账号踩链接进群。进群成功后，执行行直接进入联系人和邀请拉手阶段；系统没有确认该账号已经获得目标群管理员权限，也没有让群内已有的我方群主或管理员执行 `PROMOTE`。`PULLER_INVITE` 又只校验管理账号已进群且协议身份可用，不校验 `admin_status=SUCCESS`，因此普通成员也会被用于邀请拉手。

第一套环境任务 2 提供了可复现证据：

- 账号 15 已进群，但管理员权限仍为 `PENDING`；
- 两个目标群的 `account_group_membership` 均能找到我方管理员账号；
- 账号 906 在两个群中均为 `is_admin=1`，并且账号正常、在线、协议身份完整；
- 账号 15 邀请拉手时收到 `not-authorized`；
- 拉手 45、47 邀请失败后，资源恢复逻辑重新占用了相同的 `JOIN_FAILED` 角色行，导致其余可用拉手无法补位。

因此本次同时解决两个直接关联的问题：补齐管理员设置业务阶段，以及修复失败拉手不换号导致的假性“拉手不足”。

## 2. 目标与非目标

### 2.1 目标

1. 管理分组账号进群后，必须确认已经成为目标群管理员，才能进入联系人和邀请拉手阶段。
2. 自动从现有群关系事实中选择我方在线群主或管理员，不新增“建群人分组”配置。
3. 提权失败不终止群执行行；自动轮换其他候选，或进入可恢复等待。
4. 拉群任务详情明确展示“管理员设置”阶段、“管理员设置失败”任务情况和脱敏异常原因。
5. 现有非终态执行行若管理员尚未确认权限，部署后补走管理员设置阶段。
6. 拉手邀请明确失败后选择本执行行尚未尝试的可用拉手，不重新占用同一条 `JOIN_FAILED` 角色行。

### 2.2 非目标

- 不新增建群人账号分组或要求用户手工指定提权账号。
- 不把外部号码、群预览中的任意群主号码直接当作 Armada 账号。
- 不修改已完成、已失败或已放弃执行行的历史结果。
- 不改变料子提权阶段及其 `admin_status` 语义。
- 不在本次引入通用工作流引擎或统一所有群成员操作。

## 3. 业务流程

阶段调整为：

| 阶段值 | 枚举 | 展示名称 |
| --- | --- | --- |
| 1 | `LINK_VALIDATION` | 链接校验 |
| 2 | `MANAGER_JOIN` | 管理员进群 |
| 3 | `MANAGER_ADMIN` | 管理员设置 |
| 4 | `MANAGER_PULLER_CONTACT` | 管理—拉手联系人 |
| 5 | `PULLER_INVITE` | 管理员邀请拉手 |
| 6 | `PULL_EXECUTION` | 拉人执行 |
| 7 | `MATERIAL_ADMIN` | 料子提权 |
| 8 | `CLOSING` | 执行收口 |

管理员设置阶段的数据流：

```text
任务管理员确认在群
  -> 按 tenant_id + group_jid 查询我方在群管理员候选
  -> 过滤在线、正常、协议身份完整的账号
  -> 实时成员查询确认候选仍为群主/管理员
  -> 候选账号对任务管理员执行 PROMOTE
  -> 实时成员查询确认任务管理员已成为管理员
  -> admin_status=SUCCESS
  -> 进入管理—拉手联系人阶段
```

不得以协议命令提交成功代替最终权限事实。阶段推进条件是实时成员列表确认目标账号为群主或管理员。

## 4. 候选账号选择

管理员设置候选以 `account_group_membership` 为主事实源，查询条件为：

- 当前租户；
- `group_jid` 与执行行一致；
- 关系未删除、`membership_status=IN_GROUP`、`is_admin=1`；
- Armada 账号未删除；
- `account_state=NORMAL`、`login_state=ONLINE`；
- `protocol_id`、`protocol_account_id` 和号码身份完整；
- 账号不是待提权的任务管理员本人；
- 风险和禁言状态符合群成员写操作的现有账号可用口径。

排序规则为：群预览能够可靠匹配的平台群主优先，其次按 `last_seen_at` 倒序，最后按账号 ID 升序保证稳定。群预览没有群主号码时不影响选择；第一套环境任务 2 会选中账号 906。

表内关系只用于产生候选。提交 `PROMOTE` 前必须通过 `GroupMemberListPort` 实时确认候选仍为群主或管理员。实时确认不通过时，本次候选失败并轮换下一个，不直接篡改其他业务域的群关系事实。

## 5. 持久化事实与幂等

### 5.1 角色账号

`pull_task_group_account.role_type` 增加：

- `4=PROMOTER`：本执行行中负责把任务管理员设为管理员的我方既有群主或管理员。

PROMOTER 角色行仅用于执行审计和动作关联：

- `membership_status=IN_GROUP`；
- `admin_status=SUCCESS`；
- 不参与 `required_manager_count`、任务管理员资源统计和拉手邀请轮询；
- 不建立跨任务占用；
- 可以保留多个候选尝试记录。

### 5.2 账号动作

`pull_task_account_action.action_type` 增加：

- `4=PROMOTE_MANAGER`：actor 为 PROMOTER 角色行，target 为任务管理员角色行。

沿用 `(tenant_id, group_execution_id, action_type, actor_group_account_id, target_group_account_id)` 唯一键保证同一候选与目标只有一条逻辑动作。动作保存 `command_id`、提交时间、结果、原因和是否可重试；重试使用新的 commandId，迟到的旧 commandId 回调不能覆盖当前尝试。

迁移为 `pull_task_account_action` 增加：

- `attempt_no INT NOT NULL DEFAULT 0`；
- `retryable TINYINT(1) DEFAULT NULL`。

已有动作使用默认值，不改变现有联系人、邀请和踩链接语义。

## 6. 协议契约

复用现有 `group.participants.requested` 命令和 `group.action_result_reported` 结果事件，不新建 Kafka topic。

新增 source：

```text
source    = pull_task_manager_admin
operation = PARTICIPANT_PROMOTE
action    = PROMOTE
```

payload 使用 PROMOTER 的协议身份作为执行账号，参与者列表只包含任务管理员 JID，关联字段继续使用 `pullTaskId + groupExecutionId + actionId + commandId`。

Web 协议层在 `participantsSourceSpec` 中增加该来源，继续复用 `groupParticipantsUpdate(groupJid, participants, 'promote')`、操作闸门、结果缓存和逐目标事件发布。协议错误必须保留语义：权限不足映射为 `GROUP_PERMISSION_DENIED`，限流映射为 `RATE_LIMITED`，不能全部降级为 `TEMPORARY_FAILURE`。

Android 后端沿用现有 `GroupParticipantPort` 的 `PROMOTE` 能力和同一回调契约。

## 7. 状态流转与失败恢复

### 7.1 正常推进

1. `MANAGER_JOIN` 成功后，任务管理员 `membership_status=IN_GROUP`、`admin_status=PENDING`，执行行进入 `MANAGER_ADMIN`。
2. 提权动作提交后，任务管理员 `admin_status=SUBMITTED`。
3. 协议回执成功或结果未知时，下一轮先实时查询目标权限。
4. 实时确认管理员权限后写 `admin_status=SUCCESS`，清空执行行异常并进入阶段 4。

### 7.2 可恢复异常

新增执行原因码：

| 原因码 | 展示说明 | 行为 |
| --- | --- | --- |
| `MANAGER_ADMIN_ACTOR_UNAVAILABLE` | 当前没有在线的我方群主/管理员 | `WAIT_RESOURCE + MANAGER`，等待账号或群关系恢复 |
| `MANAGER_ADMIN_SETUP_FAILED` | 管理员设置失败 | 换下一个候选；无候选时等待 |
| `MANAGER_ADMIN_UNCONFIRMED` | 管理员权限结果暂未确认 | 退避后实时复核 |

稳定失败，例如实时确认候选已不是管理员或协议明确返回权限不足，将当前 PROMOTER 动作置为 `FAILED/retryable=false` 并轮换下一个候选。

临时失败，例如限流、网络、账号忙、worker 忙或协议结果未知，将动作置为可重试状态，执行行写入 `next_run_at`。恢复时先实时确认任务管理员权限；仍未生效时优先使用尚未尝试的候选，所有候选均已尝试后按退避策略重试可重试动作。

管理员设置失败不写执行终态，不触发父任务终态聚合。

### 7.3 详情展示

普通拉群详情使用后端执行行 `reason_code/reason_message`：

- 当前阶段显示“管理员设置”；
- `reason_code` 为上述管理员设置异常时，任务情况显示“管理员设置失败”；
- 当前异常显示脱敏后的 `reason_message`；
- PROMOTER 和 `PROMOTE_MANAGER` 动作在群执行明细中可审计；
- 不回显完整号码、群 JID、原始协议 payload 或未脱敏异常。

## 8. 失败拉手换号

管理员设置成功后，现有任务仍需正确处理已经失败的拉手：

- `restoreReleasedPullers` 不得重新占用 `membership_status=JOIN_FAILED` 的拉手角色行；
- 已有失败邀请动作只阻止重复邀请同一角色行，不阻止从拉手分组选择新的账号；
- 新候选必须排除本执行行已有账号 ID，按现有稳定顺序继续选择；
- 只有当前执行行没有未尝试候选时，才显示真正的“拉手不足”；
- 失败账号只在本执行行内被排除，不修改其全局正常/在线状态，也不阻止其他任务独立判断。

第一套环境任务 2 在管理员设置成功后，应跳过已失败的 45、47，并从其余可用拉手中补位。

## 9. 数据迁移与现有任务

使用 `V101__pull_task_manager_admin_stage.sql`：

1. 把现有阶段 3～7 顺延为 4～8；
2. 更新阶段、角色类型和动作类型的列注释；
3. 为账号动作增加 `attempt_no` 与 `retryable`；
4. 对非终态、任务管理员已进群但 `admin_status<>SUCCESS` 的普通链接执行行，设置为新阶段 3；
5. 清除这些回退行原来的资源等待类型和旧异常，设置 `next_run_at=0` 以便重新调度；
6. 不修改已完成、已失败、已放弃或父任务已终态的执行行；
7. 迁移使用表存在、列存在和列缺失守卫，兼容第一套环境的 Flyway 历史。

部署必须停止旧版本调度器后执行迁移，再启动新版本，避免旧代码把新阶段值按旧枚举解释。生产或共享环境执行前仍需单独确认目标环境。

## 10. 组件边界

后端新增或扩展：

- 管理员设置阶段 processor：事务外执行实时权限查询；
- 管理员设置短事务服务：候选角色、动作、Outbox 和执行行 CAS；
- 群关系候选查询：只负责按租户与群 JID 返回可执行账号；
- 管理员设置回调服务：按 actionId、commandId 和当前状态幂等回写；
- 资源恢复：允许阶段 3 的管理员设置等待行重新领取；
- 详情读模型：暴露新阶段、PROMOTER 角色和提权动作。

前端扩展：

- 阶段 3 显示“管理员设置”，原阶段 3～7 顺延；
- 管理员设置原因码映射为 `ADMIN_SETUP_FAILED`；
- 群执行明细显示 PROMOTER 和 `PROMOTE_MANAGER`；
- 当前异常继续以服务端 `reasonMessage` 为事实，不在前端猜测协议错误。

协议层扩展：

- 新增 `pull_task_manager_admin` source 解析；
- 复用 PROMOTE 原生动作、幂等状态存储和结果发布；
- 增加权限不足、限流和未知结果的映射测试。

## 11. 测试与验收

### 11.1 后端测试

- 管理员进群成功后进入 `MANAGER_ADMIN`，不再直接进入联系人阶段；
- 候选查询只返回本租户、同群、在群、管理员、正常在线且协议身份完整的账号；
- 群主优先、最近确认优先和 ID 稳定排序；
- 实时复核候选权限失败后轮换；
- PROMOTE 成功且实时确认后才推进；
- 权限不足、限流、未知结果分别进入正确的失败或重试路径；
- 所有候选不可用时保持 `WAIT_RESOURCE + MANAGER` 并返回明确异常；
- 重复回调和迟到旧 commandId 不覆盖当前尝试；
- 迁移正确顺延阶段并只回退符合条件的非终态执行行；
- `JOIN_FAILED` 拉手不会被重新占用，未尝试拉手可以补位。

### 11.2 协议测试

- 新 source 只能使用 `PARTICIPANT_PROMOTE/PROMOTE`；
- 执行账号、目标账号和业务关联字段校验；
- 同一 commandId 只产生一次 WhatsApp 副作用；
- 权限不足映射为 `GROUP_PERMISSION_DENIED`；
- 限流映射为 `RATE_LIMITED` 且可重试；
- 结果发布失败只重放缓存结果，不重复提权。

### 11.3 前端测试

- 阶段标签与新 1～8 枚举一致；
- 管理员设置异常显示“管理员设置失败”；
- 当前异常展示后端原因消息；
- PROMOTER 和提权动作使用明确中文标签；
- 旧资源不足、暂停、完成和结束状态映射不回归。

### 11.4 第一套环境验收

1. 确认目标为第一套测试环境后部署后端、前端和协议层。
2. 观察现有任务 2 的执行行回到“管理员设置”。
3. 确认系统从群关系选择账号 906，并实时确认其管理员权限。
4. 确认账号 15 被提升并在实时成员列表中显示管理员。
5. 确认执行行进入联系人和邀请拉手阶段。
6. 确认失败的 45、47 不再被重新占用，新的可用拉手得到补位。
7. 制造一次权限不足或临时失败，确认任务不终止，详情显示明确异常并可恢复重试。

## 12. 回滚

应用回滚不能直接恢复旧版本，因为旧代码不能识别新阶段值。安全回滚顺序为：停止调度器，部署兼容新旧阶段值的过渡版本，确认没有处于阶段 3 的活动执行行，再执行经审批的数据回退。数据库列保持向后兼容，不在紧急回滚中删除。

