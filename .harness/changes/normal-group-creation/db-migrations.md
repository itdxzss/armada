# 数据库迁移

- `V101__normal_group_creation.sql`：创建任务、计划群、成员冻结表及权限入口。
- `V103__normal_group_creation_protocol_commands.sql`：增加四类协议动作的当前 `command_id`
  关联列及唯一索引。
- `V104__normal_group_creation_event_id_length.sql`：将 `normal_group_creation_item.last_event_id`
  从 `VARCHAR(64)` 扩容为 `VARCHAR(255)`，容纳由协议账号、事件类型和命令 ID 组成的统一结果事件 ID。
- `V113__normal_group_creation_secondary_admin.sql`：为任务表增加次管理员分组与数量快照，新增
  `normal_group_creation_item_secondary_admin` 明细表，保存每群次管理员、普通成员锚点、四个
  联系人方向状态、入群状态和提权结果；不增加新的协议 action 或协议命令表字段。
- V103 每个列和索引都通过 `information_schema` 独立检查后再执行 DDL，支持环境中已存在
  部分结构时安全收敛；Flyway 仍以版本校验和为正式执行依据。
- 上线前先核对三张表的租户索引和 V103 五个唯一索引，再启动 Kafka consumer。

## V104 回滚约束

- 扩容不改变现有数据语义，旧版本应用可继续读写，应用回滚时无需缩短字段。
- 禁止直接回退为 `VARCHAR(64)`；已有长事件 ID 时会再次触发截断或丢失数据。
- 如必须收缩，先确认 `MAX(CHAR_LENGTH(last_event_id)) <= 64`，并在独立维护窗口执行。

## V113 回滚约束

- 应用回滚时保留新增列和次管理员明细表，旧版本不会读取这些结构，无需破坏性回滚。
- 禁止手工删除共享库中的次管理员明细。若未来确认永久下线，先等待所有新版本任务终态并备份，
  再通过新的 Flyway 版本执行清理。
