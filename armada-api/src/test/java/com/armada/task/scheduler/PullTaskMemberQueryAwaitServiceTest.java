package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.shared.exception.BusinessException;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskMemberQueryDefer;
import com.armada.task.model.dto.PullTaskMemberQueryRequest;
import com.armada.task.model.dto.PullTaskMemberQueryResult;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PullTaskMemberQueryAwaitServiceTest {

    @Mock private PullTaskMemberQueryService queryService;
    @Mock private PullTaskGroupExecutionMapper executionMapper;

    @AfterEach
    void clearTenant() {
        com.armada.shared.tenant.TenantContext.clear();
    }

    @Test
    void readOrDefer_pendingAtomicallyReleasesLeaseUntilQueryDeadline() {
        when(queryService.requestOrRead(any(), anyLong()))
                .thenReturn(PullTaskMemberQueryResult.pending(701L, 31_000L));
        when(executionMapper.deferForMemberQuery(any())).thenReturn(1);
        PullTaskMemberQueryAwaitService service =
                new PullTaskMemberQueryAwaitService(queryService, executionMapper);

        PullTaskMemberQueryResult result = service.readOrDefer(
                7L, request(), 4, "worker-1", 3, 1_000L);

        assertThat(result.state()).isEqualTo(PullTaskMemberQueryResult.State.PENDING);
        ArgumentCaptor<PullTaskMemberQueryDefer> captor =
                ArgumentCaptor.forClass(PullTaskMemberQueryDefer.class);
        verify(executionMapper).deferForMemberQuery(captor.capture());
        assertThat(captor.getValue().nextRunAt()).isEqualTo(31_000L);
        assertThat(captor.getValue().expectedVersion()).isEqualTo(4);
        assertThat(com.armada.shared.tenant.TenantContext.get()).isNull();
    }

    @Test
    void readOrDefer_availableKeepsCurrentLeaseForCallerCompletion() {
        when(queryService.requestOrRead(any(), anyLong()))
                .thenReturn(PullTaskMemberQueryResult.available(701L, List.of()));
        PullTaskMemberQueryAwaitService service =
                new PullTaskMemberQueryAwaitService(queryService, executionMapper);

        PullTaskMemberQueryResult result = service.readOrDefer(
                7L, request(), 4, "worker-1", 3, 1_000L);

        assertThat(result.state()).isEqualTo(PullTaskMemberQueryResult.State.AVAILABLE);
        verify(executionMapper, never()).deferForMemberQuery(any());
    }

    @Test
    void readOrDefer_rollsBackQueryWhenLeaseIdentityWasLost() {
        when(queryService.requestOrRead(any(), anyLong()))
                .thenReturn(PullTaskMemberQueryResult.pending(701L, 31_000L));
        when(executionMapper.deferForMemberQuery(any())).thenReturn(0);
        PullTaskMemberQueryAwaitService service =
                new PullTaskMemberQueryAwaitService(queryService, executionMapper);

        assertThatThrownBy(() -> service.readOrDefer(
                7L, request(), 4, "worker-1", 3, 1_000L))
                .isInstanceOf(BusinessException.class);
    }

    private static PullTaskMemberQueryRequest request() {
        return new PullTaskMemberQueryRequest(
                100L, 11L, "manager-admin:601",
                PullTaskMemberQueryPurpose.MANAGER_ADMIN_MEMBERSHIP,
                ProtocolAccountRef.legacyWeb("manager-901"),
                "120363group@g.us", List.of("8613800000902@s.whatsapp.net"));
    }
}
