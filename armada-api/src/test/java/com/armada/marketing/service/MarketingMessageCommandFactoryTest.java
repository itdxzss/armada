package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.support.MarketingResolvedTarget;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import org.junit.jupiter.api.Test;

class MarketingMessageCommandFactoryTest {

    @Test
    void composeTaskMessage_readsTemplateAndImageThroughCurrentOwnerScope() {
        MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
        MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
        MarketingMessageCommandFactory factory = new MarketingMessageCommandFactory(
                templateMapper, fileMapper, new MarketingMessageComposer());
        MarketingTask task = task();
        task.setOwnerUserId(81L);
        var template = new com.armada.marketing.model.entity.MarketingTemplate();
        template.setId(77L);
        template.setOwnerUserId(81L);
        template.setLinkMode(3);
        template.setContent("hello");
        template.setImageFileId(88L);
        var image = new com.armada.marketing.model.entity.MarketingTemplateFile();
        image.setId(88L);
        image.setOwnerUserId(81L);
        image.setContent(new byte[]{1, 2, 3});
        image.setContentType("image/png");
        DataScope scope = DataScope.self(81L);
        when(templateMapper.selectByIdForScope(77L, scope)).thenReturn(template);
        when(fileMapper.selectByIdForScope(88L, scope)).thenReturn(image);

        MarketingMessageComposer.ComposedMessage message;
        try (DataScopeContext.Scope ignored = DataScopeContext.open(scope)) {
            message = factory.composeTaskMessage(task);
        }

        assertThat(message.text()).isEqualTo("hello");
        assertThat(message.imageBytes()).containsExactly(1, 2, 3);
        verify(templateMapper).selectByIdForScope(eq(77L), eq(scope));
        verify(fileMapper).selectByIdForScope(eq(88L), eq(scope));
    }

    @Test
    void composeTaskMessage_rejectsCrossOwnerTemplateEvenInAdminScope() {
        MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
        MarketingMessageCommandFactory factory = new MarketingMessageCommandFactory(
                templateMapper,
                mock(MarketingTemplateFileMapper.class),
                new MarketingMessageComposer());
        MarketingTask task = task();
        task.setOwnerUserId(81L);
        var template = new com.armada.marketing.model.entity.MarketingTemplate();
        template.setId(77L);
        template.setOwnerUserId(82L);
        DataScope scope = DataScope.all(9_001L);
        when(templateMapper.selectByIdForScope(77L, scope)).thenReturn(template);

        try (DataScopeContext.Scope ignored = DataScopeContext.open(scope)) {
            assertThatThrownBy(() -> factory.composeTaskMessage(task))
                    .hasMessageContaining("营销任务与模板归属不一致");
        }
    }

    @Test
    void toCommand_preservesAndroidRoutingRoundAndDelay() {
        MarketingTemplateMapper templateMapper = mock(MarketingTemplateMapper.class);
        MarketingTemplateFileMapper fileMapper = mock(MarketingTemplateFileMapper.class);
        MarketingMessageCommandFactory factory = new MarketingMessageCommandFactory(
                templateMapper, fileMapper, new MarketingMessageComposer());
        MarketingTask task = task();
        MarketingTaskTarget target = target();
        MarketingTaskSendAttempt attempt = attempt();
        MarketingResolvedTarget resolved = new MarketingResolvedTarget(
                target, 301L, "120363new@g.us", "新群");
        MarketingMessageComposer.ComposedMessage message =
                new MarketingMessageComposer.ComposedMessage(
                        "TEXT", "hello", null, null, false);

        MessageSendCommand command = factory.toCommand(
                task, resolved, attempt, message, 2_750L);

        assertThat(command.account().backend()).isEqualTo(ProtocolBackend.ANDROID);
        assertThat(command.correlation().marketing().roundNo()).isZero();
        assertThat(command.commandId()).isEqualTo("cmd_immediate");
        assertThat(command.sendIntervalMs()).isEqualTo(750);
        assertThat(command.notBeforeAt()).isEqualTo(2_750L);
    }

    private static MarketingTask task() {
        MarketingTask task = new MarketingTask();
        task.setId(42L);
        task.setTenantId(1L);
        task.setMarketingTemplateId(77L);
        task.setAccountGroupSendIntervalMs(750);
        return task;
    }

    private static MarketingTaskTarget target() {
        MarketingTaskTarget target = new MarketingTaskTarget();
        target.setId(501L);
        target.setMarketingTaskId(42L);
        target.setAccountId(5_001L);
        target.setProtocolId("ANDROID");
        target.setProtocolAccountId("acc_5001");
        target.setProtocolWsPhone("923000001");
        return target;
    }

    private static MarketingTaskSendAttempt attempt() {
        MarketingTaskSendAttempt attempt = new MarketingTaskSendAttempt();
        attempt.setId(9_001L);
        attempt.setMarketingTaskId(42L);
        attempt.setTargetId(501L);
        attempt.setCommandId("cmd_immediate");
        attempt.setRoundNo(0L);
        return attempt;
    }
}
