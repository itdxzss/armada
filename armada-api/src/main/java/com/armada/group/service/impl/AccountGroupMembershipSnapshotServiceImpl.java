package com.armada.group.service.impl;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.enums.GroupLinkHealthStatus;
import com.armada.group.model.enums.GroupLinkOrigin;
import com.armada.group.model.enums.GroupMembershipState;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号可见群关系快照写入服务。
 *
 * <p>本类统一维护 {@code group_link}、{@code group_link_preview}、{@code group_link_health}
 * 和 {@code account_group_membership} 这些本地冗余事实。调用方负责先过滤 baseline 旧群,
 * 本类只负责把“当前可见群集合”一致地写入本地库。</p>
 */
@Service
public class AccountGroupMembershipSnapshotServiceImpl implements AccountGroupMembershipSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(AccountGroupMembershipSnapshotServiceImpl.class);

    private static final String ACCOUNT_SYNC_LINK_PREFIX = "wa://group/";
    private static final int GROUP_NAME_MAX_LENGTH = 128;
    private static final int SUBJECT_MAX_LENGTH = 255;
    private static final int OWNER_PHONE_MAX_LENGTH = 32;
    private static final int AVATAR_URL_MAX_LENGTH = 512;

    private final AccountGroupMembershipMapper membershipMapper;
    private final GroupLinkMapper groupLinkMapper;
    private final GroupLinkHealthMapper healthMapper;

    /**
     * 创建账号可见群关系快照写入服务。
     *
     * @param membershipMapper 账号群关系 mapper
     * @param groupLinkMapper  群链接 mapper
     * @param healthMapper     群健康状态 mapper
     */
    public AccountGroupMembershipSnapshotServiceImpl(AccountGroupMembershipMapper membershipMapper,
                                                     GroupLinkMapper groupLinkMapper,
                                                     GroupLinkHealthMapper healthMapper) {
        this.membershipMapper = membershipMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.healthMapper = healthMapper;
    }

    @Override
    public List<AccountGroupMembershipSnapshot> replaceVisibleGroups(
            Long accountId,
            List<AccountGroupsReportedEvent.Group> groups,
            long syncAt,
            String eventId,
            String source) {
        if (accountId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群关系写入缺少 accountId");
        }
        long now = System.currentTimeMillis();
        Map<String, AccountGroupsReportedEvent.Group> visibleGroups = normalizeVisibleGroups(groups);
        List<AccountGroupMembershipSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<String, AccountGroupsReportedEvent.Group> entry : visibleGroups.entrySet()) {
            String groupJid = entry.getKey();
            AccountGroupsReportedEvent.Group group = entry.getValue();
            Long groupLinkId = ensureGroupLink(groupJid, group, now);
            persistSnapshots(groupLinkId, groupJid, group, syncAt, now);
            upsertMembership(accountId, groupLinkId, groupJid, group, syncAt, now);
            snapshots.add(toSnapshot(groupLinkId, groupJid, group));
        }
        int deleted = membershipMapper.markMissingMembershipsDeleted(accountId, List.copyOf(visibleGroups.keySet()), now);
        log.info("账号可见群关系快照已刷新 eventId={} source={} accountId={} visibleGroups={} "
                        + "visibleGroupJidSample={} deleted={} syncAt={}",
                eventId, source, accountId, visibleGroups.size(), jidSample(visibleGroups.keySet()), deleted, syncAt);
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
        Map<String, AccountGroupsReportedEvent.Group> visible = new LinkedHashMap<>();
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

    private Long ensureGroupLink(String groupJid, AccountGroupsReportedEvent.Group group, long now) {
        Long groupLinkId = membershipMapper.selectActiveGroupLinkIdByGroupJid(groupJid);
        if (groupLinkId == null) {
            GroupLink existing = groupLinkMapper.selectAnyByUrl(accountSyncLinkUrl(groupJid));
            if (existing == null) {
                GroupLink row = new GroupLink();
                row.setLinkUrl(accountSyncLinkUrl(groupJid));
                row.setGroupName(clamp(blankToNull(group.subject()), GROUP_NAME_MAX_LENGTH));
                row.setOrigin(GroupLinkOrigin.ACCOUNT_SYNC.code());
                row.setMembershipState(GroupMembershipState.JOINED.code());
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                groupLinkMapper.insert(row);
                groupLinkId = row.getId();
                log.info("账号群同步发现新群入口 groupJid={} groupLinkId={} subject={}",
                        groupJid, groupLinkId, blankToNull(group.subject()));
            } else {
                groupLinkId = existing.getId();
            }
        }
        membershipMapper.touchGroupLinkFromAccountSync(
                groupLinkId, clamp(blankToNull(group.subject()), GROUP_NAME_MAX_LENGTH), now);
        return groupLinkId;
    }

    private void persistSnapshots(Long groupLinkId,
                                  String groupJid,
                                  AccountGroupsReportedEvent.Group group,
                                  long syncAt,
                                  long now) {
        membershipMapper.upsertPreviewFromAccountSync(
                groupLinkId,
                groupJid,
                clamp(blankToNull(group.subject()), SUBJECT_MAX_LENGTH),
                group.memberCount(),
                clamp(ownerPhone(group), OWNER_PHONE_MAX_LENGTH),
                group.announceOnly(),
                clamp(blankToNull(group.avatarUrl()), AVATAR_URL_MAX_LENGTH),
                syncAt,
                now);

        GroupLinkHealth health = new GroupLinkHealth();
        health.setGroupLinkId(groupLinkId);
        health.setHealthStatus(GroupLinkHealthStatus.AVAILABLE.code());
        health.setBanned(false);
        health.setCurrentCount(group.memberCount());
        health.setLastCheckAt(syncAt);
        health.setLastHealthError(null);
        health.setHealthFailureCount(0);
        health.setCreatedAt(now);
        health.setUpdatedAt(now);
        healthMapper.upsertFromAccountGroupSync(health);
    }

    private void upsertMembership(Long accountId,
                                  Long groupLinkId,
                                  String groupJid,
                                  AccountGroupsReportedEvent.Group group,
                                  long syncAt,
                                  long now) {
        AccountGroupMembership row = new AccountGroupMembership();
        row.setAccountId(accountId);
        row.setGroupLinkId(groupLinkId);
        row.setGroupJid(groupJid);
        row.setAdmin(group.admin());
        row.setJoinedAt(now);
        row.setLastSeenAt(syncAt);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        membershipMapper.upsertMembership(row);
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
