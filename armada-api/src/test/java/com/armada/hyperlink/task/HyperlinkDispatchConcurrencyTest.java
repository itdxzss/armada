package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountHyperlinkCandidateService;
import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskContentMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRound;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRoundStatus;
import com.armada.hyperlink.task.port.HyperlinkPrivateCapabilityPort;
import com.armada.hyperlink.task.service.HyperlinkDispatchService;
import com.armada.hyperlink.task.service.HyperlinkAccountDispatchGuard;
import com.armada.hyperlink.task.service.HyperlinkMessageCommandFactory;
import com.armada.hyperlink.task.service.HyperlinkShortCodeGenerator;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 派发按 runtime → round → usage → account → recipient 固定锁序占槽。 */
class HyperlinkDispatchConcurrencyTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set(7L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void reservesUsageBeforeRecipientAndReleasesItWhenNoRecipientExists() {
        Fixture fixture = fixture(1);
        when(fixture.usageMapper.reserveSlot(eq(31L), eq(4), eq(2), anyLong())).thenReturn(1);
        when(fixture.recipientMapper.lockPending(
                eq(7L), eq(11L), eq(21L), anyLong())).thenReturn(null);

        assertThat(fixture.service.dispatchOne(11L)).isFalse();

        InOrder order = inOrder(fixture.runtimeMapper, fixture.roundMapper, fixture.usageMapper,
                fixture.accountService, fixture.recipientMapper);
        order.verify(fixture.runtimeMapper).selectByTaskIdForShare(7L, 11L);
        order.verify(fixture.roundMapper).selectActiveForUpdate(7L, 11L);
        order.verify(fixture.usageMapper).reserveSlot(eq(31L), eq(4), eq(2), anyLong());
        order.verify(fixture.accountService).lockForHyperlinkDispatch(51L);
        order.verify(fixture.recipientMapper).lockSendingIdsByAccount(7L, 51L, 20);
        order.verify(fixture.recipientMapper).lockPending(
                eq(7L), eq(11L), eq(21L), anyLong());
        order.verify(fixture.usageMapper).completeSlot(eq(31L), eq(false), anyLong());
        verify(fixture.recipientMapper, never()).assignCommand(
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(fixture.messageSendPort);
    }

    @Test
    void stoppedRuntimeFenceRejectsDispatchBeforeLookingUpAnyUsage() {
        Fixture fixture = fixture(4);

        assertThat(fixture.service.dispatchOne(11L)).isFalse();

        verify(fixture.runtimeMapper).selectByTaskIdForShare(7L, 11L);
        verify(fixture.usageMapper, never()).selectAvailable(
                anyLong(), anyLong(), anyLong(), anyInt(), anyInt());
        verifyNoInteractions(fixture.messageSendPort);
    }

    private Fixture fixture(int runStatus) {
        HyperlinkTaskMapper taskMapper = mock(HyperlinkTaskMapper.class);
        HyperlinkTaskContentMapper contentMapper = mock(HyperlinkTaskContentMapper.class);
        HyperlinkTaskRuntimeMapper runtimeMapper = mock(HyperlinkTaskRuntimeMapper.class);
        HyperlinkTaskRoundMapper roundMapper = mock(HyperlinkTaskRoundMapper.class);
        HyperlinkTaskAccountUsageMapper usageMapper = mock(HyperlinkTaskAccountUsageMapper.class);
        HyperlinkTaskRecipientMapper recipientMapper = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkMessageCommandFactory commandFactory = mock(HyperlinkMessageCommandFactory.class);
        HyperlinkPrivateCapabilityPort capability = mock(HyperlinkPrivateCapabilityPort.class);
        MessageSendPort messageSendPort = mock(MessageSendPort.class);
        DataPackageRecipientClaimService data = mock(DataPackageRecipientClaimService.class);
        AccountHyperlinkCandidateService accountService =
                mock(AccountHyperlinkCandidateService.class);

        HyperlinkTaskRuntime runtime = new HyperlinkTaskRuntime();
        runtime.setRunStatus(runStatus);
        when(runtimeMapper.selectByTaskIdForShare(7L, 11L)).thenReturn(runtime);

        HyperlinkTask task = new HyperlinkTask();
        task.setTenantId(7L);
        task.setAccountSendConcurrency(2);
        task.setMsgIntervalMinMs(500);
        task.setMsgIntervalMaxMs(700);
        when(taskMapper.selectById(11L)).thenReturn(task);

        HyperlinkTaskRound round = new HyperlinkTaskRound();
        round.setId(21L);
        round.setRoundStatus(HyperlinkTaskRoundStatus.READY.code());
        when(roundMapper.selectActiveForUpdate(7L, 11L)).thenReturn(round);

        HyperlinkTaskAccountUsage usage = new HyperlinkTaskAccountUsage();
        usage.setId(31L);
        usage.setVersion(4);
        usage.setAccountId(51L);
        usage.setProtocolBackend(1);
        usage.setProtocolIdSnapshot("web");
        when(usageMapper.selectAvailable(eq(11L), eq(21L), anyLong(), eq(2), eq(20)))
                .thenReturn(List.of(usage));
        when(capability.supports(ProtocolBackend.WEB, "web")).thenReturn(true);
        when(accountService.lockForHyperlinkDispatch(51L)).thenReturn(true);
        when(recipientMapper.lockSendingIdsByAccount(7L, 51L, 20))
                .thenReturn(List.of());
        HyperlinkAccountDispatchGuard dispatchGuard = mock(HyperlinkAccountDispatchGuard.class);

        HyperlinkDispatchService service = new HyperlinkDispatchService(taskMapper,
                contentMapper, runtimeMapper, roundMapper, usageMapper, recipientMapper,
                commandFactory, mock(HyperlinkShortCodeGenerator.class), capability,
                messageSendPort, data, accountService, dispatchGuard);
        return new Fixture(service, runtimeMapper, roundMapper, usageMapper, accountService,
                recipientMapper, messageSendPort);
    }

    private record Fixture(HyperlinkDispatchService service,
            HyperlinkTaskRuntimeMapper runtimeMapper,
            HyperlinkTaskRoundMapper roundMapper,
            HyperlinkTaskAccountUsageMapper usageMapper,
            AccountHyperlinkCandidateService accountService,
            HyperlinkTaskRecipientMapper recipientMapper,
            MessageSendPort messageSendPort) {
    }
}
