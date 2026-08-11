package com.armada.task.scheduler;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import com.armada.task.model.dto.PullTaskMemberAddPermissionWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 优先处理补充拉手踩链接；首次启动时再确认权限并提交管理—拉手联系人命令。 */
@Component
public class PullTaskManagerPullerContactProcessor {

    private final PullTaskManagerPullerContactTransactionService transactions;
    private final PullTaskSupplementPullerProcessor supplementProcessor;
    private final FixedAccountGroupMetadataPort metadataPort;
    private final GroupSettingsPort settingsPort;

    /** 创建联系人阶段处理器。 */
    public PullTaskManagerPullerContactProcessor(
            PullTaskManagerPullerContactTransactionService transactions,
            PullTaskSupplementPullerProcessor supplementProcessor,
            FixedAccountGroupMetadataPort metadataPort,
            GroupSettingsPort settingsPort) {
        this.transactions = transactions;
        this.supplementProcessor = supplementProcessor;
        this.metadataPort = metadataPort;
        this.settingsPort = settingsPort;
    }

    /**
     * 补充拉手直接踩链接；没有补充指令时才执行首次启动的权限确认和联系人 Outbox。
     *
     * <p>设置和回读均在事务外执行；未确认时保留当前阶段并延迟重试，禁止提前占用拉手。</p>
     */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        var supplementResult = supplementProcessor.processIfPresent(candidate, lockOwner, now);
        if (supplementResult.isPresent()) {
            return supplementResult.get();
        }
        PullTaskMemberAddPermissionPreparation permission =
                transactions.prepareMemberAddPermission(candidate, lockOwner, now);
        if (!permission.ready()) {
            return permission.result();
        }
        Optional<PullTaskExecutionDispatchResult> permissionResult =
                ensureMemberAddPermission(permission.work(), now);
        if (permissionResult.isPresent()) {
            return permissionResult.get();
        }
        return transactions.prepare(candidate, lockOwner, now);
    }

    private Optional<PullTaskExecutionDispatchResult> ensureMemberAddPermission(
            PullTaskMemberAddPermissionWork work,
            long now) {
        try {
            if (memberAddAllowed(work)) {
                return Optional.empty();
            }
            settingsPort.setAddMembersAllowed(work.manager(), work.groupJid(), true);
            if (memberAddAllowed(work)) {
                return Optional.empty();
            }
            return Optional.of(transactions.deferMemberAddPermission(
                    work,
                    PullTaskExecutionReasonCode.GROUP_MEMBER_ADD_PERMISSION_UNCONFIRMED,
                    now));
        } catch (ProtocolException exception) {
            PullTaskExecutionReasonCode reason = exception.errorCode()
                    == ProtocolErrorCode.GROUP_PERMISSION_DENIED
                    ? PullTaskExecutionReasonCode.GROUP_MEMBER_ADD_PERMISSION_DENIED
                    : PullTaskExecutionReasonCode.GROUP_MEMBER_ADD_PERMISSION_UNCONFIRMED;
            return Optional.of(transactions.deferMemberAddPermission(work, reason, now));
        }
    }

    private boolean memberAddAllowed(PullTaskMemberAddPermissionWork work) {
        GroupMetadataResult metadata = metadataPort.getMetadata(
                work.manager(), work.groupJid());
        return metadata != null && Boolean.TRUE.equals(metadata.memberAddMode());
    }
}
