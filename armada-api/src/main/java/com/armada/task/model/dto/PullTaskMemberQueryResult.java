package com.armada.task.model.dto;

import java.util.List;

/** 成员查询读取结果；PENDING 时调用方释放租约，AVAILABLE 时消费冻结事实。 */
public record PullTaskMemberQueryResult(
        State state,
        Long queryId,
        Long nextCheckAt,
        List<PullTaskMemberFact> members,
        String errorCode,
        String errorMessage
) {
    public PullTaskMemberQueryResult {
        members = members == null ? List.of() : List.copyOf(members);
    }

    public enum State {
        PENDING,
        AVAILABLE,
        FAILED
    }

    public static PullTaskMemberQueryResult pending(Long queryId, Long nextCheckAt) {
        return new PullTaskMemberQueryResult(
                State.PENDING, queryId, nextCheckAt, List.of(), null, null);
    }

    public static PullTaskMemberQueryResult available(
            Long queryId,
            List<PullTaskMemberFact> members) {
        return new PullTaskMemberQueryResult(
                State.AVAILABLE, queryId, null, members, null, null);
    }

    public static PullTaskMemberQueryResult failed(
            Long queryId,
            String errorCode,
            String errorMessage) {
        return new PullTaskMemberQueryResult(
                State.FAILED, queryId, null, List.of(), errorCode, errorMessage);
    }
}
