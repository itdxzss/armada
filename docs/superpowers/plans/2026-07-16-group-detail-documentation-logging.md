# 群详情后端注释与日志补强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐群详情新增后端代码的业务 Javadoc 和安全、可排障日志，同时保持现有业务与协议行为不变。

**Architecture:** 注释遵循端口说明契约、Adapter 说明 wire 映射、Service 说明业务编排的分层口径。业务成功/失败由 Service 记录，Adapter 只在 DEBUG 记录低层协议动作；所有日志避开正文、完整 JID和协议凭据。

**Tech Stack:** Java 17、Spring Boot 3.3.5、SLF4J、JUnit 5、Mockito。

---

### Task 1: 补齐成员批量协议方法注释

**Files:**

- Modify: `armada-api/src/main/java/com/armada/platform/protocol/port/GroupParticipantPort.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapter.java`

- [x] 扩展端口 Javadoc，写清动作范围、逐 JID 回执和异常。
- [x] 扩展 Adapter Javadoc，写清 30 秒等待、master 转发和 partial 语义。
- [x] 增加不含 JID/协议账号的 DEBUG 调用摘要。

### Task 2: 补齐协议依赖组和群设置 Adapter 注释

**Files:**

- Modify: `armada-api/src/main/java/com/armada/group/service/GroupDetailProtocolPorts.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupSettingsAdapter.java`

- [x] 说明四个端口的职责、组合原因及依赖方向。
- [x] 为 Adapter 类、构造器、公开方法和关键私有方法补齐业务 Javadoc。
- [x] 在统一 `postMode` 边界增加不含协议账号的 DEBUG 设置摘要。

### Task 3: 补齐群详情 Service 注释和关键日志

**Files:**

- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java`

- [x] 补齐类、构造器、全部公开方法及关键私有编排方法的 Javadoc。
- [x] 为群名称、头像、限时消息、权限和成员操作增加成功日志。
- [x] 为协议失败、超时回读和批量结果增加安全日志；不输出正文、完整 JID或协议账号句柄。

### Task 4: 回归验证与记录

**Files:**

- Modify: `.harness/changes/2026-07-15-group-detail-drawer-completion.md`

- [x] 运行 `GroupDetailServiceImplTest`、三个群 HTTP Adapter 测试和 `ProtocolConfigurationTest`。
- [x] 运行 `mvn -q -Dmaven.test.skip=true compile`。
- [x] 运行 `git diff --check`，人工检查日志字段和 Javadoc 一致性。
- [x] 更新变更记录；遵循用户要求不 commit。
