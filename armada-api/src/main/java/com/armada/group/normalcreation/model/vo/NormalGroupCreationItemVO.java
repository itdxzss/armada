package com.armada.group.normalcreation.model.vo;

import com.armada.group.normalcreation.model.enums.NormalGroupCreationErrorMessage;

/**
 * 新建普群计划群明细；已知协议错误在出参构造时转换为运营可读提示。
 *
 * <p>{@code contactPrepareFailed} 表示进入建群阶段时存在未成功的加好友方向。加好友失败不阻断
 * 建群，所以该标记为真时 {@code status} 仍可能是 {@code CREATED}；具体失败原因见任务详情的
 * 加好友失败明细列表。</p>
 */
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
        Long updatedAt,
        Boolean contactPrepareFailed) {

    public NormalGroupCreationItemVO {
        lastErrorMessage = NormalGroupCreationErrorMessage.resolve(
                lastErrorCode,
                lastErrorMessage,
                currentStep);
    }
}
