package com.armada.group.normalcreation.model.vo;

import com.armada.group.normalcreation.model.enums.NormalGroupCreationErrorMessage;

/**
 * 新建普群加好友失败明细。
 *
 * <p>加好友是尽力而为的可选前置动作，失败不阻断建群，所以群可能已建成而某些方向没加上好友。
 * 这里按方向逐条保留失败原因，供运营核对「哪些成员没加上好友、为什么」。成功的方向原因为空。</p>
 */
public record NormalGroupCreationContactFailureVO(
        Long itemId,
        Integer itemNo,
        Long memberAccountId,
        String memberProtocolBackend,
        String creatorSavedMemberStatus,
        String creatorSaveErrorCode,
        String creatorSaveErrorMessage,
        String memberSavedCreatorStatus,
        String memberSaveErrorCode,
        String memberSaveErrorMessage) {

    private static final String CONTACT_STEP = "PREPARING_CONTACTS";

    public NormalGroupCreationContactFailureVO {
        creatorSaveErrorMessage = NormalGroupCreationErrorMessage.resolve(
                creatorSaveErrorCode, creatorSaveErrorMessage, CONTACT_STEP);
        memberSaveErrorMessage = NormalGroupCreationErrorMessage.resolve(
                memberSaveErrorCode, memberSaveErrorMessage, CONTACT_STEP);
    }
}
