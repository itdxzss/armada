package com.armada.task.service;

import com.armada.platform.kafka.consumer.group.ProtocolGroupJoinResultReportedEvent;
import com.armada.task.model.dto.JoinTaskResultReportedEvent;
import com.armada.task.service.impl.JoinTaskResultReportedSinkAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JoinTaskResultReportedSinkAdapterTest {

    @Test
    void handleJoinResultReported_convertsEveryField() {
        JoinTaskResultService service = mock(JoinTaskResultService.class);
        JoinTaskResultReportedSinkAdapter adapter = new JoinTaskResultReportedSinkAdapter(service);
        ProtocolGroupJoinResultReportedEvent source = new ProtocolGroupJoinResultReportedEvent(
                "event-1", 1L, 9L, 26L, 382L, "acc-1", "cmd-1", 2,
                "FAILED", null, "TEMPORARY_FAILURE", "temporary", true, 123L, "worker-a");

        adapter.handleJoinResultReported(source);

        ArgumentCaptor<JoinTaskResultReportedEvent> captor = ArgumentCaptor.forClass(JoinTaskResultReportedEvent.class);
        verify(service).apply(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new JoinTaskResultReportedEvent(
                "event-1", 1L, 9L, 26L, 382L, "acc-1", "cmd-1", 2,
                "FAILED", null, "TEMPORARY_FAILURE", "temporary", true, 123L, "worker-a"));
    }
}
