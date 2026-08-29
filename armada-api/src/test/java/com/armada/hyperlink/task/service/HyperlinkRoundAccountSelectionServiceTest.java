package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.model.vo.AccountHyperlinkCandidateVO;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundAccountMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskAccountUsage;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRound;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRoundAccount;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskAccountUsageStatus;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 周期轮账号补选必须越过任务内已耗尽账号，且不得虚构可用账号。 */
class HyperlinkRoundAccountSelectionServiceTest {

    private static final long NOW = 2_000_000L;

    private final HyperlinkTaskAccountUsageMapper usages =
            mock(HyperlinkTaskAccountUsageMapper.class);
    private final HyperlinkTaskRoundAccountMapper roundAccounts =
            mock(HyperlinkTaskRoundAccountMapper.class);
    private final HyperlinkAccountCandidateSelector candidates =
            mock(HyperlinkAccountCandidateSelector.class);
    private final HyperlinkRoundAccountSelectionService service =
            new HyperlinkRoundAccountSelectionService(usages, roundAccounts, candidates);

    @Test
    void skipsExhaustedLeadingCandidatesAndSelectsTheNextEligibleAccount() {
        HyperlinkTask task = task();
        HyperlinkTaskRound round = round();
        AccountHyperlinkCandidateVO first = candidate(1L);
        AccountHyperlinkCandidateVO second = candidate(2L);
        AccountHyperlinkCandidateVO third = candidate(3L);
        when(roundAccounts.selectByRoundId(21L)).thenReturn(List.of());
        when(roundAccounts.countAvailableByRoundId(21L)).thenReturn(0, 1);
        when(candidates.select(task, null, null, 50, NOW))
                .thenReturn(List.of(first, second, third));
        when(usages.selectByTaskAndAccount(11L, 1L)).thenReturn(usage(101L, 1L,
                HyperlinkTaskAccountUsageStatus.LIMIT_REACHED));
        when(usages.selectByTaskAndAccount(11L, 2L)).thenReturn(usage(102L, 2L,
                HyperlinkTaskAccountUsageStatus.LIMIT_REACHED));
        when(usages.selectByTaskAndAccount(11L, 3L)).thenReturn(usage(103L, 3L,
                HyperlinkTaskAccountUsageStatus.AVAILABLE));
        when(usages.markSelectedRound(103L, 2L, NOW)).thenReturn(1);
        when(roundAccounts.insertIgnore(any())).thenReturn(1);

        assertThat(service.select(task, round, NOW)).isEqualTo(1);

        ArgumentCaptor<HyperlinkTaskRoundAccount> inserted =
                ArgumentCaptor.forClass(HyperlinkTaskRoundAccount.class);
        verify(roundAccounts).insertIgnore(inserted.capture());
        assertThat(inserted.getValue().getAccountId()).isEqualTo(third.accountId());
    }

    @Test
    void concurrentOneFindsEligibleAccountInOneFiftyRowScanPage() {
        HyperlinkTask task = task(1, 1);
        HyperlinkTaskRound round = round();
        when(roundAccounts.selectByRoundId(21L)).thenReturn(List.of());
        when(roundAccounts.countAvailableByRoundId(21L)).thenReturn(0, 1);
        when(candidates.select(task, null, null, 50, NOW)).thenReturn(List.of(
                candidate(1L), candidate(2L), candidate(3L), candidate(4L)));
        when(usages.selectByTaskAndAccount(11L, 1L)).thenReturn(usage(101L, 1L,
                HyperlinkTaskAccountUsageStatus.LIMIT_REACHED));
        when(usages.selectByTaskAndAccount(11L, 2L)).thenReturn(usage(102L, 2L,
                HyperlinkTaskAccountUsageStatus.INVALID));
        when(usages.selectByTaskAndAccount(11L, 3L)).thenReturn(usage(103L, 3L,
                HyperlinkTaskAccountUsageStatus.AVAILABLE));
        when(usages.markSelectedRound(103L, 2L, NOW)).thenReturn(1);
        when(roundAccounts.insertIgnore(any())).thenReturn(1);

        assertThat(service.select(task, round, NOW)).isEqualTo(1);

        verify(candidates).select(task, null, null, 50, NOW);
        verifyNoMoreInteractions(candidates);
        verify(roundAccounts, times(1)).insertIgnore(any());
        verify(usages, never()).selectByTaskAndAccount(11L, 4L);
    }

    @Test
    void scansAdditionalPagesWithTheSameFiftyRowLimit() {
        HyperlinkTask task = task(1, 1);
        HyperlinkTaskRound round = round();
        List<AccountHyperlinkCandidateVO> firstPage = LongStream.rangeClosed(1, 50)
                .mapToObj(this::candidate)
                .toList();
        when(roundAccounts.selectByRoundId(21L)).thenReturn(List.of());
        when(roundAccounts.countAvailableByRoundId(21L)).thenReturn(0, 1);
        when(candidates.select(task, null, null, 50, NOW)).thenReturn(firstPage);
        when(candidates.select(task, 0, 50L, 50, NOW)).thenReturn(List.of(candidate(51L)));
        when(usages.selectByTaskAndAccount(eq(11L), anyLong())).thenAnswer(invocation -> {
            long accountId = invocation.getArgument(1);
            HyperlinkTaskAccountUsageStatus status = accountId == 51L
                    ? HyperlinkTaskAccountUsageStatus.AVAILABLE
                    : HyperlinkTaskAccountUsageStatus.LIMIT_REACHED;
            return usage(100L + accountId, accountId, status);
        });
        when(usages.markSelectedRound(151L, 2L, NOW)).thenReturn(1);
        when(roundAccounts.insertIgnore(any())).thenReturn(1);

        assertThat(service.select(task, round, NOW)).isEqualTo(1);

        verify(candidates).select(task, null, null, 50, NOW);
        verify(candidates).select(task, 0, 50L, 50, NOW);
        verifyNoMoreInteractions(candidates);
        verify(roundAccounts, times(1)).insertIgnore(any());
    }

    @Test
    void returnsZeroWhenEveryCandidatePageIsExhausted() {
        HyperlinkTask task = task();
        HyperlinkTaskRound round = round();
        when(roundAccounts.selectByRoundId(21L)).thenReturn(List.of());
        when(roundAccounts.countAvailableByRoundId(21L)).thenReturn(0);
        when(candidates.select(task, null, null, 50, NOW))
                .thenReturn(List.of(candidate(1L), candidate(2L)));
        when(usages.selectByTaskAndAccount(11L, 1L)).thenReturn(usage(101L, 1L,
                HyperlinkTaskAccountUsageStatus.LIMIT_REACHED));
        when(usages.selectByTaskAndAccount(11L, 2L)).thenReturn(usage(102L, 2L,
                HyperlinkTaskAccountUsageStatus.INVALID));

        assertThat(service.select(task, round, NOW)).isZero();

        verify(roundAccounts, never()).insertIgnore(any());
    }

    private HyperlinkTask task() {
        return task(2, 2);
    }

    private HyperlinkTask task(int concurrentNum, int maxUseAccount) {
        HyperlinkTask task = new HyperlinkTask();
        task.setId(11L);
        task.setConcurrentNum(concurrentNum);
        task.setMaxUseAccount(maxUseAccount);
        task.setAccountMaxSendNum(10);
        return task;
    }

    private HyperlinkTaskRound round() {
        HyperlinkTaskRound round = new HyperlinkTaskRound();
        round.setId(21L);
        round.setRoundNo(2L);
        return round;
    }

    private AccountHyperlinkCandidateVO candidate(long accountId) {
        return new AccountHyperlinkCandidateVO(accountId, 0, "55" + accountId, "BR", 2,
                1_000L, "WEB", "acc-" + accountId, "WEB");
    }

    private HyperlinkTaskAccountUsage usage(long id, long accountId,
            HyperlinkTaskAccountUsageStatus status) {
        HyperlinkTaskAccountUsage usage = new HyperlinkTaskAccountUsage();
        usage.setId(id);
        usage.setAccountId(accountId);
        usage.setUsageStatus(status.code());
        return usage;
    }
}
