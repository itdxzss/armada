# Account Group Membership Marketing Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 跨 Android 协议、Armada 后端和 Vue 前端交付账号群关系状态保留、营销运行时跳过和完整明细展示。

**Architecture:** Android 先以加法契约发布精确成员变化事件和带完整性标记的群快照；Armada 再把 `account_group_membership` 状态化，并在每轮营销写 attempt 前批量读取关系状态；Vue 最后消费新增字段，创建任务时展示全部关系，明细中分离当前关系、最后协议群状态和最后执行结果。Baileys 不改代码，Armada 对其旧快照契约做按账号协议后端识别的兼容。

**Tech Stack:** Go 1.25、Kafka JSON 事件、Java 17、Spring Boot 3.3.5、MyBatis/MySQL/Flyway、Vue 3、TypeScript、Element Plus

---

## 0. 子计划与责任边界

按下列三份子计划执行，不把三个仓库塞进同一提交：

1. [Android 事件计划](2026-07-22-android-account-group-membership-events.md)
2. [Armada 状态与营销计划](2026-07-22-armada-account-group-membership-marketing.md)
3. [Vue 展示计划](2026-07-22-web-account-group-membership-marketing.md)

明确不修改 `/Users/daishuaishuai/IdeaProjects/armada-protocol`。现有 Baileys
`account.groups_reported` 不带 `snapshotComplete`，由 Armada 按数据库中的账号
`protocol_id` 兼容：Web 缺字段沿用历史完整快照语义，Android 缺字段按不完整处理。

当前 Android 和 Vue 工作区已有与本需求无关的在途修改。执行阶段必须先使用
`superpowers:using-git-worktrees` 建隔离 worktree，再从目标分支最新提交开始；不得在现有脏工作区
直接实现，不得暂存或提交现有修改。

## 1. 冻结跨仓契约

### 1.1 精确关系事件

```json
{
  "event": "account.group_membership_changed",
  "version": "v1",
  "accountId": "acc_android_1",
  "occurredAt": "2026-07-22T02:00:00Z",
  "data": {
    "tenantId": 7,
    "accountId": 100,
    "protocolAccountId": "acc_android_1",
    "groupJid": "120363001@g.us",
    "action": "remove",
    "selfParticipation": "SELF",
    "source": "android_wgp2"
  }
}
```

只允许 `add/remove/leave`，且只在 `selfParticipation=SELF` 时发布。事件中禁止出现 participant
数组、手机号、PN、LID、operator 或原始 notification。

### 1.2 群快照新增字段

```json
{
  "event": "account.groups_reported",
  "data": {
    "snapshotComplete": true,
    "skippedGroupCount": 0,
    "groups": []
  }
}
```

Android 只有在 IQ 成功、groups 容器存在且 `skippedGroupCount == 0` 时写
`snapshotComplete=true`。Armada 只有对有效完整快照执行缺失关系校准。

### 1.3 API 新增字段

创建任务账号群节点：

```json
{
  "membershipStatus": "KICKED_OUT",
  "membershipStatusText": "被踢出",
  "statusUpdatedAt": 1784685600000
}
```

营销任务详情群节点：

```json
{
  "membershipStatus": "LEFT",
  "groupStatus": "NORMAL",
  "executionResult": "SKIPPED",
  "executionReason": "账号已主动退出群聊",
  "sentMessageCount": 1,
  "failedMessageCount": 0,
  "skippedMessageCount": 1
}
```

关系状态、最后协议群状态、最后执行结果是三个独立事实，前后端禁止复用一个字段表达。

## 2. 发布顺序

- [ ] **Step 1: 完成 Android 子计划并记录 commit**

Expected: 精确事件和快照字段均为加法变更；旧 Armada 对未知事件走现有跳过分支，对未知字段忽略。

- [ ] **Step 2: 在测试 Kafka 抽样验证 Android 契约**

验证一个 `remove self` 事件和一条完整 `account.groups_reported`，只检查事件名、路由 ID、action、
完整性、群数量和低敏感日志；不得复制 participant 或原始载荷到变更记录。

Expected: 精确事件先于或独立于防抖快照到达；快照失败不抹掉精确事件。

- [ ] **Step 3: 完成 Armada 子计划并执行 Flyway/DbTest**

Expected: 关系迁移后只有一条当前行；不可发送状态生成 `SKIPPED` 且无 outbox；Web 旧快照仍可校准，
Android 缺完整性字段不执行缺失更新。

- [ ] **Step 4: 完成 Vue 子计划**

Expected: 创建树全部状态可见可选；详情同时显示当前关系和最后执行，跳过不计失败。

- [ ] **Step 5: 执行测试环境端到端验收**

远程、SSH、数据库、部署前先向用户确认目标环境、三个 commit 和部署范围。按以下固定场景验收：

| 场景 | 关系状态 | 下轮执行 | 协议命令 | 详情统计 |
|---|---|---|---:|---|
| 管理员移除账号 | `KICKED_OUT` | `SKIPPED` | 0 | skipped +1，failed 不变 |
| 账号主动退出 | `LEFT` | `SKIPPED` | 0 | skipped +1，failed 不变 |
| 完整快照缺失且无精确原因 | `NOT_IN_GROUP` | `SKIPPED` | 0 | skipped +1，failed 不变 |
| `UNCONFIRMED` | `UNCONFIRMED` | 正常提交 | 1 | 按真实回执成功或失败 |
| 退出后更新快照重新出现 | `IN_GROUP` | 正常提交 | 1 | 按真实回执统计 |
| 不完整快照缺失 | 原状态不变 | 依原状态 | 不误跳过 | 无错误状态转换 |

- [ ] **Step 6: 记录发布证据和回滚点**

在 Armada change 记录写入三个 commit、测试命令、测试数、测试环境和验收结果。后端若需回滚到旧版本，
必须先执行经审核的回滚 SQL，把 `KICKED_OUT/LEFT/NOT_IN_GROUP` 当前行重新软删；禁止直接回滚旧应用后继续营销发送。

## 3. 总体验收门禁

全部满足才可称为完成：

- Android：`gofmt` 无差异，`go vet ./...`、`go build ./...`、`go test ./...` 通过，协调器定向 race 通过。
- Armada：聚焦测试和 `mvn test` 通过；Flyway、Mapper、租户隔离、状态迁移和 Worker 行为的真库 DbTest 真实执行通过。
- Vue：定向 Node tests、`pnpm typecheck`、定向 lint、`pnpm build` 通过。
- 安全：日志与事件中不存在 participant 手机号、PN、LID、凭据、营销正文或原始 notification。
- 数据：全局 `group_link` 列表不因单账号退出被删除；关系状态只存于 `account_group_membership`。
- 统计：`SKIPPED` 单独累计，不增加成功或失败；全部跳过的任务仍可推进到正常完成。
