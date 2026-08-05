package com.armada.group.service.impl;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.platform.kafka.consumer.account.ProtocolGroupMetadataSyncRequestedEvent;
import com.armada.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 协议群详情同步请求到群任务的 adapter 单测。 */
class GroupMetadataSyncRequestedSinkAdapterTest {

    private final GroupLinkMapper groupLinkMapper = Mockito.mock(GroupLinkMapper.class);
    private final GroupMetadataSyncTaskService taskService = Mockito.mock(GroupMetadataSyncTaskService.class);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void mapsValidatedEventWithinItsTenant() {
        when(groupLinkMapper.selectActiveIdByGroupJid("120363001@g.us")).thenReturn(101L);
        GroupMetadataSyncRequestedSinkAdapter adapter =
                new GroupMetadataSyncRequestedSinkAdapter(groupLinkMapper, taskService);

        adapter.handleGroupMetadataSyncRequested(new ProtocolGroupMetadataSyncRequestedEvent(
                "evt-1", 7L, 22L, "acc_web_22", "120363001@g.us",
                "PARTICIPANT_CHANGED", 1_000L, "wa_group_participants_update", "web-1"));

        verify(taskService).enqueue(101L, GroupMetadataSyncTrigger.PARTICIPANT_CHANGED, 1_000L);
        org.assertj.core.api.Assertions.assertThat(TenantContext.get()).isNull();
    }
}
