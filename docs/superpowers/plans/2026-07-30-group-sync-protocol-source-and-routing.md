# Group Sync Protocol Source And Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 群组列表以 `group_link` 上的同步来源位掩码展示 JSON号/六段号，并让群详情根据当前在线在群账号路由到正确协议。

**Architecture:** 用 `group_link.sync_protocol_mask` 保存历史观察来源，账号群同步写入时原子按位或，列表直接读取该列。删除分页后的实时协议聚合；群详情把执行账号的 `ProtocolAccountRef` 交给已有 `FixedAccountGroupMetadataPort` 路由 Web/Android。

**Tech Stack:** Java 17、Spring Boot、MyBatis、Flyway、H2 MySQL mode、Vue 3、TypeScript、Element Plus、Node test runner

---

### Task 1: 同步来源字段与迁移

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V084__group_sync_protocol_mask.sql`
- Create: `armada-api/src/test/java/com/armada/group/GroupSyncProtocolMigrationSqlTest.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/entity/GroupLink.java`
- Modify: `armada-api/src/test/java/com/armada/testsupport/MysqlModeMapperInMemoryTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/AccountGroupSyncMySqlConcurrencyTest.java`

- [x] **Step 1: 写失败的迁移合同测试**

测试读取 `V084__group_sync_protocol_mask.sql`，断言包含非空默认列和一次性存量回填：

```java
assertThat(sql)
        .contains("ADD COLUMN sync_protocol_mask TINYINT NOT NULL DEFAULT 0")
        .contains("account_group_membership")
        .contains("protocol_id")
        .contains("BIT_OR");
```

- [x] **Step 2: 运行测试确认迁移文件缺失**

Run: `cd armada-api && mvn -Dtest=GroupSyncProtocolMigrationSqlTest test`

Expected: FAIL，提示 `V084__group_sync_protocol_mask.sql` 不存在。

- [x] **Step 3: 新增字段、存量回填与实体属性**

迁移新增 `sync_protocol_mask`，按租户和群 ID 聚合现有关系：Android 贡献位 `2`，其余协议按兼容规则贡献位 `1`，使用 `BIT_OR` 合并。`GroupLink` 新增 `Integer syncProtocolMask` getter/setter；H2/MySQL 测试表结构同步加入默认列。

- [x] **Step 4: 运行迁移合同及 Flyway 合同测试**

Run: `cd armada-api && mvn -Dtest=GroupSyncProtocolMigrationSqlTest,FlywayMigrationVersionContractTest,FlywayMigrationSqlContractTest test`

Expected: PASS。

### Task 2: 账号同步原子维护来源位

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupLinkRegistryService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/AccountGroupMembershipSnapshotService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkRegistryServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipStatusServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/GroupLinkMapper.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml`
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Modify: relevant registry/snapshot/status/report and mapper tests under `armada-api/src/test/java/com/armada/group/`

- [x] **Step 1: 写失败的 Registry 与 Mapper 测试**

Registry 测试传入 `ProtocolBackend.ANDROID`，断言新行 `syncProtocolMask=2`；匹配已有群时断言 `touchGroupLinkFromAccountSync(..., 2, now)`。H2 Mapper 测试先用 Web 位 `1` upsert，再用 Android 位 `2` upsert相同 URL，断言最终值为 `3`。

- [x] **Step 2: 运行测试确认接口和 SQL 尚不支持来源位**

Run: `cd armada-api && mvn -Dtest=GroupLinkRegistryServiceImplUnitTest,MysqlModeMapperInMemoryTest#accountObservedUpsertAccumulatesSyncProtocolMask test`

Expected: FAIL，提示方法签名或 `syncProtocolMask` 属性缺失。

- [x] **Step 3: 贯通来源协议参数并原子合并**

接口改为显式接收 `ProtocolBackend observedBackend`：

```java
Long registerAccountObservedGroup(
        String groupJid,
        String groupName,
        ProtocolBackend observedBackend,
        long now);
```

Registry 将 Android 映射为 `2`，其他映射为 `1`。`upsertAccountObservedGroup` 插入列并在冲突分支执行兼容 MySQL/H2 的原子合并：

```sql
sync_protocol_mask = CASE
  WHEN sync_protocol_mask = 0 THEN #{row.syncProtocolMask}
  WHEN sync_protocol_mask = #{row.syncProtocolMask} THEN sync_protocol_mask
  ELSE 3
END
```

已有群 JID 路径的 `touchGroupLinkFromAccountSync` 同样按位或。Report 从 `baselineRow.protocolId` 解析 backend 并传给 Snapshot；精确关系事件从已读取账号行解析 backend 并传给 Registry。

- [x] **Step 4: 更新受方法签名影响的测试并运行同步测试集**

Run: `cd armada-api && mvn -Dtest=GroupLinkRegistryServiceImplUnitTest,AccountGroupMembershipSnapshotServiceImplTest,AccountGroupMembershipReportServiceImplTest,AccountGroupMembershipStatusServiceImplTest,MysqlModeMapperInMemoryTest,GroupLinkMapperSqlShapeTest test`

Expected: PASS，且 SQL shape 测试确认按位或存在。

### Task 3: 群组列表直接读取来源位

**Files:**
- Delete: `armada-api/src/main/java/com/armada/group/model/vo/GroupAvailableBackendRow.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/GroupLinkMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVoRow.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVO.java`
- Modify: `armada-api/src/main/java/com/armada/group/converter/GroupConverter.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java`
- Modify: backend list/converter/service tests
- Modify: `wheel-saas-pure-web/src/api/group.ts`
- Modify: `wheel-saas-pure-web/src/views/group/list/constants.ts`
- Modify: `wheel-saas-pure-web/src/views/group/list/components/GroupListTable.vue`
- Modify: `wheel-saas-pure-web/src/views/group/list/components/GroupListTable.test.ts`

- [x] **Step 1: 写失败的后端列表测试**

Converter 测试断言 `GroupLinkVoRow.syncProtocolMask=3` 原样进入 `GroupLinkVO`。Service 测试断言列表只执行 count/page 查询，不存在 `selectAvailableBackends` 调用。

- [x] **Step 2: 写失败的前端展示测试**

契约测试断言 API 字段为 `syncProtocolMask?: number`，表格对位 `1` 显示 JSON号、位 `2` 显示六段号，`0` 显示 `-`。

- [x] **Step 3: 运行红灯测试**

Run: `cd armada-api && mvn -Dtest=GroupLinkServiceImplTest,GroupConverterTest test`

Run: `node --import ./src/api/__tests__/node-test-alias.mjs --test --experimental-strip-types src/views/group/list/components/GroupListTable.test.ts`

Expected: FAIL，仍存在 `availableBackends` 契约或缺少 `syncProtocolMask`。

- [x] **Step 4: 删除运行时聚合并改为直接字段**

删除 `selectAvailableBackends` Mapper、XML、投影类和 Service enrichment。分页 SELECT 新增 `g.sync_protocol_mask AS syncProtocolMask`，VO 返回数字字段。前端使用按位判断直接显示固定两个标签，不构造额外协议数组。

- [x] **Step 5: 运行列表测试**

Run: `cd armada-api && mvn -Dtest=GroupLinkServiceImplTest,GroupConverterTest,GroupLinkMapperSqlShapeTest test`

Run: `node --import ./src/api/__tests__/node-test-alias.mjs --test --experimental-strip-types src/api/group.test.ts src/views/group/list/components/GroupListTable.test.ts`

Expected: PASS。

### Task 4: 群详情按执行账号协议路由

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupDetailProtocolPorts.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/GroupDetailServiceImplTest.java`

- [x] **Step 1: 写失败的 Android 详情路由测试**

Mock `FixedAccountGroupMetadataPort`，让 selector 返回：

```java
new GroupExecutionAccount(7L, "ANDROID", "android-handle", "8613800000000")
```

调用 `detail(10L)` 后验证：

```java
verify(metadataPort).getMetadata(account.protocolRef(), "120363detail@g.us");
```

- [x] **Step 2: 运行测试确认旧端口只接受字符串账号句柄**

Run: `cd armada-api && mvn -Dtest=GroupDetailServiceImplTest test`

Expected: FAIL，`GroupDetailProtocolPorts` 仍要求 `GroupMetadataPort` 或调用参数不匹配。

- [x] **Step 3: 切换为已有路由端口**

`GroupDetailProtocolPorts.metadata` 改为 `FixedAccountGroupMetadataPort`。`GroupDetailServiceImpl` 所有 metadata 读取和写后回读统一传 `account.protocolRef()`，使详情、成员、权限及回读使用同一个已选账号和正确 backend。

- [x] **Step 4: 运行详情与协议路由测试**

Run: `cd armada-api && mvn -Dtest=GroupDetailServiceImplTest,RoutingFixedAccountGroupMetadataPortTest,AndroidNativeFixedAccountGroupMetadataAdapterTest,HttpGroupMetadataAdapterTest test`

Expected: PASS。

### Task 5: 综合验证

**Files:**
- Verify only

- [x] **Step 1: 校验 XML、迁移和工作区差异**

Run: `xmllint --noout armada-api/src/main/resources/mapper/group/GroupLinkMapper.xml armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`

Run: `git diff --check && git status --short`

Expected: XML 合法、无空白错误，只保留本任务与用户原有在途文件。

- [x] **Step 2: 运行后端相关测试**

Run: `cd armada-api && mvn -Dtest=GroupSyncProtocolMigrationSqlTest,FlywayMigrationVersionContractTest,FlywayMigrationSqlContractTest,GroupLinkRegistryServiceImplUnitTest,AccountGroupMembershipSnapshotServiceImplTest,AccountGroupMembershipReportServiceImplTest,AccountGroupMembershipStatusServiceImplTest,MysqlModeMapperInMemoryTest,GroupLinkMapperSqlShapeTest,GroupLinkServiceImplTest,GroupConverterTest,GroupDetailServiceImplTest,RoutingFixedAccountGroupMetadataPortTest,AndroidNativeFixedAccountGroupMetadataAdapterTest,HttpGroupMetadataAdapterTest test`

Expected: BUILD SUCCESS。

- [x] **Step 3: 运行前端质量检查和构建**

Run: `pnpm exec prettier --check src/api/group.ts src/views/group/list/constants.ts src/views/group/list/components/GroupListTable.vue src/views/group/list/components/GroupListTable.test.ts`

Run: `pnpm exec eslint --max-warnings 0 src/api/group.ts src/views/group/list/constants.ts src/views/group/list/components/GroupListTable.vue src/views/group/list/components/GroupListTable.test.ts`

Run: `node --import ./src/api/__tests__/node-test-alias.mjs --test --experimental-strip-types src/api/group.test.ts src/views/group/list/components/GroupListTable.test.ts`

Run: `pnpm typecheck`

Run: `pnpm build`

Expected: 全部 exit 0。

### 执行约束

- 用户已选择当前会话内联执行。
- 所有改动只保留在本地工作区。
- 不执行 `git commit`、push、部署、SSH 或远程数据库操作。
