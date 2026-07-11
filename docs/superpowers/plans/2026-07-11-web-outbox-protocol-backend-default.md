# Web Outbox Protocol Backend Default Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让所有当前固定路由到 Web master topic 的 outbox 命令显式写入 `protocol_backend=WEB`,恢复普通营销和建群营销发送。

**Architecture:** 保留数据库 `protocol_backend NOT NULL` 约束,在 Java 的 Web-only outbox row builder 中显式赋值,避免 Mapper 静默兜底掩盖未来 Android 路由遗漏。普通营销与建群营销共用同一个 row builder,群健康检查和账号群同步同步修复同类回归。

**Tech Stack:** Java 17, Spring Boot, MyBatis, JUnit 5, AssertJ, Mockito, Maven

**Execution constraint:** 用户要求直接在当前 `1.0.1-snapshot` 分支修改并保持未提交状态,因此本计划不执行任何 commit。

---

### Task 1: 为 Web-only outbox 增加回归断言

**Files:**
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`

- [ ] **Step 1: 在四条现有用例中增加失败断言**

在群健康检查、账号群同步、普通营销、建群营销用例捕获到 `ProtocolCommandOutbox row` 后分别增加:

```java
assertThat(row.getProtocolBackend()).isEqualTo("WEB");
```

四条用例分别为:

```text
enqueueGroupHealthCheckCommands_singleCommand_insertsGroupLinkCommandWithRoutablePayload
enqueueAccountGroupSyncCommands_singleCommand_insertsMasterRoutedAccountCommand
enqueueMarketingMessageCommands_singleCommand_insertsMasterRoutedAttemptCommand
enqueueMarketingMessageCommands_groupCreationSourceDoesNotRequireMarketingAttempt
```

- [ ] **Step 2: 运行目标测试并确认 RED**

Run:

```bash
mvn -Dtest=ProtocolCommandOutboxServiceImplTest test
```

Expected: 测试失败,失败值为 `actual null`,期望值为 `WEB`;失败原因必须是 Web-only row builder 尚未设置协议后端。

### Task 2: 显式设置 Web-only outbox 的协议后端

**Files:**
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`

- [ ] **Step 1: 写入最小生产代码**

在以下三个方法中,紧跟 `row.setProtocolAccountId(...)` 增加同一行:

```java
row.setProtocolBackend(ProtocolBackend.WEB.name());
```

目标方法:

```text
toGroupHealthCheckOutboxRow
toAccountGroupSyncOutboxRow
toMarketingMessageOutboxRow
```

不修改上线/下线动态路由,不修改 Mapper,不修改营销 command DTO,不改协议层仓库。

- [ ] **Step 2: 运行目标测试并确认 GREEN**

Run:

```bash
mvn -Dtest=ProtocolCommandOutboxServiceImplTest test
```

Expected: `ProtocolCommandOutboxServiceImplTest` 全部通过,包括已有 Web/Android 上下线路由测试。

### Task 3: 完整本地验证并交付未提交 diff

**Files:**
- Verify: `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`
- Verify: `armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`

- [ ] **Step 1: 运行模块编译**

Run:

```bash
mvn -DskipTests compile
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 2: 检查补丁格式**

Run:

```bash
git diff --check
```

Expected: 退出码 0,没有空白错误。

- [ ] **Step 3: 审阅目标 diff 和工作区状态**

Run:

```bash
git diff -- armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java
git status --short
```

Expected: 目标 diff 只包含三个 `WEB` 赋值和四个测试断言;代码与计划文件保持未提交,现有用户改动原样保留。
