# Proxy Reonline Conditional Update Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让代理分配在默认事务隔离级别下通过单行条件 UPDATE 逐个抢占候选，失败立即换候选，并让 PROXY_FAILED 状态先落库、自动上线失败不再把状态事件送入 DLT。

**Architecture:** 代理候选只做普通 SELECT，真正抢占使用 `UPDATE ip_proxy ... WHERE id=? AND status=IDLE` 的返回行数判断成功；冲突代理加入本次排除集合并继续下一候选，不依赖 READ_COMMITTED。PROXY_FAILED 使用三个互不传播回滚的事务：A 只提交账号状态；B 按事件中的 `accountId + proxyId` 精确把安卓旧代理释放回 IDLE，不置为 UNAVAILABLE；C 条件抢占新代理、更新快照并写上线 outbox。C 失败时 A/B 保持提交，PROXY_FAILED/OFFLINE 状态驱动后续持续补偿。

**Tech Stack:** Java 17、Spring Boot 3.3、MyBatis/MySQL、Spring Kafka、JUnit 5/Mockito。

**Execution note:** 用户明确要求当前 `1.0.1-snapshot` 工作区修改且不 commit；本计划不包含提交、部署或安卓协议自救改动。

---

### Task 1: 单行条件 UPDATE 代理抢占

**Files:**
- Modify: `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyOptimisticAllocator.java`
- Modify: `armada-api/src/main/java/com/armada/resource/mapper/IpProxyMapper.java`
- Modify: `armada-api/src/main/resources/mapper/resource/IpProxyMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/resource/mapper/IpProxyMapperDbTest.java`

- [x] 写失败测试：第一个候选条件 UPDATE 返回 0 时，同一账号继续尝试第二个候选且成功。
- [x] 运行 `IpProxyServiceImplTest`，确认测试因尚未使用单行条件 UPDATE 而失败。
- [x] 新增单行 `markUsingAndBind` Mapper，并让分配器依次尝试候选；失败 ID 加入排除集合，直到成功或无候选。
- [x] 运行代理 Service 聚焦测试转绿，并校验 Mapper XML。

### Task 2: 恢复默认事务隔离级别

**Files:**
- Modify: `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/account/service/impl/AccountOnlineCommandServiceImplTest.java`

- [x] 写/改失败测试：上线入口保留默认 `@Transactional`，但不声明 `READ_COMMITTED`。
- [x] 删除全部显式 `Isolation.READ_COMMITTED` 和对应 import，保留 `rollbackFor=Exception.class`。
- [x] 运行账号上线服务聚焦测试转绿。

### Task 3: PROXY_FAILED 的 A/B/C 独立事务

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountStateEventServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/account/state/ProxyFailedAutoReonlineSideEffect.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountStateMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountStateMapper.xml`
- Add/Modify: adjacent account state and scheduler tests/classes as required by the existing cross-tenant dispatch pattern.

- [x] 写失败测试：事务 A 提交 PROXY_FAILED/OFFLINE 后，事务 B 或 C 异常不能回滚状态，也不能从状态 sink 向 Kafka listener 抛出。
- [x] 写失败测试：事务 B 只按事件中的 `accountId + proxyId` 释放安卓旧代理回 IDLE，不把它置为 UNAVAILABLE，不误释放后续新绑定。
- [x] 写失败测试：事务 C 同一 PROXY_FAILED/OFFLINE 账号只允许一个恢复事务抢占；C 失败回滚后状态仍可被后续轮次重试。
- [x] 状态事件透传 `proxyId`；A 完成后顺序触发 B、C，但三者使用独立 Spring 事务且异常互不传播回滚。
- [x] C 立即失败只记录日志；周期补偿继续扫描 PROXY_FAILED/OFFLINE 账号并重试 C，直到上线或进入人工停止/终态。
- [x] 运行账号状态、自动上线和相关 Mapper 聚焦测试转绿。

### Task 4: 验证

**Files:**
- Modify: `.harness/changes/2026-07-24-batch-online-optimistic-proxy-allocation.md`

- [x] 运行受影响 Java 聚焦测试。
- [x] 运行 `xmllint --noout` 校验修改的 Mapper XML。
- [x] 运行 `mvn -DskipTests package` 和 `git diff --check`。
- [x] 复核没有修改 `armada-protocol`、没有 commit、没有部署。
