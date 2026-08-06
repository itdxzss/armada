# 第一套环境 Flyway V101 撞号恢复设计

## 背景与根因

第一套环境的 `flyway_schema_history` 已于 2026-08-06 02:17:18 成功执行
`V101__normal_group_creation.sql`，记录 checksum 为 `419410967`。当前
`1.0.2-snapshot` 分支却以同一版本号新增了
`V101__pull_task_manager_admin_stage.sql`，Flyway 因版本 101 的已执行迁移与本地解析迁移
不一致而拒绝启动。

当前 nginx 已正常运行；后端因 Flyway validate 失败持续重启。联合部署脚本在后端验活失败后
会把前后端一并标记为失败，但前端本身不需要修复。

## 方案

1. 从提交 `e69b785` 原样恢复数据库已执行的
   `V101__normal_group_creation.sql`，确保文件名、内容与 checksum 均与第一套环境历史一致。
2. 将尚未在第一套环境执行的管理员阶段迁移顺延为
   `V102__pull_task_manager_admin_stage.sql`，不修改其 SQL 行为。
3. 在 `FlywayAppliedMigrationCompatibilityTest` 中登记 V101 的既有 checksum，防止后续再次
   改写已执行迁移；同步更新管理员阶段迁移结构测试引用的版本号。
4. 不执行 `flyway repair`，不删除或改写 `flyway_schema_history`，不手工执行迁移 DDL/DML。
   由应用启动时的 Flyway 正常校验 V101 并执行 V102。

## 验证与部署

1. 先用聚焦测试证明当前分支缺少已执行 V101，随后完成文件恢复与版本顺延并转绿。
2. 运行 Flyway checksum 契约测试、管理员阶段迁移结构测试及后端打包。
3. 仅向第一套环境部署后端，不重复部署已正常运行的前端。
4. 验证 Flyway 历史中 V101 仍为 `normal group creation` 且 checksum 不变，V102 新增成功；
   验证后端容器稳定、重启计数不再增长、启动日志出现应用启动成功，并通过真实 API 冒烟。

## 回滚与风险

- 部署前的代码回滚只需撤销 V101 恢复、V102 重命名和对应测试修改。
- V102 一旦成功执行，不回滚或改写迁移文件；后续修正只能新增更高版本迁移。
- V102 包含任务阶段重编号和运行中任务回拨，依赖其持久化 checkpoint 保证幂等。部署后必须
  同时核对 checkpoint 的 `stage_renumbered` 与 `manager_rewound` 均为 1。
