package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupBatchTaskItemMapper;
import com.armada.group.mapper.GroupBatchTaskMapper;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.enums.GroupBatchTaskType;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.observability.GroupSnapshotMetrics;
import com.armada.group.service.impl.GroupBatchRefreshSupport;
import com.armada.group.service.impl.GroupBatchTaskSettlement;
import com.armada.platform.protocol.model.command.ProtocolGroupSnapshotCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 批量明细与快照 Outbox 的事务关联测试。 */
@ExtendWith(MockitoExtension.class)
class GroupBatchSnapshotDispatchServiceTest {

    @Mock private GroupBatchTaskItemMapper itemMapper;
    @Mock private GroupBatchTaskMapper taskMapper;
    @Mock private GroupBatchRefreshSupport support;
    @Mock private GroupBatchTaskSettlement settlement;
    @Mock private ProtocolCommandOutboxService outboxService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void metadataItemWritesOutboxThenFreezesCommandCorrelation() {
        TenantContext.set(1L);
        GroupBatchTaskItem item = item();
        GroupExecutionAccount account = new GroupExecutionAccount(
                77L, "ANDROID", "acc-77", "919", true);
        when(support.selector()).thenReturn(org.mockito.Mockito.mock(GroupExecutionAccountSelector.class));
        when(support.selector().find(5001L, 0)).thenReturn(Optional.of(account));
        when(support.groupJid(5001L)).thenReturn("120363batch@g.us");
        when(outboxService.enqueueGroupSnapshotCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(null, List.of("cmd-batch"), 1));
        when(itemMapper.markWaitingResult(any(), anyInt(), anyInt())).thenReturn(1);

        assertThat(service().dispatch(item, GroupBatchTaskType.REFRESH_INFO, 1_000L)).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolGroupSnapshotCommandRequest>> commands =
                ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueGroupSnapshotCommands(commands.capture());
        ProtocolGroupSnapshotCommandRequest command = commands.getValue().get(0);
        assertThat(command.taskType()).isEqualTo("GROUP_BATCH_TASK_ITEM");
        assertThat(command.taskId()).isEqualTo(9L);
        assertThat(command.scopes()).containsExactly("METADATA");
        assertThat(command.wsPhone()).isEqualTo("919");
        assertThat(command.protocolBackend().name()).isEqualTo("ANDROID");
        verify(itemMapper).markWaitingResult(
                any(), eq(GroupBatchTaskItemStatus.PENDING.code()),
                eq(GroupBatchTaskItemStatus.WAITING_RESULT.code()));
        assertThat(item.getCurrentCommandId()).isEqualTo("cmd-batch");
    }

    private GroupBatchSnapshotDispatchService service() {
        return new GroupBatchSnapshotDispatchService(
                itemMapper, taskMapper, support, settlement, outboxService,
                new GroupSnapshotProperties(false, 20, 1, 120_000L, 4, false),
                new GroupSnapshotMetrics());
    }

    private static GroupBatchTaskItem item() {
        GroupBatchTaskItem item = new GroupBatchTaskItem();
        item.setId(9L);
        item.setTenantId(1L);
        item.setTaskId(900L);
        item.setGroupLinkId(5001L);
        item.setStatus(GroupBatchTaskItemStatus.PENDING.code());
        item.setAttemptCount(0);
        item.setCandidateCursor(0);
        return item;
    }
}
