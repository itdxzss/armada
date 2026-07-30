# Flyway V085 冲突修复设计

## 背景

测试环境数据库已经执行 `V085__account_group_membership_last_exit.sql`，Flyway checksum 为
`810248183`。当前 `1.0.2-snapshot` 分支却使用同一版本号发布
`V085__historical_group_created_at.sql`，checksum 为 `1081112955`，导致 Flyway validate
失败并使 `armada-backend` 容器持续重启。

## 方案比较

1. **保留已执行历史并顺延新迁移（采用）**：恢复数据库实际执行过的旧 `V085`，当前两个尚未执行的迁移依次改为 `V086`、`V087`。该方案不修改数据库历史，重新部署后 Flyway 可先校验旧迁移，再正常执行新迁移。
2. **执行 `flyway repair`（拒绝）**：会把数据库历史 checksum 改成当前错误脚本，但数据库实际 schema 仍来自旧脚本，历史记录和真实结构失真。
3. **删除或手工修改 `flyway_schema_history`（拒绝）**：属于破坏性真库操作，既违反项目规范，也可能重复执行已落库 DDL/DML。

## 文件与版本

- 恢复 `V085__account_group_membership_last_exit.sql`，内容必须与已部署版本完全一致，checksum 固定为 `810248183`。
- `V085__historical_group_created_at.sql` 顺延为 `V086__historical_group_created_at.sql`。
- `V086__historical_group_pull_source_account_group.sql` 顺延为 `V087__historical_group_pull_source_account_group.sql`。
- SQL 内容和业务语义不变，只修复版本分配。

## 回归门禁

- `FlywayAppliedMigrationCompatibilityTest` 固定旧 `V085` 的文件名和 checksum。
- 历史群两条 SQL 合同测试改为读取新版本路径。
- 先运行聚焦测试确认旧状态失败，再移动迁移文件并确认转绿。
- 最后运行完整 `mvn test`，同时检查迁移目录不存在重复版本。

## 边界

本次只修改本地仓库，不执行 `flyway repair`、不连接数据库写数据、不部署远程环境。
