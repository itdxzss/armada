package com.armada.group.service.impl;

import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.observability.GroupMetadataSyncMetrics;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupMetadataSnapshotPersistence;
import com.armada.group.service.GroupMetadataSnapshotService;
import com.armada.group.service.GroupMetadataSyncProtocolPorts;
import com.armada.platform.country.model.vo.CountryReferenceVO;
import com.armada.platform.country.service.CountryService;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 读取、校验并持久化单群完整 metadata 和成员快照。 */
@Service
public class GroupMetadataSnapshotServiceImpl implements GroupMetadataSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(GroupMetadataSnapshotServiceImpl.class);

    private final GroupMetadataSyncProtocolPorts ports;
    private final GroupMetadataSnapshotPersistence persistence;
    private final GroupExecutionAccountSelector executionAccountSelector;
    private final CountryService countryService;
    private final GroupMetadataSyncMetrics metrics;

    /** 创建群详情快照执行器。 */
    public GroupMetadataSnapshotServiceImpl(
            GroupMetadataSyncProtocolPorts ports,
            GroupMetadataSnapshotPersistence persistence,
            GroupExecutionAccountSelector executionAccountSelector,
            CountryService countryService,
            GroupMetadataSyncMetrics metrics) {
        this.ports = ports;
        this.persistence = persistence;
        this.executionAccountSelector = executionAccountSelector;
        this.countryService = countryService;
        this.metrics = metrics;
    }

    @Override
    public void execute(GroupMetadataSyncTask task, GroupExecutionAccount account) {
        String groupJid = requireGroupJid(task.getGroupJid());
        long observedAt = System.currentTimeMillis();
        GroupMetadataResult metadata = ports.metadata().getMetadata(account.protocolRef(), groupJid);
        if (!metadata.participantsComplete()) {
            throw new IllegalStateException("群 metadata 成员快照不完整");
        }
        long completedAt = System.currentTimeMillis();
        List<WhatsappGroupMemberSnapshot> members = normalizeMembers(
                task.getGroupLinkId(), groupJid, metadata, completedAt);
        List<String> freshAdminPhones = members.stream()
                .filter(row -> Boolean.TRUE.equals(row.getIsAdmin()))
                .map(WhatsappGroupMemberSnapshot::getPhone)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        int completedAttempts = Math.max(
                0, (task.getAttemptCount() == null ? 0 : task.getAttemptCount()) - 1);
        GroupExecutionAccount inviteAccount = executionAccountSelector.findAdminByPhones(
                        task.getGroupLinkId(), freshAdminPhones, completedAttempts)
                .orElseGet(() -> freshAdminPhones.isEmpty() && account.groupAdmin() ? account : null);
        String inviteCode = inviteAccount == null ? null : safeInviteCode(inviteAccount, groupJid);
        String ownerPhone = confirmedOwnerPhone(metadata, members);
        CountryReferenceVO country = resolveCountry(ownerPhone);
        GroupLinkPreview preview = preview(
                task, metadata, inviteCode, ownerPhone, country, observedAt, completedAt);
        if (persistence.persist(preview, members)) {
            metrics.recordSnapshotMembers(members.size());
        }
        if (Boolean.TRUE.equals(task.getInviteRequired()) && inviteCode == null) {
            throw new IllegalStateException("自建群邀请码暂未取得");
        }
    }

    private String safeInviteCode(GroupExecutionAccount account, String groupJid) {
        try {
            GroupInviteResult invite = ports.invite().getInvite(account.protocolRef(), groupJid);
            return invite == null ? null : blankToNull(invite.inviteCode());
        } catch (RuntimeException exception) {
            log.warn("群邀请码读取失败，保留旧邀请码 groupJid={} errorType={}",
                    groupJid, exception.getClass().getSimpleName());
            return null;
        }
    }

    private CountryReferenceVO resolveCountry(String ownerPhone) {
        if (ownerPhone == null) {
            return null;
        }
        return countryService.resolveActiveCountriesByPhoneNumbers(List.of(ownerPhone))
                .get(ownerPhone);
    }

    private static GroupLinkPreview preview(
            GroupMetadataSyncTask task,
            GroupMetadataResult metadata,
            String inviteCode,
            String ownerPhone,
            CountryReferenceVO country,
            long observedAt,
            long completedAt) {
        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(task.getGroupLinkId());
        row.setGroupJid(task.getGroupJid());
        row.setInviteCode(inviteCode);
        row.setWaSubject(blankToNull(metadata.subject()));
        row.setWaDescription(blankToNull(metadata.description()));
        row.setWaDescriptionObserved(true);
        row.setMemberSize(metadata.participants().size());
        row.setOwnerPhone(ownerPhone);
        row.setOwnerPhoneObserved(true);
        row.setAnnounceOnly(metadata.announce());
        row.setAnnounceOnlyObserved(metadata.announce() != null);
        row.setAdminOnlyEditInfo(metadata.restrict());
        row.setAdminOnlyEditInfoObserved(metadata.restrict() != null);
        row.setMemberAddMode(metadata.memberAddMode());
        row.setMemberAddModeObserved(metadata.memberAddMode() != null);
        row.setJoinApprovalMode(metadata.joinApprovalMode());
        row.setJoinApprovalModeObserved(metadata.joinApprovalMode() != null);
        row.setEphemeralDurationSeconds(metadata.ephemeralDurationSeconds());
        row.setEphemeralDurationObserved(metadata.ephemeralDurationSeconds() != null);
        row.setGroupCreatedAt(validCreation(metadata.createdAtSeconds(), observedAt));
        row.setCreatorCountryIso2(country == null ? null : country.iso2());
        row.setCreatorContinentCode(country == null ? null : country.continentCode());
        row.setCreatorCountryObserved(true);
        row.setLastPreviewAt(completedAt);
        row.setMetadataObservedAt(observedAt);
        row.setCreatedAt(completedAt);
        row.setUpdatedAt(completedAt);
        return row;
    }

    private static List<WhatsappGroupMemberSnapshot> normalizeMembers(
            Long groupLinkId,
            String groupJid,
            GroupMetadataResult metadata,
            long completedAt) {
        Map<String, WhatsappGroupMemberSnapshot> unique = new LinkedHashMap<>();
        String explicitOwnerJid = blankToNull(metadata.ownerJid());
        for (GroupParticipantResult participant : metadata.participants()) {
            String participantJid = stableParticipantJid(participant.jid());
            String phone = confirmedPhone(participant, participantJid);
            boolean owner = Boolean.TRUE.equals(participant.owner())
                    || sameIdentity(explicitOwnerJid, participantJid, phone);
            boolean admin = owner || Boolean.TRUE.equals(participant.admin());
            WhatsappGroupMemberSnapshot row = member(
                    groupLinkId, groupJid, participantJid, phone, admin, owner, completedAt);
            unique.merge(participantJid, row, GroupMetadataSnapshotServiceImpl::strongerRole);
        }
        List<WhatsappGroupMemberSnapshot> rows = new ArrayList<>(unique.values());
        rows.sort(Comparator
                .comparingInt(GroupMetadataSnapshotServiceImpl::roleRank)
                .reversed()
                .thenComparing(WhatsappGroupMemberSnapshot::getParticipantJid));
        return List.copyOf(rows);
    }

    private static WhatsappGroupMemberSnapshot member(
            Long groupLinkId,
            String groupJid,
            String participantJid,
            String phone,
            boolean admin,
            boolean owner,
            long completedAt) {
        WhatsappGroupMemberSnapshot row = new WhatsappGroupMemberSnapshot();
        row.setGroupLinkId(groupLinkId);
        row.setGroupJid(groupJid);
        row.setParticipantJid(participantJid);
        row.setPhone(phone);
        row.setRole(owner ? "OWNER" : admin ? "ADMIN" : "MEMBER");
        row.setIsAdmin(admin);
        row.setIsOwner(owner);
        row.setSnapshotAt(completedAt);
        row.setCreatedAt(completedAt);
        row.setUpdatedAt(completedAt);
        return row;
    }

    private static WhatsappGroupMemberSnapshot strongerRole(
            WhatsappGroupMemberSnapshot first,
            WhatsappGroupMemberSnapshot second) {
        return roleRank(second) > roleRank(first) ? second : first;
    }

    private static int roleRank(WhatsappGroupMemberSnapshot row) {
        if (Boolean.TRUE.equals(row.getIsOwner())) {
            return 3;
        }
        return Boolean.TRUE.equals(row.getIsAdmin()) ? 2 : 1;
    }

    private static String confirmedOwnerPhone(
            GroupMetadataResult metadata,
            List<WhatsappGroupMemberSnapshot> members) {
        String fromOwnerJid = phoneFromPnJid(metadata.ownerJid());
        if (fromOwnerJid != null) {
            return fromOwnerJid;
        }
        return members.stream()
                .filter(row -> Boolean.TRUE.equals(row.getIsOwner()))
                .map(WhatsappGroupMemberSnapshot::getPhone)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static boolean sameIdentity(String ownerJid, String participantJid, String phone) {
        if (ownerJid == null) {
            return false;
        }
        if (ownerJid.equals(participantJid)) {
            return true;
        }
        String ownerPhone = phoneFromPnJid(ownerJid);
        return ownerPhone != null && ownerPhone.equals(phone);
    }

    private static String confirmedPhone(GroupParticipantResult participant, String participantJid) {
        String explicit = digitsOnly(participant.phone());
        return explicit != null ? explicit : phoneFromPnJid(participantJid);
    }

    private static String phoneFromPnJid(String value) {
        String normalized = blankToNull(value);
        if (normalized == null || !normalized.endsWith("@s.whatsapp.net")) {
            return null;
        }
        return digitsOnly(normalized.substring(0, normalized.indexOf('@')));
    }

    private static String digitsOnly(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        int at = normalized.indexOf('@');
        if (at >= 0) {
            normalized = normalized.substring(0, at);
        }
        String digits = normalized.replaceAll("[^0-9]", "");
        return digits.isBlank() ? null : digits;
    }

    private static String stableParticipantJid(String value) {
        String normalized = blankToNull(value);
        if (normalized == null || normalized.indexOf('@') <= 0 || normalized.endsWith("@g.us")) {
            throw new IllegalStateException("群 metadata 成员缺少稳定 JID");
        }
        return normalized;
    }

    private static Long validCreation(Long seconds, long observedAt) {
        if (seconds == null || seconds <= 0 || seconds > observedAt / 1_000L) {
            return null;
        }
        return seconds;
    }

    private static String requireGroupJid(String value) {
        String normalized = blankToNull(value);
        if (normalized == null || !normalized.endsWith("@g.us")) {
            throw new IllegalStateException("群详情同步任务缺少有效 groupJid");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
