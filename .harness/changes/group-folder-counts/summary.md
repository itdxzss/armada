# 群组列表分组数量

- 日期：2026-09-12
- 状态：代码及本地验证完成，未部署
- 需求：群组列表筛选下拉框各分组名称后显示 `（群数量）`，包括全部分组、未分组和零群分组。

## 设计与范围

- 按群组列表未删除记录计数，包含所有健康状态，不跟随关键词、状态等其他筛选条件。
- 分组归属与列表一致：`COALESCE(wa_group.folder_id, group_link.folder_id)`；保留现有租户隔离与选项排序。
- 新增 `GET /api/group-folders/filter-options` 返回 `totalGroupCount`、`unassignedGroupCount`、`folders: [{id, name, groupCount}]`。
- 保留普通拉群使用的 `/options` 与管理列表的可用群统计契约；全部数量独立从所有分组聚合，不能用可见选项相加代替。
- 前端打开下拉框、刷新列表、分组管理、批量分组和删除后更新数量。未加载成功时不伪造零值。
- 涉及 Armada 群组 Controller / Service / Mapper / VO 及同级前端群组列表。
- 数据库结构、Redis、协议层无改动，无 Flyway 迁移。

## 验证

- `xmllint --noout armada-api/src/main/resources/mapper/group/GroupFolderMapper.xml`：退出 0。
- Maven 聚焦 `GroupFolderMapperInMemoryTest,GroupFolderServiceImplTest,GroupFolderControllerTest`：31 项通过，0 失败、0 跳过。H2 真实执行 Mapper XML 与生产租户插件，覆盖全部健康状态、软删除、当前分组覆盖/回退、未分组、空租户、跨租户和与列表 count 一致。
- 先红后绿：新增两项 H2 用例在 SQL 未实现时因缺失映射失败，实现后通过。
- 本机 Mockito 默认自附加失败；使用本机已有 Byte Buddy 1.14.19 agent 重跑成功，未修改依赖或弱化测试。
- `python3 .harness/wiki/test_api_docs.py`：1 项通过，退出 0。
- 前端使用 `node --import ./src/api/__tests__/node-test-alias.mjs --test` 运行 `src/api/group-folder.test.ts`、`src/views/group/list/GroupListFolderIntegration.test.ts`、`src/views/group/list/composables/useGroupListPage.test.ts`：8 项通过；覆盖名称/数量/零值、查询口径独立、操作后刷新、失败不伪造零值、旧请求不覆盖新数量。
- `tsc --noEmit`、`vue-tsc --noEmit --skipLibCheck`、变更文件 ESLint / Prettier、页面 Stylelint：均退出 0。通过已有 `node_modules/.bin` 执行，未重装依赖。
- Vite 生产构建退出 0，16.08 秒；产物在 `/tmp/group-folder-counts-build-20260912`，未覆盖工作区原有 dist。
- 群组目录及 group API 扩大回归：57 项中 52 通过、5 失败。将 HEAD `37dd646eeac86c8decd6bc8a035f8e38603a5970` 的未修改源码导出至独立临时目录，复现相同 5 项失败（头像请求超时参数、成员详情刷新源码断言、三个权限请求参数断言），属于既有测试基线，与本次改动无关。
- 本次未运行真实环境浏览器验收、真库查询或部署，不将本地测试结果视为测试服已生效。

## 发布与回滚

- 本次交付目标为将群组数量功能提交并推送到前后端 `1.0.3-snapshot`；部署另行执行。
- 合入前按 `expert-reviewer` 复核，未发现阻断项。重跑后端 31 项及前端 8 项聚焦测试、tsc/vue-tsc 与变更文件静态检查均通过；既有 5 项群组回归基线失败及未做线上验收的边界不变。
- 本次仅纳入群组数量功能，不包含账号列表、运行时文件或其他 worktree 的在途改动。
- 发布时先后端后前端；回滚对应前后端改动即可，无数据回滚。
