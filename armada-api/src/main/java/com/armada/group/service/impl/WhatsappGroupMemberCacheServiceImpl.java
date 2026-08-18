package com.armada.group.service.impl;

import com.armada.group.mapper.WhatsappGroupMemberCacheMapper;
import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.model.dto.WhatsappGroupIdentityMergeFact;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.vo.WhatsappGroupMemberCacheRow;
import com.armada.group.model.vo.WhatsappGroupMemberCacheSnapshotVO;
import com.armada.group.model.vo.WhatsappGroupMemberStateVO;
import com.armada.group.service.WhatsappGroupMemberCacheService;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MySQL 实现的 WhatsApp 群成员缓存服务；读取统一使用新群模型。 */
@Service
public class WhatsappGroupMemberCacheServiceImpl implements WhatsappGroupMemberCacheService {

    private static final Logger log =
            LoggerFactory.getLogger(WhatsappGroupMemberCacheServiceImpl.class);

    private static final int QUERY_BATCH_SIZE = 500;

    private final WhatsappGroupMemberCacheMapper mapper;
    private final AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;

    public WhatsappGroupMemberCacheServiceImpl(
            WhatsappGroupMemberCacheMapper mapper,
            AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence) {
        this.mapper = mapper;
        this.currentSnapshotPersistence = currentSnapshotPersistence;
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
        GroupLinkPreview profile = new GroupLinkPreview();
        profile.setGroupJid(normalizedGroupJid);
        profile.setWaSubject(metadata.subject());
        profile.setAnnounceOnly(metadata.announce());
        profile.setMetadataObservedAt(snapshotAt);
        profile.setUpdatedAt(snapshotAt);
        currentSnapshotPersistence.replaceCompleteGroupMetadataSnapshot(
                profile, metadata.participants(), snapshotAt, snapshotVersion);
        return findByGroupJids(tenantId, List.of(normalizedGroupJid)).get(normalizedGroupJid);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyJoins(List<WhatsappGroupJoinFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        currentSnapshotPersistence.applyParticipantJoins(facts);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyDepartures(List<WhatsappGroupDepartureFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        currentSnapshotPersistence.applyParticipantDepartures(facts);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyIdentityMerges(List<WhatsappGroupIdentityMergeFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        List<WhatsappGroupIdentityMergeFact> mergeable = facts.stream()
                .filter(fact -> fact != null && !alreadySplitAcrossRows(fact))
                .toList();
        if (mergeable.isEmpty()) {
            return;
        }
        currentSnapshotPersistence.applyParticipantIdentityMerges(mergeable);
    }

    /**
     * 判断这个人在库里是不是已经分裂成 PN 行和 LID 行。
     *
     * <p>成员行按 PN 优先形态索引，两个身份各自命中一行时会查回两条记录。这种情况下写入会
     * 同时命中 PN 与 LID 两个唯一键，MySQL 直接报重复键错误把消费卡住，所以只记日志跳过。</p>
     */
    private boolean alreadySplitAcrossRows(WhatsappGroupIdentityMergeFact fact) {
        String groupJid = canonicalGroupJid(fact.groupJid());
        List<String> identities = List.of(fact.pnJid(), fact.lidJid()).stream()
                .filter(jid -> jid != null && !jid.isBlank())
                .map(jid -> jid.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
        if (identities.size() < 2) {
            return false;
        }
        List<WhatsappGroupMemberStateVO> existing = mapper.selectStatesByParticipantJids(
                fact.tenantId(), groupJid, identities);
        if (existing == null || existing.size() < 2) {
            return false;
        }
        log.warn("群成员两种身份已分属不同行,跳过身份合并 tenantId={} groupJid={} sourceEventId={} rows={}",
                fact.tenantId(), groupJid, fact.sourceEventId(), existing.size());
        return true;
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
