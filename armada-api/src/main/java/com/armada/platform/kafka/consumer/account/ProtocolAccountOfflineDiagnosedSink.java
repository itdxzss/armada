package com.armada.platform.kafka.consumer.account;

public interface ProtocolAccountOfflineDiagnosedSink {

    void handleOfflineDiagnosed(ProtocolAccountOfflineDiagnosedEvent event);
}
