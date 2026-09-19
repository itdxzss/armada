package com.armada.platform.kafka.consumer.group;
/** 平台校验后的定向联系人保存结果。 */
public record
        ProtocolMutualContactResult(Long tenantId, Long taskId, Long itemId, Long accountId, String protocolAccountId,
                String commandId, int attemptNo, String outcome, String reasonCode, boolean retryable) {}
