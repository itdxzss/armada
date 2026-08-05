# Flyway V093-V098 历史对齐与重新部署设计

## 背景

第一套测试环境后端在提交 `6ea7822` 部署后持续重启，Nginx 后端接口返回 502。
启动日志明确显示 Flyway 检测到两个 `V093`：

- `V093__pull_task_normal_link_execution.sql`
- `V093__whatsapp_group_member_cache.sql`

该问题由分支合并时重排迁移版本造成。除了源码目录存在重复版本，第一套测试库已经执行的
`V096`、`V097` 也与当前分支文件映射不一致，因此只消除重复 `V093` 仍不足以恢复启动。

## 已确认的数据库历史

第一套测试库 `flyway_schema_history` 中 V090-V097 均已成功执行：

| 版本 | 迁移文件 | 数据库校验和 |
| --- | --- | ---: |
| V090 | `V090__group_folder.sql` | -1682709825 |
| V091 | `V091__whatsapp_group_departed_member.sql` | 1816449594 |
| V092 | `V092__whatsapp_group_member_join_fact.sql` | -1133243864 |
| V093 | `V093__pull_task_normal_link_execution.sql` | -1160226712 |
| V094 | `V094__pull_task_group_account_membership_result.sql` | -1117482777 |
| V095 | `V095__pull_task_standard_full_form_settings.sql` | -1758254373 |
| V096 | `V096__whatsapp_group_member_cache.sql` | 380433951 |
| V097 | `V097__group_departure_unknown_metadata.sql` | -1292654150 |

当前分支错误地把成员缓存脚本放在 V093，把新的群列表历史元数据脚本放在 V096，并且缺少
数据库已经执行的 V097 文件。

## 方案比较

### 方案一：恢复已执行历史并顺延新迁移（采用）

以第一套测试库已经执行的文件名和校验和为不可变事实，恢复 V090-V097 的完整映射；把尚未在
第一套测试库执行的群列表历史元数据迁移顺延到下一个可用版本 V098。

该方案不修改数据库历史，部署后 Flyway 会校验 V090-V097，只执行新的 V098。

### 方案二：执行 `flyway repair`（否决）

`repair` 只能让历史表接受当前错误编号，无法让数据库实际结构与脚本语义重新一致，还会掩盖
已经发布迁移被改写的问题。

### 方案三：回滚旧后端制品（仅作应急回滚）

旧制品可以临时恢复服务，但不会修复当前分支，下一次部署仍会因同一冲突失败。

## 代码变更

1. 将 `V093__whatsapp_group_member_cache.sql` 恢复为
   `V096__whatsapp_group_member_cache.sql`，SQL 字节内容必须产生数据库记录中的校验和
   `380433951`。
2. 从仓库历史恢复 `V097__group_departure_unknown_metadata.sql`，内容必须产生校验和
   `-1292654150`。
3. 将当前尚未执行的 `V096__group_list_history_metadata.sql` 顺延为
   `V098__group_list_history_metadata.sql`，业务 SQL 内容保持不变。
4. 更新引用上述迁移路径的 SQL 合同测试。
5. 扩充 `FlywayAppliedMigrationCompatibilityTest`，固定 V090-V097 的文件名与校验和，防止后续
   合并再次改写已发布历史。
6. 保留现有 `FlywayMigrationVersionContractTest` 作为全目录版本唯一性门禁。该测试已在错误状态下
   稳定复现 `V93` 重复，修复后必须转绿。

## 数据与运行时行为

- 不执行 `flyway repair`，不删除、不更新 `flyway_schema_history`。
- 第一套测试库的 V090-V097 只参与校验，不重复执行。
- 部署新制品时，Flyway 应只新增一条 V098 成功记录。
- 如果恢复文件的任一校验和不一致，构建或部署验证必须停止，不通过修改数据库记录绕过。

## 验证与部署

1. 运行 Flyway 版本唯一性、历史兼容性及三个相关 SQL 合同测试。
2. 运行完整 Maven 测试和打包，检查最终 JAR 中 V090-V098 每个版本恰好一个文件。
3. 使用部署脚本先执行 test1 后端部署的参数/制品预检，再重新部署后端，不改前端和协议层。
4. 部署后确认：
   - 后端容器保持运行且重启次数不再增长；
   - 日志不再出现重复版本、checksum mismatch 或迁移失败；
   - 后端健康接口返回预期响应，不再是 502；
   - `flyway_schema_history` 中 V098 只出现一条成功记录。

## 回滚

如果 V098 执行前部署失败，恢复部署前后端制品即可，数据库无需回滚。

如果 V098 已成功执行但应用验证失败，优先修复或回滚应用；由于 V098 是向后兼容的增量字段迁移，
保留其历史记录和结构，不手工删除字段或迁移记录。

## 范围

本次只修复 Armada 后端 Flyway 迁移历史、相关测试并重新部署第一套测试环境后端；不修改前端、
协议层、业务数据或其他环境。
