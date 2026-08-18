package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupBatchTaskItemMapper;
import com.armada.group.mapper.GroupBatchTaskMapper;
import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupBatchSubmitDTO;
import com.armada.group.model.entity.GroupBatchTask;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.enums.GroupBatchTaskStatus;
import com.armada.group.model.vo.GroupBatchTaskAcceptedVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 群组列表批量刷新任务提交与进度单测。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupBatchTaskServiceImplTest {

    private static final long TENANT_ID = 7L;
    private static final long OPERATOR_ID = 55L;

    @Mock
    private GroupBatchTaskMapper taskMapper;

    @Mock
    private GroupBatchTaskItemMapper itemMapper;

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private GroupLinkHealthMapper healthMapper;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void submitRejectsEmptySelectionSoNoTaskIsEverCreatedWithoutTargets() {
        assertThatThrownBy(() -> service().submitRefreshLinks(
                new GroupBatchSubmitDTO(List.of(), "req-1"), OPERATOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先勾选");

        verify(taskMapper, never()).insert(any());
    }

    @Test
    void submitReturnsTheExistingTaskWhenRequestIdRepeats() {
        GroupBatchTask existing = new GroupBatchTask();
        existing.setId(900L);
        existing.setCreatedAt(1_000L);
        existing.setStatus(2);
        when(taskMapper.selectByRequestId("req-dup")).thenReturn(existing);

        GroupBatchTaskAcceptedVO accepted = service().submitRefreshLinks(
                new GroupBatchSubmitDTO(List.of(101L), "req-dup"), OPERATOR_ID);

        assertThat(accepted.taskId()).isEqualTo(900L);
        verify(taskMapper, never()).insert(any());
        verify(itemMapper, never()).batchInsert(anyList());
    }

    @Test
    void refreshLinksRecordsStateBlockedGroupsAsFailedItemsInsteadOfExecutingThem() {
        when(groupLinkMapper.selectActiveByIds(List.of(101L, 102L)))
                .thenReturn(List.of(groupLink(101L), groupLink(102L)));
        when(healthMapper.selectLinkRefreshBlockedIds(List.of(101L, 102L)))
                .thenReturn(List.of(102L));

        service().submitRefreshLinks(
                new GroupBatchSubmitDTO(List.of(101L, 102L), "req-blocked"), OPERATOR_ID);

        List<GroupBatchTaskItem> items = capturedItems();
        assertThat(items).extracting(GroupBatchTaskItem::getGroupLinkId)
                .containsExactly(101L, 102L);
        assertThat(items.get(0).getStatus()).isEqualTo(GroupBatchTaskItemStatus.PENDING.code());
        // 封禁群在提交阶段就落终态失败，绕过前端置灰也不会走到写路径。
        assertThat(items.get(1).getStatus()).isEqualTo(GroupBatchTaskItemStatus.FAILED.code());
        assertThat(items.get(1).getDescription()).contains("状态异常");
    }

    @Test
    void refreshInfoAcceptsStateBlockedGroupsBecauseReadingInfoIsNotAWriteOperation() {
        when(groupLinkMapper.selectActiveByIds(List.of(101L, 102L)))
                .thenReturn(List.of(groupLink(101L), groupLink(102L)));
        when(healthMapper.selectLinkRefreshBlockedIds(List.of(101L, 102L)))
                .thenReturn(List.of(102L));

        service().submitRefreshInfo(
                new GroupBatchSubmitDTO(List.of(101L, 102L), "req-info"), OPERATOR_ID);

        // 获取最新群信息是只读操作，封禁群也要放行；两项都留给执行器实时直调协议。
        assertThat(capturedItems()).allSatisfy(item ->
                assertThat(item.getStatus()).isEqualTo(GroupBatchTaskItemStatus.PENDING.code()));
    }

    @Test
    void submitDeduplicatesIdsAndDropsGroupsOutsideTheTenant() {
        when(groupLinkMapper.selectActiveByIds(List.of(101L, 102L)))
                .thenReturn(List.of(groupLink(101L)));
        when(healthMapper.selectLinkRefreshBlockedIds(anyList())).thenReturn(List.of());

        service().submitRefreshInfo(
                new GroupBatchSubmitDTO(List.of(101L, 101L, 102L), "req-dedup"), OPERATOR_ID);

        assertThat(capturedItems()).extracting(GroupBatchTaskItem::getGroupLinkId)
                .containsExactly(101L);
        ArgumentCaptor<GroupBatchTask> task = ArgumentCaptor.forClass(GroupBatchTask.class);
        verify(taskMapper).insert(task.capture());
        assertThat(task.getValue().getTotalCount()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private List<GroupBatchTaskItem> capturedItems() {
        ArgumentCaptor<List<GroupBatchTaskItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemMapper).batchInsert(captor.capture());
        return captor.getValue();
    }

    private static GroupLink groupLink(long id) {
        GroupLink link = new GroupLink();
        link.setId(id);
        link.setTenantId(TENANT_ID);
        return link;
    }

    @Test
    void closingTheDialogCancelsRemainingItemsSoTheyStopSendingProtocolCalls() {
        when(taskMapper.selectById(900L)).thenReturn(taskWithStatus(GroupBatchTaskStatus.RUNNING));
        when(itemMapper.cancelPending(
                org.mockito.ArgumentMatchers.eq(900L),
                anyInt(),
                anyInt(),
                anyInt(),
                anyLong())).thenReturn(3);

        assertThat(service().cancel(900L)).isEqualTo(3);

        verify(itemMapper).cancelPending(
                org.mockito.ArgumentMatchers.eq(900L),
                org.mockito.ArgumentMatchers.eq(GroupBatchTaskItemStatus.CANCELED.code()),
                org.mockito.ArgumentMatchers.eq(GroupBatchTaskItemStatus.PENDING.code()),
                org.mockito.ArgumentMatchers.eq(GroupBatchTaskItemStatus.WAITING_RESULT.code()),
                anyLong());
        verify(taskMapper).cancelIfRunnable(
                org.mockito.ArgumentMatchers.eq(900L),
                org.mockito.ArgumentMatchers.eq(GroupBatchTaskStatus.CANCELED.code()),
                org.mockito.ArgumentMatchers.eq(List.of(
                        GroupBatchTaskStatus.PENDING.code(),
                        GroupBatchTaskStatus.RUNNING.code())),
                anyLong());
    }

    @Test
    void cancelingAnAlreadyFinishedTaskChangesNothing() {
        when(taskMapper.selectById(900L))
                .thenReturn(taskWithStatus(GroupBatchTaskStatus.COMPLETED));

        assertThat(service().cancel(900L)).isZero();

        // 已完成的任务没有待执行项；改写状态会把成功的批次显示成已取消。
        verify(itemMapper, never()).cancelPending(any(), anyInt(), anyInt(), anyInt(), anyLong());
        verify(taskMapper, never()).cancelIfRunnable(any(), anyInt(), anyList(), anyLong());
    }

    @Test
    void cancelRejectsATaskOutsideTheCurrentTenant() {
        when(taskMapper.selectById(900L)).thenReturn(null);

        assertThatThrownBy(() -> service().cancel(900L))
                .isInstanceOf(BusinessException.class);
    }

    private static GroupBatchTask taskWithStatus(GroupBatchTaskStatus status) {
        GroupBatchTask task = new GroupBatchTask();
        task.setId(900L);
        task.setTenantId(TENANT_ID);
        task.setTaskType(1);
        task.setStatus(status.code());
        task.setTotalCount(3);
        task.setSuccessCount(0);
        task.setFailedCount(0);
        return task;
    }

    private GroupBatchTaskServiceImpl service() {
        return new GroupBatchTaskServiceImpl(
                taskMapper, itemMapper, groupLinkMapper, healthMapper);
    }
}
