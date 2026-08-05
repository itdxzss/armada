package com.armada.group.service.impl;

import com.armada.group.mapper.WhatsappGroupMemberCacheMapper;
import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.model.dto.WhatsappGroupMemberCacheHeaderWrite;
import com.armada.group.model.dto.WhatsappGroupMemberStateWrite;
import com.armada.group.model.enums.WhatsappGroupMemberStateSource;
import com.armada.group.model.vo.WhatsappGroupMemberCacheRow;
import com.armada.group.model.vo.WhatsappGroupMemberCacheSnapshotVO;
import com.armada.group.model.vo.WhatsappGroupMemberStateVO;
import com.armada.group.service.WhatsappGroupMemberCacheService;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MySQL 实现的 WhatsApp 群成员完整缓存服务。 */
@Service
public class WhatsappGroupMemberCacheServiceImpl implements WhatsappGroupMemberCacheService {

    private static final int QUERY_BATCH_SIZE = 500;
    private static final int WRITE_BATCH_SIZE = 200;

    private final WhatsappGroupMemberCacheMapper mapper;

    public WhatsappGroupMemberCacheServiceImpl(WhatsappGroupMemberCacheMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, WhatsappGroupMemberCacheSnapshotVO> findByGroupJids(
            Long tenantId,
            List<String> groupJids) {
        if (tenantId == null) {
            return Map.of();
        }
        List<String> normalized = normalizeGroupJids(groupJids);
        if (normalized.isEmpty()) {
            return Map.of();
        }
        List<WhatsappGroupMemberCacheRow> rows = new ArrayList<>();
        for (int start = 0; start < normalized.size(); start += QUERY_BATCH_SIZE) {
            rows.addAll(mapper.selectByGroupJids(
                    tenantId,
                    normalized.subList(start, Math.min(start + QUERY_BATCH_SIZE, normalized.size()))));
        }
        Map<String, SnapshotBuilder> builders = new LinkedHashMap<>();
        for (WhatsappGroupMemberCacheRow row : rows) {
            SnapshotBuilder builder = builders.computeIfAbsent(
                    row.groupJid(),
                    ignored -> new SnapshotBuilder(row));
            if (row.participantJid() != null) {
                builder.members.add(new WhatsappGroupMemberStateVO(
                        row.participantJid(), row.phone(), row.admin(), row.owner(), row.role(),
                        Boolean.TRUE.equals(row.inGroup()), row.stateSource(), row.stateUpdatedAt()));
            }
        }
        Map<String, WhatsappGroupMemberCacheSnapshotVO> result = new LinkedHashMap<>();
        builders.forEach((groupJid, builder) -> result.put(groupJid, builder.build()));
        return Map.copyOf(result);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WhatsappGroupMemberCacheSnapshotVO replaceCompleteSnapshot(
            Long tenantId,
            Long observerAccountId,
            String groupJid,
            GroupMetadataResult metadata,
            long snapshotAt) {
        if (tenantId == null || observerAccountId == null || metadata == null
                || metadata.participants() == null) {
            throw new IllegalArgumentException("完整群成员快照参数不完整");
        }
        String normalizedGroupJid = canonicalGroupJid(groupJid);
        String snapshotVersion = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        mapper.upsertHeader(new WhatsappGroupMemberCacheHeaderWrite(
                tenantId, normalizedGroupJid, metadata.subject(), metadata.announce(), snapshotAt,
                snapshotVersion, observerAccountId), now);
        String winningVersion = mapper.selectSnapshotVersionForUpdate(tenantId, normalizedGroupJid);
        if (!snapshotVersion.equals(winningVersion)) {
            return findByGroupJids(tenantId, List.of(normalizedGroupJid)).get(normalizedGroupJid);
        }
        List<WhatsappGroupMemberStateWrite> states = metadata.participants().stream()
                .map(participant -> snapshotState(
                        tenantId, observerAccountId, normalizedGroupJid,
                        snapshotVersion, snapshotAt, participant))
                .sorted(Comparator.comparing(WhatsappGroupMemberStateWrite::participantJid))
                .toList();
        upsertInBatches(states);
        mapper.markSnapshotMissing(
                tenantId, normalizedGroupJid, snapshotVersion, snapshotAt,
                "snapshot:" + snapshotVersion + ":absent", observerAccountId, now);
        return findByGroupJids(tenantId, List.of(normalizedGroupJid)).get(normalizedGroupJid);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyJoins(List<WhatsappGroupJoinFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        List<WhatsappGroupMemberStateWrite> states = facts.stream()
                .map(fact -> new WhatsappGroupMemberStateWrite(
                        fact.tenantId(), canonicalGroupJid(fact.groupJid()),
                        canonicalParticipantJid(fact.participantJid(), fact.phone()),
                        normalizedPhone(fact.phone()), false, false, "member", true,
                        WhatsappGroupMemberStateSource.ADD_EVENT.name(), fact.eventAt(),
                        fact.sourceEventId(), null, fact.observerAccountId()))
                .sorted(stateComparator())
                .toList();
        upsertInBatches(states);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyDepartures(List<WhatsappGroupDepartureFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        List<WhatsappGroupMemberStateWrite> states = facts.stream()
                .map(fact -> new WhatsappGroupMemberStateWrite(
                        fact.tenantId(), canonicalGroupJid(fact.groupJid()),
                        canonicalParticipantJid(fact.participantJid(), fact.phone()),
                        normalizedPhone(fact.phone()), null, null, null, false,
                        departureSource(fact.sourceType(), fact.exitType()).name(), fact.eventAt(),
                        fact.sourceEventId(), null, null))
                .sorted(stateComparator())
                .toList();
        upsertInBatches(states);
    }

    private void upsertInBatches(List<WhatsappGroupMemberStateWrite> states) {
        long now = System.currentTimeMillis();
        for (int start = 0; start < states.size(); start += WRITE_BATCH_SIZE) {
            mapper.upsertStates(states.subList(
                    start, Math.min(start + WRITE_BATCH_SIZE, states.size())), now);
        }
    }

    private static WhatsappGroupMemberStateWrite snapshotState(
            Long tenantId,
            Long observerAccountId,
            String groupJid,
            String snapshotVersion,
            long snapshotAt,
            GroupParticipantResult participant) {
        String phone = normalizedPhone(participant.phone());
        String participantJid = canonicalParticipantJid(participant.jid(), phone);
        return new WhatsappGroupMemberStateWrite(
                tenantId, groupJid, participantJid, phone,
                participant.admin(), participant.owner(), participant.role(), true,
                WhatsappGroupMemberStateSource.FULL_SNAPSHOT.name(), snapshotAt,
                "snapshot:" + snapshotVersion + ":" + participantJid,
                snapshotVersion, observerAccountId);
    }

    private static Comparator<WhatsappGroupMemberStateWrite> stateComparator() {
        return Comparator.comparing(WhatsappGroupMemberStateWrite::groupJid)
                .thenComparing(WhatsappGroupMemberStateWrite::participantJid);
    }

    private static WhatsappGroupMemberStateSource departureSource(String sourceType, String exitType) {
        if ("WGP2_NOTIFICATION".equalsIgnoreCase(sourceType)
                && "REMOVED".equalsIgnoreCase(exitType)) {
            return WhatsappGroupMemberStateSource.UNKNOWN_EXIT_EVENT;
        }
        if ("REMOVED".equalsIgnoreCase(exitType)) {
            return WhatsappGroupMemberStateSource.REMOVE_EVENT;
        }
        if ("LEFT".equalsIgnoreCase(exitType)) {
            return WhatsappGroupMemberStateSource.LEAVE_EVENT;
        }
        return WhatsappGroupMemberStateSource.UNKNOWN_EXIT_EVENT;
    }

    private static List<String> normalizeGroupJids(List<String> groupJids) {
        if (groupJids == null) {
            return List.of();
        }
        return groupJids.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(WhatsappGroupMemberCacheServiceImpl::canonicalGroupJid)
                .distinct()
                .sorted()
                .toList();
    }

    private static String canonicalGroupJid(String groupJid) {
        if (groupJid == null || groupJid.isBlank()) {
            throw new IllegalArgumentException("群JID不能为空");
        }
        String normalized = groupJid.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("@") ? normalized : normalized + "@g.us";
    }

    private static String canonicalParticipantJid(String participantJid, String phone) {
        String jid = participantJid == null
                ? null : participantJid.trim().toLowerCase(Locale.ROOT);
        if (jid != null && !jid.isBlank()) {
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
        if (jid == null || jid.isBlank()) {
            throw new IllegalArgumentException("群成员JID不能为空");
        }
        return jid;
    }

    private static String normalizedPhone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : digits;
    }

    private static final class SnapshotBuilder {
        private final WhatsappGroupMemberCacheRow header;
        private final List<WhatsappGroupMemberStateVO> members = new ArrayList<>();

        private SnapshotBuilder(WhatsappGroupMemberCacheRow header) {
            this.header = header;
        }

        private WhatsappGroupMemberCacheSnapshotVO build() {
            return new WhatsappGroupMemberCacheSnapshotVO(
                    header.groupJid(), header.subject(), header.announce(), header.snapshotAt(),
                    header.observerAccountId(), List.copyOf(members));
        }
    }
}
