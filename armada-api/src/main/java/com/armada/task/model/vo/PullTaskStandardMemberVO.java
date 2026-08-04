package com.armada.task.model.vo;

/** TXT 料子的入群和提权结果详情。 */
public record PullTaskStandardMemberVO(
        long memberId,
        int memberSeq,
        String normalizedPhone,
        boolean adminRequired,
        Long pullCallId,
        int pullStatus,
        String pullReasonCode,
        String pullReasonMessage,
        String waJid,
        int adminStatus,
        String adminReasonCode) {
}
