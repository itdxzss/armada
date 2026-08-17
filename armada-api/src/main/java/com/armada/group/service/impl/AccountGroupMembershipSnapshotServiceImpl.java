package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.model.enums.OwnerIdentityKind;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号可见群关系快照写入服务。
 *
 * <p>本类只负责解析仍需兼容的 {@code group_link} 数字句柄、固化旧列表分类，
 * 并保留旧列表仍展示的创建者手机号。群资料、健康状态、成员和账号关系事实只写新模型。</p>
 */
@Service
public class AccountGroupMembershipSnapshotServiceImpl implements AccountGroupMembershipSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(AccountGroupMembershipSnapshotServiceImpl.class);

    private static final String ACCOUNT_SYNC_LINK_PREFIX = "wa://group/";
    private static final int OWNER_PHONE_MAX_LENGTH = 32;

    private final GroupLinkMapper groupLinkMapper;
    private final GroupLinkPreviewMapper previewMapper;
    private final GroupLinkRegistryService groupLinkRegistryService;
    private final GroupClassificationService classificationService;

    /**
     * 创建账号可见群关系快照写入服务。
     *
     * @param groupLinkMapper  群链接 mapper
     * @param previewMapper 创建者字段兼容 mapper
     * @param groupLinkRegistryService 群组池登记服务
     * @param classificationService 历史群与上控后群分类服务
     */
    public AccountGroupMembershipSnapshotServiceImpl(GroupLinkMapper groupLinkMapper,
                                                     GroupLinkPreviewMapper previewMapper,
                                                     GroupLinkRegistryService groupLinkRegistryService,
                                                     GroupClassificationService classificationService) {
        this.groupLinkMapper = groupLinkMapper;
        this.previewMapper = previewMapper;
        this.groupLinkRegistryService = groupLinkRegistryService;
        this.classificationService = classificationService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AccountGroupMembershipSnapshot> replaceVisibleGroups(
            Long accountId,
            List<AccountGroupsReportedEvent.Group> groups,
            boolean snapshotComplete,
            long syncAt,
            String eventId,
            String source,
            ProtocolBackend observedBackend) {
        if (accountId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群关系写入缺少 accountId");
        }
        long now = System.currentTimeMillis();
        Map<String, AccountGroupsReportedEvent.Group> visibleGroups = normalizeVisibleGroups(groups);
        List<ResolvedGroup> resolvedGroups = resolveGroups(visibleGroups, observedBackend, now);
        classificationService.classifyVisibleGroups(
                accountId,
                resolvedGroups.stream()
                        .map(resolved -> new GroupClassificationCandidate(
                                resolved.groupLinkId(),
                                resolved.groupJid(),
                                blankToNull(resolved.group().subject())))
                        .toList(),
                now);
        List<ResolvedGroup> groupsByJid = resolvedGroups.stream()
                .sorted(Comparator.comparing(ResolvedGroup::groupJid))
                .toList();
        persistCreatorCompatibility(groupsByJid, syncAt, now);
        List<AccountGroupMembershipSnapshot> snapshots = groupsByJid.stream()
                .map(resolved -> toSnapshot(
                        resolved.groupLinkId(), resolved.groupJid(), resolved.group()))
                .toList();
        log.info("账号可见群兼容句柄已刷新 eventId={} source={} accountId={} visibleGroups={} "
                        + "visibleGroupJidSample={} snapshotComplete={} syncAt={}",
                eventId, source, accountId, visibleGroups.size(),
                jidSample(visibleGroups.keySet()), snapshotComplete, syncAt);
        return snapshots;
    }

    /**
     * 返回最多 5 个非空群 JID,用于有界排障日志。
     */
    private static List<String> jidSample(Iterable<String> groupJids) {
        List<String> sample = new ArrayList<>(5);
        for (String groupJid : groupJids) {
            if (groupJid != null && !groupJid.isBlank()) {
                sample.add(groupJid);
                if (sample.size() == 5) {
                    break;
                }
            }
        }
        return sample;
    }

    private static Map<String, AccountGroupsReportedEvent.Group> normalizeVisibleGroups(
            List<AccountGroupsReportedEvent.Group> groups) {
        Map<String, AccountGroupsReportedEvent.Group> visible = new TreeMap<>();
        if (groups == null) {
            return visible;
        }
        for (AccountGroupsReportedEvent.Group group : groups) {
            String groupJid = normalizeJid(group.groupJid());
            if (groupJid != null) {
                visible.putIfAbsent(groupJid, group);
            }
        }
        return visible;
    }

    private List<ResolvedGroup> resolveGroups(
            Map<String, AccountGroupsReportedEvent.Group> visibleGroups,
            ProtocolBackend observedBackend,
            long now) {
        List<ResolvedGroup> resolvedGroups = new ArrayList<>(visibleGroups.size());
        for (Map.Entry<String, AccountGroupsReportedEvent.Group> entry : visibleGroups.entrySet()) {
            AccountGroupsReportedEvent.Group group = entry.getValue();
            Long groupLinkId = groupLinkRegistryService.registerAccountObservedGroup(
                    entry.getKey(), group.subject(), observedBackend, now);
            resolvedGroups.add(new ResolvedGroup(groupLinkId, entry.getKey(), group));
        }
        return resolvedGroups;
    }

    private void persistCreatorCompatibility(
            List<ResolvedGroup> resolvedGroups,
            long syncAt,
            long now) {
        List<GroupLinkPreview> rows = resolvedGroups.stream()
                .map(resolved -> {
                    OwnerPhoneObservation owner = ownerPhoneObservation(resolved.group());
                    if (!owner.observed()) {
                        return null;
                    }
                    GroupLinkPreview row = new GroupLinkPreview();
                    row.setGroupLinkId(resolved.groupLinkId());
                    row.setOwnerPhone(clamp(owner.phone(), OWNER_PHONE_MAX_LENGTH));
                    row.setOwnerPhoneObserved(true);
                    row.setCreatorCountryObserved(false);
                    row.setLastPreviewAt(syncAt);
                    row.setMetadataObservedAt(syncAt);
                    row.setCreatedAt(now);
                    row.setUpdatedAt(now);
                    return row;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        if (!rows.isEmpty()) {
            previewMapper.upsertCreatorCompatibility(rows);
        }
    }

    private record ResolvedGroup(
            Long groupLinkId,
            String groupJid,
            AccountGroupsReportedEvent.Group group) {
    }

    private AccountGroupMembershipSnapshot toSnapshot(Long groupLinkId,
                                                      String groupJid,
                                                      AccountGroupsReportedEvent.Group group) {
        GroupLink link = groupLinkMapper.selectActiveById(groupLinkId);
        String linkUrl = link == null ? accountSyncLinkUrl(groupJid) : link.getLinkUrl();
        String groupName = link == null ? null : blankToNull(link.getGroupName());
        if (groupName == null) {
            groupName = blankToNull(group.subject());
        }
        if (groupName == null) {
            groupName = groupJid;
        }
        return new AccountGroupMembershipSnapshot(groupLinkId, groupJid, groupName, linkUrl, group.admin());
    }

    private static String accountSyncLinkUrl(String groupJid) {
        return ACCOUNT_SYNC_LINK_PREFIX + groupJid;
    }

    private static OwnerPhoneObservation ownerPhoneObservation(
            AccountGroupsReportedEvent.Group group) {
        WhatsappJids.OwnerIdentity explicitPhone = WhatsappJids.ownerIdentity(group.ownerPhone(), "pn");
        if (explicitPhone.kind() == OwnerIdentityKind.PN) {
            return new OwnerPhoneObservation(explicitPhone.ownerPhone(), true);
        }
        WhatsappJids.OwnerIdentity owner = WhatsappJids.ownerIdentity(group.ownerJid(), null);
        if (owner.kind() == OwnerIdentityKind.LID) {
            return new OwnerPhoneObservation(null, true);
        }
        return new OwnerPhoneObservation(null, false);
    }

    private record OwnerPhoneObservation(String phone, boolean observed) {
    }

    private static String normalizeJid(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String clamp(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
