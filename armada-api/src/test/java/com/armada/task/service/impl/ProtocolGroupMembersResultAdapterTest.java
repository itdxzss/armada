package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.armada.platform.kafka.consumer.group.ProtocolGroupMemberFact;
import com.armada.platform.kafka.consumer.group.ProtocolGroupMembersResultReportedEvent;
import com.armada.task.model.dto.PullTaskMemberQueryCallback;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import com.armada.task.service.PullTaskMemberQueryResultService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProtocolGroupMembersResultAdapterTest {

    @Test
    void mapsEnvelopeEventIdAndDiscoveryPurposeIntoTaskCallback() {
        PullTaskMemberQueryResultService resultService =
                mock(PullTaskMemberQueryResultService.class);
        ProtocolGroupMembersResultAdapter adapter =
                new ProtocolGroupMembersResultAdapter(resultService);

        adapter.handleMembersResultReported(new ProtocolGroupMembersResultReportedEvent(
                "event-701", 7L, 100L, 11L, 701L, "MANAGER_ADMIN_DISCOVERY",
                901L, "account-901", "WEB", "command-701", 1, "SUCCESS",
                "120363group@g.us", List.of(new ProtocolGroupMemberFact(
                "906@s.whatsapp.net", "906@s.whatsapp.net", "906", true, true)),
                null, null, false, 5_000L, "worker-1"));

        ArgumentCaptor<PullTaskMemberQueryCallback> callback =
                ArgumentCaptor.forClass(PullTaskMemberQueryCallback.class);
        verify(resultService).apply(callback.capture());
        assertThat(callback.getValue().eventId()).isEqualTo("event-701");
        assertThat(callback.getValue().purpose())
                .isEqualTo(PullTaskMemberQueryPurpose.MANAGER_ADMIN_DISCOVERY);
        assertThat(callback.getValue().members()).singleElement().satisfies(fact -> {
            assertThat(fact.targetJid()).isEqualTo("906@s.whatsapp.net");
            assertThat(fact.admin()).isTrue();
        });
    }
}
