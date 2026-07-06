package com.armada.marketing.service;

import com.armada.marketing.model.ButtonType;
import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.MessageButton;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketingMessageComposerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    private static MarketingTemplate template(Integer linkMode, Long imageFileId) {
        MarketingTemplate template = new MarketingTemplate();
        template.setId(10L);
        template.setTemplateName("template");
        template.setLinkMode(linkMode);
        template.setImageFileId(imageFileId);
        return template;
    }
}
