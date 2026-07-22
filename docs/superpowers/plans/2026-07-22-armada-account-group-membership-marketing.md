# Armada Account Group Membership Marketing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Armada 中持久化五种账号群关系状态，消费 Android 精确事件，营销运行时按本地状态发送或跳过，并在详情 API 中完整展示关系与执行统计。

**Architecture:** `account_group_membership` 继续作为账号群关系唯一当前事实源，新增状态、来源和事实时间；group 域通过 Service 向 marketing 域提供批量状态查询，Kafka consumer 通过 sink adapter 进入 group 域。营销 Worker 在 attempt/outbox 边界前一次批量读取状态，不可发送关系只写 `SKIPPED` attempt。详情 SQL 以固定 target 与 attempt 群集合的并集为维度，分别选取当前关系、最后协议结果和最后已结束执行。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis、MySQL 8、Flyway、Kafka、JUnit 5、AssertJ、Mockito、真库 DbTest

---

## 0. 执行边界与文件结构

本计划只修改 `/Users/daishuaishuai/IdeaProjects/armada`。执行前必须读取仓库 `AGENTS.md` 和本计划，
使用 `superpowers:using-git-worktrees` 建隔离 worktree，并再次确认 Flyway `V060` 未被最新分支占用。

新增生产文件：

- `group/model/enums/AccountGroupMembershipStatus.java`：五种状态、码、发送能力和展示文案。
- `group/model/dto/AccountGroupMembershipChangedEvent.java`：group 域精确事件 DTO。
- `group/model/vo/AccountGroupMembershipLookup.java`：批量查询 key。
- `group/model/vo/AccountGroupMembershipStatusRow.java`：Mapper 查询行。
- `group/model/vo/AccountGroupMembershipStatusSnapshot.java`：跨域 Service 返回值。
- `group/service/AccountGroupMembershipStatusService.java` 与 impl：精确状态写入和批量状态读取。
- `group/service/impl/AccountGroupMembershipChangedSinkAdapter.java`：platform Kafka 到 group 域 adapter。
- `platform/kafka/consumer/account/ProtocolAccountGroupMembershipChangedEvent.java`：wire event。
- `platform/kafka/consumer/account/ProtocolAccountGroupMembershipChangedSink.java`：consumer 下游口。
- `marketing/service/impl/MarketingMembershipSendPolicy.java`：关系状态到 send/skip 决策。
- `db/migration/V060__account_group_membership_status.sql`：状态列、数据迁移和索引。

新增测试文件：

- `group/AccountGroupMembershipStatusMigrationDbTest.java`
- `group/service/AccountGroupMembershipStatusServiceDbTest.java`
- `marketing/service/impl/MarketingMembershipSendPolicyTest.java`

现有 `.harness/changes/2026-07-21-account-group-membership-status-marketing.md` 保留设计记录；实现阶段另建：

- `.harness/changes/account-group-membership-marketing/summary.md`
- `.harness/changes/account-group-membership-marketing/db-migrations.sql`
- `.harness/changes/account-group-membership-marketing/rollback.sql`

## Task 1: 迁移账号群关系状态模型

**Files:**

- Create: `armada-api/src/main/resources/db/migration/V060__account_group_membership_status.sql`
- Create: `armada-api/src/main/java/com/armada/group/model/enums/AccountGroupMembershipStatus.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/entity/AccountGroupMembership.java`
- Create: `armada-api/src/test/java/com/armada/group/AccountGroupMembershipStatusMigrationDbTest.java`

- [ ] **Step 1: 确认 Flyway 版本未碰撞**

```bash
test ! -e armada-api/src/main/resources/db/migration/V060__account_group_membership_status.sql
ls armada-api/src/main/resources/db/migration | sort -V | tail -n 5
```

Expected: 第一条退出码 0，当前最高版本仍为 V059。若最新分支已有 V060，停止本任务并把本计划所有
V060 路径统一改成下一个空闲版本，禁止提交重复版本。

- [ ] **Step 2: 先写迁移 DbTest**

```java
class AccountGroupMembershipStatusMigrationDbTest extends DbTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void membershipTableHasCurrentStatusColumnsAndIndex() {
        assertThat(columnType("membership_status")).isEqualTo("tinyint");
        assertThat(nullable("membership_status")).isFalse();
        assertThat(columnType("status_source")).isEqualTo("varchar");
        assertThat(columnType("status_updated_at")).isEqualTo("bigint");
        assertThat(nullable("last_seen_at")).isTrue();
        assertThat(indexExists("idx_account_group_membership_status")).isTrue();
    }
}
```

再建立一个一次性 V059 测试 schema，在执行 V060 前插入四组 fixture：当前 active、仅有两条 deleted 历史、
已有 active 加旧历史、另一租户同 account/group。执行 V060 后逐行断言 active 为 `IN_GROUP`、仅历史组合
只有最新行恢复且为 `NOT_IN_GROUP`、旧历史仍 deleted、两租户互不影响，并核对 `status_updated_at` 等于
原始 `deleted_at/updated_at`。该 schema 名必须是专用测试库且需用户确认；禁止在共享开发库做 Flyway clean。

- [ ] **Step 3: 运行并确认先失败**

先确认 `armada-api/.env` 指向允许使用的测试数据库，远程或共享库需用户确认；然后运行：

```bash
cd armada-api
./dbtest.sh 'AccountGroupMembershipStatusMigrationDbTest'
```

Expected: FAIL，新列和索引不存在。不得用 `mvn test` 代替真库失败证据。

- [ ] **Step 4: 定义状态枚举**

```java
public enum AccountGroupMembershipStatus {
    IN_GROUP(1, true, "在群"),
    UNCONFIRMED(2, true, "未确认"),
    KICKED_OUT(3, false, "被踢出"),
    LEFT(4, false, "已主动退出"),
    NOT_IN_GROUP(5, false, "已不在群");

    private final int code;
    private final boolean sendable;
    private final String text;

    AccountGroupMembershipStatus(int code, boolean sendable, String text) {
        this.code = code;
        this.sendable = sendable;
        this.text = text;
    }

    public int code() { return code; }
    public boolean sendable() { return sendable; }
    public String apiValue() { return name(); }
    public String text() { return text; }
    public static AccountGroupMembershipStatus fromCode(Integer code) {
        if (code == null) return UNCONFIRMED;
        return Arrays.stream(values())
                .filter(status -> status.code == code)
                .findFirst()
                .orElse(UNCONFIRMED);
    }
}
```

文件需导入 `java.util.Arrays` 并为枚举添加类级 Javadoc。未知/null 只在读兼容时回退
`UNCONFIRMED`，写路径禁止持久化未知码。

- [ ] **Step 5: 编写 V060 数据迁移**

迁移按以下顺序执行，所有新增列和索引使用 `information_schema` 守卫：

```sql
ALTER TABLE account_group_membership
  ADD COLUMN membership_status TINYINT NOT NULL DEFAULT 1
    COMMENT '当前账号群关系:1在群 2未确认 3被踢 4主动退出 5不在群' AFTER is_admin,
  ADD COLUMN status_source VARCHAR(64) NULL
    COMMENT '当前关系状态来源' AFTER membership_status,
  ADD COLUMN status_updated_at BIGINT NULL
    COMMENT '当前关系状态事实时间(epoch毫秒)' AFTER status_source;

UPDATE account_group_membership
SET membership_status = 1,
    status_source = CASE WHEN deleted_at IS NULL THEN 'LEGACY_ACTIVE' ELSE 'LEGACY_ARCHIVED' END,
    status_updated_at = COALESCE(deleted_at, updated_at, created_at);

CREATE TEMPORARY TABLE tmp_membership_revival AS
SELECT archived.id,
       COALESCE(archived.deleted_at, archived.updated_at, archived.created_at) AS exited_at
FROM account_group_membership archived
LEFT JOIN account_group_membership active
  ON active.tenant_id = archived.tenant_id
 AND active.account_id = archived.account_id
 AND active.group_jid = archived.group_jid
 AND active.deleted_at IS NULL
WHERE archived.deleted_at IS NOT NULL
  AND active.id IS NULL
  AND archived.id = (
    SELECT newer.id
    FROM account_group_membership newer
    WHERE newer.tenant_id = archived.tenant_id
      AND newer.account_id = archived.account_id
      AND newer.group_jid = archived.group_jid
      AND newer.deleted_at IS NOT NULL
    ORDER BY newer.deleted_at DESC, newer.id DESC
    LIMIT 1
  );

UPDATE account_group_membership m
JOIN tmp_membership_revival r ON r.id = m.id
SET m.membership_status = 5,
    m.status_source = 'LEGACY_MIGRATION',
    m.status_updated_at = r.exited_at,
    m.deleted_at = NULL,
    m.updated_at = r.exited_at;

DROP TEMPORARY TABLE tmp_membership_revival;

ALTER TABLE account_group_membership
  MODIFY COLUMN status_updated_at BIGINT NOT NULL
    COMMENT '当前关系状态事实时间(epoch毫秒)';

ALTER TABLE account_group_membership
  MODIFY COLUMN last_seen_at BIGINT NULL
    COMMENT '最近一次快照或精确add确认仍在群的时间(epoch毫秒);从未确认可为NULL';
```

最后增加索引 `(tenant_id, account_id, membership_status, deleted_at)`。不要删除 `deleted_at/is_active`，
它们继续隔离更早重复历史行和真正废弃数据。

- [ ] **Step 6: 扩展实体并运行真库测试**

实体增加 `Integer membershipStatus`、`String statusSource`、`Long statusUpdatedAt` 及完整 getter/setter，
同步把 `deletedAt` 注释改为“旧重复历史/真正废弃”，不再解释为退出状态。

```bash
cd armada-api
./dbtest.sh 'AccountGroupMembershipStatusMigrationDbTest'
```

Expected: PASS，测试真实执行且没有 skipped。

- [ ] **Step 7: 提交迁移与模型**

```bash
git add armada-api/src/main/resources/db/migration/V060__account_group_membership_status.sql \
  armada-api/src/main/java/com/armada/group/model/enums/AccountGroupMembershipStatus.java \
  armada-api/src/main/java/com/armada/group/model/entity/AccountGroupMembership.java \
  armada-api/src/test/java/com/armada/group/AccountGroupMembershipStatusMigrationDbTest.java
git commit -m "feat: add account group membership statuses"
```

## Task 2: 实现快照状态转换和批量状态查询

**Files:**

- Create: `armada-api/src/main/java/com/armada/group/model/vo/AccountGroupMembershipLookup.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/AccountGroupMembershipStatusRow.java`
- Create: `armada-api/src/main/java/com/armada/group/model/vo/AccountGroupMembershipStatusSnapshot.java`
- Create: `armada-api/src/main/java/com/armada/group/service/AccountGroupMembershipStatusService.java`
- Create: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipStatusServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/service/AccountGroupMembershipSnapshotService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/GroupLinkRegistryService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/GroupLinkRegistryServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/AccountGroupMembershipReportServiceDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImplTest.java`
- Create: `armada-api/src/test/java/com/armada/group/service/AccountGroupMembershipStatusServiceDbTest.java`

- [ ] **Step 1: 写状态转换和批量读取失败测试**

真库测试逐项覆盖：

```java
@Test
void completeSnapshotMarksMissingAsNotInGroupWithoutSoftDelete() {
    service.applyGroupsReported(completeSnapshot(accountId, List.of(visibleGroup), 2000L));
    assertThat(status(accountId, missingJid)).isEqualTo(AccountGroupMembershipStatus.NOT_IN_GROUP.code());
    assertThat(deletedAt(accountId, missingJid)).isNull();
}

@Test
void incompleteSnapshotUpdatesPresentGroupsButPreservesMissingStatus() {
    service.applyGroupsReported(incompleteSnapshot(accountId, List.of(visibleGroup), 3000L));
    assertThat(status(accountId, missingJid)).isEqualTo(AccountGroupMembershipStatus.IN_GROUP.code());
}

```

`newerPresenceRestoresExitedMembershipAndResetsJoinedAt` 先写 `KICKED_OUT@2000`，再应用
`IN_GROUP@3000` 快照，断言状态恢复且 `joined_at=3000`；`batchStatusLookupReturnsOnlyCurrentTenantRows`
给两个租户写相同 account/group key，切换 `TenantContext` 后分别断言只返回各自一行。

另测完整缺失不把 `KICKED_OUT/LEFT` 降为 `NOT_IN_GROUP`；旧快照不覆盖新状态；重复快照不改变
`joined_at`；`UNCONFIRMED` 属于 sendable；查询 key 去重且空输入返回空列表。

- [ ] **Step 2: 运行并确认先失败**

```bash
cd armada-api
./dbtest.sh 'AccountGroupMembershipReportServiceDbTest,AccountGroupMembershipStatusServiceDbTest'
mvn -Dtest=AccountGroupMembershipSnapshotServiceImplTest test
```

Expected: FAIL，旧逻辑仍软删缺失关系且没有批量状态 Service。

- [ ] **Step 3: 定义跨域只读契约**

```java
public record AccountGroupMembershipLookup(Long accountId, String groupJid) {
}

public record AccountGroupMembershipStatusSnapshot(
        Long accountId,
        String groupJid,
        AccountGroupMembershipStatus status,
        Long statusUpdatedAt) {
}

public interface AccountGroupMembershipStatusService {
    List<AccountGroupMembershipStatusSnapshot> findCurrentStatuses(
            List<AccountGroupMembershipLookup> lookups);
}
```

本任务只实现 `findCurrentStatuses`；Task 3 创建精确事件 DTO 后，再给此接口增加
`applyMembershipChanged(AccountGroupMembershipChangedEvent event)`。marketing 域只能调用该 Service，
禁止直接注入 group Mapper/entity。

- [ ] **Step 4: 收敛 group_link 登记能力**

在 `GroupLinkRegistryService` 增加：

```java
Long registerAccountObservedGroup(String groupJid, String groupName, long now);
```

实现复用现有 `wa://group/{jid}`、`ACCOUNT_SYNC` origin 和 `JOINED` 全局关系语义；已有群只复活/更新名称，
不创建第二条入口。`AccountGroupMembershipSnapshotServiceImpl` 删除私有重复的 `ensureGroupLink`，改调该 Service。

- [ ] **Step 5: 把 Mapper 改成状态更新而不是软删**

Mapper 新增/替换方法：

```java
List<String> selectSendableGroupJids(
        @Param("accountId") Long accountId,
        @Param("sendableStatuses") List<Integer> sendableStatuses);

List<AccountGroupMembershipStatusRow> selectCurrentStatuses(
        @Param("lookups") List<AccountGroupMembershipLookup> lookups);

int markMissingMembershipsNotInGroup(
        @Param("accountId") Long accountId,
        @Param("groupJids") List<String> groupJids,
        @Param("status") int status,
        @Param("preservedStatuses") List<Integer> preservedStatuses,
        @Param("source") String source,
        @Param("statusUpdatedAt") long statusUpdatedAt,
        @Param("updatedAt") long updatedAt);
```

删除 `markMissingMembershipsDeleted`。`upsertMembership` 必须同时写状态字段，并用
`status_updated_at` 的条件表达式阻止旧事实覆盖新事实；同一时间精确 `WGP2_REMOVE/WGP2_LEAVE`
优先于 `GROUP_SNAPSHOT`。只有当前状态属于 `KICKED_OUT/LEFT/NOT_IN_GROUP` 且更新快照恢复
`IN_GROUP` 时刷新 `joined_at`。

- [ ] **Step 6: 快照 Service 接收完整性并应用转换**

接口签名增加 `boolean snapshotComplete`：

```java
AccountGroupMembershipChangeSet replaceVisibleGroups(
        Long accountId,
        List<AccountGroupsReportedEvent.Group> groups,
        boolean snapshotComplete,
        long syncAt,
        String eventId,
        String source);
```

可见群始终 upsert 为 `IN_GROUP/GROUP_SNAPSHOT`；只有 `snapshotComplete=true` 才调用
`markMissingMembershipsNotInGroup`。缺失更新保留 `KICKED_OUT/LEFT`，其他当前关系改为
`NOT_IN_GROUP`，且 `deleted_at` 保持 null。

- [ ] **Step 7: 实现批量读取并验证**

`findCurrentStatuses` 规范化 groupJid、去重 key、一次 Mapper 查询，返回 enum snapshot；空输入返回
`List.of()`。Mapper/数据库异常原样抛出，不降级为 `UNCONFIRMED`。

```bash
cd armada-api
mvn -Dtest=AccountGroupMembershipSnapshotServiceImplTest test
./dbtest.sh 'AccountGroupMembershipReportServiceDbTest,AccountGroupMembershipStatusServiceDbTest'
```

Expected: PASS；DbTest 断言退出行未软删。

- [ ] **Step 8: 提交状态服务**

```bash
git add armada-api/src/main/java/com/armada/group \
  armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml \
  armada-api/src/test/java/com/armada/group/service
git commit -m "feat: persist account group membership transitions"
```

提交前用 `git diff --cached --name-only` 确认没有其它业务域文件混入。

## Task 3: 消费 Android 精确事件并兼容 Web 旧快照

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountGroupMembershipChangedEvent.java`
- Create: `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountGroupMembershipChangedSink.java`
- Create: `armada-api/src/main/java/com/armada/group/model/dto/AccountGroupMembershipChangedEvent.java`
- Create: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipChangedSinkAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumer.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/account/ProtocolAccountGroupsReportedEvent.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/dto/AccountGroupsReportedEvent.java`
- Modify: `armada-api/src/main/java/com/armada/group/model/vo/AccountGroupBaselineRow.java`
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupsReportedSinkAdapter.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipStatusServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/platform/kafka/consumer/account/ProtocolAccountEventConsumerTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/AccountGroupMembershipStatusServiceDbTest.java`

- [ ] **Step 1: 写 consumer 契约失败测试**

```java
@Test
void onMessage_membershipChangedDispatchesSafeEvent() {
    consumer.onMessage("""
        {"eventId":"evt-1","event":"account.group_membership_changed","version":"v1",
         "accountId":"acc_android_1","occurredAt":"2026-07-22T02:00:00Z","workerId":"android-1",
         "data":{"tenantId":7,"accountId":100,"protocolAccountId":"acc_android_1",
                 "groupJid":"120363001@g.us","action":"remove",
                 "selfParticipation":"SELF","source":"android_wgp2"}}
        """);

    assertThat(membershipEvents).singleElement().satisfies(event -> {
        assertThat(event.action()).isEqualTo("remove");
        assertThat(event.occurredAt()).isEqualTo(1784685600000L);
    });
}
```

另测非法 action、非 SELF、缺路由字段失败并进入 Kafka retry；groups event 正确解析
`snapshotComplete/skippedGroupCount`；未知事件仍走现有 warn/skip。

- [ ] **Step 2: 运行并确认先失败**

```bash
cd armada-api
mvn -Dtest=ProtocolAccountEventConsumerTest,AccountGroupMembershipReportServiceImplTest test
```

Expected: FAIL，新事件常量、sink 和快照字段不存在。

- [ ] **Step 3: 扩展 platform wire records**

```java
public record ProtocolAccountGroupMembershipChangedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String groupJid,
        String action,
        String selfParticipation,
        Long occurredAt,
        String source,
        String workerId) {
}
```

group 域 DTO 固定为：

```java
public record AccountGroupMembershipChangedEvent(
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String groupJid,
        String action,
        Long occurredAt,
        String eventId,
        String source) {
}
```

`ProtocolAccountGroupsReportedEvent` 增加 `Boolean snapshotComplete`、`Integer skippedGroupCount`；
consumer 日志只记录 accountId、action、source、groupCount、完整性和 eventId，不记录原始 JSON。

- [ ] **Step 4: 用 sink adapter 进入 group 域**

`AccountGroupMembershipChangedSinkAdapter` 先拒绝非 `SELF`、未知 action 和非 `@g.us` JID，再做
wire → group DTO 转换，调用
`AccountGroupMembershipStatusService.applyMembershipChanged`；本步骤同时把该方法加入 Task 2 创建的
Service 接口。Service 在事务内重建 `TenantContext`，
action 映射为：

```java
add    -> IN_GROUP / WGP2_ADD
remove -> KICKED_OUT / WGP2_REMOVE
leave  -> LEFT / WGP2_LEAVE
```

先用 `selectAccountBaselineRow` 校验账号存在且 `protocolAccountId` 与数据库当前绑定一致，再通过
`GroupLinkRegistryService.registerAccountObservedGroup` 获得 groupLinkId 后 upsert 当前关系。负向事件先于
任何快照时允许创建 `joined_at/last_seen_at=NULL` 的当前行；`add` 写两个时间。旧 `occurredAt` 不覆盖新事实，
相同 event 重复消费不改变 `joined_at`。

- [ ] **Step 5: 实现 Web 旧契约兼容**

`AccountGroupBaselineRow/selectAccountBaselineRow` 同时返回 `account.protocol_id` 和
`account.protocol_account_id`。报告 Service 先验证事件协议账号句柄仍匹配当前账号绑定，再使用唯一规则：

```java
boolean complete = Boolean.TRUE.equals(event.snapshotComplete())
        && zero(event.skippedGroupCount()) == 0;
if (event.snapshotComplete() == null
        && ProtocolBackend.fromProtocolId(baselineRow.getProtocolId()) == ProtocolBackend.WEB) {
    complete = true;
}
```

含义：Web/Baileys 旧事件缺字段继续按历史完整快照处理；Android 缺字段按不完整处理；任意显式 false
或 `skippedGroupCount > 0` 都不得批量更新缺失关系。不要修改 `armada-protocol`。

- [ ] **Step 6: 验证 consumer、幂等和乱序**

```bash
cd armada-api
mvn -Dtest=ProtocolAccountEventConsumerTest,AccountGroupMembershipReportServiceImplTest test
./dbtest.sh 'AccountGroupMembershipStatusServiceDbTest,AccountGroupMembershipReportServiceDbTest'
```

Expected: PASS；Web 缺字段会校准，Android 缺字段不校准，精确 remove/leave 可独立落库。

- [ ] **Step 7: 提交事件消费**

```bash
git add armada-api/src/main/java/com/armada/platform/kafka/consumer/account \
  armada-api/src/main/java/com/armada/group \
  armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml \
  armada-api/src/test/java/com/armada/platform/kafka/consumer/account \
  armada-api/src/test/java/com/armada/group
git commit -m "feat: consume account group membership events"
```

## Task 4: 创建任务时返回并允许选择全部关系状态

**Files:**

- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTargetCandidateRow.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTreeGroupVO.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeService.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskAccountTreeDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java`

- [ ] **Step 1: 写树和创建任务失败测试**

真库 fixture 给同一账号建立五种当前关系，断言：

```java
assertThat(service.accountGroups(accountId).groups())
        .extracting(MarketingTreeGroupVO::membershipStatus)
        .containsExactly("IN_GROUP", "UNCONFIRMED", "KICKED_OUT", "LEFT", "NOT_IN_GROUP");
```

再通过创建 Service 选择 `KICKED_OUT/LEFT/NOT_IN_GROUP` 的 groupLinkId，断言任务 target 正常落库，
没有“群组不可用”校验错误。全局群组列表回归结果数量不变。

- [ ] **Step 2: 运行并确认先失败**

```bash
cd armada-api
./dbtest.sh 'MarketingTaskAccountTreeDbTest,MarketingTaskCreateReadDbTest'
```

Expected: FAIL，旧查询会过滤退出、健康异常或 baseline 群，VO 也没有状态字段。

- [ ] **Step 3: 扩展候选和 API VO**

`MarketingTargetCandidateRow` 增加 `Integer membershipStatus`、`Long statusUpdatedAt`；
`MarketingTreeGroupVO` 改为：

```java
public record MarketingTreeGroupVO(
        Long groupLinkId,
        String groupJid,
        String groupName,
        String linkUrl,
        Boolean isAdmin,
        String membershipStatus,
        String membershipStatusText,
        Long statusUpdatedAt) {
}
```

- [ ] **Step 4: 修改三类查询**

- `selectDynamicTargetGroups`：返回所有 `deleted_at IS NULL` 当前关系及状态；保留 `joined_at` 时间边界。
- `selectAccountTreeAccounts/selectAccountTreeAccount` 的 groupCount：统计所有当前关系，不按 health、baseline、
  `group_link.membership_state` 或关系状态过滤。
- `selectTargetCandidate`：显式 JOIN 当前账号的 `account_group_membership`，验证群确实属于该账号；不按关系状态、
  health 或 baseline 过滤；groupJid 取 membership 行。

账号本身仍必须满足在线、非风控、非禁言、协议账号存在。所有群关系允许勾选不等于离线账号可创建任务。

- [ ] **Step 5: 运行真库测试并提交**

```bash
cd armada-api
./dbtest.sh 'MarketingTaskAccountTreeDbTest,MarketingTaskCreateReadDbTest'
cd ..
git add armada-api/src/main/java/com/armada/marketing/model/vo \
  armada-api/src/main/java/com/armada/marketing/service/impl/MarketingAccountTreeRealtimeService.java \
  armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml \
  armada-api/src/test/java/com/armada/marketing/service/MarketingTaskAccountTreeDbTest.java \
  armada-api/src/test/java/com/armada/marketing/service/MarketingTaskCreateReadDbTest.java
git commit -m "feat: expose all marketing membership states"
```

## Task 5: 营销 Worker 按关系状态发送或写 SKIPPED

**Files:**

- Create: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingMembershipSendPolicy.java`
- Create: `armada-api/src/test/java/com/armada/marketing/service/impl/MarketingMembershipSendPolicyTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTaskSendAttempt.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerDbTest.java`

- [ ] **Step 1: 写发送决策失败测试**

```java
@ParameterizedTest
@EnumSource(value = AccountGroupMembershipStatus.class,
        names = {"IN_GROUP", "UNCONFIRMED"})
void sendableStatusesContinueToProtocol(AccountGroupMembershipStatus status) {
    assertThat(MarketingMembershipSendPolicy.decide(status).sendable()).isTrue();
}

@ParameterizedTest
@CsvSource({
        "KICKED_OUT,KICKED_OUT,账号已被踢出群聊",
        "LEFT,LEFT,账号已主动退出群聊",
        "NOT_IN_GROUP,NOT_IN_GROUP,账号当前已不在群聊"
})
void exitedStatusesHaveStableSkipReasons(
        AccountGroupMembershipStatus status, String code, String message) {
    var decision = MarketingMembershipSendPolicy.decide(status);
    assertThat(decision.sendable()).isFalse();
    assertThat(decision.reasonCode()).isEqualTo(code);
    assertThat(decision.reasonMessage()).isEqualTo(message);
}
```

Worker 测试覆盖：一次批量状态查询；两种可发送状态有 outbox；三种退出状态只有 SKIPPED attempt；
缺失关系按 UNCONFIRMED 发送；状态查询抛异常时事务回滚、无 attempt/outbox；关系跳过优先于账号占用原因；
全部跳过仍成功 claim 本轮并推进 nextRoundAt。

- [ ] **Step 2: 运行并确认先失败**

```bash
cd armada-api
mvn -Dtest=MarketingMembershipSendPolicyTest,MarketingRoundWorkerTest test
./dbtest.sh 'MarketingRoundWorkerDbTest'
```

Expected: FAIL，Worker 仍不读取 membership 状态。

- [ ] **Step 3: 定义 send/skip policy**

```java
final class MarketingMembershipSendPolicy {
    static Decision decide(AccountGroupMembershipStatus status) {
        AccountGroupMembershipStatus resolved = status == null
                ? AccountGroupMembershipStatus.UNCONFIRMED : status;
        return switch (resolved) {
            case IN_GROUP, UNCONFIRMED -> Decision.send();
            case KICKED_OUT -> Decision.skip("KICKED_OUT", "账号已被踢出群聊");
            case LEFT -> Decision.skip("LEFT", "账号已主动退出群聊");
            case NOT_IN_GROUP -> Decision.skip("NOT_IN_GROUP", "账号当前已不在群聊");
        };
    }

    record Decision(boolean sendable, String reasonCode, String reasonMessage) {
        static Decision send() { return new Decision(true, null, null); }
        static Decision skip(String code, String message) { return new Decision(false, code, message); }
    }
}
```

- [ ] **Step 4: 扩展 attempt 状态快照字段**

`MarketingTaskSendAttempt` 增加 `groupStatus/groupStatusReason/groupStatusCheckedAt`。Mapper resultMap、批量 insert、
单条 insert 和 select 列表全部同步，不能只改 UPDATE 回执路径。

- [ ] **Step 5: Worker 在 attempt/outbox 边界批量读取**

`MarketingRoundWorker` 注入 `AccountGroupMembershipStatusService`。目标解析后，把
`accountId/groupJid` 转成去重 `AccountGroupMembershipLookup`，调用一次 `findCurrentStatuses` 并构造 Map。

分区顺序固定为：

1. 当前关系不可发送 → membership skipped。
2. 关系可发送或缺失 → 再检查账号 occupancy。
3. 两项均通过 → 创建 SUBMITTED attempt 和协议 outbox。

membership skip attempt 字段：

```java
attempt.setCommandId(null);
attempt.setStatus(MarketingSendAttemptStatus.SKIPPED.code());
attempt.setReasonCode(decision.reasonCode());
attempt.setReasonMessage(decision.reasonMessage());
attempt.setGroupStatus(status.apiValue());
attempt.setGroupStatusReason(decision.reasonCode());
attempt.setGroupStatusCheckedAt(now);
attempt.setSubmittedAt(null);
attempt.setResultAt(now);
```

缺失 key 不写关系表、不报错，按 `UNCONFIRMED` 进入发送；Service/Mapper 异常必须抛出让整个事务回滚。

- [ ] **Step 6: 验证 Worker 和真库字段**

```bash
cd armada-api
mvn -Dtest=MarketingMembershipSendPolicyTest,MarketingRoundWorkerTest test
./dbtest.sh 'MarketingRoundWorkerDbTest'
```

Expected: PASS；退出状态 command/outbox 数为 0，attempt 状态为 3，success/failed counters 不变。

- [ ] **Step 7: 提交 Worker 运行时跳过**

```bash
git add armada-api/src/main/java/com/armada/marketing \
  armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml \
  armada-api/src/test/java/com/armada/marketing
git commit -m "feat: skip marketing sends for exited groups"
```

## Task 6: 详情分离当前关系、协议状态和最后执行

**Files:**

- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountGroupStatRow.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskGroupStatVO.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskAccountTargetVO.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/MarketingTaskDetailVO.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizer.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizerTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingRoundMapperDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/controller/MarketingTaskControllerDbTest.java`

- [ ] **Step 1: 写聚合和 API 失败测试**

构造：固定 LEFT target 尚未执行、历史 SUCCESS 后本轮 SKIPPED、FAILED 后 SUBMITTED、全部 SKIPPED 四组数据。
断言：

```java
assertThat(group.membershipStatus()).isEqualTo("LEFT");
assertThat(group.groupStatus()).isEqualTo("NORMAL");
assertThat(group.executionResult()).isEqualTo("SKIPPED");
assertThat(group.executionReason()).isEqualTo("账号已主动退出群聊");
assertThat(group.sentMessageCount()).isEqualTo(1);
assertThat(group.failedMessageCount()).isZero();
assertThat(group.skippedMessageCount()).isEqualTo(1);
```

固定 target 未执行也必须出现在 groups；动态 target 只有解析/attempt 后出现真实群；SUBMITTED 不覆盖最后已结束结果。

- [ ] **Step 2: 运行并确认先失败**

```bash
cd armada-api
mvn -Dtest=MarketingGroupExecutionNormalizerTest test
./dbtest.sh 'MarketingRoundMapperDbTest,MarketingTaskControllerDbTest#getDetail_returnsTargets'
```

Expected: FAIL，旧聚合只从 attempt 取群、latest effective 排除 SKIPPED，VO 没有新字段。

- [ ] **Step 3: 重写群维度 CTE，保持 SQL 下推**

`selectAccountGroupStatsByTaskId` 使用以下职责清晰的 CTE：

- `attempt_facts`：状态 0/1/2/3 的全部 attempt。
- `fixed_target_groups`：`target_scope=GROUP_FIXED` 的固定目标，即使没有 attempt 也产生群维度。
- `group_dimensions`：固定目标与 attempt 群 key 的 `UNION`。
- `group_aggregate`：按维度统计 SUCCESS/FAILED/SKIPPED、最后 attempt/成功时间。
- `latest_protocol`：只在 1/2 中按 round/attempt/id 选最后协议结果，派生 `groupStatus`。
- `latest_ended`：只在 1/2/3 中选最后已结束结果，派生 `executionResult/reason`。

最终 LEFT JOIN 当前 `account_group_membership`，只取同租户、同账号、同 groupJid、`deleted_at IS NULL`
的 `membership_status`；缺失交给 Java 回退 `UNCONFIRMED`。禁止在 Java load-all 后聚合。

- [ ] **Step 4: 扩展 row 和 VO**

`MarketingTaskGroupStatVO` 最终字段顺序固定为：

```java
public record MarketingTaskGroupStatVO(
        Long groupLinkId,
        String groupJid,
        String groupLinkUrl,
        String groupName,
        String membershipStatus,
        String groupStatus,
        String executionResult,
        String executionReason,
        Integer sentMessageCount,
        Integer failedMessageCount,
        Integer skippedMessageCount,
        Long lastAttemptAt,
        Long lastSentAt,
        String lastReason) {
}
```

`MarketingTaskAccountTargetVO` 和 `MarketingTaskDetailVO` 各增加 `skippedMessageCount`；任务值从 account/group
明细汇总，不新增主表计数列，不改变列表接口的成功/失败持久化口径。

- [ ] **Step 5: 分离 normalizer 职责**

`MarketingGroupExecutionNormalizer` 保留协议 SUCCESS/FAILED 到 `NORMAL/...` 的群状态归一；新增执行状态映射：

```java
static String executionResult(Integer status) {
    if (Integer.valueOf(MarketingSendAttemptStatus.SUCCESS.code()).equals(status)) return "SUCCESS";
    if (Integer.valueOf(MarketingSendAttemptStatus.FAILED.code()).equals(status)) return "FAILED";
    if (Integer.valueOf(MarketingSendAttemptStatus.SKIPPED.code()).equals(status)) return "SKIPPED";
    return null;
}
```

SKIPPED 的 executionReason 优先 `reasonMessage`，再 reasonCode，最后“本轮已跳过”；协议 groupStatus 仍来自
latest protocol，不被 skip 覆盖。当前 membershipStatus 完全来自关系表，不从历史失败原因猜测，只有关系缺失
时才做 `UNCONFIRMED`/历史 KICKED_OUT 兼容回退。

- [ ] **Step 6: 运行聚焦和真库测试**

```bash
cd armada-api
mvn -Dtest=MarketingGroupExecutionNormalizerTest test
./dbtest.sh 'MarketingRoundMapperDbTest,MarketingTaskControllerDbTest#getDetail_returnsTargets'
```

Expected: PASS；SKIPPED 计数不进入 failed，固定未执行 target 可见。

- [ ] **Step 7: 提交详情契约**

```bash
git add armada-api/src/main/java/com/armada/marketing/model/vo \
  armada-api/src/main/java/com/armada/marketing/service/impl/MarketingGroupExecutionNormalizer.java \
  armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java \
  armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml \
  armada-api/src/test/java/com/armada/marketing
git commit -m "feat: expose marketing membership and skipped details"
```

## Task 7: 文档、回滚材料和后端总验证

**Files:**

- Create: `.harness/changes/account-group-membership-marketing/summary.md`
- Create: `.harness/changes/account-group-membership-marketing/db-migrations.sql`
- Create: `.harness/changes/account-group-membership-marketing/rollback.sql`
- Modify: `.harness/wiki/数据模型.md`（只能由生成器刷新）
- Modify: `.harness/changes/2026-07-21-account-group-membership-status-marketing.md`

- [ ] **Step 1: 写变更和回滚文件**

`db-migrations.sql` 保存与 V060 相同的受控结构/数据迁移说明。`rollback.sql` 只恢复旧应用安全筛选语义：

```sql
UPDATE account_group_membership
SET deleted_at = COALESCE(deleted_at, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
    updated_at = UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000
WHERE deleted_at IS NULL
  AND membership_status IN (3, 4, 5);
```

不 DROP 新列，不删除历史状态。该 SQL 只落文件，真实执行前必须确认环境、备份和影响行数。

- [ ] **Step 2: 刷新数据模型文档**

在已确认测试库应用 Flyway 后，从 `information_schema` 导出生成器要求的三个 TSV，再运行：

```bash
python3 .harness/wiki/gen_datamodel.py
```

Expected: `/tmp/datamodel_tables.md` 中 `account_group_membership` 含三个新字段和索引。按仓库现有文档同步流程
替换生成段落，禁止手写猜测 schema；记录生成库和 Flyway version。

- [ ] **Step 3: 执行聚焦普通单测**

```bash
cd armada-api
mvn -Dtest=ProtocolAccountEventConsumerTest,AccountGroupMembershipSnapshotServiceImplTest,\
MarketingMembershipSendPolicyTest,MarketingRoundWorkerTest,MarketingGroupExecutionNormalizerTest test
```

Expected: 0 failures，0 errors，0 unexpected skipped。

- [ ] **Step 4: 执行真库 DbTest**

```bash
./dbtest.sh 'AccountGroupMembershipStatusMigrationDbTest,AccountGroupMembershipReportServiceDbTest,AccountGroupMembershipStatusServiceDbTest,MarketingTaskAccountTreeDbTest,MarketingTaskCreateReadDbTest,MarketingRoundWorkerDbTest,MarketingRoundMapperDbTest,MarketingTaskControllerDbTest'
```

Expected: 目标类真实执行、0 failures、0 errors；缺 `.env`、未确认数据库或连接失败必须如实记录为未执行。

- [ ] **Step 5: 执行后端全量门禁**

```bash
mvn test
cd ..
python3 .harness/wiki/test_api_docs.py
git diff --check
```

Expected: 全部退出码 0。人工复核 `shared <- platform <- 业务域 <- boot`、跨域只调 Service、Mapper
租户隔离、无 Repository、无内存分页、无生产 mock。

- [ ] **Step 6: 专家评审并提交文档**

执行 `superpowers:requesting-code-review`，重点审查 Flyway、状态乱序、Kafka 幂等、Worker 事务、SQL 聚合和回滚安全。
修复所有红色问题后：

```bash
git add .harness/changes/account-group-membership-marketing \
  .harness/changes/2026-07-21-account-group-membership-status-marketing.md \
  .harness/wiki/数据模型.md
git commit -m "docs: record account membership marketing rollout"
```

不得把 `.env`、私钥、数据库导出 TSV 或原始 Kafka payload 加入提交。
