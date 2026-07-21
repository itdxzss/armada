package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.entity.MarketingTaskSendAttempt;
import com.armada.marketing.model.entity.MarketingTaskTarget;
import com.armada.marketing.model.support.MarketingResolvedTarget;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import org.junit.jupiter.api.Test;

class MarketingMessageCommandFactoryTest {

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
