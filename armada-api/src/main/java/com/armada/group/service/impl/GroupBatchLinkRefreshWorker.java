package com.armada.group.service.impl;

import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.port.GroupInvitePort;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 批量刷新群链接的单项执行器。
 *
 * <p>刷新即用群管理员账号重新拉取当前邀请链接并回填，不吊销旧链接。
 * 协议调用不在事务内，落库统一交给 {@link GroupBatchTaskSettlement} 的独立事务;
 * 并发下同一账号由账号闸门串行。</p>
 */
@Component
public class GroupBatchLinkRefreshWorker {

    private static final Logger log = LoggerFactory.getLogger(GroupBatchLinkRefreshWorker.class);

    private static final String OBSERVATION_SOURCE = "GROUP_BATCH_LINK_REFRESH";
    private static final String NO_ADMIN_CODE = "NO_AVAILABLE_ADMIN";
    private static final String NO_ADMIN_MESSAGE = "系统内没有可用管理员账号";
    private static final String NO_JID_CODE = "GROUP_JID_UNKNOWN";
    private static final String NO_JID_MESSAGE = "群组标识未知，无法读取邀请链接";
    private static final String PROTOCOL_FAILURE_CODE = "INVITE_FETCH_FAILED";
    private static final String PROTOCOL_FAILURE_PREFIX = "读取邀请链接失败：";
    private static final String DB_FAILURE_CODE = "DB_WRITE_FAILED";
    private static final String DB_FAILURE_MESSAGE = "邀请链接写入失败（数据库繁忙），请稍后重试";
    private static final String SUCCESS_MESSAGE = "邀请链接已更新";

    private final GroupBatchRefreshSupport support;
    private final GroupInvitePort invitePort;
    private final GroupInviteLinkService inviteLinkService;

    /** 创建批量刷新群链接执行器。 */
    public GroupBatchLinkRefreshWorker(
            GroupBatchRefreshSupport support,
            GroupInvitePort invitePort,
            GroupInviteLinkService inviteLinkService) {
        this.support = support;
        this.invitePort = invitePort;
        this.inviteLinkService = inviteLinkService;
    }

    /**
     * 执行并结算一条刷新群链接明细。
     *
     * <p>任何失败都只影响本项，旧链接原样保留（BR-05、BR-10）。</p>
     *
     * @param item 待执行明细
     * @param now 结算时间(epoch 毫秒)
     */
    public void execute(GroupBatchTaskItem item, long now) {
        Optional<GroupExecutionAccount> admin = support.selector().findAdmin(item.getGroupLinkId());
        if (admin.isEmpty()) {
            settle(failed(item, null, null, NO_ADMIN_CODE, NO_ADMIN_MESSAGE, now));
            return;
        }
        GroupExecutionAccount account = admin.get();
        String groupJid = support.groupJid(item.getGroupLinkId());
        if (groupJid == null) {
            settle(failed(item, account.accountId(), null, NO_JID_CODE, NO_JID_MESSAGE, now));
            return;
        }
        try {
            GroupInviteResult invite = support.throttle().call(
                    account.accountId(), () -> invitePort.getInvite(account.protocolRef(), groupJid));
            inviteLinkService.applyCurrentInvite(observation(item, account, groupJid, invite, now));
            settle(succeeded(item, account.accountId(), groupJid, now));
        } catch (RuntimeException exception) {
            settle(failure(item, account, groupJid, exception, now));
        }
    }

    /**
     * 按失败来源分类结算。
     *
     * <p>只记异常类型与群标识，不回显邀请码等敏感数据;数据库异常单独给码并按 error 报出。</p>
     */
    private static GroupBatchTaskItem failure(
            GroupBatchTaskItem item,
            GroupExecutionAccount account,
            String groupJid,
            RuntimeException exception,
            long now) {
        if (GroupBatchTaskOutcomes.databaseFailure(exception)) {
            log.error("批量刷新群链接写库失败 groupLinkId={} groupJid={} errorType={}",
                    item.getGroupLinkId(), groupJid, exception.getClass().getSimpleName());
            return failed(item, account.accountId(), groupJid, DB_FAILURE_CODE,
                    DB_FAILURE_MESSAGE + "（" + exception.getClass().getSimpleName() + "）", now);
        }
        log.warn("批量刷新群链接失败 groupLinkId={} groupJid={} errorType={}",
                item.getGroupLinkId(), groupJid, exception.getClass().getSimpleName());
        return failed(item, account.accountId(), groupJid, PROTOCOL_FAILURE_CODE,
                GroupBatchTaskOutcomes.reason(exception, PROTOCOL_FAILURE_PREFIX), now);
    }

    private void settle(GroupBatchTaskItem outcome) {
        support.settlement().settle(outcome);
    }

    private static GroupInviteLinkObservation observation(
            GroupBatchTaskItem item,
            GroupExecutionAccount account,
            String groupJid,
            GroupInviteResult invite,
            long now) {
        return new GroupInviteLinkObservation(
                OBSERVATION_SOURCE + "-" + item.getId(),
                item.getGroupLinkId(),
                groupJid,
                invite == null ? null : invite.inviteCode(),
                ProtocolBackend.fromProtocolId(account.protocolId()),
                OBSERVATION_SOURCE,
                now);
    }

    private static GroupBatchTaskItem succeeded(
            GroupBatchTaskItem item, Long accountId, String groupJid, long now) {
        GroupBatchTaskItem outcome =
                GroupBatchTaskOutcomes.outcome(item, accountId, groupJid, now);
        outcome.setStatus(GroupBatchTaskItemStatus.SUCCESS.code());
        outcome.setDescription(SUCCESS_MESSAGE);
        return outcome;
    }

    private static GroupBatchTaskItem failed(
            GroupBatchTaskItem item,
            Long accountId,
            String groupJid,
            String errorCode,
            String description,
            long now) {
        GroupBatchTaskItem outcome =
                GroupBatchTaskOutcomes.outcome(item, accountId, groupJid, now);
        outcome.setStatus(GroupBatchTaskItemStatus.FAILED.code());
        outcome.setErrorCode(errorCode);
        outcome.setDescription(description);
        return outcome;
    }
}
