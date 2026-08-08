# 管理员邀请拉手回执后延迟 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 每条管理员邀请拉手的协议回执到达后，固定等待 3 秒才允许提交下一条邀请。

**Architecture:** 仅修改拉手邀请回执状态机写入执行行 `nextRunAt` 的基准时间：由动作提交时间改为协议回执发生时间。沿用现有调度器和数据库字段，不增加配置或接口。

**Tech Stack:** Java 17、Spring Boot、JUnit 5、Mockito。

## Global Constraints

- 仅作用于普通群链接任务的管理员邀请拉手阶段。
- 固定使用 3,000 毫秒；不修改 `pullIntervalSeconds` 的批量拉人规则。
- 测试先红后绿；不修改数据库结构、接口或协议契约。

---

### Task 1: 邀请回执后的固定等待

**Files:**

- Modify: `armada-api/src/test/java/com/armada/task/service/impl/PullTaskPullerInviteResultServiceImplTest.java:42-71`
- Modify: `armada-api/src/main/java/com/armada/task/service/impl/PullTaskPullerInviteResultServiceImpl.java:31,86-93`

**Interfaces:**

- Consumes: `PullTaskPullerInviteCallback.occurredAt()` 的协议回执发生时间。
- Produces: `PullTaskExecutionResultTransition.nextRunAt()`，供普通群链接调度器决定下一条管理员邀请的最早提交时间。

- [ ] **Step 1: 写入失败测试**

将成功回执测试命名为 `successWritesFactsThenWaitsThreeSecondsAfterResult`，保留动作提交时间 `1_000L` 和回执时间 `1_100L`，断言捕获到的 `nextRunAt()` 为手工推导的 `4_100L`：

```java
assertThat(executionChange.getValue().nextRunAt()).isEqualTo(4_100L);
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd armada-api && mvn -Dtest=PullTaskPullerInviteResultServiceImplTest#successWritesFactsThenWaitsThreeSecondsAfterResult test
```

Expected: 断言失败，现有实现写入 `2_000L`，证明它错误地以提交时间加 1 秒安排下一次邀请。

- [ ] **Step 3: 最小生产修改**

在 `PullTaskPullerInviteResultServiceImpl` 将邀请延迟常量替换为 `3_000L`，并在 `transitionProtocolResult` 中将 `nextRunAt` 计算改为：

```java
Math.addExact(callback.occurredAt(), INVITE_RESULT_DELAY_MS)
```

这使回执发生于 `1_100L` 时，下一条邀请最早于 `4_100L` 执行。

- [ ] **Step 4: 运行聚焦测试并确认通过**

Run:

```bash
cd armada-api && mvn -Dtest=PullTaskPullerInviteResultServiceImplTest test
```

Expected: PASS，成功、未知、部分事实写入和重复终态回执的既有行为保持通过。

- [ ] **Step 5: 运行相邻邀请事务集成测试**

Run:

```bash
cd armada-api && mvn -Dtest=PullTaskPullerInviteTransactionIntegrationTest test
```

Expected: PASS，邀请 Outbox 提交和回执状态收敛保持兼容。

