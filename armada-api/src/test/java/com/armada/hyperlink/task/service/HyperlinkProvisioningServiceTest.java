package com.armada.hyperlink.task.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class HyperlinkProvisioningServiceTest {

    @Test
    void completesSeveralShortClaimTransactionsInOneAdvance() {
        HyperlinkRecipientClaimService claims = mock(HyperlinkRecipientClaimService.class);
        HyperlinkBillingSagaService billing = mock(HyperlinkBillingSagaService.class);
        HyperlinkFirstRoundService firstRound = mock(HyperlinkFirstRoundService.class);
        HyperlinkTaskRuntimeMapper runtimes = mock(HyperlinkTaskRuntimeMapper.class);
        when(claims.claimNext(11L)).thenReturn(
                batch(false, 500, 500),
                batch(false, 500, 1_000),
                batch(false, 500, 1_500),
                batch(true, 300, 1_800));
        HyperlinkProvisioningService service = new HyperlinkProvisioningService(
                claims, billing, firstRound, runtimes, Clock.systemUTC());

        service.advance(11L);

        verify(claims, times(4)).claimNext(11L);
        verify(billing).ensureProvisionReservation(11L);
        verify(firstRound).createFirstRound(11L);
    }

    @Test
    void yieldsAfterFourIncompleteClaimTransactions() {
        HyperlinkRecipientClaimService claims = mock(HyperlinkRecipientClaimService.class);
        HyperlinkBillingSagaService billing = mock(HyperlinkBillingSagaService.class);
        HyperlinkFirstRoundService firstRound = mock(HyperlinkFirstRoundService.class);
        when(claims.claimNext(11L)).thenReturn(batch(false, 500, 500));
        HyperlinkProvisioningService service = new HyperlinkProvisioningService(
                claims, billing, firstRound, mock(HyperlinkTaskRuntimeMapper.class),
                Clock.systemUTC());

        service.advance(11L);

        verify(claims, times(4)).claimNext(11L);
        verify(billing, never()).ensureProvisionReservation(11L);
        verify(firstRound, never()).createFirstRound(11L);
    }

    @Test
    void yieldsWhenOneSecondPerTaskBudgetIsExhausted() {
        HyperlinkRecipientClaimService claims = mock(HyperlinkRecipientClaimService.class);
        HyperlinkBillingSagaService billing = mock(HyperlinkBillingSagaService.class);
        HyperlinkFirstRoundService firstRound = mock(HyperlinkFirstRoundService.class);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1_000L, 1_000L, 2_001L);
        when(claims.claimNext(11L)).thenReturn(batch(false, 500, 500));
        HyperlinkProvisioningService service = new HyperlinkProvisioningService(
                claims, billing, firstRound, mock(HyperlinkTaskRuntimeMapper.class), clock);

        service.advance(11L);

        verify(claims, times(2)).claimNext(11L);
        verify(billing, never()).ensureProvisionReservation(11L);
        verify(firstRound, never()).createFirstRound(11L);
    }

    private HyperlinkRecipientClaimService.ClaimBatchResult batch(boolean completed,
            int claimedThisBatch, int claimedTotal) {
        return new HyperlinkRecipientClaimService.ClaimBatchResult(
                completed, claimedThisBatch, claimedTotal);
    }
}
