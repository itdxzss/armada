# 营销任务按账号当前全部群发送实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 动态营销任务每轮向发送账号当前 membership 中全部未软删、JID 非空的群生成发送尝试，不再受 baseline、加入时间、账号状态或群状态过滤。

**Architecture:** `account.groups_reported` 每次把协议回报的当前群全量交给既有快照服务；baseline 只保留为历史快照。动态目标 SQL 只以活跃账号、协议账号 ID 和未软删 membership 为准，`group_link`/preview 仅补充展示快照；创建链路保留兼容 DTO 字段但忽略并写空，前端移除该字段。

**Tech Stack:** Java 17、Spring Boot、MyBatis/MySQL、JUnit 5/Mockito、Vue 3、TypeScript、Element Plus、Node test runner。

---

### Task 1: 当前群同步保存全量 membership

**Files:**
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImplTest.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImpl.java`

- [x] 修改待拍与已拍账号测试，断言 baseline 捕获后和已拍回报时都把全部有效群传给 `replaceVisibleGroups`。
- [x] 运行 `mvn -Dtest=AccountGroupMembershipReportServiceImplTest test`，确认旧实现因清空或 baseline 差集而失败。
- [x] 删除 baseline 差集解析路径；待拍账号捕获 baseline 后继续用本次全量回报刷新快照，其他账号直接刷新全量快照。
- [x] 重跑测试并确认通过。

### Task 2: 动态目标查询只读取当前 membership

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingRoundMapperDbTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeService.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeServiceTest.java`

- [x] 先把 SQL 形状测试改为要求单参数查询、membership JID 非空、展示表 LEFT JOIN，并禁止 baseline、joined_at、账号状态、群状态和健康过滤。
- [ ] 把真库测试改为覆盖旧加入时间、baseline、异常群/账号状态仍选中，以及软删 membership 不选中。
- [ ] 运行 SQL 形状测试，确认旧 SQL 失败；DbTest 仅在已确认本地测试库配置可用时执行。
- [x] 将 Mapper 收敛为 `selectDynamicTargetGroups(Long accountId)` 并实现最小 SQL；同步修改账号树调用和单测。
- [x] 运行 XML 解析、SQL 形状测试和相关单测，确认通过。

### Task 3: Worker 每轮展开全部当前群

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`

- [x] 修改 Worker 测试，要求调用不带发送时间的动态查询，并为返回的全部群生成 attempt 与发送命令。
- [ ] 运行目标测试，确认旧调用签名失败。
- [x] 修改 Worker 调用和注释，保留空 JID 数据质量保护。
- [x] 重跑 Worker 测试并确认通过。

### Task 4: 后端创建链路忽略账号群组发送时间

**Files:**
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/impl/MarketingTaskServiceImplTest.java`（若存在对应单测）
- Modify: `armada-api/src/main/java/com/armada/marketing/model/dto/CreateMarketingTaskDTO.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`

- [ ] 新增/修改测试：旧客户端即使传 `accountGroupSendAt` 也不触发 72 小时校验，新建任务该列写 NULL。
- [ ] 运行目标测试，确认旧实现失败。
- [x] 删除 72 小时常量、校验与默认值计算；DTO 可选字段只保留契约兼容说明；建任务固定写 NULL。
- [ ] 重跑创建相关测试并确认通过。

### Task 5: 前端移除账号群组发送时间

**Files:**
- Modify: `src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts`
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.test.ts`
- Modify: `src/views/task/group-marketing/components/GroupMarketingDetailDrawer.test.ts`（若无则新增最小源码契约测试）
- Modify: `src/views/task/group-marketing/components/GroupMarketingCreateDrawer.vue`
- Modify: `src/views/task/group-marketing/components/GroupMarketingDetailDrawer.vue`
- Modify: `src/views/task/group-marketing/composables/useGroupMarketingTaskPage.ts`
- Modify: `src/api/marketing-task.ts`

- [x] 先修改测试，断言创建抽屉和详情不含该字段、生命周期只校验开始/结束、请求 payload 不含 `accountGroupSendAt`。
- [x] 运行相关前端测试，确认旧实现失败。
- [x] 删除表单字段、日期禁用函数、72 小时校验、创建请求字段和详情展示；响应类型可暂留历史字段兼容读取。
- [x] 重跑前端目标测试、typecheck 和 build。

### Task 6: 回归验证与变更记录

**Files:**
- Create: `.harness/changes/2026-07-16-marketing-current-group-send.md`

- [ ] 运行后端相关单测、编译/XML 校验；具备确认过的真库环境时运行 `MarketingRoundMapperDbTest` 和创建 DbTest。
- [ ] 运行前端相关测试、typecheck 和 build。
- [ ] 检查两个仓库 diff，只保留本需求相关最小修改，不覆盖既有在途工作。
- [ ] 更新 change 记录中的真实命令、输出、未执行项和风险；不部署。
