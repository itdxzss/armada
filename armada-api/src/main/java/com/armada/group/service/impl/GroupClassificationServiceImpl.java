package com.armada.group.service.impl;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Existing;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.model.vo.GroupClassificationPlan;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.enums.OwnerIdentityKind;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScopeAccess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 历史群与账号上控后群固化分类实现。 */
@Service
public class GroupClassificationServiceImpl implements GroupClassificationService {

    private final AccountGroupCurrentSnapshotMapper currentSnapshotMapper;
    private final GroupLinkMapper groupLinkMapper;
    private final GroupLinkRegistryService registryService;
    private final GroupMetadataSyncTaskService metadataSyncTaskService;

    /**
     * 创建群分类服务。
     *
     * @param currentSnapshotMapper 新模型账号 baseline 与绑定数据访问
     * @param groupLinkMapper 群入口数据访问
     * @param registryService 群组池登记服务
     * @param metadataSyncTaskService 群详情同步任务服务
     */
    public GroupClassificationServiceImpl(
            AccountGroupCurrentSnapshotMapper currentSnapshotMapper,
            GroupLinkMapper groupLinkMapper,
            GroupLinkRegistryService registryService,
            GroupMetadataSyncTaskService metadataSyncTaskService) {
        this.currentSnapshotMapper = currentSnapshotMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.registryService = registryService;
        this.metadataSyncTaskService = metadataSyncTaskService;
    }

    @Override
    public void captureHistoricalBaseline(
            List<GroupClassificationCandidate> groups,
            ProtocolBackend observedBackend,
            long now) {
        captureHistoricalBaseline(groups, observedBackend, now, true);
    }

    @Override
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
        List<Long> historicalIds = candidates.values().stream()
                .map(group -> group.groupLinkId() == null
                        ? resolved.get(group.groupJid()) : group.groupLinkId())
                .filter(java.util.Objects::nonNull)
                .toList();
        return persistClassifications(historicalIds, List.of(), now, enqueueTasks);
    }

    private GroupClassificationPlan persistClassifications(
            List<Long> historicalIds,
            List<Long> postControlIds,
            long now,
            boolean enqueueTasks) {
        Map<Long, GroupMetadataSyncTrigger> byGroupLinkId = new TreeMap<>();
        historicalIds.stream()
                .filter(java.util.Objects::nonNull)
                .forEach(id -> byGroupLinkId.putIfAbsent(
                        id, GroupMetadataSyncTrigger.BASELINE_CAPTURED));
        postControlIds.stream()
                .filter(java.util.Objects::nonNull)
                .forEach(id -> byGroupLinkId.putIfAbsent(
                        id, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED));
        if (byGroupLinkId.isEmpty()) {
            return GroupClassificationPlan.empty();
        }
        Map<Long, GroupLink> activeLinks = groupLinkMapper.selectActiveByIds(
                        List.copyOf(byGroupLinkId.keySet()),
                        DataScopeAccess.requireCurrent()).stream()
                .collect(Collectors.toMap(
                        GroupLink::getId,
                        Function.identity(),
                        (left, right) -> left));
        Map<Long, GroupMetadataSyncTrigger> desired = new TreeMap<>();
        byGroupLinkId.forEach((groupLinkId, trigger) -> {
            if (activeLinks.containsKey(groupLinkId)) {
                desired.put(groupLinkId, trigger);
            }
        });
        Map<Long, GroupMetadataSyncTrigger> classificationsToPersist =
                pendingClassifications(byGroupLinkId, activeLinks);
        if (classificationsToPersist.isEmpty()) {
            return new GroupClassificationPlan(desired, Map.of());
        }
        Map<Long, GroupLink> lockedLinks = groupLinkMapper.selectActiveByIdsForUpdate(
                        List.copyOf(classificationsToPersist.keySet()),
                        DataScopeAccess.requireCurrent()).stream()
                .collect(Collectors.toMap(
                        GroupLink::getId,
                        Function.identity(),
                        (left, right) -> left));
        Map<Long, GroupMetadataSyncTrigger> lockedClassifications = pendingClassifications(
                classificationsToPersist, lockedLinks);
        if (lockedClassifications.isEmpty()) {
            return new GroupClassificationPlan(desired, Map.of());
        }
        List<Long> historicalToPersist = new ArrayList<>();
        List<Long> postControlToPersist = new ArrayList<>();
        lockedClassifications.forEach((groupLinkId, trigger) -> {
            if (trigger == GroupMetadataSyncTrigger.BASELINE_CAPTURED) {
                historicalToPersist.add(groupLinkId);
            } else {
                postControlToPersist.add(groupLinkId);
            }
        });
        int affected = groupLinkMapper.markClassifications(
                historicalToPersist, postControlToPersist,
                DataScopeAccess.requireCurrent(), now);
        if (affected != lockedClassifications.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "群分类批量提升结果不完整");
        }
        if (enqueueTasks) {
            metadataSyncTaskService.enqueueClassifications(lockedClassifications, now);
        }
        return new GroupClassificationPlan(desired, lockedClassifications);
    }

    private static Map<Long, GroupMetadataSyncTrigger> pendingClassifications(
            Map<Long, GroupMetadataSyncTrigger> requested,
            Map<Long, GroupLink> linksById) {
        Map<Long, GroupMetadataSyncTrigger> pending = new TreeMap<>();
        requested.forEach((groupLinkId, trigger) -> {
            GroupLink link = linksById.get(groupLinkId);
            if (trigger == GroupMetadataSyncTrigger.BASELINE_CAPTURED) {
                if (link != null && !Boolean.TRUE.equals(link.getIsHistorical())) {
                    pending.put(groupLinkId, trigger);
                }
            } else if (link != null && !Boolean.TRUE.equals(link.getIsPostControl())) {
                pending.put(groupLinkId, trigger);
            }
        });
        return pending;
    }

    @Override
    public void classifyVisibleGroups(
            Long accountId,
            List<GroupClassificationCandidate> groups,
            long now) {
        classifyVisibleGroups(accountId, groups, now, true);
    }

    @Override
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
        List<Long> historicalIds = new ArrayList<>();
        List<Long> postControlIds = new ArrayList<>();
        for (GroupClassificationCandidate group : candidates.values()) {
            if (group.groupLinkId() == null) {
                continue;
            }
            Existing existing = existingByJid.get(group.groupJid());
            if (existing != null && Integer.valueOf(1).equals(existing.wasInInitialBaseline())) {
                historicalIds.add(group.groupLinkId());
            } else if ((existing != null
                    && (Integer.valueOf(0).equals(existing.wasInInitialBaseline())
                    || existing.firstPostControlObservedAt() != null))
                    || (existing == null && now > context.baselineCapturedAt())) {
                postControlIds.add(group.groupLinkId());
            }
        }
        return persistClassifications(historicalIds, postControlIds, now, enqueueTasks);
    }

    @Override
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
        markAndEnqueuePostControl(group.groupLinkId(), now);
    }

    private void markAndEnqueueHistorical(Long groupLinkId, long now) {
        if (groupLinkMapper.markHistorical(
                groupLinkId, DataScopeAccess.requireCurrent(), now) == 1) {
            metadataSyncTaskService.enqueue(
                    groupLinkId, GroupMetadataSyncTrigger.BASELINE_CAPTURED, now);
        }
    }

    private void markAndEnqueuePostControl(Long groupLinkId, long now) {
        if (groupLinkMapper.markPostControl(
                groupLinkId, DataScopeAccess.requireCurrent(), now) == 1) {
            metadataSyncTaskService.enqueue(
                    groupLinkId, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED, now);
        }
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

    private static String normalizeJid(String value) {
        return blankToNull(value);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
