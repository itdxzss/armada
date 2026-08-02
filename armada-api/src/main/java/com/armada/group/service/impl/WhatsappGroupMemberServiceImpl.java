package com.armada.group.service.impl;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.WhatsappGroupMemberMapper;
import com.armada.group.model.dto.WhatsappGroupParticipant;
import com.armada.group.model.dto.WhatsappGroupParticipantsChangedEvent;
import com.armada.group.model.entity.WhatsappGroupMember;
import com.armada.group.model.vo.AccountGroupBaselineRow;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.WhatsappGroupMemberService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以 WhatsApp 成员身份为主键维护群成员最新状态。 */
@Service
public class WhatsappGroupMemberServiceImpl implements WhatsappGroupMemberService {

    private static final Logger log = LoggerFactory.getLogger(WhatsappGroupMemberServiceImpl.class);
    private static final int IN_GROUP = 1;
    private static final int KICKED_OUT = 3;
    private static final int LEFT = 4;

    private final WhatsappGroupMemberMapper memberMapper;
    private final AccountGroupMembershipMapper accountMembershipMapper;
    private final GroupLinkRegistryService groupLinkRegistryService;

    public WhatsappGroupMemberServiceImpl(
            WhatsappGroupMemberMapper memberMapper,
            AccountGroupMembershipMapper accountMembershipMapper,
            GroupLinkRegistryService groupLinkRegistryService) {
        this.memberMapper = memberMapper;
        this.accountMembershipMapper = accountMembershipMapper;
        this.groupLinkRegistryService = groupLinkRegistryService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceCurrentMembers(
            Long observerAccountId,
            Long groupLinkId,
            String groupJid,
            List<WhatsappGroupParticipant> participants,
            Integer declaredMemberCount,
            boolean participantsComplete,
            Boolean announceOnly,
            Boolean observerAdmin,
            long snapshotAt,
            String sourceEventId) {
        String normalizedGroupJid = requireGroupJid(groupJid);
        if (observerAccountId == null || groupLinkId == null || snapshotAt <= 0 || participants == null) {
            throw validation("WhatsApp 群成员快照参数不完整");
        }
        sourceEventId = requireSourceEventId(sourceEventId);
        Map<String, WhatsappGroupParticipant> normalized = normalizeParticipants(participants);
        memberMapper.lockGroupLink(groupLinkId);
        long now = System.currentTimeMillis();
        for (WhatsappGroupParticipant participant : normalized.values()) {
            WhatsappGroupMember row = row(
                    observerAccountId,
                    groupLinkId,
                    normalizedGroupJid,
                    participant,
                    IN_GROUP,
                    "MEMBER_SNAPSHOT",
                    sourceEventId,
                    snapshotAt,
                    null,
                    null,
                    now);
            if (memberMapper.insertMemberFact(row) == 0) {
                log.warn("WhatsApp 群成员快照事实重复，忽略当前态重放 eventId={} accountId={} memberJid={}",
                        sourceEventId, observerAccountId, participant.memberJid());
                continue;
            }
            memberMapper.upsertMember(row);
        }
        boolean complete = participantsComplete
                && declaredMemberCount != null
                && declaredMemberCount >= 0
                && declaredMemberCount == normalized.size();
        int missingCount = 0;
        if (complete) {
            List<String> memberJids = new ArrayList<>(normalized.keySet());
            List<WhatsappGroupMember> missing = memberMapper.selectMissingCurrentMembers(
                    normalizedGroupJid, memberJids, snapshotAt, sourceEventId);
            if (missing != null && !missing.isEmpty()) {
                List<Long> missingIds = new ArrayList<>(missing.size());
                for (WhatsappGroupMember member : missing) {
                    member.setMembershipStatus(5);
                    member.setStatusSource("MEMBER_SNAPSHOT");
                    member.setStatusSourceEventId(sourceEventId);
                    member.setStatusUpdatedAt(snapshotAt);
                    member.setObserverAccountId(observerAccountId);
                    member.setCreatedAt(now);
                    memberMapper.insertMemberFact(member);
                    missingIds.add(member.getId());
                }
                memberMapper.markMissingMembers(
                        missingIds, snapshotAt, now, observerAccountId, sourceEventId);
                missingCount = missingIds.size();
            }
            memberMapper.insertCompleteSnapshot(
                    groupLinkId,
                    normalizedGroupJid,
                    normalized.size(),
                    snapshotAt,
                    sourceEventId,
                    observerAccountId,
                    announceOnly,
                    observerAdmin,
                    now);
        }
        log.info("WhatsApp 群成员快照已刷新 accountId={} groupLinkId={} memberCount={} "
                        + "declaredMemberCount={} complete={} missingCount={}",
                observerAccountId, groupLinkId, normalized.size(), declaredMemberCount, complete, missingCount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyParticipantsChanged(WhatsappGroupParticipantsChangedEvent event) {
        validateEvent(event);
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            AccountGroupBaselineRow account = accountMembershipMapper.selectAccountBaselineRow(
                    event.observerAccountId());
            if (account == null || !Objects.equals(
                    normalizeText(account.getProtocolAccountId()),
                    normalizeText(event.protocolAccountId()))) {
                log.warn("WhatsApp 群成员事件账号绑定不存在或已过期 eventId={} accountId={}",
                        event.eventId(), event.observerAccountId());
                return;
            }
            String groupJid = requireGroupJid(event.groupJid());
            Long groupLinkId = groupLinkRegistryService.registerAccountObservedGroup(
                    groupJid,
                    null,
                    ProtocolBackend.fromProtocolId(account.getProtocolId()),
                    System.currentTimeMillis());
            Transition transition = transition(event.action());
            Map<String, WhatsappGroupParticipant> participants = normalizeParticipants(event.participants());
            if (participants.isEmpty()) {
                throw validation("WhatsApp 群成员事件缺少有效 participants");
            }
            memberMapper.lockGroupLink(groupLinkId);
            long now = System.currentTimeMillis();
            String sourceEventId = normalizeText(event.sourceEventId());
            sourceEventId = requireSourceEventId(sourceEventId == null ? event.eventId() : sourceEventId);
            for (WhatsappGroupParticipant participant : participants.values()) {
                WhatsappGroupMember row = row(
                        event.observerAccountId(),
                        groupLinkId,
                        groupJid,
                        participant,
                        transition.status(),
                        transition.source(),
                        sourceEventId,
                        event.occurredAt(),
                        transition.status() == IN_GROUP ? event.occurredAt() : null,
                        transition.status() == IN_GROUP ? null : event.occurredAt(),
                        now);
                if (memberMapper.insertMemberFact(row) == 0) {
                    log.warn("WhatsApp 群成员事件重复，忽略当前态重放 eventId={} accountId={}",
                            event.eventId(), event.observerAccountId());
                    continue;
                }
                memberMapper.upsertMember(row);
            }
            log.info("WhatsApp 群成员事件已应用 eventId={} accountId={} action={} participantCount={} source={}",
                    event.eventId(), event.observerAccountId(), event.action(), participants.size(), event.source());
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private static WhatsappGroupMember row(
            Long observerAccountId,
            Long groupLinkId,
            String groupJid,
            WhatsappGroupParticipant participant,
            int membershipStatus,
            String statusSource,
            String statusSourceEventId,
            long statusUpdatedAt,
            Long joinedAt,
            Long exitedAt,
            long now) {
        WhatsappGroupMember row = new WhatsappGroupMember();
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw validation("WhatsApp 群成员写入缺少租户上下文");
        }
        row.setTenantId(tenantId);
        row.setGroupLinkId(groupLinkId);
        row.setGroupJid(groupJid);
        row.setMemberJid(participant.memberJid());
        row.setParticipantJid(participant.participantJid());
        row.setPhone(participant.phone());
        row.setRole(participant.role());
        row.setAdmin(participant.admin());
        row.setOwner(participant.owner());
        row.setMembershipStatus(membershipStatus);
        row.setStatusSource(statusSource);
        row.setStatusSourceEventId(statusSourceEventId);
        row.setStatusUpdatedAt(statusUpdatedAt);
        row.setJoinedAt(joinedAt);
        row.setLastExitType(exitedAt == null ? null : membershipStatus);
        row.setLastExitedAt(exitedAt);
        row.setFirstSeenAt(statusUpdatedAt);
        row.setLastSeenAt(membershipStatus == IN_GROUP ? statusUpdatedAt : null);
        row.setObserverAccountId(observerAccountId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private static Map<String, WhatsappGroupParticipant> normalizeParticipants(
            List<WhatsappGroupParticipant> participants) {
        Map<String, WhatsappGroupParticipant> normalized = new TreeMap<>();
        if (participants == null) {
            return normalized;
        }
        for (WhatsappGroupParticipant participant : participants) {
            if (participant == null) {
                continue;
            }
            String phone = normalizePhone(participant.phone());
            String participantJid = normalizeText(participant.participantJid());
            String memberJid = normalizeText(participant.memberJid());
            if (memberJid == null) {
                memberJid = participantJid;
            }
            if (memberJid == null && phone != null) {
                memberJid = phone + "@s.whatsapp.net";
            }
            if (memberJid == null || memberJid.length() > 128) {
                continue;
            }
            normalized.putIfAbsent(memberJid, new WhatsappGroupParticipant(
                    memberJid,
                    clamp(participantJid, 128),
                    clamp(phone, 32),
                    clamp(normalizeText(participant.role()), 32),
                    participant.admin(),
                    participant.owner()));
        }
        return normalized;
    }

    private static void validateEvent(WhatsappGroupParticipantsChangedEvent event) {
        if (event == null || event.tenantId() == null || event.observerAccountId() == null) {
            throw validation("WhatsApp 群成员事件缺少租户或观察账号");
        }
        if (normalizeText(event.eventId()) == null || normalizeText(event.protocolAccountId()) == null) {
            throw validation("WhatsApp 群成员事件缺少事件或协议账号标识");
        }
        if (event.occurredAt() == null || event.occurredAt() <= 0) {
            throw validation("WhatsApp 群成员事件缺少事实时间");
        }
        requireGroupJid(event.groupJid());
        transition(event.action());
    }

    private static Transition transition(String action) {
        String normalized = normalizeText(action);
        normalized = normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "add" -> new Transition(IN_GROUP, "PARTICIPANT_ADD");
            case "remove" -> new Transition(KICKED_OUT, "PARTICIPANT_REMOVE");
            case "leave" -> new Transition(LEFT, "PARTICIPANT_LEAVE");
            default -> throw validation("WhatsApp 群成员事件 action 非法");
        };
    }

    private static String requireGroupJid(String value) {
        String normalized = normalizeText(value);
        if (normalized == null || !normalized.endsWith("@g.us") || normalized.length() > 128) {
            throw validation("WhatsApp 群成员事件 groupJid 非法");
        }
        return normalized;
    }

    private static String requireSourceEventId(String value) {
        String normalized = normalizeText(value);
        if (normalized == null || normalized.length() > 191) {
            throw validation("WhatsApp 群成员事件 sourceEventId 非法");
        }
        return normalized;
    }

    private static String normalizePhone(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return null;
        }
        int at = normalized.indexOf('@');
        if (at >= 0) {
            normalized = normalized.substring(0, at);
        }
        int device = normalized.indexOf(':');
        if (device >= 0) {
            normalized = normalized.substring(0, device);
        }
        if (normalized.startsWith("+")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() || !normalized.chars().allMatch(Character::isDigit)
                ? null : normalized;
    }

    private static String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String clamp(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private record Transition(int status, String source) {
    }
}
