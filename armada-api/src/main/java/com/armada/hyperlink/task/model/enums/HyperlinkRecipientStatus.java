package com.armada.hyperlink.task.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 一个 recipient 的唯一逻辑发送状态。 */
public enum HyperlinkRecipientStatus {
    /** 尚未生成协议命令。 */
    PENDING(1, 0, false),
    /** 已有稳定 commandId，等待结果。 */
    SENDING(2, 1, false),
    /** 至少达到单钩。 */
    SUCCESS(3, 2, false),
    /** 至少达到双钩。 */
    DELIVERED(4, 3, false),
    /** 已读。 */
    READ(5, 4, false),
    /** 最终失败。 */
    FAILED(6, -1, true),
    /** 确认未开通 WhatsApp 的最终失败子类。 */
    UNREGISTERED(7, -1, true);

    private final int code;
    private final int rank;
    private final boolean terminalFailure;

    HyperlinkRecipientStatus(int code, int rank, boolean terminalFailure) {
        this.code = code;
        this.rank = rank;
        this.terminalFailure = terminalFailure;
    }

    public int code() { return code; }
    public int rank() { return rank; }
    public boolean terminalFailure() { return terminalFailure; }

    /** 按数据库码解析状态。 */
    public static HyperlinkRecipientStatus fromCode(Integer code) {
        for (HyperlinkRecipientStatus status : values()) {
            if (Integer.valueOf(status.code).equals(code)) {
                return status;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "recipient 状态非法");
    }
}
