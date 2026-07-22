# Web Account Group Membership Marketing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在新建营销任务的账号群树中展示并允许选择全部关系状态，在营销详情中分离当前关系、最后协议群状态和最后执行结果，并单独展示跳过统计。

**Architecture:** `src/api/marketing-task.ts` 固化后端加法契约；纯函数 helper 负责关系状态、协议群状态和执行结果三套独立文案映射；创建抽屉只展示关系标签，不据此禁用群；详情抽屉直接渲染后端聚合后的成功、失败、跳过计数，不在前端重新推导统计。

**Tech Stack:** Vue 3、TypeScript 5、Element Plus、Node.js built-in test runner、pnpm、Vite

---

## 0. 执行边界与兼容口径

本计划只修改 `/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`。执行前读取仓库
`AGENTS.md` 和本计划，并使用 `superpowers:using-git-worktrees` 从目标分支最新提交创建隔离 worktree；
当前工作区已有 `package.json`、`pnpm-workspace.yaml` 在途修改，不得在原工作区实现或提交。

后端字段均按加法契约处理：历史接口缺失 `membershipStatus`、`skippedMessageCount` 时页面使用安全默认值，
未知枚举显示“未确认”或“未知”，不能抛异常。前端不修改 `group_link` 全局群组列表页面，也不在创建任务时
根据群关系状态拦截选择。

## Task 1: 固化 API 类型和三套状态映射

**Files:**

- Modify: `src/api/marketing-task.ts`
- Create: `src/views/task/group-marketing/components/group-membership-status.ts`
- Create: `src/views/task/group-marketing/components/group-membership-status.test.ts`
- Modify: `src/views/task/group-marketing/components/group-execution-result.ts`
- Modify: `src/views/task/group-marketing/components/group-execution-result.test.ts`
- Modify: `src/views/task/group-marketing/components/group-send-status.test.ts`

- [ ] **Step 1: 先写关系状态映射失败测试**

```ts
import assert from "node:assert/strict";
import { describe, it } from "node:test";

// @ts-expect-error Node's built-in TypeScript runner needs the explicit extension here.
import { groupMembershipStatusMeta } from "./group-membership-status.ts";

describe("group membership status meta", () => {
  it("maps all confirmed membership states", () => {
    assert.equal(groupMembershipStatusMeta("IN_GROUP").label, "在群");
    assert.equal(groupMembershipStatusMeta("UNCONFIRMED").label, "未确认");
    assert.equal(groupMembershipStatusMeta("KICKED_OUT").label, "被踢出");
    assert.equal(groupMembershipStatusMeta("LEFT").label, "已主动退出");
    assert.equal(groupMembershipStatusMeta("NOT_IN_GROUP").label, "已不在群");
  });

  it("falls back safely for old and future responses", () => {
    assert.equal(groupMembershipStatusMeta(undefined).label, "未确认");
    assert.equal(groupMembershipStatusMeta("FUTURE_STATE").label, "未确认");
  });
});
```

在 `group-execution-result.test.ts` 增加 `SKIPPED -> 已跳过 / warning / tagged=true` 断言；保留 unknown
返回普通 `-` 的断言。`group-send-status.test.ts` 继续只验证最后协议群状态，防止把 `LEFT/NOT_IN_GROUP`
误塞入旧的协议状态 helper。

- [ ] **Step 2: 运行并确认先失败**

```bash
node --test \
  src/views/task/group-marketing/components/group-membership-status.test.ts \
  src/views/task/group-marketing/components/group-execution-result.test.ts \
  src/views/task/group-marketing/components/group-send-status.test.ts
```

Expected: FAIL，新 helper 和 `SKIPPED` 映射不存在。

- [ ] **Step 3: 扩展 API 类型**

在 `src/api/marketing-task.ts` 增加并复用：

```ts
export type AccountGroupMembershipStatus =
  | "IN_GROUP"
  | "UNCONFIRMED"
  | "KICKED_OUT"
  | "LEFT"
  | "NOT_IN_GROUP";

export type MarketingGroupExecutionResult =
  | "SUCCESS"
  | "FAILED"
  | "SKIPPED";
```

接口字段固定为：

```ts
export interface MarketingTreeGroup {
  groupLinkId: number;
  groupJid: string;
  groupName?: string | null;
  linkUrl: string;
  isAdmin?: boolean | null;
  membershipStatus?: AccountGroupMembershipStatus | null;
  membershipStatusText?: string | null;
  statusUpdatedAt?: number | null;
}

export interface MarketingTaskGroupStatRow {
  groupLinkId?: number | null;
  groupJid?: string | null;
  groupLinkUrl?: string | null;
  groupName?: string | null;
  membershipStatus?: AccountGroupMembershipStatus | null;
  groupStatus?: MarketingGroupSendStatus | null;
  executionResult?: MarketingGroupExecutionResult | null;
  executionReason?: string | null;
  sentMessageCount: number;
  failedMessageCount: number;
  skippedMessageCount?: number | null;
  lastAttemptAt?: number | null;
  lastSentAt?: number | null;
  lastReason?: string | null;
}
```

`MarketingTaskAccountTargetRow` 增加可空 `skippedMessageCount`；`MarketingTaskDetail` 增加可空
`skippedMessageCount`。保持 `MarketingTaskRow` 列表契约不变，避免无后端字段的列表页被迫伪造数据。

- [ ] **Step 4: 实现纯函数映射**

`group-membership-status.ts` 使用完整映射：

```ts
export interface GroupMembershipStatusMeta {
  label: string;
  tagType: "success" | "warning" | "danger" | "info";
}

const UNCONFIRMED_META: GroupMembershipStatusMeta = {
  label: "未确认",
  tagType: "info"
};

const MEMBERSHIP_META: Record<string, GroupMembershipStatusMeta> = {
  IN_GROUP: { label: "在群", tagType: "success" },
  UNCONFIRMED: UNCONFIRMED_META,
  KICKED_OUT: { label: "被踢出", tagType: "danger" },
  LEFT: { label: "已主动退出", tagType: "warning" },
  NOT_IN_GROUP: { label: "已不在群", tagType: "info" }
};

export function groupMembershipStatusMeta(
  status: string | null | undefined
): GroupMembershipStatusMeta {
  return status ? (MEMBERSHIP_META[status] ?? UNCONFIRMED_META) : UNCONFIRMED_META;
}
```

`group-execution-result.ts` 的 `RESULT_META` 增加：

```ts
SKIPPED: {
  label: "已跳过",
  tagType: "warning",
  tagged: true
}
```

并把 `GroupExecutionResultMeta.tagType` 联合类型增加 `warning`。

- [ ] **Step 5: 验证并提交契约**

```bash
node --test \
  src/views/task/group-marketing/components/group-membership-status.test.ts \
  src/views/task/group-marketing/components/group-execution-result.test.ts \
  src/views/task/group-marketing/components/group-send-status.test.ts
pnpm typecheck
git add src/api/marketing-task.ts \
  src/views/task/group-marketing/components/group-membership-status.ts \
  src/views/task/group-marketing/components/group-membership-status.test.ts \
  src/views/task/group-marketing/components/group-execution-result.ts \
  src/views/task/group-marketing/components/group-execution-result.test.ts \
  src/views/task/group-marketing/components/group-send-status.test.ts
git commit -m "feat: add marketing membership status types"
```

Expected: 定向测试和 typecheck 退出码 0。

## Task 2: 创建任务账号群树展示全部状态且保持可选

**Files:**

- Modify: `src/views/task/group-marketing/components/GroupMarketingCreateDrawer.vue`
- Modify: `src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts`

- [ ] **Step 1: 写创建树失败测试**

在现有 source-contract test 增加：

```ts
it("shows membership status for every lazy-loaded group without disabling it", () => {
  assert.match(source, /groupMembershipStatusMeta/);
  assert.match(source, /group\.membershipStatusText/);
  assert.match(source, /group\.membershipStatus/);
  assert.match(source, /membershipLabel/);
  assert.match(source, /disabled: !accountSelectable\(account\)/);
  assert.doesNotMatch(source, /membershipStatus[^\n]*disabled/);
});
```

另在测试 fixture/文本断言中覆盖五种后端状态文案，确保账号可选时退出状态群节点仍未 disabled。

- [ ] **Step 2: 运行并确认先失败**

```bash
node --test src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts
```

Expected: FAIL，当前群节点只显示管理员/成员。

- [ ] **Step 3: 给群节点添加独立状态元数据**

扩展本地 `TreeNode`：

```ts
interface TreeNode {
  id: string;
  label: string;
  disabled?: boolean;
  disabledReason?: string;
  membershipLabel?: string;
  membershipTagType?: "success" | "warning" | "danger" | "info";
  isLeaf?: boolean;
  children?: TreeNode[];
}
```

导入 `groupMembershipStatusMeta`。`toGroupTreeNodes` 必须优先使用后端非空
`membershipStatusText`，否则使用本地枚举映射：

```ts
function toGroupTreeNodes(account: MarketingTreeAccount): TreeNode[] {
  return account.groups.map(group => {
    const statusMeta = groupMembershipStatusMeta(group.membershipStatus);
    return {
      id: groupTreeKey(account.accountId, group.groupLinkId),
      label: `${group.groupName || group.groupJid} · ${group.isAdmin ? "管理员" : "成员"}`,
      disabled: !accountSelectable(account),
      membershipLabel: group.membershipStatusText?.trim() || statusMeta.label,
      membershipTagType: statusMeta.tagType,
      isLeaf: true
    };
  });
}
```

树节点 slot 在主 label 后渲染 `v-if="data.membershipLabel"` 的小号 plain `el-tag`。disabled 仍只取决于
`accountSelectable(account)`；不得根据 `KICKED_OUT/LEFT/NOT_IN_GROUP` 增加 disabled、tooltip 或提交校验。

- [ ] **Step 4: 验证选择语义并提交**

```bash
node --test \
  src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts \
  src/views/task/group-marketing/composables/marketing-selection.test.ts
pnpm typecheck
git add src/views/task/group-marketing/components/GroupMarketingCreateDrawer.vue \
  src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts
git commit -m "feat: show membership states in marketing tree"
```

Expected: 五种状态群都可生成 `GROUP_FIXED` selection；账号不可用规则保持不变。

## Task 3: 详情分离当前关系、协议状态和执行结果

**Files:**

- Modify: `src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue`
- Modify: `src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts`

- [ ] **Step 1: 写详情展示失败测试**

调整字段顺序契约，要求汇总和群行包含独立事实：

```ts
it("renders membership, protocol status, execution result and skipped counts separately", () => {
  assert.match(source, /label="跳过条数"/);
  assert.match(source, /groupMembershipStatusMeta\(group\.membershipStatus\)/);
  assert.match(source, /groupSendStatusMeta\(group\.groupStatus\)/);
  assert.match(source, /groupExecutionResultMeta\(group\.executionResult\)/);
  assert.match(source, /group\.skippedMessageCount \?\? 0/);
  assert.match(source, /row\.skippedMessageCount \?\? 0/);
});

it("shows reasons for failed and skipped executions", () => {
  assert.match(source, /\["FAILED", "SKIPPED"\]\.includes/);
  assert.match(source, /group\.executionReason \|\| "未知原因"/);
});
```

历史详情缺新字段时断言 `membershipStatus` 显示“未确认”、三个 skipped 位置显示 0，空 groups 仍显示
“暂无发送记录”。

- [ ] **Step 2: 运行并确认先失败**

```bash
node --test src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts
```

Expected: FAIL，当前页面没有关系标签或跳过统计，原因只在 FAILED 时展示。

- [ ] **Step 3: 扩展任务和账号汇总**

任务 `el-descriptions` 在“失败条数”后增加：

```vue
<el-descriptions-item label="跳过条数">
  {{ detail.skippedMessageCount ?? 0 }}
</el-descriptions-item>
```

账号表在发送总条数后增加“账号失败条数”和“账号跳过条数”，分别显示
`row.failedMessageCount` 与 `row.skippedMessageCount ?? 0`。不要把 skipped 加进 failed，也不要在前端用
总数相减计算。

- [ ] **Step 4: 重排群明细为三个独立状态**

群明细表头固定为：当前关系、最后协议状态、群名称、群 GID、成功、失败、跳过、最后发送时间、最后执行。

- 当前关系：`groupMembershipStatusMeta(group.membershipStatus)`。
- 最后协议状态：现有 `groupSendStatusMeta(group.groupStatus)`。
- 成功/失败/跳过：直接显示三个后端计数，skipped 缺失回退 0。
- 最后执行：`groupExecutionResultMeta(group.executionResult)`；`FAILED` 和 `SKIPPED` 都在标签后显示
  `executionReason || "未知原因"`。

同步修改 CSS grid 为九列并保留 `@media (width <= 960px)` 的双列回退。执行原因的颜色使用普通次要文字，
不要把 `SKIPPED` 原因染成失败红色。

- [ ] **Step 5: 验证详情并提交**

```bash
node --test \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts \
  src/views/task/group-marketing/components/group-membership-status.test.ts \
  src/views/task/group-marketing/components/group-execution-result.test.ts \
  src/views/task/group-marketing/components/group-send-status.test.ts
pnpm typecheck
git add src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts
git commit -m "feat: show skipped marketing group details"
```

Expected: 当前 `LEFT` + 历史协议 `NORMAL` + 最后执行 `SKIPPED` 可同时显示，跳过数不影响失败数。

## Task 4: 前端变更记录和完整门禁

**Files:**

- Create: `.harness/changes/account-group-membership-marketing/summary.md`

- [ ] **Step 1: 写前端变更记录**

记录后端依赖字段、五种关系状态、`SKIPPED`、历史响应回退、未修改全局群组列表、测试命令和结果。
明确创建页“可见可选”和运行时“后端跳过”是两层独立行为。

- [ ] **Step 2: 运行 group-marketing 全部 Node tests**

```bash
node --test src/views/task/group-marketing/components/*.test.ts \
  src/views/task/group-marketing/composables/*.test.ts
```

Expected: 0 failures，所有测试文件真实执行。

- [ ] **Step 3: 运行静态检查和构建**

```bash
pnpm typecheck
pnpm exec eslint \
  src/api/marketing-task.ts \
  src/views/task/group-marketing/components/GroupMarketingCreateDrawer.vue \
  src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts \
  src/views/task/group-marketing/components/group-membership-status.ts \
  src/views/task/group-marketing/components/group-membership-status.test.ts \
  src/views/task/group-marketing/components/group-execution-result.ts \
  src/views/task/group-marketing/components/group-execution-result.test.ts \
  src/views/task/group-marketing/components/group-send-status.test.ts \
  --max-warnings 0
pnpm exec prettier --check \
  src/api/marketing-task.ts \
  src/views/task/group-marketing/components/GroupMarketingCreateDrawer.vue \
  src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue \
  src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts \
  src/views/task/group-marketing/components/group-membership-status.ts \
  src/views/task/group-marketing/components/group-membership-status.test.ts \
  src/views/task/group-marketing/components/group-execution-result.ts \
  src/views/task/group-marketing/components/group-execution-result.test.ts \
  src/views/task/group-marketing/components/group-send-status.test.ts
pnpm build
git diff --check
```

Expected: 全部退出码 0。若构建受现有外部依赖或环境阻塞，记录原始命令和错误，不得声称通过。

- [ ] **Step 4: 请求评审并提交记录**

执行 `superpowers:requesting-code-review`，重点检查三套状态是否混用、退出状态是否被误禁用、历史响应是否
安全、skipped 是否被计入 failed，以及窄屏布局。修复问题后：

```bash
git add .harness/changes/account-group-membership-marketing/summary.md
git commit -m "docs: record marketing membership UI rollout"
git status --short
```

Expected: 业务改动和变更记录均已提交，工作树只剩执行者明确保留的无关文件。
