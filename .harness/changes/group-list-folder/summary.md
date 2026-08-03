# 变更记录：群组列表运营分组

- 日期 / 分支 / worktree: 2026-08-03 / `1.0.2-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户要求参考竞品群组列表的“批量分组、管理群组分组”；设计文档 `docs/superpowers/specs/2026-08-03-group-list-folder-design.md`
- 状态: 实现与自动化验证完成，未部署

## 目标（一句话）

为 Armada 群组列表增加与 WS 导入分组相互独立的单一运营分组，支持筛选、批量绑定、取消绑定和安全管理分组。

## 缺口拆解 / 任务清单

- [x] 只读分析竞品“批量分组”和“管理群组分组”交互及接口行为。
- [x] 对账 Armada `group_link_label/label_id`、群组列表后端和前端现状。
- [x] 确认单分组、允许未分组、独立模型和首期范围。
- [x] 完成并提交设计文档。
- [x] 编写实施计划：`docs/superpowers/plans/2026-08-03-group-list-folder.md`。
- [x] 后端新增 `group_folder`、`group_link.folder_id`、CRUD、筛选和批量设置能力。
- [x] 前端新增筛选、批量分组和分组管理交互。
- [x] 完成后端、前端自动化验证并更新本记录。

## 关键设计决策

- 采用 `group_folder + group_link.folder_id`；`group_link.label_id` 继续只表示 WS 链接导入分组。
- 每个群最多属于一个运营分组，`folder_id = NULL` 表示未分组。
- 删除运营分组时先清空所属群组的 `folder_id`，不删除群组、导入批次或导入分组。
- 批量绑定和取消绑定限制 1～100 个群组，全有或全无。
- 首期不把分组选择扩展到新建群、获取账号下群组或批量进群。
- 否决复用 `group_link_label`：会污染导入统计，并继承危险的级联删除语义。
- 否决独立关系表：单分组需求下增加不必要的 JOIN 和事务复杂度。

## 验证（evidence-before-done）

- 后端目标及相邻回归：
  `mvn -Dtest='GroupFolderMigrationSqlTest,GroupFolderServiceImplTest,GroupFolderControllerTest,GroupLinkServiceImplTest,GroupLinkControllerTest,GroupConverterTest,MysqlModeMapperInMemoryTest' test`，81 项通过，0 失败、0 错误。
- 后端编译：`mvn -DskipTests test-compile`，`BUILD SUCCESS`。
- Mapper XML：`xmllint --noout GroupFolderMapper.xml GroupLinkMapper.xml` 通过；V090 与 change SQL 副本 `cmp` 一致。
- 前端 API、页面契约及相邻群详情回归：目标 Node test 命令共 21 项通过，0 失败。
- 前端静态门禁：定向 ESLint 通过；本地 `node_modules/.bin/tsc --noEmit` 与 `node_modules/.bin/vue-tsc --noEmit --skipLibCheck` 通过。
- 前端生产构建：`node_modules/.bin/vite build` 通过，输出 4.04 MB。
- `pnpm typecheck` 包装命令因本机 pnpm 判定依赖目录元数据不一致，尝试联网重装后被受限网络/非 TTY 中止；未改锁文件或重装依赖，改用相同本地 `tsc/vue-tsc` 二进制完成检查。
- 未运行 `GroupLinkMapperDbTest/GroupLinkLabelMapperDbTest`：它们读取 `.env` 连接真实测试库，当前未确认数据库目标；新增 Mapper XML 已由 H2 MySQL mode 真执行覆盖。
- 未做本地浏览器联调：本轮未启动或连接本地后端/测试库，不伪报人工冒烟结果。

## 部署

- commit / 环境 / 部署后验证结果: 用户已授权提交并推送 `1.0.2-snapshot`；未连接部署环境，未部署。

## 遗留 / 跟进

- `.harness/wiki/数据模型.md` 是自动生成文档，按仓库规则未手工修改；待确认测试库目标并执行真实迁移后再通过生成流程更新。
- 上线前仍需在明确的测试环境执行真库迁移/Mapper 回归和浏览器冒烟；迁移号当前为 `V090`。
