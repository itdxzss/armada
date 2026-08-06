package com.armada.group.normalcreation.service;

/** 新建普群三阶段业务消息发布边界。 */
public interface NormalGroupCreationEventPublisher {

    /** 发布一个计划群的待执行阶段。 */
    void publish(
            String action, Long tenantId, Long taskId, Long itemId, Long creatorAccountId);
}
