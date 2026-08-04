# 普通群链接拉群任务测试排查手册

适用范围：`pull_task.task_type='STANDARD'` 且 `pull_task.mode='NORMAL_LINK'`。

本手册只做只读排查，不自动重试、恢复、释放资源、修改状态或重启服务。旧建群营销、拉群营销、历史群拉人和其他拉群模式不使用本手册。

## 测试时请保留

```text
环境：测试环境名称
测试时间：精确到约 5 分钟范围
任务 ID：pull_task.id
操作：创建 / 启动 / 暂停 / 恢复 / 结束 / 补充资源
现象：页面可见结果
截图：可选
```

后续排查优先使用 `taskId` 找到 `executionId`，再使用 `commandId` 串联 Armada、Outbox 和协议层。不要在聊天或排查记录中粘贴完整号码、完整群链接、私钥、密码或命令载荷。

## 快速判断顺序

```text
父任务
→ 执行行状态、stage、next_run_at、租约
→ 角色账号和业务事实
→ commandId
→ protocol_command_outbox
→ Armada 日志
→ 协议 master/worker 日志
→ 回调与状态收口
```

不要跳过业务事实和 Outbox，直接根据页面“执行中”猜测协议层异常。

## 任务状态

| 状态 | 含义 | 首要检查 |
| --- | --- | --- |
| `DRAFT` | 创建页内部草稿，不进入正式调度 | 草稿计划、链接/TXT 解析和提交冻结 |
| `WAIT_START` | 已提交，等待启动 | 是否调用启动接口、启动校验是否通过 |
| `EXECUTING` | 父任务执行中 | 执行行状态、阶段、排期和租约 |
| `PAUSED` | 父任务已暂停 | 是否为人工暂停，已提交动作是否正在安全收口 |
| `INTERRUPTED` | 父任务已中断 | 中断原因、执行行和资源释放状态 |
| `COMPLETED` | 自然完成 | 是否仍有非终态执行行或未释放拉手 |
| `ENDED` | 人工结束 | 未开始事实是否已取消、已提交事实是否已收口 |

父任务为 `EXECUTING` 只说明任务生命周期正在运行，不能证明每条群执行行都在主动执行。

## 群执行行状态

| 值 | 状态 | 含义 |
| --- | --- | --- |
| `0` | `DRAFT` | 创建页未提交的计划行 |
| `1` | `WAIT_START` | 已冻结，等待调度领取 |
| `2` | `EXECUTING` | 正在推进某个业务阶段 |
| `3` | `WAIT_RESOURCE` | 等待管理、拉手或站台资源 |
| `4` | `COMPLETED` | 本行正常收口 |
| `5` | `FAILED` | 不可恢复失败终态 |
| `6` | `ABANDONED` | 人工放弃终态 |

`WAIT_RESOURCE` 是明确的业务等待，不直接判定为程序卡死。需要结合 `wait_resource_type`、`reason_code` 和对应账号池确认缺少什么资源。

## 七个执行阶段

| 阶段 | 枚举 | 主要事实 | 正常推进条件 | 典型故障层 |
| --- | --- | --- | --- | --- |
| 1 | `LINK_VALIDATION` | 执行行 `group_jid`、`reason_code`、`next_run_at` | 链接有效并解析出群 JID | 链接失效、公开页网络、后端校验 |
| 2 | `MANAGER_JOIN` | 管理角色、`JOIN_BY_LINK` 动作、在群状态 | 管理账号确认在群 | 账号选择、协议进群、入群待审批 |
| 3 | `MANAGER_PULLER_CONTACT` | 双向 `SAVE_CONTACT` 动作 | 联系人动作进入终态 | 命令准备、Outbox、协议联系人操作 |
| 4 | `PULLER_INVITE` | `INVITE_TO_GROUP`、拉手在群状态 | 计划拉手确认在群 | 管理权限、拉手可用性、协议邀请 |
| 5 | `PULL_EXECUTION` | `pull_task_pull_call`、站台和料子结果 | 当前调用逐参与者结果回写并继续取料或进入下一阶段 | 拉手分配、批量拉人、协议回调 |
| 6 | `MATERIAL_ADMIN` | `admin_status`、`admin_command_id` | 需要提权的成功入群料子全部终态 | 管理员设置、Outbox、协议结果 |
| 7 | `CLOSING` | 拉手释放、执行行终态、父任务聚合 | 本行收口并触发父任务完成检查 | 资源释放、状态汇总 |

阶段 3 的保存联系人失败通常不阻断后续邀请或拉人。看到联系人动作 `FAILED` 时，先检查阶段是否仍能推进，不要直接把整个任务判成失败。

## 业务事实状态

### 账号动作

动作类型：

| 值 | 类型 |
| --- | --- |
| `1` | `SAVE_CONTACT`，单向保存联系人 |
| `2` | `INVITE_TO_GROUP`，邀请账号入群 |
| `3` | `JOIN_BY_LINK`，账号踩链接入群 |

动作状态：

| 值 | 状态 | 含义 |
| --- | --- | --- |
| `1` | `PENDING` | 动作行已生成，命令尚未发出 |
| `2` | `SUBMITTED` | 已生成 `commandId`，等待结果 |
| `3` | `SUCCESS` | 协议确认成功 |
| `4` | `FAILED` | 协议明确失败 |
| `5` | `UNKNOWN` | 结果无法确认，等待核对 |
| `6` | `CANCELED` | 任务结束时取消未完成动作 |

### 拉人调用

| 值 | 状态 | 含义 |
| --- | --- | --- |
| `1` | `PLANNED` | 调用和成员绑定已生成，命令尚未发出 |
| `2` | `SUBMITTED` | 批量加成员命令已发出 |
| `3` | `WRITTEN_BACK` | 逐参与者结果已回写 |
| `4` | `UNKNOWN` | 调用结果无法确认 |
| `5` | `CANCELED` | 未发出的调用已取消 |

### 料子入群与提权

料子入群 `pull_status`：`0` 未消费、`1` 已提交、`2` 成功、`3` 失败、`4` 结果未知、`5` 取消。

料子提权 `admin_status`：`0` 不需要、`1` 待执行、`2` 已提交、`3` 成功、`4` 失败、`5` 结果未知、`6` 取消。

料子入群明确失败后不换拉手重试；提权失败不反向修改该号码已经确认的入群成功结果。

## Outbox 状态

| 值 | 状态 | 判断 |
| --- | --- | --- |
| `0` | `PENDING` | 待 publisher 扫描或重试时间尚未到达 |
| `1` | `LOCKED` | 已被 publisher 抢占 |
| `2` | `SENT` | Kafka producer 已确认发送，不代表 worker 已执行成功 |
| `3` | `DEAD` | 发布重试耗尽或不可恢复失败，读取 `last_error` |
| `4` | `CANCELED` | 业务取消，命令不再发送 |
| `5` | `DISPATCHING` | 发送权已提交，等待 Kafka 结果 |
| `6` | `CANCEL_REQUESTED` | 业务已结束，当前发送只允许收口 |

## 卡点判断表

| 已确认事实 | 初步定位 | 下一步证据 |
| --- | --- | --- |
| 没有生成当前阶段应有的业务事实 | 后端调度或阶段状态机 | `next_run_at`、租约、调度日志 |
| 业务事实为待执行且没有 `commandId` | 事务编排或 Outbox 入库前 | 业务事实更新时间、Armada 异常日志 |
| Outbox 为 `PENDING` | 尚未到重试时间或 publisher 未推进 | `next_retry_at`、`retry_count`、publisher 日志 |
| Outbox 为 `LOCKED` 或 `DISPATCHING` | Armada 发布链路处理中 | `locked_at`、`locked_by`、Kafka producer 日志 |
| Outbox 为 `DEAD` | Kafka 发布失败 | `last_error` |
| Outbox 为 `SENT`，业务事实仍为已提交 | 协议 worker 执行或结果回传 | 相同 `commandId` 的协议日志 |
| 协议日志已有结果，业务事实未变化 | Armada 回调消费、关联或落库 | 回调消费日志、`commandId` 关联结果 |
| 业务事实为 `UNKNOWN` | 结果无法确认 | 未知结果核对调度与实时状态查询 |
| `next_run_at` 还没到 | 正常排期 | 等到排期时间后复查 |
| `next_run_at` 已到，租约为空或过期 | 应具备调度资格 | 调度器是否运行、领取 SQL 是否命中 |
| `lock_owner` 非空但 `lock_expires_at` 为空 | 租约数据不一致 | 写入租约的事务和相关日志 |
| 执行行为 `WAIT_RESOURCE` | 资源等待 | `wait_resource_type`、原因码和账号可用性 |
| 子执行行全部终态，父任务仍在运行态 | 收口或父任务聚合 | `PullTaskParentCompletionService` 日志和状态更新 |

异常摘要 SQL 输出的是“排查候选”，不是自动故障结论。尤其是 `SENT_WITHOUT_BUSINESS_RESULT`：命令刚发送时短暂存在属于正常现象，必须结合测试时间和事实更新时间判断。

## SQL 查询使用方法

在已确认的目标测试数据库会话中先设置任务 ID：

```sql
SET @task_id := 123;
```

然后执行 `docs/operations/pull-task-normal-link-diagnosis.sql`。按下面顺序阅读：

1. 父任务概况。无结果时停止，先确认任务 ID、软删状态或业务模式。
2. 群执行行概况。选择页面异常对应的 `executionId`。
3. 角色账号。检查管理、拉手、站台的在群、权限、可用性和占用。
4. 账号动作及 Outbox。检查联系人、邀请和踩链接。
5. 拉人调用及 Outbox。检查每次真实批量拉人。
6. 料子结果。先看聚合，再看处理中或异常明细。
7. 异常摘要。把候选异常与前六组事实交叉确认。

先排除未到排期、人工暂停和资源等待，再进入命令链路。只有拿到 `commandId` 后，才继续查询 Outbox 和协议日志。

## 日志检索原则

连接远程环境前必须先确认目标环境。日志检索保持只读，并遵循以下顺序：

1. 先限定测试时间前后约 5 分钟，避免扫描无关历史日志。
2. Armada 先搜 `taskId` 和 `executionId`，确认阶段是否生成业务事实。
3. 业务事实已经有 `commandId` 时，Armada 和协议层都使用同一个 `commandId` 搜索。
4. Outbox `SENT` 但协议层没有相同 `commandId` 时，再检查 Kafka topic、账号所属 backend 和 worker 路由。
5. 协议层已有明确结果时，回到 Armada 搜结果事件消费、关联和状态更新。

需要保留的日志证据只包含时间、动作名、`taskId`、`executionId`、`commandId`、脱敏原因码和必要的账号内部 ID。不要复制完整命令载荷。

## 故障归类

最终结论归入以下一类：

1. 创建链路：草稿、链接解析、TXT 解析、匹配或提交冻结。
2. 生命周期：启动、暂停、恢复、结束或父任务状态。
3. 后端调度：候选未领取、租约未回收或阶段未推进。
4. 资源分配：管理、拉手、站台不足或账号不可用。
5. 业务编排：动作或拉人调用没有正确生成。
6. Outbox/Kafka：命令待发送、发送中、取消或死信。
7. 协议路由：命令没有到达账号所属 worker。
8. 协议执行：worker 收到命令，但 WhatsApp 操作失败或结果无法确认。
9. 回调处理：协议已有结果，Armada 未关联或落库。
10. 结果核对：未知结果没有按预期收敛。
11. 执行收口：单群终态、资源释放或父任务汇总异常。

## 标准排查回复

```text
结论：确认卡在什么阶段、哪一层。

证据：列出数据库事实、commandId、Outbox 状态和相关日志。

影响：说明是单个群执行行、整个任务还是某个账号。

建议：指出应检查或修改的模块，不直接执行修复。

确定性：已确认 / 高概率 / 信息不足。
```

“已确认”必须有数据库或日志直接证据；只有状态组合推断时使用“高概率”；缺少环境、时间或任务 ID 时使用“信息不足”。

## 代码事实锚点

- `PullTaskStandardStatus`：父任务状态。
- `PullTaskExecutionStatus`：群执行行状态。
- `PullTaskExecutionStage`：七阶段顺序。
- `PullTaskActionStatus`、`PullTaskPullCallStatus`：动作和拉人调用状态。
- `PullTaskMaterialPullStatus`、`PullTaskMaterialAdminStatus`：料子状态。
- `ProtocolCommandOutboxStatus`：命令传输状态。
- `PullTaskGroupExecutionMapper.xml`：调度资格、排期和租约条件。
- `ProtocolCommandOutboxMapper.xml`：Outbox 抢占、发布、重试和死信流转。

代码调整后应先更新状态映射和 SQL，再使用本手册判断新任务。

## 验证边界

在没有连接用户确认的测试数据库前，只能完成源码、Mapper、Flyway 和 SQL 的静态核对，不能声称查询已经在真实 MySQL 数据上执行通过。首次实际排查时，应在确认的测试环境执行只读 SQL，并根据真实输出修正文档或查询错误。
