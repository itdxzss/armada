package com.armada.marketing.service;

import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketingMessageComposerTest {

    private final MarketingMessageComposer composer = new MarketingMessageComposer();

    @Test
    void normalLinkComposesLinkText() {
        MarketingTemplate template = template(LinkMode.NORMAL.code(), null);
        template.setContent("标题");
        template.setBodyText("正文");
        template.setPromotionLink("https://example.com");

        MarketingMessageComposer.ComposedMessage message = composer.compose(template, null);

        assertThat(message.messageType()).isEqualTo("LINK");
        assertThat(message.text()).contains("标题", "正文", "https://example.com");
        assertThat(message.imageBytes()).isNull();
    }

    @Test
    void imageTextWithFileComposesImagePayload() {
        MarketingTemplate template = template(LinkMode.IMAGE_TEXT.code(), 99L);
        template.setContent("标题");
        template.setBodyText("正文");
        MarketingTemplateFile file = new MarketingTemplateFile();
        file.setContent(new byte[] {1, 2, 3});
        file.setContentType("image/png");

        MarketingMessageComposer.ComposedMessage message = composer.compose(template, file);

        assertThat(message.messageType()).isEqualTo("IMAGE");
        assertThat(message.text()).contains("标题", "正文");
        assertThat(message.imageBytes()).containsExactly(1, 2, 3);
        assertThat(message.imageMimetype()).isEqualTo("image/png");
    }

    @Test
    void buttonModeFallsBackToText() {
        MarketingTemplate template = template(LinkMode.BUTTON.code(), null);
        template.setContent("按钮标题");
        template.setBodyText("按钮正文");

        MarketingMessageComposer.ComposedMessage message = composer.compose(template, null);

        assertThat(message.messageType()).isEqualTo("TEXT");
        assertThat(message.text()).contains("按钮标题", "按钮正文");
        assertThat(message.imageBytes()).isNull();
    }

    private static MarketingTemplate template(Integer linkMode, Long imageFileId) {
        MarketingTemplate template = new MarketingTemplate();
        template.setId(10L);
        template.setTemplateName("template");
        template.setLinkMode(linkMode);
        template.setImageFileId(imageFileId);
        return template;
    }
}
