package com.armada.task.model.dto;

/** 按最新 attempt 的真实协议结果修复历史待执行投影，状态码由业务层提供。 */
public record PullTaskHistoricalRetryNormalization(
        Scope scope,
        Target target,
        AttemptState attemptState,
        RetryRule retryRule,
        long now) {

    /** 执行行与参与者类别共同限定历史台账。 */
    public record Scope(long executionId, int participantType) {
    }

    /** 只归一待执行行，真实协议结果决定恢复为失败还是未知。 */
    public record Target(int pendingStatus, int resultStatus, String protocolOutcome) {
    }

    /** 只接管已关闭或释放的台账，未知协议结果保留独立语义。 */
    public record AttemptState(int closed, int released, String unknownOutcome, String failedOutcome) {
    }

    /** 总尝试预算及允许未知重试所必需的未执行或名单事实。 */
    public record RetryRule(
            int maxAttempts, String notStarted, String uncertain, String confirmedAbsenceReason,
            String retryableFailureReason) {
    }
}
