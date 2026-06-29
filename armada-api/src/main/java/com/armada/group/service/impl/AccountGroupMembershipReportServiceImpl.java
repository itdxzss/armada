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
import com.armada.group.service.AccountGroupMembershipReportService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号当前群列表回报落库服务实现。
 *
 * <p>Kafka listener 线程没有 HTTP 租户上下文,本服务按事件中的 {@code tenantId}
 * 临时重建 {@link TenantContext}。落库时只写“当前群 - 登录前 baseline”的差集,
 * baseline 快照只读不写。</p>
 */
@Service
public class AccountGroupMembershipReportServiceImpl implements AccountGroupMembershipReportService {

    private static final Logger log = LoggerFactory.getLogger(AccountGroupMembershipReportServiceImpl.class);

    private static final String ACCOUNT_SYNC_LINK_PREFIX = "wa://group/";
    private static final int GROUP_NAME_MAX_LENGTH = 128;
    private static final int SUBJECT_MAX_LENGTH = 255;
    private static final int OWNER_PHONE_MAX_LENGTH = 32;
    private static final int AVATAR_URL_MAX_LENGTH = 512;

    private final AccountGroupMembershipMapper membershipMapper;
    private final GroupLinkMapper groupLinkMapper;
    private final GroupLinkHealthMapper healthMapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建账号当前群列表回报落库服务。
     *
     * @param membershipMapper 账号群关系 mapper
     * @param groupLinkMapper  群入口 mapper
     * @param healthMapper     群健康状态 mapper
     * @param objectMapper     JSON 解析器
     */
    public AccountGroupMembershipReportServiceImpl(AccountGroupMembershipMapper membershipMapper,
                                                   GroupLinkMapper groupLinkMapper,
                                                   GroupLinkHealthMapper healthMapper,
                                                   ObjectMapper objectMapper) {
        this.membershipMapper = membershipMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.healthMapper = healthMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 应用协议层 {@code account.groups_reported} 回报事件。
     *
     * <p>协议层返回的是账号当前全部参与群;Armada 先读取账号上线前 baseline JSON,
     * 过滤掉上线前已在的群,只把上线后新增的群写入展示关系。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyGroupsReported(AccountGroupsReportedEvent event) {
        validate(event);
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            long now = System.currentTimeMillis();
            long syncAt = event.reportedAt() == null ? now : event.reportedAt();
            Set<String> baseline = loadBaselineGroupJids(event.accountId());
            Map<String, AccountGroupsReportedEvent.Group> visibleGroups = visibleGroups(event.groups(), baseline);
            for (Map.Entry<String, AccountGroupsReportedEvent.Group> entry : visibleGroups.entrySet()) {
                Long groupLinkId = ensureGroupLink(entry.getKey(), entry.getValue(), now);
                persistSnapshots(groupLinkId, entry.getKey(), entry.getValue(), syncAt, now);
                upsertMembership(event.accountId(), groupLinkId, entry.getKey(), entry.getValue(), syncAt, now);
            }
            membershipMapper.markMissingMembershipsDeleted(
                    event.accountId(), List.copyOf(visibleGroups.keySet()), now);
            log.info("账号群列表事件已回写 tenantId={} accountId={} protocolAccountId={} rawGroups={} "
                            + "baselineGroups={} visibleGroups={} eventId={}",
                    event.tenantId(), event.accountId(), event.protocolAccountId(), event.groups().size(),
                    baseline.size(), visibleGroups.size(), event.eventId());
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private Set<String> loadBaselineGroupJids(Long accountId) {
        String json = membershipMapper.selectBaselineGroupJidsJson(accountId);
        if (json == null || json.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群基线不存在: " + accountId);
        }
        List<String> groupJids;
        try {
            groupJids = objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群基线 JSON 解析失败");
        }
        Set<String> baseline = new LinkedHashSet<>();
        for (String groupJid : groupJids) {
            String normalized = normalizeJid(groupJid);
            if (normalized != null) {
                baseline.add(normalized);
            }
        }
        return baseline;
    }

    private static Map<String, AccountGroupsReportedEvent.Group> visibleGroups(
            List<AccountGroupsReportedEvent.Group> groups,
            Set<String> baseline) {
        Map<String, AccountGroupsReportedEvent.Group> visible = new LinkedHashMap<>();
        for (AccountGroupsReportedEvent.Group group : groups) {
            String groupJid = normalizeJid(group.groupJid());
            if (groupJid == null || baseline.contains(groupJid)) {
                continue;
            }
            visible.putIfAbsent(groupJid, group);
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
        row.setLastSeenAt(syncAt);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        membershipMapper.upsertMembership(row);
    }

    private static void validate(AccountGroupsReportedEvent event) {
        if (event == null || event.tenantId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群列表事件缺少 tenantId");
        }
        if (event.accountId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群列表事件缺少 accountId");
        }
        if (event.groups() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号群列表事件缺少 groups");
        }
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
