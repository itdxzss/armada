package com.armada.marketing.model.vo;

public record GroupCreationMarketingItemVO(
        Long id,
        Integer fileIndex,
        String fileName,
        Integer participantCount,
        Long accountId,
        String accountPhone,
        String protocolAccountId,
        String groupSubject,
        String groupJid,
        Long groupLinkId,
        Long marketingTaskId,
        Long marketingTargetId,
        Long marketingAttemptId,
        String commandId,
        Integer status,
        String reasonCode,
        String reasonMessage,
        Long startedAt,
        Long finishedAt,
        Long createdAt,
        Long updatedAt) {
}
