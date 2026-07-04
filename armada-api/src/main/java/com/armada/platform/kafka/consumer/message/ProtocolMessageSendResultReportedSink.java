package com.armada.platform.kafka.consumer.message;

public interface ProtocolMessageSendResultReportedSink {
    void handleSendResultReported(ProtocolMessageSendResultReportedEvent event);
}
