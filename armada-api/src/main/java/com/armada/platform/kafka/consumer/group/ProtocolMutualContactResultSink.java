package com.armada.platform.kafka.consumer.group;
/** 平台到账号互存业务的单向回执边界。 */
public interface ProtocolMutualContactResultSink {
    void apply(ProtocolMutualContactResult result);
}
