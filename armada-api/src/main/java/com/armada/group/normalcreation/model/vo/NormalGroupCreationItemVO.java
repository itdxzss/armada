package com.armada.group.normalcreation.model.vo;

import com.armada.group.normalcreation.model.enums.NormalGroupCreationErrorMessage;

/** 新建普群计划群明细；已知协议错误在出参构造时转换为运营可读提示。 */
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

    public NormalGroupCreationItemVO {
        lastErrorMessage = NormalGroupCreationErrorMessage.resolve(
                lastErrorCode,
                lastErrorMessage,
                currentStep);
    }
}
