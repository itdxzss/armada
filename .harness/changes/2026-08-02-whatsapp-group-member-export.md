# WhatsApp 群全成员营销导出

## 背景与问题

普通营销任务导出的事实来源错误地绑定在 Armada 受控账号上：

- `COUNTRY_ENTRY` 从 `join_task_result -> account` 取号码，只能导出控上成功进群账号；
- `FULL` 的成员工作表从 `account_group_membership -> account` 取成员，只能导出控上账号；
- Android 群列表 IQ 已返回完整 participant、群人数和 `announce`，但 `account.groups_reported`
  事件只保留群 JID、群名和当前受控账号角色，导致群人数为空、发言权限为“未确认”；
- 现有 `account.group_membership_changed` 只描述当前受控账号自身进退群，不承载普通成员身份。

## 本次口径

1. WhatsApp 群成员是导出成员事实源，Armada 账号只用于读取群数据和归属营销任务。
2. 全量成员工作表导出目标群内所有当前成员，以及已经通过协议事件/历史同步明确观察到的退出成员；
   不要求成员存在于 `account` 表。
3. `是否在群`、角色、进群时间、退出时间和退出方式使用 WhatsApp 快照及事件事实：
   - `add` 写入进群时间；
   - `remove` 写入“被移出群”和退出时间；
   - `leave` 写入“主动退群”和退出时间；
   - 只有当前快照、没有历史事件的成员不得伪造准确进群时间。
4. 群人数使用当前 WhatsApp participant 快照；累计成功进群号码数量使用该群截至导出快照已观察到的
   不同成员总数（当前成员与已退出成员去重）。
5. 发言权限使用 Android IQ 的 `announce` 与发送账号管理员身份联合判断，不再因 Android 事件裁字段而
   长期输出“未确认”。
6. 按国家导出从 WhatsApp 群成员号码识别国家：选择美国时导出该群全部美国成员，选择多个/全部国家时
   导出属于所选国家集合的全部群成员；按 `任务 + 群 + 成员` 去重，不依赖 `account` 或
   `join_task_result`。

## 设计

### Android 协议层

- 兼容扩展 `account.groups_reported`：每个群新增 `memberCount`、`announceOnly` 和完整
  `participants`（JID、号码、角色、管理员/群主），并用 `participantsComplete` 明确声明成员列表完整。
  现有字段和事件名不变。
- 新增 `group.participants_changed` 事件，承载 `add/remove/leave` 的成员身份、事实时间和源事件 ID。
- w:gp2 实时通知保留 participant 身份并发布新事件；原有当前账号自身
  `account.group_membership_changed` 继续发布，语义不变。
- 历史同步从 `PastParticipants` 及群成员 stub 中提取可用的进退群事实并发布同一种新事件。
- 群成员 HTTP 响应追加 `jid` 和 `Announce`，不修改既有字段。

### Armada 后端

- Flyway V089 新增当前状态表 `whatsapp_group_member`、追加式事实表
  `whatsapp_group_member_fact` 和完整快照水位表 `whatsapp_group_member_snapshot_fact`；事实表使异步导出能按
  `snapshotAt` 回放，任务排队期间的新事件不会污染旧快照。
- participant 列表只要非空即可保存已观察事实；只有 `participantsComplete=true`，且协议声明人数等于
  规范化去重后的成员数时，才把列表外成员标记为“已不在群、退出方式未知”并推进完整快照水位。
- 同群写入用 `group_link` 行锁串行化，成员按 `member_jid` 排序；精确事件按事实时间、来源优先级和事件 ID
  选择唯一胜出事实，防止并发死锁、同毫秒冲突或乱序事件回滚新状态。
- 每个完整快照都为明确出现的 participant 追加正向事实，保证同毫秒乱序快照仍可由来源优先级和事件 ID
  确定性回放；重复事件 ID 依靠唯一键幂等，不根据易变的当前表省略历史事实。
- 迟到的完整快照按 `snapshotAt` 从事实表回放当时状态后识别缺失成员；同毫秒事实按来源优先级和事件 ID
  稳定决胜，并以条件更新避免回滚更新的当前态。
- Kafka consumer 兼容解析旧 `account.groups_reported`（无 participants 时不清空成员），并消费新增事件。
- 两种导出 SQL 改为回放 `whatsapp_group_member_fact`；群人数只有在导出时间之前存在完整快照水位时才使用
  成员事实计数；发言权限也只使用该水位记录的群设置及截止时间成员角色。没有完整水位时保持未知，
  不以最新群预览或 Armada 控上账号关系冒充历史事实。国家导出以 `任务 + 群 + member_jid` 去重。
- LID 发送账号没有可匹配手机号时，发言权限使用该发送账号自己的完整快照观察者角色，并结合账号群关系的
  进退时间判断其在导出截止时间是否仍在群；旧管理员快照不得给已退出或重新入群后未复核的账号重新赋权。
- 普通成员事件优先使用协议 `sourceEventId` 做事实幂等键，旧载荷缺失时才回退 envelope `eventId`。

## 验收标准

- 群内 100 人且只有 5 人是 Armada 账号时，全量成员表仍输出 100 个当前成员；已记录退出成员另行保留。
- 10 个美国、10 个巴西号码：选择美国输出 10 行，选择美国和巴西输出 20 行；全选输出所有能识别国家的成员。
- Android 完整快照后，群人数等于 participant 数量；`announce=false` 输出“所有成员可发言”，
  `announce=true` 根据发送账号管理员身份输出对应权限，不输出“未确认”。
- `add/remove/leave` 事件分别正确写入进群、被移出、主动退群时间，重复或乱序事件不破坏较新事实。
- 旧版 Android 不带 participant 的群快照仍可消费，且不会把现有群成员误清空。
- participant 数量与 `memberCount` 不一致或完整性标记缺失时，只补充已观察成员，不清理任何旧成员。
- 导出作业创建后发生的进退群事件不改变该作业 `snapshotAt` 对应的成员状态和人数。
- 租户间相同群 JID/成员 JID 严格隔离。

## 发布与回滚

- 先发布 Flyway 与兼容消费者，再发布 Android 事件生产者；旧生产者与新消费者可共存。
- 应用回滚时保留 V089 表，不删除已采集事实；Android 可独立回滚到旧事件载荷。
- 历史准确时间受 WhatsApp 实际下发的历史范围约束；没有事件事实的时间字段保持空，不用抓取时间冒充。
