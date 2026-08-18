package com.armada.group.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.group.mapper.WhatsappGroupMemberCacheMapper;
import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.enums.WhatsappGroupMemberStateSource;
import com.armada.group.model.vo.WhatsappGroupMemberStateVO;
import com.armada.group.service.GroupParticipantObservationService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MySQL 群成员事实收敛和角色投影实现。 */
@Service
public class GroupParticipantObservationServiceImpl implements GroupParticipantObservationService {

    private final WhatsappGroupMemberCacheMapper memberStateMapper;
    private final AccountMapper accountMapper;
    private final AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;

    public GroupParticipantObservationServiceImpl(
            WhatsappGroupMemberCacheMapper memberStateMapper,
            AccountMapper accountMapper,
            AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence) {
        this.memberStateMapper = memberStateMapper;
        this.accountMapper = accountMapper;
        this.currentSnapshotPersistence = currentSnapshotPersistence;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void apply(List<GroupParticipantObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            return;
        }
        Map<TenantGroupKey, List<NormalizedObservation>> grouped = observations.stream()
                .map(GroupParticipantObservationServiceImpl::normalize)
                .collect(Collectors.groupingBy(
                        value -> new TenantGroupKey(value.tenantId(), value.groupJid()),
                        LinkedHashMap::new,
                        Collectors.toList()));
        Long previousTenant = TenantContext.get();
        try {
            for (Map.Entry<TenantGroupKey, List<NormalizedObservation>> entry : grouped.entrySet()) {
                TenantContext.set(entry.getKey().tenantId());
                applyGroup(entry.getKey(), entry.getValue());
            }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private void applyGroup(TenantGroupKey key, List<NormalizedObservation> observations) {
        currentSnapshotPersistence.applyParticipantObservations(observations.stream()
                .map(GroupParticipantObservationServiceImpl::currentObservation)
                .toList());
        reconcileControlledMemberships(key.tenantId(), key.groupJid(), observations.stream()
                .map(NormalizedObservation::participantJid)
                .toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reconcileControlledMemberships(
            Long tenantId,
            String groupJid,
            List<String> participantJids) {
        if (tenantId == null || participantJids == null || participantJids.isEmpty()) {
            return;
        }
        List<String> candidates = participantJids.stream()
                .filter(GroupParticipantObservationServiceImpl::hasText)
                .map(jid -> jid.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        String normalizedGroupJid = canonicalGroupJid(groupJid);
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            List<WhatsappGroupMemberStateVO> winners = memberStateMapper.selectStatesByParticipantJids(
                    tenantId, normalizedGroupJid, candidates);
            if (winners == null || winners.isEmpty()) {
                return;
            }
            reconcileControlledMemberships(normalizedGroupJid, winners);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private void reconcileControlledMemberships(
            String groupJid,
            List<WhatsappGroupMemberStateVO> winners) {
        Map<String, WhatsappGroupMemberStateVO> winnerByPhone = winners.stream()
                .filter(winner -> normalizedPhone(winner.phone()) != null)
                .collect(Collectors.toMap(
                        winner -> normalizedPhone(winner.phone()),
                        Function.identity(),
                        GroupParticipantObservationServiceImpl::laterState,
                        LinkedHashMap::new));
        if (winnerByPhone.isEmpty()) {
            return;
        }
        List<String> phones = winnerByPhone.keySet().stream().sorted().toList();
        List<Account> accounts = accountMapper.selectActiveByWsPhones(phones);
        if (accounts == null || accounts.isEmpty()) {
            return;
        }
        for (Account account : accounts) {
            String phone = normalizedPhone(account.getWsPhone());
            WhatsappGroupMemberStateVO winner = winnerByPhone.get(phone);
            if (winner == null || account.getId() == null) {
                continue;
            }
            currentSnapshotPersistence.applyControlledParticipantObservation(
                    account.getId(), groupJid, Boolean.TRUE.equals(winner.inGroup()),
                    Boolean.TRUE.equals(winner.admin()), winner.stateUpdatedAt(),
                    winner.sourceEventId(), membershipStatusSource(winner));
        }
    }

    private static String membershipStatusSource(WhatsappGroupMemberStateVO winner) {
        return switch (winner.stateSource()) {
            case "ROLE_EVENT" -> Boolean.TRUE.equals(winner.admin())
                    ? "WGP2_PROMOTE" : "WGP2_DEMOTE";
            case "MEMBER_QUERY" -> "GROUP_MEMBER_QUERY";
            case "ADD_EVENT" -> "WGP2_ADD";
            case "LEAVE_EVENT" -> "WGP2_LEAVE";
            case "REMOVE_EVENT", "UNKNOWN_EXIT_EVENT" -> "WGP2_REMOVE";
            default -> "GROUP_SNAPSHOT";
        };
    }

    private static WhatsappGroupMemberStateVO laterState(
            WhatsappGroupMemberStateVO left,
            WhatsappGroupMemberStateVO right) {
        int byTime = Long.compare(valueOrZero(left.stateUpdatedAt()), valueOrZero(right.stateUpdatedAt()));
        if (byTime != 0) {
            return byTime >= 0 ? left : right;
        }
        return sourcePriority(left.stateSource()) >= sourcePriority(right.stateSource()) ? left : right;
    }

    private static int sourcePriority(String source) {
        return switch (source == null ? "" : source) {
            case "UNKNOWN_EXIT_EVENT", "LEAVE_EVENT", "REMOVE_EVENT" -> 5;
            case "ROLE_EVENT" -> 4;
            case "ADD_EVENT" -> 3;
            case "MEMBER_QUERY", "FULL_SNAPSHOT", "SNAPSHOT_ABSENT" -> 2;
            default -> 0;
        };
    }

    private static GroupParticipantObservation currentObservation(NormalizedObservation value) {
        return new GroupParticipantObservation(
                value.tenantId(), value.observerAccountId(), value.groupJid(),
                value.participantJid(), value.participantJid(), value.phone(),
                value.inGroup(), value.admin(),
                WhatsappGroupMemberStateSource.valueOf(value.stateSource()),
                value.observedAt(), value.sourceEventId());
    }

    private static NormalizedObservation normalize(GroupParticipantObservation value) {
        if (value == null || value.tenantId() == null || value.tenantId() <= 0) {
            throw validation("群成员观察缺少 tenantId");
        }
        if (value.admin() && !value.inGroup()) {
            throw validation("群成员观察管理员必须在群");
        }
        if (value.source() == null || value.observedAt() <= 0 || !hasText(value.sourceEventId())) {
            throw validation("群成员观察来源或事实时间非法");
        }
        String groupJid = canonicalGroupJid(value.groupJid());
        String phone = normalizedPhone(value.phone());
        if (phone == null) {
            phone = phoneFromJid(value.participantJid());
        }
        if (phone == null) {
            phone = phoneFromJid(value.targetJid());
        }
        String participantJid = canonicalParticipantJid(
                hasText(value.participantJid()) ? value.participantJid() : value.targetJid(), phone);
        return new NormalizedObservation(
                value.tenantId(), value.observerAccountId(), groupJid, participantJid, phone,
                value.inGroup(), value.admin(), value.source().name(), value.observedAt(),
                value.sourceEventId().trim());
    }

    private static String canonicalGroupJid(String value) {
        if (!hasText(value)) {
            throw validation("群成员观察缺少 groupJid");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.endsWith("@g.us")) {
            throw validation("群成员观察 groupJid 非法");
        }
        return normalized;
    }

    private static String canonicalParticipantJid(String value, String phone) {
        if (hasText(value)) {
            String jid = value.trim().toLowerCase(Locale.ROOT);
            int at = jid.indexOf('@');
            int device = jid.indexOf(':');
            if (device >= 0 && at > device) {
                jid = jid.substring(0, device) + jid.substring(at);
            }
            if (jid.endsWith("@lid") || jid.endsWith("@s.whatsapp.net")) {
                return jid;
            }
        }
        if (phone != null) {
            return phone + "@s.whatsapp.net";
        }
        throw validation("群成员观察缺少可用成员身份");
    }

    private static String phoneFromJid(String value) {
        if (!hasText(value)) {
            return null;
        }
        String jid = value.trim().toLowerCase(Locale.ROOT);
        int at = jid.indexOf('@');
        if (at <= 0 || !jid.endsWith("@s.whatsapp.net")) {
            return null;
        }
        String user = jid.substring(0, at);
        int device = user.indexOf(':');
        if (device >= 0) {
            user = user.substring(0, device);
        }
        return user.length() >= 5 && user.length() <= 20
                && user.chars().allMatch(Character::isDigit) ? user : null;
    }

    private static String normalizedPhone(String value) {
        if (!hasText(value)) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.length() >= 5 && digits.length() <= 20 ? digits : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private record TenantGroupKey(Long tenantId, String groupJid) {
    }

    private record NormalizedObservation(
            Long tenantId,
            Long observerAccountId,
            String groupJid,
            String participantJid,
            String phone,
            boolean inGroup,
            boolean admin,
            String stateSource,
            long observedAt,
            String sourceEventId) {
    }
}
