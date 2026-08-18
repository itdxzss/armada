package com.armada.platform.kafka.consumer.group;

import java.util.Map;

/** 单群快照命令结算事件；事实字段由 profile/invite 事件单独承载。 */
public record ProtocolGroupSnapshotResultReportedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String protocolBackend,
        Long groupLinkId,
        String groupJid,
        String taskType,
        Long taskId,
        int attemptNo,
        String commandId,
        Map<String, ScopeResult> scopes,
        String workerId) {

    /** 单个请求 scope 的明确结算。 */
    public record ScopeResult(String outcome, long completedAt, String errorCode) {
    }
}
