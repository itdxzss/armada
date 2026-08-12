package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupBatchTaskAcceptedVO;
import com.armada.group.service.GroupMetadataSyncTaskService;
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

    @Mock
    private GroupMetadataSyncTaskService metadataSyncTaskService;

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
    void refreshInfoAcceptsStateBlockedGroupsAndEnqueuesThemOnTheBatchLane() {
        when(groupLinkMapper.selectActiveByIds(List.of(101L, 102L)))
                .thenReturn(List.of(groupLink(101L), groupLink(102L)));
        when(healthMapper.selectLinkRefreshBlockedIds(List.of(101L, 102L)))
                .thenReturn(List.of(102L));

        service().submitRefreshInfo(
                new GroupBatchSubmitDTO(List.of(101L, 102L), "req-info"), OPERATOR_ID);

        assertThat(capturedItems()).allSatisfy(item ->
                assertThat(item.getStatus()).isEqualTo(GroupBatchTaskItemStatus.PENDING.code()));
        // 只读同步走批量档 trigger，绝不能用 MANUAL_REFRESH 挤占实时链路。
        verify(metadataSyncTaskService).enqueue(
                org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.eq(GroupMetadataSyncTrigger.BATCH_REFRESH),
                org.mockito.ArgumentMatchers.anyLong());
        verify(metadataSyncTaskService).enqueue(
                org.mockito.ArgumentMatchers.eq(102L),
                org.mockito.ArgumentMatchers.eq(GroupMetadataSyncTrigger.BATCH_REFRESH),
                org.mockito.ArgumentMatchers.anyLong());
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

    private GroupBatchTaskServiceImpl service() {
        return new GroupBatchTaskServiceImpl(
                taskMapper, itemMapper, groupLinkMapper, healthMapper, metadataSyncTaskService);
    }
}
