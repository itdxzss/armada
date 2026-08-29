package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskContent;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.service.HyperlinkMessageCommandFactory;
import com.armada.hyperlink.task.service.HyperlinkShortLinkGuard;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** hyperlink 命令保持单 recipient 单 command，且不引入第二套消息内容 wire。 */
class HyperlinkMessageCommandFactoryTest {

    @Test
    void buttonContentMapsToExistingButtonCardWireWithStablePrivateTarget() {
        HyperlinkMessageCommandFactory factory = new HyperlinkMessageCommandFactory(
                mock(MarketingTemplateFileService.class), new ObjectMapper(),
                new HyperlinkShortLinkGuard("https://links.example.test/root/"));

        var first = factory.create(task(), content(), recipient(), usage(), 1000L);
        var replay = factory.create(task(), content(), recipient(), usage(), 1000L);

        assertThat(first.commandId()).isEqualTo("hl:7:11:13").isEqualTo(replay.commandId());
        assertThat(first.target().jid()).isEqualTo("8613800000000@s.whatsapp.net");
        assertThat(first.target().kind().name()).isEqualTo("PRIVATE");
        assertThat(first.correlation().hyperlink().taskId()).isEqualTo(11L);
        assertThat(first.correlation().hyperlink().recipientId()).isEqualTo(13L);
        assertThat(first.correlation().marketing()).isNull();
        assertThat(first.payload().type()).isEqualTo(MessageType.BUTTON_CARD);
        assertThat(first.payload().content().text()).isEqualTo("正文");
        assertThat(first.payload().content().buttonCard().title()).isEqualTo("标题");
        assertThat(first.payload().content().buttonCard().footer()).isEqualTo("卡片文字");
        assertThat(first.payload().content().buttonCard().buttons()).singleElement()
                .satisfies(button -> {
                    assertThat(button.displayText()).isEqualTo("查看");
                    assertThat(button.value()).isEqualTo("https://example.com/promo");
                });
    }

    @Test
    void enabledShortLinkReplacesOnlyTheFrozenButtonUrl() {
        HyperlinkMessageCommandFactory factory = new HyperlinkMessageCommandFactory(
                mock(MarketingTemplateFileService.class), new ObjectMapper(),
                new HyperlinkShortLinkGuard("https://links.example.test/root/"));
        HyperlinkTask task = task();
        task.setShortLinkEnabled(true);
        HyperlinkTaskContent content = content();
        content.setButtons("[{\"type\":\"CTA_URL\",\"displayText\":\"查看\","
                + "\"targetValue\":\"https://example.com/promo\","
                + "\"useShortLink\":true,\"sort\":1}]");
        HyperlinkTaskRecipient recipient = recipient();
        recipient.setShortCode("AbCdEf0123_-xyZ9");

        var command = factory.create(task, content, recipient, usage(), 1000L);

        assertThat(command.payload().content().buttonCard().buttons()).singleElement()
                .satisfies(button -> assertThat(button.value())
                        .isEqualTo("https://links.example.test/root/api/public/hl/AbCdEf0123_-xyZ9"));
    }

    @Test
    void enabledShortLinkReplacesTheFrozenLinkCardPromotionUrl() {
        HyperlinkMessageCommandFactory factory = new HyperlinkMessageCommandFactory(
                mock(MarketingTemplateFileService.class), new ObjectMapper(),
                new HyperlinkShortLinkGuard("https://links.example.test"));
        HyperlinkTask task = task();
        task.setShortLinkEnabled(true);
        HyperlinkTaskContent content = content();
        content.setMessageType(1);
        content.setPromotionLink("https://example.com/original");
        content.setLinkDescription("描述");
        HyperlinkTaskRecipient recipient = recipient();
        recipient.setShortCode("AbCdEf0123_-xyZ9");

        var command = factory.create(task, content, recipient, usage(), 1000L);

        assertThat(command.payload().content().linkCard().url())
                .isEqualTo("https://links.example.test/api/public/hl/AbCdEf0123_-xyZ9");
        assertThat(content.getPromotionLink()).isEqualTo("https://example.com/original");
    }

    @Test
    void historicalDoubleImageFailsClosedInsteadOfFallingThroughToButtonWire() {
        MarketingTemplateFileService files = mock(MarketingTemplateFileService.class);
        HyperlinkMessageCommandFactory factory = new HyperlinkMessageCommandFactory(
                files, new ObjectMapper(), new HyperlinkShortLinkGuard(""));
        HyperlinkTaskContent content = content();
        content.setMessageType(2);
        content.setButtons(null);
        content.setLinkPreviewAssetId(101L);
        content.setBodyMainAssetId(102L);

        assertThatThrownBy(() -> factory.create(task(), content, recipient(), usage(), 1000L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(exception.getMessage()).contains("历史双图文").contains("禁止");
                });
        verifyNoInteractions(files);
    }

    private HyperlinkTask task() {
        HyperlinkTask value = new HyperlinkTask();
        value.setId(11L);
        value.setTenantId(7L);
        value.setMsgIntervalMinMs(500);
        return value;
    }

    private HyperlinkTaskContent content() {
        HyperlinkTaskContent value = new HyperlinkTaskContent();
        value.setMessageType(4);
        value.setTitle("标题");
        value.setContent("正文");
        value.setCardText("卡片文字");
        value.setButtons("[{\"type\":\"CTA_URL\",\"displayText\":\"查看\","
                + "\"targetValue\":\"https://example.com/promo\","
                + "\"useShortLink\":false,\"sort\":1}]");
        return value;
    }

    private HyperlinkTaskRecipient recipient() {
        HyperlinkTaskRecipient value = new HyperlinkTaskRecipient();
        value.setId(13L);
        value.setRecipientPhoneSnapshot("8613800000000");
        return value;
    }

    private HyperlinkTaskAccountUsage usage() {
        HyperlinkTaskAccountUsage value = new HyperlinkTaskAccountUsage();
        value.setAccountId(17L);
        value.setAccountPhoneSnapshot("8613900000000");
        value.setProtocolAccountIdSnapshot("acc_17");
        value.setProtocolBackend(1);
        return value;
    }
}
