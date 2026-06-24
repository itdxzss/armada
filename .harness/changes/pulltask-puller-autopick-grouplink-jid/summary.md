# 变更记录：拉群任务启动后自动取拉手 + 老群链接 jid 接线修复

- 日期：2026-06-19
- 分支/worktree：`worktree-pulltask-autopull-grouplink`（基于 origin/2.0.0-snapshot）
- spec：`docs/superpowers/specs/2026-06-19-pulltask-puller-autopick-and-grouplink-jid-design.md`
- plan：`docs/superpowers/plans/2026-06-19-pulltask-puller-autopick-and-grouplink-jid.md`

## 变更概述
修复 AUTO + OLD_LINK 拉群任务点「启动」后 0/2、拉不到人的三处根因：
1. **选号口径**（原 Task 1，已由 `87c3a34` 单独提交+部署）：`AvailableByTagFilter` `account_state=2`→`IN(1,2)`，导入新增号(1)在线即可被选为拉手/站台/手动补号。
2. **群链接 jid 接线**（Task 3+5）：MANUAL_PASTE 建任务不再造 `hashCode` 假 GroupLink；按归一 URL 接真实 `group_link`（命中复用 / 未命中新建 `origin=PASTE`）。执行期 `resolveGroupJid` 改从 `group_link.group_jid` 取 jid（不再只认 `@g.us` URL）；jid 未回填则行 PULL_SKIP 等待 `GroupLinkPreviewBackfillJob` 回填，超时才失败。根治 `MISSING_GROUP_JID`。
3. **拉手自动取号**（Task 4）：`executeRow` 占用为空且配了 `puller_group` 名时，仿站台 `assignAndJoinStandbys` 用 `TagMapper.selectIdByTenantAndName` + `AccountSelector.selectAvailable` 自动取号写 `occupied_pullers`，再继续拉。

## 影响模块
- **domain**：`GroupLinkMapper`(+`selectByTenantAndUrl`) + `mapper/tenant/GroupLinkMapper.xml`(+select)。
- **tenant**：`TenantManualTaskService`（`resolveLinks` 改 + 新 `resolvePastedLink`；`parseLongList` 兼容 string-numeric）；`TenantManualTaskRepository`(+`findGroupLinkByUrl`/`insertGroupLink`) + MyBatis 实现。
- **internal**：`DispatchEngineService`（新 `tryAutoAssignPullers`；`resolveGroupJid` 改读 group_link.group_jid；新 `groupJidWaitExceeded` + `@Value` 等待上限；`BLOCK_REASON_BY_ERROR_CODE` 加 `GROUP_JID_UNRESOLVED`、删死 `MISSING_GROUP_JID`）；`PullTaskParams`(+`pullerGroup()`/`pullerNum()`)。
- **app**：`mapper/tenant/AccountMapper.xml`（Task 1，已部署）；测试（`GroupLinkByUrlDbTest`、`TenantManualTaskServiceDbTest`、`DispatchEngineServiceTest`、`TenantManualTaskSupplementJoinTest`、`TenantManualTaskResolvePastedLinkTest`）。

## 数据库变更
- **无 Flyway 迁移。** `group_link.group_jid` / `origin` 列均已存在。
- 运行期数据：MANUAL_PASTE 建任务对未登记的粘贴链接 `INSERT group_link(origin=PASTE, group_jid=NULL)`（复用现有 `insert`，`group_jid` 由 `GroupLinkPreviewBackfillJob` 异步回填）。

## API 变更
- 无新端点、无契约字段变化。
- 行为变化：`POST /api/tenant/task-batches`（建任务）MANUAL_PASTE 分支现把 `task_row.group_link_id` 绑到真实 `group_link.id`（此前是 URL 的 `Math.abs(hashCode())` 假 id）。

## 新增配置
- `wheel.dispatch.group-jid.max-wait-minutes`（默认 `10`）：行绑定的 `group_link` 建出超过该时长仍无 `group_jid` 时，`executeRow` 由「PULL_SKIP 等待」转 `failRow(GROUP_JID_UNRESOLVED)`，防坏链接永久空转。

## Redis 变更
- 无（项目暂未接入 Redis）。

## 关键约束 / 注意
- **occupied_pullers 双格式**：引擎自动取号写**字符串数字数组** `["123"]`（因引擎 `parseOccupiedAccounts` 只认字符串元素，写 numeric 会读回空导致取号不生效）；手动补号路径仍写 numeric `[123]`。租户侧 `parseLongList` 已加宽为**同时兼容 numeric 与 string-numeric**，避免自动取号的行在「行详情展示 / 手动补号合并」时丢号。
- **GROUP_JID_UNRESOLVED → block_reason=GROUP_BANNED**：`GROUP_BANNED` 是「群侧问题」广义桶（其常量 Javadoc 明列「群 jid 缺失 / 链接非法」），归类一致，非误标。
- **mapper XML 改动已 `xmllint` 校验**（红线：裸 `<>` 运行期 crash-loop 全站 502）。
- **DbTest 留部署冒烟**：本地无测试库 creds，`AbstractDbTest @EnabledIf` 跳过；`GroupLinkByUrlDbTest`/`TenantManualTaskServiceDbTest` 在部署到 65.2.123.53 后冒烟验证真库行为。

## 验证
- 本地（JDK17）：`DispatchEngineServiceTest` 86/86、`TenantManualTaskSupplementJoinTest` 19/19、`TenantManualTaskResolvePastedLinkTest` 2/2、`GroupLinkMapper.xml` xmllint 绿、全量 `mvn test`（含 archtest 模块边界）绿（DbTest 跳过）。
- 部署冒烟（待执行）：65.2.123.53 部署后跑 DbTest + 对 `LG9900033081`（或新建粘贴老群链接任务）点启动，观测 row 绑真实 group_link_id → 自动取拉手 → 拉手进群 → 2 料子号 OK → row/批次 COMPLETED。

## 回滚方案
- 纯代码改动，无数据迁移、无不可逆 DB 变更。
- `git revert` 本分支提交（`8d3b97d..49982b2`，共 7 个）即可；`account_state IN(1,2)`（`87c3a34`）为独立提交，可单独 revert（注意 revert 它会让补号/自动取号回到「永远无可用拉手」）。
- 新增 `group_link(origin=PASTE)` 行为历史数据，回滚代码后不影响（仅不再新建）。

## 后续扩展 B（管理员角色启动自动取号，commit 7e17d4f）
- **动机**：use_admin=true 任务点启动时 occupied_admins 为空会卡管理员门控（NO_NEW_ADMIN→ADMIN_SETUP_FAILED）——拉手已自动取号，管理员没有。
- **实现**：把拉手自动取号泛化为 `DispatchEngineService.tryAutoAssignRole(row, groupName, count, role)`（拉手/管理员共用，DRY）；`PullTaskParams` 加 `managerGroup()`/`adminNum()`（仿 pullerGroup/pullerNum）；`doAdminPromotionGate` 在 bootstrap 老管理员校验后、`swapAdminOrFail(NO_NEW_ADMIN)` 前，occupied_admins 空且配了 manager_group 时自动从管理员分组取号写 occupied_admins、再由在线老管理员提权。
- **格式**：occupied_admins 写字符串数字数组 `["123"]`，经 `resolveOccupiedAccountSafe`/`parseOccupiedAccounts` 读回（与拉手一致，opus 评审 + promoteCalls e2e 核实 round-trip）。
- **验证**：DispatchEngineServiceTest 90/90（4 新管理员用例 + 拉手零回归）、archtest 8/8。opus 全审 APPROVED。
- **Minor（非阻塞）**：拉手日志键 auto_puller→auto_assign（无消费方）；AUTO_SUPPLEMENT_PULLER payload 加 role 字段；可补 occupied_admins 写入格式显式断言（已被 e2e 间接锁）。
