package com.armada.task.service;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.task.model.PullTaskPullerSlotPolicy;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.dto.PullTaskHistoricalRetryNormalization;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import java.util.List;

/** 普通拉群逐号码自动重试预算与波次冷却，不把未知结果计为明确失败。 */
public final class PullTaskRetryPolicy {

    /** 每个群内每个参与者最多业务尝试四次，包含首轮；未执行的临时离线不计入。 */
    public static final int MAX_ATTEMPTS = 4;

    /** 名单查询明确未在群内时允许不确定的原调用进入有界重试。 */
    public static final String CONFIRMED_ABSENCE_REASON = "ROSTER_NOT_PRESENT";

    /** 原调用明确返回拉手受限时允许换健康拉手补拉，保留 UNKNOWN 事实。 */
    public static final List<String> RETRYABLE_ACCOUNT_RISK_REASONS = List.of(
            ProtocolErrorCode.RATE_LIMITED.name(), ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED.name());

    private static final long INITIAL_RETRY_DELAY_MS = 60_000L;
    private static final int MAX_BACKOFF_EXPONENT = 2;

    private PullTaskRetryPolicy() {
    }

    /** @return 扣除明确未执行离线后的业务尝试数是否仍有下一次预算 */
    public static boolean canRetry(int businessAttemptCount) {
        return businessAttemptCount >= 0 && businessAttemptCount < MAX_ATTEMPTS;
    }

    /** 只有协议明确证明未开始的临时离线才不消耗业务重试，不能推断 UNCERTAIN 没有副作用。 */
    public static boolean isUnstartedOffline(PullTaskBatchParticipantCallback callback) {
        return callback.outcome() == PullTaskBatchParticipantProtocolOutcome.UNKNOWN
                && callback.executionState() == PullTaskParticipantExecutionState.NOT_STARTED
                && PullTaskPullerSlotPolicy.isTemporaryOfflineReason(callback.reasonCode());
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
                        PullTaskRetryPolicy.CONFIRMED_ABSENCE_REASON, ProtocolErrorCode.TIMEOUT.name(),
                        RETRYABLE_ACCOUNT_RISK_REASONS), now);
    }

}
