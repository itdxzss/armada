package com.armada.hyperlink.template.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.hyperlink.template.model.HyperlinkButton;
import com.armada.hyperlink.template.model.HyperlinkMessageContent;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateCreateDTO;
import com.armada.hyperlink.template.model.entity.HyperlinkTemplate;
import com.armada.hyperlink.template.model.enums.HyperlinkButtonType;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** 超链模板 MapStruct 转换和按钮 JSON 合同测试。 */
class HyperlinkTemplateConverterTest {

    private final HyperlinkTemplateConverter converter =
            Mappers.getMapper(HyperlinkTemplateConverter.class);

    @Test
    void createContentEntityAndDetailRoundTripFrozenButtonFields() {
        HyperlinkButton button = new HyperlinkButton(
                HyperlinkButtonType.CTA_URL,
                "立即查看",
                "https://example.com/promo",
                true,
                1);
        HyperlinkTemplateCreateDTO request = new HyperlinkTemplateCreateDTO(
                "按钮模板", 1, 3, "新人福利", "正文", null, null,
                java.util.List.of(button), null, null, 123L, "备注");
        HyperlinkMessageContent content = converter.toContent(request);

        HyperlinkTemplate entity = converter.toEntity(request.name(), request.remark(), content);
        entity.setId(301L);
        entity.setVersion(1);
        entity.setCreatedBy(7L);
        entity.setCreatedAt(100L);
        entity.setUpdatedAt(100L);

        assertThat(entity.getTemplateName()).isEqualTo("按钮模板");
        assertThat(entity.getMessageSchemaVersion()).isEqualTo(1);
        assertThat(entity.getButtons()).contains("CTA_URL", "displayText", "targetValue", "useShortLink", "sort");
        assertThat(converter.toDetail(entity).buttons()).containsExactly(button);
        assertThat(converter.toDetail(entity).bodyMainAssetUrl())
                .isEqualTo("/api/marketing-template-files/123/content");
        assertThat(converter.toDetail(entity).linkPreviewAssetUrl()).isNull();
        assertThat(converter.toDetail(entity).taskRefCount()).isZero();
    }

    @Test
    void emptyButtonsPersistAndReturnAsEmptyArray() {
        assertThat(converter.buttonsToJson(java.util.List.of())).isEqualTo("[]");
        assertThat(converter.buttonsFromJson(null)).isEmpty();
        assertThat(converter.buttonsFromJson("[]")).isEmpty();
    }
}
