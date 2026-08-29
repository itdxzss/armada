package com.armada.contact.task;

import com.armada.account.selection.model.SelectedAccount;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.contact.task.service.ContactTaskMessageCommandFactory;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 通讯录消息命令组装的纯 Mockito 测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactTaskMessageCommandFactoryTest {

    @Mock
    private MarketingTemplateFileMapper fileMapper;

    private ContactTaskMessageCommandFactory factory() {
        return new ContactTaskMessageCommandFactory(fileMapper);
    }

    private static ContactFriendTask linkTask() {
        ContactFriendTask task = new ContactFriendTask();
        task.setId(1L);
        task.setTenantId(5L);
        task.setMessageType(0);
        task.setTitle("限时优惠");
        task.setDescription("点开看看");
        task.setPromotionLink("https://example.com/promo");
        task.setContent("正文");
        task.setMsgIntervalMinSec(new BigDecimal("1.0"));
        task.setMsgIntervalMaxSec(new BigDecimal("1.0"));
        return task;
    }

    private static ContactFriendTask imageTask() {
        ContactFriendTask task = new ContactFriendTask();
        task.setId(1L);
        task.setTenantId(5L);
        task.setMessageType(1);
        task.setContent("图文文案");
        task.setMsgIntervalMinSec(new BigDecimal("0.5"));
        task.setMsgIntervalMaxSec(new BigDecimal("0.5"));
        return task;
    }

    private static ContactFriendTaskAccount accountRow() {
        ContactFriendTaskAccount row = new ContactFriendTaskAccount();
        row.setId(101L);
        row.setAccountId(11L);
        return row;
    }

    private static ContactFriendTaskRecipient recipient() {
        ContactFriendTaskRecipient row = new ContactFriendTaskRecipient();
        row.setId(999L);
        row.setContactPhone("8613900000001");
        row.setContactJid("8613900000001@s.whatsapp.net");
        return row;
    }

    private static SelectedAccount protocolFacts() {
        return new SelectedAccount(11L, "8613800000000", "web", "acc_8613800000000");
    }

    @Test
    void composesLinkCardForLinkMessageType() {
        ContactTaskMessageCommandFactory.ComposedContactMessage content =
                factory().composeContent(linkTask());

        assertThat(content.type()).isEqualTo(MessageType.LINK_CARD);
        assertThat(content.linkUrl()).isEqualTo("https://example.com/promo");
        assertThat(content.linkTitle()).isEqualTo("限时优惠");
        assertThat(content.linkDescription()).isEqualTo("点开看看");
        assertThat(content.text()).isEqualTo("正文");
    }

    @Test
    void composesImageWhenPictureMessageHasFile() {
        ContactFriendTask task = imageTask();
        task.setPreviewImageFileId(77L);
        MarketingTemplateFile file = new MarketingTemplateFile();
        file.setContent(new byte[]{1, 2, 3});
        file.setContentType("image/png");
        when(fileMapper.selectById(77L)).thenReturn(file);

        ContactTaskMessageCommandFactory.ComposedContactMessage content =
                factory().composeContent(task);

        assertThat(content.type()).isEqualTo(MessageType.IMAGE);
        assertThat(content.imageBytes()).containsExactly(1, 2, 3);
        assertThat(content.imageMimetype()).isEqualTo("image/png");
        assertThat(content.text()).isEqualTo("图文文案");
    }

    @Test
    void fallsBackToTextWhenPictureMessageHasNoFile() {
        ContactTaskMessageCommandFactory.ComposedContactMessage content =
                factory().composeContent(imageTask());

        assertThat(content.type()).isEqualTo(MessageType.TEXT);
        assertThat(content.imageBytes()).isNull();
        assertThat(content.text()).isEqualTo("图文文案");
    }

    @Test
    void neverProducesButtonCard() {
        // 竞品的通讯录消息没有按钮（设计 §2.3），任何 message_type 都不该出现 BUTTON_CARD
        assertThat(factory().composeContent(linkTask()).type())
                .isNotEqualTo(MessageType.BUTTON_CARD);
        assertThat(factory().composeContent(imageTask()).type())
                .isNotEqualTo(MessageType.BUTTON_CARD);

        MessageSendCommand command = factory().toCommand(
                linkTask(), accountRow(), recipient(), protocolFacts(),
                factory().composeContent(linkTask()), 5L, 0L, new Random(1L));
        assertThat(command.payload().content().buttonCard()).isNull();
    }

    @Test
    void targetsPeerJidNotGroupJid() {
        MessageSendCommand command = factory().toCommand(
                linkTask(), accountRow(), recipient(), protocolFacts(),
                factory().composeContent(linkTask()), 5L, 0L, new Random(1L));

        assertThat(command.target().jid()).isEqualTo("8613900000001@s.whatsapp.net");
    }

    @Test
    void carriesAllFourContactCorrelationFields() {
        // 缺任一协议层就判 invalid message send payload 丢弃
        MessageSendCommand command = factory().toCommand(
                linkTask(), accountRow(), recipient(), protocolFacts(),
                factory().composeContent(linkTask()), 5L, 0L, new Random(1L));

        MessageSendCommand.ContactTaskCorrelation correlation = command.correlation().contactTask();
        assertThat(correlation.taskId()).isEqualTo(1L);
        assertThat(correlation.taskAccountId()).isEqualTo(101L);
        assertThat(correlation.recipientId()).isEqualTo(999L);
        assertThat(correlation.roundNo()).isEqualTo(5L);
        assertThat(command.correlation().source()).isEqualTo("contact_task");
        assertThat(command.correlation().tenantId()).isEqualTo(5L);
        assertThat(command.correlation().marketing()).isNull();
    }

    @Test
    void resolvesProtocolBackendFromAccountProtocolId() {
        MessageSendCommand command = factory().toCommand(
                linkTask(), accountRow(), recipient(), protocolFacts(),
                factory().composeContent(linkTask()), 5L, 0L, new Random(1L));

        assertThat(command.account().backend()).isEqualTo(ProtocolBackend.WEB);
        assertThat(command.account().protocolAccountId()).isEqualTo("acc_8613800000000");
        assertThat(command.account().wsPhone()).isEqualTo("8613800000000");
        assertThat(command.account().armadaAccountId()).isEqualTo(11L);
    }

    @Test
    void picksSendIntervalInsideConfiguredRange() {
        ContactFriendTask task = linkTask();
        task.setMsgIntervalMinSec(new BigDecimal("0.5"));
        task.setMsgIntervalMaxSec(new BigDecimal("3.0"));

        MessageSendCommand command = factory().toCommand(
                task, accountRow(), recipient(), protocolFacts(),
                factory().composeContent(task), 5L, 0L, new Random(42L));

        assertThat(command.sendIntervalMs()).isBetween(500, 3000);
    }

    @Test
    void reusesRecipientCommandIdWhenAlreadyClaimed() {
        // 抢批时已生成 commandId，组命令时必须复用，否则 outbox 与 recipient 对不上
        ContactFriendTaskRecipient claimed = recipient();
        claimed.setCommandId("cmd_already_claimed");

        MessageSendCommand command = factory().toCommand(
                linkTask(), accountRow(), claimed, protocolFacts(),
                factory().composeContent(linkTask()), 5L, 0L, new Random(1L));

        assertThat(command.commandId()).isEqualTo("cmd_already_claimed");
    }

    @Test
    void generatesDistinctCommandIdsWithMarketingPrefix() {
        ContactTaskMessageCommandFactory factory = factory();

        String first = factory.newCommandId();
        String second = factory.newCommandId();

        assertThat(first).startsWith("cmd_");
        assertThat(first).isNotEqualTo(second);
    }
}
