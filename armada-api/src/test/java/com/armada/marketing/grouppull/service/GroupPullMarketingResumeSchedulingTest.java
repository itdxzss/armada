package com.armada.marketing.grouppull.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullResourceStatus;
import com.armada.marketing.grouppull.model.vo.GroupPullMarketingTaskDetailVO;
import com.armada.marketing.grouppull.service.impl.GroupPullMarketingTaskServiceImpl;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.enums.MarketingBusinessType;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 拉群营销恢复任务时逐料执行的随机重排测试。 */
@ExtendWith(MockitoExtension.class)
class GroupPullMarketingResumeSchedulingTest {

    @Mock
    private GroupPullMarketingMapper mapper;

    @Mock
    private AccountGroupMapper accountGroupMapper;

    @Mock
    private AccountProtocolLookupService accountProtocolLookupService;

    @Test
    void resumeReschedulesEveryPendingMaterialExecutionWithinConfiguredWindow() {
        MarketingTask task = new MarketingTask();
        task.setId(101L);
        task.setOwnerUserId(11L);
        task.setAccountGroupId(201L);
        task.setStatus(MarketingTaskStatus.PAUSED.code());
        GroupPullMarketingTask extension = new GroupPullMarketingTask();
        extension.setMarketingTaskId(101L);
        extension.setBuilderGroupId(301L);
        extension.setMaterialPerGroup(3);
        extension.setMaterialEntryIntervalSeconds(300);
        extension.setResourceStatus(GroupPullResourceStatus.LOCKED.code());
        AccountGroup marketingGroup = new AccountGroup();
        marketingGroup.setId(201L);
        marketingGroup.setOwnerUserId(11L);
        marketingGroup.setMarketingOccupancyType(MarketingBusinessType.GROUP_PULL.code());
        marketingGroup.setMarketingOccupancyTaskId(101L);
        GroupPullMarketingTaskDetailVO detail = detail();
        when(mapper.selectTaskForUpdateForScope(eq(101L), any())).thenReturn(task);
        when(mapper.selectTaskById(101L)).thenReturn(extension);
        when(accountGroupMapper.selectById(201L)).thenReturn(marketingGroup);
        when(mapper.resumeTask(eq(101L), anyLong())).thenReturn(1);
        when(mapper.rescheduleMaterialExecutionsOnResume(
                eq(101L), anyLong(), eq(240_000L), eq(360_000L))).thenReturn(2);
        when(accountProtocolLookupService.findRandomOnlineNormalByGroupId(301L))
                .thenReturn(Optional.of(ProtocolAccountRef.legacyWeb("builder")));
        when(accountProtocolLookupService.findRandomOnlineNormalByGroupId(201L))
                .thenReturn(Optional.of(ProtocolAccountRef.legacyWeb("marketer")));
        when(mapper.countAvailableMaterials(101L)).thenReturn(3L);
        when(mapper.updateBlockReason(eq(101L), eq(0), anyLong())).thenReturn(1);
        when(mapper.selectTaskDetailForScope(eq(101L), any())).thenReturn(detail);
        GroupPullMarketingTaskServiceImpl service = new GroupPullMarketingTaskServiceImpl(
                mapper,
                null,
                null,
                accountGroupMapper,
                accountProtocolLookupService,
                null,
                null,
                null,
                new GroupPullMaterialEntryDelayPolicy());
        ArgumentCaptor<Long> nowCaptor = ArgumentCaptor.forClass(Long.class);

        GroupPullMarketingTaskDetailVO result;
        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.self(11L))) {
            result = service.resume(101L);
        }

        assertThat(result).isSameAs(detail);
        verify(mapper).rescheduleMaterialExecutionsOnResume(
                eq(101L), nowCaptor.capture(), eq(240_000L), eq(360_000L));
        assertThat(nowCaptor.getValue()).isPositive();
    }

    private static GroupPullMarketingTaskDetailVO detail() {
        return new GroupPullMarketingTaskDetailVO(
                101L,
                "恢复排期测试",
                MarketingTaskStatus.SENDING.code(),
                0,
                GroupPullResourceStatus.LOCKED.code(),
                301L,
                null,
                null,
                201L,
                10,
                401L,
                30,
                null,
                3,
                3,
                300,
                1,
                true,
                null,
                System.currentTimeMillis() + 3_600_000L,
                3,
                0,
                0,
                0,
                1,
                0,
                1L,
                1L);
    }
}
