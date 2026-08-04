package com.armada.platform.kafka.consumer.group;

/** 统一进群结果的业务关联，按来源保持强类型，禁止业务间复用主键。 */
public sealed interface ProtocolGroupJoinCorrelation
        permits ProtocolJoinTaskGroupJoinCorrelation, ProtocolPullTaskGroupJoinCorrelation {
}
