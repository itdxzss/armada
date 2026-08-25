package com.armada.group.service.impl;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Existing;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.GroupId;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.LegacyGroupHandle;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.LegacyGroupReference;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.MembershipExitWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantIdentityMergeWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantIdentityRow;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantPresenceWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.SyncStateWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Write;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.model.dto.WhatsappGroupIdentityMergeFact;
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

    private static final int SUBJECT_MAX_LENGTH = 255;
    private static final int DESCRIPTION_MAX_LENGTH = 1024;
    private static final int AVATAR_URL_MAX_LENGTH = 512;
    private static final int EVENT_ID_MAX_LENGTH = 255;
    private static final int SNAPSHOT_VERSION_MAX_LENGTH = 64;
    private static final int PARTICIPANT_WRITE_BATCH_SIZE = 200;
    private static final int PRESENCE_IN_GROUP = 1;
    private static final String SNAPSHOT_SOURCE = "GROUP_SNAPSHOT";
    private static final String PRECISE_ADD_SOURCE = "WGP2_ADD";
    private final AccountGroupCurrentSnapshotMapper mapper;

    public AccountGroupCurrentSnapshotPersistenceImpl(AccountGroupCurrentSnapshotMapper mapper) {
        this.mapper = mapper;
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
        boolean capturingBaseline = baseline.state() == AccountGroupBaselineStateCode.PENDING;
        List<Existing> discoveredRows = mapper.selectExisting(
                accountId, self.ownerJid(), visibleJids);
        Map<String, Existing> discoveredByJid = discoveredRows.stream()
                .filter(row -> visible.containsKey(row.groupJid()))
                .collect(Collectors.toMap(
                        Existing::groupJid,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        String normalizedEventId = clamp(blankToNull(eventId), EVENT_ID_MAX_LENGTH);
        List<Write> discoveryRows = visible.entrySet().stream()
                .map(entry -> snapshotWrite(
                        entry, discoveredByJid.get(entry.getKey()), self,
                        baseline, capturingBaseline, syncAt, now, normalizedEventId))
                .toList();
        List<Write> missingGroupRows = discoveryRows.stream()
                .filter(row -> {
                    Existing existing = discoveredByJid.get(row.groupJid());
                    return existing == null || existing.deletedAt() != null;
                })
                .toList();
        List<Long> preexistingSnapshotGroupIds = discoveredRows.stream()
                .map(Existing::groupId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        lockGroupIds(tenantId, preexistingSnapshotGroupIds);
        if (!missingGroupRows.isEmpty()) {
            mapper.insertMissingGroups(tenantId, missingGroupRows);
        }

        Map<String, Long> visibleGroupIds = discoveredRows.stream()
                .filter(row -> visible.containsKey(row.groupJid()))
                .filter(row -> row.groupId() != null)
                .collect(Collectors.toMap(
                        Existing::groupJid,
                        Existing::groupId,
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<String> unresolvedVisibleJids = visibleJids.stream()
                .filter(groupJid -> !visibleGroupIds.containsKey(groupJid))
                .toList();
        if (!unresolvedVisibleJids.isEmpty()) {
            mapper.selectGroupIdsWithoutLock(tenantId, unresolvedVisibleJids)
                    .forEach(group -> visibleGroupIds.put(group.groupJid(), group.groupId()));
        }
        if (visibleGroupIds.size() != visibleJids.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群模型批量解析 groupId 不完整");
        }
        List<Long> snapshotGroupIds = java.util.stream.Stream.concat(
                        visibleGroupIds.values().stream(),
                        discoveredRows.stream()
                                .filter(row -> row.bindingId() != null)
                                .map(Existing::groupId))
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        Set<Long> prelockedGroupIds = Set.copyOf(preexistingSnapshotGroupIds);
        lockGroupIds(
                tenantId,
                snapshotGroupIds.stream()
                        .filter(groupId -> !prelockedGroupIds.contains(groupId))
                        .toList());

        // G 已按 PRIMARY 串行化；随后统一按 G→P→B 取得 current locks。
        List<Existing> existingRows = mapper.selectExistingAfterGroupLock(
                tenantId, accountId, self.ownerJid(), snapshotGroupIds);
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
                .filter(row -> !PRECISE_ADD_SOURCE.equals(row.presenceSource()))
                .map(Existing::groupJid)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Write> rows = visible.entrySet().stream()
                .map(entry -> snapshotWrite(
                        entry, existingByJid.get(entry.getKey()), self,
                        baseline, capturingBaseline, syncAt, now, normalizedEventId))
                .toList();

        if (!rows.isEmpty()) {
            Map<String, Long> groupIds = visibleGroupIds;
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
            mergeSplitParticipantIdentities(
                    tenantId,
                    rows.stream()
                            .map(AccountGroupCurrentSnapshotPersistenceImpl::snapshotIdentityCandidate)
                            .toList());
            mapper.upsertParticipants(rows);
        }

        if (snapshotComplete) {
            List<Existing> missingRows = existingRows.stream()
                    .filter(row -> row.bindingId() != null)
                    .filter(row -> row.participantId() != null)
                    .filter(row -> !visible.containsKey(row.groupJid()))
                    .filter(row -> snapshotWins(row, syncAt))
                    .toList();
            List<Long> missingParticipantIds = missingRows.stream()
                    .map(Existing::participantId)
                    .distinct()
                    .sorted()
                    .toList();
            if (!missingParticipantIds.isEmpty()) {
                mapper.markMissingParticipants(
                        missingParticipantIds, syncAt, normalizedEventId, now);
                mapper.clearMembershipActiveSinceForAcceptedExit(
                        tenantId,
                        new MembershipExitWrite(
                                accountId,
                                missingRows.stream()
                                .map(Existing::groupId)
                                .distinct()
                                .sorted()
                                .toList(),
                                SNAPSHOT_SOURCE,
                                syncAt,
                                now));
            }
        }

        if (!rows.isEmpty()) {
            mapper.upsertBindings(tenantId, accountId, rows);
        }

        mapper.upsertSyncState(syncState(
                context, baseline, capturingBaseline, visible.size(), snapshotComplete, syncAt, now));

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

    private Write snapshotWrite(
            Map.Entry<String, AccountGroupsReportedEvent.Group> entry,
            Existing existing,
            WhatsappJids.OwnerIdentity self,
            BaselineEvidence baseline,
            boolean capturingBaseline,
            long syncAt,
            long now,
            String normalizedEventId) {
        boolean acceptedPresence = snapshotWins(existing, syncAt);
        Classification classification = snapshotClassification(
                entry.getKey(), blankToNull(entry.getValue().subject()), existing,
                baseline, capturingBaseline, acceptedPresence, syncAt);
        AccountGroupsReportedEvent.Group group = entry.getValue();
        Long activeSince = acceptedPresence
                && (existing == null || existing.presenceStatus() == null
                || existing.presenceStatus() != PRESENCE_IN_GROUP)
                ? Long.valueOf(syncAt) : null;
        return new Write(
                null,
                entry.getKey(),
                clamp(blankToNull(group.avatarUrl()), AVATAR_URL_MAX_LENGTH),
                clamp(blankToNull(group.subject()), SUBJECT_MAX_LENGTH),
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
                classification.firstPostControlObservedAt(),
                group.adminOnlyEditInfo(),
                group.memberAddMode(),
                // null=本次未观察（保留旧值），空串=明确观察到空描述（写入）。
                group.descriptionObserved()
                        ? clamp(group.description(), DESCRIPTION_MAX_LENGTH) : null,
                group.joinApprovalMode(),
                group.ephemeralDurationSeconds());
    }

    /**
     * 写入账号自身的精确进群、离群或不在群事实。
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
        long now = System.currentTimeMillis();
        String normalizedEventId = clamp(blankToNull(eventId), EVENT_ID_MAX_LENGTH);
        Long groupId = resolveGroupIds(
                tenantId, List.of(normalizedGroupJid), now).get(normalizedGroupJid);
        Existing existing = mapper.selectSelfMembershipExistingAfterGroupLock(
                tenantId, accountId, self.ownerJid(), normalizedGroupJid);
        if (existing == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法锁定精确关系事件的群事实");
        }
        if (inGroup && existing.deletedAt() != null) {
            mapper.insertMissingGroups(tenantId, List.of(new Write(
                    null, normalizedGroupJid, null,
                    null, null, null, null, self.ownerJid(), self.ownerPhone(),
                    0, normalizedEventId, occurredAt, now, null, null, null, null, null, null,
                    null, null, null)));
            existing = mapper.selectSelfMembershipExistingAfterGroupLock(
                    tenantId, accountId, self.ownerJid(), normalizedGroupJid);
        }
        boolean accepted = presenceWins(existing, occurredAt, normalizedSource);
        boolean preciseAdd = PRECISE_ADD_SOURCE.equals(normalizedSource);
        BaselineEvidence baseline = baselineEvidenceForPreciseEvent(context);
        Classification classification = inGroup
                ? preciseEventClassification(existing, baseline, accepted, occurredAt)
                : Classification.unknown();
        Long activeSince = accepted && inGroup
                && (existing == null || existing.presenceStatus() == null
                || existing.presenceStatus() != PRESENCE_IN_GROUP)
                ? Long.valueOf(occurredAt)
                : preciseAddActiveSinceRepair(existing, inGroup, preciseAdd, occurredAt);
        ParticipantPresenceWrite row = new ParticipantPresenceWrite(
                existing.groupId(),
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

        mergeSplitParticipantIdentities(tenantId, List.of(row));
        mapper.upsertParticipantFacts(List.of(row));
        if (!inGroup) {
            mapper.clearMembershipActiveSinceForAcceptedExit(
                    tenantId, new MembershipExitWrite(
                            accountId, List.of(row.groupId()),
                            normalizedSource, occurredAt, now));
        }
        mapper.upsertSelfBinding(tenantId, accountId, row);
    }

    /**
     * 写入受控账号的成员当前状态和角色观察。
     *
     * <p>只有 WGP2_ADD 是账号自身的精确进群事实，可以补充进群起始时间；角色事件和成员查询
     * 仍只更新当前状态，不得伪造进群时间。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean applyControlledParticipantObservation(
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

        long now = System.currentTimeMillis();
        String normalizedEventId = clamp(blankToNull(eventId), EVENT_ID_MAX_LENGTH);
        Long groupId = resolveGroupIds(
                tenantId, List.of(normalizedGroupJid), now).get(normalizedGroupJid);
        Existing existing = mapper.selectSelfMembershipExistingAfterGroupLock(
                tenantId, accountId, self.ownerJid(), normalizedGroupJid);
        if (existing == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法锁定成员观察的群事实");
        }
        if (inGroup && existing.deletedAt() != null) {
            mapper.insertMissingGroups(tenantId, List.of(new Write(
                    null, normalizedGroupJid, null,
                    null, null, null, null, self.ownerJid(), self.ownerPhone(),
                    0, normalizedEventId, observedAt, now, null, null, null, null, null, null,
                    null, null, null)));
            existing = mapper.selectSelfMembershipExistingAfterGroupLock(
                    tenantId, accountId, self.ownerJid(), normalizedGroupJid);
        }
        boolean accepted = presenceWins(existing, observedAt, normalizedSource);
        boolean preciseAdd = PRECISE_ADD_SOURCE.equals(normalizedSource);
        boolean acceptedTransition = inGroup
                && accepted
                && (existing == null || existing.presenceStatus() == null
                || existing.presenceStatus() != PRESENCE_IN_GROUP);
        Long activeSince = preciseAdd && acceptedTransition
                ? Long.valueOf(observedAt)
                : preciseAddActiveSinceRepair(existing, inGroup, preciseAdd, observedAt);
        boolean newlyInGroup = acceptedTransition || activeSince != null;
        ParticipantPresenceWrite row = new ParticipantPresenceWrite(
                existing.groupId(),
                normalizedGroupJid,
                self.ownerJid(),
                null,
                self.ownerPhone(),
                inGroup ? 1 : 2,
                normalizedSource,
                normalizedEventId,
                observedAt,
                now,
                activeSince,
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
                activeSince,
                null);

        mergeSplitParticipantIdentities(tenantId, List.of(row));
        mapper.upsertParticipantFacts(List.of(row));
        if (!inGroup) {
            mapper.clearMembershipActiveSinceForAcceptedExit(
                    tenantId, new MembershipExitWrite(
                            accountId, List.of(row.groupId()),
                            normalizedSource, observedAt, now));
        }
        mapper.upsertSelfBinding(tenantId, accountId, row);
        return newlyInGroup;
    }

    /** 写入普通成员进群事实，不创建账号群关系。 */
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
                            normalizedPhone(fact.phone()),
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

    /** 写入普通成员退群事实，不创建账号群关系。 */
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
                            normalizedPhone(fact.phone()),
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

    /**
     * 把同一个人的 PN/LID 身份与号码补进同一行，不改在群态与角色。
     *
     * <p>协议 modify 事件只说明成员身份形态变了，没有观察到在群与否和角色。写进群/退群那条
     * 语句会把已知的在群态覆盖成未知，所以走单独的身份合并语句。</p>
     *
     * <p>写入前会按本次明确提供的 PN/LID 定点归并既有双行，再补齐身份，不改变未观察到的
     * presence 与 role。</p>
     *
     * @param facts 身份合并事实，两个身份至少各有一个才有合并意义
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyParticipantIdentityMerges(List<WhatsappGroupIdentityMergeFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        Long tenantId = requiredTenantId();
        long now = System.currentTimeMillis();
        List<ParticipantPresenceWrite> rows = facts.stream()
                .filter(Objects::nonNull)
                .map(fact -> {
                    validateParticipantFactTenant(tenantId, fact.tenantId());
                    return identityMergeRow(fact, now);
                })
                .toList();
        if (rows.isEmpty()) {
            return;
        }
        List<String> groupJids = rows.stream()
                .map(ParticipantPresenceWrite::groupJid)
                .distinct()
                .sorted()
                .toList();
        Map<String, Long> groupIds = resolveGroupIds(tenantId, groupJids, now);
        List<ParticipantPresenceWrite> resolvedRows = rows.stream()
                .map(row -> row.withGroupId(groupIds.get(row.groupJid())))
                .toList();
        mergeParticipantIdentities(tenantId, resolvedRows);
    }

    /** 身份合并只填 groupId/pnJid/lidJid/phone/now，其余列对应语句一律不写。 */
    private static ParticipantPresenceWrite identityMergeRow(
            WhatsappGroupIdentityMergeFact fact,
            long now) {
        // 只有一个身份就没有可合并的对象，落库只会凭空多出一行未知态成员。
        ParticipantIdentity pn = participantIdentity(fact.pnJid());
        ParticipantIdentity lid = participantIdentity(fact.lidJid());
        if (pn.pnJid() == null || lid.lidJid() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群成员身份合并需要 PN 与 LID 各一个");
        }
        return new ParticipantPresenceWrite(
                null,
                participantGroupJid(fact.groupJid()),
                pn.pnJid(),
                lid.lidJid(),
                normalizedPhone(fact.phone()),
                null, null, null,
                requiredFactTime(fact.eventAt()),
                now,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null);
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

    /**
     * 在单群资料上报写 PROFILE/P/B 前建立统一的旧句柄、群主键写入边界。
     *
     * <p>资料字段 patch 本身只按 JID 普通读取群主键，不能承担锁序；完整成员快照又会在之后才锁
     * {@code wa_group}。入口必须先走 GL→G(PRIMARY)，否则缺少建群时间的事件会形成
     * PROFILE→G，与账号群报告的 G→PROFILE 反向。</p>
     *
     * @param groupLinkId 本事件刚登记或复用的兼容群句柄，可空
     * @param groupJid WhatsApp 群 JID
     * @return 已锁定的 {@code wa_group.id}
     */
    @Transactional(rollbackFor = Exception.class)
    public Long lockGroupWriteBoundary(Long groupLinkId, String groupJid) {
        Long tenantId = requiredTenantId();
        String normalizedGroupJid = participantGroupJid(groupJid);
        if (groupLinkId != null) {
            List<Long> lockedIds = mapper.selectLegacyGroupHandleIdsByIdsForUpdate(
                    tenantId, List.of(groupLinkId));
            if (lockedIds.size() != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法锁定资料上报的旧群句柄");
            }
        }
        Long groupId = resolveGroupIds(
                tenantId, List.of(normalizedGroupJid), System.currentTimeMillis())
                .get(normalizedGroupJid);
        if (groupId == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法锁定资料上报的群写入边界");
        }
        return groupId;
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

    /**
     * 只填建群时间；已有值时不覆盖。
     *
     * <p>建群时间建群时定死、此后不变，与可变的资料字段不是同一生命周期，因此不进
     * fieldMask 也不参与版本比较——先到先得即可。</p>
     *
     * @param groupJid    群 JID
     * @param waCreatedAt WhatsApp 建群时间(epoch 毫秒)
     */
    @Transactional(rollbackFor = Exception.class)
    public void fillGroupCreatedAt(String groupJid, Long waCreatedAt) {
        if (waCreatedAt == null || waCreatedAt <= 0) {
            return;
        }
        Long tenantId = requiredTenantId();
        long now = System.currentTimeMillis();
        String normalizedJid = participantGroupJid(groupJid);
        Long groupId = resolveGroupIds(tenantId, List.of(normalizedJid), now).get(normalizedJid);
        if (groupId == null) {
            return;
        }
        mapper.fillGroupCreatedAt(groupId, waCreatedAt, now);
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
        upsertParticipantFactsInBatches(tenantId, rows);
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
        upsertParticipantFactsInBatches(tenantId, rows);
    }

    private Map<String, Long> resolveGroupIds(
            Long tenantId,
            List<String> groupJids,
            long now) {
        List<LegacyGroupHandle> legacyHandles = lockUnboundLegacyGroupHandles(
                tenantId, groupJids);
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
                            now, now, null, null, null, null, null, null,
                            null, null, null))
                    .toList();
            mapper.insertMissingGroups(tenantId, missingGroups);
            mapper.selectGroupIdsWithoutLock(tenantId, missingGroupJids)
                    .forEach(group -> groupIds.put(group.groupJid(), group.groupId()));
        }
        if (groupIds.size() != groupJids.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法解析成员事件的 groupId");
        }
        lockGroupIds(tenantId, groupIds.values().stream().sorted().toList());
        updatePrelockedLegacyGroupReferences(tenantId, legacyHandles, groupIds);
        return groupIds;
    }

    private List<LegacyGroupHandle> lockUnboundLegacyGroupHandles(
            Long tenantId,
            List<String> groupJids) {
        if (groupJids == null || groupJids.isEmpty()) {
            return List.of();
        }
        List<LegacyGroupHandle> handles = mapper.selectUnboundLegacyGroupHandlesWithoutLock(
                tenantId, groupJids);
        List<Long> handleIds = handles.stream()
                .map(LegacyGroupHandle::groupLinkId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (handleIds.isEmpty()) {
            return List.of();
        }
        List<Long> lockedIds = mapper.selectLegacyGroupHandleIdsByIdsForUpdate(
                tenantId, handleIds);
        if (lockedIds.size() != handleIds.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法锁定完整的旧群句柄写入边界");
        }
        return handles;
    }

    private void updatePrelockedLegacyGroupReferences(
            Long tenantId,
            List<LegacyGroupHandle> handles,
            Map<String, Long> groupIds) {
        if (handles == null || handles.isEmpty()) {
            return;
        }
        Map<Long, LegacyGroupReference> references = new TreeMap<>();
        for (LegacyGroupHandle handle : handles) {
            if (handle == null || handle.groupLinkId() == null) {
                continue;
            }
            Long groupId = groupIds.get(handle.groupJid());
            if (groupId != null) {
                references.putIfAbsent(
                        handle.groupLinkId(),
                        new LegacyGroupReference(handle.groupLinkId(), groupId));
            }
        }
        if (!references.isEmpty()) {
            mapper.updateSelectedLegacyGroupReferences(
                    tenantId, List.copyOf(references.values()));
        }
    }

    private void lockGroupIds(Long tenantId, List<Long> groupIds) {
        List<Long> stableIds = groupIds == null ? List.of() : groupIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (stableIds.isEmpty()) {
            return;
        }
        List<GroupId> locked = mapper.selectGroupIdsByIdsForUpdate(tenantId, stableIds);
        if (locked.size() != stableIds.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法锁定完整的群写入边界");
        }
    }

    private void upsertParticipantFactsInBatches(
            Long tenantId,
            List<ParticipantPresenceWrite> rows) {
        for (int start = 0; start < rows.size(); start += PARTICIPANT_WRITE_BATCH_SIZE) {
            List<ParticipantPresenceWrite> batch = rows.subList(
                    start, Math.min(start + PARTICIPANT_WRITE_BATCH_SIZE, rows.size()));
            mergeSplitParticipantIdentities(tenantId, batch);
            mapper.upsertParticipantFacts(batch);
        }
    }

    private void mergeParticipantIdentities(
            Long tenantId,
            List<ParticipantPresenceWrite> rows) {
        mergeSplitParticipantIdentities(tenantId, rows);
        mapper.mergeParticipantIdentities(rows);
    }

    /** 将同群 PN/LID 双行按明确身份或可信 phone 证据归并到 LID canonical 行。 */
    private void mergeSplitParticipantIdentities(
            Long tenantId,
            List<ParticipantPresenceWrite> rows) {
        Map<String, ParticipantPresenceWrite> candidates = new LinkedHashMap<>();
        for (ParticipantPresenceWrite row : rows) {
            String phone = normalizedPhone(row.phone());
            if (row.groupId() != null
                    && (row.pnJid() != null || row.lidJid() != null)
                    && ((row.pnJid() != null && row.lidJid() != null) || phone != null)) {
                String key = participantIdentityCandidateKey(row, phone);
                ParticipantPresenceWrite previous = candidates.putIfAbsent(key, row);
                if (previous != null && identitiesConflict(previous, row)) {
                    throw new BusinessException(
                            ErrorCode.CONFLICT, "同群相同 phone 对应多个成员身份");
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        List<ParticipantPresenceWrite> candidateRows = List.copyOf(candidates.values());
        List<ParticipantIdentityRow> existing = mapper.selectParticipantIdentityRowsForUpdate(
                tenantId, candidateRows);
        Map<String, ParticipantIdentityRow> byPn = new LinkedHashMap<>();
        Map<String, ParticipantIdentityRow> byLid = new LinkedHashMap<>();
        Map<String, ParticipantIdentityRow> byPhone = new LinkedHashMap<>();
        for (ParticipantIdentityRow row : existing) {
            if (row.pnJid() != null) {
                byPn.put(identityRowKey(row.groupId(), row.pnJid()), row);
            }
            if (row.lidJid() != null) {
                byLid.put(identityRowKey(row.groupId(), row.lidJid()), row);
            }
            String phone = normalizedPhone(row.phone());
            if (phone != null) {
                ParticipantIdentityRow previous = byPhone.putIfAbsent(
                        phoneRowKey(row.groupId(), phone), row);
                if (previous != null && !Objects.equals(previous.id(), row.id())) {
                    throw new BusinessException(
                            ErrorCode.CONFLICT, "同群 phone 唯一身份约束不完整");
                }
            }
        }
        int merged = 0;
        for (ParticipantPresenceWrite candidate : candidateRows) {
            String phone = normalizedPhone(candidate.phone());
            ParticipantIdentityRow phoneMatch = phone == null ? null : byPhone.get(
                    phoneRowKey(candidate.groupId(), phone));
            validatePhoneIdentityEvidence(candidate, phoneMatch);
            ParticipantIdentityRow duplicate = candidate.pnJid() == null ? null : byPn.get(
                    identityRowKey(candidate.groupId(), candidate.pnJid()));
            ParticipantIdentityRow canonical = candidate.lidJid() == null ? null : byLid.get(
                    identityRowKey(candidate.groupId(), candidate.lidJid()));
            if (duplicate == null && phoneMatch != null && phoneMatch.pnJid() != null) {
                duplicate = phoneMatch;
            }
            if (canonical == null && phoneMatch != null && phoneMatch.lidJid() != null) {
                canonical = phoneMatch;
            }
            if (duplicate == null || canonical == null
                    || Objects.equals(duplicate.id(), canonical.id())) {
                continue;
            }
            validatePhoneIdentityEvidence(candidate, duplicate);
            validatePhoneIdentityEvidence(candidate, canonical);
            if (duplicate.lidJid() != null || canonical.pnJid() != null) {
                throw new BusinessException(
                        ErrorCode.CONFLICT, "群成员 PN/LID 身份映射与既有完整身份冲突");
            }
            String pnJid = candidate.pnJid() == null ? duplicate.pnJid() : candidate.pnJid();
            String lidJid = candidate.lidJid() == null ? canonical.lidJid() : candidate.lidJid();
            mergeSplitParticipantIdentity(
                    tenantId, candidate.groupId(), pnJid, lidJid, phone, candidate.now(),
                    canonical.id(), duplicate.id());
            merged++;
        }
        if (merged > 0) {
            log.info("群成员 PN/LID 双行已定点归并 count={}", merged);
        }
    }

    private void mergeSplitParticipantIdentity(
            Long tenantId,
            Long groupId,
            String pnJid,
            String lidJid,
            String phone,
            long now,
            Long canonicalId,
            Long duplicateId) {
        if (phone == null) {
            phone = normalizedPhone(pnJid);
        }
        ParticipantIdentityMergeWrite merge = new ParticipantIdentityMergeWrite(
                tenantId, groupId, canonicalId, duplicateId,
                pnJid, lidJid, phone, now);
        int factRows = mapper.mergeSplitParticipantFacts(merge);
        mapper.repointSplitParticipantBindings(merge);
        int deletedRows = mapper.deleteSplitParticipantDuplicate(merge);
        int identityRows = mapper.completeSplitParticipantIdentity(merge);
        if (factRows != 1 || deletedRows != 1 || identityRows != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "群成员 PN/LID 双行归并状态冲突");
        }
    }

    private static String participantIdentityCandidateKey(
            ParticipantPresenceWrite row,
            String phone) {
        if (phone != null) {
            return phoneRowKey(row.groupId(), phone);
        }
        return identityRowKey(row.groupId(), row.pnJid()) + '\u0000' + row.lidJid();
    }

    private static boolean identitiesConflict(
            ParticipantPresenceWrite left,
            ParticipantPresenceWrite right) {
        return left.pnJid() != null && right.pnJid() != null
                && !left.pnJid().equals(right.pnJid())
                || left.lidJid() != null && right.lidJid() != null
                && !left.lidJid().equals(right.lidJid());
    }

    private static void validatePhoneIdentityEvidence(
            ParticipantPresenceWrite candidate,
            ParticipantIdentityRow phoneMatch) {
        if (phoneMatch == null) {
            return;
        }
        if (candidate.pnJid() != null && phoneMatch.pnJid() != null
                && !candidate.pnJid().equals(phoneMatch.pnJid())) {
            throw new BusinessException(ErrorCode.CONFLICT, "同群 phone 与既有 PN 身份冲突");
        }
        if (candidate.lidJid() != null && phoneMatch.lidJid() != null
                && !candidate.lidJid().equals(phoneMatch.lidJid())) {
            throw new BusinessException(ErrorCode.CONFLICT, "同群 phone 与既有 LID 身份冲突");
        }
        String candidatePhone = normalizedPhone(candidate.phone());
        String existingPhone = normalizedPhone(phoneMatch.phone());
        if (candidatePhone != null && existingPhone != null
                && !candidatePhone.equals(existingPhone)) {
            throw new BusinessException(ErrorCode.CONFLICT, "群成员身份与既有 phone 冲突");
        }
    }

    private static String phoneRowKey(Long groupId, String phone) {
        return groupId + "\u0000phone\u0000" + phone;
    }

    private static String identityRowKey(Long groupId, String jid) {
        return groupId + "\u0000" + jid;
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
                    false);
        }
    }

    private SyncStateWrite syncState(
            Context context,
            BaselineEvidence baseline,
            boolean capturingBaseline,
            int visibleGroupCount,
            boolean snapshotComplete,
            long syncAt,
            long now) {
        boolean baselineCaptured = capturingBaseline && snapshotComplete;
        return new SyncStateWrite(
                context.accountId(),
                baselineCaptured ? AccountGroupBaselineStateCode.CAPTURED : baseline.state(),
                baselineCaptured ? 1 : baseline.completeness(),
                baselineCaptured ? Long.valueOf(syncAt) : baseline.capturedAt(),
                baselineCaptured ? Integer.valueOf(visibleGroupCount) : baseline.groupCount(),
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
            return new BaselineEvidence(state, 0, null, null, false);
        }
        if (context.baselineCapturedAt() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "已拍 baseline 缺少 capturedAt");
        }
        int completeness = context.baselineCompleteness() == null
                ? 0 : context.baselineCompleteness();
        Integer groupCount = context.baselineGroupCount();
        if (completeness == 1 && groupCount == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "完整 baseline 缺少群数量");
        }
        return new BaselineEvidence(
                state,
                completeness,
                context.baselineCapturedAt(),
                groupCount,
                completeness == 1);
    }

    private static Classification snapshotClassification(
            String groupJid,
            String subject,
            Existing existing,
            BaselineEvidence baseline,
            boolean capturingBaseline,
            boolean acceptedPresence,
            long syncAt) {
        if (!acceptedPresence) {
            return Classification.unknown();
        }
        if (capturingBaseline) {
            return new Classification(1, clamp(subject, SUBJECT_MAX_LENGTH), null);
        }
        if (existing != null && existing.bindingId() != null) {
            return Classification.unknown();
        }
        if (baseline.classifiable()
                && baseline.capturedAt() != null
                && syncAt > baseline.capturedAt()) {
            return new Classification(0, null, syncAt);
        }
        return Classification.unknown();
    }

    private static Classification preciseEventClassification(
            Existing existing,
            BaselineEvidence baseline,
            boolean acceptedPresence,
            long occurredAt) {
        if (!acceptedPresence
                || existing != null && existing.bindingId() != null
                || !baseline.classifiable()
                || baseline.capturedAt() == null
                || occurredAt <= baseline.capturedAt()) {
            return Classification.unknown();
        }
        return new Classification(0, null, occurredAt);
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

    /**
     * 当前仍在群且尚无连续关系起点时，允许迟到的精确 ADD 只补起点，不回退当前 presence。
     */
    private static Long preciseAddActiveSinceRepair(
            Existing existing,
            boolean inGroup,
            boolean preciseAdd,
            long observedAt) {
        if (!preciseAdd || !inGroup || existing == null
                || existing.presenceStatus() == null
                || existing.presenceStatus() != PRESENCE_IN_GROUP
                || existing.membershipActiveSinceAt() != null) {
            return null;
        }
        if (PRECISE_ADD_SOURCE.equals(existing.presenceSource())
                && existing.presenceObservedAt() != null) {
            return Math.max(existing.presenceObservedAt(), observedAt);
        }
        return observedAt;
    }

    private static int sourcePriority(String source) {
        if (source == null) {
            return 0;
        }
        return switch (source) {
            case "WGP2_REMOVE", "WGP2_LEAVE", "REMOVE_EVENT", "LEAVE_EVENT",
                    "UNKNOWN_EXIT_EVENT", "GROUP_SNAPSHOT_NOT_JOINED" -> 5;
            case "WGP2_PROMOTE", "WGP2_DEMOTE" -> 4;
            case PRECISE_ADD_SOURCE, "ADD_EVENT" -> 3;
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
            // 只判后缀不够：拼了两遍后缀的 "号码@s.whatsapp.net@s.whatsapp.net" 同样以它结尾，
            // 却会在 pn_jid 上开出一行永远匹配不上的身份（绑定按 pn_jid 等值关联）。
            // PN 的用户部分必然是纯数字，据此把畸形值挡在落库之前。
            String user = participantJid.substring(
                    0, participantJid.length() - "@s.whatsapp.net".length());
            if (user.isEmpty() || !user.chars().allMatch(Character::isDigit)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION, "群成员事件 participantJid 非法: " + participantJid);
            }
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
                normalizedPhone(observation.phone()),
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
        ParticipantIdentity identity = mergedIdentity(participant);
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

    /** 账号群快照只提供 self PN+phone；归并器据此处理既有 LID-only 同人行。 */
    private static ParticipantPresenceWrite snapshotIdentityCandidate(Write row) {
        return new ParticipantPresenceWrite(
                row.groupId(), row.groupJid(), row.pnJid(), null, row.phone(),
                null, null, row.eventId(), row.syncAt(), row.now(),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null);
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

    /**
     * 合并协议给出的两种成员身份。
     *
     * <p>WhatsApp 群成员列表已逐步只返回 LID，此时仅按主标识解析会丢掉 PN 身份，
     * 导致同一个人在 PN 与 LID 两行并存。协议另行给出号码时 adapter 会还原 PN JID，
     * 这里把它补进同一行，使两个身份落在一条成员记录上（群组数据模型设计 §3.2 硬不变量 3）。</p>
     *
     * @param participant 协议成员结果
     * @return 合并后的成员身份
     */
    private static ParticipantIdentity mergedIdentity(GroupParticipantResult participant) {
        ParticipantIdentity identity = participantIdentity(participant.jid());
        if (identity.pnJid() != null || participant.pnJid() == null) {
            return identity;
        }
        ParticipantIdentity restored = participantIdentity(participant.pnJid());
        return new ParticipantIdentity(restored.pnJid(), identity.lidJid());
    }

    private record BaselineEvidence(
            int state,
            int completeness,
            Long capturedAt,
            Integer groupCount,
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
