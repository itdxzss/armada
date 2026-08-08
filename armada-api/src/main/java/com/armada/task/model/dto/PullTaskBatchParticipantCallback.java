package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import java.util.Objects;

/**
 * 普通链接拉群批量加成员命令的单成员协议回调。
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 执行行 ID
 * @param pullCallId 批量拉人调用 ID
 * @param accountId 执行本次调用的拉手账号 ID
 * @param protocolAccountId 协议账号 ID
 * @param commandId 协议命令 ID
 * @param attemptNo 协议尝试次数
 * @param targetJid 结果对应的成员 JID
 * @param outcome 单成员结果
 * @param executionState 号码相对协议副作用调用的执行阶段
 * @param reasonCode 原因码
 * @param reasonMessage 已脱敏原因描述
 * @param retryable 是否可重试
 * @param occurredAt 协议结果时间(epoch 毫秒)
 */
public record PullTaskBatchParticipantCallback(
        long tenantId,
        long pullTaskId,
        long groupExecutionId,
        long pullCallId,
        long accountId,
        String protocolAccountId,
        String commandId,
        int attemptNo,
        String targetJid,
        PullTaskBatchParticipantProtocolOutcome outcome,
        PullTaskParticipantExecutionState executionState,
        String reasonCode,
        String reasonMessage,
        boolean retryable,
        long occurredAt) {

    /** 校验协议回调的定位字段。 */
    public PullTaskBatchParticipantCallback {
        if (tenantId <= 0 || pullTaskId <= 0 || groupExecutionId <= 0
                || pullCallId <= 0 || accountId <= 0 || attemptNo <= 0) {
            throw new IllegalArgumentException("批量拉人回调关联 ID 非法");
        }
        if (protocolAccountId == null || protocolAccountId.isBlank()
                || commandId == null || commandId.isBlank()
                || targetJid == null || targetJid.isBlank()) {
            throw new IllegalArgumentException("批量拉人回调定位字段不能为空");
        }
        protocolAccountId = protocolAccountId.trim();
        commandId = commandId.trim();
        targetJid = targetJid.trim();
        outcome = Objects.requireNonNull(outcome, "outcome 不能为空");
        executionState = Objects.requireNonNull(executionState, "executionState 不能为空");
        boolean explicit = outcome == PullTaskBatchParticipantProtocolOutcome.SUCCESS
                || outcome == PullTaskBatchParticipantProtocolOutcome.FAILED;
        boolean valid = explicit
                ? executionState == PullTaskParticipantExecutionState.STARTED
                : executionState == PullTaskParticipantExecutionState.NOT_STARTED
                        || executionState == PullTaskParticipantExecutionState.UNCERTAIN;
        if (!valid) {
            throw new IllegalArgumentException("批量拉人回调结果与执行阶段不匹配");
        }
    }

    /**
     * 兼容既有任务域单测和内部调用；协议入口必须显式传入执行阶段。
     */
    public PullTaskBatchParticipantCallback(
            long tenantId,
            long pullTaskId,
            long groupExecutionId,
            long pullCallId,
            long accountId,
            String protocolAccountId,
            String commandId,
            int attemptNo,
            String targetJid,
            PullTaskBatchParticipantProtocolOutcome outcome,
            String reasonCode,
            String reasonMessage,
            boolean retryable,
            long occurredAt) {
        this(tenantId, pullTaskId, groupExecutionId, pullCallId, accountId,
                protocolAccountId, commandId, attemptNo, targetJid, outcome,
                inferredExecutionState(outcome), reasonCode, reasonMessage, retryable, occurredAt);
    }

    private static PullTaskParticipantExecutionState inferredExecutionState(
            PullTaskBatchParticipantProtocolOutcome outcome) {
        return outcome == PullTaskBatchParticipantProtocolOutcome.UNKNOWN
                ? PullTaskParticipantExecutionState.UNCERTAIN
                : PullTaskParticipantExecutionState.STARTED;
    }
}
