package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.platform.kafka.consumer.message.ProtocolMessageAckEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageAckSink;
import com.armada.platform.kafka.consumer.message.ProtocolMessageEventConsumer;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** ACK 优先读 uppercase ackStatus，且 hyperlink 唯一路由不要求 marketing attempt。 */
class HyperlinkProtocolAckRoutingTest {

    @Test
    void sendResultReadsFrozenHyperlinkRecipientIdAndOutcomeFields() {
        AtomicReference<ProtocolMessageSendResultReportedEvent> captured = new AtomicReference<>();
        ProtocolMessageSendResultReportedSink sink = new ProtocolMessageSendResultReportedSink() {
            @Override public boolean supports(ProtocolMessageSendResultReportedEvent event) { return true; }
            @Override public void handleSendResultReported(ProtocolMessageSendResultReportedEvent event) {
                captured.set(event);
            }
        };
        ProtocolMessageEventConsumer consumer = new ProtocolMessageEventConsumer(
                new ObjectMapper(), List.of(sink), List.of());

        consumer.onMessage("""
                {"event":"message.send_result_reported","data":{"tenantId":7,
                  "source":"hyperlink_task","hyperlinkTaskId":11,"hyperlinkRecipientId":13,
                  "protocolAccountId":"acc_17","commandId":"hl:7:11:13",
                  "jid":"8613800000000@s.whatsapp.net","targetKind":"PRIVATE",
                  "success":false,"outcome":"UNKNOWN","terminal":false,
                  "reasonCode":"MESSAGE_SEND_RESULT_UNKNOWN","timestamp":1234}}
                """, null);

        assertThat(captured.get().hyperlinkRecipientId()).isEqualTo(13L);
        assertThat(captured.get().outcome()).isEqualTo("UNKNOWN");
        assertThat(captured.get().terminal()).isFalse();
    }

    @Test
    void uppercaseAckStatusWinsOverLegacyStatus() {
        AtomicReference<ProtocolMessageAckEvent> captured = new AtomicReference<>();
        ProtocolMessageAckSink sink = new ProtocolMessageAckSink() {
            @Override public boolean supports(ProtocolMessageAckEvent event) {
                return "hyperlink_task".equals(event.source());
            }
            @Override public void handleAck(ProtocolMessageAckEvent event) { captured.set(event); }
        };
        ProtocolMessageEventConsumer consumer = new ProtocolMessageEventConsumer(
                new ObjectMapper(), List.of(), List.of(sink));

        consumer.onMessage("""
                {"event":"message.ack","eventId":"ack-1","workerId":"w1","data":{
                  "tenantId":7,"source":"hyperlink_task","hyperlinkTaskId":11,"hyperlinkRecipientId":13,
                  "commandId":"hl:7:11:13","protocolAccountId":"acc_17",
                  "jid":"8613800000000@s.whatsapp.net","targetKind":"PRIVATE",
                  "messageId":"m1","ackStatus":"READ","status":"server","success":true,
                  "timestamp":1234
                }}
                """, null);

        assertThat(captured.get().ackStatus()).isEqualTo("READ");
        assertThat(captured.get().hyperlinkRecipientId()).isEqualTo(13L);
        assertThat(captured.get().commandId()).isEqualTo("hl:7:11:13");
    }

    @Test
    void legacyDeliveryStatusRemainsCompatible() {
        AtomicReference<ProtocolMessageAckEvent> captured = new AtomicReference<>();
        ProtocolMessageAckSink sink = new ProtocolMessageAckSink() {
            @Override public boolean supports(ProtocolMessageAckEvent event) { return true; }
            @Override public void handleAck(ProtocolMessageAckEvent event) { captured.set(event); }
        };
        ProtocolMessageEventConsumer consumer = new ProtocolMessageEventConsumer(
                new ObjectMapper(), List.of(), List.of(sink));
        consumer.onMessage("""
                {"event":"message.ack","data":{"tenantId":7,"source":"hyperlink_task",
                  "hyperlinkTaskId":11,"hyperlinkRecipientId":13,"protocolAccountId":"acc_17",
                  "messageId":"m1","status":"delivery"}}
                """, null);
        assertThat(captured.get().ackStatus()).isEqualTo("DELIVERED");
    }
}
