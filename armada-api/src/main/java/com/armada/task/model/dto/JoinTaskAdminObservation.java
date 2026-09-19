package com.armada.task.model.dto;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;

/** 事务外成员查询结论；未知与明确未在群必须区分。 */
public record JoinTaskAdminObservation(Kind kind, ProtocolAccountRef actor, String reason) {
    /** 查询结果类别。 */
    public enum Kind {
        /** 已确认管理员。 */ SUCCESS,
        /** 目标为普通成员且原有管理员可执行。 */ READY,
        /** 资源不足或事实未知。 */ WAIT,
        /** 明确不可执行。 */ FAILED
    }
}
