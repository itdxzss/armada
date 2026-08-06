package com.armada.group.normalcreation.model.dto;

/** Kafka 中只携带任务定位信息的轻量阶段命令。 */
public record NormalGroupCreationCommand(
        int schemaVersion,
        String eventId,
        Long tenantId,
        Long taskId,
        Long itemId,
        String action,
        long occurredAt) {
}
