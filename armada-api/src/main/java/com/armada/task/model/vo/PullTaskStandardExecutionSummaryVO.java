package com.armada.task.model.vo;

/**
 * 普通群链接任务中的单条执行行摘要。
 *
 * @param executionId     执行行 ID
 * @param seq             任务内顺序
 * @param normalizedLink  归一化群链接
 * @param groupJid        WhatsApp 群 JID
 * @param executionStatus 执行状态码
 * @param stage           当前业务阶段码
 * @param manualPaused    是否被人工暂停；与资源等待独立
 * @param waitResourceType 当前等待的资源类型；非资源等待时为空
 * @param validMemberCount 有效料子人数
 * @param reasonCode      当前原因码
 * @param reasonMessage   当前脱敏原因
 * @param lastBusinessExecutedAt 最近业务执行时间
 */
public record PullTaskStandardExecutionSummaryVO(
        long executionId,
        int seq,
        String normalizedLink,
        String groupJid,
        int executionStatus,
        int stage,
        boolean manualPaused,
        Integer waitResourceType,
        int validMemberCount,
        String reasonCode,
        String reasonMessage,
        Long lastBusinessExecutedAt,
        PullTaskStandardMaterialSummaryVO materialSummary,
        PullTaskStandardResourceCountVO managers,
        PullTaskStandardResourceCountVO pullers,
        PullTaskStandardResourceCountVO stations) {

    /** M1 兼容构造；没有聚合事实时不填假零值。 */
    public PullTaskStandardExecutionSummaryVO(
            long executionId,
            int seq,
            String normalizedLink,
            String groupJid,
            int executionStatus,
            int stage,
            boolean manualPaused,
            int validMemberCount,
            String reasonCode,
            String reasonMessage,
            Long lastBusinessExecutedAt) {
        this(executionId, seq, normalizedLink, groupJid, executionStatus, stage,
                manualPaused, null, validMemberCount, reasonCode, reasonMessage,
                lastBusinessExecutedAt, null, null, null, null);
    }
}
