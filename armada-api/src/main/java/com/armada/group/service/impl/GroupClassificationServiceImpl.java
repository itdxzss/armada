package com.armada.group.service.impl;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.mapper.GroupClassificationMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Existing;
import com.armada.group.model.enums.GroupClassification;
import com.armada.group.model.enums.GroupClassificationSource;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.CanonicalGroupClassificationRow;
import com.armada.group.model.vo.CanonicalGroupClassificationWrite;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.model.vo.GroupClassificationPlan;
import com.armada.group.model.vo.GroupPostControlClassificationCandidate;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.enums.OwnerIdentityKind;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScopeAccess;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 租户内 canonical 群首次唯一分类实现。 */
@Service
public class GroupClassificationServiceImpl implements GroupClassificationService {

    private final AccountGroupCurrentSnapshotMapper currentSnapshotMapper;
    private final GroupLinkMapper groupLinkMapper;
    private final GroupClassificationMapper classificationMapper;
    private final GroupLinkRegistryService registryService;
    private final GroupMetadataSyncTaskService metadataSyncTaskService;

    /**
     * 创建群分类服务。
     *
     * @param currentSnapshotMapper 新模型账号 baseline 与绑定数据访问
     * @param groupLinkMapper 群入口数据访问
     * @param classificationMapper canonical 群首次分类数据访问
     * @param registryService 群组池登记服务
     * @param metadataSyncTaskService 群详情同步任务服务
     */
    public GroupClassificationServiceImpl(
            AccountGroupCurrentSnapshotMapper currentSnapshotMapper,
            GroupLinkMapper groupLinkMapper,
            GroupClassificationMapper classificationMapper,
            GroupLinkRegistryService registryService,
            GroupMetadataSyncTaskService metadataSyncTaskService) {
        this.currentSnapshotMapper = currentSnapshotMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.classificationMapper = classificationMapper;
        this.registryService = registryService;
        this.metadataSyncTaskService = metadataSyncTaskService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void captureHistoricalBaseline(
            List<GroupClassificationCandidate> groups,
            ProtocolBackend observedBackend,
            long now) {
        captureHistoricalBaseline(groups, observedBackend, now, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupClassificationPlan stageHistoricalBaseline(
            List<GroupClassificationCandidate> groups,
            ProtocolBackend observedBackend,
            long now) {
        return captureHistoricalBaseline(groups, observedBackend, now, false);
    }

    private GroupClassificationPlan captureHistoricalBaseline(
            List<GroupClassificationCandidate> groups,
            ProtocolBackend observedBackend,
            long now,
            boolean enqueueTasks) {
        Map<String, GroupClassificationCandidate> candidates = normalized(groups);
        Map<String, String> unresolved = new LinkedHashMap<>();
        for (GroupClassificationCandidate group : candidates.values()) {
            if (group.groupLinkId() == null) {
                unresolved.put(group.groupJid(), group.groupName());
            }
        }
        Map<String, Long> resolved = unresolved.isEmpty()
                ? Map.of()
                : registryService.registerAccountObservedGroups(unresolved, observedBackend, now);
        List<ClassificationRequest> requests = candidates.values().stream()
                .map(group -> new ClassificationRequest(
                        group.groupLinkId() == null
                                ? resolved.get(group.groupJid()) : group.groupLinkId(),
                        group.groupJid(),
                        GroupClassification.HISTORICAL,
                        GroupClassificationSource.BASELINE_CAPTURED,
                        GroupMetadataSyncTrigger.BASELINE_CAPTURED,
                        now))
                .toList();
        return persistClassifications(requests, now, enqueueTasks);
    }

    private GroupClassificationPlan persistClassifications(
            List<ClassificationRequest> incomingRequests,
            long now,
            boolean enqueueTasks) {
        Map<String, ClassificationRequest> requestsByJid = stableRequests(incomingRequests);
        if (requestsByJid.isEmpty()) {
            return GroupClassificationPlan.empty();
        }
        Set<Long> activeIds = activeGroupLinkIds(requestsByJid.values());
        requestsByJid.entrySet().removeIf(
                entry -> !activeIds.contains(entry.getValue().groupLinkId()));
        if (requestsByJid.isEmpty()) {
            return GroupClassificationPlan.empty();
        }
        Long tenantId = requiredTenantId();
        List<String> groupJids = List.copyOf(requestsByJid.keySet());
        classificationMapper.ensureCanonicalGroups(tenantId, groupJids, now);
        Map<String, Integer> actualByJid = classificationMapper.selectByGroupJids(
                        tenantId, groupJids).stream()
                .collect(Collectors.toMap(
                        CanonicalGroupClassificationRow::groupJid,
                        CanonicalGroupClassificationRow::classificationCode,
                        (left, right) -> left));
        if (actualByJid.size() != requestsByJid.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "canonical 群分类读取结果不完整");
        }
        List<CanonicalGroupClassificationWrite> writes = new ArrayList<>();
        Map<Long, GroupMetadataSyncTrigger> newlyPersisted = new TreeMap<>();
        for (Map.Entry<String, ClassificationRequest> entry : requestsByJid.entrySet()) {
            Integer actualCode = actualByJid.get(entry.getKey());
            if (GroupClassification.fromCode(actualCode) != GroupClassification.UNCLASSIFIED) {
                continue;
            }
            ClassificationRequest request = entry.getValue();
            writes.add(new CanonicalGroupClassificationWrite(
                    request.groupJid(),
                    request.classification().code(),
                    request.source().code(),
                    request.classifiedAt()));
            newlyPersisted.put(request.groupLinkId(), request.trigger());
            actualByJid.put(request.groupJid(), request.classification().code());
        }
        if (!writes.isEmpty()) {
            int affected = classificationMapper.classifyFirstBatch(tenantId, writes, now);
            if (affected != writes.size()) {
                throw new BusinessException(ErrorCode.CONFLICT, "canonical 群批量首次分类结果非法");
            }
        }
        Map<Long, GroupMetadataSyncTrigger> desired = actualClassifications(
                requestsByJid, actualByJid);
        if (enqueueTasks && !newlyPersisted.isEmpty()) {
            metadataSyncTaskService.enqueueClassifications(newlyPersisted, now);
        }
        return new GroupClassificationPlan(desired, newlyPersisted);
    }

    private Set<Long> activeGroupLinkIds(Iterable<ClassificationRequest> requests) {
        List<Long> requestedIds = new ArrayList<>();
        requests.forEach(request -> {
            if (request.groupLinkId() != null) {
                requestedIds.add(request.groupLinkId());
            }
        });
        if (requestedIds.isEmpty()) {
            return Set.of();
        }
        // canonical 行可能尚不存在；仅按 group_jid 排序的并发 INSERT 仍会因唯一键间隙锁
        // 形成环。先按已经存在的兼容句柄 PRIMARY 顺序锁定，同群候选便会在建档前串行化。
        return groupLinkMapper.selectActiveByIdsForUpdate(requestedIds.stream()
                        .distinct()
                        .sorted()
                        .toList(), DataScopeAccess.requireCurrent()).stream()
                .map(com.armada.group.model.entity.GroupLink::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private Map<Long, GroupMetadataSyncTrigger> actualClassifications(
            Map<String, ClassificationRequest> requestsByJid,
            Map<String, Integer> actualByJid) {
        Map<Long, GroupMetadataSyncTrigger> desired = new TreeMap<>();
        requestsByJid.forEach((groupJid, request) -> desired.put(
                request.groupLinkId(), triggerFor(actualByJid.get(groupJid))));
        return desired;
    }

    private static GroupMetadataSyncTrigger triggerFor(Integer classificationCode) {
        return switch (GroupClassification.fromCode(classificationCode)) {
            case HISTORICAL -> GroupMetadataSyncTrigger.BASELINE_CAPTURED;
            case POST_CONTROL -> GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED;
            case UNCLASSIFIED -> throw new BusinessException(
                    ErrorCode.CONFLICT, "canonical 群可靠候选写后仍未分类");
        };
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void classifyVisibleGroups(
            Long accountId,
            List<GroupClassificationCandidate> groups,
            long now) {
        classifyVisibleGroups(accountId, groups, now, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupClassificationPlan stageVisibleGroups(
            Long accountId,
            List<GroupClassificationCandidate> groups,
            long now) {
        return classifyVisibleGroups(accountId, groups, now, false);
    }

    private GroupClassificationPlan classifyVisibleGroups(
            Long accountId,
            List<GroupClassificationCandidate> groups,
            long now,
            boolean enqueueTasks) {
        Context context = currentSnapshotMapper.selectContext(accountId);
        if (!capturedBaseline(context)) {
            return GroupClassificationPlan.empty();
        }
        Map<String, GroupClassificationCandidate> candidates = normalized(groups);
        if (candidates.isEmpty()) {
            return GroupClassificationPlan.empty();
        }
        WhatsappJids.OwnerIdentity self = WhatsappJids.ownerIdentity(context.wsPhone(), "pn");
        if (self.kind() != OwnerIdentityKind.PN || self.ownerJid() == null) {
            return GroupClassificationPlan.empty();
        }
        Map<String, Existing> existingByJid = currentSnapshotMapper.selectExisting(
                        accountId, self.ownerJid(), List.copyOf(candidates.keySet())).stream()
                .collect(Collectors.toMap(Existing::groupJid, Function.identity(), (left, right) -> left));
        List<ClassificationRequest> requests = new ArrayList<>();
        for (GroupClassificationCandidate group : candidates.values()) {
            if (group.groupLinkId() == null) {
                continue;
            }
            Existing existing = existingByJid.get(group.groupJid());
            if (existing != null && Integer.valueOf(1).equals(existing.wasInInitialBaseline())) {
                requests.add(new ClassificationRequest(
                        group.groupLinkId(),
                        group.groupJid(),
                        GroupClassification.HISTORICAL,
                        GroupClassificationSource.BASELINE_CAPTURED,
                        GroupMetadataSyncTrigger.BASELINE_CAPTURED,
                        context.baselineCapturedAt()));
            } else if ((existing != null
                    && (Integer.valueOf(0).equals(existing.wasInInitialBaseline())
                    || existing.firstPostControlObservedAt() != null))
                    || (existing == null && now > context.baselineCapturedAt())) {
                long classifiedAt = existing != null
                        && existing.firstPostControlObservedAt() != null
                        ? existing.firstPostControlObservedAt() : now;
                requests.add(new ClassificationRequest(
                        group.groupLinkId(),
                        group.groupJid(),
                        GroupClassification.POST_CONTROL,
                        GroupClassificationSource.POST_CONTROL_DISCOVERED,
                        GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED,
                        classifiedAt));
            }
        }
        return persistClassifications(requests, now, enqueueTasks);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupClassificationPlan stagePostControlEvidence(
            List<GroupPostControlClassificationCandidate> groups,
            long now) {
        if (groups == null || groups.isEmpty()) {
            return GroupClassificationPlan.empty();
        }
        List<ClassificationRequest> requests = groups.stream()
                .filter(java.util.Objects::nonNull)
                .map(group -> new ClassificationRequest(
                        group.groupLinkId(),
                        normalizeJid(group.groupJid()),
                        GroupClassification.POST_CONTROL,
                        GroupClassificationSource.POST_CONTROL_DISCOVERED,
                        GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED,
                        group.observedAt()))
                .toList();
        return persistClassifications(requests, now, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void classifyMembershipAdded(
            Long accountId,
            GroupClassificationCandidate group,
            long occurredAt,
            long now) {
        if (group == null || group.groupLinkId() == null) {
            return;
        }
        Context context = currentSnapshotMapper.selectContext(accountId);
        if (!capturedBaseline(context) || occurredAt <= context.baselineCapturedAt()) {
            return;
        }
        WhatsappJids.OwnerIdentity self = WhatsappJids.ownerIdentity(context.wsPhone(), "pn");
        if (self.kind() != OwnerIdentityKind.PN || self.ownerJid() == null) {
            return;
        }
        Existing existing = currentSnapshotMapper.selectSelfMembershipExisting(
                accountId, self.ownerJid(), normalizeJid(group.groupJid()));
        if (existing != null && Integer.valueOf(1).equals(existing.wasInInitialBaseline())) {
            return;
        }
        ClassificationRequest request = new ClassificationRequest(
                group.groupLinkId(),
                normalizeJid(group.groupJid()),
                GroupClassification.POST_CONTROL,
                GroupClassificationSource.POST_CONTROL_DISCOVERED,
                GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED,
                occurredAt);
        persistClassifications(List.of(request), now, true);
    }

    private static boolean capturedBaseline(Context context) {
        return context != null
                && Integer.valueOf(AccountGroupBaselineStateCode.CAPTURED).equals(context.baselineState())
                && Integer.valueOf(1).equals(context.baselineCompleteness())
                && context.baselineCapturedAt() != null;
    }

    private static Map<String, GroupClassificationCandidate> normalized(
            List<GroupClassificationCandidate> groups) {
        Map<String, GroupClassificationCandidate> normalized = new LinkedHashMap<>();
        if (groups == null) {
            return normalized;
        }
        for (GroupClassificationCandidate group : groups) {
            if (group == null) {
                continue;
            }
            String groupJid = normalizeJid(group.groupJid());
            if (groupJid != null) {
                normalized.putIfAbsent(groupJid, new GroupClassificationCandidate(
                        group.groupLinkId(), groupJid, blankToNull(group.groupName())));
            }
        }
        return normalized;
    }

    private static Map<String, ClassificationRequest> stableRequests(
            List<ClassificationRequest> requests) {
        Map<String, ClassificationRequest> stable = new TreeMap<>();
        if (requests == null) {
            return stable;
        }
        for (ClassificationRequest request : requests) {
            if (request == null
                    || request.groupLinkId() == null
                    || request.groupJid() == null) {
                continue;
            }
            stable.merge(request.groupJid(), request, GroupClassificationServiceImpl::preferred);
        }
        return stable;
    }

    private static ClassificationRequest preferred(
            ClassificationRequest left,
            ClassificationRequest right) {
        if (left.classification() == GroupClassification.HISTORICAL) {
            return left;
        }
        return right.classification() == GroupClassification.HISTORICAL ? right : left;
    }

    private static Long requiredTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return tenantId;
    }

    private static String normalizeJid(String value) {
        return blankToNull(value);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /** 单个可靠候选及仍需兼容的群入口句柄。 */
    private record ClassificationRequest(
            Long groupLinkId,
            String groupJid,
            GroupClassification classification,
            GroupClassificationSource source,
            GroupMetadataSyncTrigger trigger,
            long classifiedAt) {
    }
}
