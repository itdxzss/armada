package com.armada.platform.protocol.model.command;

public record ProtocolMarketingMessageCommandRequest(
        Long tenantId,
        Long marketingTaskId,
        Long attemptId,
        Long targetId,
        Long roundNo,
        Long accountId,
        String protocolAccountId,
        String groupJid,
        String messageType,
        String text,
        String imageBase64,
        String imageMimetype,
        String source
) {
}
