package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountHyperlinkCandidateService;
import com.armada.account.service.AccountOperationRestrictionService;
import com.armada.hyperlink.data.service.DataPackageRecipientClaimService;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskContentMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskContent;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRound;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskAccountUsageStatus;
import com.armada.hyperlink.task.port.HyperlinkPrivateCapabilityPort;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** recipient 派发只在短链开启时生成 CSPRNG 短码，并由唯一键处理碰撞。 */
class HyperlinkDispatchServiceTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set(7L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void duplicateShortCodeRetriesTheSameRecipientWithoutChangingCommand() {
        Fixture fixture = new Fixture(true);
        when(fixture.shortCodeGenerator.next()).thenReturn("collisionCode1", "freshCode00002");
        when(fixture.recipientMapper.assignCommand(any()))
                .thenThrow(new DuplicateKeyException("uq_hyperlink_recipient_short_code"))
                .thenReturn(1);

        assertThat(fixture.service.dispatchOne(11L)).isTrue();

        verify(fixture.shortCodeGenerator, org.mockito.Mockito.times(2)).next();
        assertThat(fixture.recipient.getShortCode()).isEqualTo("freshCode00002");
        assertThat(fixture.recipient.getCommandId()).isEqualTo("hl:7:11:13");
        assertThat(fixture.recipient.getSenderDeviceOsSnapshot()).isEqualTo(2);
        verify(fixture.messageSendPort).enqueue(any());
    }

    @Test
    void disabledShortLinkStoresNullAndKeepsOriginalMessagePath() {
        Fixture fixture = new Fixture(false);
        when(fixture.recipientMapper.assignCommand(any())).thenReturn(1);

        assertThat(fixture.service.dispatchOne(11L)).isTrue();

        verify(fixture.shortCodeGenerator, never()).next();
        assertThat(fixture.recipient.getShortCode()).isNull();
    }

    @Test
    void fullGlobalAccountCapacityOnlyDelaysTheCandidate() {
        Fixture fixture = new Fixture(false);
        when(fixture.usageMapper.selectAvailable(anyLong(), anyLong(), anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of(usage(), secondUsage()));
        when(fixture.dispatchGuard.tryAcquire(51L, "hl:7:11:13"))
                .thenReturn(false);

        assertThat(fixture.service.dispatchOne(11L)).isTrue();

        verify(fixture.usageMapper).completeSlot(41L, false, 1_000L);
        verify(fixture.usageMapper).scheduleNextSend(41L, 31_000L, 1_000L);
        verify(fixture.recipientMapper, never()).assignCommand(any());
        verify(fixture.accountService, never()).lockForHyperlinkDispatch(52L);
        verify(fixture.messageSendPort, never()).enqueue(any());
    }

    @Test
    void databaseCapacityRejectsTheTwentyFirstCrossTaskSendWhenRedisHolderIsMissing() {
        Fixture fixture = new Fixture(false);
        when(fixture.usageMapper.selectAvailable(anyLong(), anyLong(), anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of(usage(), secondUsage()));
        when(fixture.recipientMapper.lockSendingIdsByAccount(7L, 51L, 20))
                .thenReturn(ids(20));

        assertThat(fixture.service.dispatchOne(11L)).isTrue();

        verify(fixture.accountService).lockForHyperlinkDispatch(51L);
        verify(fixture.usageMapper).completeSlot(41L, false, 1_000L);
        verify(fixture.usageMapper).scheduleNextSend(41L, 31_000L, 1_000L);
        verify(fixture.recipientMapper, never()).lockPending(anyLong(), anyLong(), anyLong(), anyLong());
        verify(fixture.dispatchGuard, never()).tryAcquire(anyLong(), any());
        verify(fixture.accountService, never()).lockForHyperlinkDispatch(52L);
        verify(fixture.messageSendPort, never()).enqueue(any());
    }

    @Test
    void terminalRecipientFreesDatabaseCapacityForTheNextDispatch() {
        Fixture fixture = new Fixture(false);
        when(fixture.recipientMapper.lockSendingIdsByAccount(7L, 51L, 20))
                .thenReturn(ids(20), ids(19));
        when(fixture.recipientMapper.assignCommand(any())).thenReturn(1);

        assertThat(fixture.service.dispatchOne(11L)).isTrue();
        assertThat(fixture.service.dispatchOne(11L)).isTrue();

        verify(fixture.dispatchGuard).tryAcquire(51L, "hl:7:11:13");
        verify(fixture.recipientMapper).assignCommand(any());
        verify(fixture.messageSendPort).enqueue(any());
    }

    @Test
    void messageUnavailableAccountRetiresAndContinuesWithNextAccount() {
        Fixture fixture = new Fixture(false);
        when(fixture.usageMapper.selectAvailable(anyLong(), anyLong(), anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of(usage(), secondUsage()));
        when(fixture.accountService.lockForHyperlinkDispatch(51L)).thenReturn(false);
        when(fixture.accountService.lockForHyperlinkDispatch(52L)).thenReturn(true);
        when(fixture.dispatchGuard.tryAcquire(52L, "hl:7:11:13")).thenReturn(true);
        when(fixture.recipientMapper.assignCommand(any())).thenReturn(1);

        assertThat(fixture.service.dispatchOne(11L)).isTrue();

        verify(fixture.usageMapper).completeSlot(41L, false, 1_000L);
        verify(fixture.usageMapper).markOperationRestricted(
                41L, HyperlinkTaskAccountUsageStatus.OPERATION_RESTRICTED.code(),
                "MESSAGE_SENDING_UNAVAILABLE", "账号当前不可用于营销消息发送", 1_000L);
        verify(fixture.usageMapper, never()).scheduleNextSend(41L, 31_000L, 1_000L);
        verify(fixture.accountService).lockForHyperlinkDispatch(52L);
        verify(fixture.recipientMapper).lockSendingIdsByAccount(7L, 52L, 20);
        verify(fixture.messageSendPort).enqueue(any());
    }

    @Test
    void transactionRollbackReleasesTheAcquiredHolder() {
        Fixture fixture = new Fixture(false);
        when(fixture.recipientMapper.assignCommand(any()))
                .thenThrow(new IllegalStateException("db write failed"));
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> fixture.service.dispatchOne(11L))
                .isInstanceOf(IllegalStateException.class);
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verify(fixture.dispatchGuard).release(51L, "hl:7:11:13");
    }

    @Test
    void localAdapterRejectionReleasesAfterDatabaseCommit() {
        Fixture fixture = new Fixture(false);
        when(fixture.recipientMapper.assignCommand(any())).thenReturn(1);
        when(fixture.messageSendPort.enqueue(any())).thenReturn(new MessageSendEnqueueResult(
                List.of(MessageSendEnqueueItem.rejected(
                        "hl:7:11:13", "LOCAL_REJECTED", "invalid payload"))));
        TransactionSynchronizationManager.initSynchronization();

        assertThat(fixture.service.dispatchOne(11L)).isTrue();
        verify(fixture.dispatchGuard, never()).release(51L, "hl:7:11:13");
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }

        verify(fixture.dispatchGuard).release(51L, "hl:7:11:13");
    }

    @Test
    void localAccountRestrictionRequeuesMaterialWithoutRecordingTerminalFailure() {
        Fixture fixture = new Fixture(false);
        when(fixture.recipientMapper.assignCommand(any())).thenReturn(1);
        when(fixture.recipientMapper.requeueAfterAccountRestriction(
                13L, "hl:7:11:13", 1_000L)).thenReturn(1);
        when(fixture.messageSendPort.enqueue(any())).thenReturn(new MessageSendEnqueueResult(
                List.of(MessageSendEnqueueItem.rejected(
                        "hl:7:11:13", "ACCOUNT_REACHOUT_RESTRICTED", "restricted"))));

        assertThat(fixture.service.dispatchOne(11L)).isTrue();

        verify(fixture.restrictionService).restrictMessageSending(
                51L, "ACCOUNT_REACHOUT_RESTRICTED", 1_000L, 1_000L);
        verify(fixture.recipientMapper).requeueAfterAccountRestriction(
                13L, "hl:7:11:13", 1_000L);
        verify(fixture.usageMapper).completeSlot(41L, false, 1_000L);
        verify(fixture.usageMapper).markOperationRestricted(
                41L, 6, "ACCOUNT_REACHOUT_RESTRICTED", "restricted", 1_000L);
        verify(fixture.recipientMapper, never()).applyResult(any());
        verify(fixture.dataPackageService, never()).advanceDeliveryFact(
                anyLong(), anyLong(), anyInt(), any(), any(), anyLong());
    }

    @Test
    void acceptedOutboxKeepsHolderAfterDatabaseCommit() {
        Fixture fixture = new Fixture(false);
        when(fixture.recipientMapper.assignCommand(any())).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        assertThat(fixture.service.dispatchOne(11L)).isTrue();
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }

        verify(fixture.dispatchGuard, never()).release(51L, "hl:7:11:13");
    }

    private static final class Fixture {
        private final HyperlinkTaskRecipientMapper recipientMapper =
                mock(HyperlinkTaskRecipientMapper.class);
        private final HyperlinkTaskAccountUsageMapper usageMapper =
                mock(HyperlinkTaskAccountUsageMapper.class);
        private final HyperlinkShortCodeGenerator shortCodeGenerator =
                mock(HyperlinkShortCodeGenerator.class);
        private final MessageSendPort messageSendPort = mock(MessageSendPort.class);
        private final HyperlinkAccountDispatchGuard dispatchGuard =
                mock(HyperlinkAccountDispatchGuard.class);
        private final AccountHyperlinkCandidateService accountService =
                mock(AccountHyperlinkCandidateService.class);
        private final AccountOperationRestrictionService restrictionService =
                mock(AccountOperationRestrictionService.class);
        private final DataPackageRecipientClaimService dataPackageService =
                mock(DataPackageRecipientClaimService.class);
        private final HyperlinkTaskRecipient recipient = recipient();
        private final HyperlinkDispatchService service;

        private Fixture(boolean shortLinkEnabled) {
            HyperlinkTaskMapper taskMapper = mock(HyperlinkTaskMapper.class);
            HyperlinkTaskContentMapper contentMapper = mock(HyperlinkTaskContentMapper.class);
            HyperlinkTaskRuntimeMapper runtimeMapper = mock(HyperlinkTaskRuntimeMapper.class);
            HyperlinkTaskRoundMapper roundMapper = mock(HyperlinkTaskRoundMapper.class);
            HyperlinkMessageCommandFactory commandFactory = mock(HyperlinkMessageCommandFactory.class);
            HyperlinkPrivateCapabilityPort capabilityPort =
                    mock(HyperlinkPrivateCapabilityPort.class);
            HyperlinkTask task = task(shortLinkEnabled);
            when(taskMapper.selectById(11L)).thenReturn(task);
            var runtime = new com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime();
            runtime.setRunStatus(1);
            when(runtimeMapper.selectByTaskIdForShare(7L, 11L)).thenReturn(runtime);
            HyperlinkTaskRound round = round();
            when(roundMapper.selectActiveForUpdate(7L, 11L)).thenReturn(round);
            HyperlinkTaskAccountUsage usage = usage();
            when(usageMapper.selectAvailable(anyLong(), anyLong(), anyLong(), anyInt(), anyInt()))
                    .thenReturn(List.of(usage));
            when(usageMapper.reserveSlot(anyLong(), anyInt(), anyInt(), anyLong()))
                    .thenReturn(1);
            when(usageMapper.scheduleNextSend(anyLong(), anyLong(), anyLong())).thenReturn(1);
            when(usageMapper.markOperationRestricted(
                    anyLong(), anyInt(), any(), any(), anyLong())).thenReturn(1);
            when(capabilityPort.supports(ProtocolBackend.WEB, "web")).thenReturn(true);
            when(accountService.lockForHyperlinkDispatch(51L)).thenReturn(true);
            when(recipientMapper.lockSendingIdsByAccount(7L, 51L, 20))
                    .thenReturn(List.of());
            when(recipientMapper.lockPending(7L, 11L, 31L, 1_000L)).thenReturn(recipient);
            MessageSendCommand command = mock(MessageSendCommand.class);
            when(commandFactory.commandId(7L, 11L, 13L, null))
                    .thenReturn("hl:7:11:13");
            when(dispatchGuard.tryAcquire(51L, "hl:7:11:13")).thenReturn(true);
            when(commandFactory.create(any(), any(), any(), any(), anyLong())).thenReturn(command);
            when(contentMapper.selectByTaskId(11L)).thenReturn(content());
            when(messageSendPort.enqueue(any())).thenReturn(new MessageSendEnqueueResult(
                    List.of(MessageSendEnqueueItem.accepted("hl:7:11:13"))));
            service = new HyperlinkDispatchService(taskMapper, contentMapper, runtimeMapper,
                    roundMapper, usageMapper, recipientMapper, commandFactory, shortCodeGenerator,
                    capabilityPort, messageSendPort, dataPackageService,
                    accountService, restrictionService, dispatchGuard,
                    Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));
        }
    }

    private static List<Long> ids(int count) {
        return java.util.stream.LongStream.rangeClosed(1, count).boxed().toList();
    }

    private static HyperlinkTask task(boolean shortLinkEnabled) {
        HyperlinkTask value = new HyperlinkTask();
        value.setId(11L);
        value.setTenantId(7L);
        value.setMsgIntervalMinMs(0);
        value.setMsgIntervalMaxMs(0);
        value.setAccountSendConcurrency(2);
        value.setShortLinkEnabled(shortLinkEnabled);
        return value;
    }

    private static HyperlinkTaskContent content() {
        HyperlinkTaskContent value = new HyperlinkTaskContent();
        value.setMessageType(4);
        return value;
    }

    private static HyperlinkTaskRound round() {
        HyperlinkTaskRound value = new HyperlinkTaskRound();
        value.setId(31L);
        value.setRoundNo(1L);
        value.setRoundStatus(3);
        return value;
    }

    private static HyperlinkTaskAccountUsage usage() {
        HyperlinkTaskAccountUsage value = new HyperlinkTaskAccountUsage();
        value.setId(41L);
        value.setVersion(1);
        value.setAccountId(51L);
        value.setAccountPhoneSnapshot("8613900000000");
        value.setSenderCountryIso2Snapshot("CN");
        value.setAccountTypeSnapshot(1);
        value.setSenderDeviceOsSnapshot(2);
        value.setProtocolIdSnapshot("web");
        value.setProtocolAccountIdSnapshot("acc-51");
        value.setProtocolBackend(1);
        return value;
    }

    private static HyperlinkTaskAccountUsage secondUsage() {
        HyperlinkTaskAccountUsage value = usage();
        value.setId(42L);
        value.setAccountId(52L);
        value.setAccountPhoneSnapshot("8613900000001");
        value.setProtocolAccountIdSnapshot("acc-52");
        return value;
    }

    private static HyperlinkTaskRecipient recipient() {
        HyperlinkTaskRecipient value = new HyperlinkTaskRecipient();
        value.setId(13L);
        value.setDataPackageId(71L);
        value.setDataPackageGeneration(2);
        value.setRecipientPhoneSnapshot("8613800000000");
        return value;
    }
}
