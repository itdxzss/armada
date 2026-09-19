package com.armada.platform.kafka.consumer.group;

/** 进群提管理员成员级结果，执行者与目标成员分别关联。 */
public record ProtocolJoinTaskAdminResult(
        Long tenantId, Long joinTaskId, Long joinTaskResultId, String commandId, int attemptNo,
        Long accountId, String protocolAccountId, String groupJid, String targetJid,
        String outcome, String reasonCode, boolean retryable, long timestamp, String eventId) { }
