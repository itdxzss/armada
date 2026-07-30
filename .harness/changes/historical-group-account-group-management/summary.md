# 历史群账号组维度管理

## 目标

- [x] 账号组选择后直接分页展示组内全部历史群。
- [x] “加载群列表”同步刷新组内在线账号的 WhatsApp 群数据。
- [x] 后端按群自动选择在线群主/管理员执行详情、成员管理和拉群操作。
- [x] 列表补充关联账号、邀请链接、国家、群创建时间，并把完整 JID 放到最后。

## 数据模型

- `group_link_preview.group_created_at` 保存 WhatsApp 群创建时间，和已有创建者、邀请码等群预览事实同属群预览聚合。
- `historical_group_pull_execution.source_account_group_id` 保存执行创建时的来源账号组，实际执行账号仍写入原字段用于审计。
- 两列均被业务写入和查询使用，没有新增并行表或重复事实。

## 查询与刷新语义

- 历史范围只取账号组内各账号首次 baseline 的群 JID 并集；当前关系则聚合账号组内全部账号的持久化群关系。
- 尚无确定关系的 baseline 群仍展示；刷新后仅保留至少一个群主/管理员关系的群，离线管理员群保留但置为不可操作。
- 显式刷新逐个同步账号组内所有在线正常账号；单账号失败和单群邀请链接失败彼此隔离，邀请链接按群去重获取。

## 部署与回滚

- 部署顺序：Android 协议服务 → Armada 后端（执行 Flyway）→ 前端。
- 回滚脚本见 `rollback.sql`；删除 `source_account_group_id` 会丢失新合同下的来源账号组审计维度。
- 未连接或修改任何远程数据库，因此没有基于真实 `information_schema` 重新生成 `.harness/wiki/数据模型.md`；部署到确认环境并执行 Flyway 后，必须运行仓库生成流程刷新该文档，禁止手改生成物。

## 验证

- Android 本次相关包测试和全量构建通过；全量 vet/test 仍有仓库既有失败，详见交付说明。
- 后端相关 Controller、Service、协议映射、Mapper 与迁移合同测试 91 条全部通过；XML、API 文档测试和 `mvn -DskipTests package` 通过。
- `CountryMapperDbTest` 需要本机 MySQL，当前因 `root` 无密码而无法启动上下文；未连接或修改远程数据库。
- 前端 API、页面、详情和执行测试 33 条全部通过，类型检查、ESLint、Stylelint 及生产构建通过。
