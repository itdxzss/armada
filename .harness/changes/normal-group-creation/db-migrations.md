# 数据库迁移

- `V101__normal_group_creation.sql`：创建任务、计划群、成员冻结表及权限入口。
- `V103__normal_group_creation_protocol_commands.sql`：增加四类协议动作的当前 `command_id`
  关联列及唯一索引。
- V103 每个列和索引都通过 `information_schema` 独立检查后再执行 DDL，支持环境中已存在
  部分结构时安全收敛；Flyway 仍以版本校验和为正式执行依据。
- 上线前先核对三张表的租户索引和 V103 五个唯一索引，再启动 Kafka consumer。
