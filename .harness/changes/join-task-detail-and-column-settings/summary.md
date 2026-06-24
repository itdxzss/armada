# 变更：进群任务「列设置」+「详情字段」对齐

## 变更概述
任务中心「进群任务」页对齐菜单需求两条 P0：
1. **列设置**：列表表头工具条加「列设置」下拉，可勾选显隐 7 个数据列（任务名称/总数/已执行/成功/进群间隔/任务状态/创建时间）；选择列、操作列固定常显。
2. **详情**：进群任务详情抽屉三字段对齐字段说明 —— 补齐账号管理员两态展示 + 群链接后端脱敏 + 链接列防撑破。「进群状态」原已合规（成功绿 `.ok` / 失败红 `.bad`），未改。

## 影响模块
- 后端：`wheel-api-tenant`（TenantJoinTaskService.results 链接脱敏）
- 前端：`wheel-saas-web`（JoinTaskList.vue）

## 数据库变更
无。

## API 变更
无新端点。`GET /api/tenant/join-tasks/{id}/results` 行为变更：返回的 `link` 字段**改为脱敏值**
（`chat.whatsapp.com/****` + 邀请码末 3 位，如 `chat.whatsapp.com/****IJK`）；失败明细里的非群链接原文保持原样。

## 详情字段对账（对齐字段说明）
| 字段 | 规则 | 实现 |
|---|---|---|
| 账号 | 号码为主 + 管理员状态在下方，已设→「已被设置为管理」/未设→「未被设置为管理」 | 号码 + `admin-badge` 两态恒显（非管理员 `.muted` 灰），文案对齐 |
| 群链接 | 脱敏如 `chat.whatsapp.com/****Bu9`，过长不撑破 | **后端** `maskGroupLink`（用户拍板放后端，列表/详情统一口径、防全链接外泄）；前端 `.link-cell` word-break+max-width 兜底 |
| 进群状态 | 成功绿 / 失败红，每条必有状态 | 原已实现（`resultClass`：成功`.ok`绿、失败`.bad`红），未改 |

## 关键约束
- 脱敏放后端（用户拍板）：`maskGroupLink` 仅对含 `chat.whatsapp.com` 的群链接脱敏，非群链接（失败明细坏链接）原样返回便于运营排查；常量 `GROUP_LINK_MASK_PREFIX` / `MASK_TAIL_LEN=3`，复用 `GroupLinkUrls.extractInviteCode`。
- 列设置纯前端、内存态不持久化（与账号列表 `AccountList` 一致）；菜单 z-index=50 远低于自绘 select 下拉(30000)。
- 空数据行 colspan 动态（`visibleTableColumnCount` = 选择列 + 可见数据列 + 操作列）。

## 测试
- 后端 `TenantJoinTaskServiceTest`：27（+1：群链接脱敏 / 非链接原文保留），tenant 模块编译通过
- 前端 `JoinTaskList.test.ts`：21（+2：管理员两态、列设置隐藏列）；前端全量 473 绿；vue-tsc clean

## 回滚方案
纯增量，无 DB 迁移。`git revert` 本次提交即可（脱敏改动会让 results 重新返回原始链接，无数据影响）。
