package com.armada.group.service.impl;

import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.service.AccountGroupMembershipSnapshotService;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.group.service.HistoricalGroupProtocolPorts;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.AccountGroupMetadataSummaryResult;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 用户显式触发的账号组历史群实时同步。 */
@Service
public class HistoricalGroupAccountGroupRefreshService {

    private static final Logger log = LoggerFactory.getLogger(
            HistoricalGroupAccountGroupRefreshService.class);
    private static final String SOURCE = "HISTORICAL_GROUP_MANUAL_REFRESH";
    private static final int SUMMARY_CONCURRENCY = 8;

    private final AccountGroupMapper accountGroupMapper;
    private final AccountProtocolLookupService accountLookupService;
    private final HistoricalGroupProtocolPorts protocolPorts;
    private final AccountGroupMembershipSnapshotService snapshotService;
    private final AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;
    private final GroupInviteLinkService inviteLinkService;

    /**
     * 创建账号组历史群实时同步服务。
     *
     * @param accountGroupMapper 账号组数据访问
     * @param accountLookupService 账号协议身份查询服务
     * @param protocolPorts 历史群协议能力集合
     * @param snapshotService 账号群快照写入服务
     * @param currentSnapshotPersistence 新群模型账号快照持久化服务
     * @param inviteLinkService 当前群邀请链接事实服务
     */
    public HistoricalGroupAccountGroupRefreshService(
            AccountGroupMapper accountGroupMapper,
            AccountProtocolLookupService accountLookupService,
            HistoricalGroupProtocolPorts protocolPorts,
            AccountGroupMembershipSnapshotService snapshotService,
            AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence,
            GroupInviteLinkService inviteLinkService) {
        this.accountGroupMapper = accountGroupMapper;
        this.accountLookupService = accountLookupService;
        this.protocolPorts = protocolPorts;
        this.snapshotService = snapshotService;
        this.currentSnapshotPersistence = currentSnapshotPersistence;
        this.inviteLinkService = inviteLinkService;
    }

    /**
     * 同步账号组内全部在线正常账号的当前群,单账号协议或持久化失败不影响其它账号。
     *
     * @param accountGroupId 账号组 ID
     */
    public void refresh(Long accountGroupId) {
        requireAccountGroup(accountGroupId);
        List<ProtocolAccountRef> accounts = accountLookupService.findOnlineNormalByGroupId(accountGroupId);
        if (accounts.isEmpty()) {
            throw new BusinessException(ErrorCode.GROUP_EXECUTOR_UNAVAILABLE, "账号组内没有在线正常账号");
        }
        Map<String, ProtocolAccountRef> inviteAccounts = new LinkedHashMap<>();
        int succeeded = 0;
        for (ProtocolAccountRef account : accounts) {
            long syncAt = System.currentTimeMillis();
            try {
                List<AccountParticipatingGroupResult.Group> groups = completeGroups(
                        account,
                        protocolPorts.participatingGroups().listCurrent(account));
                List<AccountGroupsReportedEvent.Group> reportedGroups = toReportedGroups(groups);
                String eventId = "historical-group-manual-"
                        + account.armadaAccountId() + "-" + syncAt;
                var currentGroups = snapshotService.replaceVisibleGroups(
                        account.armadaAccountId(),
                        reportedGroups,
                        true,
                        syncAt,
                        eventId,
                        SOURCE,
                        account.backend());
                try {
                    currentSnapshotPersistence.replaceVisibleGroups(
                            account.armadaAccountId(), reportedGroups, true, syncAt, eventId,
                            currentGroups);
                } catch (RuntimeException ex) {
                    log.warn("历史群新模型影子写入失败 accountGroupId={} accountId={} errorType={}",
                            accountGroupId,
                            account.armadaAccountId(),
                            ex.getClass().getSimpleName());
                }
                for (AccountParticipatingGroupResult.Group group : groups) {
                    if (group != null
                            && Boolean.TRUE.equals(group.admin())
                            && text(group.groupJid()) != null) {
                        inviteAccounts.putIfAbsent(group.groupJid().trim(), account);
                    }
                }
                succeeded++;
            } catch (ProtocolException ex) {
                log.warn("历史群账号同步失败 accountGroupId={} accountId={} reasonCode={} httpStatus={}",
                        accountGroupId, account.armadaAccountId(), ex.errorCode(), ex.httpStatus());
            } catch (RuntimeException ex) {
                log.warn("历史群账号同步处理失败 accountGroupId={} accountId={} errorType={}",
                        accountGroupId,
                        account.armadaAccountId(),
                        ex.getClass().getSimpleName());
            }
        }
        if (succeeded == 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号组群列表加载失败");
        }
        refreshInvites(inviteAccounts);
        log.info("账号组历史群刷新完成 accountGroupId={} accounts={} succeeded={} inviteGroups={}",
                accountGroupId, accounts.size(), succeeded, inviteAccounts.size());
    }

    private List<AccountParticipatingGroupResult.Group> completeGroups(
            ProtocolAccountRef account,
            List<AccountParticipatingGroupResult.Group> groups) {
        List<AccountParticipatingGroupResult.Group> safeGroups = groups == null
                ? List.of()
                : groups.stream().filter(group -> group != null).toList();
        List<String> missingRoleJids = safeGroups.stream()
                .filter(group -> group.admin() == null)
                .map(AccountParticipatingGroupResult.Group::groupJid)
                .filter(jid -> text(jid) != null)
                .toList();
        if (missingRoleJids.isEmpty()) {
            return safeGroups;
        }
        Map<String, AccountGroupMetadataSummaryResult> summaries = new LinkedHashMap<>();
        for (AccountGroupMetadataSummaryResult summary : protocolPorts.participatingGroups()
                .summarize(account, missingRoleJids, SUMMARY_CONCURRENCY)) {
            if (summary != null && text(summary.groupJid()) != null) {
                summaries.putIfAbsent(summary.groupJid().trim(), summary);
            }
        }
        List<AccountParticipatingGroupResult.Group> completed = new ArrayList<>(safeGroups.size());
        for (AccountParticipatingGroupResult.Group group : safeGroups) {
            AccountGroupMetadataSummaryResult summary = summaries.get(text(group.groupJid()));
            if (group.admin() != null || summary == null || !summary.success()) {
                completed.add(group);
                continue;
            }
            completed.add(new AccountParticipatingGroupResult.Group(
                    group.groupJid(),
                    firstText(summary.subject(), group.subject()),
                    summary.memberSize() == null ? group.memberCount() : summary.memberSize(),
                    group.ownerJid(),
                    group.ownerPhone(),
                    group.ownerIdentityKind(),
                    "OWNER".equals(summary.selfRole()) || "ADMIN".equals(summary.selfRole()),
                    summary.announceOnly() == null ? group.announceOnly() : summary.announceOnly(),
                    group.createdAt()));
        }
        return List.copyOf(completed);
    }

    private static List<AccountGroupsReportedEvent.Group> toReportedGroups(
            List<AccountParticipatingGroupResult.Group> groups) {
        return groups.stream()
                .filter(group -> text(group.groupJid()) != null)
                .map(group -> new AccountGroupsReportedEvent.Group(
                        group.groupJid().trim(),
                        text(group.subject()),
                        group.memberCount(),
                        text(group.ownerJid()),
                        text(group.ownerPhone()),
                        group.admin(),
                        group.announceOnly(),
                        null,
                        group.createdAt()))
                .toList();
    }

    private void refreshInvites(Map<String, ProtocolAccountRef> inviteAccounts) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ProtocolAccountRef> entry : inviteAccounts.entrySet()) {
            try {
                GroupInviteResult invite = protocolPorts.invite().getInvite(
                        entry.getValue(), entry.getKey());
                String inviteCode = inviteCode(invite);
                if (inviteCode != null) {
                    ProtocolAccountRef account = entry.getValue();
                    inviteLinkService.applyCurrentInvite(new GroupInviteLinkObservation(
                            "historical-refresh:" + account.armadaAccountId()
                                    + ":" + entry.getKey() + ":" + now,
                            null, entry.getKey(), inviteCode,
                            account.backend(), SOURCE, now));
                }
            } catch (ProtocolException ex) {
                log.warn("历史群邀请链接刷新失败 accountId={} reasonCode={} httpStatus={}",
                        entry.getValue().armadaAccountId(), ex.errorCode(), ex.httpStatus());
            } catch (RuntimeException ex) {
                log.warn("历史群邀请链接写入失败 accountId={} errorType={}",
                        entry.getValue().armadaAccountId(),
                        ex.getClass().getSimpleName());
            }
        }
    }

    private void requireAccountGroup(Long accountGroupId) {
        if (accountGroupId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号组 ID 不能为空");
        }
        if (accountGroupMapper.selectById(accountGroupId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账号组不存在: " + accountGroupId);
        }
    }

    private static String inviteCode(GroupInviteResult invite) {
        if (invite == null) {
            return null;
        }
        String code = text(invite.inviteCode());
        if (code != null) {
            return code;
        }
        String url = text(invite.inviteUrl());
        if (url == null) {
            return null;
        }
        int slash = url.lastIndexOf('/');
        return slash < 0 || slash == url.length() - 1 ? null : url.substring(slash + 1);
    }

    private static String firstText(String preferred, String fallback) {
        String value = text(preferred);
        return value == null ? text(fallback) : value;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
