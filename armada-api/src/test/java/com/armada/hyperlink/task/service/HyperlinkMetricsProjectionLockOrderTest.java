package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountStatMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.shared.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 指标投影与显式校准必须遵守 runtime → round → recipient 锁序。 */
class HyperlinkMetricsProjectionLockOrderTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void projectionLocksRuntimeAndRoundBeforeExactRecipients() {
        Fixture fixture = fixture();
        HyperlinkTaskRecipient candidate = candidate();
        when(fixture.recipients.selectMetricsProjectionCandidates(500))
                .thenReturn(List.of(candidate));
        when(fixture.runtimes.selectByTaskIdForUpdate(7L, 11L))
                .thenReturn(new HyperlinkTaskRuntime());
        when(fixture.rounds.lockMetricsProjectionRounds(7L, 11L, List.of(21L)))
                .thenReturn(List.of(21L));
        when(fixture.recipients.lockMetricsProjectionBatch(List.of(31L)))
                .thenReturn(List.of());

        assertThat(fixture.service.projectNextBatch()).isZero();

        InOrder order = inOrder(fixture.recipients, fixture.runtimes, fixture.rounds);
        order.verify(fixture.recipients).selectMetricsProjectionCandidates(500);
        order.verify(fixture.runtimes).selectByTaskIdForUpdate(7L, 11L);
        order.verify(fixture.rounds).lockMetricsProjectionRounds(7L, 11L, List.of(21L));
        order.verify(fixture.recipients).lockMetricsProjectionBatch(List.of(31L));
    }

    @Test
    void reconciliationLocksRuntimeBeforeUpdatingRounds() {
        Fixture fixture = fixture();
        TenantContext.set(7L);
        when(fixture.runtimes.selectByTaskIdForUpdate(7L, 11L))
                .thenReturn(new HyperlinkTaskRuntime());

        fixture.service.reconcile(11L);

        InOrder order = inOrder(fixture.runtimes, fixture.rounds,
                fixture.accountStats, fixture.recipients);
        order.verify(fixture.runtimes).selectByTaskIdForUpdate(7L, 11L);
        order.verify(fixture.rounds).rebuildProjection(11L, 1_000L);
        order.verify(fixture.accountStats).replaceFromRecipient(11L, 1_000L);
        order.verify(fixture.runtimes).rebuildProjection(11L, 1_000L);
        order.verify(fixture.recipients).markProjected(11L, 1_000L);
    }

    private Fixture fixture() {
        HyperlinkTaskRecipientMapper recipients = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskRuntimeMapper runtimes = mock(HyperlinkTaskRuntimeMapper.class);
        HyperlinkTaskRoundMapper rounds = mock(HyperlinkTaskRoundMapper.class);
        HyperlinkTaskAccountStatMapper accountStats = mock(HyperlinkTaskAccountStatMapper.class);
        HyperlinkMetricsProjectionService service = new HyperlinkMetricsProjectionService(
                recipients, runtimes, rounds, accountStats,
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));
        return new Fixture(service, recipients, runtimes, rounds, accountStats);
    }

    private HyperlinkTaskRecipient candidate() {
        HyperlinkTaskRecipient value = new HyperlinkTaskRecipient();
        value.setId(31L);
        value.setTenantId(7L);
        value.setHyperlinkTaskId(11L);
        value.setHyperlinkTaskRoundId(21L);
        return value;
    }

    private record Fixture(HyperlinkMetricsProjectionService service,
            HyperlinkTaskRecipientMapper recipients,
            HyperlinkTaskRuntimeMapper runtimes,
            HyperlinkTaskRoundMapper rounds,
            HyperlinkTaskAccountStatMapper accountStats) {
    }
}
