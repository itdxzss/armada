# Batch Online Optimistic Proxy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完整落地已确认的批量上线防重、代理乐观抢占、快照批量更新、批次日志和前端超时/冷却行为。

**Architecture:** 用户手动上线先以 `account_state.login_state` 条件更新预占，再在同一事务内按国家策略分组查询 IDLE 代理、每 100 条执行 `CASE WHEN` CAS、核验真实绑定并有限重试。前端保留 10 秒超时，但把上线 timeout 解释为结果未知并维持 30 秒页面冷却。

**Tech Stack:** Java 17、Spring Boot 3.3、MyBatis/MySQL、JUnit 5/Mockito、Vue 3、TypeScript、Element Plus、Node test。

**Execution note:** 用户明确要求当前工作区修改且不 commit；下面所有提交步骤均省略，代码留给用户本地复核。

---

### Task 1: 代理候选查询与乐观批量抢占

**Files:**
- Modify: `armada-api/src/main/java/com/armada/resource/service/impl/IpProxyServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/resource/mapper/IpProxyMapper.java`
- Create: `armada-api/src/main/java/com/armada/resource/mapper/IpProxyCandidateQuery.java`
- Modify: `armada-api/src/main/resources/mapper/resource/IpProxyMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/resource/service/IpProxyServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/resource/mapper/IpProxyMapperDbTest.java`

- [x] 写失败 Service 测试：同策略账号只做批量候选查询，CAS 目标最多 100 条并按代理 ID 排序，冲突只重试未分配账号。
- [x] 运行 `IpProxyServiceImplTest`，确认因新 Mapper API 缺失或旧 `FOR UPDATE` 路径仍被调用而失败。
- [x] 新增 `IpProxyCandidateQuery`，用普通 SELECT 替换 `selectOneIdleByRegionPriorityForUpdate`，删除批量递增 `NOT IN`。
- [x] 复用并收紧 `markUsingAndBindBatch`：`CASE id WHEN ...`、`status=IDLE`、`ORDER BY id`；UPDATE 后用已有批量查询核验实际账号映射。
- [x] 服务使用 `READ_COMMITTED`、稳定策略分组、100 条 CAS、最多三轮；删除旧逐账号锁定路径。
- [x] 运行 Service 测试转绿；补真库 DbTest，但只在目标库确认后执行。

### Task 2: 状态防重、快照批量更新与批次日志

**Files:**
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountOnlineCommandServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/account/service/impl/AccountBatchLifecycleServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountStateMapper.java`
- Modify: `armada-api/src/main/java/com/armada/account/mapper/AccountMapper.java`
- Modify: `armada-api/src/main/resources/mapper/account/AccountStateMapper.xml`
- Modify: `armada-api/src/main/resources/mapper/account/AccountMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/account/model/enums/AccountBatchSkipReason.java`
- Modify: `armada-api/src/main/java/com/armada/account/model/vo/AccountBatchTargetRow.java`
- Modify: `armada-api/src/main/java/com/armada/account/model/vo/AccountBatchPreviewRow.java`
- Modify: matching account service and Mapper tests

- [x] 写失败测试：预估/执行跳过 `PENDING_ONLINE`、`ONLINE`；单账号重复上线不分配代理；并发条件更新不足不写 outbox。
- [x] 把用户手动上线的条件预占、代理分配、快照和 outbox 放进同一事务，系统恢复来源继续走原恢复规则。
- [x] 新增每 100 条 `UPDATE JOIN` 快照 Mapper，删除批量循环中的逐账号快照 UPDATE 和逐账号准备 INFO。
- [x] 保留批次汇总日志并加入代理分配/快照/outbox 数量与耗时。
- [x] 运行账号聚焦测试转绿；Mapper SQL 等待确认环境后跑 DbTest。

### Task 3: Web timeout 语义与 30 秒批量冷却

**Files:**
- Modify: `wheel-saas-pure-web/src/utils/api-error.ts`
- Modify: `wheel-saas-pure-web/src/views/account/index/composables/useAccountListPage.ts`
- Modify: `wheel-saas-pure-web/src/views/account/index/components/AccountListTable.vue`
- Modify: `wheel-saas-pure-web/src/views/account/index/index.vue`
- Modify/Create: adjacent Node tests

- [x] 写失败测试：仅 timeout 被识别为结果未知；确认后开始冷却；timeout warning 并刷新；离线不受冷却影响。
- [x] 新增严格 timeout 判定，批量上线 timeout 显示 `正在上线，请稍后`。
- [x] 增加当前页面 30 秒批量上线倒计时，菜单显示 `批量登录(29s)`，卸载清理 timer。
- [x] 运行前端聚焦测试、typecheck 和 build。

### Task 4: 统一验证与变更记录

**Files:**
- Modify: `.harness/changes/2026-07-24-batch-online-optimistic-proxy-allocation.md`

- [x] 运行所有受影响 Java 聚焦测试和 `mvn -DskipTests package`。
- [x] 运行四个受影响 Mapper XML 的 `xmllint --noout`。
- [x] 运行前端全量测试、`pnpm typecheck`、定向 ESLint、`pnpm build`。
- [x] `git diff --check` 并逐条核对设计；真库 DbTest 未获目标环境确认，保留为未执行。
