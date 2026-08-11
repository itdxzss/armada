package com.armada.task.scheduler;

import com.armada.group.service.GroupInviteLinkService;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.task.model.dto.PullTaskManagerJoinWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 管理员进群协议执行器；链接失效恢复仅允许查询当前邀请码并重试一次。 */
@Component
public class PullTaskManagerJoinProtocolExecutor {

    private static final Logger log = LoggerFactory.getLogger(
            PullTaskManagerJoinProtocolExecutor.class);
    private static final Set<String> REFRESHABLE_INVITE_FAILURE_CODES = Set.of(
            "INVITE_INVALID", "INVITE_REVOKED");

    private final GroupJoinPort joinPort;
    private final GroupInviteLinkService inviteLinkService;

    /**
     * 创建管理员进群协议执行器。
     *
     * @param joinPort 统一进群协议端口
     * @param inviteLinkService 当前群邀请链接事实服务
     */
    public PullTaskManagerJoinProtocolExecutor(
            GroupJoinPort joinPort,
            GroupInviteLinkService inviteLinkService) {
        this.joinPort = joinPort;
        this.inviteLinkService = inviteLinkService;
    }

    /**
     * 执行普通进群，或在上次邀请码失效后用当前邀请码执行唯一一次恢复重试。
     *
     * @param candidate 当前执行行及上次失败原因
     * @param work 已持久化的管理员进群工作项
     * @return 可由事务服务收敛的进群结果
     */
    public PullTaskManagerJoinOutcome join(
            PullTaskGroupExecution candidate,
            PullTaskManagerJoinWork work) {
        GroupJoinCommand command = work.joinCommand();
        if (requiresInviteRefresh(candidate)) {
            Optional<String> currentInviteCode = refreshInvite(candidate);
            if (currentInviteCode.isEmpty()) {
                return PullTaskManagerJoinOutcome.executionFailed(candidate.getReasonCode());
            }
            command = new GroupJoinCommand(
                    work.payload().account(),
                    PullTaskGroupJoinArgumentResolver.resolveCurrentInviteCode(
                            work.payload().account().backend(), currentInviteCode.get()),
                    work.payload().operationId());
        }
        PullTaskManagerJoinOutcome outcome = outcome(joinPort.join(command));
        if (outcome.kind() == PullTaskManagerJoinOutcome.Kind.CONFIRMED
                && candidate.getGroupLinkId() != null) {
            inviteLinkService.bindGroupJid(
                    candidate.getGroupLinkId(), outcome.groupJid(), System.currentTimeMillis());
        }
        return outcome;
    }

    private Optional<String> refreshInvite(PullTaskGroupExecution candidate) {
        try {
            return inviteLinkService.refreshCurrentInviteCode(
                    candidate.getGroupLinkId(), candidate.getGroupJid(), candidate.getInviteCode());
        } catch (ProtocolException exception) {
            log.warn("管理员主动查询当前邀请码失败 executionId={} groupLinkId={} "
                            + "reasonCode={} errorCode={}",
                    candidate.getId(), candidate.getGroupLinkId(),
                    candidate.getReasonCode(), exception.errorCode());
            return Optional.empty();
        }
    }

    private static boolean requiresInviteRefresh(PullTaskGroupExecution candidate) {
        return candidate != null
                && REFRESHABLE_INVITE_FAILURE_CODES.contains(candidate.getReasonCode());
    }

    private static PullTaskManagerJoinOutcome outcome(GroupJoinResult result) {
        if (result != null && result.outcome() == GroupJoinOutcome.PENDING_APPROVAL) {
            return PullTaskManagerJoinOutcome.pendingApproval(result.groupJid());
        }
        if (result == null || !result.joined()
                || result.groupJid() == null || result.groupJid().isBlank()) {
            return PullTaskManagerJoinOutcome.unconfirmed(
                    result == null ? null : result.groupJid(),
                    PullTaskExecutionReasonCode.MANAGER_MEMBERSHIP_UNCONFIRMED.name());
        }
        return PullTaskManagerJoinOutcome.confirmed(result.groupJid());
    }
}
