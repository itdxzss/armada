package com.armada.group.service.impl;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.model.enums.GroupLinkHealthStatus;
import com.armada.group.model.vo.AccountGroupMembershipChangeSet;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号可见群关系快照写入服务。
 *
 * <p>本类统一维护 {@code group_link}、{@code group_link_preview}、{@code group_link_health}
 * 和 {@code account_group_membership} 这些本地冗余事实。调用方传入协议回报的当前全部群,
 * 本类负责去重、更新当前关系，并在完整快照中把缺失关系标为“不在群”。</p>
 */
@Service
public class AccountGroupMembershipSnapshotServiceImpl implements AccountGroupMembershipSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(AccountGroupMembershipSnapshotServiceImpl.class);

    private static final String ACCOUNT_SYNC_LINK_PREFIX = "wa://group/";
    private static final int SUBJECT_MAX_LENGTH = 255;
    private static final int OWNER_PHONE_MAX_LENGTH = 32;
    private static final int AVATAR_URL_MAX_LENGTH = 512;

    private final AccountGroupMembershipMapper membershipMapper;
    private final GroupLinkMapper groupLinkMapper;
    private final GroupLinkHealthMapper healthMapper;
    private final GroupLinkRegistryService groupLinkRegistryService;

    /**
     * 创建账号可见群关系快照写入服务。
     *
     * @param membershipMapper 账号群关系 mapper
     * @param groupLinkMapper  群链接 mapper
     * @param healthMapper     群健康状态 mapper
     * @param groupLinkRegistryService 群组池登记服务
     */
    public AccountGroupMembershipSnapshotServiceImpl(AccountGroupMembershipMapper membershipMapper,
                                                     GroupLinkMapper groupLinkMapper,
                                                     GroupLinkHealthMapper healthMapper,
                                                     GroupLinkRegistryService groupLinkRegistryService) {
        this.membershipMapper = membershipMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.healthMapper = healthMapper;
        this.groupLinkRegistryService = groupLinkRegistryService;
    }

    @Override
    public AccountGroupMembershipChangeSet replaceVisibleGroups(
            Long accountId,
            List<AccountGroupsReportedEvent.Group> groups,
            boolean snapshotComplete,
            long syncAt,
            String eventId,
            String source) {
        if (accountId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群关系写入缺少 accountId");
        }
        long now = System.currentTimeMillis();
        List<String> activeGroupJids = membershipMapper.selectSnapshotEstablishedGroupJids(
                accountId,
                List.of(AccountGroupMembershipStatus.IN_GROUP.code(),
                        AccountGroupMembershipStatus.UNCONFIRMED.code()));
        Set<String> previousActive = normalizeJids(activeGroupJids);
        Map<String, AccountGroupsReportedEvent.Group> visibleGroups = normalizeVisibleGroups(groups);
        List<ResolvedGroup> resolvedGroups = resolveGroups(visibleGroups, now);
        List<ResolvedGroup> groupsByLinkId = resolvedGroups.stream()
                .sorted(Comparator.comparing(ResolvedGroup::groupLinkId)
                        .thenComparing(ResolvedGroup::groupJid))
                .toList();
        List<ResolvedGroup> groupsByJid = resolvedGroups.stream()
                .sorted(Comparator.comparing(ResolvedGroup::groupJid))
                .toList();
        List<Long> groupLinkIds = groupsByLinkId.stream().map(ResolvedGroup::groupLinkId).toList();
        List<String> groupJids = groupsByJid.stream().map(ResolvedGroup::groupJid).toList();
        Set<Long> existingPreviewIds = groupLinkIds.isEmpty()
                ? Set.of()
                : nonNullSet(membershipMapper.selectExistingPreviewGroupLinkIds(groupLinkIds));
        Set<Long> existingHealthIds = groupLinkIds.isEmpty()
                ? Set.of()
                : nonNullSet(healthMapper.selectExistingGroupLinkIds(groupLinkIds));
        Set<String> existingMembershipJids = groupJids.isEmpty()
                ? Set.of()
                : nonNullSet(membershipMapper.selectExistingActiveGroupJids(accountId, groupJids));
        // 先用普通一致性读区分存量/新增，再按表和唯一键全局排序写入。RR 下禁止对缺失键先 UPDATE，
        // 否则 next-key/gap 锁会与后续 INSERT 的插入意向锁形成 supremum 死锁。
        persistPreviews(groupsByLinkId, existingPreviewIds, syncAt, now);
        persistHealthRows(groupsByLinkId, existingHealthIds, syncAt, now);
        persistMemberships(accountId, groupsByJid, existingMembershipJids, syncAt, now);
        List<AccountGroupMembershipSnapshot> snapshots = groupsByJid.stream()
                .map(resolved -> toSnapshot(
                        resolved.groupLinkId(), resolved.groupJid(), resolved.group()))
                .toList();
        int markedMissing = 0;
        if (snapshotComplete) {
            List<Integer> preservedStatuses = List.of(
                    AccountGroupMembershipStatus.KICKED_OUT.code(),
                    AccountGroupMembershipStatus.LEFT.code());
            List<Long> missingMembershipIds = sortedIds(membershipMapper.selectMissingMembershipIds(
                    accountId,
                    List.copyOf(visibleGroups.keySet()),
                    preservedStatuses,
                    syncAt));
            if (!missingMembershipIds.isEmpty()) {
                AccountGroupMembership missingState = new AccountGroupMembership();
                missingState.setMembershipStatus(AccountGroupMembershipStatus.NOT_IN_GROUP.code());
                missingState.setStatusSource("GROUP_SNAPSHOT");
                missingState.setStatusUpdatedAt(syncAt);
                missingState.setUpdatedAt(syncAt);
                markedMissing = membershipMapper.markMembershipsNotInGroupByIds(
                        missingMembershipIds, missingState, preservedStatuses);
            }
        }
        Set<String> currentSendable = snapshots.isEmpty()
                ? Set.of()
                : normalizeJids(membershipMapper.selectSendableGroupJids(
                        accountId,
                        List.of(AccountGroupMembershipStatus.IN_GROUP.code(),
                                AccountGroupMembershipStatus.UNCONFIRMED.code())));
        List<AccountGroupMembershipSnapshot> added = snapshots.stream()
                .filter(snapshot -> !previousActive.contains(snapshot.groupJid()))
                .filter(snapshot -> currentSendable.contains(snapshot.groupJid()))
                .toList();
        log.info("账号可见群关系快照已刷新 eventId={} source={} accountId={} visibleGroups={} "
                        + "addedGroups={} addedGroupJidSample={} visibleGroupJidSample={} snapshotComplete={} "
                        + "markedMissing={} syncAt={}",
                eventId, source, accountId, visibleGroups.size(), added.size(), jidSample(
                        added.stream().map(AccountGroupMembershipSnapshot::groupJid).toList()),
                jidSample(visibleGroups.keySet()), snapshotComplete, markedMissing, syncAt);
        return new AccountGroupMembershipChangeSet(snapshots, added);
    }

    private static Set<String> normalizeJids(List<String> groupJids) {
        if (groupJids == null || groupJids.isEmpty()) {
            return Set.of();
        }
        return groupJids.stream()
                .map(AccountGroupMembershipSnapshotServiceImpl::normalizeJid)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static <T> Set<T> nonNullSet(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static List<Long> sortedIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
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
            long now) {
        List<ResolvedGroup> resolvedGroups = new ArrayList<>(visibleGroups.size());
        for (Map.Entry<String, AccountGroupsReportedEvent.Group> entry : visibleGroups.entrySet()) {
            AccountGroupsReportedEvent.Group group = entry.getValue();
            Long groupLinkId = groupLinkRegistryService.registerAccountObservedGroup(
                    entry.getKey(), group.subject(), now);
            resolvedGroups.add(new ResolvedGroup(groupLinkId, entry.getKey(), group));
        }
        return resolvedGroups;
    }

    private void persistPreviews(
            List<ResolvedGroup> resolvedGroups,
            Set<Long> existingPreviewIds,
            long syncAt,
            long now) {
        for (ResolvedGroup resolved : resolvedGroups) {
            GroupLinkPreview preview = previewRow(resolved, syncAt, now);
            if (existingPreviewIds.contains(preview.getGroupLinkId())
                    && membershipMapper.updatePreviewFromAccountSync(preview) > 0) {
                continue;
            }
            membershipMapper.upsertPreviewFromAccountSync(
                    preview.getGroupLinkId(),
                    preview.getGroupJid(),
                    preview.getWaSubject(),
                    preview.getMemberSize(),
                    preview.getOwnerPhone(),
                    preview.getAnnounceOnly(),
                    preview.getAvatarUrl(),
                    preview.getLastPreviewAt(),
                    preview.getUpdatedAt());
        }
    }

    private GroupLinkPreview previewRow(ResolvedGroup resolved, long syncAt, long now) {
        AccountGroupsReportedEvent.Group group = resolved.group();
        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setGroupLinkId(resolved.groupLinkId());
        preview.setGroupJid(resolved.groupJid());
        preview.setWaSubject(clamp(blankToNull(group.subject()), SUBJECT_MAX_LENGTH));
        preview.setMemberSize(group.memberCount());
        preview.setOwnerPhone(clamp(ownerPhone(group), OWNER_PHONE_MAX_LENGTH));
        preview.setAnnounceOnly(group.announceOnly());
        preview.setAvatarUrl(clamp(blankToNull(group.avatarUrl()), AVATAR_URL_MAX_LENGTH));
        preview.setLastPreviewAt(syncAt);
        preview.setCreatedAt(now);
        preview.setUpdatedAt(now);
        return preview;
    }

    private void persistHealthRows(
            List<ResolvedGroup> resolvedGroups,
            Set<Long> existingHealthIds,
            long syncAt,
            long now) {
        for (ResolvedGroup resolved : resolvedGroups) {
            GroupLinkHealth health = healthRow(resolved, syncAt, now);
            if (existingHealthIds.contains(health.getGroupLinkId())
                    && healthMapper.updateFromAccountGroupSync(health) > 0) {
                continue;
            }
            healthMapper.upsertFromAccountGroupSync(health);
        }
    }

    private GroupLinkHealth healthRow(ResolvedGroup resolved, long syncAt, long now) {
        AccountGroupsReportedEvent.Group group = resolved.group();
        GroupLinkHealth health = new GroupLinkHealth();
        health.setGroupLinkId(resolved.groupLinkId());
        health.setHealthStatus(GroupLinkHealthStatus.AVAILABLE.code());
        health.setBanned(false);
        health.setCurrentCount(group.memberCount());
        health.setLastCheckAt(syncAt);
        health.setLastHealthError(null);
        health.setHealthFailureCount(0);
        health.setCreatedAt(now);
        health.setUpdatedAt(now);
        return health;
    }

    private void persistMemberships(
            Long accountId,
            List<ResolvedGroup> resolvedGroups,
            Set<String> existingMembershipJids,
            long syncAt,
            long now) {
        for (ResolvedGroup resolved : resolvedGroups) {
            AccountGroupMembership membership = membershipRow(accountId, resolved, syncAt, now);
            if (existingMembershipJids.contains(membership.getGroupJid())
                    && membershipMapper.updateActiveMembership(membership) > 0) {
                continue;
            }
            membershipMapper.upsertMembership(membership);
        }
    }

    private AccountGroupMembership membershipRow(
            Long accountId,
            ResolvedGroup resolved,
            long syncAt,
            long now) {
        AccountGroupsReportedEvent.Group group = resolved.group();
        AccountGroupMembership row = new AccountGroupMembership();
        row.setAccountId(accountId);
        row.setGroupLinkId(resolved.groupLinkId());
        row.setGroupJid(resolved.groupJid());
        row.setAdmin(group.admin());
        row.setMembershipStatus(AccountGroupMembershipStatus.IN_GROUP.code());
        row.setStatusSource("GROUP_SNAPSHOT");
        row.setStatusUpdatedAt(syncAt);
        row.setJoinedAt(syncAt);
        row.setLastSeenAt(syncAt);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
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

    private static String ownerPhone(AccountGroupsReportedEvent.Group group) {
        String ownerPhone = blankToNull(group.ownerPhone());
        if (ownerPhone != null) {
            return ownerPhone;
        }
        String ownerJid = blankToNull(group.ownerJid());
        if (ownerJid == null) {
            return null;
        }
        int at = ownerJid.indexOf('@');
        return at <= 0 ? ownerJid : ownerJid.substring(0, at);
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
