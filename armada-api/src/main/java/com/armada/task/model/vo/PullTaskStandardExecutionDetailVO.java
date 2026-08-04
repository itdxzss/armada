package com.armada.task.model.vo;

import java.util.List;

/** 普通群链接单执行行 M1 最小详情。 */
public record PullTaskStandardExecutionDetailVO(
        PullTaskStandardExecutionSummaryVO execution,
        List<PullTaskStandardRoleVO> roles,
        List<PullTaskStandardCallVO> calls,
        List<PullTaskStandardActionVO> actions) {

    /** 冻结角色和调用事实列表。 */
    public PullTaskStandardExecutionDetailVO {
        roles = List.copyOf(roles);
        calls = List.copyOf(calls);
        actions = List.copyOf(actions);
    }

    /** M1 兼容构造。 */
    public PullTaskStandardExecutionDetailVO(
            PullTaskStandardExecutionSummaryVO execution,
            List<PullTaskStandardRoleVO> roles,
            List<PullTaskStandardCallVO> calls) {
        this(execution, roles, calls, List.of());
    }
}
