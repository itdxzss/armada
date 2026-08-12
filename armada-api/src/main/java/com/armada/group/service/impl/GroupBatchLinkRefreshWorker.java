package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupExecutionAccountSelector;
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
 * 协议调用不在事务内，落库统一交给 {@link GroupBatchTaskSettlement} 的独立事务。</p>
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
    private static final String SUCCESS_MESSAGE = "邀请链接已更新";
    private static final int DESCRIPTION_MAX_LENGTH = 512;

    private final GroupExecutionAccountSelector selector;
    private final GroupInvitePort invitePort;
    private final GroupInviteLinkService inviteLinkService;
    private final GroupLinkPreviewMapper previewMapper;
    private final GroupBatchTaskSettlement settlement;

    /** 创建批量刷新群链接执行器。 */
    public GroupBatchLinkRefreshWorker(
            GroupExecutionAccountSelector selector,
            GroupInvitePort invitePort,
            GroupInviteLinkService inviteLinkService,
            GroupLinkPreviewMapper previewMapper,
            GroupBatchTaskSettlement settlement) {
        this.selector = selector;
        this.invitePort = invitePort;
        this.inviteLinkService = inviteLinkService;
        this.previewMapper = previewMapper;
        this.settlement = settlement;
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
        Optional<GroupExecutionAccount> admin = selector.findAdmin(item.getGroupLinkId());
        if (admin.isEmpty()) {
            settlement.settle(failed(item, NO_ADMIN_CODE, NO_ADMIN_MESSAGE, null, null, now));
            return;
        }
        GroupExecutionAccount account = admin.get();
        String groupJid = groupJid(item.getGroupLinkId());
        if (groupJid == null) {
            settlement.settle(failed(
                    item, NO_JID_CODE, NO_JID_MESSAGE, account.accountId(), null, now));
            return;
        }
        try {
            GroupInviteResult invite = invitePort.getInvite(account.protocolRef(), groupJid);
            inviteLinkService.applyCurrentInvite(observation(item, account, groupJid, invite, now));
            settlement.settle(succeeded(item, account.accountId(), groupJid, now));
        } catch (RuntimeException exception) {
            // 只记异常类型与脱敏摘要，不回显邀请码等敏感数据。
            log.warn("批量刷新群链接失败 groupLinkId={} errorType={}",
                    item.getGroupLinkId(), exception.getClass().getSimpleName());
            settlement.settle(failed(
                    item,
                    PROTOCOL_FAILURE_CODE,
                    reason(exception),
                    account.accountId(),
                    groupJid,
                    now));
        }
    }

    private String groupJid(Long groupLinkId) {
        GroupLinkPreview preview = previewMapper.selectByGroupLinkId(groupLinkId);
        String groupJid = preview == null ? null : preview.getGroupJid();
        return groupJid == null || groupJid.isBlank() ? null : groupJid.trim();
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
        GroupBatchTaskItem outcome = outcome(item, accountId, groupJid, now);
        outcome.setStatus(GroupBatchTaskItemStatus.SUCCESS.code());
        outcome.setDescription(SUCCESS_MESSAGE);
        return outcome;
    }

    private static GroupBatchTaskItem failed(
            GroupBatchTaskItem item,
            String errorCode,
            String description,
            Long accountId,
            String groupJid,
            long now) {
        GroupBatchTaskItem outcome = outcome(item, accountId, groupJid, now);
        outcome.setStatus(GroupBatchTaskItemStatus.FAILED.code());
        outcome.setErrorCode(errorCode);
        outcome.setDescription(description);
        return outcome;
    }

    private static GroupBatchTaskItem outcome(
            GroupBatchTaskItem item, Long accountId, String groupJid, long now) {
        GroupBatchTaskItem outcome = new GroupBatchTaskItem();
        outcome.setId(item.getId());
        outcome.setTaskId(item.getTaskId());
        outcome.setGroupLinkId(item.getGroupLinkId());
        outcome.setAccountId(accountId);
        outcome.setGroupJid(groupJid);
        outcome.setOperatedAt(now);
        outcome.setUpdatedAt(now);
        return outcome;
    }

    private static String reason(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "读取邀请链接失败：" + exception.getClass().getSimpleName();
        }
        String trimmed = message.trim();
        return trimmed.length() > DESCRIPTION_MAX_LENGTH
                ? trimmed.substring(0, DESCRIPTION_MAX_LENGTH)
                : trimmed;
    }
}
