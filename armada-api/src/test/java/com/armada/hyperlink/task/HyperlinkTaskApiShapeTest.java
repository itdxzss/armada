package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.hyperlink.task.controller.HyperlinkTaskController;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskSaveDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** 冻结 H2/H3 唯一 Save wire，避免内部模板模型字段泄漏到任务接口。 */
class HyperlinkTaskApiShapeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void saveRequestUsesOuterMessageTypeAndUrlButtonContract() throws Exception {
        HyperlinkTaskSaveDTO request = objectMapper.readValue(validJson(""), HyperlinkTaskSaveDTO.class);

        assertThat(request.messageType()).isEqualTo(3);
        assertThat(request.messageContent().buttons()).singleElement().satisfies(button -> {
            assertThat(button.url()).isEqualTo("https://example.com/promo");
            assertThat(button.displayText()).isEqualTo("立即查看");
        });
        assertThat(objectMapper.valueToTree(request.messageContent()).fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "linkPreviewAssetId", "title", "linkDescription", "promotionLink",
                        "bodyMainAssetId", "content", "cardText", "buttons");
    }

    @Test
    void saveRequestRejectsTemplateOnlyNestedFields() {
        assertThatThrownBy(() -> objectMapper.readValue(
                validJson("\"schemaVersion\":1,"),
                HyperlinkTaskSaveDTO.class))
                .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> objectMapper.readValue(
                validJson("\"messageType\":3,"),
                HyperlinkTaskSaveDTO.class))
                .hasMessageContaining("messageType");
        assertThatThrownBy(() -> objectMapper.readValue(
                validJson("", "\"targetValue\":\"https://bad.example\","),
                HyperlinkTaskSaveDTO.class))
                .hasMessageContaining("targetValue");
        assertThatThrownBy(() -> objectMapper.readValue(
                validJson("", "\"sort\":1,"), HyperlinkTaskSaveDTO.class))
                .hasMessageContaining("sort");
    }

    @Test
    void accountFilterRejectsUnknownKeys() {
        assertThatThrownBy(() -> objectMapper.readValue(
                validJson("").replace("\"filterSchemaVersion\":1",
                        "\"filterSchemaVersion\":1,\"silentUnknown\":true"),
                HyperlinkTaskSaveDTO.class))
                .hasMessageContaining("silentUnknown");
    }

    @Test
    void createContextIsAvailableToCreateEditAndViewPermissions() throws Exception {
        PreAuthorize permission = HyperlinkTaskController.class
                .getMethod("createContext")
                .getAnnotation(PreAuthorize.class);

        assertThat(permission.value())
                .contains("tenant:hyperlink_task:create")
                .contains("tenant:hyperlink_task:edit")
                .contains("tenant:hyperlink_task:view");
    }

    private static String validJson(String contentPrefix) {
        return validJson(contentPrefix, "");
    }

    private static String validJson(String contentPrefix, String buttonPrefix) {
        return """
                {
                  "version":null,
                  "sourceTaskId":null,
                  "taskName":"H3 contract",
                  "messageType":3,
                  "messageContent":{
                    %s
                    "linkPreviewAssetId":null,
                    "title":"Title",
                    "linkDescription":null,
                    "promotionLink":null,
                    "bodyMainAssetId":null,
                    "content":"Body",
                    "cardText":null,
                    "buttons":[{%s"type":"CTA_URL","displayText":"立即查看","url":"https://example.com/promo","useShortLink":false}]
                  },
                  "taskMode":"instant",
                  "plannedEndAt":null,
                  "cycleIntervalMinutes":0,
                  "accountFilter":{"filterSchemaVersion":1},
                  "messageIntervalMinSeconds":0.5,
                  "messageIntervalMaxSeconds":0.7,
                  "maxExecutingAccounts":1,
                  "maxUseAccounts":1,
                  "maxSendPerAccount":0,
                  "startMode":"now",
                  "delayMinutes":0,
                  "dataPackageId":null,
                  "enabled":false,
                  "quoteToken":null
                }
                """.formatted(contentPrefix, buttonPrefix);
    }
}
