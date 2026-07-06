# Marketing Link/Button Card Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align Armada marketing sending with wheel for normal link cards and button cards while keeping the outbox/Kafka protocol path.

**Architecture:** Armada API composes structured `LINK_CARD` and `BUTTON_CARD` protocol commands, then `armada-protocol` consumes those command payloads and sends Baileys link preview or NativeFlow interactive button messages. The frontend blocks saving button templates without at least one valid button, while backend save-time validation remains the final guard.

**Tech Stack:** Java 17, Spring Boot, MyBatis, JUnit 5, Mockito, TypeScript, Fastify, Baileys 7.x, Jest, Vue 3, Element Plus, pnpm.

---

## Scope Note

This is one integrated plan because the backend/protocol payload contract must land coherently. The frontend validation task is independent enough to be last, but it enforces the same business invariant: `BUTTON` templates must have 1-3 valid buttons.

## File Structure

### Armada API

- Modify: `armada/armada-api/src/main/java/com/armada/marketing/service/MarketingMessageComposer.java`
  - Add `LINK_CARD` / `BUTTON_CARD` composition.
  - Parse template `buttons` JSON for send-time safety.
  - Keep existing `TEXT` / `LINK` / `IMAGE` behavior.
- Modify: `armada/armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolMarketingMessageCommandRequest.java`
  - Add structured `linkCard` and `buttonCard` payload records.
  - Keep the existing constructor for `TEXT` / `LINK` / `IMAGE` callers.
- Modify: `armada/armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`
  - Map composer card payloads into protocol command payloads.
  - Treat card thumbnails as image-sized outbox payloads.
  - Record local failed attempts when a historical/corrupt button template cannot be composed.
- Modify: `armada/armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`
  - Serialize `linkCard` and `buttonCard` into outbox JSON.
  - Validate card command payloads before enqueue.
- Test: `armada/armada-api/src/test/java/com/armada/marketing/service/MarketingMessageComposerTest.java`
- Test: `armada/armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`
- Test: `armada/armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`

### armada-protocol

- Create: `armada-protocol/protocol-layer/src/messages/card-content.ts`
  - Shared helper for link-card and button-card payload validation and Baileys content construction.
- Create: `armada-protocol/protocol-layer/src/messages/card-content.test.ts`
  - Unit tests for helper behavior without Fastify routes.
- Modify: `armada-protocol/protocol-layer/src/commands/worker-consumer.ts`
  - Accept `LINK_CARD` / `BUTTON_CARD`.
  - Dispatch link-card through `sendMessage`.
  - Dispatch button-card through `relayMessage`.
  - Publish `INVALID_MESSAGE_PAYLOAD` for parseable command refs with invalid card payloads.
- Modify: `armada-protocol/protocol-layer/src/commands/worker-consumer.test.ts`
  - Add Kafka worker tests for `LINK_CARD`, `BUTTON_CARD`, and invalid card payload.
- Modify: `armada-protocol/protocol-layer/src/routes/messages.ts`
  - Optional compatibility routes `/v1/messages/link-card` and `/v1/messages/button-card`, both using the shared helper.
- Create: `armada-protocol/protocol-layer/src/routes/messages.link-button-card.test.ts`
  - Route-level regression tests if routes are restored.
- Modify: `armada-protocol/protocol-layer/package.json`
- Modify: `armada-protocol/protocol-layer/package-lock.json`
  - Add explicit `sharp` dependency because implementation imports it directly.

### wheel-saas-pure-web

- Modify: `wheel-saas-pure-web/src/views/material/marketing-template/components/MarketingButtonEditor.vue`
  - Prevent deleting the last button in normal editing.
  - Show a clear empty state if historical data opens with zero buttons.
- Modify: `wheel-saas-pure-web/src/views/material/marketing-template/components/MarketingTemplateDrawer.vue`
  - Disable Save when `linkMode === "BUTTON"` and the button list is empty.
- Modify: `wheel-saas-pure-web/src/views/material/marketing-template/components/MarketingTemplateDrawer.test.ts`
  - Source-level regression for Save disable binding.
- Modify: `wheel-saas-pure-web/src/views/material/marketing-template/composables/useMarketingTemplatePage.test.ts`
  - Regression that zero-button button templates do not call backend save.

---

## Task 1: Armada Composer Contract

**Files:**
- Modify: `armada/armada-api/src/test/java/com/armada/marketing/service/MarketingMessageComposerTest.java`
- Modify: `armada/armada-api/src/main/java/com/armada/marketing/service/MarketingMessageComposer.java`

- [ ] **Step 1: Write failing composer tests**

Add imports:

```java
import com.armada.marketing.model.ButtonType;
import com.armada.marketing.model.MessageButton;
import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

Add a static mapper near the existing `composer` field:

```java
private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
```

Replace `buttonModeFallsBackToText` with:

```java
@Test
void normalLinkWithImageAndHttpUrlComposesLinkCard() {
    MarketingTemplate template = template(LinkMode.NORMAL.code(), 99L);
    template.setContent("标题");
    template.setBodyText("正文");
    template.setPromotionLink("https://example.com/promo");
    MarketingTemplateFile file = new MarketingTemplateFile();
    file.setContent(new byte[] {9, 8, 7});
    file.setContentType("image/png");

    MarketingMessageComposer.ComposedMessage message = composer.compose(template, file);

    assertThat(message.messageType()).isEqualTo("LINK_CARD");
    assertThat(message.text()).contains("标题");
    assertThat(message.imageBytes()).isNull();
    assertThat(message.linkCard()).isNotNull();
    assertThat(message.linkCard().url()).isEqualTo("https://example.com/promo");
    assertThat(message.linkCard().title()).isEqualTo("标题");
    assertThat(message.linkCard().description()).isEqualTo("正文");
    assertThat(message.linkCard().thumbnail().bytes()).containsExactly(9, 8, 7);
    assertThat(message.linkCard().thumbnail().mimetype()).isEqualTo("image/png");
}

@Test
void normalLinkWithImageAndNonHttpUrlKeepsTextLink() {
    MarketingTemplate template = template(LinkMode.NORMAL.code(), 99L);
    template.setContent("标题");
    template.setPromotionLink("ftp://example.com/promo");
    MarketingTemplateFile file = new MarketingTemplateFile();
    file.setContent(new byte[] {1});
    file.setContentType("image/png");

    MarketingMessageComposer.ComposedMessage message = composer.compose(template, file);

    assertThat(message.messageType()).isEqualTo("LINK");
    assertThat(message.linkCard()).isNull();
}

@Test
void buttonModeWithValidButtonsComposesButtonCard() throws JsonProcessingException {
    MarketingTemplate template = template(LinkMode.BUTTON.code(), 99L);
    template.setContent("按钮标题");
    template.setBodyText("按钮正文");
    template.setButtons(OBJECT_MAPPER.writeValueAsString(List.of(
            new MessageButton(ButtonType.LINK_JUMP, "访问", "https://example.com"),
            new MessageButton(ButtonType.COPY_CONTENT, "复制", "VIP88"),
            new MessageButton(ButtonType.QUICK_REPLY, "我要参加", null))));
    MarketingTemplateFile file = new MarketingTemplateFile();
    file.setContent(new byte[] {5, 6});
    file.setContentType("image/jpeg");

    MarketingMessageComposer.ComposedMessage message = composer.compose(template, file);

    assertThat(message.messageType()).isEqualTo("BUTTON_CARD");
    assertThat(message.text()).contains("按钮标题", "按钮正文");
    assertThat(message.buttonCard()).isNotNull();
    assertThat(message.buttonCard().title()).isEqualTo("按钮标题");
    assertThat(message.buttonCard().buttons())
            .extracting(MarketingMessageComposer.ButtonPayload::type)
            .containsExactly("link", "copy", "quick");
    assertThat(message.buttonCard().buttons())
            .extracting(MarketingMessageComposer.ButtonPayload::displayText)
            .containsExactly("访问", "复制", "我要参加");
    assertThat(message.buttonCard().buttons())
            .extracting(MarketingMessageComposer.ButtonPayload::value)
            .containsExactly("https://example.com", "VIP88", null);
    assertThat(message.buttonCard().thumbnail().bytes()).containsExactly(5, 6);
    assertThat(message.buttonCard().thumbnail().mimetype()).isEqualTo("image/jpeg");
}

@Test
void buttonModeWithoutButtonsThrowsConfigError() {
    MarketingTemplate template = template(LinkMode.BUTTON.code(), null);
    template.setContent("按钮标题");
    template.setBodyText("按钮正文");
    template.setButtons("[]");

    assertThatThrownBy(() -> composer.compose(template, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("按钮超链消息类型至少需要一个按钮");
}
```

- [ ] **Step 2: Run composer tests and verify failure**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=MarketingMessageComposerTest test
```

Expected: FAIL because `ComposedMessage` has no `linkCard()` / `buttonCard()` accessors and `BUTTON` still falls back to `TEXT`.

- [ ] **Step 3: Implement composer card payloads**

In `MarketingMessageComposer.java`, add imports:

```java
import com.armada.marketing.model.ButtonType;
import com.armada.marketing.model.MessageButton;
import com.armada.shared.util.HttpUrlValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
```

Add constants after the class declaration:

```java
private static final ObjectMapper BUTTONS_JSON = new ObjectMapper();
private static final TypeReference<List<MessageButton>> BUTTON_LIST = new TypeReference<>() {
};
```

Replace `compose(...)` with:

```java
public ComposedMessage compose(MarketingTemplate template, MarketingTemplateFile imageFile) {
    if (template == null) {
        throw new BusinessException(ErrorCode.VALIDATION, "营销模板不能为空");
    }
    LinkMode mode = LinkMode.fromCode(template.getLinkMode());
    String text = composeText(template);
    MediaPayload thumbnail = mediaPayload(imageFile);
    if (mode == LinkMode.IMAGE_TEXT && thumbnail != null) {
        return new ComposedMessage("IMAGE", text, thumbnail.bytes(), thumbnail.mimetype());
    }
    if (mode == LinkMode.BUTTON) {
        return composeButtonCard(template, text, thumbnail);
    }
    if (mode == LinkMode.NORMAL
            && thumbnail != null
            && HttpUrlValidator.isHttpUrl(template.getPromotionLink())) {
        return new ComposedMessage(
                "LINK_CARD",
                linkCardText(template),
                null,
                null,
                new LinkCardPayload(
                        template.getPromotionLink().trim(),
                        linkCardTitle(template),
                        trimToNull(template.getBodyText()),
                        thumbnail),
                null);
    }
    if (mode == LinkMode.NORMAL && StringUtils.hasText(template.getPromotionLink())) {
        return new ComposedMessage("LINK", text, null, null);
    }
    return new ComposedMessage("TEXT", text, null, null);
}
```

Add helper methods before `composeText(...)`:

```java
private static ComposedMessage composeButtonCard(MarketingTemplate template, String text, MediaPayload thumbnail) {
    List<ButtonPayload> buttons = buttonsFromJson(template.getButtons()).stream()
            .map(MarketingMessageComposer::buttonPayload)
            .toList();
    if (buttons.isEmpty()) {
        throw new BusinessException(ErrorCode.VALIDATION, "按钮超链消息类型至少需要一个按钮");
    }
    return new ComposedMessage(
            "BUTTON_CARD",
            text,
            null,
            null,
            null,
            new ButtonCardPayload(linkCardTitle(template), null, buttons, thumbnail));
}

private static List<MessageButton> buttonsFromJson(String json) {
    if (!StringUtils.hasText(json)) {
        return List.of();
    }
    try {
        return BUTTONS_JSON.readValue(json, BUTTON_LIST);
    } catch (JsonProcessingException ex) {
        throw new BusinessException(ErrorCode.VALIDATION, "按钮配置格式不正确");
    }
}

private static ButtonPayload buttonPayload(MessageButton button) {
    if (button == null || button.type() == null || !StringUtils.hasText(button.text())) {
        throw new BusinessException(ErrorCode.VALIDATION, "按钮配置不完整");
    }
    if (button.type() == ButtonType.LINK_JUMP) {
        if (!HttpUrlValidator.isHttpUrl(button.param())) {
            throw new BusinessException(ErrorCode.VALIDATION, "跳转链接格式不正确");
        }
        return new ButtonPayload("link", button.text().trim(), button.param().trim());
    }
    if (button.type() == ButtonType.COPY_CONTENT) {
        if (!StringUtils.hasText(button.param())) {
            throw new BusinessException(ErrorCode.VALIDATION, "复制按钮必须填写参数");
        }
        return new ButtonPayload("copy", button.text().trim(), button.param().trim());
    }
    return new ButtonPayload("quick", button.text().trim(), null);
}

private static MediaPayload mediaPayload(MarketingTemplateFile imageFile) {
    if (imageFile == null || imageFile.getContent() == null || imageFile.getContent().length == 0) {
        return null;
    }
    return new MediaPayload(imageFile.getContent(), imageFile.getContentType());
}

private static String linkCardText(MarketingTemplate template) {
    String content = trimToNull(template.getContent());
    if (content != null) {
        return content;
    }
    String body = trimToNull(template.getBodyText());
    if (body != null) {
        return body;
    }
    return template.getPromotionLink().trim();
}

private static String linkCardTitle(MarketingTemplate template) {
    String content = trimToNull(template.getContent());
    if (content != null) {
        return content;
    }
    String name = trimToNull(template.getTemplateName());
    if (name != null) {
        return name;
    }
    String link = trimToNull(template.getPromotionLink());
    return link == null ? "" : link;
}

private static String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
        return null;
    }
    return value.trim();
}
```

Replace the `ComposedMessage` record with:

```java
public record ComposedMessage(
        String messageType,
        String text,
        byte[] imageBytes,
        String imageMimetype,
        LinkCardPayload linkCard,
        ButtonCardPayload buttonCard
) {
    public ComposedMessage(String messageType, String text, byte[] imageBytes, String imageMimetype) {
        this(messageType, text, imageBytes, imageMimetype, null, null);
    }
}

public record MediaPayload(
        byte[] bytes,
        String mimetype
) {
}

public record LinkCardPayload(
        String url,
        String title,
        String description,
        MediaPayload thumbnail
) {
}

public record ButtonCardPayload(
        String title,
        String footer,
        List<ButtonPayload> buttons,
        MediaPayload thumbnail
) {
}

public record ButtonPayload(
        String type,
        String displayText,
        String value
) {
}
```

- [ ] **Step 4: Run composer tests and verify pass**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=MarketingMessageComposerTest test
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/marketing/service/MarketingMessageComposer.java armada-api/src/test/java/com/armada/marketing/service/MarketingMessageComposerTest.java
git commit -m "feat: compose marketing card messages"
```

---

## Task 2: Armada Protocol Command Payload

**Files:**
- Modify: `armada/armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java`
- Modify: `armada/armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolMarketingMessageCommandRequest.java`
- Modify: `armada/armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java`

- [ ] **Step 1: Write failing outbox payload tests**

Add two tests after `enqueueMarketingMessageCommands_singleCommand_insertsMasterRoutedAttemptCommand`:

```java
@Test
void enqueueMarketingMessageCommands_linkCardPayload_serializesStructuredCard() throws Exception {
    TestableProtocolCommandOutboxService service = newService(List.of("cmd-link-card"), List.of());
    ProtocolMarketingMessageCommandRequest command = new ProtocolMarketingMessageCommandRequest(
            1L,
            42L,
            9001L,
            7001L,
            1L,
            501L,
            "acc_1",
            "120363001@g.us",
            "LINK_CARD",
            "标题",
            null,
            null,
            new ProtocolMarketingMessageCommandRequest.MarketingLinkCardPayload(
                    "https://example.com/promo",
                    "标题",
                    "正文",
                    new ProtocolMarketingMessageCommandRequest.MarketingMediaPayload("AQID", "image/png")),
            null,
            "marketing_task");
    when(mapper.batchInsertPending(anyList())).thenReturn(1);

    service.enqueueMarketingMessageCommands(List.of(command));

    Map<String, Object> payload = objectMapper.readValue(capturedRows().get(0).getPayloadJson(), new TypeReference<>() {
    });
    assertThat(payload).containsEntry("messageType", "LINK_CARD");
    assertThat(payload).containsEntry("text", "标题");
    @SuppressWarnings("unchecked")
    Map<String, Object> linkCard = (Map<String, Object>) payload.get("linkCard");
    assertThat(linkCard)
            .containsEntry("url", "https://example.com/promo")
            .containsEntry("title", "标题")
            .containsEntry("description", "正文");
    @SuppressWarnings("unchecked")
    Map<String, Object> thumbnail = (Map<String, Object>) linkCard.get("thumbnail");
    assertThat(thumbnail)
            .containsEntry("base64", "AQID")
            .containsEntry("mimetype", "image/png");
}

@Test
void enqueueMarketingMessageCommands_buttonCardPayload_serializesButtons() throws Exception {
    TestableProtocolCommandOutboxService service = newService(List.of("cmd-button-card"), List.of());
    ProtocolMarketingMessageCommandRequest command = new ProtocolMarketingMessageCommandRequest(
            1L,
            42L,
            9002L,
            7002L,
            1L,
            502L,
            "acc_2",
            "120363002@g.us",
            "BUTTON_CARD",
            "按钮正文",
            null,
            null,
            null,
            new ProtocolMarketingMessageCommandRequest.MarketingButtonCardPayload(
                    "按钮标题",
                    null,
                    List.of(new ProtocolMarketingMessageCommandRequest.MarketingButtonPayload(
                            "link", "访问", "https://example.com")),
                    null),
            "marketing_task");
    when(mapper.batchInsertPending(anyList())).thenReturn(1);

    service.enqueueMarketingMessageCommands(List.of(command));

    Map<String, Object> payload = objectMapper.readValue(capturedRows().get(0).getPayloadJson(), new TypeReference<>() {
    });
    assertThat(payload).containsEntry("messageType", "BUTTON_CARD");
    @SuppressWarnings("unchecked")
    Map<String, Object> buttonCard = (Map<String, Object>) payload.get("buttonCard");
    assertThat(buttonCard).containsEntry("title", "按钮标题");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> buttons = (List<Map<String, Object>>) buttonCard.get("buttons");
    assertThat(buttons).hasSize(1);
    assertThat(buttons.get(0))
            .containsEntry("type", "link")
            .containsEntry("displayText", "访问")
            .containsEntry("value", "https://example.com");
}
```

- [ ] **Step 2: Run outbox tests and verify failure**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=ProtocolCommandOutboxServiceImplTest test
```

Expected: FAIL because `ProtocolMarketingMessageCommandRequest` has no card payload constructor or nested records.

- [ ] **Step 3: Extend protocol request record**

Replace `ProtocolMarketingMessageCommandRequest` parameters with:

```java
public record ProtocolMarketingMessageCommandRequest(
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
        String imageBase64,
        String imageMimetype,
        MarketingLinkCardPayload linkCard,
        MarketingButtonCardPayload buttonCard,
        String source,
        String commandId
) {
```

Keep the current constructor and route it to the expanded record:

```java
public ProtocolMarketingMessageCommandRequest(
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
        String imageBase64,
        String imageMimetype,
        String source,
        String commandId) {
    this(tenantId, marketingTaskId, attemptId, targetId, roundNo, accountId,
            protocolAccountId, groupJid, messageType, text, imageBase64, imageMimetype,
            null, null, source, commandId);
}

public ProtocolMarketingMessageCommandRequest(
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
        String imageBase64,
        String imageMimetype,
        String source) {
    this(tenantId, marketingTaskId, attemptId, targetId, roundNo, accountId,
            protocolAccountId, groupJid, messageType, text, imageBase64, imageMimetype,
            null, null, source, null);
}
```

Add a new card constructor:

```java
public ProtocolMarketingMessageCommandRequest(
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
        String imageBase64,
        String imageMimetype,
        MarketingLinkCardPayload linkCard,
        MarketingButtonCardPayload buttonCard,
        String source) {
    this(tenantId, marketingTaskId, attemptId, targetId, roundNo, accountId,
            protocolAccountId, groupJid, messageType, text, imageBase64, imageMimetype,
            linkCard, buttonCard, source, null);
}
```

Add nested records before the final `}`:

```java
public record MarketingMediaPayload(
        String base64,
        String mimetype
) {
}

public record MarketingLinkCardPayload(
        String url,
        String title,
        String description,
        MarketingMediaPayload thumbnail
) {
}

public record MarketingButtonPayload(
        String type,
        String displayText,
        String value
) {
}

public record MarketingButtonCardPayload(
        String title,
        String footer,
        java.util.List<MarketingButtonPayload> buttons,
        MarketingMediaPayload thumbnail
) {
}
```

- [ ] **Step 4: Serialize and validate card payloads**

In `ProtocolCommandOutboxServiceImpl.payloadJson(...)`, pass `command.linkCard()` and `command.buttonCard()` into `MarketingMessagePayload`:

```java
MarketingMessagePayload payload = new MarketingMessagePayload(
        command.tenantId(),
        command.marketingTaskId(),
        command.attemptId(),
        command.targetId(),
        command.roundNo(),
        command.accountId(),
        command.protocolAccountId(),
        command.groupJid(),
        command.messageType(),
        command.text(),
        isBlank(command.imageBase64())
                ? null
                : new MarketingImagePayload(command.imageBase64(), command.imageMimetype()),
        command.linkCard(),
        command.buttonCard(),
        sourceOrDefault(command.source(), "marketing_task"));
```

Replace `MarketingMessagePayload` with:

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
private record MarketingMessagePayload(
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
        MarketingImagePayload image,
        ProtocolMarketingMessageCommandRequest.MarketingLinkCardPayload linkCard,
        ProtocolMarketingMessageCommandRequest.MarketingButtonCardPayload buttonCard,
        String source
) {
}
```

Add card validation at the end of `validateMarketingMessageCommand(...)`:

```java
String type = command.messageType().trim().toUpperCase();
if ("LINK_CARD".equals(type)) {
    if (command.linkCard() == null
            || isBlank(command.linkCard().url())
            || isBlank(command.linkCard().title())
            || command.linkCard().thumbnail() == null
            || isBlank(command.linkCard().thumbnail().base64())) {
        throw new BusinessException(ErrorCode.VALIDATION, "LINK_CARD 营销消息缺少卡片字段");
    }
}
if ("BUTTON_CARD".equals(type)) {
    if (command.buttonCard() == null
            || command.buttonCard().buttons() == null
            || command.buttonCard().buttons().isEmpty()
            || command.buttonCard().buttons().size() > 3) {
        throw new BusinessException(ErrorCode.VALIDATION, "BUTTON_CARD 营销消息按钮数量必须为1-3个");
    }
    for (ProtocolMarketingMessageCommandRequest.MarketingButtonPayload button : command.buttonCard().buttons()) {
        if (button == null || isBlank(button.type()) || isBlank(button.displayText())) {
            throw new BusinessException(ErrorCode.VALIDATION, "BUTTON_CARD 营销消息按钮字段不完整");
        }
    }
}
```

- [ ] **Step 5: Run outbox tests and verify pass**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=ProtocolCommandOutboxServiceImplTest test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/platform/protocol/model/command/ProtocolMarketingMessageCommandRequest.java armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java armada-api/src/test/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImplTest.java
git commit -m "feat: serialize marketing card commands"
```

---

## Task 3: Armada Round Worker Mapping and Local Config Failure

**Files:**
- Modify: `armada/armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java`
- Modify: `armada/armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java`

- [ ] **Step 1: Write failing worker tests**

Add imports:

```java
import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import com.armada.marketing.model.ButtonType;
import com.armada.marketing.model.MessageButton;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
```

Add static Mockito imports:

```java
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
```

Add field:

```java
private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
```

Add these tests before helper methods:

```java
@Test
void normalLinkCardRoundEnqueuesLinkCardCommand() {
    MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
    ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
    MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
    properties.setBacklogMultiplier(2);
    properties.setImageOutboxBatchSize(200);
    MarketingTask task = task();
    when(taskMapper.selectTaskById(42L)).thenReturn(task);
    when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
    when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets(1));
    when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
    doAnswer(invocation -> {
        @SuppressWarnings("unchecked")
        List<MarketingTaskSendAttempt> attempts = invocation.getArgument(0, List.class);
        attempts.get(0).setId(9201L);
        return attempts.size();
    }).when(taskMapper).insertSendAttempts(any());
    MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
    MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
    when(templateMapper.selectById(77L)).thenReturn(normalLinkCardTemplate());
    when(fileMapper.selectById(88L)).thenReturn(imageFile());
    MarketingRoundWorker worker = new MarketingRoundWorker(taskMapper, templateMapper, fileMapper,
            new MarketingMessageComposer(), outbox, properties);

    worker.runRound(1L, 42L);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ProtocolMarketingMessageCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
    verify(outbox).enqueueMarketingMessageCommands(commandsCaptor.capture());
    ProtocolMarketingMessageCommandRequest command = commandsCaptor.getValue().get(0);
    assertThat(command.messageType()).isEqualTo("LINK_CARD");
    assertThat(command.linkCard().url()).isEqualTo("https://example.com/promo");
    assertThat(command.linkCard().thumbnail().base64()).isEqualTo("AQID");
    assertThat(command.buttonCard()).isNull();
}

@Test
void buttonCardRoundEnqueuesButtonCardCommand() throws JsonProcessingException {
    MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
    ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
    MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
    properties.setBacklogMultiplier(2);
    MarketingTask task = task();
    when(taskMapper.selectTaskById(42L)).thenReturn(task);
    when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
    when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets(1));
    when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
    doAnswer(invocation -> {
        @SuppressWarnings("unchecked")
        List<MarketingTaskSendAttempt> attempts = invocation.getArgument(0, List.class);
        attempts.get(0).setId(9301L);
        return attempts.size();
    }).when(taskMapper).insertSendAttempts(any());
    MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
    MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
    when(templateMapper.selectById(77L)).thenReturn(buttonTemplateWithButtons());
    MarketingRoundWorker worker = new MarketingRoundWorker(taskMapper, templateMapper, fileMapper,
            new MarketingMessageComposer(), outbox, properties);

    worker.runRound(1L, 42L);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ProtocolMarketingMessageCommandRequest>> commandsCaptor = ArgumentCaptor.forClass(List.class);
    verify(outbox).enqueueMarketingMessageCommands(commandsCaptor.capture());
    ProtocolMarketingMessageCommandRequest command = commandsCaptor.getValue().get(0);
    assertThat(command.messageType()).isEqualTo("BUTTON_CARD");
    assertThat(command.buttonCard().buttons()).hasSize(1);
    assertThat(command.buttonCard().buttons().get(0).type()).isEqualTo("quick");
    assertThat(command.linkCard()).isNull();
}

@Test
void invalidButtonTemplateCreatesLocalFailuresWithoutOutbox() {
    MarketingTaskMapper taskMapper = mock(MarketingTaskMapper.class);
    ProtocolCommandOutboxService outbox = mock(ProtocolCommandOutboxService.class);
    MarketingRoundSchedulerProperties properties = new MarketingRoundSchedulerProperties();
    properties.setBacklogMultiplier(2);
    MarketingTask task = task();
    when(taskMapper.selectTaskById(42L)).thenReturn(task);
    when(taskMapper.countUnfinishedAttempts(42L)).thenReturn(0L);
    when(taskMapper.selectTargetsByTaskId(42L)).thenReturn(targets(2));
    when(taskMapper.claimDueRound(any(), anyLong(), anyLong())).thenReturn(1);
    doAnswer(invocation -> {
        @SuppressWarnings("unchecked")
        List<MarketingTaskSendAttempt> attempts = invocation.getArgument(0, List.class);
        long id = 9400L;
        for (MarketingTaskSendAttempt attempt : attempts) {
            attempt.setId(++id);
        }
        return attempts.size();
    }).when(taskMapper).insertSendAttempts(any());
    MarketingRoundWorker worker = worker(taskMapper, outbox, properties);

    worker.runRound(1L, 42L);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MarketingTaskSendAttempt>> attemptsCaptor = ArgumentCaptor.forClass(List.class);
    verify(taskMapper).insertSendAttempts(attemptsCaptor.capture());
    assertThat(attemptsCaptor.getValue()).hasSize(2);
    assertThat(attemptsCaptor.getValue()).extracting(MarketingTaskSendAttempt::getStatus)
            .containsOnly(MarketingSendAttemptStatus.FAILED.code());
    assertThat(attemptsCaptor.getValue()).extracting(MarketingTaskSendAttempt::getReasonCode)
            .containsOnly("INVALID_TEMPLATE_CONFIG");
    verify(taskMapper).incrementTaskSendCounters(42L, 0, 2, attemptsCaptor.getValue().get(0).getResultAt());
    verify(taskMapper, times(2)).markTargetFailedFromAttempt(anyLong(), anyLong(),
            eq("INVALID_TEMPLATE_CONFIG"), contains("按钮超链消息类型"), anyLong());
    verify(outbox, never()).enqueueMarketingMessageCommands(any());
}
```

Add helper methods:

```java
private static MarketingTemplate normalLinkCardTemplate() {
    MarketingTemplate template = template();
    template.setLinkMode(LinkMode.NORMAL.code());
    template.setImageFileId(88L);
    template.setContent("标题");
    template.setBodyText("正文");
    template.setPromotionLink("https://example.com/promo");
    return template;
}

private static MarketingTemplate buttonTemplateWithButtons() throws JsonProcessingException {
    MarketingTemplate template = template();
    template.setLinkMode(LinkMode.BUTTON.code());
    template.setContent("按钮标题");
    template.setBodyText("按钮正文");
    template.setButtons(OBJECT_MAPPER.writeValueAsString(List.of(
            new MessageButton(ButtonType.QUICK_REPLY, "我要参加", null))));
    return template;
}
```

In existing `template()`, keep it invalid for the local failure test:

```java
template.setLinkMode(LinkMode.BUTTON.code());
template.setContent("hello");
template.setButtons("[]");
```

- [ ] **Step 2: Run worker tests and verify failure**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=MarketingRoundWorkerTest test
```

Expected: FAIL because worker does not map card payloads and invalid `BUTTON` templates are not locally failed.

- [ ] **Step 3: Map card payloads in worker**

In `MarketingRoundWorker.java`, add imports:

```java
import com.armada.shared.exception.BusinessException;
```

Add constants near existing constants:

```java
private static final String REASON_INVALID_TEMPLATE_CONFIG = "INVALID_TEMPLATE_CONFIG";
```

Wrap compose in `runRound(...)` after `imageFile` is loaded:

```java
MarketingMessageComposer.ComposedMessage message;
try {
    message = messageComposer.compose(template, imageFile);
} catch (BusinessException ex) {
    recordLocalFailedAttempts(task, sendTargets, roundNo, now, ex.getMessage());
    log.warn("营销任务模板配置错误,本轮不下发协议命令 tenantId={} taskId={} roundNo={} targetCount={} reason={}",
            task.getTenantId(), task.getId(), roundNo, sendTargets.size(), ex.getMessage());
    return;
}
```

In `enqueueCommands(...)`, compute card payloads once:

```java
String imageBase64 = message.imageBytes() == null ? null : Base64.getEncoder().encodeToString(message.imageBytes());
ProtocolMarketingMessageCommandRequest.MarketingLinkCardPayload linkCard = linkCardPayload(message.linkCard());
ProtocolMarketingMessageCommandRequest.MarketingButtonCardPayload buttonCard = buttonCardPayload(message.buttonCard());
```

Use the expanded constructor:

```java
batch.add(new ProtocolMarketingMessageCommandRequest(
        task.getTenantId(),
        task.getId(),
        attempt.getId(),
        target.getId(),
        attempt.getRoundNo(),
        target.getAccountId(),
        protocolAccountId(target),
        sendTarget.groupJid(),
        message.messageType(),
        message.text(),
        imageBase64,
        message.imageMimetype(),
        linkCard,
        buttonCard,
        SOURCE_MARKETING_TASK,
        attempt.getCommandId()));
```

Add mapper helpers before `outboxBatchSize(...)`:

```java
private void recordLocalFailedAttempts(MarketingTask task,
                                       List<ResolvedMarketingTarget> sendTargets,
                                       long roundNo,
                                       long now,
                                       String reasonMessage) {
    List<MarketingTaskSendAttempt> attempts = new ArrayList<>(sendTargets.size());
    for (ResolvedMarketingTarget sendTarget : sendTargets) {
        MarketingTaskSendAttempt attempt = toAttempt(task, sendTarget, roundNo, now);
        attempt.setStatus(MarketingSendAttemptStatus.FAILED.code());
        attempt.setReasonCode(REASON_INVALID_TEMPLATE_CONFIG);
        attempt.setReasonMessage(reasonMessage);
        attempt.setResultAt(now);
        attempts.add(attempt);
    }
    int inserted = taskMapper.insertSendAttempts(attempts);
    if (inserted != attempts.size()) {
        throw new BusinessException(ErrorCode.CONFLICT,
                "营销发送失败尝试写入数量不一致: expected=" + attempts.size() + ", inserted=" + inserted);
    }
    taskMapper.incrementTaskSendCounters(task.getId(), 0, attempts.size(), now);
    for (MarketingTaskSendAttempt attempt : attempts) {
        taskMapper.markTargetFailedFromAttempt(attempt.getTargetId(), attempt.getId(),
                REASON_INVALID_TEMPLATE_CONFIG, reasonMessage, now);
    }
}

private static ProtocolMarketingMessageCommandRequest.MarketingLinkCardPayload linkCardPayload(
        MarketingMessageComposer.LinkCardPayload linkCard) {
    if (linkCard == null) {
        return null;
    }
    return new ProtocolMarketingMessageCommandRequest.MarketingLinkCardPayload(
            linkCard.url(),
            linkCard.title(),
            linkCard.description(),
            mediaPayload(linkCard.thumbnail()));
}

private static ProtocolMarketingMessageCommandRequest.MarketingButtonCardPayload buttonCardPayload(
        MarketingMessageComposer.ButtonCardPayload buttonCard) {
    if (buttonCard == null) {
        return null;
    }
    return new ProtocolMarketingMessageCommandRequest.MarketingButtonCardPayload(
            buttonCard.title(),
            buttonCard.footer(),
            buttonCard.buttons().stream()
                    .map(button -> new ProtocolMarketingMessageCommandRequest.MarketingButtonPayload(
                            button.type(), button.displayText(), button.value()))
                    .toList(),
            mediaPayload(buttonCard.thumbnail()));
}

private static ProtocolMarketingMessageCommandRequest.MarketingMediaPayload mediaPayload(
        MarketingMessageComposer.MediaPayload media) {
    if (media == null || media.bytes() == null || media.bytes().length == 0) {
        return null;
    }
    return new ProtocolMarketingMessageCommandRequest.MarketingMediaPayload(
            Base64.getEncoder().encodeToString(media.bytes()),
            media.mimetype());
}
```

Change `outboxBatchSize(...)`:

```java
private int outboxBatchSize(MarketingMessageComposer.ComposedMessage message) {
    int configured = hasLargeMediaPayload(message)
            ? properties.getImageOutboxBatchSize()
            : properties.getOutboxBatchSize();
    return Math.max(1, Math.min(500, configured));
}

private static boolean hasLargeMediaPayload(MarketingMessageComposer.ComposedMessage message) {
    return "IMAGE".equals(message.messageType())
            || (message.linkCard() != null && message.linkCard().thumbnail() != null)
            || (message.buttonCard() != null && message.buttonCard().thumbnail() != null);
}
```

- [ ] **Step 4: Run worker tests and verify pass**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=MarketingRoundWorkerTest test
```

Expected: PASS.

- [ ] **Step 5: Run Armada focused regression**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=MarketingMessageComposerTest,MarketingRoundWorkerTest,ProtocolCommandOutboxServiceImplTest test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 3**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git add armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java armada-api/src/test/java/com/armada/marketing/scheduler/MarketingRoundWorkerTest.java
git commit -m "feat: enqueue marketing card commands"
```

---

## Task 4: Protocol Card Content Helper

**Files:**
- Modify: `armada-protocol/protocol-layer/package.json`
- Modify: `armada-protocol/protocol-layer/package-lock.json`
- Create: `armada-protocol/protocol-layer/src/messages/card-content.ts`
- Create: `armada-protocol/protocol-layer/src/messages/card-content.test.ts`

- [ ] **Step 1: Add explicit sharp dependency**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm install sharp --package-lock-only
```

Expected: `package.json` root dependencies include `"sharp"` and `package-lock.json` root dependencies include `"sharp"`.

- [ ] **Step 2: Write failing helper tests**

Create `src/messages/card-content.test.ts`:

```ts
import {
  buildButtonCardMessage,
  buildLinkCardContent,
  nativeFlowButton
} from './card-content.js'

const TINY_PNG_B64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='

describe('card content helpers', () => {
  it('builds link-card content with url in text and high quality thumbnail', async () => {
    const sock = {
      waUploadToServer: async () => ({ mediaUrl: 'https://cdn.test/thumb', directPath: '/v/t/thumb' })
    }

    const content = await buildLinkCardContent({
      text: '快来玩',
      card: {
        url: 'https://example.com/promo',
        title: '大奖等你',
        description: '点击参与',
        thumbnail: { base64: TINY_PNG_B64, mimetype: 'image/png' }
      },
      sock
    } as never)

    expect(content.text).toContain('https://example.com/promo')
    const preview = content.linkPreview as Record<string, unknown>
    expect(preview['canonical-url']).toBe('https://example.com/promo')
    expect(preview['matched-text']).toBe('https://example.com/promo')
    expect(preview.title).toBe('大奖等你')
    expect(preview.description).toBe('点击参与')
    expect(preview.highQualityThumbnail).toBeTruthy()
  })

  it('maps Armada button types to native flow buttons', () => {
    expect(nativeFlowButton({ type: 'link', displayText: '访问', value: 'https://x.example' })).toEqual({
      name: 'cta_url',
      buttonParamsJson: JSON.stringify({
        display_text: '访问',
        url: 'https://x.example',
        merchant_url: 'https://x.example'
      })
    })
    expect(nativeFlowButton({ type: 'copy', displayText: '复制', value: 'VIP88' })).toEqual({
      name: 'cta_copy',
      buttonParamsJson: JSON.stringify({
        display_text: '复制',
        id: 'VIP88',
        copy_code: 'VIP88'
      })
    })
    expect(nativeFlowButton({ type: 'quick', displayText: '我要参加' })).toEqual({
      name: 'quick_reply',
      buttonParamsJson: JSON.stringify({
        display_text: '我要参加',
        id: '我要参加'
      })
    })
  })

  it('builds button-card relay message with biz native flow nodes', async () => {
    const sock = {
      user: { id: 'acc_1@s.whatsapp.net' },
      waUploadToServer: async () => ({ mediaUrl: 'https://cdn.test/thumb', directPath: '/v/t/thumb' })
    }

    const result = await buildButtonCardMessage({
      jid: '120363@g.us',
      text: '点下方按钮',
      card: {
        title: '限时活动',
        buttons: [{ type: 'link', displayText: '立即领取', value: 'https://promo.example/vip' }],
        thumbnail: { base64: TINY_PNG_B64, mimetype: 'image/png' }
      },
      sock
    } as never)

    const interactive = (result.message as any).viewOnceMessageV2Extension.message.interactiveMessage
    expect(interactive.header.hasMediaAttachment).toBe(true)
    expect(interactive.nativeFlowMessage.buttons[0].name).toBe('cta_url')
    expect(result.relayOptions.additionalNodes[0].tag).toBe('biz')
    expect(result.relayOptions.additionalNodes[0].content[0].tag).toBe('interactive')
  })

  it('rejects button-card without buttons', async () => {
    await expect(buildButtonCardMessage({
      jid: '120363@g.us',
      text: 'body',
      card: {
        title: 'T',
        buttons: []
      },
      sock: { user: { id: 'acc_1@s.whatsapp.net' } }
    } as never)).rejects.toThrow('BUTTON_CARD requires 1-3 buttons')
  })
})
```

- [ ] **Step 3: Run helper tests and verify failure**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runTestsByPath src/messages/card-content.test.ts
```

Expected: FAIL because `src/messages/card-content.ts` does not exist.

- [ ] **Step 4: Implement helper**

Create `src/messages/card-content.ts`:

```ts
import { generateWAMessageFromContent, prepareWAMessageMedia } from 'baileys'
import sharp from 'sharp'

export interface MediaInput {
  base64?: string
  url?: string
  mimetype?: string
}

export interface LinkCardPayload {
  url: string
  title: string
  description?: string
  thumbnail: MediaInput
}

export type ButtonCardButtonType = 'link' | 'copy' | 'quick'

export interface ButtonCardButton {
  type: ButtonCardButtonType
  displayText: string
  value?: string | null
}

export interface ButtonCardPayload {
  title?: string | null
  footer?: string | null
  buttons: ButtonCardButton[]
  thumbnail?: MediaInput | null
}

export interface CardSocket {
  user?: { id?: string }
  waUploadToServer?: unknown
}

export function nativeFlowButton(btn: ButtonCardButton): {
  name: string
  buttonParamsJson: string
} {
  const displayText = btn.displayText
  if (btn.type === 'copy') {
    const value = btn.value ?? ''
    return {
      name: 'cta_copy',
      buttonParamsJson: JSON.stringify({ display_text: displayText, id: value, copy_code: value })
    }
  }
  if (btn.type === 'quick') {
    return {
      name: 'quick_reply',
      buttonParamsJson: JSON.stringify({ display_text: displayText, id: btn.value ?? displayText })
    }
  }
  const url = btn.value ?? ''
  return {
    name: 'cta_url',
    buttonParamsJson: JSON.stringify({ display_text: displayText, url, merchant_url: url })
  }
}

export async function buildLinkCardContent(input: {
  text?: string
  card: LinkCardPayload
  sock: CardSocket
}): Promise<Record<string, unknown>> {
  validateLinkCard(input.card)
  const raw = await mediaBuffer(input.card.thumbnail)
  const normalized = await sharp(raw).resize({ width: 800, withoutEnlargement: true }).jpeg({ quality: 80 }).toBuffer()
  const prepared = await prepareWAMessageMedia(
    { image: normalized },
    { upload: input.sock.waUploadToServer as never, mediaTypeOverride: 'thumbnail-link' }
  )
  const highQualityThumbnail = prepared.imageMessage
  const text = ensureTextContainsUrl(input.text ?? '', input.card.url)
  return {
    text,
    linkPreview: {
      'canonical-url': input.card.url,
      'matched-text': input.card.url,
      title: input.card.title,
      description: input.card.description ?? '',
      jpegThumbnail: highQualityThumbnail?.jpegThumbnail ? Buffer.from(highQualityThumbnail.jpegThumbnail) : undefined,
      highQualityThumbnail: highQualityThumbnail ?? undefined
    }
  }
}

export async function buildButtonCardMessage(input: {
  jid: string
  text?: string
  card: ButtonCardPayload
  sock: CardSocket
}): Promise<{
  message: Record<string, unknown>
  key: { id?: string | null; remoteJid?: string | null }
  messageTimestamp?: unknown
  relayOptions: Record<string, unknown>
}> {
  validateButtonCard(input.card)
  let header: Record<string, unknown> = {
    title: input.card.title ?? '',
    hasMediaAttachment: false
  }
  if (input.card.thumbnail) {
    const raw = await mediaBuffer(input.card.thumbnail)
    const jpeg = await sharp(raw).rotate().jpeg({ quality: 85 }).toBuffer()
    const prepared = await prepareWAMessageMedia(
      { image: jpeg },
      { upload: input.sock.waUploadToServer as never }
    )
    header = { hasMediaAttachment: true, imageMessage: prepared.imageMessage, title: input.card.title ?? '' }
  }
  const content = {
    viewOnceMessageV2Extension: {
      message: {
        messageContextInfo: { deviceListMetadata: {}, deviceListMetadataVersion: 2 },
        interactiveMessage: {
          header,
          body: { text: input.text ?? '' },
          footer: { text: input.card.footer ?? '' },
          nativeFlowMessage: {
            buttons: input.card.buttons.map(nativeFlowButton),
            messageVersion: 1
          }
        }
      }
    }
  }
  const waMsg = generateWAMessageFromContent(input.jid, content as never, { userJid: input.sock.user?.id ?? '' })
  return {
    message: waMsg.message as Record<string, unknown>,
    key: {
      id: waMsg.key.id ?? null,
      remoteJid: waMsg.key.remoteJid ?? input.jid
    },
    messageTimestamp: waMsg.messageTimestamp,
    relayOptions: {
      messageId: waMsg.key.id as string,
      additionalNodes: [bizNativeFlowNode()]
    }
  }
}

function validateLinkCard(card: LinkCardPayload): void {
  if (!card.url || !card.title || !card.thumbnail) {
    throw new Error('LINK_CARD requires url, title and thumbnail')
  }
  assertHttpUrl(card.url, 'LINK_CARD url must be http(s)')
}

function validateButtonCard(card: ButtonCardPayload): void {
  if (!Array.isArray(card.buttons) || card.buttons.length < 1 || card.buttons.length > 3) {
    throw new Error('BUTTON_CARD requires 1-3 buttons')
  }
  for (const button of card.buttons) {
    if (!button.displayText || !button.type) {
      throw new Error('BUTTON_CARD button requires type and displayText')
    }
    if (button.type === 'link') {
      assertHttpUrl(button.value ?? '', 'BUTTON_CARD link button requires http(s) value')
    }
    if (button.type === 'copy' && !button.value) {
      throw new Error('BUTTON_CARD copy button requires value')
    }
  }
}

async function mediaBuffer(media: MediaInput): Promise<Buffer> {
  if (media.base64) {
    return Buffer.from(media.base64, 'base64')
  }
  if (!media.url) {
    throw new Error('media input must provide url or base64')
  }
  const resp = await fetch(media.url)
  if (!resp.ok) {
    throw new Error(`thumbnail fetch failed: ${resp.status}`)
  }
  return Buffer.from(await resp.arrayBuffer())
}

function ensureTextContainsUrl(text: string, url: string): string {
  if (text.includes(url)) return text
  return text.trim().length > 0 ? `${text}\n${url}` : url
}

function assertHttpUrl(value: string, message: string): void {
  try {
    const parsed = new URL(value)
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      throw new Error(message)
    }
  } catch {
    throw new Error(message)
  }
}

function bizNativeFlowNode(): Record<string, unknown> {
  return {
    tag: 'biz',
    attrs: {},
    content: [
      {
        tag: 'interactive',
        attrs: { type: 'native_flow', v: '1' },
        content: [{ tag: 'native_flow', attrs: { v: '2', name: 'mixed' }, content: undefined }]
      }
    ]
  }
}
```

- [ ] **Step 5: Run helper tests and verify pass**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runTestsByPath src/messages/card-content.test.ts
```

Expected: PASS.

- [ ] **Step 6: Commit Task 4**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol
git add protocol-layer/package.json protocol-layer/package-lock.json protocol-layer/src/messages/card-content.ts protocol-layer/src/messages/card-content.test.ts
git commit -m "feat: build whatsapp card message content"
```

---

## Task 5: Protocol Kafka Worker Card Sending

**Files:**
- Modify: `armada-protocol/protocol-layer/src/commands/worker-consumer.test.ts`
- Modify: `armada-protocol/protocol-layer/src/commands/worker-consumer.ts`

- [ ] **Step 1: Write failing worker tests**

Add `TINY_PNG_B64` near test helpers:

```ts
const TINY_PNG_B64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='
```

Add tests after the existing `message.send.requested` success test:

```ts
it('message.send.requested LINK_CARD sends link preview content', async () => {
  const calls: string[] = []
  const command: MasterCommandEnvelope = {
    commandId: 'cmd_link_card',
    type: 'message.send.requested',
    version: 'v1',
    accountId: 'acc_1',
    createdAt: '2026-07-06T00:00:00.000Z',
    payload: {
      tenantId: 1,
      marketingTaskId: 42,
      attemptId: 9003,
      targetId: 7003,
      roundNo: 2,
      accountId: 503,
      protocolAccountId: 'acc_1',
      groupJid: '120363003@g.us',
      messageType: 'LINK_CARD',
      text: '快来玩',
      linkCard: {
        url: 'https://example.com/promo',
        title: '大奖等你',
        description: '点击参与',
        thumbnail: { base64: TINY_PNG_B64, mimetype: 'image/png' }
      },
      source: 'marketing_task'
    }
  }
  const publish = jest.fn(async () => {
    calls.push('publish')
  })

  await executeWorkerCommand(command, {
    accounts: {
      offline: async () => undefined,
      getSocket: () => ({
        groupMetadata: async () => ({}),
        waUploadToServer: async () => ({ mediaUrl: 'https://cdn.test/thumb', directPath: '/v/t/thumb' }),
        sendMessage: async (jid, content) => {
          calls.push('send')
          expect(jid).toBe('120363003@g.us')
          expect(content.text).toContain('https://example.com/promo')
          expect((content.linkPreview as Record<string, unknown>)['canonical-url']).toBe('https://example.com/promo')
          return { key: { id: 'wamid.linkcard' }, messageTimestamp: 1783159200 }
        }
      } as never)
    },
    publisher: { publish },
    ack: async () => {
      calls.push('ack')
    }
  } as never)

  expect(publish).toHaveBeenCalledWith('message.send_result_reported', 'acc_1', expect.objectContaining({
    success: true,
    messageId: 'wamid.linkcard',
    attemptId: 9003
  }))
  expect(calls).toEqual(['send', 'publish', 'ack'])
})

it('message.send.requested BUTTON_CARD relays interactive native flow content', async () => {
  const calls: string[] = []
  const command: MasterCommandEnvelope = {
    commandId: 'cmd_button_card',
    type: 'message.send.requested',
    version: 'v1',
    accountId: 'acc_2',
    createdAt: '2026-07-06T00:00:00.000Z',
    payload: {
      tenantId: 1,
      marketingTaskId: 42,
      attemptId: 9004,
      targetId: 7004,
      roundNo: 2,
      accountId: 504,
      protocolAccountId: 'acc_2',
      groupJid: '120363004@g.us',
      messageType: 'BUTTON_CARD',
      text: '点下方按钮',
      buttonCard: {
        title: '限时活动',
        buttons: [{ type: 'link', displayText: '立即领取', value: 'https://promo.example/vip' }]
      },
      source: 'marketing_task'
    }
  }
  const publish = jest.fn(async () => {
    calls.push('publish')
  })

  await executeWorkerCommand(command, {
    accounts: {
      offline: async () => undefined,
      getSocket: () => ({
        user: { id: 'acc_2@s.whatsapp.net' },
        groupMetadata: async () => ({}),
        relayMessage: async (jid, message, opts) => {
          calls.push('relay')
          expect(jid).toBe('120363004@g.us')
          expect((message as any).viewOnceMessageV2Extension.message.interactiveMessage.nativeFlowMessage.buttons[0].name).toBe('cta_url')
          expect((opts as any).additionalNodes[0].tag).toBe('biz')
        }
      } as never)
    },
    publisher: { publish },
    ack: async () => {
      calls.push('ack')
    }
  } as never)

  expect(publish).toHaveBeenCalledWith('message.send_result_reported', 'acc_2', expect.objectContaining({
    success: true,
    attemptId: 9004
  }))
  expect(calls).toEqual(['relay', 'publish', 'ack'])
})

it('message.send.requested invalid BUTTON_CARD publishes invalid payload failure and ack', async () => {
  const calls: string[] = []
  const command: MasterCommandEnvelope = {
    commandId: 'cmd_button_invalid',
    type: 'message.send.requested',
    version: 'v1',
    accountId: 'acc_3',
    createdAt: '2026-07-06T00:00:00.000Z',
    payload: {
      tenantId: 1,
      marketingTaskId: 42,
      attemptId: 9005,
      targetId: 7005,
      roundNo: 2,
      protocolAccountId: 'acc_3',
      groupJid: '120363005@g.us',
      messageType: 'BUTTON_CARD',
      text: 'body',
      buttonCard: { title: 'T', buttons: [] },
      source: 'marketing_task'
    }
  }
  const publish = jest.fn(async () => {
    calls.push('publish')
  })

  await executeWorkerCommand(command, {
    accounts: {
      offline: async () => undefined,
      getSocket: () => {
        throw new Error('socket must not be requested')
      }
    },
    publisher: { publish },
    ack: async () => {
      calls.push('ack')
    }
  } as never)

  expect(publish).toHaveBeenCalledWith('message.send_result_reported', 'acc_3', expect.objectContaining({
    success: false,
    reasonCode: 'INVALID_MESSAGE_PAYLOAD',
    attemptId: 9005
  }))
  expect(calls).toEqual(['publish', 'ack'])
})
```

- [ ] **Step 2: Run worker tests and verify failure**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runTestsByPath src/commands/worker-consumer.test.ts
```

Expected: FAIL because worker only accepts `TEXT` / `LINK` / `IMAGE`.

- [ ] **Step 3: Extend socket interface and payload types**

In `worker-consumer.ts`, add import:

```ts
import {
  buildButtonCardMessage,
  buildLinkCardContent,
  type ButtonCardPayload,
  type LinkCardPayload
} from '../messages/card-content.js'
```

Extend `MessageSendResult` and `GroupMetadataSocket`:

```ts
type MessageSendResult = {
  key?: { id?: string | null; remoteJid?: string | null }
  messageTimestamp?: unknown
}

interface GroupMetadataSocket {
  user?: { id?: string }
  waUploadToServer?: unknown
  sendMessage?(jid: string, content: Record<string, unknown>): Promise<MessageSendResult | undefined>
  relayMessage?(jid: string, message: Record<string, unknown>, opts: Record<string, unknown>): Promise<unknown>
  groupMetadata(groupJid: string): Promise<{
    subject?: unknown
    participants?: unknown[]
    size?: unknown
  }>
  groupFetchAllParticipating?(): Promise<Record<string, AccountGroupMetadata>>
}
```

Change `MarketingMessageType`:

```ts
type MarketingMessageType = 'TEXT' | 'LINK' | 'IMAGE' | 'LINK_CARD' | 'BUTTON_CARD'
```

Extend `MessageSendPayload`:

```ts
  linkCard?: LinkCardPayload
  buttonCard?: ButtonCardPayload
```

- [ ] **Step 4: Parse card payloads**

Add parsing helpers near `marketingImagePayload(...)`:

```ts
function marketingLinkCardPayload(value: unknown): LinkCardPayload | undefined {
  if (!isRecord(value)) return undefined
  const url = nonBlankString(value.url)
  const title = nonBlankString(value.title)
  const thumbnail = marketingImagePayload(value.thumbnail)
  if (!url || !title || !thumbnail) return undefined
  return {
    url,
    title,
    description: typeof value.description === 'string' ? value.description : undefined,
    thumbnail
  }
}

function marketingButtonCardPayload(value: unknown): ButtonCardPayload | undefined {
  if (!isRecord(value) || !Array.isArray(value.buttons)) return undefined
  const buttons = value.buttons
    .filter(isRecord)
    .map(button => marketingButtonPayload(button))
    .filter((button): button is NonNullable<ReturnType<typeof marketingButtonPayload>> => button !== null)
  return {
    title: typeof value.title === 'string' ? value.title : undefined,
    footer: typeof value.footer === 'string' ? value.footer : undefined,
    buttons,
    thumbnail: marketingImagePayload(value.thumbnail)
  }
}

function marketingButtonPayload(button: Record<string, unknown>): ButtonCardPayload['buttons'][number] | null {
  const type = nonBlankString(button.type)
  const displayText = nonBlankString(button.displayText)
  if ((type !== 'link' && type !== 'copy' && type !== 'quick') || !displayText) return null
  return {
    type,
    displayText,
    value: nonBlankString(button.value)
  }
}
```

Update `messageSendPayload(...)`:

```ts
const linkCard = marketingLinkCardPayload(payload.linkCard)
const buttonCard = marketingButtonCardPayload(payload.buttonCard)
```

Add validation:

```ts
if (messageType === 'LINK_CARD' && !linkCard) {
  throw new Error('invalid message send link card payload')
}
if (messageType === 'BUTTON_CARD' && !buttonCard) {
  throw new Error('invalid message send button card payload')
}
if (messageType === 'BUTTON_CARD' && (
  buttonCard.buttons.length < 1 ||
  buttonCard.buttons.length > 3 ||
  buttonCard.buttons.some(button => button.type === 'link' && !button.value) ||
  buttonCard.buttons.some(button => button.type === 'copy' && !button.value)
)) {
  throw new Error('invalid message send button card payload')
}
```

Return card payloads:

```ts
linkCard: messageType === 'LINK_CARD' ? linkCard : undefined,
buttonCard: messageType === 'BUTTON_CARD' ? buttonCard : undefined,
```

Update `marketingMessageType(...)`:

```ts
return type === 'TEXT' || type === 'LINK' || type === 'IMAGE' || type === 'LINK_CARD' || type === 'BUTTON_CARD'
  ? type
  : null
```

- [ ] **Step 5: Publish invalid payload failures**

At the top of `executeMessageSend(...)`, replace the direct parse with:

```ts
let payload: MessageSendPayload
try {
  payload = messageSendPayload(command.payload)
} catch (error) {
  await publishInvalidMessagePayload(command, deps, error)
  return
}
```

Add function before `publishGroupHealthReport(...)`:

```ts
async function publishInvalidMessagePayload(
  command: MasterCommandEnvelope,
  deps: WorkerCommandExecutorDeps,
  error: unknown
): Promise<void> {
  if (!deps.publisher) {
    throw error
  }
  const base = invalidMessageResultBase(command)
  if (!base) {
    throw error
  }
  const reasonMessage = errorMessage(error)
  deps.logger?.warn({
    commandId: command.commandId,
    accountId: command.accountId,
    reasonCode: 'INVALID_MESSAGE_PAYLOAD',
    reasonMessage
  }, 'message send command invalid payload')
  await deps.publisher.publish('message.send_result_reported', command.accountId, {
    ...base,
    success: false,
    messageId: null,
    reasonCode: 'INVALID_MESSAGE_PAYLOAD',
    reasonMessage,
    timestamp: Date.now()
  })
  await deps.ack()
}

function invalidMessageResultBase(command: MasterCommandEnvelope): Record<string, unknown> | null {
  const payload = command.payload
  const tenantId = numericPayloadField(payload, 'tenantId')
  const marketingTaskId = numericPayloadField(payload, 'marketingTaskId')
  const attemptId = numericPayloadField(payload, 'attemptId')
  const targetId = numericPayloadField(payload, 'targetId')
  const roundNo = numericPayloadField(payload, 'roundNo')
  const groupJid = nonBlankString(payload.groupJid)
  if (tenantId === null || marketingTaskId === null || attemptId === null || targetId === null || roundNo === null || !groupJid) {
    return null
  }
  return {
    tenantId,
    marketingTaskId,
    attemptId,
    targetId,
    roundNo,
    accountId: numericPayloadField(payload, 'accountId') ?? undefined,
    protocolAccountId: nonBlankString(payload.protocolAccountId) ?? command.accountId,
    groupJid,
    commandId: command.commandId,
    source: typeof payload.source === 'string' ? payload.source : undefined
  }
}
```

- [ ] **Step 6: Dispatch link-card and button-card**

In `executeMessageSend(...)`, replace the send section inside `try`:

```ts
const sock = deps.accounts.getSocket(command.accountId)
if (payload.messageType === 'BUTTON_CARD') {
  if (!sock.relayMessage) {
    throw new Error('button card send requires socket.relayMessage')
  }
  const buttonMessage = await buildButtonCardMessage({
    jid: payload.groupJid,
    text: payload.text,
    card: payload.buttonCard!,
    sock
  })
  await sock.relayMessage(payload.groupJid, buttonMessage.message, buttonMessage.relayOptions)
  result = buttonMessage
} else if (payload.messageType === 'LINK_CARD') {
  if (!sock.sendMessage) {
    throw new Error('link card send requires socket.sendMessage')
  }
  result = await sock.sendMessage(payload.groupJid, await buildLinkCardContent({
    text: payload.text,
    card: payload.linkCard!,
    sock
  }))
} else {
  if (!sock.sendMessage) {
    throw new Error('message send requires socket.sendMessage')
  }
  result = await sock.sendMessage(payload.groupJid, messageContent(payload))
}
```

Update `imageInput(...)` to stay image-only:

```ts
function imageInput(payload: MessageSendPayload): string | undefined {
  if (payload.messageType !== 'IMAGE' || !payload.image) return undefined
  if (payload.image.base64) return 'base64'
  if (payload.image.url) return 'url'
  return undefined
}
```

- [ ] **Step 7: Run protocol worker tests and verify pass**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runTestsByPath src/commands/worker-consumer.test.ts src/messages/card-content.test.ts
```

Expected: PASS.

- [ ] **Step 8: Commit Task 5**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol
git add protocol-layer/src/commands/worker-consumer.ts protocol-layer/src/commands/worker-consumer.test.ts
git commit -m "feat: send marketing card commands"
```

---

## Task 6: Protocol HTTP Compatibility Routes

**Files:**
- Modify: `armada-protocol/protocol-layer/src/routes/messages.ts`
- Create: `armada-protocol/protocol-layer/src/routes/messages.link-button-card.test.ts`

- [ ] **Step 1: Write failing route tests**

Create `src/routes/messages.link-button-card.test.ts`:

```ts
import Fastify from 'fastify'

import { registerErrorHandler } from '../error/error-handler.js'
import { registerMessagesRoutes } from './messages.js'

const TINY_PNG_B64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='

function buildApp() {
  const sent: Array<{ jid: string; content: Record<string, unknown> }> = []
  const relayed: Array<{ jid: string; message: Record<string, unknown>; opts: Record<string, unknown> }> = []
  const fakeSock = {
    user: { id: 'acc_1@s.whatsapp.net' },
    waUploadToServer: async () => ({ mediaUrl: 'https://cdn.test/thumb', directPath: '/v/t/thumb' }),
    sendMessage: async (jid: string, content: Record<string, unknown>) => {
      sent.push({ jid, content })
      return { key: { id: 'MSG1', remoteJid: jid }, messageTimestamp: 1718000000 }
    },
    relayMessage: async (jid: string, message: Record<string, unknown>, opts: Record<string, unknown>) => {
      relayed.push({ jid, message, opts })
      return undefined
    }
  }
  const noopLogger: Record<string, unknown> = {
    info() {}, warn() {}, error() {}, debug() {},
    child() { return noopLogger }
  }
  const app = Fastify()
  registerMessagesRoutes(app, { logger: noopLogger, accounts: { getSocket: () => fakeSock } } as never)
  registerErrorHandler(app, noopLogger as never)
  return { app, sent, relayed }
}

describe('messages link/button card routes', () => {
  it('POST /v1/messages/link-card sends custom link preview', async () => {
    const { app, sent } = buildApp()
    const res = await app.inject({
      method: 'POST',
      url: '/v1/messages/link-card',
      payload: {
        accountId: 'acc_1',
        jid: '120363@g.us',
        text: '快来玩',
        url: 'https://example.com/promo',
        title: '大奖等你',
        description: '点击参与',
        thumbnail: { base64: TINY_PNG_B64, mimetype: 'image/png' }
      }
    })

    expect(res.statusCode).toBe(200)
    expect(sent).toHaveLength(1)
    expect((sent[0].content.linkPreview as Record<string, unknown>)['canonical-url']).toBe('https://example.com/promo')
    await app.close()
  })

  it('POST /v1/messages/button-card relays native flow buttons', async () => {
    const { app, relayed } = buildApp()
    const res = await app.inject({
      method: 'POST',
      url: '/v1/messages/button-card',
      payload: {
        accountId: 'acc_1',
        jid: '120363@g.us',
        text: '点下方按钮',
        title: '限时活动',
        buttons: [{ type: 'quick', displayText: '我要参加' }]
      }
    })

    expect(res.statusCode).toBe(200)
    expect(relayed).toHaveLength(1)
    expect((relayed[0].message as any).viewOnceMessageV2Extension.message.interactiveMessage.nativeFlowMessage.buttons[0].name).toBe('quick_reply')
    await app.close()
  })
})
```

- [ ] **Step 2: Run route tests and verify failure**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runTestsByPath src/routes/messages.link-button-card.test.ts
```

Expected: FAIL because routes are not registered.

- [ ] **Step 3: Add routes using shared helper**

In `src/routes/messages.ts`, add imports:

```ts
import {
  buildButtonCardMessage,
  buildLinkCardContent,
  type ButtonCardButtonType
} from '../messages/card-content.js'
```

Add route bodies after `/v1/messages/link`:

```ts
  app.post('/v1/messages/link-card', async (req, reply) => {
    const Body = z.object({
      accountId: z.string(),
      jid: z.string(),
      text: z.string().default(''),
      url: z.string().url(),
      title: z.string(),
      description: z.string().optional(),
      thumbnail: MediaInputShape
    })
    const b = Body.parse(req.body)
    const sock = ctx.accounts.getSocket(b.accountId)
    const content = await buildLinkCardContent({
      text: b.text,
      card: {
        url: b.url,
        title: b.title,
        description: b.description,
        thumbnail: b.thumbnail
      },
      sock
    })
    const r = await sock.sendMessage(b.jid, content as never)
    auditMessageSent(ctx, 'link-card', b.accountId, b.jid, r)
    reply.send({ messageId: r?.key.id, key: r?.key, timestamp: Number(r?.messageTimestamp ?? 0), status: 'pending' })
  })

  app.post('/v1/messages/button-card', async (req, reply) => {
    const ButtonShape = z.object({
      type: z.enum(['link', 'copy', 'quick']).default('link'),
      displayText: z.string(),
      value: z.string().optional().nullable()
    })
    const b = z.object({
      accountId: z.string(),
      jid: z.string(),
      text: z.string().default(''),
      footer: z.string().optional().nullable(),
      title: z.string().optional().nullable(),
      buttons: z.array(ButtonShape).min(1).max(3),
      thumbnail: MediaInputShape.optional().nullable()
    }).parse(req.body)
    const sock = ctx.accounts.getSocket(b.accountId)
    const built = await buildButtonCardMessage({
      jid: b.jid,
      text: b.text,
      card: {
        title: b.title,
        footer: b.footer,
        buttons: b.buttons.map(button => ({
          type: button.type as ButtonCardButtonType,
          displayText: button.displayText,
          value: button.value
        })),
        thumbnail: b.thumbnail
      },
      sock
    })
    await sock.relayMessage(b.jid, built.message as never, built.relayOptions as never)
    auditMessageSent(ctx, 'button-card', b.accountId, b.jid, built)
    reply.send({ messageId: built.key.id, key: built.key, timestamp: Number(built.messageTimestamp ?? 0), status: 'pending' })
  })
```

- [ ] **Step 4: Run route tests and verify pass**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runTestsByPath src/routes/messages.link-button-card.test.ts
```

Expected: PASS.

- [ ] **Step 5: Run protocol focused regression**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runTestsByPath src/messages/card-content.test.ts src/commands/worker-consumer.test.ts src/routes/messages.link-button-card.test.ts src/commands/types.test.ts
npm run lint
```

Expected: PASS for tests and TypeScript lint.

- [ ] **Step 6: Commit Task 6**

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol
git add protocol-layer/src/routes/messages.ts protocol-layer/src/routes/messages.link-button-card.test.ts
git commit -m "feat: expose link and button card message routes"
```

---

## Task 7: Frontend Button Template Save Guard

**Files:**
- Modify: `wheel-saas-pure-web/src/views/material/marketing-template/components/MarketingButtonEditor.vue`
- Modify: `wheel-saas-pure-web/src/views/material/marketing-template/components/MarketingTemplateDrawer.vue`
- Modify: `wheel-saas-pure-web/src/views/material/marketing-template/components/MarketingTemplateDrawer.test.ts`
- Modify: `wheel-saas-pure-web/src/views/material/marketing-template/composables/useMarketingTemplatePage.test.ts`

- [ ] **Step 1: Write failing frontend tests**

Append to `MarketingTemplateDrawer.test.ts`:

```ts
it("disables save when button template has no buttons", () => {
  assert.match(source, /const saveDisabled = computed/);
  assert.match(source, /form\.value\.linkMode === "BUTTON" && form\.value\.buttons\.length === 0/);
  assert.match(source, /:disabled="saveDisabled"/);
});
```

Append to `useMarketingTemplatePage.test.ts` before clone/delete test:

```ts
it("rejects button template without buttons before saving", async () => {
  resetArmadaMock({
    list: [],
    total: 0,
    page: 1,
    pageSize: 10
  });
  const pageState = useMarketingTemplatePage();
  pageState.openCreateDrawer();
  pageState.templateForm.value.templateName = "新模板";
  pageState.templateForm.value.linkMode = "BUTTON";
  pageState.templateForm.value.content = "标题";
  pageState.templateForm.value.text = "正文";
  pageState.templateForm.value.promotionLink = "https://promo.example/vip";
  pageState.templateForm.value.buttons = [];

  await pageState.saveTemplate();

  assert.deepEqual(armadaCalls(), []);
  assert.equal(pageState.drawerVisible.value, true);
});
```

Create `MarketingButtonEditor.test.ts`:

```ts
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

const source = readFileSync(
  new URL("./MarketingButtonEditor.vue", import.meta.url),
  "utf8"
);

describe("marketing button editor", () => {
  it("keeps at least one button during normal editing", () => {
    assert.match(source, /buttons\.length <= 1/);
    assert.match(source, /按钮超链至少需要 1 个按钮/);
  });
});
```

- [ ] **Step 2: Run frontend tests and verify failure**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --test src/views/material/marketing-template/components/MarketingTemplateDrawer.test.ts src/views/material/marketing-template/components/MarketingButtonEditor.test.ts src/views/material/marketing-template/composables/useMarketingTemplatePage.test.ts
```

Expected: FAIL because drawer has no `saveDisabled` and button editor allows deleting the last button.

- [ ] **Step 3: Disable save for empty button templates**

In `MarketingTemplateDrawer.vue`, change import:

```ts
import { computed, onBeforeUnmount, ref, watch } from "vue";
```

Add after `const isPreview = ...`:

```ts
const saveDisabled = computed(
  () => form.value.linkMode === "BUTTON" && form.value.buttons.length === 0
);
```

Change the Save button:

```vue
<el-button
  v-if="!isPreview()"
  type="primary"
  :loading="props.loading"
  :disabled="saveDisabled"
  @click="emit('save')"
>
  保存
</el-button>
```

- [ ] **Step 4: Prevent deleting the last normal button**

In `MarketingButtonEditor.vue`, change the delete button:

```vue
<el-button
  link
  type="danger"
  :disabled="disabled || buttons.length <= 1"
  :icon="useRenderIcon(Delete)"
  @click="removeButton(button.id)"
>
  删除
</el-button>
```

Add an empty state before `<div class="button-list">`:

```vue
<el-alert
  v-if="buttons.length === 0"
  class="button-empty-alert"
  type="warning"
  show-icon
  :closable="false"
  title="按钮超链至少需要 1 个按钮，请添加按钮后再保存。"
/>
```

Add style:

```css
.button-empty-alert {
  margin-bottom: 12px;
}
```

- [ ] **Step 5: Run frontend tests and verify pass**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --test src/views/material/marketing-template/components/MarketingTemplateDrawer.test.ts src/views/material/marketing-template/components/MarketingButtonEditor.test.ts src/views/material/marketing-template/composables/useMarketingTemplatePage.test.ts
```

Expected: PASS.

- [ ] **Step 6: Run frontend typecheck**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 7: Commit Task 7**

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git add src/views/material/marketing-template/components/MarketingButtonEditor.vue src/views/material/marketing-template/components/MarketingButtonEditor.test.ts src/views/material/marketing-template/components/MarketingTemplateDrawer.vue src/views/material/marketing-template/components/MarketingTemplateDrawer.test.ts src/views/material/marketing-template/composables/useMarketingTemplatePage.test.ts
git commit -m "fix: require marketing button templates to keep a button"
```

---

## Task 8: Final Cross-Repo Verification

**Files:**
- No new code files.

- [ ] **Step 1: Run Armada focused tests**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest=MarketingMessageComposerTest,MarketingRoundWorkerTest,ProtocolCommandOutboxServiceImplTest,MarketingTemplateServiceImplTest test
```

Expected: PASS.

- [ ] **Step 2: Run protocol focused tests and TypeScript check**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test -- --runTestsByPath src/messages/card-content.test.ts src/commands/worker-consumer.test.ts src/routes/messages.link-button-card.test.ts src/commands/types.test.ts
npm run lint
```

Expected: PASS.

- [ ] **Step 3: Run frontend focused tests and typecheck**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
node --test src/api/marketing-template.test.ts src/views/material/marketing-template/components/MarketingTemplateDrawer.test.ts src/views/material/marketing-template/components/MarketingButtonEditor.test.ts src/views/material/marketing-template/composables/useMarketingTemplatePage.test.ts
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 4: Inspect git status in each repo**

Run:

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
git status --short
cd /Users/daishuaishuai/IdeaProjects/armada-protocol
git status --short
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
git status --short
```

Expected:

- `armada` may still show pre-existing unrelated dirty files, but no unstaged changes from this plan.
- `armada-protocol` should be clean except user-owned unrelated changes if any existed before execution.
- `wheel-saas-pure-web` should be clean except user-owned unrelated changes if any existed before execution.

- [ ] **Step 5: Summarize implementation evidence**

Report:

```text
Armada:
- NORMAL + image + http(s) promotionLink now emits LINK_CARD.
- BUTTON + valid buttons now emits BUTTON_CARD.
- BUTTON with no valid buttons is not downgraded to TEXT.

Protocol:
- LINK_CARD sends Baileys linkPreview with highQualityThumbnail.
- BUTTON_CARD relays NativeFlow interactive buttons.
- Invalid card payload publishes INVALID_MESSAGE_PAYLOAD when task refs are parseable.

Frontend:
- BUTTON templates cannot be saved without at least one button.
- The last button cannot be removed in normal editing.
```
