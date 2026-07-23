# 拉群营销前端 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增独立“拉群营销”菜单，完成任务创建、任务列表、生命周期操作、群组明细子页面，并在现有账号列表增加轻量的营销占用展示与筛选。

**Architecture:** 新功能放在 `src/views/task/group-pull-marketing/`，不修改或复用旧 `group-creation-marketing` 的业务状态。接口集中在一个新 API 模块。账号列表只接收后端分页已经补充的占用字段，点击分组时才按需加载占用详情，不在页面对每行发请求。

**Tech Stack:** Vue 3、TypeScript、Element Plus、pure-admin-thin、现有 `armadaRequest`、Node test runner、Vue Test Utils。

---

## 实施前提

- 后端接口前缀固定为 `/api/group-pull-marketing-tasks`。
- 创建接口使用 `multipart/form-data`，只提交一个 `config` JSON part 和一个 `materialFile` 文件 part。
- 生产菜单来自外部租户菜单/权限服务，不在 Armada 数据库迁移里硬编码；本仓库只补页面、mock 路由和路由契约测试。
- 新菜单名称使用“拉群营销”，与现有“建群营销”并存；不得改名、覆盖或跳转到旧页面。
- 群组明细是拉群营销任务下的隐藏子页面，不是一级菜单。
- 需求真值以同目录 `summary.md` 为准。

## 页面与文件结构

```text
src/api/group-pull-marketing.ts
src/api/group-pull-marketing.test.ts
src/views/task/group-pull-marketing/
├── constants.ts
├── constants.test.ts
├── index.vue
├── detail/
│   └── index.vue
├── composables/
│   ├── useGroupPullMarketingPage.ts
│   ├── useGroupPullMarketingPage.test.ts
│   ├── useGroupPullMarketingDetail.ts
│   └── useGroupPullMarketingDetail.test.ts
└── components/
    ├── GroupPullMarketingCreateDrawer.vue
    ├── GroupPullMarketingCreateDrawer.test.ts
    ├── GroupPullMarketingTaskTable.vue
    ├── GroupPullMarketingTaskTable.test.ts
    ├── GroupPullMarketingSummary.vue
    ├── GroupPullMarketingGroupTable.vue
    └── GroupPullMarketingGroupTable.test.ts

src/views/account/index/marketing-occupancy.ts
src/views/account/index/marketing-occupancy.test.ts
src/views/account/index/components/MarketingOccupancyDialog.vue
```

## Task 1: 建立拉群营销 API 契约

**Files:**

- Create: `src/api/group-pull-marketing.ts`
- Create: `src/api/group-pull-marketing.test.ts`

- [ ] **Step 1: 写 API URL、参数和 multipart 失败测试**

测试必须拦截 `armadaRequest`，覆盖：

```text
GET    /api/group-pull-marketing-tasks
POST   /api/group-pull-marketing-tasks
GET    /api/group-pull-marketing-tasks/{id}
GET    /api/group-pull-marketing-tasks/{id}/groups
POST   /api/group-pull-marketing-tasks/{id}/start
POST   /api/group-pull-marketing-tasks/{id}/pause
POST   /api/group-pull-marketing-tasks/{id}/resume
POST   /api/group-pull-marketing-tasks/{id}/release
DELETE /api/group-pull-marketing-tasks/{id}
```

创建请求断言 `FormData` 只有 `config` 和 `materialFile` 两个 key；`config` 是 `application/json` Blob，文件对象保持原文件名和内容。

- [ ] **Step 2: 运行测试确认失败**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types src/api/group-pull-marketing.test.ts
```

Expected: 模块不存在。

- [ ] **Step 3: 定义三个状态维度**

```ts
export type GroupPullTaskStatus = 1 | 2 | 5 | 7 | 8;
// 1待启动 2执行中 5已暂停 7已完成 8已手动结束

export type GroupPullBlockReason = 0 | 1 | 2 | 3 | 4 | 5;
// 0无 1等待建群账号 2等待营销账号 3等待料子数据 4系统异常 5人工处理

export type GroupPullResourceStatus = 1 | 2 | 3 | 4 | 5;
// 1未锁定 2已锁定 3释放中 4已释放 5释放失败
```

三个字段分别保存和展示，不在前端拼成新的组合枚举。

- [ ] **Step 4: 定义创建配置**

```ts
export interface CreateGroupPullMarketingConfig {
  taskName: string;
  builderGroupId: number;
  successGroupId?: number | null;
  failureGroupId?: number | null;
  marketingGroupId: number;
  marketingAccountGroupLimit: number;
  marketingTemplateId: number;
  sendIntervalSeconds: number;
  groupNamePrefix?: string | null;
  friendRetryLimit: number;
  materialPerGroup: number;
  speakPermission: 1 | 2 | 3;
  builderExitEnabled: boolean;
  remark?: string | null;
  taskEndAt: number;
}
```

不发送分组名称、模板名称、文件内容 Base64、无效数据数或重复数据数。

- [ ] **Step 5: 定义列表、详情与群明细结构**

列表行至少包含：

```ts
export interface GroupPullMarketingTaskRow {
  id: number;
  taskName: string;
  status: GroupPullTaskStatus;
  blockReason: GroupPullBlockReason;
  resourceStatus: GroupPullResourceStatus;
  totalDataCount: number;
  completedDataCount: number;
  groupSuccessCount: number;
  groupFailureCount: number;
  marketingAccountTotalCount?: number | null;
  marketingAccountUsedCount: number;
  createdAt: number;
  taskEndAt: number;
}
```

详情扩展配置 ID、模板、权限配置和备注。群明细包含：executionId、builder/marketer 账号、groupName/Jid/inviteUrl、groupStatus、materialJoinedCount、groupMemberCount、sentMessageCount、speakPermission、builderExitEnabled、builderExitStatus、marketerAdminStatus、executionStatus、failureStage、failureReason、marketingSendStatus、lastSentAt、groupCreatedAt。

- [ ] **Step 6: 实现函数并通过测试**

列表查询只传有值参数；群明细查询传 `page/pageSize`；删除使用 `armadaRequest("delete", ...)`。

```bash
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types src/api/group-pull-marketing.test.ts
```

Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add src/api/group-pull-marketing.ts src/api/group-pull-marketing.test.ts
git commit -m "feat: add group pull marketing api"
```

## Task 2: 建立状态、颜色和操作权限纯函数

**Files:**

- Create: `src/views/task/group-pull-marketing/constants.ts`
- Create: `src/views/task/group-pull-marketing/constants.test.ts`

- [ ] **Step 1: 写状态文案失败测试**

断言：

- 主状态依次显示待启动、执行中、已暂停、已完成、已手动结束；
- 阻塞原因 0 显示“无”，其余显示需求文案；
- 资源状态包含释放失败，但不提供“重新释放”操作；
- 未知值统一显示 `-`，不猜测状态。

- [ ] **Step 2: 写行操作矩阵失败测试**

```ts
actions({ status: 1, resourceStatus: 1 })
// ["start", "detail", "delete"]

actions({ status: 2, resourceStatus: 2 })
// ["pause", "release", "detail"]

actions({ status: 5, resourceStatus: 2 })
// ["resume", "release", "detail"]

actions({ status: 7, resourceStatus: 3 })
// ["detail"]

actions({ status: 8, resourceStatus: 4 })
// ["detail"]
```

释放中、已释放、释放失败都不得再次出现 release。

- [ ] **Step 3: 写管理员联动纯函数测试**

```ts
requiresMarketerAdmin(2, false) === true;  // 禁言
requiresMarketerAdmin(1, true) === true;   // 建群号退出
requiresMarketerAdmin(3, false) === false; // 不禁言且不退出
```

该函数只用于页面说明，不额外修改用户选择的权限值。

- [ ] **Step 4: 实现常量与纯函数**

提供任务列定义、下拉选项、epoch 时间格式化和状态 tag 类型。列表仍只有一个“任务状态”列，该列内部显示三行状态，不拆三列。

- [ ] **Step 5: 运行测试并提交**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types \
  src/views/task/group-pull-marketing/constants.test.ts
git add src/views/task/group-pull-marketing/constants.ts \
  src/views/task/group-pull-marketing/constants.test.ts
git commit -m "feat: define group pull marketing ui states"
```

## Task 3: 实现创建抽屉和单文件覆盖

**Files:**

- Create: `src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.vue`
- Create: `src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.test.ts`
- Create: `src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.ts`
- Create: `src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.test.ts`
- Modify: `src/api/account-group.ts`
- Modify: `src/api/account-group.test.ts`

- [ ] **Step 1: 写初始表单测试**

初始值固定为：

```ts
{
  taskName: "",
  builderGroupId: "",
  successGroupId: "",
  failureGroupId: "",
  marketingGroupId: "",
  marketingAccountGroupLimit: 10,
  marketingTemplateId: "",
  sendIntervalSeconds: 30,
  groupNamePrefix: "",
  friendRetryLimit: 3,
  materialPerGroup: 3,
  speakPermission: 1,
  builderExitEnabled: true,
  remark: "",
  taskEndAt: endOfToday235959()
}
```

- [ ] **Step 2: 写表单校验失败测试**

按顺序覆盖：

1. 任务名称必填、trim 后最长 128。
2. 建群账号分组、营销分组必选且不能相同；该限制只在前端执行。
3. 建群账号分组和营销分组均显示正常在线数量，空分组不允许保存。
4. 成功/失败转入分组可空、可相同；选择建群分组提示“不能选择当前分组”；选择有营销占用的分组提示“该分组正在任务中使用，不能选择”。下拉不提前过滤这些项。
5. 单营销账号最大群组数必须是 `>=1` 的整数，无业务最大值。
6. 营销模板必选，只从现有模板列表选择。
7. 发送间隔必须是 `>=1` 的整数，文案明确“营销轮次间隔（秒）”。
8. 群名前缀可空；备注最长 512。
9. 加好友重试次数必须是 0～10 整数。
10. 单群抽取数量必须是 `>=1` 的整数。
11. 群发言权限必选；退出开关必须得到明确布尔值。
12. 结束时间必填且晚于当前时间。
13. 必须选择一个 TXT 或 CSV 文件。

- [ ] **Step 3: 写单文件覆盖测试**

连续选择 `a.txt`、`b.csv` 后：

```ts
expect(page.materialFile.value?.name).toBe("b.csv");
```

不展示多文件列表，不合并内容，不在浏览器解析和统计无效/重复条数。扩展名不支持时立即提示且保留上一个有效文件。

- [ ] **Step 4: 扩展账号分组 API 行**

`AccountGroupApiRow` 增加：

```ts
marketingOccupancyType?: number | null;
marketingOccupancyTaskId?: number | null;
marketingLockedAt?: number | null;
```

并在 `toAccountGroupRow` 映射后端 camelCase 字段。创建抽屉只用 `marketingOccupancyTaskId != null` 判断结果转入分组当前被占用；营销分组本身即使当前被占用，仍允许保存待启动任务，真正启动时由后端抢锁。

- [ ] **Step 5: 实现抽屉布局**

抽屉分为两个可见区域：

1. “基础设置”：任务、分组、账号上限、模板、轮次间隔、前缀、好友重试、单群数量、单文件、结束时间、备注。
2. “群信息设置”：发言权限 radio 和建群账号退出 switch。

不显示群头像、群描述、公告、邀请权限等本期未实现项，也不放灰色占位。

管理员联动只显示说明：选择禁言或开启退出时提示“营销账号必须设置为管理员”；不新增管理员开关。

- [ ] **Step 6: 实现保存行为**

保存按钮只叫“保存”，不出现草稿或保存并启动。保存成功：

1. 提示“拉群营销任务已保存，当前为待启动”；
2. 关闭抽屉；
3. 清空表单和文件；
4. 刷新列表；
5. 不自动调用 start；
6. 不跳详情，停留在一级列表。

- [ ] **Step 7: 运行测试**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types \
  src/api/account-group.test.ts \
  src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.test.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.test.ts
```

Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add src/api/account-group.ts src/api/account-group.test.ts \
  src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.vue \
  src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.test.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.test.ts
git commit -m "feat: add group pull marketing create form"
```

## Task 4: 实现一级任务列表和生命周期操作

**Files:**

- Create: `src/views/task/group-pull-marketing/index.vue`
- Create: `src/views/task/group-pull-marketing/components/GroupPullMarketingTaskTable.vue`
- Create: `src/views/task/group-pull-marketing/components/GroupPullMarketingTaskTable.test.ts`
- Modify: `src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.ts`
- Modify: `src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.test.ts`

- [ ] **Step 1: 写列表列和状态单元格失败测试**

断言表格准确显示：任务 ID、任务名称、任务状态、总数据量/完成数据量、建群数量、失败数量、营销号数量/占用数量、创建时间、结束时间、操作。

示例格式：

```text
数据：3000/1250
营销号：50/18
```

营销账号总数在待启动未锁组时允许显示 `-/0`，不得伪装为 0 个营销账号。

- [ ] **Step 2: 写查询和分页测试**

搜索字段为任务 ID、任务名称和主状态；查询或重置都回到第 1 页。列表按后端返回顺序展示，不在前端二次排序。

- [ ] **Step 3: 写生命周期动作测试**

- start：不二次确认；成功刷新列表；抢锁冲突显示后端明确错误，任务仍待启动。
- pause：成功提示营销分组继续锁定。
- resume：成功刷新。
- release：必须弹出原需求二次确认文案；确认后只调用一次 release。
- delete：只有待启动显示；确认后调用 DELETE；执行态不渲染入口。
- detail：所有状态都显示。

释放确认文案固定为：

```text
释放账号后，当前任务将停止继续创建群组，并解除建群账号及营销分组占用。是否继续？
```

- [ ] **Step 4: 实现页面和表格**

操作按钮由 Task 2 的纯函数生成，避免模板重复写状态判断。资源状态为释放中或释放失败时只保留明细，不提供重新释放。

- [ ] **Step 5: 运行测试并提交**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types \
  src/views/task/group-pull-marketing/components/GroupPullMarketingTaskTable.test.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.test.ts
git add src/views/task/group-pull-marketing/index.vue \
  src/views/task/group-pull-marketing/components/GroupPullMarketingTaskTable.vue \
  src/views/task/group-pull-marketing/components/GroupPullMarketingTaskTable.test.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.test.ts
git commit -m "feat: add group pull marketing task list"
```

## Task 5: 实现群组明细隐藏子页面

**Files:**

- Create: `src/views/task/group-pull-marketing/detail/index.vue`
- Create: `src/views/task/group-pull-marketing/components/GroupPullMarketingSummary.vue`
- Create: `src/views/task/group-pull-marketing/components/GroupPullMarketingGroupTable.vue`
- Create: `src/views/task/group-pull-marketing/components/GroupPullMarketingGroupTable.test.ts`
- Create: `src/views/task/group-pull-marketing/composables/useGroupPullMarketingDetail.ts`
- Create: `src/views/task/group-pull-marketing/composables/useGroupPullMarketingDetail.test.ts`

- [ ] **Step 1: 写路由参数和加载测试**

详情从 `route.params.id` 读取正整数任务 ID，并行加载任务详情和第一页群明细。非法 ID 显示错误并返回列表，不向 API 发送 `NaN`。

- [ ] **Step 2: 写任务摘要测试**

摘要显示：三个状态维度、四个分组配置（建群、成功转入、失败转入、营销）、营销账号上限、模板、轮次间隔、群名前缀、好友重试次数、单群抽取数量、发言权限、退出配置、结束时间和备注。成功/失败分组未配置显示“未配置，账号保留原分组”。

- [ ] **Step 3: 写群明细表测试**

表格按后端 `execution.id ASC` 分页结果直接展示以下列：

- 序号；
- 建群账号、营销账号；
- 群名称、群链接；
- 群状态；
- 进群人数、群人数；
- 营销号发送条数；
- 发言权限、建群号退出配置；
- 退群状态、管理员状态；
- 创建时间；
- 建群执行结果、失败阶段、失败原因；
- 营销发送状态、最后发送时间。

`groupMemberCount=null` 显示 `-`，不得显示 0。失败记录没有群 ID/链接时仍保留一行。

- [ ] **Step 4: 写群链接行为测试**

- 有效 URL：渲染“打开”和“复制”；打开使用新窗口安全属性；复制成功提示。
- URL 为空：根据 failure reason/status 显示“未获取群链接”或“链接获取失败”，不创建空 anchor。
- 已失效：显示“链接已失效”，禁用打开和复制。

- [ ] **Step 5: 实现详情页面**

页面顶部提供“返回任务列表”和手动刷新。分页变化只重载群明细，不重复加载不变的任务配置；手动刷新才同时刷新摘要和当前页。

本期不新增群状态手动检测按钮、不做补查更新、不在明细页重试失败步骤。

- [ ] **Step 6: 运行测试并提交**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types \
  src/views/task/group-pull-marketing/components/GroupPullMarketingGroupTable.test.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingDetail.test.ts
git add src/views/task/group-pull-marketing/detail \
  src/views/task/group-pull-marketing/components/GroupPullMarketingSummary.vue \
  src/views/task/group-pull-marketing/components/GroupPullMarketingGroupTable.vue \
  src/views/task/group-pull-marketing/components/GroupPullMarketingGroupTable.test.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingDetail.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingDetail.test.ts
git commit -m "feat: add group pull marketing details page"
```

## Task 6: 在现有账号列表展示营销占用

**Files:**

- Modify: `src/api/account.ts`
- Modify: `src/api/account-mapping.ts`
- Modify: `src/api/account.test.ts`
- Modify: `src/views/account/index/composables/useAccountListPage.ts`
- Modify: `src/views/account/index/composables/useAccountListPage.test.ts`
- Modify: `src/views/account/index/index.vue`
- Modify: `src/views/account/index/components/AccountListTable.vue`
- Modify: `src/views/account/index/components/AccountListTable.test.ts`
- Modify: `src/views/task/group-marketing/index.vue`
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts`
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts`
- Create: `src/views/account/index/marketing-occupancy.ts`
- Create: `src/views/account/index/marketing-occupancy.test.ts`
- Create: `src/views/account/index/components/MarketingOccupancyDialog.vue`

- [ ] **Step 1: 写占用颜色纯函数测试**

在 `src/api/account.ts` 定义后端和页面共用的展示类型，不把账号分组表中的持久化数字直接暴露给组件：

```ts
export type MarketingOccupancyDisplayType =
  | "FREE"
  | "SIMPLE_MARKETING"
  | "GROUP_PULL_MARKETING"
  | "GROUP_PULL_MODE_2"
  | "GROUP_PULL_MODE_3"
  | "OTHER_MARKETING"
  | "PAUSED"
  | "RELEASING";
```

使用该展示类型 key，颜色固定为：

```ts
export const MARKETING_OCCUPANCY_COLORS = {
  FREE: "#A2A8B2",
  SIMPLE_MARKETING: "#6F9FEF",
  GROUP_PULL_MARKETING: "#9A84E8",
  GROUP_PULL_MODE_2: "#E7A15A",
  GROUP_PULL_MODE_3: "#58B7C4",
  OTHER_MARKETING: "#BE87C7",
  PAUSED: "#766C82",
  RELEASING: "#71869D"
} as const;
```

标签文字统一白色。未知值回退到 FREE 灰色并显示“空闲”，不使用红色或绿色。

- [ ] **Step 2: 扩展账号 API 映射与查询测试**

`TenantAccount` 增加：

```ts
marketing_occupancy_type?: MarketingOccupancyDisplayType | null;
marketing_occupancy_task_id?: number | null;
marketing_locked_at?: string | null;
```

同时在 `account.ts` 内部的原始 `ArmadaTenantAccount` 增加 camelCase 响应字段：

```ts
marketingOccupancyType?: MarketingOccupancyDisplayType | null;
marketingOccupancyTaskId?: number | null;
marketingLockedAt?: number | null;
```

`toTenantAccount` 显式完成 camelCase 到 snake_case 的映射，并用现有 `formatEpochMillis(row.marketingLockedAt, null)` 生成展示时间；不得把 epoch 毫秒声明成字符串后直接透传。

列表查询增加：

```ts
marketingOccupancyType?: MarketingOccupancyDisplayType | "";
occupiedTaskKeyword?: string;
occupiedBusinessType?: number | "";
callable?: boolean | "";
```

`toTenantAccountListParams` trim 文本、保留明确的 `false`，不得用 truthy 判断丢掉“不可调用”。批量按筛选操作的 query 也必须保留这四项，以免页面显示条件与批量目标不一致。

- [ ] **Step 3: 写分组标签与懒加载测试**

账号表中的“分组”列改为纯色 tag：

- 有 groupId 时可点击；
- 同页相同分组的每行只展示后端已经返回的字段，不额外请求；
- 用户点击后才请求 `GET /api/account-groups/{id}/marketing-occupancy`；
- 重复点击同一分组可使用本次页面会话缓存，列表刷新时清空缓存；
- 空闲分组详情只显示“当前空闲”，不跳任务。

- [ ] **Step 4: 在 account-group API 增加占用详情函数**

**Additional Files:**

- Modify: `src/api/account-group.ts`
- Modify: `src/api/account-group.test.ts`

```ts
export interface MarketingOccupancyDetail {
  groupId: number;
  occupancyType: MarketingOccupancyDisplayType;
  taskBusinessType?: number | null;
  taskId?: number | null;
  taskName?: string | null;
  taskStatus?: number | null;
  resourceStatus?: number | null;
  lockedAt?: number | null;
  marketingAccountTotalCount: number;
  marketingAccountUsedCount: number;
}
```

- [ ] **Step 5: 实现占用详情弹窗**

显示任务类型、任务 ID、任务名称、当前任务状态、分组锁定状态、锁定时间、账号总数、实际调用数。任务名称可点击：

- `taskBusinessType=1` 跳现有 `/task/group-marketing` 并携带任务 ID 查询参数；
- `taskBusinessType=2` 跳 `/task/group-pull-marketing/{taskId}`；
- 模式二、三或其他尚无页面时仅显示，不制造不存在的路由。

现有普通营销页目前不会消费 task ID 查询参数，本 Task 同步补齐：`useGroupMarketingTaskPage` 增加 `openDetailById(taskId)`，直接复用现有 `getMarketingTaskDetail`、详情抽屉和轮询逻辑，原来的 `openDetail(row)` 改为委托该方法；`group-marketing/index.vue` 监听路由中的 `taskId`，仅对正整数调用一次 `openDetailById`。测试从账号占用详情跳转到 `/task/group-marketing?taskId=42` 后，断言营销页打开 42 的现有详情抽屉；非法、缺失或未变化的 taskId 不重复请求。

- [ ] **Step 6: 实现三个高级筛选区域**

1. “营销占用类型”下拉：每个 option 同时显示色块和名称。
2. “占用任务”：一个任务 ID/名称关键词输入框，加一个营销任务类型下拉。
3. “可调用状态”：可调用、不可调用。

查询和重置沿用现有分页行为；不在前端自行按当前页过滤。

- [ ] **Step 7: 运行账号页测试**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types \
  src/api/account.test.ts \
  src/api/account-group.test.ts \
  src/views/account/index/marketing-occupancy.test.ts \
  src/views/account/index/components/AccountListTable.test.ts \
  src/views/account/index/composables/useAccountListPage.test.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
```

Expected: PASS，测试桩断言加载一页账号不会触发 N 条占用详情请求。

- [ ] **Step 8: 提交**

```bash
git add src/api/account.ts src/api/account-mapping.ts src/api/account.test.ts \
  src/api/account-group.ts src/api/account-group.test.ts \
  src/views/account/index/composables/useAccountListPage.ts \
  src/views/account/index/composables/useAccountListPage.test.ts \
  src/views/account/index/index.vue \
  src/views/account/index/components/AccountListTable.vue \
  src/views/account/index/components/AccountListTable.test.ts \
  src/views/account/index/components/MarketingOccupancyDialog.vue \
  src/views/account/index/marketing-occupancy.ts \
  src/views/account/index/marketing-occupancy.test.ts \
  src/views/task/group-marketing/index.vue \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
git commit -m "feat: show marketing occupancy in account list"
```

## Task 7: 注册独立菜单与隐藏详情路由

**Files:**

- Modify: `mock/asyncRoutes.ts`
- Modify: `src/api/routes.test.ts`
- Create: `src/router/group-pull-marketing-route.test.ts`

- [ ] **Step 1: 写菜单与详情路由失败测试**

mock 菜单中新增两个 route record：

```ts
{
  path: "/task/group-pull-marketing",
  component: "task/group-pull-marketing/index",
  name: "TaskGroupPullMarketing",
  meta: {
    title: "拉群营销",
    roles: ["admin", "common"],
    showParent: true,
    module_key: "group_pull_marketing",
    perm_key: "tenant:group_pull_marketing:view"
  }
},
{
  path: "/task/group-pull-marketing/:id",
  component: "task/group-pull-marketing/detail/index",
  name: "TaskGroupPullMarketingDetail",
  meta: {
    title: "拉群营销明细",
    showLink: false,
    activePath: "/task/group-pull-marketing",
    roles: ["admin", "common"],
    module_key: "group_pull_marketing",
    perm_key: "tenant:group_pull_marketing:view"
  }
}
```

断言现有 `TaskGroupCreationMarketing` route 仍存在且路径、组件均未改。

- [ ] **Step 2: 运行测试确认失败**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types \
  src/api/routes.test.ts src/router/group-pull-marketing-route.test.ts
```

- [ ] **Step 3: 修改 mock 路由并通过测试**

新菜单放在任务管理目录中，与“营销任务”“建群营销”并列。详情 route `showLink=false`，不会生成第二个菜单项。

- [ ] **Step 4: 配置外部生产菜单与权限**

这是部署配置步骤，不写 Armada Flyway：在生产租户菜单/权限来源增加同样两个 route record，并为角色分配 `tenant:group_pull_marketing:view`。若该权限服务只允许一个可见菜单记录，隐藏详情 route 仍需返回给前端但设置 `showLink=false`。

- [ ] **Step 5: 提交前端路由改动**

```bash
git add mock/asyncRoutes.ts src/api/routes.test.ts \
  src/router/group-pull-marketing-route.test.ts
git commit -m "feat: register group pull marketing routes"
```

## Task 8: 前端完整回归与构建验证

**Files:**

- Verify: all files changed above

- [ ] **Step 1: 运行拉群营销相关测试**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types \
  src/api/group-pull-marketing.test.ts \
  src/views/task/group-pull-marketing/constants.test.ts \
  src/views/task/group-pull-marketing/components/GroupPullMarketingCreateDrawer.test.ts \
  src/views/task/group-pull-marketing/components/GroupPullMarketingTaskTable.test.ts \
  src/views/task/group-pull-marketing/components/GroupPullMarketingGroupTable.test.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingPage.test.ts \
  src/views/task/group-pull-marketing/composables/useGroupPullMarketingDetail.test.ts
```

Expected: PASS。

- [ ] **Step 2: 运行账号列表回归**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types \
  src/api/account.test.ts src/api/account-group.test.ts \
  src/views/account/index/account-display.test.ts \
  src/views/account/index/marketing-occupancy.test.ts \
  src/views/account/index/components/AccountListTable.test.ts \
  src/views/account/index/composables/useAccountListPage.test.ts
```

Expected: PASS。

- [ ] **Step 3: 运行旧营销页面回归**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs \
  --test --experimental-strip-types \
  src/api/group-creation-marketing.test.ts \
  src/views/task/group-creation-marketing/composables/useGroupCreationMarketingPage.test.ts \
  src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts
```

Expected: PASS，旧建群营销和普通营销菜单行为不变。

- [ ] **Step 4: 运行类型检查**

```bash
pnpm typecheck
```

Expected: PASS。

- [ ] **Step 5: 运行生产构建**

```bash
pnpm build
```

Expected: PASS。

## 前端验收口径

- 创建页只有“保存”；保存后回列表，状态为待启动，不自动启动。
- 页面只保留一个料子文件，后选覆盖前选；只接受 TXT/CSV。
- 群信息区域只展示本期两个配置，不展示未来功能占位。
- 一级列表一个状态单元格同时展示主状态、阻塞原因和资源状态。
- 待启动无释放按钮；执行中/暂停且资源已锁定才有释放按钮；释放失败不提供业务重试。
- 群组明细是隐藏子页面，失败群记录也可见，空人数不显示为 0。
- 账号列表不逐行请求任务信息；占用详情只在点击分组标签后加载。
- 占用标签严格使用需求颜色且统一白字，不与账号正常/封禁颜色混用。
- 现有“营销任务”和“建群营销”菜单、接口与页面均保持独立并通过回归。
