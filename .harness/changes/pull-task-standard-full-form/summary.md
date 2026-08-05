# 变更记录：普通群链接拉群任务完整表单持久化

- 日期 / 分支 / worktree: 2026-08-04 / `feature/pull-task-normal-group-link` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: `docs/superpowers/specs/2026-08-04-pull-task-standard-full-form-persistence-design.md`
- 实施计划: `docs/superpowers/plans/2026-08-04-pull-task-standard-full-form-persistence.md`
- 状态: 已实现并完成本地聚焦验证（不提交、不部署）

## 目标（一句话）

让普通群链接拉群创建页的一次保存完整接收、校验、事务持久化并回读全部本期字段，同时闭环 Armada 本地群头像和群组分组链接来源。

## 缺口拆解 / 任务清单

- [x] V095 扩充 `pull_task_standard_setting`，新增 `pull_task_standard_group_setting`。
- [x] 通过 V095 增量把已执行 V090 的 `group_folder.name` 从 64 放宽到 100，不改写 V090 checksum。
- [x] group folder 后端 CRUD 与有效链接读取。
- [x] folder + 粘贴链接合并规划、规范化去重并冻结。
- [x] 21 个本期顶层字段、11 个群资料字段的创建 DTO、枚举、两张设置表 Mapper/Writer；后期字段不进入合同。
- [x] JPG/JPEG/PNG、≤512000 字节的本地头像上传、读取、临时删除。
- [x] 整表事务创建、幂等、规范化详情与列表回读。
- [x] 自动启动失败后的幂等重试；头像绑定事务与删除/过期清理按规范文件 Key 互斥到事务结束，路径别名在加锁和查库前拒绝。
- [x] 任务软删除提交后的头像清理与 24 小时未绑定文件清理。
- [x] 前端完整请求、真实头像文件、失败保留与无重复上传重试。
- [x] 前后端聚焦回归、前端生产构建、静态安全检查和本地 Docker 真库启动验证。
- [ ] 连接一次性本地 MySQL 后跑完整 Maven DB 测试和手工端到端业务验收。

## 关键设计决策

- `pull_task_standard_setting` 只保存冻结执行策略；群资料和权限独立归属 `pull_task_standard_group_setting`。
- `avatar_file_key` 只保存一次，不建头像元数据表，不写 BLOB。
- `group_link_label` 是导入来源；`group_folder` 是群组运营分组，两个事实不复用。
- STANDARD/NORMAL_LINK 不再把 DTO 写入 `pull_task.config_json`，也不重复写 `pull_task.group_name`。
- 本阶段只保存和回读群资料设置，不发送 WhatsApp 群资料协议命令。
- `.harness/wiki/数据模型.md` 是真库生成物；本地无真库时不手改，待获授权环境运行生成器。

## 数据库变更

- `V095__pull_task_standard_full_form_settings.sql`：8 个执行设置字段、站台分组列可空、群组分组名称放宽、新建任务级群资料设置表。
- 正式前向迁移副本和说明见 `db-migrations.sql`；逆向步骤见 `rollback.sql`。

## API 变更

- `POST /api/pull-tasks/standard` 接收 21 个本期顶层字段和必填 `groupSetting`，未知字段明确拒绝。
- `GET /api/pull-tasks/standard/{taskId}` 从规范化表回读 `standardSetting`、`groupSetting` 和头像预览 URL。
- `/api/group-folders` 提供分页、下拉、新增、重命名和批量软删除；删除只解除群入口归属。
- `/api/pull-tasks/standard/group-avatars` 提供真实文件上传、预览和未绑定文件删除。

## 最终字段归属

- `pull_task`：`taskName`、`remark` 和生命周期事实；NORMAL_LINK 不再重复写 `config_json` 或 `group_name`。
- `pull_task_standard_setting`：`autoStart`、来源群组分组 ID/名称快照、拉手同步方式、料子管理时机、清空原成员、人数范围、间隔、拉手/站台/并发参数，以及管理/拉手/站台/完成归档账号分组 ID 与名称快照；既有后期风控列仅写服务端默认 0。
- `pull_task_standard_group_setting`：设置顺序、群名或 TXT 文件名模式、唯一 `avatar_file_key`、群描述、任务后自动解除禁言/关闭拉人权限、编辑权限、禁言、群链接权限和限时消息。
- `pull_task_group_execution`：规范化群链接、TXT 文件名、配对顺序和解析统计。
- `pull_task_material_member`：规范化号码与管理员标记。

## Redis 变更

- 无。

## 关键约束

- 图片只允许 JPG/JPEG/PNG，真实字节不超过 512000；存储 Key 必须是上传服务生成的 32 位小写十六进制文件名及 `.jpg`/`.png` 后缀。
- `station_count_per_call=0` 时站台分组可空；大于 0 时必填。
- 头像路径不得由原始文件名或前端路径决定。
- 所有新表与查询受 `tenant_id` 隔离。

## 验证（evidence-before-done）

- 基线：后端聚焦测试 57 个通过；前端相关测试 27 个通过。
- RED：`PullTaskStandardFullFormMigrationSqlTest` 因 V095 不存在产生 4 个预期失败。
- GREEN：`PullTaskStandardFullFormMigrationSqlTest,FlywayAppliedMigrationCompatibilityTest` 共 5 个测试通过。
- GREEN：`PullTaskNormalLinkSchemaSelfTest,PullTaskStandardSettingMapperInMemoryTest` 共 10 个测试通过。
- GREEN：本次功能 24 个后端测试类共 130 个测试通过，0 failure / 0 error；最终 Maven 总耗时 3.593 秒。
- GREEN：头像服务与过期清理专项 9 个测试通过，覆盖事务结束前互斥、路径别名拒绝、24 小时边界和符号链接保护；任务删除后的头像清理另由 `PullTaskMutationServiceTest` 覆盖。
- GREEN：本地 Docker MySQL 8.4.8、Redis 7.4 均 healthy；后端成功校验并执行 96 个 Flyway 迁移至 V095，Spring Boot 在 8080 启动，前端在 8848 启动，两端 HTTP 均返回 200。
- GREEN：新增 Spring 构造器实例化回归，修复 `PullTaskGroupAvatarCleanupJob` 多构造器未显式注入导致的真实启动失败。
- GREEN：4 份新增/修改 Mapper XML 均通过 `xmllint --noout`，`git diff --check` 通过。
- GREEN：前端相关 6 个测试套件共 43 个测试通过；`pnpm typecheck`、ESLint、Prettier、`pnpm build` 均通过，Vite 构建 9.29 秒。
- GREEN：`armada-deploy/deploy-test.test.sh` 通过。
- 历史验证记录：首次完整 `mvn test` 时 OrbStack 尚未启动，仓库既有 DB 测试连接默认 3306 也因 `root@localhost` 无密码访问被拒；停止前 364 个测试中 63 个均为该上下文加载错误、0 个 assertion failure。本轮已启动一次性本地 MySQL/Redis 并完成应用真启动，但尚未重跑全仓测试。
- 仓库既有阻塞：`armada-deploy/package-prod.test.sh` 要求的 `armada-deploy/prod/scripts/inspect-production-host.sh` 当前不存在，本次未越界补建。

## 本地头像运行与备份

- 应用目录：`${ARMADA_PULL_TASK_AVATAR_STORAGE_DIR:-/app/data/pull-task-avatars}`。
- Compose 主机挂载：`${ARMADA_PULL_TASK_AVATAR_HOST_DIR:-./data/pull-task-avatars}`。
- 未绑定头像默认保留 86400000 毫秒，每 3600000 毫秒扫描一次；只扫描直接租户目录和普通文件，不跟随符号链接。绑定事务、手动删除和清理使用同一文件 Key 锁，删除前再次查询有效绑定。
- 数据库只保存随机文件 Key。备份任务数据库时必须同时备份头像主机目录，否则详情记录仍在但图片文件无法恢复。

## 回滚方案

- 先回退调用新字段的前后端版本，再按 `rollback.sql` 删除新增表/列。
- 站台列恢复 `NOT NULL` 前必须先补齐所有 NULL 历史行。
- 不删除旧 `pull_task.config_json`、`pull_task.group_name`，也不自动删除头像目录。

## 部署

- commit / 环境 / 部署后验证结果: 用户要求仅本地开发，不提交、不部署。

## 遗留 / 跟进

- 数据模型生成和任何真库验证都需要用户另行确认目标环境。
- 群资料/权限本期只保存和回读，尚未发送 WhatsApp 协议命令；协议应用需单独设计调度与幂等回执。
- 在具备一次性本地 MySQL 或 Docker 的开发环境复跑 `mvn test`，并执行计划末尾的手工验收清单。
