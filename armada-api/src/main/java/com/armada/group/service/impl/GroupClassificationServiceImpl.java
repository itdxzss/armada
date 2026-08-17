package com.armada.group.service.impl;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Existing;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.enums.OwnerIdentityKind;
import com.armada.platform.protocol.util.WhatsappJids;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        for (GroupClassificationCandidate group : normalized(groups).values()) {
            Long groupLinkId = group.groupLinkId();
            if (groupLinkId == null) {
                groupLinkId = registryService.registerAccountObservedGroup(
                        group.groupJid(), group.groupName(), observedBackend, now);
            }
            markAndEnqueueHistorical(groupLinkId, now);
        }
    }

    @Override
    public void classifyVisibleGroups(
            Long accountId,
            List<GroupClassificationCandidate> groups,
            long now) {
        Context context = currentSnapshotMapper.selectContext(accountId);
        if (!capturedBaseline(context)) {
            return;
        }
        Map<String, GroupClassificationCandidate> candidates = normalized(groups);
        if (candidates.isEmpty()) {
            return;
        }
        WhatsappJids.OwnerIdentity self = WhatsappJids.ownerIdentity(context.wsPhone(), "pn");
        if (self.kind() != OwnerIdentityKind.PN || self.ownerJid() == null) {
            return;
        }
        Map<String, Existing> existingByJid = currentSnapshotMapper.selectExisting(
                        accountId, self.ownerJid(), List.copyOf(candidates.keySet())).stream()
                .collect(Collectors.toMap(Existing::groupJid, Function.identity(), (left, right) -> left));
        for (GroupClassificationCandidate group : candidates.values()) {
            if (group.groupLinkId() == null) {
                continue;
            }
            Existing existing = existingByJid.get(group.groupJid());
            if (existing != null && Integer.valueOf(1).equals(existing.wasInInitialBaseline())) {
                markAndEnqueueHistorical(group.groupLinkId(), now);
            } else if ((existing != null
                    && (Integer.valueOf(0).equals(existing.wasInInitialBaseline())
                    || existing.firstPostControlObservedAt() != null))
                    || (existing == null && now > context.baselineCapturedAt())) {
                markAndEnqueuePostControl(group.groupLinkId(), now);
            }
        }
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
        if (groupLinkMapper.markHistorical(groupLinkId, now) == 1) {
            metadataSyncTaskService.enqueue(
                    groupLinkId, GroupMetadataSyncTrigger.BASELINE_CAPTURED, now);
        }
    }

    private void markAndEnqueuePostControl(Long groupLinkId, long now) {
        if (groupLinkMapper.markPostControl(groupLinkId, now) == 1) {
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
