package com.armada.platform.kafka.consumer.group;

/** 平台向进群任务域传递已校验的管理员结果。 */
public interface ProtocolJoinTaskAdminResultSink {
    /** 处理当前命令结果，重复和迟到消息不得重复推进。 */
    void apply(ProtocolJoinTaskAdminResult event);
}
