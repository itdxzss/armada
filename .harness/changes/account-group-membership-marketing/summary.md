# 账号群关系状态保留与营销安全跳过

## 变更概述

- `account_group_membership` 以显式状态保留当前账号群关系：在群、未确认、被踢、主动退出、不在群。
- Android 精确群成员事件与完整群快照共同维护关系；不完整快照不会把缺失群误判为退出。
- 普通群组营销创建页返回所有当前关系状态，均可见、可选；执行前重新读取关系表决定是否发送。
- `KICKED_OUT`、`LEFT`、`NOT_IN_GROUP` 只写 `SKIPPED` 明细，不生成协议命令，也不计入失败数。
- 营销任务明细分别返回当前关系、最后协议状态和最后执行结果，并单独统计跳过数。

## 影响模块

- 数据库：`account_group_membership` 新增状态、来源、事实时间及查询索引；发送尝试新增关系快照字段。
- 群关系：完整快照校准缺失关系；精确 add/remove/leave 在 Android 收到通知时固定事实时间，并采用来源优先级防止异步和乱序覆盖。
- 协议事件：消费 `account.group_membership_changed`，严格校验 Kafka 路由账号与数据账号一致，并兼容 Web/Android 旧群快照完整性字段。
- 营销创建：账号懒加载群列表展示五态关系，关系状态不影响选择。
- 营销执行：关系状态查询发生在账号占用和轮次抢占之前；查询失败停止本轮。
- 营销明细：固定目标即使没有 attempt 也保留展示，当前关系、协议状态、执行结果互不覆盖。

## 状态与发送规则

| 关系状态 | 含义 | 创建页 | 运行时 |
| --- | --- | --- | --- |
| `IN_GROUP` | 当前在群 | 可见可选 | 发送 |
| `UNCONFIRMED` | 尚未确认 | 可见可选 | 发送 |
| `KICKED_OUT` | 被踢出群 | 可见可选 | 写 `SKIPPED`，不下发 |
| `LEFT` | 主动退出群 | 可见可选 | 写 `SKIPPED`，不下发 |
| `NOT_IN_GROUP` | 完整快照确认不在群 | 可见可选 | 写 `SKIPPED`，不下发 |

## 数据库变更

- Flyway：`V060__account_group_membership_status.sql`。
- 归档副本：同目录 `db-migrations.sql`。
- 历史活跃关系迁移为 `IN_GROUP`；仅存在软删历史的最新关系恢复为 `NOT_IN_GROUP`。
- 回滚不删除新增列或历史状态，只在回退旧应用前把退出三态软删，见 `rollback.sql`。

## API 变更

- 营销账号树群节点新增 `membershipStatus`、`membershipStatusText`、`membershipStatusUpdatedAt`。
- 营销详情任务、账号、群组层级新增 `skippedMessageCount`。
- 群组明细新增 `membershipStatus`、`executionResult=SKIPPED` 及执行原因。
- `groupStatus` 继续只表示最后协议发送状态，不复用关系状态。

## 关键兼容与安全约束

- 快照字段缺失：Web/Baileys 按旧完整快照处理，Android 按不完整处理；显式 `false` 或跳过数大于 0 始终不完整。
- 完整快照缺失只转成 `NOT_IN_GROUP`，不会覆盖已知 `KICKED_OUT` / `LEFT`。
- 同时间戳来源优先级为精确 remove/leave > 精确 add > 群快照；重复事件保持幂等。
- 精确 add 后的首次有效快照仍按新增群触发现有即时营销；过期快照不会把退出关系误报成新增群。
- 按 JID 再次发现软删除群入口时复活并复用原 `group_link`，不创建重复群入口。
- 当前关系行缺失的历史任务，从最后 `SKIPPED` 稳定原因码恢复退出三态，避免错误回显为 `UNCONFIRMED`。
- 账号列表群数、群操作账号选择等既有“当前在群”语义仅统计 `IN_GROUP` / `UNCONFIRMED`。
- `group_link` 全局群资料语义不变；退出状态只属于账号和群的关系。

## 验证

- 后端聚焦普通单测：117 个通过，0 失败、0 错误、0 跳过。
- `mvn -DskipTests test-compile`：成功，260 个测试源码可编译。
- 三份改动 Mapper XML 通过 `xmllint`；API 文档检查通过（20 个 Controller、108 个 endpoint）。
- 真库 DbTest：未执行；尚未获得本次数据库目标环境确认。
- 数据模型 wiki：未刷新；必须在已确认测试库应用 V060 后由生成器更新，禁止手写。
- 三项目 `git diff --check` 均通过；专家复审确认 7 个重要问题均已修复，无新增 Critical/Important。

## 部署与回滚

- 当前仅在本地 `1.0.1-snapshot` 工作区修改，未提交、未部署。
- 部署顺序：Android 协议事件生产者 → Armada Flyway/后端 → Web 前端。
- 回滚前先回退依赖新状态语义的应用代码，再评估并执行 `rollback.sql`；执行真实 SQL 前必须确认环境、备份与影响行数。

## 遗留 / 跟进

- 在用户确认的测试库执行 Flyway 与计划内 DbTest，并由真实 schema 刷新数据模型文档。
- 在测试 Kafka 抽样验证一条 self remove 和一条完整群快照事件。
- 完成目标环境的创建页、运行时跳过和营销明细端到端联调。
