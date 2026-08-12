package com.armada.platform.kafka.consumer.pairing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.armada.shared.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProtocolPairingEventConsumerTest {

    private static final String FIXED_TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Mock
    private ProtocolPairingEventSink sink;

    @Test
    void parsesProtocolAccountAndBusinessDetectionForExactSessionCorrelation() {
        ProtocolPairingEventConsumer consumer = new ProtocolPairingEventConsumer(new ObjectMapper(), sink);
        doAnswer(invocation -> {
            assertThat(TraceContext.current()).contains(FIXED_TRACE_ID);
            return null;
        }).when(sink).handle(any());

        consumer.onMessage("""
                {
                  "traceId":"0123456789abcdef0123456789abcdef",
                  "eventId":"evt-1",
                  "event":"pairing.completed",
                  "version":1,
                    "accountId":"acc_pair_7d9ca2f10b8e4c31",
                  "occurredAt":"2027-01-15T08:00:00Z",
                  "workerId":"worker-1",
                  "data":{
                    "phone":"919876543210:12",
                    "jid":"919876543210:12@s.whatsapp.net",
                    "detection":{"accountType":"BUSINESS_STANDARD"},
                    "completedAt":"2027-01-15T08:00:00Z"
                  }
                }
                """, null);

        ArgumentCaptor<ProtocolPairingEvent> captor = ArgumentCaptor.forClass(ProtocolPairingEvent.class);
        verify(sink).handle(captor.capture());
        assertThat(captor.getValue().protocolAccountId()).isEqualTo("acc_pair_7d9ca2f10b8e4c31");
        assertThat(captor.getValue().detectedAccountType()).isEqualTo("BUSINESS_STANDARD");
        assertThat(TraceContext.current()).isEmpty();
    }
}
