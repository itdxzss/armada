package com.armada.task.model.vo;

/** 单群内联系人、邀请或踩链接动作的可追溯执行记录。 */
public record PullTaskStandardActionVO(
        long actionId,
        int actionType,
        long actorRoleRowId,
        long targetRoleRowId,
        int actionStatus,
        String reasonCode,
        String reasonMessage,
        Long submittedAt,
        Long resultAt) {
}
