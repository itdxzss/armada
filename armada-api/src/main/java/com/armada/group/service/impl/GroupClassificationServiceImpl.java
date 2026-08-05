package com.armada.group.service.impl;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.vo.AccountGroupBaselineRow;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 历史群与账号上控后群固化分类实现。 */
@Service
public class GroupClassificationServiceImpl implements GroupClassificationService {

    private static final Logger log = LoggerFactory.getLogger(GroupClassificationServiceImpl.class);

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final AccountGroupMembershipMapper membershipMapper;
    private final GroupLinkMapper groupLinkMapper;
    private final GroupLinkRegistryService registryService;
    private final ObjectMapper objectMapper;
    private final GroupMetadataSyncTaskService metadataSyncTaskService;

    /**
     * 创建群分类服务。
     *
     * @param membershipMapper 账号 baseline 数据访问
     * @param groupLinkMapper 群入口数据访问
     * @param registryService 群组池登记服务
     * @param objectMapper JSON 解析器
     * @param metadataSyncTaskService 群详情同步任务服务
     */
    public GroupClassificationServiceImpl(
            AccountGroupMembershipMapper membershipMapper,
            GroupLinkMapper groupLinkMapper,
            GroupLinkRegistryService registryService,
            ObjectMapper objectMapper,
            GroupMetadataSyncTaskService metadataSyncTaskService) {
        this.membershipMapper = membershipMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.registryService = registryService;
        this.objectMapper = objectMapper;
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
        AccountGroupBaselineRow baseline = membershipMapper.selectAccountBaselineRow(accountId);
        Optional<Set<String>> baselineJids = capturedBaselineJids(baseline);
        if (baselineJids.isEmpty()) {
            return;
        }
        for (GroupClassificationCandidate group : normalized(groups).values()) {
            if (group.groupLinkId() == null) {
                continue;
            }
            if (baselineJids.get().contains(group.groupJid())) {
                markAndEnqueueHistorical(group.groupLinkId(), now);
            } else {
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
        AccountGroupBaselineRow baseline = membershipMapper.selectAccountBaselineRow(accountId);
        Optional<Set<String>> baselineJids = capturedBaselineJids(baseline);
        if (baselineJids.isEmpty()
                || baseline.getCapturedAt() == null
                || occurredAt <= baseline.getCapturedAt()
                || baselineJids.get().contains(normalizeJid(group.groupJid()))) {
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

    private Optional<Set<String>> capturedBaselineJids(AccountGroupBaselineRow baseline) {
        if (baseline == null
                || baseline.getGroupBaselineState() == null
                || baseline.getGroupBaselineState() != AccountGroupBaselineStateCode.CAPTURED
                || baseline.getBaselineGroupJidsJson() == null) {
            return Optional.empty();
        }
        try {
            List<String> values = objectMapper.readValue(baseline.getBaselineGroupJidsJson(), STRING_LIST);
            Set<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                String groupJid = normalizeJid(value);
                if (groupJid != null) {
                    normalized.add(groupJid);
                }
            }
            return Optional.of(Set.copyOf(normalized));
        } catch (JsonProcessingException exception) {
            log.warn("账号群 baseline JSON 无法解析，跳过群分类 accountId={}", baseline.getAccountId());
            return Optional.empty();
        }
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
