# Account-Dynamic New-Group Immediate Marketing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让发送中的 `ACCOUNT_DYNAMIC` 普通营销任务在账号新加入群时立即生成一次 `round_no=0` 消息，同时保持原正常轮次时间、协议路由和后续动态群解析不变。

**Architecture:** 在现有 `account.groups_reported` 事务里读取账号同步前活跃群集合，刷新 membership 后得到新增差量；群域只通过营销 Service 契约把新增群交给营销域。营销域使用现有动态 target、attempt 唯一键、`MessageSendPort` 和 `protocol_command_outbox` 原子入队，事务提交后由现有 afterCommit dispatcher 推送 Kafka；协议层和前端均不修改。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis/MySQL、Flyway、Kafka transactional outbox、JUnit 5、Mockito、AssertJ、Armada 真库 DbTest。

---

## 开工前约束

- 权威设计：`docs/superpowers/specs/2026-07-20-account-dynamic-new-group-immediate-marketing-design.md`。
- 只修改 `armada/armada-api` 和本任务文档；不要修改 Web、Android Zhuan 或前端。
- 开始执行时先使用 `using-git-worktrees` 创建隔离 worktree，避免覆盖当前主 worktree 中已有的部署改动。
- 每个任务严格先红后绿；Mapper、SQL、Flyway 必须跑真库 DbTest。
- 业务重试只作用于 `round_no=0`；正常轮次失败行为保持不变。
- `protocol_command_outbox` 的 Kafka 发布重试不是业务消息重试，不能新增营销 attempt。

## 文件结构与职责

### 新增文件

- `armada-api/src/main/java/com/armada/group/model/vo/AccountGroupMembershipChangeSet.java`：一次账号群快照的当前集合和新增差量。
- `armada-api/src/main/java/com/armada/marketing/model/dto/MarketingNewGroupDTO.java`：群域调用营销 Service 的轻量跨域参数。
- `armada-api/src/main/java/com/armada/marketing/model/support/MarketingResolvedTarget.java`：普通轮次、即时发送、重试共享的实际群目标。
- `armada-api/src/main/java/com/armada/marketing/service/MarketingNewGroupImmediateSendService.java`：群域可调用的即时发送 Service 契约。
- `armada-api/src/main/java/com/armada/marketing/service/MarketingMessageCommandFactory.java`：集中组装模板和协议无关 `MessageSendCommand`。
- `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingNewGroupImmediateSendServiceImpl.java`：首次即时 attempt 抢占、间隔排期和 outbox 入队。
- `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingImmediateRetryService.java`：`round_no=0` 第一次协议失败后的单次业务重试。
- `armada-api/src/main/resources/db/migration/V059__marketing_new_group_immediate_round.sql`：把 `round_no=0` 的业务语义写入列注释。
- `armada-api/src/test/java/com/armada/marketing/service/MarketingMessageCommandFactoryTest.java`：共享命令工厂回归。
- `armada-api/src/test/java/com/armada/marketing/service/MarketingNewGroupImmediateSendServiceImplTest.java`：即时发送服务单测。
- `armada-api/src/test/java/com/armada/marketing/service/MarketingImmediateRetryServiceTest.java`：一次业务重试单测。
- `armada-api/src/test/java/com/armada/marketing/service/AccountDynamicNewGroupImmediateMarketingDbTest.java`：群回报到 attempt/outbox/下一轮的真库验收。
- `.harness/changes/2026-07-20-account-dynamic-new-group-immediate-marketing/db-migrations.sql`：数据库前滚语义记录。
- `.harness/changes/2026-07-20-account-dynamic-new-group-immediate-marketing/rollback.sql`：仅恢复旧列注释的回滚 SQL。

### 修改文件

- `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`：查询账号同步前活跃群 JID。
- `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`：账号范围活跃群查询。
- `armada-api/src/main/java/com/armada/group/service/AccountGroupMembershipSnapshotService.java`：返回变化集。
- `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImpl.java`：求新增差量。
- `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImpl.java`：baseline 保护并调用营销 Service。
- `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`：即时 target 查询、attempt 读取/重提和 commandId 幂等参数。
- `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`：实现上述 SQL。
- `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`：复用共享目标和命令工厂，正常轮次行为不变。
- `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java`：commandId 幂等和即时失败重试分流。
- `armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageSendResultReportedEvent.java`：修正文档，明确 commandId 参与结果幂等。
- 现有相关测试：更新构造器和 mapper 方法签名，新增边界断言。
- `.harness/wiki/数据模型.md`：从迁移后的真库 schema 重新生成。
- `.harness/changes/2026-07-20-account-dynamic-new-group-immediate-marketing.md`：持续记录实施、验证和部署事实。

---

### Task 1: 群快照返回新增差量

**Files:**
- Create: `armada-api/src/main/java/com/armada/group/model/vo/AccountGroupMembershipChangeSet.java`
- Modify: `armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java`
- Modify: `armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/group/service/AccountGroupMembershipSnapshotService.java`
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImpl.java`
- Test: `armada-api/src/test/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImplTest.java`

- [ ] **Step 1: 写失败单测，锁定“当前集合 + 新增集合”**

在 `AccountGroupMembershipSnapshotServiceImplTest` 增加：

```java
@Test
void replaceVisibleGroups_returnsOnlyGroupsMissingBeforeRefreshAsAdded() {
    when(membershipMapper.selectActiveGroupJids(10L))
            .thenReturn(List.of("120363old@g.us"));
    when(membershipMapper.selectActiveGroupLinkIdByGroupJid("120363old@g.us"))
            .thenReturn(11L);
    when(membershipMapper.selectActiveGroupLinkIdByGroupJid("120363new@g.us"))
            .thenReturn(12L);

    AccountGroupMembershipChangeSet result = service.replaceVisibleGroups(
            10L,
            List.of(
                    group("120363old@g.us", "旧群"),
                    group("120363new@g.us", "新群"),
                    group("120363new@g.us", "重复新群")),
            1783785600000L,
            "evt-added",
            "wa_groups_dirty");

    assertThat(result.currentGroups())
            .extracting(AccountGroupMembershipSnapshot::groupJid)
            .containsExactly("120363old@g.us", "120363new@g.us");
    assertThat(result.addedGroups())
            .extracting(AccountGroupMembershipSnapshot::groupJid)
            .containsExactly("120363new@g.us");
}

private static AccountGroupsReportedEvent.Group group(String jid, String subject) {
    return new AccountGroupsReportedEvent.Group(
            jid, subject, null, null, null, false, false, null);
}
```

补齐 AssertJ 和新 VO imports。

- [ ] **Step 2: 运行单测确认 RED**

Run:

```bash
cd armada-api && mvn -q -Dtest=AccountGroupMembershipSnapshotServiceImplTest test
```

Expected: 编译失败，缺少 `AccountGroupMembershipChangeSet`、`selectActiveGroupJids`，且接口仍返回 `List<AccountGroupMembershipSnapshot>`。

- [ ] **Step 3: 新增变化集和 Mapper 查询**

创建 `AccountGroupMembershipChangeSet.java`：

```java
package com.armada.group.model.vo;

import java.util.List;

/**
 * 一次账号当前群全量快照刷新结果。
 *
 * @param currentGroups 刷新后仍活跃的全部群
 * @param addedGroups   刷新前不存在、本次首次出现的群
 */
public record AccountGroupMembershipChangeSet(
        List<AccountGroupMembershipSnapshot> currentGroups,
        List<AccountGroupMembershipSnapshot> addedGroups
) {
    public AccountGroupMembershipChangeSet {
        currentGroups = currentGroups == null ? List.of() : List.copyOf(currentGroups);
        addedGroups = addedGroups == null ? List.of() : List.copyOf(addedGroups);
    }
}
```

在 `AccountGroupMembershipMapper` 增加：

```java
/** 查询账号刷新前仍活跃的群 JID，供全量快照计算新增差量。 */
List<String> selectActiveGroupJids(@Param("accountId") Long accountId);
```

在 `AccountGroupMembershipMapper.xml` 增加：

```xml
<select id="selectActiveGroupJids" resultType="java.lang.String">
  SELECT group_jid
  FROM account_group_membership
  WHERE account_id = #{accountId}
    AND deleted_at IS NULL
    AND group_jid IS NOT NULL
    AND TRIM(group_jid) &lt;&gt; ''
  ORDER BY id ASC
</select>
```

- [ ] **Step 4: 修改快照 Service 返回变化集**

把接口返回值改为：

```java
AccountGroupMembershipChangeSet replaceVisibleGroups(
        Long accountId,
        List<AccountGroupsReportedEvent.Group> groups,
        long syncAt,
        String eventId,
        String source);
```

在实现方法开头读取旧集合，在循环中收集新增快照，结尾返回变化集：

```java
List<String> activeGroupJids = membershipMapper.selectActiveGroupJids(accountId);
Set<String> previousActive = activeGroupJids == null
        ? Set.of()
        : activeGroupJids.stream()
                .map(AccountGroupMembershipSnapshotServiceImpl::normalizeJid)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
List<AccountGroupMembershipSnapshot> snapshots = new ArrayList<>();
List<AccountGroupMembershipSnapshot> added = new ArrayList<>();

for (Map.Entry<String, AccountGroupsReportedEvent.Group> entry : visibleGroups.entrySet()) {
    String groupJid = entry.getKey();
    AccountGroupsReportedEvent.Group group = entry.getValue();
    Long groupLinkId = ensureGroupLink(groupJid, group, now);
    persistSnapshots(groupLinkId, groupJid, group, syncAt, now);
    upsertMembership(accountId, groupLinkId, groupJid, group, syncAt, now);
    AccountGroupMembershipSnapshot snapshot = toSnapshot(groupLinkId, groupJid, group);
    snapshots.add(snapshot);
    if (!previousActive.contains(groupJid)) {
        added.add(snapshot);
    }
}

membershipMapper.markMissingMembershipsDeleted(
        accountId, List.copyOf(visibleGroups.keySet()), now);
return new AccountGroupMembershipChangeSet(snapshots, added);
```

同步修改 Javadoc，并 import `Set`、`LinkedHashSet`、`Collectors` 和变化集类型。

- [ ] **Step 5: 运行 GREEN 和 XML 校验**

```bash
xmllint --noout armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml
cd armada-api && mvn -q -Dtest=AccountGroupMembershipSnapshotServiceImplTest test
```

Expected: XML exit 0；测试全部 PASS。

- [ ] **Step 6: 提交 Task 1**

```bash
git add armada-api/src/main/java/com/armada/group/model/vo/AccountGroupMembershipChangeSet.java \
        armada-api/src/main/java/com/armada/group/mapper/AccountGroupMembershipMapper.java \
        armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml \
        armada-api/src/main/java/com/armada/group/service/AccountGroupMembershipSnapshotService.java \
        armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImpl.java \
        armada-api/src/test/java/com/armada/group/service/impl/AccountGroupMembershipSnapshotServiceImplTest.java
git commit -m "feat(group): expose added account groups"
```

---

### Task 2: 抽取普通营销共享命令工厂

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/model/support/MarketingResolvedTarget.java`
- Create: `armada-api/src/main/java/com/armada/marketing/service/MarketingMessageCommandFactory.java`
- Create: `armada-api/src/test/java/com/armada/marketing/service/MarketingMessageCommandFactoryTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerDbTest.java`

- [ ] **Step 1: 写命令工厂失败单测**

创建 `MarketingMessageCommandFactoryTest`，使用 mock template/file mapper 和真实 `MarketingMessageComposer`：

```java
@Test
void toCommand_preservesAndroidRoutingRoundAndDelay() {
    MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
    MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
    MarketingMessageCommandFactory factory = new MarketingMessageCommandFactory(
            templateMapper, fileMapper, new MarketingMessageComposer());
    MarketingTask task = task(42L, 1L, 77L, 750);
    MarketingTaskTarget target = target(501L, 5001L, "ANDROID", "acc_5001", "923000001");
    MarketingTaskSendAttempt attempt = attempt(9001L, "cmd_immediate", 0L);
    MarketingResolvedTarget resolved = new MarketingResolvedTarget(
            target, 301L, "120363new@g.us", "新群");
    MarketingMessageComposer.ComposedMessage message = new MarketingMessageComposer.ComposedMessage(
            "TEXT", "hello", null, null, false);

    MessageSendCommand command = factory.toCommand(
            task, resolved, attempt, message, 2_750L);

    assertThat(command.account().backend()).isEqualTo(ProtocolBackend.ANDROID);
    assertThat(command.correlation().marketing().roundNo()).isZero();
    assertThat(command.commandId()).isEqualTo("cmd_immediate");
    assertThat(command.sendIntervalMs()).isEqualTo(750);
    assertThat(command.notBeforeAt()).isEqualTo(2_750L);
}
```

测试里的 `task`、`target`、`attempt` helper 必须完整设置断言用到的 ID、租户、协议账号和间隔字段。

- [ ] **Step 2: 运行单测确认 RED**

```bash
cd armada-api && mvn -q -Dtest=MarketingMessageCommandFactoryTest test
```

Expected: 编译失败，缺少 `MarketingResolvedTarget` 和 `MarketingMessageCommandFactory`。

- [ ] **Step 3: 创建共享目标 record**

```java
package com.armada.marketing.model.support;

import com.armada.marketing.model.entity.MarketingTaskTarget;

/** 一条营销 target 在某次发送中解析出的实际群。 */
public record MarketingResolvedTarget(
        MarketingTaskTarget target,
        Long groupLinkId,
        String groupJid,
        String groupName
) {
}
```

- [ ] **Step 4: 创建命令工厂并搬移现有组包代码**

`MarketingMessageCommandFactory` 必须包含下列公开方法：

```java
@Component
public class MarketingMessageCommandFactory {
    private static final String SOURCE_MARKETING_TASK = "marketing_task";
    private static final int DEFAULT_ACCOUNT_GROUP_SEND_INTERVAL_MS = 500;

    private final MarketingTemplateMapper templateMapper;
    private final MarketingTemplateFileMapper fileMapper;
    private final MarketingMessageComposer composer;

    public MarketingMessageCommandFactory(MarketingTemplateMapper templateMapper,
                                          MarketingTemplateFileMapper fileMapper,
                                          MarketingMessageComposer composer) {
        this.templateMapper = templateMapper;
        this.fileMapper = fileMapper;
        this.composer = composer;
    }

    public MarketingMessageComposer.ComposedMessage composeTaskMessage(MarketingTask task) {
        MarketingTemplate template = templateMapper.selectById(task.getMarketingTemplateId());
        if (template == null) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "营销模板不存在: " + task.getMarketingTemplateId());
        }
        MarketingTemplateFile image = template.getImageFileId() == null
                ? null
                : fileMapper.selectById(template.getImageFileId());
        return composer.compose(template, image);
    }

    public MessageSendCommand toCommand(MarketingTask task,
                                        MarketingResolvedTarget resolved,
                                        MarketingTaskSendAttempt attempt,
                                        MarketingMessageComposer.ComposedMessage message,
                                        long notBeforeAt) {
        MarketingTaskTarget target = resolved.target();
        return new MessageSendCommand(
                accountRef(target),
                new MessageSendCommand.MessageTarget(resolved.groupJid()),
                payload(message),
                new MessageSendCommand.MessageCorrelation(
                        task.getTenantId(),
                        SOURCE_MARKETING_TASK,
                        new MessageSendCommand.MarketingCorrelation(
                                task.getId(), target.getId(), attempt.getId(), attempt.getRoundNo()),
                        null,
                        null),
                attempt.getCommandId(),
                accountGroupSendIntervalMs(task),
                notBeforeAt);
    }

    public String newCommandId() {
        return "cmd_" + UUID.randomUUID().toString().replace("-", "");
    }

    public int accountGroupSendIntervalMs(MarketingTask task) {
        Integer configured = task.getAccountGroupSendIntervalMs();
        return configured == null || configured < 1
                ? DEFAULT_ACCOUNT_GROUP_SEND_INTERVAL_MS
                : configured;
    }

    public boolean hasLargeMediaPayload(MarketingMessageComposer.ComposedMessage message) {
        return "IMAGE".equals(message.messageType())
                || (message.linkCard() != null && message.linkCard().thumbnail() != null)
                || (message.buttonCard() != null && message.buttonCard().thumbnail() != null);
    }
}
```

把以下 helper 一并逐字搬入工厂，保持 Web/Android payload、默认 backend 和错误文案不变：

```java
private static MessageSendCommand.MessagePayload payload(
        MarketingMessageComposer.ComposedMessage message) {
    return new MessageSendCommand.MessagePayload(
            MessageType.valueOf(message.messageType()),
            new MessageSendCommand.MessageContent(
                    message.text(),
                    mediaPayload(message.imageBytes(), message.imageMimetype()),
                    linkCardPayload(message.linkCard()),
                    buttonCardPayload(message.buttonCard())),
            message.mentionAll());
}

private static MessageSendCommand.MessageLinkCard linkCardPayload(
        MarketingMessageComposer.LinkCardPayload linkCard) {
    if (linkCard == null) {
        return null;
    }
    return new MessageSendCommand.MessageLinkCard(
            linkCard.url(),
            linkCard.title(),
            linkCard.description(),
            mediaPayload(linkCard.thumbnail()));
}

private static MessageSendCommand.MessageButtonCard buttonCardPayload(
        MarketingMessageComposer.ButtonCardPayload buttonCard) {
    if (buttonCard == null) {
        return null;
    }
    return new MessageSendCommand.MessageButtonCard(
            buttonCard.title(),
            buttonCard.footer(),
            buttonCard.buttons().stream()
                    .map(button -> new MessageSendCommand.MessageButton(
                            button.type(), button.displayText(), button.value()))
                    .toList(),
            mediaPayload(buttonCard.thumbnail()));
}

private static MessageSendCommand.MessageMedia mediaPayload(
        MarketingMessageComposer.MediaPayload media) {
    if (media == null || media.bytes() == null || media.bytes().length == 0) {
        return null;
    }
    return new MessageSendCommand.MessageMedia(media.bytes(), media.mimetype());
}

private static MessageSendCommand.MessageMedia mediaPayload(byte[] bytes, String mimetype) {
    if (bytes == null || bytes.length == 0) {
        return null;
    }
    return new MessageSendCommand.MessageMedia(bytes, mimetype);
}

private static ProtocolAccountRef accountRef(MarketingTaskTarget target) {
    if (!StringUtils.hasText(target.getProtocolAccountId())
            || !StringUtils.hasText(target.getProtocolWsPhone())) {
        throw new BusinessException(
                ErrorCode.VALIDATION,
                "营销目标缺少协议账号事实: targetId=" + target.getId());
    }
    return new ProtocolAccountRef(
            target.getAccountId(),
            ProtocolBackend.fromProtocolId(target.getProtocolId()),
            target.getProtocolAccountId(),
            target.getProtocolWsPhone());
}
```

- [ ] **Step 5: 让 RoundWorker 使用工厂，不改变轮次行为**

构造器移除 `MarketingTemplateMapper`、`MarketingTemplateFileMapper`、`MarketingMessageComposer`，新增 `MarketingMessageCommandFactory`。把内部 `ResolvedMarketingTarget` 替换为公开 record，并将：

```java
messageFactory.composeTaskMessage(task)
messageFactory.newCommandId()
messageFactory.toCommand(task, sendTarget, attempt, message, notBeforeAt)
messageFactory.accountGroupSendIntervalMs(task)
messageFactory.hasLargeMediaPayload(message)
```

用于替换原私有实现。删除搬走后的死代码和 imports，不保留兼容构造器。

- [ ] **Step 6: 更新现有 worker 测试构造器并运行 GREEN**

测试 helper 统一构造：

```java
MarketingMessageCommandFactory messageFactory = new MarketingMessageCommandFactory(
        templateMapper,
        fileMapper,
        new MarketingMessageComposer());
return new MarketingRoundWorker(
        taskMapper,
        occupancyService,
        messageFactory,
        messageSendPort,
        properties,
        clock);
```

Run:

```bash
cd armada-api && mvn -q -Dtest='MarketingMessageCommandFactoryTest,MarketingRoundWorkerTest' test
```

Expected: 全部 PASS；现有 Web/Android、按钮、图片、批量和间隔断言保持不变。

- [ ] **Step 7: 提交 Task 2**

```bash
git add armada-api/src/main/java/com/armada/marketing/model/support/MarketingResolvedTarget.java \
        armada-api/src/main/java/com/armada/marketing/service/MarketingMessageCommandFactory.java \
        armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java \
        armada-api/src/test/java/com/armada/marketing/service/MarketingMessageCommandFactoryTest.java \
        armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java \
        armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerDbTest.java
git commit -m "refactor(marketing): share message command factory"
```

---

### Task 3: 用 commandId 加固普通营销结果幂等

**Files:**
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`
- Modify: `armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageSendResultReportedEvent.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplDbTest.java`
- Test: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`

- [ ] **Step 1: 写迟到 command 结果失败测试**

在 `MarketingSendResultServiceImplDbTest` 增加一个 SUBMITTED attempt，数据库保存 `command_id=cmd_current`，传入 `command_id=cmd_stale` 的成功事件，断言 attempt 仍为 0 且任务计数不变：

```java
@Test
void staleCommandResultCannotFinalizeCurrentAttempt() {
    long now = System.currentTimeMillis();
    Long taskId = insertTask("stale-command-" + now, now);
    Long targetId = insertDynamicTarget(taskId, now);
    Long groupLinkId = insertGroupLink(now);
    String groupJid = "120363stale@g.us";
    Long attemptId = insertSubmittedAttempt(
            taskId, targetId, groupLinkId, groupJid, 0L, now);

    ProtocolMessageSendResultReportedEvent stale = successEvent(
            taskId, targetId, attemptId, groupJid, 0L, now + 1_000, "cmd_stale");
    service.handleSendResultReported(stale);

    Integer status = jdbc.queryForObject(
            "SELECT status FROM marketing_task_send_attempt WHERE id = ?",
            Integer.class,
            attemptId);
    Integer sent = jdbc.queryForObject(
            "SELECT sent_message_count FROM marketing_task WHERE id = ?",
            Integer.class,
            taskId);
    assertThat(status).isZero();
    assertThat(sent).isZero();
}
```

给测试 helper 增加显式 commandId 参数，默认 helper 继续传数据库真实 commandId。

- [ ] **Step 2: 运行 DbTest 确认 RED**

```bash
cd armada-api && ./dbtest.sh 'MarketingSendResultServiceImplDbTest#staleCommandResultCannotFinalizeCurrentAttempt'
```

Expected: FAIL；旧 SQL 只按 attemptId/status 更新，错误地把迟到命令标记成功。

- [ ] **Step 3: 修改 Mapper 方法和 SQL**

在两个方法的 `attemptId` 后增加：

```java
@Param("commandId") String commandId
```

成功和失败 SQL 都增加：

```xml
AND command_id = #{commandId}
```

最终 WHERE 必须是：

```xml
WHERE id = #{attemptId}
  AND command_id = #{commandId}
  AND status = 0
```

- [ ] **Step 4: 更新所有调用方和事件文档**

结果服务调用：

```java
taskMapper.markAttemptSuccess(
        event.attemptId(), event.commandId(), event.messageId(), event.groupJid(),
        event.groupStatus(), event.groupStatusReason(), event.groupStatusCheckedAt(), resultAt);
```

失败同样在 attemptId 后传 `event.commandId()`。RoundWorker 本地拒绝使用 `attempt.getCommandId()`。把事件 Javadoc 改为“attemptId + commandId 做幂等回写”。

- [ ] **Step 5: 更新 Mockito 断言并跑 GREEN**

```bash
xmllint --noout armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml
cd armada-api && mvn -q -Dtest='MarketingSendResultServiceImplTest,MarketingRoundWorkerTest' test
cd armada-api && ./dbtest.sh 'MarketingSendResultServiceImplDbTest#staleCommandResultCannotFinalizeCurrentAttempt'
```

Expected: XML exit 0；单测和真库用例 PASS。

- [ ] **Step 6: 提交 Task 3**

```bash
git add armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java \
        armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml \
        armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java \
        armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java \
        armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageSendResultReportedEvent.java \
        armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java \
        armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplDbTest.java \
        armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java
git commit -m "fix(marketing): guard send results by command id"
```

---

### Task 4: 实现首次即时 attempt 和 outbox 入队

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/model/dto/MarketingNewGroupDTO.java`
- Create: `armada-api/src/main/java/com/armada/marketing/service/MarketingNewGroupImmediateSendService.java`
- Create: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingNewGroupImmediateSendServiceImpl.java`
- Create: `armada-api/src/test/java/com/armada/marketing/service/MarketingNewGroupImmediateSendServiceImplTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`

- [ ] **Step 1: 写即时发送服务失败单测**

覆盖三个用例：

```java
@Test
void enqueueNewGroups_createsRoundZeroAttemptsAndSpacedCommands() {
    MarketingTask task = sendingTask(42L, 1L, 77L, 750);
    MarketingTaskTarget target = dynamicTarget(501L, 42L, 5001L);
    when(mapper.selectOwnedSendingDynamicTarget(5001L, 2_000L)).thenReturn(target);
    when(mapper.selectTaskById(42L)).thenReturn(task);
    assignAttemptIds(9_000L);
    when(messageFactory.composeTaskMessage(task)).thenReturn(textMessage("hello"));
    when(messageFactory.accountGroupSendIntervalMs(task)).thenReturn(750);
    when(messagePort.enqueue(any())).thenAnswer(acceptAllCommands());

    service.enqueueNewGroups(
            5001L,
            List.of(
                    new MarketingNewGroupDTO(301L, "120363a@g.us", "群A"),
                    new MarketingNewGroupDTO(302L, "120363b@g.us", "群B")),
            2_000L);

    ArgumentCaptor<MarketingTaskSendAttempt> attemptCaptor =
            ArgumentCaptor.forClass(MarketingTaskSendAttempt.class);
    verify(mapper, times(2)).insertSendAttempt(attemptCaptor.capture());
    assertThat(attemptCaptor.getAllValues())
            .extracting(MarketingTaskSendAttempt::getRoundNo)
            .containsOnly(0L);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MessageSendCommand>> commandCaptor =
            ArgumentCaptor.forClass(List.class);
    verify(messagePort).enqueue(commandCaptor.capture());
    assertThat(commandCaptor.getValue())
            .extracting(MessageSendCommand::notBeforeAt)
            .containsExactly(2_000L, 2_750L);
}

@Test
void enqueueNewGroups_withoutOwnedSendingDynamicTargetDoesNothing() {
    when(mapper.selectOwnedSendingDynamicTarget(5001L, 2_000L)).thenReturn(null);

    service.enqueueNewGroups(
            5001L,
            List.of(new MarketingNewGroupDTO(301L, "120363a@g.us", "群A")),
            2_000L);

    verify(mapper, never()).insertSendAttempt(any());
    verify(messagePort, never()).enqueue(any());
}

@Test
void enqueueNewGroups_duplicateInitialAttemptDoesNotWriteAnotherCommand() {
    when(mapper.insertSendAttempt(any()))
            .thenThrow(new DuplicateKeyException("uq_marketing_task_attempt_group_round"));

    service.enqueueNewGroups(
            5001L,
            List.of(new MarketingNewGroupDTO(301L, "120363a@g.us", "群A")),
            2_000L);

    verify(messagePort, never()).enqueue(any());
}
```

测试 fixture 必须给 target 设置 `protocolAccountId/protocolId/protocolWsPhone`，给 task 设置 `SENDING`、模板和租户字段。

- [ ] **Step 2: 运行单测确认 RED**

```bash
cd armada-api && mvn -q -Dtest=MarketingNewGroupImmediateSendServiceImplTest test
```

Expected: 编译失败，缺少 DTO、Service 和 mapper 查询。

- [ ] **Step 3: 增加跨域 DTO 和 Service 契约**

```java
package com.armada.marketing.model.dto;

/**
 * 账号群快照中首次出现、等待即时营销的群。
 *
 * @param groupLinkId 本地群入口 ID
 * @param groupJid    WhatsApp 群 JID
 * @param groupName   群名快照
 */
public record MarketingNewGroupDTO(
        Long groupLinkId,
        String groupJid,
        String groupName
) {
}
```

```java
package com.armada.marketing.service;

import com.armada.marketing.model.dto.MarketingNewGroupDTO;
import java.util.List;

/** 发送中账号动态任务的新群首次即时营销入口。 */
public interface MarketingNewGroupImmediateSendService {
    void enqueueNewGroups(Long accountId, List<MarketingNewGroupDTO> groups, long detectedAt);
}
```

- [ ] **Step 4: 增加“账号当前占用任务的动态 target”查询**

Mapper 接口：

```java
MarketingTaskTarget selectOwnedSendingDynamicTarget(
        @Param("accountId") Long accountId,
        @Param("now") long now);
```

Mapper XML：

```xml
<select id="selectOwnedSendingDynamicTarget" resultMap="MarketingTaskTargetResultMap">
    SELECT <include refid="TargetColumns"/>
    FROM marketing_task_target t
    JOIN marketing_task mt ON mt.id = t.marketing_task_id
                          AND mt.deleted_at IS NULL
                          AND mt.status = 2
                          AND (mt.task_start_at IS NULL OR mt.task_start_at &lt;= #{now})
                          AND (mt.task_end_at IS NULL OR mt.task_end_at &gt; #{now})
    JOIN marketing_account_occupancy o ON o.account_id = t.account_id
                                      AND o.marketing_task_id = mt.id
    JOIN account a ON a.id = t.account_id
                  AND a.deleted_at IS NULL
                  AND a.protocol_account_id IS NOT NULL
                  AND TRIM(a.protocol_account_id) &lt;&gt; ''
    WHERE t.account_id = #{accountId}
      AND t.target_scope = 2
    ORDER BY t.id ASC
    LIMIT 1
</select>
```

- [ ] **Step 5: 实现即时发送核心流程**

实现类使用 `@Service` 和 `@Transactional(rollbackFor = Exception.class)`，常量固定：

```java
private static final long IMMEDIATE_ROUND_NO = 0L;
private static final int INITIAL_ATTEMPT_NO = 1;
```

公开方法按以下顺序实现：

```java
public void enqueueNewGroups(Long accountId,
                             List<MarketingNewGroupDTO> groups,
                             long detectedAt) {
    List<MarketingNewGroupDTO> candidates = normalizeGroups(groups);
    if (accountId == null || candidates.isEmpty()) {
        return;
    }
    MarketingTaskTarget target = taskMapper.selectOwnedSendingDynamicTarget(accountId, detectedAt);
    if (target == null) {
        return;
    }
    MarketingTask task = taskMapper.selectTaskById(target.getMarketingTaskId());
    if (!isSendingNow(task, detectedAt)) {
        return;
    }

    List<MarketingResolvedTarget> claimedTargets = new ArrayList<>();
    List<MarketingTaskSendAttempt> attempts = new ArrayList<>();
    for (MarketingNewGroupDTO group : candidates) {
        MarketingTaskSendAttempt attempt = immediateAttempt(task, target, group, detectedAt);
        try {
            if (taskMapper.insertSendAttempt(attempt) == 1) {
                claimedTargets.add(new MarketingResolvedTarget(
                        target, group.groupLinkId(), group.groupJid(), group.groupName()));
                attempts.add(attempt);
            }
        } catch (DuplicateKeyException duplicate) {
            log.debug("新群即时营销重复跳过 tenantId={} taskId={} accountId={} groupJid={}",
                    task.getTenantId(), task.getId(), accountId, group.groupJid());
        }
    }
    if (attempts.isEmpty()) {
        return;
    }

    MarketingMessageComposer.ComposedMessage message;
    try {
        message = messageFactory.composeTaskMessage(task);
    } catch (BusinessException ex) {
        markLocalTemplateFailures(task, attempts, ex.getMessage(), detectedAt);
        return;
    }
    enqueueClaimed(task, claimedTargets, attempts, message, detectedAt);
}
```

helper 按以下实现，确保唯一键使用 `attempt_group_key=trim(groupJid)`，而不是群名或本地 ID：

```java
private MarketingTaskSendAttempt immediateAttempt(MarketingTask task,
                                                  MarketingTaskTarget target,
                                                  MarketingNewGroupDTO group,
                                                  long detectedAt) {
    MarketingTaskSendAttempt attempt = new MarketingTaskSendAttempt();
    attempt.setTenantId(task.getTenantId());
    attempt.setMarketingTaskId(task.getId());
    attempt.setTargetId(target.getId());
    attempt.setRoundNo(IMMEDIATE_ROUND_NO);
    attempt.setAttemptNo(INITIAL_ATTEMPT_NO);
    attempt.setRetry(false);
    attempt.setGroupLinkId(group.groupLinkId());
    attempt.setGroupJid(group.groupJid());
    attempt.setGroupName(group.groupName());
    attempt.setAttemptGroupKey(group.groupJid());
    attempt.setCommandId(messageFactory.newCommandId());
    attempt.setStatus(MarketingSendAttemptStatus.SUBMITTED.code());
    attempt.setSubmittedAt(detectedAt);
    attempt.setAttemptedAt(detectedAt);
    return attempt;
}

private static List<MarketingNewGroupDTO> normalizeGroups(List<MarketingNewGroupDTO> groups) {
    if (groups == null || groups.isEmpty()) {
        return List.of();
    }
    Map<String, MarketingNewGroupDTO> unique = new LinkedHashMap<>();
    for (MarketingNewGroupDTO group : groups) {
        if (group == null || !StringUtils.hasText(group.groupJid())) {
            continue;
        }
        String jid = group.groupJid().trim();
        unique.putIfAbsent(jid,
                new MarketingNewGroupDTO(group.groupLinkId(), jid, group.groupName()));
    }
    return List.copyOf(unique.values());
}

private static boolean isSendingNow(MarketingTask task, long now) {
    return task != null
            && Integer.valueOf(MarketingTaskStatus.SENDING.code()).equals(task.getStatus())
            && (task.getTaskStartAt() == null || task.getTaskStartAt() <= now)
            && (task.getTaskEndAt() == null || task.getTaskEndAt() > now);
}
```

`enqueueClaimed` 先用 `messageFactory.accountGroupSendIntervalMs(task)` 取间隔，按 `i` 计算 `detectedAt + (long) i * intervalMs`，为每个已抢占 attempt 调用 `messageFactory.toCommand(...)`，最后一次调用 `MessageSendPort.enqueue(commands)`。结果数量或 commandId 不一致时抛 `IllegalStateException` 回滚；对每个 `accepted=false` 的返回项，以 `attemptId + commandId` 调用 `markAttemptFailed`，仅更新成功时再调用 `markTargetFailedFromAttempt`，批末按真实拒绝数调用一次 `incrementTaskSendCounters`。模板组合失败则逐条用对应 commandId 最终失败并计数；这些本地失败不改变正常轮次时间字段。

- [ ] **Step 6: 运行即时发送单测和 SQL 形状测试**

```bash
xmllint --noout armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml
cd armada-api && mvn -q -Dtest='MarketingNewGroupImmediateSendServiceImplTest,MarketingTaskMapperSqlShapeTest' test
```

Expected: PASS；SQL 只按 accountId 关联 target/occupancy，不扫描全部任务。

- [ ] **Step 7: 提交 Task 4**

```bash
git add armada-api/src/main/java/com/armada/marketing/model/dto/MarketingNewGroupDTO.java \
        armada-api/src/main/java/com/armada/marketing/service/MarketingNewGroupImmediateSendService.java \
        armada-api/src/main/java/com/armada/marketing/service/impl/MarketingNewGroupImmediateSendServiceImpl.java \
        armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java \
        armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml \
        armada-api/src/test/java/com/armada/marketing/service/MarketingNewGroupImmediateSendServiceImplTest.java \
        armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java
git commit -m "feat(marketing): enqueue immediate messages for new groups"
```

---

### Task 5: 从 account.groups_reported 事务触发即时发送

**Files:**
- Modify: `armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImplTest.java`

- [ ] **Step 1: 写 baseline 抑制和 captured 触发失败单测**

给测试新增 `MarketingNewGroupImmediateSendService` mock，并增加：

```java
@Test
void applyGroupsReported_pendingBaselineDoesNotTriggerImmediateMarketing() {
    AccountGroupBaselineRow row = baseline(10L, AccountGroupBaselineStateCode.PENDING);
    when(membershipMapper.selectAccountBaselineRow(10L)).thenReturn(row);
    when(membershipMapper.capturePendingAccountGroupBaseline(any(), anyLong(), anyLong()))
            .thenReturn(1);
    when(membershipMapper.markAccountBaselineCaptured(eq(10L), anyLong())).thenReturn(1);
    when(snapshotService.replaceVisibleGroups(anyLong(), any(), anyLong(), any(), any()))
            .thenReturn(changeSet("120363old@g.us"));

    service.applyGroupsReported(event(10L, "120363old@g.us"));

    verify(immediateSendService, never()).enqueueNewGroups(anyLong(), any(), anyLong());
}

@Test
void applyGroupsReported_capturedBaselineTriggersOnlyAddedGroups() {
    AccountGroupBaselineRow row = baseline(10L, AccountGroupBaselineStateCode.CAPTURED);
    when(membershipMapper.selectAccountBaselineRow(10L)).thenReturn(row);
    when(snapshotService.replaceVisibleGroups(anyLong(), any(), anyLong(), any(), any()))
            .thenReturn(changeSet("120363new@g.us"));

    service.applyGroupsReported(event(10L, "120363new@g.us"));

    verify(immediateSendService).enqueueNewGroups(
            eq(10L),
            argThat(groups -> groups.size() == 1
                    && "120363new@g.us".equals(groups.get(0).groupJid())),
            anyLong());
}
```

- [ ] **Step 2: 运行单测确认 RED**

```bash
cd armada-api && mvn -q -Dtest=AccountGroupMembershipReportServiceImplTest test
```

Expected: 失败；ReportService 尚未依赖即时发送 Service，也未消费变化集。

- [ ] **Step 3: 修改 ReportService 构造器和事务流程**

构造器新增 `MarketingNewGroupImmediateSendService`。在读取 baseline 状态后保留布尔值：

```java
boolean pendingBaseline = baselineState(baselineRow) == BASELINE_PENDING;
if (pendingBaseline) {
    capturePendingBaseline(event, syncAt, now);
}
AccountGroupMembershipChangeSet changes = snapshotService.replaceVisibleGroups(
        event.accountId(),
        event.groups(),
        syncAt,
        event.eventId(),
        event.source());
if (!pendingBaseline && !changes.addedGroups().isEmpty()) {
    List<MarketingNewGroupDTO> added = changes.addedGroups().stream()
            .map(group -> new MarketingNewGroupDTO(
                    group.groupLinkId(), group.groupJid(), group.groupName()))
            .toList();
    immediateSendService.enqueueNewGroups(event.accountId(), added, now);
}
```

日志增加 `addedGroups` 和有界样本，不打印模板内容。保持方法上的原事务注解，使 membership、attempt 和 outbox 同事务。

- [ ] **Step 4: 更新原测试构造器和断言并跑 GREEN**

```bash
cd armada-api && mvn -q -Dtest='AccountGroupMembershipReportServiceImplTest,AccountGroupMembershipSnapshotServiceImplTest' test
```

Expected: PASS；原 baseline 捕获断言保持不变，新测试证明首次 baseline 不触发。

- [ ] **Step 5: 提交 Task 5**

```bash
git add armada-api/src/main/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImpl.java \
        armada-api/src/test/java/com/armada/group/service/impl/AccountGroupMembershipReportServiceImplTest.java
git commit -m "feat(group): trigger marketing for newly reported groups"
```

---

### Task 6: 即时发送协议失败最多业务重试一次

**Files:**
- Create: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingImmediateRetryService.java`
- Create: `armada-api/src/test/java/com/armada/marketing/service/MarketingImmediateRetryServiceTest.java`
- Modify: `armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml`
- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplDbTest.java`

- [ ] **Step 1: 写重试资格和迟到结果失败单测**

`MarketingImmediateRetryServiceTest` 至少覆盖：

```java
@Test
void retryIfEligible_resubmitsSameAttemptWithNewCommand() {
    ProtocolMessageSendResultReportedEvent event = failedImmediateEvent("cmd_first");
    MarketingTaskSendAttempt attempt = submittedImmediateAttempt(9001L, "cmd_first", 1);
    MarketingTask task = retryEnabledSendingTask(42L);
    MarketingTaskTarget target = dynamicTarget(501L, 42L, 5001L);
    when(mapper.selectSendAttemptById(9001L)).thenReturn(attempt);
    when(mapper.selectTaskById(42L)).thenReturn(task);
    when(mapper.selectTargetById(501L)).thenReturn(target);
    when(occupancyService.loadActiveOwners(List.of(5001L)))
            .thenReturn(Map.of(5001L, owner(5001L, 42L)));
    when(mapper.selectCurrentTargetGroup(5001L, 301L)).thenReturn(group(301L));
    when(messageFactory.composeTaskMessage(task)).thenReturn(textMessage("hello"));
    when(messageFactory.newCommandId()).thenReturn("cmd_retry");
    when(mapper.resubmitImmediateAttempt(
            9001L, "cmd_first", "cmd_retry", 2_000L)).thenReturn(1);
    when(messagePort.enqueue(any())).thenReturn(accepted("cmd_retry"));

    assertThat(service.retryIfEligible(event, 2_000L)).isTrue();

    verify(mapper).resubmitImmediateAttempt(
            9001L, "cmd_first", "cmd_retry", 2_000L);
    verify(mapper).incrementTargetRetryCount(501L, 9001L, 2_000L);
}

@Test
void retryIfEligible_normalRoundNeverRetries() {
    ProtocolMessageSendResultReportedEvent event = failedEventWithRound(3L);

    assertThat(service.retryIfEligible(event, 2_000L)).isFalse();

    verifyNoInteractions(mapper, messagePort);
}

@Test
void retryIfEligible_secondImmediateAttemptNeverRetriesAgain() {
    MarketingTaskSendAttempt attempt = submittedImmediateAttempt(9001L, "cmd_retry", 2);
    when(mapper.selectSendAttemptById(9001L)).thenReturn(attempt);

    assertThat(service.retryIfEligible(failedImmediateEvent("cmd_retry"), 2_000L)).isFalse();
}
```

- [ ] **Step 2: 运行单测确认 RED**

```bash
cd armada-api && mvn -q -Dtest=MarketingImmediateRetryServiceTest test
```

Expected: 编译失败，缺少重试服务和 mapper 方法。

- [ ] **Step 3: 增加 attempt/target 查询和原子重提 SQL**

Mapper 接口新增：

```java
MarketingTaskSendAttempt selectSendAttemptById(@Param("attemptId") Long attemptId);

MarketingTaskTarget selectTargetById(@Param("targetId") Long targetId);

int resubmitImmediateAttempt(@Param("attemptId") Long attemptId,
                             @Param("expectedCommandId") String expectedCommandId,
                             @Param("newCommandId") String newCommandId,
                             @Param("submittedAt") long submittedAt);

int incrementTargetRetryCount(@Param("targetId") Long targetId,
                              @Param("attemptId") Long attemptId,
                              @Param("updatedAt") long updatedAt);
```

XML 增加完整 attempt resultMap，并实现：

```xml
<update id="resubmitImmediateAttempt">
    UPDATE marketing_task_send_attempt
    SET attempt_no = 2,
        is_retry = 1,
        command_id = #{newCommandId},
        status = 0,
        reason_code = NULL,
        reason_message = NULL,
        message_id = NULL,
        submitted_at = #{submittedAt},
        result_at = NULL,
        attempted_at = #{submittedAt}
    WHERE id = #{attemptId}
      AND round_no = 0
      AND attempt_no = 1
      AND is_retry = 0
      AND command_id = #{expectedCommandId}
      AND status = 0
</update>

<update id="incrementTargetRetryCount">
    UPDATE marketing_task_target
    SET retry_count = retry_count + 1,
        last_attempt_at = #{updatedAt},
        updated_at = #{updatedAt}
    WHERE id = #{targetId}
      AND EXISTS (
          SELECT 1
          FROM marketing_task_send_attempt a
          WHERE a.id = #{attemptId}
            AND a.target_id = marketing_task_target.id
            AND a.round_no = 0
            AND a.attempt_no = 2
      )
</update>
```

`selectTargetById` 必须复用 `TargetColumns` 并 JOIN account，确保重试命令仍使用账号当前协议事实。

- [ ] **Step 4: 实现重试资格检查和同 attempt 重提**

`retryIfEligible` 顺序固定：

```java
@Transactional(rollbackFor = Exception.class)
public boolean retryIfEligible(ProtocolMessageSendResultReportedEvent event, long resultAt) {
    if (event == null || !Long.valueOf(0L).equals(event.roundNo())) {
        return false;
    }
    MarketingTaskSendAttempt attempt = taskMapper.selectSendAttemptById(event.attemptId());
    if (!matchesFirstImmediateAttempt(attempt, event.commandId())) {
        return false;
    }
    MarketingTask task = taskMapper.selectTaskById(attempt.getMarketingTaskId());
    MarketingTaskTarget target = taskMapper.selectTargetById(attempt.getTargetId());
    if (!retryEnabledAndSending(task, target, resultAt)) {
        return false;
    }
    MarketingAccountOccupancyOwnerRow owner = occupancyService
            .loadActiveOwners(List.of(target.getAccountId()))
            .get(target.getAccountId());
    if (owner == null || !task.getId().equals(owner.getMarketingTaskId())) {
        return false;
    }
    MarketingTargetCandidateRow group = taskMapper.selectCurrentTargetGroup(
            target.getAccountId(), attempt.getGroupLinkId());
    if (group == null || !attempt.getGroupJid().equals(group.getGroupJid())) {
        return false;
    }

    MarketingMessageComposer.ComposedMessage message;
    try {
        message = messageFactory.composeTaskMessage(task);
    } catch (BusinessException ex) {
        return false;
    }
    String newCommandId = messageFactory.newCommandId();
    if (taskMapper.resubmitImmediateAttempt(
            attempt.getId(), event.commandId(), newCommandId, resultAt) != 1) {
        return false;
    }
    taskMapper.incrementTargetRetryCount(target.getId(), attempt.getId(), resultAt);
    attempt.setAttemptNo(2);
    attempt.setRetry(true);
    attempt.setCommandId(newCommandId);
    MessageSendCommand command = messageFactory.toCommand(
            task,
            new MarketingResolvedTarget(
                    target, group.getGroupLinkId(), group.getGroupJid(), group.getGroupName()),
            attempt,
            message,
            resultAt);
    return enqueueRetryOrFinalizeLocalFailure(task, target, attempt, command, resultAt);
}
```

资格 helper 必须完整实现为：

```java
private static boolean matchesFirstImmediateAttempt(MarketingTaskSendAttempt attempt,
                                                    String commandId) {
    return attempt != null
            && Integer.valueOf(MarketingSendAttemptStatus.SUBMITTED.code())
                    .equals(attempt.getStatus())
            && Long.valueOf(0L).equals(attempt.getRoundNo())
            && Integer.valueOf(1).equals(attempt.getAttemptNo())
            && !Boolean.TRUE.equals(attempt.getRetry())
            && Objects.equals(attempt.getCommandId(), commandId);
}

private static boolean retryEnabledAndSending(MarketingTask task,
                                              MarketingTaskTarget target,
                                              long now) {
    return task != null
            && target != null
            && Objects.equals(task.getId(), target.getMarketingTaskId())
            && Boolean.TRUE.equals(task.getAutoRetryEnabled())
            && task.getRetryLimit() != null
            && task.getRetryLimit() >= 1
            && Integer.valueOf(MarketingTaskStatus.SENDING.code()).equals(task.getStatus())
            && Integer.valueOf(MarketingTargetScope.ACCOUNT_DYNAMIC.code())
                    .equals(target.getTargetScope())
            && (task.getTaskStartAt() == null || task.getTaskStartAt() <= now)
            && (task.getTaskEndAt() == null || task.getTaskEndAt() > now);
}

private boolean enqueueRetryOrFinalizeLocalFailure(MarketingTask task,
                                                   MarketingTaskTarget target,
                                                   MarketingTaskSendAttempt attempt,
                                                   MessageSendCommand command,
                                                   long resultAt) {
    MessageSendEnqueueResult enqueueResult = messageSendPort.enqueue(List.of(command));
    if (enqueueResult == null || enqueueResult.items().size() != 1) {
        throw new IllegalStateException("即时营销重试入队结果数量与命令不一致");
    }
    MessageSendEnqueueItem item = enqueueResult.items().get(0);
    if (item == null || !command.commandId().equals(item.commandId())) {
        throw new IllegalStateException("即时营销重试入队结果 commandId 与命令不一致");
    }
    if (item.accepted()) {
        return true;
    }
    int updated = taskMapper.markAttemptFailed(
            attempt.getId(),
            attempt.getCommandId(),
            item.reasonCode(),
            item.reasonMessage(),
            attempt.getGroupJid(),
            null,
            null,
            null,
            resultAt);
    if (updated > 0) {
        taskMapper.markTargetFailedFromAttempt(
                target.getId(),
                attempt.getId(),
                item.reasonCode(),
                item.reasonMessage(),
                resultAt);
        taskMapper.incrementTaskSendCounters(task.getId(), 0, 1, resultAt);
    }
    return true;
}
```

端口抛异常时不捕获，让事务回滚，由 Kafka 结果消费重试。实现类 import `Objects`、`MarketingTargetScope`、attempt/task 状态枚举和两个 enqueue result 类型。

- [ ] **Step 5: 在结果服务中优先分流即时重试**

构造器新增 `MarketingImmediateRetryService`。在普通营销失败进入 `markAttemptFailed` 前增加：

```java
if (!event.success() && immediateRetryService.retryIfEligible(event, resultAt)) {
    log.info("新群即时营销失败已进入单次重试 tenantId={} taskId={} targetId={} "
                    + "attemptId={} commandId={}",
            event.tenantId(), event.marketingTaskId(), event.targetId(),
            event.attemptId(), event.commandId());
    return;
}
```

建群营销分支必须继续优先返回，普通正常轮次失败继续走原最终失败逻辑。

- [ ] **Step 6: 更新结果服务单测并运行 GREEN**

增加 mock 断言：即时失败 `retryIfEligible=true` 时不调用 `markAttemptFailed`/计数；正常轮次或重试不符合时仍最终失败。

```bash
xmllint --noout armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml
cd armada-api && mvn -q -Dtest='MarketingImmediateRetryServiceTest,MarketingSendResultServiceImplTest' test
```

Expected: PASS。

- [ ] **Step 7: 增加真库迟到结果和最多一次重试测试**

在 `MarketingSendResultServiceImplDbTest` 使用 `round_no=0`、自动重试任务和真实 membership/occupancy，第一次失败后断言：

```text
attempt 行数 = 1
attempt_no = 2
is_retry = 1
command_id != 第一次 command_id
status = 0
target.retry_count = 1
任务 failed_message_count = 0
```

再发送旧 commandId 的迟到成功结果，断言状态仍为 0；发送新 commandId 的失败结果，断言 attempt 最终为 2、任务失败数为 1，且不再改变 commandId。

Run:

```bash
cd armada-api && ./dbtest.sh 'MarketingSendResultServiceImplDbTest'
```

Expected: 全类 PASS。

- [ ] **Step 8: 提交 Task 6**

```bash
git add armada-api/src/main/java/com/armada/marketing/service/impl/MarketingImmediateRetryService.java \
        armada-api/src/main/java/com/armada/marketing/mapper/MarketingTaskMapper.java \
        armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml \
        armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java \
        armada-api/src/test/java/com/armada/marketing/service/MarketingImmediateRetryServiceTest.java \
        armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplTest.java \
        armada-api/src/test/java/com/armada/marketing/service/MarketingSendResultServiceImplDbTest.java
git commit -m "feat(marketing): retry immediate new-group send once"
```

---

### Task 7: Flyway 语义、真库端到端和数据文档

**Files:**
- Create: `armada-api/src/main/resources/db/migration/V059__marketing_new_group_immediate_round.sql`
- Create: `armada-api/src/test/java/com/armada/marketing/service/AccountDynamicNewGroupImmediateMarketingDbTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/MarketingKafkaRoundSendMigrationDbTest.java`
- Create: `.harness/changes/2026-07-20-account-dynamic-new-group-immediate-marketing/db-migrations.sql`
- Create: `.harness/changes/2026-07-20-account-dynamic-new-group-immediate-marketing/rollback.sql`
- Modify: `.harness/wiki/数据模型.md`

- [ ] **Step 1: 写迁移注释失败测试**

在 `MarketingKafkaRoundSendMigrationDbTest` 增加：

```java
@Test
void marketingAttemptRoundCommentReservesZeroForImmediateNewGroupSend() {
    String comment = jdbc.queryForObject(
            "SELECT column_comment FROM information_schema.columns "
                    + "WHERE table_schema = DATABASE() "
                    + "AND table_name = 'marketing_task_send_attempt' "
                    + "AND column_name = 'round_no'",
            String.class);
    assertThat(comment).isEqualTo("营销轮次:0=新群首次即时发送 1+=正常任务轮次");
}
```

- [ ] **Step 2: 运行测试确认 RED**

```bash
cd armada-api && ./dbtest.sh 'MarketingKafkaRoundSendMigrationDbTest#marketingAttemptRoundCommentReservesZeroForImmediateNewGroupSend'
```

Expected: FAIL，当前注释仍为正常轮次从 1 开始。

- [ ] **Step 3: 新增 V059 和前滚/回滚记录**

`V059__marketing_new_group_immediate_round.sql`：

```sql
ALTER TABLE marketing_task_send_attempt
    MODIFY COLUMN round_no BIGINT NOT NULL DEFAULT 0
    COMMENT '营销轮次:0=新群首次即时发送 1+=正常任务轮次';
```

`db-migrations.sql` 保存同一前滚语句；`rollback.sql`：

```sql
ALTER TABLE marketing_task_send_attempt
    MODIFY COLUMN round_no BIGINT NOT NULL DEFAULT 0
    COMMENT '所属营销轮次;从1开始';
```

执行前再次运行 `ls armada-api/src/main/resources/db/migration | sort -V | tail`，若 V059 已被别的会话占用，改用当时最新的下一个唯一版本，并同步计划执行记录。

- [ ] **Step 4: 写 account.groups_reported 到 outbox 的真库验收**

`AccountDynamicNewGroupImmediateMarketingDbTest` 继承 `DbTestBase`，使用真实：

- `AccountGroupMembershipReportService`
- `MarketingTaskMapper`
- `MarketingRoundWorker`
- `JdbcTemplate`

用文本模板避免媒体/Redis依赖。测试顺序：

```java
@Test
void reportedNewGroupIsSentImmediatelyOnceThenJoinsNormalRound() {
    Fixture fixture = insertOnlineAccountWithCapturedBaseline();
    reportGroups(fixture.accountId(), List.of(group("120363old@g.us", "旧群")));
    MarketingFixture marketing = insertSendingDynamicTaskAndOccupancy(
            fixture.accountId(), System.currentTimeMillis());

    reportGroups(fixture.accountId(), List.of(
            group("120363old@g.us", "旧群"),
            group("120363new@g.us", "新群")));
    reportGroups(fixture.accountId(), List.of(
            group("120363old@g.us", "旧群"),
            group("120363new@g.us", "新群")));

    assertThat(countAttempts(marketing.taskId(), 0L, "120363new@g.us"))
            .isEqualTo(1);
    assertThat(countOutboxForRound(marketing.taskId(), 0L)).isEqualTo(1);
    assertNormalScheduleUnchanged(marketing.taskId(), marketing.nextRoundAt());

    roundWorker.runRound(TEST_TENANT_ID, marketing.taskId());

    assertThat(countAttempts(marketing.taskId(), 1L, "120363new@g.us"))
            .isEqualTo(1);
}
```

`reportGroups` 构造真实 `AccountGroupsReportedEvent`；首次旧群报告发生在营销任务创建前，因此不生成即时 attempt。任务的 `account_group_send_at` 放在两次报告之间，使下一正常轮次只选择新群。

再增加两个真库用例：

- baseline 状态为 PENDING 的首次报告包含多个群，断言 `round_no=0` 为 0 行。
- 同一 groupJid 由两个各自持有动态任务的账号报告，断言两个不同 target 各有一条 `round_no=0`。

- [ ] **Step 5: 运行迁移和端到端 DbTest**

```bash
cd armada-api && ./dbtest.sh 'MarketingKafkaRoundSendMigrationDbTest'
cd armada-api && ./dbtest.sh 'AccountDynamicNewGroupImmediateMarketingDbTest'
```

Expected: PASS；真实库中 duplicate report 不重复 attempt，正常轮次字段未被即时路径改写。

- [ ] **Step 6: 从确认过的 DbTest 库重新生成数据模型 wiki**

先确认 `armada-api/.env` 指向本地/测试专用 DbTest 库，禁止对未确认共享库执行。凭据只进入进程环境，不回显：

```bash
set -a
source armada-api/.env
set +a
MODEL_DB_URL="${DB_URL#jdbc:mysql://}"
MODEL_DB_ADDRESS="${MODEL_DB_URL%%/*}"
MODEL_DB_NAME_QUERY="${MODEL_DB_URL#*/}"
MODEL_DB_NAME="${MODEL_DB_NAME_QUERY%%\?*}"
MODEL_DB_HOST="${MODEL_DB_ADDRESS%%:*}"
MODEL_DB_PORT="${MODEL_DB_ADDRESS##*:}"
if [[ "$MODEL_DB_PORT" == "$MODEL_DB_ADDRESS" ]]; then MODEL_DB_PORT=3306; fi
export MYSQL_PWD="$DB_PASSWORD"

mysql -N -B -h "$MODEL_DB_HOST" -P "$MODEL_DB_PORT" -u "$DB_USER" "$MODEL_DB_NAME" \
  -e "SELECT TABLE_SCHEMA,TABLE_NAME,ORDINAL_POSITION,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,IFNULL(COLUMN_DEFAULT,'__NULL__'),COLUMN_KEY,EXTRA,REPLACE(REPLACE(COLUMN_COMMENT,CHAR(9),' '),CHAR(10),' ') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION" \
  > /tmp/wheel_columns.tsv
mysql -N -B -h "$MODEL_DB_HOST" -P "$MODEL_DB_PORT" -u "$DB_USER" "$MODEL_DB_NAME" \
  -e "SELECT TABLE_SCHEMA,TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,IFNULL(COLUMN_NAME,''),INDEX_TYPE FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX" \
  > /tmp/wheel_indexes.tsv
mysql -N -B -h "$MODEL_DB_HOST" -P "$MODEL_DB_PORT" -u "$DB_USER" "$MODEL_DB_NAME" \
  -e "SELECT TABLE_SCHEMA,TABLE_NAME,REPLACE(REPLACE(TABLE_COMMENT,CHAR(9),' '),CHAR(10),' ') FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME" \
  > /tmp/wheel_tables.tsv
unset MYSQL_PWD

python3 .harness/wiki/gen_datamodel.py
cp /tmp/datamodel_tables.md .harness/wiki/数据模型.md
```

Expected: `marketing_task_send_attempt.round_no` 描述为 `营销轮次:0=新群首次即时发送 1+=正常任务轮次`，其余表结构没有无关变化。

- [ ] **Step 7: 提交 Task 7**

```bash
git add armada-api/src/main/resources/db/migration/V059__marketing_new_group_immediate_round.sql \
        armada-api/src/test/java/com/armada/marketing/MarketingKafkaRoundSendMigrationDbTest.java \
        armada-api/src/test/java/com/armada/marketing/service/AccountDynamicNewGroupImmediateMarketingDbTest.java \
        .harness/changes/2026-07-20-account-dynamic-new-group-immediate-marketing/db-migrations.sql \
        .harness/changes/2026-07-20-account-dynamic-new-group-immediate-marketing/rollback.sql \
        .harness/wiki/数据模型.md
git commit -m "test(marketing): verify immediate new-group flow"
```

---

### Task 8: 全量验证、变更记录和专家评审

**Files:**
- Modify: `.harness/changes/2026-07-20-account-dynamic-new-group-immediate-marketing.md`
- Verify: all files changed by Tasks 1-7

- [ ] **Step 1: 校验全部 Mapper XML**

```bash
xmllint --noout armada-api/src/main/resources/mapper/group/AccountGroupMembershipMapper.xml
xmllint --noout armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml
```

Expected: 两条命令 exit 0。

- [ ] **Step 2: 跑 focused 单测**

```bash
cd armada-api && mvn -q -Dtest='AccountGroupMembershipSnapshotServiceImplTest,AccountGroupMembershipReportServiceImplTest,MarketingMessageCommandFactoryTest,MarketingRoundWorkerTest,MarketingNewGroupImmediateSendServiceImplTest,MarketingImmediateRetryServiceTest,MarketingSendResultServiceImplTest,MarketingTaskMapperSqlShapeTest' test
```

Expected: BUILD SUCCESS，0 failures，0 errors。

- [ ] **Step 3: 跑 focused 真库 DbTest**

```bash
cd armada-api && ./dbtest.sh 'MarketingKafkaRoundSendMigrationDbTest'
cd armada-api && ./dbtest.sh 'AccountDynamicNewGroupImmediateMarketingDbTest'
cd armada-api && ./dbtest.sh 'MarketingRoundWorkerDbTest'
cd armada-api && ./dbtest.sh 'MarketingSendResultServiceImplDbTest'
```

Expected: 四个测试类全部 PASS。若 `.env` 缺失或数据库目标未确认，停止并明确记录未执行，不能把跳过写成通过。

- [ ] **Step 4: 跑完整 Maven 测试**

```bash
cd armada-api && mvn test
```

Expected: BUILD SUCCESS。若存在与本任务无关的既有失败，保存完整类名和失败输出，并证明 focused tests 仍通过。

- [ ] **Step 5: 检查差异和禁止项**

```bash
git diff --check
git status --short
git diff --stat origin/1.0.1-snapshot...HEAD
rg -n "TO[D]O|FIX[M]E|System\.out\.println|printStackTrace" \
  armada-api/src/main/java/com/armada/group \
  armada-api/src/main/java/com/armada/marketing
```

Expected: `git diff --check` exit 0；没有任务引入的新占位符、控制台输出、协议层或前端修改；不包含当前主 worktree 的部署在途文件。

- [ ] **Step 6: 更新变更记录真实证据**

在 `.harness/changes/2026-07-20-account-dynamic-new-group-immediate-marketing.md`：

- 勾选实际完成任务。
- 记录每条验证命令、退出码和测试数量。
- 记录 V059 实际版本号、数据库目标和 wiki 生成结果。
- API、Redis、协议契约变更明确写“无”。
- 部署仍未执行时保持“尚未部署”，不得预填成功。

- [ ] **Step 7: 使用 expert-reviewer 做后端专家评审**

评审重点：

- 群域只调用营销 Service，不跨域访问 mapper/entity。
- baseline 首次报告不会触发群轰炸。
- attempt 唯一键确实覆盖 task + account target + groupJid + round0。
- membership、attempt、outbox 同事务，afterCommit 才发 Kafka。
- commandId 能拦截重试前命令的迟到结果。
- 即时路径没有更新任何正常轮次字段。
- 单群协议失败不会阻断同批其他命令。

Expected: 无 🔴 问题；所有 🟡 问题处理或记录明确取舍。

- [ ] **Step 8: 提交验证记录**

```bash
git add .harness/changes/2026-07-20-account-dynamic-new-group-immediate-marketing.md
git commit -m "docs: record immediate new-group marketing verification"
```

执行完毕后使用 `verification-before-completion` 再核对真实输出，再进入 `finishing-a-development-branch`；未经用户确认目标环境，不执行部署、SSH 或远程数据操作。
