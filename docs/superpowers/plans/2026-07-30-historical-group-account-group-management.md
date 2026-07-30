# Historical Group Account-Group Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将历史群管理从固定操作账号维度改为账号组下的群维度，显式实时加载组内全部在线账号的群，并由后端自动选择在线群主/管理员执行操作。

**Architecture:** 初次列表通过账号组历史基线的 SQL 去重分页读取缓存；实时刷新同步遍历账号组在线账号、复用群关系持久化并按 group JID 聚合；详情和写操作通过专用选择器动态路由到当前账号组内在线管理员。Android 只补充协议响应字段，后端负责持久化、国家解析和群级聚合，Vue 页面只消费群级合同。

**Tech Stack:** Java 17、Spring Boot、MyBatis XML、Flyway、JUnit 5/Mockito/H2；Vue 3、TypeScript、Element Plus、Node test runner；Go 1.25、Gin。

---

## 执行约束

- 用户已确认在三个现有 checkout 内直接修改，不创建 worktree。
- 每个任务先写或修改测试并观察失败，再写最小实现，随后观察通过。
- 不碰 `armada/.claude/worktrees/**` 现有无关改动，也不复用脏 Android worktree。
- 使用 `apply_patch` 修改源文件；Go 文件修改后立即 `gofmt`。
- 共享工作区暂不自动提交；每个任务通过测试后记录检查点，最终由用户决定是否提交。
- 不连接远程环境、真库、SSH 或部署。

### Task 1: 暴露 Android 群公告模式合同

**Files:**

- Modify: `../whatsapp-server-feature-android-zhuan/internal/service/entity/entity.go`
- Test: `../whatsapp-server-feature-android-zhuan/api/service/group_test.go`（不存在则新建）
- Verify existing parser: `../whatsapp-server-feature-android-zhuan/internal/service/node/nodes/iq_group_result_test.go`

- [ ] **Step 1: 写失败的 JSON 合同测试**

构造含 `Creator`、`Creation`、`Announce=true` 的 `entity.GroupInfo`，通过 `GetAllGroupService` 的响应序列化边界或直接 JSON 序列化断言存在：

```json
{"creator":"86138...","creation":"1720000000","announce_only":true}
```

- [ ] **Step 2: 运行测试并确认只因 `announce_only` 缺失而失败**

Run: `go test ./api/service ./internal/service/node/nodes`

Expected: FAIL，断言 `announce_only` 不存在。

- [ ] **Step 3: 最小修改 JSON 标签**

把 `GroupInfo.Announce` 从进程内忽略改为 `json:"announce_only"`，保留中文字段说明；不修改已经正确解析 `creator/creation/announcement` 的节点逻辑。

- [ ] **Step 4: 格式化并通过定向测试**

Run: `gofmt -w internal/service/entity/entity.go api/service/group_test.go`

Run: `go test ./api/service ./internal/service/node/nodes`

Expected: PASS。

### Task 2: 扩展 Armada 协议群模型

**Files:**

- Modify: `armada-api/src/main/java/com/armada/platform/protocol/model/result/AccountParticipatingGroupResult.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapper.java`
- Modify as required: `armada-api/src/main/java/com/armada/platform/protocol/http/account/HttpAccountParticipatingGroupAdapter.java`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidAccountParticipatingGroupMapperTest.java`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeAccountParticipatingGroupAdapterTest.java`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/http/account/HttpAccountParticipatingGroupAdapterTest.java`

- [ ] **Step 1: 先扩展测试期望**

Android fixture 增加 `creator`、`creation`、`announce_only`，断言标准结果含规范化创建者号码、Unix 秒创建时间与仅管理员发言标识。Web fixture 断言同一模型在缺字段时返回 `null`，存在 metadata 时正确映射。

- [ ] **Step 2: 运行协议映射测试并观察编译或断言失败**

Run:

```bash
mvn -Dtest='AndroidAccountParticipatingGroupMapperTest,AndroidNativeAccountParticipatingGroupAdapterTest,HttpAccountParticipatingGroupAdapterTest' test
```

- [ ] **Step 3: 最小扩展标准模型和映射器**

在 `AccountParticipatingGroupResult.Group` 增加 `creatorPhone`、`createdAt` 等明确字段；保留现有构造兼容方式或一次性更新所有调用点，禁止通过含义不清的默认 `0/false` 表达未知。

- [ ] **Step 4: 通过协议映射测试**

运行 Step 2 命令，Expected: PASS。

### Task 3: 保存群创建时间并支持通用国家匹配

**Files:**

- Create: `armada-api/src/main/resources/db/migration/V085__historical_group_created_at.sql`（执行前重新确认 V085 未占用）
- Modify: `armada-api/src/main/java/com/armada/group/model/entity/GroupLinkPreview.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/GroupLinkPreviewMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupLinkPreviewMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/platform/country/mapper/CountryMapper.java`
- Modify: `armada-api/src/main/resources/mapper/platform/country/CountryMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/platform/country/service/CountryService.java`
- Modify: `armada-api/src/main/java/com/armada/platform/country/service/impl/CountryServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/group/mapper/GroupLinkPreviewMapperDbTest.java`
- Test: `armada-api/src/test/java/com/armada/platform/country/mapper/CountryMapperDbTest.java`
- Test: `armada-api/src/test/java/com/armada/platform/country/service/CountryServiceImplTest.java`
- Create or modify: migration SQL shape test under `armada-api/src/test/java/com/armada/group/`

- [ ] **Step 1: 写失败测试**

覆盖：预览 upsert 写入/读取 `groupCreatedAt`；空创建时间不覆盖已有值；国家查询读取全部启用国家而非只读 `is_ip_supported=1`；号码按最长前缀匹配。

- [ ] **Step 2: 运行定向测试并观察失败**

Run:

```bash
mvn -Dtest='GroupLinkPreviewMapperDbTest,CountryMapperDbTest,CountryServiceImplTest,*HistoricalGroup*Migration*Test' test
```

- [ ] **Step 3: 添加守卫迁移和最小 Mapper/Service 实现**

迁移用 `information_schema.columns` 守卫新增 `group_created_at BIGINT NULL`。Country 新增通用启用国家查询和纯号码解析方法，不改变现有 IP 支持国家方法语义。

- [ ] **Step 4: 运行 Step 2 测试并确认通过**

如真实 DB 测试依赖本地 Docker/数据库而不可用，保留 SQL 形状测试证据并在最终结果中明确未执行项，不伪造通过。

### Task 4: 实现账号组历史基线的 SQL 分页

**Files:**

- Create: `armada-api/src/main/java/com/armada/group/model/dto/HistoricalGroupQuery.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/HistoricalGroupPageRow.java`
- Create or modify: `armada-api/src/main/java/com/armada/group/mapper/AccountGroupBaselineMapper.java`
- Create or modify: matching MyBatis XML under `armada-api/src/main/resources/mapper/`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/model/vo/HistoricalGroupItemVO.java`
- Test: add Mapper SQL/H2 tests under `armada-api/src/test/java/com/armada/group/mapper/`

- [ ] **Step 1: 写失败的分页和租户范围测试**

测试同一群在五个账号基线中只占一行，多个群按稳定排序分页，其他账号组和租户数据不出现；页内关联账号去重返回。

- [ ] **Step 2: 运行 Mapper 测试确认缺少查询**

Run: `mvn -Dtest='*HistoricalGroup*Mapper*Test,AccountGroupMembershipMapperSqlTest' test`

- [ ] **Step 3: 最小实现 count/page/batch enrichment SQL**

数据库侧通过 JSON 表展开历史 JID 并 `GROUP BY` 去重，`LIMIT/OFFSET` 分页。只对当前页 JID 批量查询关联账号和关系聚合，禁止 Java 读取全量后分页。

- [ ] **Step 4: 通过 Mapper 测试并检查 XML**

Run: Step 2 command.

Run: `mvn -DskipTests compile`

Expected: PASS 且 MyBatis XML 可解析。

### Task 5: 改造历史群列表和同步刷新服务

**Files:**

- Modify: `armada-api/src/main/java/com/armada/group/controller/HistoricalGroupController.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/HistoricalGroupService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupServiceImpl.java`
- Prefer create: `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupAccountGroupRefreshService.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/dto/HistoricalGroupRefreshDTO.java`
- Modify/Create: `armada-api/src/main/java/com/armada/group/model/dto/HistoricalGroupQuery.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/dto/AccountGroupsReportedEvent.java`
- Modify existing membership snapshot service only as required to persist creator/creation.
- Test: `armada-api/src/test/java/com/armada/group/controller/HistoricalGroupControllerTest.java`
- Test: `armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java`
- Create: focused refresh service test under the same test package.

- [ ] **Step 1: 把 Controller 契约测试改成账号组和分页**

断言 list 接收 `accountGroupId/page/pageSize` 并返回 `PageResult`；refresh body 只含 `accountGroupId`；旧 `accountId` 不再是必需参数。

- [ ] **Step 2: 写 Service 失败测试**

覆盖初次列表不调用协议；刷新只请求在线正常账号；单账号失败隔离；普通成员群不进入刷新后列表；管理员离线保留但不可操作；角色和发言状态优先级；每群邀请链接只请求一次；空新值不覆盖预览旧值。

- [ ] **Step 3: 运行测试确认失败**

Run:

```bash
mvn -Dtest='HistoricalGroupControllerTest,HistoricalGroupServiceImplTest,HistoricalGroupAccountGroupRefreshServiceTest' test
```

- [ ] **Step 4: 实现群级读取与同步编排**

把多账号刷新编排拆出专用服务，控制 `HistoricalGroupServiceImpl` 体积。同步成功账号复用现有基线/关系持久化；邀请链接失败只记录字段级失败。列表组装国家、链接、创建时间、账号和可操作性。

- [ ] **Step 5: 通过定向测试**

运行 Step 3 command，Expected: PASS。

### Task 6: 为详情和成员操作增加管理员自动路由

**Files:**

- Create: `armada-api/src/main/java/com/armada/group/service/HistoricalGroupExecutionAccountSelector.java`
- Create: `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupExecutionAccountSelectorImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/controller/HistoricalGroupController.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/dto/HistoricalGroupParticipantActionDTO.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java`
- Create: selector Mapper/service tests.

- [ ] **Step 1: 写失败测试**

断言只从指定账号组选择在线在群管理员；群主优先；离线管理员和在线普通成员都不能选；详情、升管、降管、移除请求不含 account ID；协议调用使用选择出的账号。

- [ ] **Step 2: 运行并确认失败**

Run: `mvn -Dtest='HistoricalGroupControllerTest,HistoricalGroupServiceImplTest,*HistoricalGroupExecutionAccountSelector*Test' test`

- [ ] **Step 3: 最小实现专用选择器并替换固定账号参数**

每个详情/写请求重新选择，不改动其他模块允许普通在线成员的选择器。

- [ ] **Step 4: 通过定向测试**

运行 Step 2 command，Expected: PASS。

### Task 7: 改造拉群任务来源账号组合同

**Files:**

- Modify: `armada-api/src/main/java/com/armada/group/model/dto/HistoricalGroupPullCreateForm.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/dto/HistoricalGroupPullCreateDTO.java`
- Modify: `armada-api/src/main/java/com/armada/group/controller/HistoricalGroupPullExecutionController.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/HistoricalGroupPullExecutionService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/HistoricalGroupPullCreateValidator.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupPullExecutionServiceImpl.java`
- Modify mapper only if latest lookup needs group-id scope.
- Test: `armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupPullExecutionServiceImplTest.java`
- Add/update controller tests for create/latest contract.

- [ ] **Step 1: 写失败的创建与 latest 合同测试**

创建字段使用 `sourceAccountGroupId + groupJid + pullerAccountGroupId`；后台选择来源群管理员并把实际 operation account ID 存入执行行；latest 使用来源账号组和群 JID，不能命中其他组同群执行。

- [ ] **Step 2: 运行并观察失败**

Run: `mvn -Dtest='HistoricalGroupPullExecutionServiceImplTest,*HistoricalGroupPullExecutionController*Test' test`

- [ ] **Step 3: 实现合同和选择逻辑**

复用 Task 6 选择器。启动前如原执行账号已不可用，按现有状态机允许的边界重新选择；不改变拉手账号组 `pullerAccountGroupId` 语义。

- [ ] **Step 4: 通过定向测试**

运行 Step 2 command，Expected: PASS。

### Task 8: 改造前端 API 与账号组页面状态

**Files:**

- Modify: `../wheel-saas-pure-web/src/api/historical-group.ts`
- Modify: `../wheel-saas-pure-web/src/api/historical-group.test.ts`
- Modify: `../wheel-saas-pure-web/src/views/group/history/composables/useHistoricalGroupPage.ts`
- Modify: `../wheel-saas-pure-web/src/views/group/history/HistoricalGroupPage.test.ts`
- Modify: `../wheel-saas-pure-web/src/views/group/history/components/HistoricalGroupAccountSelector.vue`
- Modify: `../wheel-saas-pure-web/src/views/group/history/index.vue`

- [ ] **Step 1: 先改测试**

断言列表参数为账号组和分页，刷新 body 只有账号组；选择账号组立即请求列表；不再加载/筛选操作账号；按钮只在未选账号组时禁用；刷新成功后重载当前页。

- [ ] **Step 2: 运行前端定向测试并确认失败**

Run:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/historical-group.test.ts src/views/group/history/HistoricalGroupPage.test.ts
```

- [ ] **Step 3: 最小修改 API 类型、选择器和 composable**

删除 `selectedAccountId/accountOptions` 历史群页面状态，保留账号组选项；新增标准分页状态和页码变化处理。

- [ ] **Step 4: 运行 Step 2 并确认通过**

### Task 9: 实现新列、详情和执行参数

**Files:**

- Modify: `../wheel-saas-pure-web/src/views/group/history/components/HistoricalGroupTable.vue`
- Modify: `../wheel-saas-pure-web/src/views/group/history/components/HistoricalGroupDetailDrawer.vue`
- Modify: `../wheel-saas-pure-web/src/views/group/history/composables/useHistoricalGroupDetail.ts`
- Modify: `../wheel-saas-pure-web/src/views/group/history/components/HistoricalGroupPullPanel.vue`
- Modify: `../wheel-saas-pure-web/src/views/group/history/composables/useHistoricalGroupExecution.ts`
- Modify: `../wheel-saas-pure-web/src/views/group/history/HistoricalGroupDetail.test.ts`
- Modify: `../wheel-saas-pure-web/src/views/group/history/HistoricalGroupExecution.test.ts`
- Modify: `../wheel-saas-pure-web/src/views/group/history/HistoricalGroupPage.test.ts`

- [ ] **Step 1: 写失败的渲染和请求测试**

断言列顺序、一个账号加 tooltip 全部账号、链接、国家、创建时间、JID 最后一列；`operable=false` 时详情/操作置灰并显示原因；详情、成员操作、拉群创建/latest 均发送来源账号组而非操作账号。

- [ ] **Step 2: 运行三组历史群测试并确认失败**

Run:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/historical-group.test.ts src/views/group/history/HistoricalGroupPage.test.ts src/views/group/history/HistoricalGroupDetail.test.ts src/views/group/history/HistoricalGroupExecution.test.ts
```

- [ ] **Step 3: 最小实现视图和 composable**

使用 Element Plus `el-tooltip`，空值统一 `--`，时间复用项目公共格式化工具。保持严格 TypeScript，不使用 `any`。

- [ ] **Step 4: 运行 Step 2 并确认通过**

### Task 10: 文档更新与完整验证

**Files:**

- Modify: `.harness/changes/2026-07-30-historical-group-account-group-management.md`
- Modify: `../wheel-saas-pure-web/.harness/changes/historical-group-account-group-management/summary.md`
- Generated only: `.harness/wiki/数据模型.md`（按项目生成器更新，禁止手改）

- [ ] **Step 1: 生成/校验后端模型文档**

按 `armada/.harness` 指定脚本生成数据模型文档；确认 diff 只包含 `group_link_preview.group_created_at`。

- [ ] **Step 2: 后端验证**

Run targeted historical group suite, then:

```bash
mvn test
```

如全模块存在与本次无关的环境依赖，记录完整失败命令和首个根因，再运行覆盖本次改动的定向集合。

- [ ] **Step 3: 前端验证**

Run:

```bash
pnpm typecheck
pnpm exec eslint --max-warnings 0 <modified-ts-and-vue-files>
pnpm exec stylelint --max-warnings 0 <modified-vue-files>
pnpm build
```

- [ ] **Step 4: Android 完整验证**

Run:

```bash
go vet ./...
go build ./...
go test ./...
```

- [ ] **Step 5: 差异和回归检查**

三个仓库分别运行 `git diff --check` 和 `git status --short`，确认没有凭据、构建缓存、`.gocache`、用户 worktree 或无关文件进入改动。

- [ ] **Step 6: 更新变更记录**

勾选已完成任务，写入实际测试数量、命令、未执行项和部署状态。不得把未运行测试写成通过。
