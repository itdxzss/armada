package com.armada.platform.kafka.consumer.group;

/** 普通拉群异步成员查询结果的任务域处理边界。 */
public interface ProtocolGroupMembersResultReportedSink {

    /** 接收一条完成严格结构校验的群成员查询结果。 */
    void handleMembersResultReported(ProtocolGroupMembersResultReportedEvent event);
}
