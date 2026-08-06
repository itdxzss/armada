package com.armada.group.normalcreation.model.vo;

/** 新建普群计划群明细。 */
public record NormalGroupCreationItemVO(
        Long id,
        Integer itemNo,
        String groupSubject,
        Long creatorAccountId,
        String creatorProtocolBackend,
        String groupJid,
        Long groupLinkId,
        String status,
        String currentStep,
        String settingsStatus,
        String creatorLeaveStatus,
        String lastErrorCode,
        String lastErrorMessage,
        Long updatedAt) {
}
