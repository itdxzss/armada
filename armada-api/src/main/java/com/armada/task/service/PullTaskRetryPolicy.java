package com.armada.task.service;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.task.model.dto.PullTaskHistoricalRetryNormalization;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;

/** 普通拉群逐号码自动重试预算与波次冷却，不把未知结果计为明确失败。 */
public final class PullTaskRetryPolicy {

    /** 每个群内每个参与者最多自动尝试四次，包含首轮。 */
    public static final int MAX_ATTEMPTS = 4;

    /** 名单查询明确未在群内，才允许不确定的原调用进入有界重试。 */
    public static final String CONFIRMED_ABSENCE_REASON = "ROSTER_NOT_PRESENT";

    private static final long INITIAL_RETRY_DELAY_MS = 60_000L;
    private static final int MAX_BACKOFF_EXPONENT = 2;

    private PullTaskRetryPolicy() {
    }

    /** @return 当前单调 attempt 序号是否仍有下一次自动尝试预算 */
    public static boolean canRetry(int attemptNo) {
        return attemptNo > 0 && attemptNo < MAX_ATTEMPTS;
    }

    /** @return 已结算波次后至少等待的毫秒数，依次为 60、120、240 秒并封顶 */
    public static long retryDelayMs(int settledWaveNo) {
        int exponent = Math.min(MAX_BACKOFF_EXPONENT, Math.max(0, settledWaveNo - 1));
        return INITIAL_RETRY_DELAY_MS << exponent;
    }
    /** 为历史待执行投影恢复提供与新重试筛选一致的类型、终态和预算条件。 */
    public static PullTaskHistoricalRetryNormalization historicalNormalization(
            long executionId, PullTaskParticipantType type,
            PullTaskBatchParticipantProtocolOutcome outcome, long now) {
        boolean material = type == PullTaskParticipantType.MATERIAL;
        int pending = material ? PullTaskMaterialPullStatus.UNCONSUMED.code()
                : PullTaskGroupAccountMembershipStatus.NOT_JOINED.code();
        int unknown = material ? PullTaskMaterialPullStatus.UNKNOWN.code()
                : PullTaskGroupAccountMembershipStatus.UNKNOWN.code();
        int failed = material ? PullTaskMaterialPullStatus.FAILED.code()
                : PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code();
        return new PullTaskHistoricalRetryNormalization(
                new PullTaskHistoricalRetryNormalization.Scope(executionId, type.code()),
                new PullTaskHistoricalRetryNormalization.Target(pending,
                        outcome == PullTaskBatchParticipantProtocolOutcome.UNKNOWN ? unknown : failed,
                        outcome.name()),
                new PullTaskHistoricalRetryNormalization.AttemptState(
                        PullTaskParticipantAttemptStatus.CLOSED.code(),
                        PullTaskParticipantAttemptStatus.RELEASED.code(),
                        PullTaskBatchParticipantProtocolOutcome.UNKNOWN.name(),
                        PullTaskBatchParticipantProtocolOutcome.FAILED.name()),
                new PullTaskHistoricalRetryNormalization.RetryRule(
                        PullTaskRetryPolicy.MAX_ATTEMPTS, PullTaskParticipantExecutionState.NOT_STARTED.name(),
                        PullTaskParticipantExecutionState.UNCERTAIN.name(),
                        PullTaskRetryPolicy.CONFIRMED_ABSENCE_REASON, ProtocolErrorCode.TIMEOUT.name()), now);
    }

}
