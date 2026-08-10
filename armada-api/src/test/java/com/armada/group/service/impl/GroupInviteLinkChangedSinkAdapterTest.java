package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.armada.group.model.dto.GroupInviteLinkChangedEvent;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.platform.kafka.consumer.group.ProtocolGroupInviteLinkChangedEvent;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GroupInviteLinkChangedSinkAdapterTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void handlesEventInsideTenantContextAndRestoresIt() {
        GroupInviteLinkService service = mock(GroupInviteLinkService.class);
        AtomicReference<Long> observedTenant = new AtomicReference<>();
        AtomicReference<GroupInviteLinkChangedEvent> observedEvent = new AtomicReference<>();
        doAnswer(invocation -> {
            observedTenant.set(TenantContext.get());
            observedEvent.set(invocation.getArgument(0));
            return null;
        }).when(service).apply(any());
        GroupInviteLinkChangedSinkAdapter adapter =
                new GroupInviteLinkChangedSinkAdapter(service);

        adapter.handleInviteLinkChanged(new ProtocolGroupInviteLinkChangedEvent(
                "evt-1", 7L, 901L, "acc-901", "ANDROID",
                "120363group@g.us", "NewInviteCode_2026", null,
                "wgp2_notification", 1786341600000L, "worker"));

        assertThat(observedTenant.get()).isEqualTo(7L);
        assertThat(observedEvent.get()).isEqualTo(new GroupInviteLinkChangedEvent(
                "evt-1", "120363group@g.us", "NewInviteCode_2026",
                ProtocolBackend.ANDROID, 1786341600000L));
        assertThat(TenantContext.get()).isNull();
    }
}
