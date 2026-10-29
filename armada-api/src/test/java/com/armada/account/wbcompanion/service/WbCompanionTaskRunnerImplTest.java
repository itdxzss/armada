package com.armada.account.wbcompanion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.wbcompanion.mapper.WbCompanionTaskMapper;
import com.armada.account.wbcompanion.model.entity.WbCompanionTask;
import com.armada.account.wbcompanion.model.enums.WbCompanionStep;
import com.armada.account.wbcompanion.model.enums.WbCompanionTaskStatus;
import com.armada.account.wbcompanion.service.impl.WbCompanionTaskRunnerImpl;
import com.armada.platform.protocol.model.result.CompanionPairCodeResult;
import com.armada.platform.protocol.port.CompanionPairCodePort;
import com.armada.platform.wbpro.WbproIntegrationClient;
import com.armada.platform.wbpro.WbproIntegrationException;
import com.armada.platform.wbpro.WbproProperties;
import com.armada.platform.wbpro.model.WbproModels;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 登录到 WB 分身的状态机：每次推进只走一小步，失败即停，配对码只用一次。 */
class WbCompanionTaskRunnerImplTest {

    private static final String PHONE = "628123460001";
    private final WbCompanionTaskMapper taskMapper = mock(WbCompanionTaskMapper.class);
    private final AccountMapper accountMapper = mock(AccountMapper.class);
    private final WbproIntegrationClient client = mock(WbproIntegrationClient.class);
    private final CompanionPairCodePort pairCode = mock(CompanionPairCodePort.class);
    private final WbCompanionBindingService binding = mock(WbCompanionBindingService.class);
    private WbCompanionTaskRunnerImpl runner;
    private WbCompanionTask task;

    @BeforeEach
    void setUp() {
        WbproProperties properties = new WbproProperties();
        properties.setTargets("test1|WB 测试|https://wb.example|armada-test1|test-integration-secret-0123456789abcdef");
        runner = new WbCompanionTaskRunnerImpl(taskMapper, accountMapper, client, properties, pairCode, binding);
        task = new WbCompanionTask();
        task.setId(31L);
        task.setAccountId(9001L);
        task.setWsPhone(PHONE);
        task.setWbEnvId("test1");
        task.setWbMemberId(120L);
        task.setBaseCount(0);
        task.setTargetCount(2);
        task.setSucceededCount(0);
        task.setStatus(WbCompanionTaskStatus.RUNNING);
        task.setStep(WbCompanionStep.WAITING);
        task.setNextRunAt(1L);
        when(taskMapper.selectById(31L)).thenReturn(task);
        when(taskMapper.claim(eq(31L), anyLong(), anyLong(), anyLong())).thenReturn(1);
        when(accountMapper.selectOnlineAccountIdsByIds(anyList(), anyInt())).thenReturn(List.of(9001L));
        when(binding.sync(any(), eq(9001L), eq(PHONE))).thenReturn(Optional.empty());
    }

    @Test
    void waitingStartsWbPairingWithFreshCode() {
        when(client.startPairing(any(), any())).thenReturn(new WbproModels.PairingStarted("p1", "MAIN", null, 0L));
        runner.advance(31L);
        ArgumentCaptor<WbproIntegrationClient.WbproPairingCommand> command =
                ArgumentCaptor.forClass(WbproIntegrationClient.WbproPairingCommand.class);
        verify(client).startPairing(any(), command.capture());
        assertThat(command.getValue().pairingCode()).matches("[1-9A-HJ-NP-TV-Z]{8}");
        assertThat(command.getValue().importRef()).isEqualTo("armada-task-31");
        assertThat(task.getStep()).isEqualTo(WbCompanionStep.WB_STARTED);
        assertThat(task.getPairCode()).isEqualTo(command.getValue().pairingCode());
        assertThat(task.getWbRole()).isEqualTo("MAIN");
        verify(taskMapper).updateProgress(task);
    }

    @Test
    void lostClaimDoesNothing() {
        when(taskMapper.claim(eq(31L), anyLong(), anyLong(), anyLong())).thenReturn(0);
        runner.advance(31L);
        verify(client, never()).startPairing(any(), any());
        verify(taskMapper, never()).updateProgress(any());
    }

    @Test
    void offlineAccountFailsBeforeTouchingWb() {
        when(accountMapper.selectOnlineAccountIdsByIds(anyList(), anyInt())).thenReturn(List.of());
        runner.advance(31L);
        assertThat(task.getStatus()).isEqualTo(WbCompanionTaskStatus.FAILED);
        assertThat(task.getErrorCode()).isEqualTo("ACCOUNT_OFFLINE");
        verify(client, never()).startPairing(any(), any());
    }

    @Test
    void wbRejectionStopsTheBatch() {
        when(client.startPairing(any(), any())).thenThrow(new WbproIntegrationException("SHADOW_LIMIT_REACHED", 409));
        runner.advance(31L);
        assertThat(task.getStatus()).isEqualTo(WbCompanionTaskStatus.FAILED);
        assertThat(task.getErrorCode()).isEqualTo("SHADOW_LIMIT_REACHED");
    }

    @Test
    void acceptedCodeMovesToAwaitingWb() {
        startedStep(System.currentTimeMillis() + 20_000);
        when(pairCode.submit(PHONE, "ABCDEFGH")).thenReturn(new CompanionPairCodeResult(true, null));
        runner.advance(31L);
        assertThat(task.getStep()).isEqualTo(WbCompanionStep.CODE_SUBMITTED);
        assertThat(task.getPairCode()).isEqualTo("ABCDEFGH");
    }

    @Test
    void noPendingRetriesInsideWindowThenFails() {
        startedStep(System.currentTimeMillis() + 20_000);
        when(pairCode.submit(PHONE, "ABCDEFGH")).thenReturn(new CompanionPairCodeResult(false, "NO_PENDING"));
        runner.advance(31L);
        assertThat(task.getStatus()).isEqualTo(WbCompanionTaskStatus.RUNNING);
        assertThat(task.getStep()).isEqualTo(WbCompanionStep.WB_STARTED);

        task.setStepDeadline(System.currentTimeMillis() - 1);
        runner.advance(31L);
        assertThat(task.getStatus()).isEqualTo(WbCompanionTaskStatus.FAILED);
        assertThat(task.getErrorCode()).isEqualTo("ANDROID_NO_PENDING");
        assertThat(task.getPairCode()).isNull();
    }

    @Test
    void ambiguousIsNeverRetried() {
        startedStep(System.currentTimeMillis() + 20_000);
        when(pairCode.submit(PHONE, "ABCDEFGH")).thenReturn(new CompanionPairCodeResult(false, "AMBIGUOUS"));
        runner.advance(31L);
        assertThat(task.getStatus()).isEqualTo(WbCompanionTaskStatus.FAILED);
        assertThat(task.getErrorCode()).isEqualTo("ANDROID_AMBIGUOUS");
    }

    @Test
    void connectedOneThenNextUntilTarget() {
        submittedStep();
        when(client.pairing(any(), eq("p1"))).thenReturn(new WbproModels.PairingState("CONNECTED", null, 10482L));
        runner.advance(31L);
        assertThat(task.getSucceededCount()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(WbCompanionTaskStatus.RUNNING);
        assertThat(task.getStep()).isEqualTo(WbCompanionStep.WAITING);
        assertThat(task.getPairCode()).isNull();

        submittedStep();
        runner.advance(31L);
        assertThat(task.getSucceededCount()).isEqualTo(2);
        assertThat(task.getStatus()).isEqualTo(WbCompanionTaskStatus.SUCCEEDED);
    }

    @Test
    void expiredWbRecordFallsBackToDeviceCount() {
        submittedStep();
        when(client.pairing(any(), eq("p1"))).thenThrow(new WbproIntegrationException("PAIRING_NOT_FOUND", 404));
        when(binding.sync(any(), eq(9001L), eq(PHONE))).thenReturn(Optional.of(
                new WbproModels.Device(10482L, 120L, "wb_jack01", "CONNECTED", "IDLE", List.of())));
        runner.advance(31L);
        assertThat(task.getSucceededCount()).isEqualTo(1);

        submittedStep();
        runner.advance(31L);
        assertThat(task.getStatus()).isEqualTo(WbCompanionTaskStatus.FAILED);
        assertThat(task.getErrorCode()).isEqualTo("WB_PAIRING_EXPIRED");
    }

    @Test
    void wbFailureCodeIsKept() {
        submittedStep();
        when(client.pairing(any(), eq("p1"))).thenReturn(new WbproModels.PairingState("FAILED", "PHONE_MISMATCH", null));
        runner.advance(31L);
        assertThat(task.getErrorCode()).isEqualTo("PHONE_MISMATCH");
    }

    private void startedStep(long deadline) {
        task.setStep(WbCompanionStep.WB_STARTED);
        task.setWbPairingId("p1");
        task.setPairCode("ABCDEFGH");
        task.setStepDeadline(deadline);
    }

    private void submittedStep() {
        task.setStep(WbCompanionStep.CODE_SUBMITTED);
        task.setWbPairingId("p1");
        task.setPairCode("ABCDEFGH");
        task.setStepDeadline(System.currentTimeMillis() + 180_000);
    }
}
