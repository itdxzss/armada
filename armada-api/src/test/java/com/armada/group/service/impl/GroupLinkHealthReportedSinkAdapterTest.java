package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.service.GroupLinkHealthReportService;
import com.armada.platform.kafka.consumer.group.ProtocolGroupHealthReportedEvent;
import com.armada.task.service.PullTaskGroupBanTerminationService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 群健康事件路由到单群任务终止服务的单元测试。 */
@ExtendWith(MockitoExtension.class)
class GroupLinkHealthReportedSinkAdapterTest {

    @Mock
    private GroupLinkHealthReportService healthReportService;

    @Mock
    private PullTaskGroupBanTerminationService banTerminationService;

    private GroupLinkHealthReportedSinkAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new GroupLinkHealthReportedSinkAdapter(
                healthReportService, banTerminationService);
    }

    @Test
    void explicitSuspensionTerminatesResolvedGroupExecution() {
        when(healthReportService.applyHealthReported(any())).thenReturn(Optional.of(203L));

        adapter.handleHealthReported(event(" banned ", " chat_suspended "));

        verify(banTerminationService).terminateBannedGroup(12L, 203L);
    }

    @Test
    void explicitTerminationTerminatesResolvedGroupExecution() {
        when(healthReportService.applyHealthReported(any())).thenReturn(Optional.of(204L));

        adapter.handleHealthReported(event("BANNED", "CHAT_TERMINATED"));

        verify(banTerminationService).terminateBannedGroup(12L, 204L);
    }

    @Test
    void ambiguousFailuresDoNotTerminateExecution() {
        when(healthReportService.applyHealthReported(any())).thenReturn(Optional.of(203L));

        adapter.handleHealthReported(event("BANNED", "TEMPORARY_FAILURE"));
        adapter.handleHealthReported(event("BANNED", "403"));
        adapter.handleHealthReported(event("BANNED", "ACCOUNT_BANNED"));
        adapter.handleHealthReported(event("BANNED", null));
        adapter.handleHealthReported(event("ERROR", "CHAT_SUSPENDED"));

        verifyNoInteractions(banTerminationService);
    }

    @Test
    void healthyReportDoesNotReactivateOrTerminateExecution() {
        when(healthReportService.applyHealthReported(any())).thenReturn(Optional.of(203L));

        adapter.handleHealthReported(event("HEALTHY", null));

        verifyNoInteractions(banTerminationService);
    }

    @Test
    void unresolvedExplicitBanDoesNotTerminateExecution() {
        when(healthReportService.applyHealthReported(any())).thenReturn(Optional.empty());

        adapter.handleHealthReported(event("BANNED", "CHAT_SUSPENDED"));

        verifyNoInteractions(banTerminationService);
    }

    @Test
    void healthWriteFailureStopsBeforeTaskTermination() {
        when(healthReportService.applyHealthReported(any()))
                .thenThrow(new IllegalStateException("health write failed"));

        assertThatThrownBy(() -> adapter.handleHealthReported(
                event("BANNED", "CHAT_SUSPENDED")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("health write failed");

        verifyNoInteractions(banTerminationService);
    }

    @Test
    void taskTerminationFailurePropagatesForKafkaRedelivery() {
        when(healthReportService.applyHealthReported(any())).thenReturn(Optional.of(203L));
        doThrow(new IllegalStateException("task transition failed"))
                .when(banTerminationService).terminateBannedGroup(12L, 203L);

        assertThatThrownBy(() -> adapter.handleHealthReported(
                event("BANNED", "CHAT_SUSPENDED")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("task transition failed");
    }

    private static ProtocolGroupHealthReportedEvent event(String health, String errorCode) {
        return new ProtocolGroupHealthReportedEvent(
                "evt-ban", 12L, null, "1203630banned@g.us", health, null,
                1_786_071_162_912L, errorCode, null, "acc_103", "worker-1");
    }
}
