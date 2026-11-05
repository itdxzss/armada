package com.armada.account.service;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
/** 有界跨组展开及单账号等待规则。 */
public final class AccountMutualChatPolicy {
    /** 首版最多五千条定向操作，避免误触发无界笛卡尔积。 */
    public static final int MAX_OPERATIONS = 5_000;
    /** 操作人员可配置到一小时。 */
    public static final int MAX_INTERVAL_SECONDS = 3600;
    /** 固定纯文本的 UTF-16 长度上限，与前端 maxlength 一致。 */
    public static final int MAX_TEXT_LENGTH = 1000;
    /** 协议链路来源。 */
    public static final String SOURCE = "account_group_mutual_chat";
    private AccountMutualChatPolicy() {}
    /** 校验参与范围并返回两个方向的总量。 */
    public static int operationCount(int left, int right) {
        long count = 2L * left * right;
        if (left <= 0 || right <= 0 || count > MAX_OPERATIONS) {
            throw new BusinessException(
                    ErrorCode.VALIDATION, "两组都需有可用账号，且定向发送总数不能超过 " + MAX_OPERATIONS);
        }
        return (int) count;
    }
    /** 从结果处理时间起计算额外等待；任务至少等待一秒。 */
    public static long nextAt(long now, int seconds) {
        if (seconds < 1 || seconds > MAX_INTERVAL_SECONDS) {
            throw new BusinessException(ErrorCode.VALIDATION, "发送间隔须为 1 到 3600 秒");
        }
        return now + seconds * 1000L;
    }
    /** 规范化任务文本，不把正文写入异常或日志。 */
    public static String text(String value) {
        String text = value == null ? "" : value.strip();
        if (text.isBlank() || text.length() > MAX_TEXT_LENGTH)
            throw new BusinessException(ErrorCode.VALIDATION, "两侧文案均需填写，且不得超过 1000 字符");
        return text;
    }
}
