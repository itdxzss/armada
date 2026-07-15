# Armada Marketing Message Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让普通营销与建群营销通过统一消息端口把 Web/Android 账号命令写入各自 Kafka topic，并在 Armada 本地拒绝不符合 Android 单跳转按钮规则的目标。

**Architecture:** 营销业务只构造包含 `ProtocolAccountRef` 的 `MessageSendCommand`，`RoutingMessageSendPort` 根据账号 backend 分组后调用 `WebMessageSendBackend` 或 `AndroidMessageSendBackend`。两个 backend 负责协议能力校验和 wire payload，`ProtocolCommandOutboxService` 只持久化 backend 已编码好的 outbox 命令；Web payload 保持不变，Android payload额外显式携带 `wsPhone`。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis、MySQL、Kafka outbox、JUnit 5、AssertJ、Mockito

---

## 0. 执行边界与文件结构

本计划只修改 `/Users/daishuaishuai/IdeaProjects/armada`。Android Zhuan 的命令消费和原生发送由配套计划
`docs/superpowers/plans/2026-07-15-android-zhuan-marketing-message-implementation.md` 实现。

新增文件及职责：

- `armada-api/src/main/java/com/armada/platform/protocol/model/enums/MessageType.java`：统一五种消息类型。
- `armada-api/src/main/java/com/armada/platform/protocol/model/command/MessageSendCommand.java`：业务无关的统一消息命令及嵌套内容模型。
- `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolMessageOutboxCommand.java`：backend 已选择 topic/backend 并编码 payload 后交给 outbox 的内部命令。
- `armada-api/src/main/java/com/armada/platform/protocol/model/result/MessageSendEnqueueItem.java`：单条命令接受/拒绝结果。
- `armada-api/src/main/java/com/armada/platform/protocol/model/result/MessageSendEnqueueResult.java`：批量逐命令结果。
- `armada-api/src/main/java/com/armada/platform/protocol/port/MessageSendPort.java`：营销域唯一可见的发送端口。
- `armada-api/src/main/java/com/armada/platform/protocol/routing/MessageSendBackend.java`：单协议消息 backend SPI。
- `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingMessageSendPort.java`：按 `ProtocolAccountRef.backend()` 路由并合并结果。
- `armada-api/src/main/java/com/armada/platform/protocol/backend/web/WebMessageSendBackend.java`：保持现有 Web topic 与 payload。
- `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java`：Android topic、`wsPhone` payload 与单链接按钮校验。

删除文件：

- `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolMarketingMessageCommandRequest.java`：所有调用迁到统一命令后删除，不保留兼容构造器。

修改文件及职责：

- `ProtocolCommandOutboxService.java` / `ProtocolCommandOutboxServiceImpl.java`：从 Web 专用营销 request 改为持久化 `ProtocolMessageOutboxCommand`。
- `ProtocolConfiguration.java`：显式装配两个 backend 和 routing port。
- `MarketingTaskTarget.java` / `MarketingTaskMapper.xml`：联表读取当前 `protocol_id/protocol_account_id/ws_phone`。
- `GroupCreationMarketingAccountCandidate.java` / `GroupCreationMarketingTaskMapper.xml`：账号候选增加当前 `protocol_id`。
- `MarketingRoundWorker.java`：只依赖 `MessageSendPort`，逐条收敛本地拒绝结果。
- `GroupCreationMarketingWorker.java`：只依赖 `MessageSendPort`，拒绝结果不写 outbox 并把 item 置失败。

## Task 1: 建立统一消息模型和 routing port

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/enums/MessageType.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/command/MessageSendCommand.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/result/MessageSendEnqueueItem.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/result/MessageSendEnqueueResult.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/port/MessageSendPort.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/MessageSendBackend.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingMessageSendPort.java`
- Test: `armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingMessageSendPortTest.java`

- [ ] **Step 1: 写 routing 失败测试**

测试固定以下行为：混合命令按 backend 分组、返回顺序恢复为输入顺序、某 backend 的拒绝不影响另一 backend、重复 backend 构造失败、缺失 backend 返回 `UNSUPPORTED_BACKEND`。

```java
@Test
void routesMixedCommandsAndPreservesInputOrder() {
    RecordingBackend web = new RecordingBackend(ProtocolBackend.WEB, Set.of());
    RecordingBackend android = new RecordingBackend(
            ProtocolBackend.ANDROID,
            Set.of("cmd_android_rejected"));
    RoutingMessageSendPort port = new RoutingMessageSendPort(List.of(web, android));

    MessageSendEnqueueResult result = port.enqueue(List.of(
            command("cmd_web", ProtocolBackend.WEB),
            command("cmd_android_rejected", ProtocolBackend.ANDROID),
            command("cmd_android", ProtocolBackend.ANDROID)));

    assertThat(web.commandIds()).containsExactly("cmd_web");
    assertThat(android.commandIds()).containsExactly("cmd_android_rejected", "cmd_android");
    assertThat(result.items()).extracting(MessageSendEnqueueItem::commandId)
            .containsExactly("cmd_web", "cmd_android_rejected", "cmd_android");
    assertThat(result.items()).extracting(MessageSendEnqueueItem::accepted)
            .containsExactly(true, false, true);
}

@Test
void rejectsDuplicateBackendRegistration() {
    assertThatThrownBy(() -> new RoutingMessageSendPort(List.of(
            new RecordingBackend(ProtocolBackend.WEB, Set.of()),
            new RecordingBackend(ProtocolBackend.WEB, Set.of()))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("重复的消息发送协议后端");
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd armada-api
mvn -Dtest=RoutingMessageSendPortTest test
```

Expected: FAIL，编译器报告 `MessageSendPort`、`RoutingMessageSendPort` 等类型不存在。

- [ ] **Step 3: 实现统一类型**

`MessageSendCommand` 使用嵌套 record 控制职责和参数数量：

```java
public record MessageSendCommand(
        ProtocolAccountRef account,
        MessageTarget target,
        MessagePayload payload,
        MessageCorrelation correlation,
        String commandId
) {
    public record MessageTarget(String groupJid) {
    }

    public record MessagePayload(MessageType type, MessageContent content, boolean mentionAll) {
    }

    public record MessageContent(
            String text,
            MessageMedia image,
            MessageLinkCard linkCard,
            MessageButtonCard buttonCard
    ) {
    }

    public record MessageMedia(byte[] bytes, String mimetype) {
    }

    public record MessageLinkCard(String url, String title, String description, MessageMedia thumbnail) {
    }

    public record MessageButtonCard(
            String title,
            String footer,
            List<MessageButton> buttons,
            MessageMedia thumbnail
    ) {
    }

    public record MessageButton(String type, String displayText, String value) {
    }

    public record MessageCorrelation(
            Long tenantId,
            String source,
            MarketingCorrelation marketing,
            GroupCreationCorrelation groupCreation
    ) {
    }

    public record MarketingCorrelation(Long taskId, Long targetId, Long attemptId, Long roundNo) {
    }

    public record GroupCreationCorrelation(Long taskId, Long itemId) {
    }
}
```

结果模型只允许通过工厂构造接受/拒绝结果：

```java
public record MessageSendEnqueueItem(
        String commandId,
        boolean accepted,
        String reasonCode,
        String reasonMessage
) {
    public static MessageSendEnqueueItem accepted(String commandId) {
        return new MessageSendEnqueueItem(commandId, true, null, null);
    }

    public static MessageSendEnqueueItem rejected(
            String commandId,
            String reasonCode,
            String reasonMessage) {
        return new MessageSendEnqueueItem(commandId, false, reasonCode, reasonMessage);
    }
}
```

`RoutingMessageSendPort.enqueue` 使用 `EnumMap<ProtocolBackend, List<MessageSendCommand>>` 分组，backend 返回后按 `commandId` 建索引，最后严格按输入命令恢复顺序；backend 少返回、多返回或重复返回 commandId 时抛 `IllegalStateException`，避免 attempt 永久停在 SUBMITTED。

- [ ] **Step 4: 运行 routing 测试确认通过**

Run:

```bash
cd armada-api
mvn -Dtest=RoutingMessageSendPortTest test
```

Expected: PASS，0 failures，0 errors。

- [ ] **Step 5: 提交统一端口**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/model/enums/MessageType.java \
  armada-api/src/main/java/com/armada/platform/protocol/model/command/MessageSendCommand.java \
  armada-api/src/main/java/com/armada/platform/protocol/model/result/MessageSendEnqueueItem.java \
  armada-api/src/main/java/com/armada/platform/protocol/model/result/MessageSendEnqueueResult.java \
  armada-api/src/main/java/com/armada/platform/protocol/port/MessageSendPort.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/MessageSendBackend.java \
  armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingMessageSendPort.java \
  armada-api/src/test/java/com/armada/platform/protocol/routing/RoutingMessageSendPortTest.java
git commit -m "feat: add routed marketing message port"
```

## Task 2: 把 outbox 改成 backend 已编码命令并接入 Web backend

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolMessageOutboxCommand.java`
- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/web/WebMessageSendBackend.java`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/ProtocolCommandOutboxService.java:56-67`
- Modify: `armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java:225-260,423-455,558-590,755-850`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java:432-625`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/web/WebMessageSendBackendTest.java`

- [ ] **Step 1: 写 Web payload 和通用 outbox 失败测试**

`WebMessageSendBackendTest` 捕获 `ProtocolMessageOutboxCommand` 并断言：

```java
assertThat(outboxCommand.backend()).isEqualTo(ProtocolBackend.WEB);
assertThat(outboxCommand.kafkaTopic()).isEqualTo("protocol.master.commands.v1");
assertThat(outboxCommand.kafkaKey()).isEqualTo("acc_web");
assertThat(json(outboxCommand.payload()))
        .containsEntry("messageType", "BUTTON_CARD")
        .containsEntry("mentionAll", true)
        .doesNotContainKey("wsPhone");
```

`ProtocolCommandOutboxServiceImplTest` 改为直接传：

```java
ProtocolMessageOutboxCommand outboxCommand = new ProtocolMessageOutboxCommand(
        command,
        ProtocolBackend.WEB,
        ProtocolMasterCommandProperties.DEFAULT_TOPIC,
        "acc_web",
        Map.of(
                "tenantId", 7L,
                "accountId", 100L,
                "protocolAccountId", "acc_web",
                "groupJid", "120363001@g.us",
                "messageType", "TEXT",
                "text", "hello",
                "mentionAll", false,
                "source", "marketing_task"));
```

并断言 outbox 行的 aggregate、topic、key、backend、payload 与现有 Web 契约一致。

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd armada-api
mvn -Dtest=WebMessageSendBackendTest,ProtocolCommandOutboxServiceImplTest test
```

Expected: FAIL，`ProtocolMessageOutboxCommand` 与 `WebMessageSendBackend` 尚不存在。

- [ ] **Step 3: 实现 backend 到 outbox 的内部命令**

```java
public record ProtocolMessageOutboxCommand(
        MessageSendCommand command,
        ProtocolBackend backend,
        String kafkaTopic,
        String kafkaKey,
        Object payload
) {
}
```

把 service 方法替换为：

```java
ProtocolCommandOutboxEnqueueResult enqueueMessageCommands(
        List<ProtocolMessageOutboxCommand> commands);
```

`ProtocolCommandOutboxServiceImpl` 保留现有 `commandId/batchId/aggregate/status/afterCommit` 逻辑；topic、key、backend 和 payload 分别取 `ProtocolMessageOutboxCommand`。公共校验必须验证：批次 1–500、command/account/target/payload 非空、commandId 唯一、普通营销四个关联 ID 完整、建群营销 task/item ID 完整。

- [ ] **Step 4: 实现 Web adapter 的现有 wire payload**

`WebMessageSendBackend` 只依赖 outbox service 和 `ProtocolMasterCommandProperties`，把 byte[] 媒体编码为 Base64。private wire record 使用现有字段名：

```java
private record WebMessagePayload(
        Long tenantId,
        Long marketingTaskId,
        Long attemptId,
        Long targetId,
        Long roundNo,
        Long accountId,
        String protocolAccountId,
        String groupJid,
        String messageType,
        String text,
        WebMediaPayload image,
        WebLinkCardPayload linkCard,
        WebButtonCardPayload buttonCard,
        boolean mentionAll,
        String source,
        Long groupCreationTaskId,
        Long groupCreationItemId
) {
}
```

`enqueue` 一次写入全部 Web 命令，成功后按输入返回 `MessageSendEnqueueItem.accepted(commandId)`；数据库、序列化或重复键异常继续抛出并回滚。

- [ ] **Step 5: 运行 Web/outbox 测试确认通过**

Run:

```bash
cd armada-api
mvn -Dtest=WebMessageSendBackendTest,ProtocolCommandOutboxServiceImplTest test
```

Expected: PASS，Web payload 不包含 `wsPhone`，topic 仍为 `protocol.master.commands.v1`。

- [ ] **Step 6: 提交 Web backend 和通用 outbox**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolMessageOutboxCommand.java \
  armada-api/src/main/java/com/armada/platform/protocol/backend/web/WebMessageSendBackend.java \
  armada-api/src/main/java/com/armada/platform/protocol/service/ProtocolCommandOutboxService.java \
  armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/web/WebMessageSendBackendTest.java \
  armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java
git commit -m "refactor: route web messages through message backend"
```

## Task 3: 实现 Android backend 和单跳转按钮本地校验

**Files:**

- Create: `armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java`
- Create: `armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackendTest.java`

- [ ] **Step 1: 写 Android 按钮矩阵失败测试**

使用参数化测试覆盖：0 个按钮、2 个按钮、`copy`、`quick`、空显示文字、`ftp://`、相对 URL 均拒绝；一个非空 `link` + HTTP(S) URL 接受。

```java
@ParameterizedTest
@MethodSource("invalidButtonCards")
void rejectsInvalidAndroidButtonCards(
        MessageSendCommand.MessageButtonCard card,
        String expectedMessage) {
    MessageSendCommand command = androidButtonCommand("cmd_bad", card);

    MessageSendEnqueueResult result = backend.enqueue(List.of(command));

    assertThat(result.items()).singleElement().satisfies(item -> {
        assertThat(item.accepted()).isFalse();
        assertThat(item.reasonCode()).isEqualTo("INVALID_ANDROID_BUTTON_CONFIG");
        assertThat(item.reasonMessage()).contains(expectedMessage);
    });
    verify(outboxService, never()).enqueueMessageCommands(anyList());
}
```

再写混合批次测试：一个非法按钮命令和一个 TEXT 命令同时进入 backend，只有 TEXT 写 outbox。

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd armada-api
mvn -Dtest=AndroidMessageSendBackendTest test
```

Expected: FAIL，`AndroidMessageSendBackend` 不存在。

- [ ] **Step 3: 实现 Android 校验和 payload**

按钮校验集中在 backend：

```java
private MessageSendEnqueueItem validateButtonCard(MessageSendCommand command) {
    MessageSendCommand.MessageButtonCard card = command.payload().content().buttonCard();
    if (card == null || card.buttons() == null || card.buttons().size() != 1) {
        return MessageSendEnqueueItem.rejected(
                command.commandId(), "INVALID_ANDROID_BUTTON_CONFIG", "按钮数量只支持 1 个");
    }
    MessageSendCommand.MessageButton button = card.buttons().get(0);
    if (button == null || !"link".equalsIgnoreCase(button.type())) {
        return MessageSendEnqueueItem.rejected(
                command.commandId(), "INVALID_ANDROID_BUTTON_CONFIG", "只支持跳转链接按钮");
    }
    if (!StringUtils.hasText(button.displayText())) {
        return MessageSendEnqueueItem.rejected(
                command.commandId(), "INVALID_ANDROID_BUTTON_CONFIG", "按钮显示文字不能为空");
    }
    if (!HttpUrlValidator.isHttpUrl(button.value())) {
        return MessageSendEnqueueItem.rejected(
                command.commandId(), "INVALID_ANDROID_BUTTON_CONFIG", "只接受有效的 HTTP(S) 跳转链接");
    }
    return MessageSendEnqueueItem.accepted(command.commandId());
}
```

Android wire record 与 Web 字段独立，显式包含：

```java
private record AndroidMessagePayload(
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String wsPhone,
        String groupJid,
        String messageType,
        String text,
        AndroidMediaPayload image,
        AndroidLinkCardPayload linkCard,
        AndroidButtonCardPayload buttonCard,
        boolean mentionAll,
        String source,
        Long marketingTaskId,
        Long targetId,
        Long attemptId,
        Long roundNo,
        Long groupCreationTaskId,
        Long groupCreationItemId
) {
}
```

关联字段按现有 Web/Armada consumer 契约直接扁平输出，不使用 `@JsonUnwrapped` 或嵌套 correlation 对象。禁止从 `protocolAccountId` 截取手机号。合法命令写 `ProtocolAndroidCommandProperties.getTopic()`，backend 为 `ANDROID`。

- [ ] **Step 4: 运行 Android backend 测试确认通过**

Run:

```bash
cd armada-api
mvn -Dtest=AndroidMessageSendBackendTest test
```

Expected: PASS；非法按钮不调用 outbox，合法 payload 包含显式 `wsPhone`、单按钮原文案和 URL。

- [ ] **Step 5: 提交 Android backend**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java \
  armada-api/src/test/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackendTest.java
git commit -m "feat: add android marketing message backend"
```

## Task 4: 装配 routing 并从账号表读取当前协议事实

**Files:**

- Modify: `armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java:1-330`
- Modify: `armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java:1-90`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTaskTarget.java:1-180`
- Modify: `armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml:42-75,145-152`
- Modify: `armada-api/src/main/java/com/armada/marketing/model/vo/GroupCreationMarketingAccountCandidate.java:1-85`
- Modify: `armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml:172-235,296-312`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapperSqlShapeTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapperDbTest.java`

- [ ] **Step 1: 写 Spring 与 SQL 失败测试**

`ProtocolConfigurationTest` 增加：

```java
assertThat(context).hasSingleBean(MessageSendPort.class);
assertThat(context.getBeansOfType(MessageSendBackend.class))
        .containsKeys("webMessageSendBackend", "androidMessageSendBackend");
```

SQL shape tests 断言普通目标和所有建群候选查询都包含：

```java
assertThat(targetSql)
        .contains("a.protocol_id AS protocolId")
        .contains("a.protocol_account_id AS protocolAccountId")
        .contains("a.ws_phone AS protocolWsPhone");
assertThat(candidateSql).contains("a.protocol_id AS protocolId");
```

DbTest 插入 `protocol_id='ANDROID'` 的账号后，断言 `selectAccountCandidateByAccountId` 返回 `protocolId=ANDROID` 和真实 `accountPhone`。

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd armada-api
mvn -Dtest=ProtocolConfigurationTest,MarketingTaskMapperSqlShapeTest,GroupCreationMarketingTaskMapperSqlShapeTest test
```

Expected: FAIL，Bean 和 SQL 字段尚未加入。

- [ ] **Step 3: 实现 Spring 显式装配**

在 `ProtocolConfiguration` 增加三个 bean：

```java
@Bean
public MessageSendBackend webMessageSendBackend(
        ProtocolCommandOutboxService outboxService,
        ProtocolMasterCommandProperties properties) {
    return new WebMessageSendBackend(outboxService, properties);
}

@Bean
public MessageSendBackend androidMessageSendBackend(
        ProtocolCommandOutboxService outboxService,
        ProtocolAndroidCommandProperties properties) {
    return new AndroidMessageSendBackend(outboxService, properties);
}

@Bean
public MessageSendPort messageSendPort(List<MessageSendBackend> backends) {
    return new RoutingMessageSendPort(backends);
}
```

同时补齐新增 bean 的 import/Javadoc，不创建第二个 routing 实例。

- [ ] **Step 4: 映射账号当前协议事实**

`MarketingTaskTarget` 增加非表快照字段 `protocolId`、`protocolWsPhone`。`TargetColumns` 使用账号联表事实：

```xml
a.protocol_id AS protocol_id,
a.protocol_account_id AS protocol_account_id,
a.ws_phone AS protocol_ws_phone
```

resultMap 分别映射 `protocolId/protocolAccountId/protocolWsPhone`。`GroupCreationMarketingAccountCandidate` 增加 `protocolId`，四个候选查询都选择 `a.protocol_id AS protocolId`。不新增数据库列或 Flyway。

- [ ] **Step 5: 运行普通测试确认通过**

Run:

```bash
cd armada-api
mvn -Dtest=ProtocolConfigurationTest,MarketingTaskMapperSqlShapeTest,GroupCreationMarketingTaskMapperSqlShapeTest test
```

Expected: PASS。

- [ ] **Step 6: 经用户确认 `.env` 目标后运行真库 DbTest**

Run only after confirming `armada-api/.env` points to an allowed database:

```bash
cd armada-api
./dbtest.sh 'GroupCreationMarketingTaskMapperDbTest#accountCandidateCarriesCurrentProtocolBackend'
```

Expected: PASS，测试真实执行且没有被跳过。若 `.env` 未配置或环境未确认，记录为未执行，不能用 `mvn test` 代替。

- [ ] **Step 7: 提交装配和协议事实映射**

```bash
git add armada-api/src/main/java/com/armada/platform/protocol/config/ProtocolConfiguration.java \
  armada-api/src/test/java/com/armada/platform/protocol/config/ProtocolConfigurationTest.java \
  armada-api/src/main/java/com/armada/marketing/model/entity/MarketingTaskTarget.java \
  armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml \
  armada-api/src/main/java/com/armada/marketing/model/vo/GroupCreationMarketingAccountCandidate.java \
  armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml \
  armada-api/src/test/java/com/armada/marketing/mapper/MarketingTaskMapperSqlShapeTest.java \
  armada-api/src/test/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapperSqlShapeTest.java \
  armada-api/src/test/java/com/armada/marketing/mapper/GroupCreationMarketingTaskMapperDbTest.java
git commit -m "feat: resolve marketing account protocol backend"
```

## Task 5: 普通营销 Worker 迁到统一端口并收敛逐目标拒绝

**Files:**

- Modify: `armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java:1-585`
- Modify: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`
- Modify: `armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerDbTest.java`

- [ ] **Step 1: 把 Worker 测试依赖替换为 `MessageSendPort` 并写混合目标失败测试**

测试准备一个 Web target 和一个 Android target，模板包含两个按钮。fake port 模拟 routing 结果：Web 接受、Android 返回 `INVALID_ANDROID_BUTTON_CONFIG`。

```java
when(messageSendPort.enqueue(anyList())).thenAnswer(invocation -> {
    List<MessageSendCommand> commands = invocation.getArgument(0);
    return new MessageSendEnqueueResult(commands.stream()
            .map(command -> command.account().backend() == ProtocolBackend.WEB
                    ? MessageSendEnqueueItem.accepted(command.commandId())
                    : MessageSendEnqueueItem.rejected(
                            command.commandId(),
                            "INVALID_ANDROID_BUTTON_CONFIG",
                            "按钮数量只支持 1 个"))
            .toList());
});
```

断言两条 attempt 均先写入，Android attempt 随后变为失败、target/任务失败计数递增一次，Web attempt 保持 SUBMITTED；传入 port 的 Web/Android `ProtocolAccountRef` 分别来自 `protocolId/protocolAccountId/protocolWsPhone`。

- [ ] **Step 2: 运行 Worker 测试并确认失败**

Run:

```bash
cd armada-api
mvn -Dtest=MarketingRoundWorkerTest test
```

Expected: FAIL，Worker 仍依赖 `ProtocolCommandOutboxService`。

- [ ] **Step 3: 构造统一命令并删除协议分支**

新增 helper：

```java
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
            target.getProtocolAccountId().trim(),
            target.getProtocolWsPhone().trim());
}
```

`toMessageSendCommand` 把 composer 输出转成 `MessagePayload/MessageContent/MessageCorrelation`。Worker 不读取 backend 做按钮判断，也不选择 topic。

- [ ] **Step 4: 逐命令处理拒绝结果**

每批调用 `messageSendPort.enqueue(batch)` 后校验结果 commandId 完整。对 `accepted=false`：

```java
int updated = taskMapper.markAttemptFailed(
        attempt.getId(),
        item.reasonCode(),
        item.reasonMessage(),
        attempt.getGroupJid(),
        null,
        null,
        null,
        resultAt);
if (updated > 0) {
    taskMapper.markTargetFailedFromAttempt(
            attempt.getTargetId(),
            attempt.getId(),
            item.reasonCode(),
            item.reasonMessage(),
            resultAt);
    rejectedCount++;
}
```

所有批次完成后以一次 `incrementTaskSendCounters(taskId, 0, rejectedCount, resultAt)` 更新任务。日志输出 accepted/rejected 数量，不打印正文、Base64、完整手机号或群 JID。

- [ ] **Step 5: 更新 DbTest 记录端口和账号 fixture**

`MarketingRoundWorkerDbTest` 的 recording double 改为 `MessageSendPort`，返回全部接受。fixture 必须真实插入 `account` 行并设置 `protocol_id/protocol_account_id/ws_phone`，不再依赖 `acc_ + accountPhone` 回退。

- [ ] **Step 6: 运行 Worker 普通测试确认通过**

Run:

```bash
cd armada-api
mvn -Dtest=MarketingRoundWorkerTest test
```

Expected: PASS，混合 Web/Android 测试中只有 Android 非法目标本地失败。

- [ ] **Step 7: 经数据库目标确认后运行 Worker DbTest**

```bash
cd armada-api
./dbtest.sh 'MarketingRoundWorkerDbTest#dueRoundGeneratesOneAttemptAndOneOutboxCommandPerTargetInChunks'
```

Expected: PASS，1000 条 attempt 和两批 500 条统一命令真实生成。

- [ ] **Step 8: 提交普通营销接入**

```bash
git add armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java \
  armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java \
  armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerDbTest.java
git commit -m "feat: route marketing rounds by protocol backend"
```

## Task 6: 建群营销发送交接迁到统一端口

**Files:**

- Modify: `armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java:1-545`
- Modify: `armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java`

- [ ] **Step 1: 写建群营销 Android account ref 和本地拒绝失败测试**

第一条测试断言候选账号 `protocolId=ANDROID` 时，发给 port 的 command account 为：

```java
assertThat(command.account()).isEqualTo(new ProtocolAccountRef(
        accountId,
        ProtocolBackend.ANDROID,
        "acc_android",
        "919000000001"));
```

第二条测试让 port 返回 `INVALID_ANDROID_BUTTON_CONFIG`，断言：

- item 最终调用 `markItemMarketingSending` 保存已创建 `groupJid/commandId`；
- 同一事务随后调用 `markItemFailedByCommandId`；
- 不把 item 留在 MARKETING_SENDING；
- 不触发账号换号重试。

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd armada-api
mvn -Dtest=GroupCreationMarketingWorkerTest test
```

Expected: FAIL，Worker 仍调用 Web 专用 outbox service。

- [ ] **Step 3: 注入 `MessageSendPort` 并使用当前候选账号事实**

把 `enqueueMarketingCommand` 参数改为包含 `GroupCreationMarketingAccountCandidate account`，构造：

```java
ProtocolAccountRef accountRef = new ProtocolAccountRef(
        account.getAccountId(),
        ProtocolBackend.fromProtocolId(account.getProtocolId()),
        account.getProtocolAccountId(),
        account.getAccountPhone());
```

命令 correlation 使用：

```java
new MessageSendCommand.MessageCorrelation(
        tenantId,
        SOURCE_GROUP_CREATION_MARKETING,
        null,
        new MessageSendCommand.GroupCreationCorrelation(taskId, item.getId()))
```

- [ ] **Step 4: 原子处理单条接受/拒绝结果**

先调用 port，严格要求只返回当前 commandId。无论接受或拒绝都调用现有 `markItemMarketingSending` 保存 group/command 快照；拒绝时紧接着调用：

```java
int failed = groupCreationMapper.markItemFailedByCommandId(
        item.getId(),
        commandId,
        result.reasonCode(),
        result.reasonMessage(),
        System.currentTimeMillis());
if (failed == 0) {
    throw new BusinessException(
            ErrorCode.CONFLICT,
            "建群营销执行项本地发送失败状态写入冲突: " + item.getId());
}
```

该事务没有 Android outbox 行；失败 item 保留已创建群和命令审计信息，但不会以换号方式重复建群。

- [ ] **Step 5: 运行建群 Worker 测试确认通过**

Run:

```bash
cd armada-api
mvn -Dtest=GroupCreationMarketingWorkerTest test
```

Expected: PASS。

- [ ] **Step 6: 提交建群营销交接**

```bash
git add armada-api/src/main/java/com/armada/marketing/service/impl/GroupCreationMarketingWorker.java \
  armada-api/src/test/java/com/armada/marketing/service/GroupCreationMarketingWorkerTest.java
git commit -m "feat: route group creation marketing messages"
```

## Task 7: 删除旧 Web 专用 request 并完成 Java 回归

**Files:**

- Delete: `armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolMarketingMessageCommandRequest.java`
- Modify: `.harness/changes/2026-07-15-android-marketing-message-kafka.md`

- [ ] **Step 1: 搜索并迁移最后的旧类型引用**

Run:

```bash
cd armada
rg -n "ProtocolMarketingMessageCommandRequest|enqueueMarketingMessageCommands" armada-api
```

Expected before deletion: only the old source file；Task 2、5、6 已迁移 service、worker 和测试引用。删除源文件后再次运行，Expected: no matches。若仍有匹配，说明前置任务未完成，返回对应任务补齐并先通过其聚焦测试，不在本任务临时扩大文件范围。

确认只剩旧源文件后，用 `apply_patch` 的 `Delete File` 删除它；不要使用 `rm` 或目录级清理命令。

- [ ] **Step 2: 删除旧类型并做编译级聚焦回归**

```bash
cd armada-api
mvn -Dtest=RoutingMessageSendPortTest,WebMessageSendBackendTest,AndroidMessageSendBackendTest,ProtocolCommandOutboxServiceImplTest,ProtocolConfigurationTest,MarketingRoundWorkerTest,GroupCreationMarketingWorkerTest,ProtocolMessageEventConsumerTest,MarketingSendResultServiceImplTest test
```

Expected: PASS，0 failures，0 errors。

- [ ] **Step 3: 运行完整 Java 单测**

```bash
cd armada-api
mvn test
```

Expected: BUILD SUCCESS；记录 tests run、failures、errors、skipped。普通 `mvn test` 不替代 Task 4/5 要求的真库 DbTest。

- [ ] **Step 4: 验证 Web 协议消费者契约未回退**

在 `/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer` 运行：

```bash
npm test -- src/commands/types.test.ts src/commands/worker-consumer.test.ts src/messages/card-content.test.ts --runInBand
```

Expected: Jest 退出 0；`message.send.requested` 五种类型、Web 1–3 按钮和 mention-all 测试全部通过。

- [ ] **Step 5: 做提交前范围和格式检查**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git diff --check
git status --short
git diff -- armada-api .harness/changes/2026-07-15-android-marketing-message-kafka.md
```

Expected: `git diff --check` 无输出；diff 不包含 `.claude/worktrees/*` 或其它会话删除的群同步计划。

- [ ] **Step 6: 更新 change evidence 并提交清理**

把实际执行命令、测试数、未执行 DbTest 原因和 Android 配套 commit 写入 change 记录，然后：

```bash
git add .harness/changes/2026-07-15-android-marketing-message-kafka.md
git add -u -- armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolMarketingMessageCommandRequest.java
git commit -m "refactor: remove web-only marketing message request"
```

不要使用 `git add armada-api/src/main` 这类目录级暂存；每次只暂存本任务 `Files` 段列出的路径，避免带入并行会话修改。

## Task 8: 双仓联调验收与回滚门禁

**Files:**

- Modify: `.harness/changes/2026-07-15-android-marketing-message-kafka.md`

- [ ] **Step 1: 用固定 fixture 对账 Web/Android outbox**

在 Java 测试中使用同一业务消息分别构造 Web/Android command，断言：

- `commandType=message.send.requested`、correlation 和五种内容语义一致；
- Web topic 为 `protocol.master.commands.v1` 且无 `wsPhone`；
- Android topic 为 `protocol.android.commands.v1` 且 `wsPhone` 为账号表当前值；
- Android 单 link button 文案和 URL 原样保留。

- [ ] **Step 2: 对账 Android 结果事件 fixture**

把 Android 计划产生的成功、失败、`SEND_RESULT_UNKNOWN` 三个 JSON fixture 交给 `ProtocolMessageEventConsumerTest`，断言普通营销和建群营销分别进入现有 sink，字段无需 Android 专用 Controller/Service。

- [ ] **Step 3: 运行最终聚焦测试和完整测试**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=ProtocolMessageEventConsumerTest,MarketingSendResultServiceImplTest,MarketingRoundWorkerTest,GroupCreationMarketingWorkerTest test
mvn test
```

Expected: 两条命令都退出 0。

- [ ] **Step 4: 记录部署前置条件，不执行远程部署**

在 change 记录写明部署顺序：先部署默认关闭消息消费的 Android Zhuan，再部署 Armada，最后在确认的测试环境开启 Android 消费。没有用户明确确认目标环境时，不运行 SSH、部署脚本或共享数据库写操作。

- [ ] **Step 5: 提交验收记录**

```bash
git add .harness/changes/2026-07-15-android-marketing-message-kafka.md
git commit -m "docs: record marketing message integration verification"
```
