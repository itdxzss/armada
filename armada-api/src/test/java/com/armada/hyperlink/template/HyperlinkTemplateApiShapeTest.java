package com.armada.hyperlink.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.hyperlink.template.model.HyperlinkButton;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateCreateDTO;
import com.armada.hyperlink.template.model.enums.HyperlinkButtonType;
import com.armada.hyperlink.template.model.vo.HyperlinkTemplateDetailVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 超链模板 camelCase JSON 字段和固定枚举形状测试。 */
class HyperlinkTemplateApiShapeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createRequestDeserializesFrozenCamelCaseShape() throws Exception {
        HyperlinkTemplateCreateDTO request = objectMapper.readValue("""
                {
                  "name": "普通按钮",
                  "schemaVersion": 1,
                  "messageType": 3,
                  "title": "新人福利",
                  "content": null,
                  "linkDescription": null,
                  "promotionLink": null,
                  "buttons": [{
                    "type": "CTA_URL",
                    "displayText": "立即查看",
                    "targetValue": "https://example.com/promo",
                    "useShortLink": true,
                    "sort": 1
                  }],
                  "cardText": null,
                  "linkPreviewAssetId": null,
                  "bodyMainAssetId": 123,
                  "remark": null
                }
                """, HyperlinkTemplateCreateDTO.class);

        assertThat(request.name()).isEqualTo("普通按钮");
        assertThat(request.schemaVersion()).isEqualTo(1);
        assertThat(request.messageType()).isEqualTo(3);
        assertThat(request.buttons()).singleElement().satisfies(button -> {
            assertThat(button.type()).isEqualTo(HyperlinkButtonType.CTA_URL);
            assertThat(button.useShortLink()).isTrue();
            assertThat(button.sort()).isEqualTo(1);
        });
    }

    @Test
    void unknownButtonTypeDeserializesForBusinessValidationInsteadOfCausingUnexpectedError() throws Exception {
        HyperlinkButton button = objectMapper.readValue("""
                {
                  "type": "CTA_CALL",
                  "displayText": "呼叫",
                  "targetValue": "https://example.com",
                  "useShortLink": false,
                  "sort": 1
                }
                """, HyperlinkButton.class);

        assertThat(button.type()).isNull();
    }

    @Test
    void detailResponseContainsEveryContractFieldWithoutDatabaseAliases() {
        HyperlinkTemplateDetailVO detail = new HyperlinkTemplateDetailVO(
                301L, "模板", null, 1, 3, "标题", null, null, null, List.of(),
                null, null, null, 123L, "/api/marketing-template-files/123/content",
                0L, 1, null, 100L, 100L);

        JsonNode json = objectMapper.valueToTree(detail);

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "name", "remark", "schemaVersion", "messageType", "title", "content",
                "linkDescription", "promotionLink", "buttons", "cardText", "linkPreviewAssetId",
                "linkPreviewAssetUrl", "bodyMainAssetId", "bodyMainAssetUrl", "taskRefCount",
                "version", "createdBy", "createdAt", "updatedAt");
        assertThat(json.has("templateName")).isFalse();
        assertThat(json.has("messageSchemaVersion")).isFalse();
        assertThat(json.get("buttons").isArray()).isTrue();
        assertThat(json.get("taskRefCount").asLong()).isZero();
    }
}
