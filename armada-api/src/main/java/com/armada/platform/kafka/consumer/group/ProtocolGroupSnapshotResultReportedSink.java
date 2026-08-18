package com.armada.platform.kafka.consumer.group;

/** 单群快照命令结算下游边界。 */
public interface ProtocolGroupSnapshotResultReportedSink {
    void handleSnapshotResult(ProtocolGroupSnapshotResultReportedEvent event);
}
