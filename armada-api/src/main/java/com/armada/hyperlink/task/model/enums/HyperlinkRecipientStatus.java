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

    /** 面向租户的结果码，内部协议和账号错误留在后台诊断记录。 */
    public String businessCode(String internalCode) {
        if (this == UNREGISTERED || this == FAILED && "INVALID_TARGET_JID".equals(internalCode)) {
            return "TARGET_UNAVAILABLE";
        }
        if (this == FAILED) { return "INCOMPLETE"; }
        if (this == SENDING && "SEND_RESULT_UNKNOWN".equals(internalCode)) { return "RESULT_PENDING"; }
        if (this == PENDING && internalCode != null) { return "RECOVERY_PENDING"; }
        return null;
    }

    /** 列表和导出共同使用的业务说明，不返回设备、账号或密钥故障细节。 */
    public String businessMessage(String internalCode) {
        String business = businessCode(internalCode);
        if (business == null) { return null; }
        return switch (business) {
            case "TARGET_UNAVAILABLE" -> "目标数据导致无法发送";
            case "RESULT_PENDING" -> "结果确认中";
            case "RECOVERY_PENDING" -> "等待恢复发送；任务暂停后可在修复完成时继续";
            default -> "未完成";
        };
    }

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
