package com.armada.group.model.dto;

import com.armada.group.model.enums.GroupParticipantObservationSource;

/** 协议角色事件或定点查询观察到的单个群成员事实。 */
public record GroupParticipantObservation(
        Long tenantId,
        Long observerAccountId,
        String groupJid,
        String targetJid,
        String participantJid,
        String phone,
        boolean inGroup,
        boolean admin,
        GroupParticipantObservationSource source,
        long observedAt,
        String sourceEventId) {
}
