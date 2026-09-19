package com.armada.account.service;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
/** 有界跨组展开及单账号等待规则。 */
public final class AccountMutualContactPolicy {
    /** 首版最多两万条定向操作，避免误触发无界笛卡尔积。 */
    public static final int MAX_OPERATIONS = 20_000;
    /** 操作人员可配置到一小时。 */
    public static final int MAX_INTERVAL_SECONDS = 3600;
    private AccountMutualContactPolicy() {}
    /** 校验参与范围并返回两个方向的总量。 */
    public static int operationCount(int left, int right) {
        long count = 2L * left * right;
        if (left <= 0 || right <= 0 || count > MAX_OPERATIONS) {
            throw new BusinessException(
                    ErrorCode.VALIDATION, "两组都需有可用账号，且定向保存总数不能超过 " + MAX_OPERATIONS);
        }
        return (int) count;
    }
    /** 从结果处理时间起计算额外等待；0 不追加冷却。 */
    public static long nextAt(long now, int seconds) {
        if (seconds < 0 || seconds > MAX_INTERVAL_SECONDS) {
            throw new BusinessException(ErrorCode.VALIDATION, "保存间隔须为 0 到 3600 秒");
        }
        return now + seconds * 1000L;
    }
}
