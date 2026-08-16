package com.armada.group.service.impl;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Existing;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.GroupId;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.LegacyGroupReference;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantPresenceWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.SyncStateWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Write;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.model.vo.AccountGroupMembershipChangeSet;
import com.armada.platform.protocol.model.enums.OwnerIdentityKind;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将账号群快照及现有精确进退群事实写入 V120 新表。 */
@Service
public class AccountGroupCurrentSnapshotPersistenceImpl {

    private static final Logger log = LoggerFactory.getLogger(
            AccountGroupCurrentSnapshotPersistenceImpl.class);

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final int SUBJECT_MAX_LENGTH = 255;
    private static final int AVATAR_URL_MAX_LENGTH = 512;
    private static final int EVENT_ID_MAX_LENGTH = 255;
    private static final int SNAPSHOT_VERSION_MAX_LENGTH = 64;
    private static final int PARTICIPANT_WRITE_BATCH_SIZE = 200;
    private static final int PRESENCE_IN_GROUP = 1;
    private static final String SNAPSHOT_SOURCE = "GROUP_SNAPSHOT";
    private final AccountGroupCurrentSnapshotMapper mapper;
    private final ObjectMapper objectMapper;

    public AccountGroupCurrentSnapshotPersistenceImpl(
            AccountGroupCurrentSnapshotMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 替换单账号当前可见群。
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountGroupMembershipChangeSet replaceVisibleGroups(
            Long accountId,
            List<AccountGroupsReportedEvent.Group> groups,
            boolean snapshotComplete,
            long syncAt,
            String eventId) {
        return replaceVisibleGroups(
                accountId, groups, snapshotComplete, syncAt, eventId, List.of());
    }

    /** 替换单账号当前可见群，并回写旧流程本次已经选中的群入口。 */
    @Transactional(rollbackFor = Exception.class)
    public AccountGroupMembershipChangeSet replaceVisibleGroups(
            Long accountId,
            List<AccountGroupsReportedEvent.Group> groups,
            boolean snapshotComplete,
            long syncAt,
            String eventId,
            List<AccountGroupMembershipSnapshot> legacyGroups) {
        if (accountId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模型快照缺少 accountId");
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        Context context = mapper.selectContext(accountId);
        if (context == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模型快照找不到活跃账号");
        }
        WhatsappJids.OwnerIdentity self = WhatsappJids.ownerIdentity(context.wsPhone(), "pn");
        if (self.kind() != OwnerIdentityKind.PN
                || self.ownerJid() == null
                || self.ownerPhone() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号手机号无法构造 self PN JID");
        }

        long now = System.currentTimeMillis();
        Map<String, AccountGroupsReportedEvent.Group> visible = normalizedGroups(groups);
        List<String> visibleJids = List.copyOf(visible.keySet());
        BaselineEvidence baseline = baselineEvidence(context);
        List<Existing> existingRows = mapper.selectExisting(
                accountId, self.ownerJid(), visibleJids);
        Map<String, Existing> existingByJid = existingRows.stream()
                .filter(row -> visible.containsKey(row.groupJid()))
                .collect(Collectors.toMap(
                        Existing::groupJid,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        Set<String> snapshotEstablishedBefore = existingRows.stream()
                .filter(row -> visible.containsKey(row.groupJid()))
                .filter(row -> row.bindingId() != null)
                .filter(row -> sendablePresence(row.presenceStatus()))
                .filter(row -> !"WGP2_ADD".equals(row.presenceSource()))
                .map(Existing::groupJid)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        String normalizedEventId = clamp(blankToNull(eventId), EVENT_ID_MAX_LENGTH);
        List<Write> rows = new ArrayList<>(visible.size());
        for (Map.Entry<String, AccountGroupsReportedEvent.Group> entry : visible.entrySet()) {
            Existing existing = existingByJid.get(entry.getKey());
            boolean acceptedPresence = snapshotWins(existing, syncAt);
            Classification classification = classification(
                    entry.getKey(), baseline, acceptedPresence, syncAt);
            AccountGroupsReportedEvent.Group group = entry.getValue();
            String subject = clamp(blankToNull(group.subject()), SUBJECT_MAX_LENGTH);
            Long activeSince = acceptedPresence
                    && (existing == null || existing.presenceStatus() == null
                    || existing.presenceStatus() != PRESENCE_IN_GROUP)
                    ? syncAt : null;
            rows.add(new Write(
                    null,
                    entry.getKey(),
                    clamp(blankToNull(group.avatarUrl()), AVATAR_URL_MAX_LENGTH),
                    subject,
                    group.memberCount(),
                    toEpochMillis(group.groupCreatedAt()),
                    group.announceOnly(),
                    self.ownerJid(),
                    self.ownerPhone(),
                    role(group.admin()),
                    normalizedEventId,
                    syncAt,
                    now,
                    classification.wasInInitialBaseline(),
                    classification.baselineSubjectSnapshot(),
                    activeSince,
                    classification.firstPostControlObservedAt()));
        }

        if (!rows.isEmpty()) {
            List<Write> missingGroupRows = rows.stream()
                    .filter(row -> {
                        Existing existing = existingByJid.get(row.groupJid());
                        return existing == null || existing.deletedAt() != null;
                    })
                    .toList();
            if (!missingGroupRows.isEmpty()) {
                mapper.insertMissingGroups(tenantId, missingGroupRows);
            }
            Map<String, Long> groupIds = mapper.selectGroupIds(tenantId, visibleJids).stream()
                    .collect(Collectors.toMap(GroupId::groupJid, GroupId::groupId));
            if (groupIds.size() != rows.size()) {
                throw new BusinessException(ErrorCode.CONFLICT, "新群模型批量解析 groupId 不完整");
            }
            Map<Long, LegacyGroupReference> referencesByHandleId = new TreeMap<>();
            if (legacyGroups != null) {
                for (AccountGroupMembershipSnapshot legacyGroup : legacyGroups) {
                    if (legacyGroup == null || legacyGroup.groupLinkId() == null) {
                        continue;
                    }
                    Long groupId = groupIds.get(normalizeJid(legacyGroup.groupJid()));
                    if (groupId != null) {
                        referencesByHandleId.putIfAbsent(
                                legacyGroup.groupLinkId(),
                                new LegacyGroupReference(legacyGroup.groupLinkId(), groupId));
                    }
                }
            }
            if (!referencesByHandleId.isEmpty()) {
                mapper.updateSelectedLegacyGroupReferences(
                        tenantId, List.copyOf(referencesByHandleId.values()));
            }
            rows = rows.stream()
                    .map(row -> row.withGroupId(groupIds.get(row.groupJid())))
                    .sorted(Comparator.comparing(Write::groupId).thenComparing(Write::groupJid))
                    .toList();
            mapper.upsertProfiles(rows);
            mapper.upsertParticipants(rows);
        }

        if (snapshotComplete) {
            List<Long> missingParticipantIds = existingRows.stream()
                    .filter(row -> row.bindingId() != null)
                    .filter(row -> row.participantId() != null)
                    .filter(row -> !visible.containsKey(row.groupJid()))
                    .filter(row -> snapshotWins(row, syncAt))
                    .map(Existing::participantId)
                    .distinct()
                    .sorted()
                    .toList();
            if (!missingParticipantIds.isEmpty()) {
                mapper.markMissingParticipants(
                        missingParticipantIds, syncAt, normalizedEventId, now);
            }
        }

        if (!rows.isEmpty()) {
            mapper.upsertBindings(tenantId, accountId, rows);
        }

        mapper.upsertSyncState(syncState(context, baseline, snapshotComplete, syncAt, now));

        List<AccountGroupMembershipSnapshot> currentGroups = legacyGroups == null
                ? List.of()
                : legacyGroups.stream().filter(Objects::nonNull).toList();
        List<AccountGroupMembershipSnapshot> addedGroups = currentGroups.stream()
                .filter(group -> visible.containsKey(normalizeJid(group.groupJid())))
                .filter(group -> !snapshotEstablishedBefore.contains(normalizeJid(group.groupJid())))
                .filter(group -> sendableAfterSnapshot(
                        existingByJid.get(normalizeJid(group.groupJid())), syncAt))
                .toList();
        return new AccountGroupMembershipChangeSet(currentGroups, addedGroups);
    }

    /**
     * 双写账号自身的精确进群、离群或不在群事实。
     *
     * <p>调用方已经完成租户、账号协议句柄和动作校验。本方法只更新新模型，不产生营销等业务副作用。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void applySelfMembershipChanged(
            Long accountId,
            String groupJid,
            AccountGroupMembershipStatus status,
            long occurredAt,
            String eventId,
            String presenceSource) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        Context context = mapper.selectContext(accountId);
        if (context == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模型精确关系事件找不到活跃账号");
        }
        WhatsappJids.OwnerIdentity self = WhatsappJids.ownerIdentity(context.wsPhone(), "pn");
        if (self.kind() != OwnerIdentityKind.PN
                || self.ownerJid() == null
                || self.ownerPhone() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号手机号无法构造 self PN JID");
        }
        boolean inGroup = status == AccountGroupMembershipStatus.IN_GROUP;
        if (!inGroup
                && status != AccountGroupMembershipStatus.NOT_IN_GROUP
                && status != AccountGroupMembershipStatus.LEFT) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模型不支持该账号群关系状态");
        }

        String normalizedGroupJid = normalizeJid(groupJid);
        String normalizedSource = blankToNull(presenceSource);
        if (normalizedGroupJid == null || normalizedSource == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模型精确关系事件缺少群或来源");
        }
        Existing existing = mapper.selectSelfMembershipExisting(
                accountId, self.ownerJid(), normalizedGroupJid);
        boolean accepted = presenceWins(existing, occurredAt, normalizedSource);
        BaselineEvidence baseline = baselineEvidenceForPreciseEvent(context);
        Classification classification = inGroup
                ? classification(normalizedGroupJid, baseline, accepted, occurredAt)
                : Classification.unknown();
        Long activeSince = accepted && inGroup
                && (existing == null || existing.presenceStatus() == null
                || existing.presenceStatus() != PRESENCE_IN_GROUP)
                ? occurredAt : null;
        long now = System.currentTimeMillis();
        String normalizedEventId = clamp(blankToNull(eventId), EVENT_ID_MAX_LENGTH);
        ParticipantPresenceWrite row = new ParticipantPresenceWrite(
                existing == null ? null : existing.groupId(),
                normalizedGroupJid,
                self.ownerJid(),
                null,
                self.ownerPhone(),
                inGroup ? 1 : 2,
                normalizedSource,
                normalizedEventId,
                occurredAt,
                now,
                inGroup ? occurredAt : null,
                status == AccountGroupMembershipStatus.LEFT ? "LEFT"
                        : inGroup ? null : "UNKNOWN",
                inGroup ? null : occurredAt,
                inGroup ? null : "WGP2_NOTIFICATION",
                null,
                null,
                null,
                null,
                null,
                classification.wasInInitialBaseline(),
                classification.baselineSubjectSnapshot(),
                activeSince,
                classification.firstPostControlObservedAt());

        if (row.groupId() == null || (inGroup && existing.deletedAt() != null)) {
            mapper.insertMissingGroups(tenantId, List.of(new Write(
                    null, normalizedGroupJid, null,
                    null, null, null, null, self.ownerJid(), self.ownerPhone(),
                    0, normalizedEventId, occurredAt, now, null, null, null, null)));
        }
        if (row.groupId() == null) {
            List<GroupId> groupIds = mapper.selectGroupIds(
                    tenantId, List.of(normalizedGroupJid));
            if (groupIds.size() != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法解析精确关系事件的 groupId");
            }
            row = row.withGroupId(groupIds.get(0).groupId());
        }
        mapper.updateLegacyGroupReferences(tenantId, List.of(normalizedGroupJid));
        mapper.upsertParticipantFacts(List.of(row));
        mapper.upsertSelfBinding(tenantId, accountId, row);
    }

    /**
     * 双写受控账号的成员当前状态和角色观察，不把角色或查询时间解释成进群时间。
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyControlledParticipantObservation(
            Long accountId,
            String groupJid,
            boolean inGroup,
            boolean admin,
            long observedAt,
            String eventId,
            String source) {
        Long tenantId = requiredTenantId();
        Context context = mapper.selectContext(accountId);
        if (context == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模型成员观察找不到活跃账号");
        }
        WhatsappJids.OwnerIdentity self = WhatsappJids.ownerIdentity(context.wsPhone(), "pn");
        if (self.kind() != OwnerIdentityKind.PN
                || self.ownerJid() == null
                || self.ownerPhone() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号手机号无法构造 self PN JID");
        }
        String normalizedGroupJid = participantGroupJid(groupJid);
        String normalizedSource = blankToNull(source);
        if (normalizedSource == null || observedAt <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "新群模型成员观察来源或事实时间非法");
        }

        Existing existing = mapper.selectSelfMembershipExisting(
                accountId, self.ownerJid(), normalizedGroupJid);
        long now = System.currentTimeMillis();
        ParticipantPresenceWrite row = new ParticipantPresenceWrite(
                existing == null ? null : existing.groupId(),
                normalizedGroupJid,
                self.ownerJid(),
                null,
                self.ownerPhone(),
                inGroup ? 1 : 2,
                normalizedSource,
                clamp(blankToNull(eventId), EVENT_ID_MAX_LENGTH),
                observedAt,
                now,
                null,
                null,
                null,
                null,
                inGroup ? (admin ? 2 : 1) : null,
                inGroup ? normalizedSource : null,
                inGroup ? observedAt : null,
                inGroup ? clamp(blankToNull(eventId), EVENT_ID_MAX_LENGTH) : null,
                null,
                null,
                null,
                null,
                null);

        if (row.groupId() == null || (inGroup && existing.deletedAt() != null)) {
            mapper.insertMissingGroups(tenantId, List.of(new Write(
                    null, normalizedGroupJid, null,
                    null, null, null, null, self.ownerJid(), self.ownerPhone(),
                    0, row.eventId(), observedAt, now, null, null, null, null)));
        }
        if (row.groupId() == null) {
            List<GroupId> groupIds = mapper.selectGroupIds(
                    tenantId, List.of(normalizedGroupJid));
            if (groupIds.size() != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法解析成员观察的 groupId");
            }
            row = row.withGroupId(groupIds.get(0).groupId());
        }
        mapper.updateLegacyGroupReferences(tenantId, List.of(normalizedGroupJid));
        mapper.upsertParticipantFacts(List.of(row));
        mapper.upsertSelfBinding(tenantId, accountId, row);
    }

    /** 双写现有普通成员进群事实，不创建账号群关系。 */
    @Transactional(rollbackFor = Exception.class)
    public void applyParticipantJoins(List<WhatsappGroupJoinFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        Long tenantId = requiredTenantId();
        long now = System.currentTimeMillis();
        List<ParticipantPresenceWrite> rows = facts.stream()
                .filter(Objects::nonNull)
                .map(fact -> {
                    validateParticipantFactTenant(tenantId, fact.tenantId());
                    ParticipantIdentity identity = participantIdentity(fact.participantJid());
                    return new ParticipantPresenceWrite(
                            null,
                            participantGroupJid(fact.groupJid()),
                            identity.pnJid(),
                            identity.lidJid(),
                            blankToNull(fact.phone()),
                            1,
                            "ADD_EVENT",
                            clamp(blankToNull(fact.sourceEventId()), EVENT_ID_MAX_LENGTH),
                            requiredFactTime(fact.eventAt()),
                            now,
                            requiredFactTime(fact.joinedAt()),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null);
                })
                .toList();
        persistParticipantFacts(tenantId, rows);
    }

    /** 双写现有普通成员退群事实，不创建账号群关系。 */
    @Transactional(rollbackFor = Exception.class)
    public void applyParticipantDepartures(List<WhatsappGroupDepartureFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        Long tenantId = requiredTenantId();
        long now = System.currentTimeMillis();
        List<ParticipantPresenceWrite> rows = facts.stream()
                .filter(Objects::nonNull)
                .map(fact -> {
                    validateParticipantFactTenant(tenantId, fact.tenantId());
                    ParticipantIdentity identity = participantIdentity(fact.participantJid());
                    String exitType = normalizedExitType(fact.exitType());
                    return new ParticipantPresenceWrite(
                            null,
                            participantGroupJid(fact.groupJid()),
                            identity.pnJid(),
                            identity.lidJid(),
                            blankToNull(fact.phone()),
                            2,
                            departurePresenceSource(fact.sourceType(), exitType),
                            clamp(blankToNull(fact.sourceEventId()), EVENT_ID_MAX_LENGTH),
                            requiredFactTime(fact.eventAt()),
                            now,
                            null,
                            exitType,
                            requiredFactTime(fact.exitedAt()),
                            blankToNull(fact.sourceType()),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null);
                })
                .toList();
        persistParticipantFacts(tenantId, rows);
    }

    /** 将现有成员角色/定点查询观察同步到成员当前事实，不创建账号群关系。 */
    @Transactional(rollbackFor = Exception.class)
    public void applyParticipantObservations(List<GroupParticipantObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            return;
        }
        Long tenantId = requiredTenantId();
        long now = System.currentTimeMillis();
        List<ParticipantPresenceWrite> rows = observations.stream()
                .filter(Objects::nonNull)
                .map(observation -> participantObservation(tenantId, observation, now))
                .toList();
        persistParticipantFacts(tenantId, rows);
    }

    /** 用已确认完整的成员数组替换新模型成员快照。 */
    @Transactional(rollbackFor = Exception.class)
    public void replaceCompleteParticipantSnapshot(
            String groupJid,
            List<GroupParticipantResult> participants,
            long snapshotAt,
            String snapshotVersion) {
        replaceCompleteSnapshot(groupJid, participants, snapshotAt, snapshotVersion, null);
    }

    /** 将现有群详情成功结果同步写入新模型群资料和完整成员快照。 */
    @Transactional(rollbackFor = Exception.class)
    public void replaceCompleteGroupMetadataSnapshot(
            GroupLinkPreview preview,
            List<GroupParticipantResult> participants,
            long snapshotAt,
            String snapshotVersion) {
        if (preview == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "完整群资料快照为空");
        }
        replaceCompleteSnapshot(
                preview.getGroupJid(), participants, snapshotAt, snapshotVersion, preview);
    }

    /** 将协议回读已确认的群资料单字段立即写入新模型。 */
    @Transactional(rollbackFor = Exception.class)
    public void applyConfirmedMetadata(GroupLinkPreview preview) {
        if (preview == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "已确认群资料为空");
        }
        Long tenantId = requiredTenantId();
        String groupJid = participantGroupJid(preview.getGroupJid());
        long observedAt = requiredFactTime(preview.getMetadataObservedAt());
        long now = System.currentTimeMillis();
        Long groupId = resolveGroupIds(tenantId, List.of(groupJid), now).get(groupJid);
        mapper.upsertGroupMetadata(groupId, preview, null, observedAt, now);
    }

    private void replaceCompleteSnapshot(
            String groupJid,
            List<GroupParticipantResult> participants,
            long snapshotAt,
            String snapshotVersion,
            GroupLinkPreview preview) {
        Long tenantId = requiredTenantId();
        if (participants == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "完整群成员快照缺少成员数组");
        }
        String normalizedGroupJid = participantGroupJid(groupJid);
        String normalizedVersion = clamp(
                blankToNull(snapshotVersion), SNAPSHOT_VERSION_MAX_LENGTH);
        if (normalizedVersion == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "完整群成员快照缺少版本");
        }
        long now = System.currentTimeMillis();
        Long groupId = resolveGroupIds(
                tenantId, List.of(normalizedGroupJid), now).get(normalizedGroupJid);
        if (groupId == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法解析成员快照的 groupId");
        }
        if (preview != null) {
            long metadataObservedAt = preview.getMetadataObservedAt() == null
                    ? requiredFactTime(preview.getUpdatedAt())
                    : preview.getMetadataObservedAt();
            mapper.upsertGroupMetadata(
                    groupId, preview, toEpochMillis(preview.getGroupCreatedAt()),
                    metadataObservedAt, now);
        }
        List<ParticipantPresenceWrite> rows = participants.stream()
                .map(participant -> snapshotParticipant(
                        groupId, normalizedGroupJid, participant,
                        snapshotAt, normalizedVersion, now))
                .sorted(Comparator.comparing(
                        AccountGroupCurrentSnapshotPersistenceImpl::participantIdentityKey))
                .toList();
        mapper.upsertParticipantSnapshotHeader(
                groupId, rows.size(), snapshotAt, normalizedVersion, now);
        String winningVersion = mapper.selectParticipantSnapshotVersionForUpdate(groupId);
        if (!normalizedVersion.equals(winningVersion)) {
            return;
        }
        upsertParticipantFactsInBatches(rows);
        mapper.markParticipantSnapshotMissing(
                groupId,
                snapshotAt,
                normalizedVersion,
                clamp("snapshot:" + normalizedVersion + ":absent", EVENT_ID_MAX_LENGTH),
                now);
    }

    private void persistParticipantFacts(
            Long tenantId,
            List<ParticipantPresenceWrite> incomingRows) {
        if (incomingRows.isEmpty()) {
            return;
        }
        List<String> groupJids = incomingRows.stream()
                .map(ParticipantPresenceWrite::groupJid)
                .distinct()
                .sorted()
                .toList();
        Map<String, Long> groupIds = resolveGroupIds(
                tenantId, groupJids, incomingRows.get(0).now());
        List<ParticipantPresenceWrite> rows = incomingRows.stream()
                .map(row -> row.withGroupId(groupIds.get(row.groupJid())))
                .sorted(Comparator.comparing(ParticipantPresenceWrite::groupId)
                        .thenComparing(AccountGroupCurrentSnapshotPersistenceImpl::participantIdentityKey)
                        .thenComparingLong(ParticipantPresenceWrite::occurredAt))
                .toList();
        upsertParticipantFactsInBatches(rows);
    }

    private Map<String, Long> resolveGroupIds(
            Long tenantId,
            List<String> groupJids,
            long now) {
        Map<String, Long> groupIds = mapper.selectGroupIdsWithoutLock(tenantId, groupJids).stream()
                .collect(Collectors.toMap(GroupId::groupJid, GroupId::groupId));
        List<String> missingGroupJids = groupJids.stream()
                .filter(groupJid -> !groupIds.containsKey(groupJid))
                .toList();
        if (!missingGroupJids.isEmpty()) {
            List<Write> missingGroups = missingGroupJids.stream()
                    .map(groupJid -> new Write(
                            null, groupJid, null,
                            null, null, null, null, null, null, 0, null,
                            now, now, null, null, null, null))
                    .toList();
            mapper.insertMissingGroups(tenantId, missingGroups);
            mapper.selectGroupIds(tenantId, missingGroupJids)
                    .forEach(group -> groupIds.put(group.groupJid(), group.groupId()));
        }
        if (groupIds.size() != groupJids.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法解析成员事件的 groupId");
        }
        mapper.updateLegacyGroupReferences(tenantId, groupJids);
        return groupIds;
    }

    private void upsertParticipantFactsInBatches(List<ParticipantPresenceWrite> rows) {
        for (int start = 0; start < rows.size(); start += PARTICIPANT_WRITE_BATCH_SIZE) {
            mapper.upsertParticipantFacts(rows.subList(
                    start, Math.min(start + PARTICIPANT_WRITE_BATCH_SIZE, rows.size())));
        }
    }

    private BaselineEvidence baselineEvidenceForPreciseEvent(Context context) {
        try {
            return baselineEvidence(context);
        } catch (BusinessException exception) {
            log.warn("账号 baseline 不完整，精确关系事件在新模型中保留未知分类 accountId={}",
                    context.accountId());
            return new BaselineEvidence(
                    AccountGroupBaselineStateCode.PENDING,
                    0,
                    null,
                    null,
                    Set.of(),
                    Map.of(),
                    false);
        }
    }

    private SyncStateWrite syncState(
            Context context,
            BaselineEvidence baseline,
            boolean snapshotComplete,
            long syncAt,
            long now) {
        return new SyncStateWrite(
                context.accountId(),
                baseline.state(),
                baseline.completeness(),
                baseline.capturedAt(),
                baseline.groupCount(),
                context.lastSyncRequestedAt(),
                syncAt,
                snapshotComplete,
                snapshotComplete ? syncAt : null,
                now);
    }

    private BaselineEvidence baselineEvidence(Context context) {
        int state = switch (context.baselineState() == null ? AccountGroupBaselineStateCode.PENDING
                : context.baselineState()) {
            case AccountGroupBaselineStateCode.CAPTURED -> AccountGroupBaselineStateCode.CAPTURED;
            case AccountGroupBaselineStateCode.DISABLED -> AccountGroupBaselineStateCode.DISABLED;
            default -> AccountGroupBaselineStateCode.PENDING;
        };
        if (state != AccountGroupBaselineStateCode.CAPTURED) {
            return new BaselineEvidence(state, 0, null, null, Set.of(), Map.of(), false);
        }
        if (context.baselineCapturedAt() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "已拍 baseline 缺少 capturedAt");
        }
        ParsedBaseline parsed = parseBaseline(context);
        boolean previouslyProven = context.targetBaselineCompleteness() != null
                && context.targetBaselineCompleteness() == 1;
        int completeness = previouslyProven || (parsed.valid() && !parsed.groupJids().isEmpty())
                ? 1 : 2;
        Integer groupCount = completeness == 1
                ? parsed.valid() ? parsed.groupJids().size() : context.baselineGroupCount()
                : context.baselineGroupCount();
        if (completeness == 1 && groupCount == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "完整 baseline 缺少群数量");
        }
        return new BaselineEvidence(
                state,
                completeness,
                context.baselineCapturedAt(),
                groupCount,
                parsed.groupJids(),
                parsed.subjects(),
                parsed.valid() && completeness == 1);
    }

    private ParsedBaseline parseBaseline(Context context) {
        if (context.baselineGroupJidsJson() == null) {
            return new ParsedBaseline(Set.of(), Map.of(), false);
        }
        try {
            List<String> rawJids = objectMapper.readValue(
                    context.baselineGroupJidsJson(), STRING_LIST);
            Set<String> groupJids = new LinkedHashSet<>();
            for (String rawJid : rawJids) {
                String groupJid = normalizeJid(rawJid);
                if (groupJid != null) {
                    groupJids.add(groupJid);
                }
            }
            Map<String, String> subjects = new LinkedHashMap<>();
            if (context.baselineGroupSubjectsJson() != null) {
                Map<String, String> rawSubjects = objectMapper.readValue(
                        context.baselineGroupSubjectsJson(), STRING_MAP);
                rawSubjects.forEach((rawJid, rawSubject) -> {
                    String groupJid = normalizeJid(rawJid);
                    String subject = clamp(blankToNull(rawSubject), SUBJECT_MAX_LENGTH);
                    if (groupJid != null && subject != null) {
                        subjects.putIfAbsent(groupJid, subject);
                    }
                });
            }
            return new ParsedBaseline(Set.copyOf(groupJids), Map.copyOf(subjects), true);
        } catch (JsonProcessingException | RuntimeException exception) {
            log.warn("账号 baseline JSON 无法安全映射到新群模型 accountId={}", context.accountId());
            return new ParsedBaseline(Set.of(), Map.of(), false);
        }
    }

    private static Classification classification(
            String groupJid,
            BaselineEvidence baseline,
            boolean acceptedPresence,
            long syncAt) {
        if (!acceptedPresence || !baseline.classifiable()) {
            return Classification.unknown();
        }
        if (baseline.groupJids().contains(groupJid)) {
            return new Classification(1, baseline.subjects().get(groupJid), null);
        }
        if (baseline.capturedAt() != null && syncAt > baseline.capturedAt()) {
            return new Classification(0, null, syncAt);
        }
        return Classification.unknown();
    }

    private static boolean snapshotWins(Existing existing, long syncAt) {
        return presenceWins(existing, syncAt, SNAPSHOT_SOURCE);
    }

    private static boolean sendableAfterSnapshot(Existing existing, long syncAt) {
        return snapshotWins(existing, syncAt)
                || existing != null && sendablePresence(existing.presenceStatus());
    }

    private static boolean sendablePresence(Integer presenceStatus) {
        return presenceStatus != null && (presenceStatus == 0 || presenceStatus == 1);
    }

    private static boolean presenceWins(Existing existing, long observedAt, String source) {
        if (existing == null || existing.presenceObservedAt() == null) {
            return true;
        }
        if (observedAt != existing.presenceObservedAt()) {
            return observedAt > existing.presenceObservedAt();
        }
        return sourcePriority(source) > sourcePriority(existing.presenceSource());
    }

    private static int sourcePriority(String source) {
        if (source == null) {
            return 0;
        }
        return switch (source) {
            case "WGP2_REMOVE", "WGP2_LEAVE", "REMOVE_EVENT", "LEAVE_EVENT",
                    "UNKNOWN_EXIT_EVENT" -> 5;
            case "WGP2_PROMOTE", "WGP2_DEMOTE" -> 4;
            case "WGP2_ADD", "ADD_EVENT" -> 3;
            case "GROUP_MEMBER_QUERY", "FULL_SNAPSHOT", "SNAPSHOT_ABSENT" -> 2;
            case SNAPSHOT_SOURCE -> 1;
            default -> 0;
        };
    }

    private static Map<String, AccountGroupsReportedEvent.Group> normalizedGroups(
            List<AccountGroupsReportedEvent.Group> groups) {
        Map<String, AccountGroupsReportedEvent.Group> normalized = new TreeMap<>();
        if (groups == null) {
            return normalized;
        }
        for (AccountGroupsReportedEvent.Group group : groups) {
            if (group == null) {
                continue;
            }
            String groupJid = normalizeJid(group.groupJid());
            if (groupJid != null) {
                normalized.putIfAbsent(groupJid, group);
            }
        }
        return normalized;
    }

    private static int role(Boolean admin) {
        if (admin == null) {
            return 0;
        }
        return admin ? 2 : 1;
    }

    private static Long toEpochMillis(Long epochSeconds) {
        return epochSeconds == null ? null : Math.multiplyExact(epochSeconds, 1_000L);
    }

    private static Long requiredTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return tenantId;
    }

    private static void validateParticipantFactTenant(Long tenantId, Long factTenantId) {
        if (!Objects.equals(tenantId, factTenantId)) {
            throw new BusinessException(ErrorCode.VALIDATION, "群成员事件租户不一致");
        }
    }

    private static String participantGroupJid(String value) {
        String groupJid = normalizeJid(value);
        if (groupJid == null || !groupJid.toLowerCase(Locale.ROOT).endsWith("@g.us")) {
            throw new BusinessException(ErrorCode.VALIDATION, "群成员事件 groupJid 非法");
        }
        return groupJid.toLowerCase(Locale.ROOT);
    }

    private static ParticipantIdentity participantIdentity(String value) {
        String participantJid = normalizeJid(value);
        if (participantJid == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群成员事件 participantJid 为空");
        }
        participantJid = participantJid.toLowerCase(Locale.ROOT);
        int at = participantJid.indexOf('@');
        int device = participantJid.indexOf(':');
        if (device >= 0 && at > device) {
            participantJid = participantJid.substring(0, device) + participantJid.substring(at);
        }
        if (participantJid.endsWith("@s.whatsapp.net")) {
            return new ParticipantIdentity(participantJid, null);
        }
        if (participantJid.endsWith("@lid")) {
            return new ParticipantIdentity(null, participantJid);
        }
        throw new BusinessException(ErrorCode.VALIDATION, "群成员事件 participantJid 非法");
    }

    private static ParticipantPresenceWrite participantObservation(
            Long tenantId,
            GroupParticipantObservation observation,
            long now) {
        validateParticipantFactTenant(tenantId, observation.tenantId());
        String participantJid = blankToNull(observation.participantJid()) == null
                ? observation.targetJid() : observation.participantJid();
        ParticipantIdentity identity = participantIdentity(participantJid);
        if (observation.source() == null || observation.observedAt() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群成员观察来源或事实时间非法");
        }
        String source = participantObservationSource(observation);
        return new ParticipantPresenceWrite(
                null,
                participantGroupJid(observation.groupJid()),
                identity.pnJid(),
                identity.lidJid(),
                blankToNull(observation.phone()),
                observation.inGroup() ? 1 : 2,
                source,
                clamp(blankToNull(observation.sourceEventId()), EVENT_ID_MAX_LENGTH),
                observation.observedAt(),
                now,
                null,
                null,
                null,
                null,
                observation.inGroup() ? (observation.admin() ? 2 : 1) : null,
                observation.inGroup() ? source : null,
                observation.inGroup() ? observation.observedAt() : null,
                observation.inGroup()
                        ? clamp(blankToNull(observation.sourceEventId()), EVENT_ID_MAX_LENGTH)
                        : null,
                null,
                null,
                null,
                null,
                null);
    }

    private static String participantObservationSource(
            GroupParticipantObservation observation) {
        return switch (observation.source()) {
            case ROLE_EVENT -> observation.admin() ? "WGP2_PROMOTE" : "WGP2_DEMOTE";
            case MEMBER_QUERY -> "GROUP_MEMBER_QUERY";
            default -> observation.source().name();
        };
    }

    private static long requiredFactTime(Long value) {
        if (value == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群成员事件缺少事实时间");
        }
        return value;
    }

    private static String normalizedExitType(String value) {
        String exitType = blankToNull(value);
        if (exitType == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群成员事件缺少退出方式");
        }
        exitType = exitType.toUpperCase(Locale.ROOT);
        if (!Set.of("LEFT", "REMOVED", "UNKNOWN").contains(exitType)) {
            throw new BusinessException(ErrorCode.VALIDATION, "群成员事件退出方式非法");
        }
        return exitType;
    }

    private static String departurePresenceSource(String sourceType, String exitType) {
        if ("WGP2_NOTIFICATION".equalsIgnoreCase(sourceType)
                && "REMOVED".equals(exitType)) {
            return "UNKNOWN_EXIT_EVENT";
        }
        return switch (exitType) {
            case "REMOVED" -> "REMOVE_EVENT";
            case "LEFT" -> "LEAVE_EVENT";
            default -> "UNKNOWN_EXIT_EVENT";
        };
    }

    private static ParticipantPresenceWrite snapshotParticipant(
            Long groupId,
            String groupJid,
            GroupParticipantResult participant,
            long snapshotAt,
            String snapshotVersion,
            long now) {
        if (participant == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "完整群成员快照包含空成员");
        }
        ParticipantIdentity identity = participantIdentity(participant.jid());
        String identityKey = identity.pnJid() == null ? identity.lidJid() : identity.pnJid();
        String eventId = clamp(
                "snapshot:" + snapshotVersion + ":" + identityKey,
                EVENT_ID_MAX_LENGTH);
        int role = Boolean.TRUE.equals(participant.owner())
                ? 3 : Boolean.TRUE.equals(participant.admin()) ? 2 : 1;
        return new ParticipantPresenceWrite(
                groupId,
                groupJid,
                identity.pnJid(),
                identity.lidJid(),
                normalizedPhone(participant.phone()),
                1,
                "FULL_SNAPSHOT",
                eventId,
                snapshotAt,
                now,
                null,
                null,
                null,
                null,
                role,
                "FULL_SNAPSHOT",
                snapshotAt,
                eventId,
                snapshotVersion,
                null,
                null,
                null,
                null);
    }

    private static String normalizedPhone(String value) {
        String phone = blankToNull(value);
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : digits;
    }

    private static String participantIdentityKey(ParticipantPresenceWrite row) {
        return row.pnJid() == null ? row.lidJid() : row.pnJid();
    }

    private static String normalizeJid(String value) {
        return blankToNull(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String clamp(String value, int maxLength) {
        return value == null || value.length() <= maxLength
                ? value : value.substring(0, maxLength);
    }

    private record ParticipantIdentity(String pnJid, String lidJid) {
    }

    private record ParsedBaseline(
            Set<String> groupJids,
            Map<String, String> subjects,
            boolean valid) {
    }

    private record BaselineEvidence(
            int state,
            int completeness,
            Long capturedAt,
            Integer groupCount,
            Set<String> groupJids,
            Map<String, String> subjects,
            boolean classifiable) {
    }

    private record Classification(
            Integer wasInInitialBaseline,
            String baselineSubjectSnapshot,
            Long firstPostControlObservedAt) {

        private static Classification unknown() {
            return new Classification(null, null, null);
        }
    }
}
