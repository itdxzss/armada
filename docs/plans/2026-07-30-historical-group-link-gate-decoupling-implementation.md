# 历史群链接门禁解耦实施计划

> **For Codex:** REQUIRED SUB-SKILL: Use `executing-plans` task-by-task. Use `test-driven-development` for each behavior change and `verification-before-completion` before reporting success.

**Goal:** 让历史群详情、群邀请链接、提升管理员和拉群/营销分别按真实权限判断；修复 Android 裸 `GroupId` 兼容，并让管理员在无群链接时仍可把普通成员设为管理员。

**Architecture:** Armada 后端继续作为权限事实源。固定账号 metadata 决定当前角色和成员快照；只有管理员/群主查询邀请链接。`PROMOTE` 写前使用固定账号 metadata 重新校验，不依赖邀请链接。前端只开放提升管理员，并把链接错误限制在拉群/营销区域。

**Tech Stack:** Java 17、Spring Boot 3、JUnit 5、Mockito、Vue 3、TypeScript、Element Plus、Node test runner、pnpm/Vite。

**Design:** [2026-07-30-historical-group-link-gate-decoupling-design.md](./2026-07-30-historical-group-link-gate-decoupling-design.md)

## 执行前置

- Armada 当前工作区含用户已有的 worktree 链接和未跟踪文档；前端当前工作区也有未跟踪文档。不得提交、格式化或删除这些内容。
- 开始实现前先取得用户对隔离 worktree 的同意，并分别为 `armada`、`wheel-saas-pure-web` 创建隔离工作区。若用户明确要求在当前目录实现，则逐文件暂存并在每次提交前检查 staged diff。
- 不执行远程部署、SSH、数据库写入或协议服务变更。第一套环境部署必须单独确认。

---

### Task 1: 兼容 Android 裸 GroupId 并声明提升能力

**Files:**

- Modify: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeFixedAccountGroupMetadataAdapterTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeFixedAccountGroupMetadataAdapter.java`

**Step 1: 写失败测试**

- 请求 `120363001@g.us`，响应 `GroupId=120363001`；
- 断言查询成功，结果 `groupJid=120363001@g.us`；
- 断言 `participantMutationSupported()` 为 `true`；
- 保留真正不同 GroupId 的拒绝用例。

**Step 2: 运行测试确认 RED**

```bash
mvn -f armada-api/pom.xml -Dtest=AndroidNativeFixedAccountGroupMetadataAdapterTest test
```

预期：裸 GroupId 因精确字符串比较失败，能力断言因当前值为 `false` 失败。

**Step 3: 最小实现**

- 将请求和响应 GroupId 去空白后统一补全 `@g.us`；
- 比较归一化值，真正不同仍抛 `ANDROID_RESPONSE_UNRECOGNIZED`；
- 输出完整群 JID；
- 将 Android metadata 的 `participantMutationSupported` 设为 `true`。

**Step 4: 运行测试确认 GREEN**

重复 Step 2 命令，预期全部通过。

**Step 5: 提交**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeFixedAccountGroupMetadataAdapter.java armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidNativeFixedAccountGroupMetadataAdapterTest.java
git commit -m "fix: 兼容安卓历史群裸 GroupId"
```

---

### Task 2: 解耦详情、角色和邀请链接

**Files:**

- Modify: `armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupServiceImpl.java`

**Step 1: 写失败测试**

1. 普通成员 metadata 成功：返回详情和成员，`operationAllowed=false`，不调用 `invitePort`。
2. 管理员邀请链接异常：`operationAllowed=true`，普通成员级操作允许，`linkAvailable=false`，链接错误完整保留。
3. metadata 失败：不调用邀请链接端口，baseline 群名和 `FETCH_FAILED` 保留。
4. Android 管理员 metadata 能力为 `true` 后：`operationAllowed=true`。

**Step 2: 运行测试确认 RED**

```bash
mvn -f armada-api/pom.xml -Dtest=HistoricalGroupServiceImplTest test
```

预期：当前实现仍无条件读取邀请链接，并把链接失败并入成员操作门禁。

**Step 3: 最小实现**

调整 `getHistoricalGroupDetail` 为：

```text
metadata -> participants -> selfRole -> accountAdmin
                                  |
                                  +-- admin/owner -> read invite
                                  +-- member/unknown -> skip invite
```

- `operationAllowed = metadata成功 && participantMutationSupported && accountAdmin`；
- `operationDisabledReason` 只描述成员提升不可用原因，不复制链接错误；
- metadata 失败或普通成员时构造空 invite lookup；
- 管理员才读取群链接，链接失败不改变成员操作能力。

**Step 4: 运行测试确认 GREEN**

重复 Step 2 命令，预期全部通过。

**Step 5: 提交**

```bash
git add armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupServiceImpl.java armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java
git commit -m "fix: 解耦历史群详情与邀请链接"
```

---

### Task 3: 让 PROMOTE 使用固定账号 metadata 且不依赖链接

**Files:**

- Modify: `armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupServiceImpl.java`

**Step 1: 写失败测试**

- Android `PROMOTE` 调用 `readMetadataPort.getMetadata(account, groupJid)`；
- 不调用 `writeMetadataPort` 和 `invitePort`；
- 管理员提升普通成员时只调用一次 `participantPort`；
- 非管理员、目标已是管理员、目标已离群时不调用协议写入；
- 保留逐项顺序、不重试和错误完整返回；
- 降级/移除旧测试继续通过，证明旧接口没有被本次放开。

**Step 2: 运行测试确认 RED**

```bash
mvn -f armada-api/pom.xml -Dtest=HistoricalGroupServiceImplTest test
```

预期：当前 `PROMOTE` 仍调用旧 metadata 端口和邀请链接端口。

**Step 3: 最小实现**

- `PROMOTE` 写前走 `protocolPorts.readMetadata()`；
- `PROMOTE` 重新确认当前账号为管理员/群主，目标仍为普通成员；
- `PROMOTE` 不调用 `requireFreshInvite`；
- DEMOTE/REMOVE 保持旧 metadata 和 fresh invite 路径；
- 不删除 `HistoricalGroupProtocolPorts.writeMetadata`，不修改 Android/Zhuan client。

**Step 4: 运行测试确认 GREEN**

重复 Step 2 命令，预期全部通过。

**Step 5: 提交**

```bash
git add armada-api/src/main/java/com/armada/group/service/impl/HistoricalGroupServiceImpl.java armada-api/src/test/java/com/armada/group/service/impl/HistoricalGroupServiceImplTest.java
git commit -m "fix: 解除历史群提升管理员的链接依赖"
```

---

### Task 4: 映射 Android 401/not-authorized

**Files:**

- Modify: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupOperationErrorMapperTest.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupOperationErrorMapper.java`

**Step 1: 写失败测试**

增加消息包含 `not-authorized, Code: 401` 和原始协议码为 `401` 两种响应，均断言 `GROUP_PERMISSION_DENIED`，同时断言安全异常消息不包含协议原文。

**Step 2: 运行测试确认 RED**

```bash
mvn -f armada-api/pom.xml -Dtest=AndroidGroupOperationErrorMapperTest test
```

预期：当前两种响应均为 `UNKNOWN`。

**Step 3: 最小实现**

在现有离线、超时判断之后识别 raw code `401`、`not-authorized`、`not authorized`、`code: 401`，映射为 `GROUP_PERMISSION_DENIED`；保留安全消息格式。

**Step 4: 运行测试确认 GREEN**

重复 Step 2 命令，预期全部通过。

**Step 5: 提交**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidGroupOperationErrorMapper.java armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidGroupOperationErrorMapperTest.java
git commit -m "fix: 识别安卓群权限错误"
```

---

### Task 5: 前端成员管理只保留提升并解除链接门禁

**Workdir:** `../wheel-saas-pure-web`

**Files:**

- Modify: `src/views/group/history/HistoricalGroupDetail.test.ts`
- Modify: `src/views/group/history/composables/useHistoricalGroupDetail.ts`
- Modify: `src/views/group/history/components/HistoricalGroupMemberTable.vue`

**Step 1: 写失败测试**

- 管理员无链接但 `operationAllowed=true` 时成员管理仍启用；
- 只有普通成员进入提升目标集合；
- 操作只调用 `/api/historical-groups/participants/promote`，完成后只刷新一次详情；
- 成员表包含“设为管理员”，不包含“批量降级”“批量移除”；
- 普通成员 `operationAllowed=false` 时保持只读。

**Step 2: 运行测试确认 RED**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/group/history/HistoricalGroupDetail.test.ts
```

预期：当前成员管理依赖 `linkGateOpen`，且仍包含三种操作。

**Step 3: 最小实现**

- 将页面 action 类型收敛为 `"promote"`；
- 删除 composable 对 demote/remove API 的导入和请求映射；
- `memberManagementDisabled` 只依赖 `detail.operationAllowed`；
- 提升候选只允许非本人、非群主、非管理员且成员级允许的行；
- 成员表删除降级/移除 props、按钮和标签；
- 保留确认弹窗、逐项结果和写后单次刷新。

**Step 4: 运行测试确认 GREEN**

重复 Step 2 命令，预期全部通过。

**Step 5: 提交**

```bash
git add src/views/group/history/HistoricalGroupDetail.test.ts src/views/group/history/composables/useHistoricalGroupDetail.ts src/views/group/history/components/HistoricalGroupMemberTable.vue
git commit -m "fix: 历史群成员管理仅开放提升"
```

---

### Task 6: 把链接错误限制在管理员拉群/营销区域

**Workdir:** `../wheel-saas-pure-web`

**Files:**

- Modify: `src/views/group/history/HistoricalGroupDetail.test.ts`
- Modify: `src/views/group/history/HistoricalGroupExecution.test.ts`
- Modify: `src/views/group/history/components/HistoricalGroupDetailDrawer.vue`
- Modify: `src/views/group/history/components/HistoricalGroupPullPanel.vue`
- Modify: `src/views/group/history/composables/useHistoricalGroupDetail.ts`
- Modify: `src/views/group/history/composables/useHistoricalGroupExecution.ts`

**Step 1: 写失败测试**

- 详情抽屉不再包含“群链接硬门禁未通过”；
- 管理员链接失败显示“群链接获取失败，仅影响拉群/营销”，但提升仍可用；
- 链接原因不重复组合 `operationDisabledReason`；
- 普通成员不渲染拉群面板；
- 即使收到异常的“普通成员 + 可用链接”详情，execution composable 也不加载选项；
- 管理员/群主有链接时正常打开，管理员无链接时面板局部禁用。

**Step 2: 运行测试确认 RED**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/group/history/HistoricalGroupDetail.test.ts src/views/group/history/HistoricalGroupExecution.test.ts
```

预期：当前抽屉有全局硬门禁，普通成员有链接即可打开拉群执行。

**Step 3: 最小实现**

- 删除详情抽屉顶部全局硬门禁；
- 仅 `selfRole=OWNER/ADMIN` 渲染拉群面板；
- execution 可执行条件改为“管理员/群主 + 有效链接”；
- 普通成员原因固定为“当前账号不是管理员，仅支持查看群详情”；
- 管理员链接原因只使用链接相关字段；
- 拉群面板标题替换为“群链接获取失败，仅影响拉群/营销”。

**Step 4: 运行测试确认 GREEN**

重复 Step 2 命令，预期全部通过。

**Step 5: 提交**

```bash
git add src/views/group/history/HistoricalGroupDetail.test.ts src/views/group/history/HistoricalGroupExecution.test.ts src/views/group/history/components/HistoricalGroupDetailDrawer.vue src/views/group/history/components/HistoricalGroupPullPanel.vue src/views/group/history/composables/useHistoricalGroupDetail.ts src/views/group/history/composables/useHistoricalGroupExecution.ts
git commit -m "fix: 限定历史群链接门禁作用域"
```

---

### Task 7: 后端回归验证

**Workdir:** `armada`

**Step 1: 聚焦测试**

```bash
mvn -f armada-api/pom.xml -Dtest=AndroidNativeFixedAccountGroupMetadataAdapterTest,AndroidGroupOperationErrorMapperTest,HistoricalGroupServiceImplTest,HistoricalGroupPullExecutionServiceImplTest test
```

**Step 2: 配置与控制器回归**

```bash
mvn -f armada-api/pom.xml -Dtest=ProtocolConfigurationTest,HistoricalGroupControllerTest test
```

**Step 3: 模块全量测试和构建**

```bash
mvn -f armada-api/pom.xml test
mvn -f armada-api/pom.xml -DskipTests package
```

若 Testcontainers 因 Docker 不可用失败，记录明确原因；不得把环境失败描述为代码通过。

**Step 4: 差异检查**

```bash
git diff --check
git status --short
```

---

### Task 8: 前端回归验证

**Workdir:** `../wheel-saas-pure-web`

**Step 1: 历史群聚焦测试**

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/historical-group.test.ts src/views/group/history/HistoricalGroupPage.test.ts src/views/group/history/HistoricalGroupDetail.test.ts src/views/group/history/HistoricalGroupExecution.test.ts
```

**Step 2: 类型、lint、构建**

```bash
pnpm typecheck
pnpm lint
pnpm build
```

`pnpm lint` 会格式化文件，只能在隔离 worktree 中执行；运行后检查没有任务外改动。

**Step 3: 差异检查**

```bash
git diff --check
git status --short
```

---

### Task 9: 最终审查与交付

**Step 1: 按设计逐项核对**

- 普通成员只读且不请求群链接；
- 管理员链接失败仍可提升普通成员；
- `PROMOTE` 写前重新校验且不查链接；
- 拉群/营销需要管理员角色和有效链接；
- Android 裸 GroupId、401 权限错误均覆盖；
- 页面无全局硬门禁，无降级/移除入口。

**Step 2: 检查两个仓库提交范围**

```bash
git log --oneline --decorate -n 10
git status --short
```

分别确认每个提交只包含对应任务文件，不包含用户既有改动。

**Step 3: 代码审查**

使用 `requesting-code-review` 技能核对设计、测试和仓库规则；修复发现的问题后重新运行 Task 7、Task 8。

**Step 4: 交付说明**

报告实际修改、精确测试结果、未修改数据库和转协议服务、尚未部署第一套环境；部署前再次确认环境和发布范围。
