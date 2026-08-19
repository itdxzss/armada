package com.armada.task.service.impl;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupSettingsCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskGroupSettingsCallback;
import com.armada.task.model.dto.PullTaskManagerJoinResultTransition;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupSettingItem;
import com.armada.task.model.enums.PullTaskGroupSettingsProtocolOutcome;
import com.armada.task.scheduler.PullTaskExecutionDispatchProperties;
import com.armada.task.service.PullTaskGroupSettingsResultService;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 收敛拉群单项群设置结果。
 *
 * <p>阻断规则集中在本类：放开加人权限是阶段推进条件，成功才追加关闭审核命令并唤醒执行行；
 * 关闭进群审核无论成败都只写动作行，不读也不 CAS 执行行，因此它的结果在执行行推进到后续阶段
 * 之后到达仍能正常落库。</p>
 *
 * <p>「群信息设置」整块命令（{@code group.profile.apply} /
 * {@link PullTaskAccountActionType#APPLY_GROUP_SETTINGS}）与关闭进群审核同形状：只写动作行，
 * 不读也不 CAS 执行行，失败按第一个失败项分派到各自的原因码。</p>
 *
 * <p>它的失败口径（业务确认 2026-08-19）：协议侧对设置项失败恒回 {@code UNKNOWN} 并带
 * {@code retryable}，但 <b>armada 只留 reason_code 供排查与统计，不重试、不重发</b>，
 * 一条命令只发一次。这是二选一而不是漏做：本仓的重试驱动来自执行行被卡在该步（放开加人权限
 * 就是这么重试的），而群资料按业务口径不阻断执行行，执行行径直往后走，没有任何人会回头再发，
 * 两者在现有结构下互斥。协议侧的 {@code retryable} 标志因此目前不消费——将来若加「按动作行扫
 * UNKNOWN」的补发任务再用，别看到这个字段就以为重试已经生效。</p>
 */
@Service
public class PullTaskGroupSettingsResultServiceImpl implements PullTaskGroupSettingsResultService {

    private static final Logger log =
            LoggerFactory.getLogger(PullTaskGroupSettingsResultServiceImpl.class);

    /** 允许被结果覆盖的动作状态。 */
    private static final List<Integer> ACTION_OPEN = List.of(
            PullTaskActionStatus.SUBMITTED.code(), PullTaskActionStatus.UNKNOWN.code());

    /**
     * 允许提交新一轮关闭进群审核命令的动作状态：首次为 PENDING，重发为失败或结果未知。
     *
     * <p>与放开加人权限的 MEMBER_ADD_SUBMITTABLE 取同一组状态，两种群设置动作共用注水器与
     * 结果服务，提交语义必须一致。</p>
     */
    private static final List<Integer> CLOSE_JOIN_APPROVAL_SUBMITTABLE = List.of(
            PullTaskActionStatus.PENDING.code(),
            PullTaskActionStatus.FAILED.code(),
            PullTaskActionStatus.UNKNOWN.code());

    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final AccountProtocolLookupService accountLookup;
    private final ProtocolCommandOutboxService outboxService;
    private final PullTaskExecutionDispatchProperties properties;

    /** 创建拉群群设置结果状态机。 */
    public PullTaskGroupSettingsResultServiceImpl(
            PullTaskAccountActionMapper actionMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskGroupExecutionMapper executionMapper,
            AccountProtocolLookupService accountLookup,
            ProtocolCommandOutboxService outboxService,
            PullTaskExecutionDispatchProperties properties) {
        this.actionMapper = actionMapper;
        this.accountMapper = accountMapper;
        this.executionMapper = executionMapper;
        this.accountLookup = accountLookup;
        this.outboxService = outboxService;
        this.properties = properties;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean apply(PullTaskGroupSettingsCallback callback) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(callback.tenantId());
        try {
            PullTaskAccountAction action = actionMapper.selectByCommandId(callback.commandId());
            if (!matchesAction(action, callback)) {
                return false;
            }
            int targetStatus = actionStatus(callback.outcome());
            if (Objects.equals(action.getActionStatus(), targetStatus)) {
                return true;
            }
            if (action.getActionType() == PullTaskAccountActionType.OPEN_MEMBER_ADD.code()) {
                return applyMemberAdd(action, callback, targetStatus);
            }
            if (action.getActionType()
                    == PullTaskAccountActionType.APPLY_GROUP_SETTINGS.code()) {
                return applyGroupProfile(action, callback, targetStatus);
            }
            return applyJoinApproval(action, callback, targetStatus);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /** 放开加人权限：唯一的阶段推进条件。 */
    private boolean applyMemberAdd(
            PullTaskAccountAction action,
            PullTaskGroupSettingsCallback callback,
            int targetStatus) {
        boolean success = callback.outcome() == PullTaskGroupSettingsProtocolOutcome.SUCCESS;
        PullTaskExecutionReasonCode reason = success ? null : memberAddReason(callback);
        if (actionMapper.transitionManagerAdminResult(
                action.getId(), callback.commandId(), callback.attemptNo(), ACTION_OPEN,
                targetStatus, !success, reason == null ? null : reason.name(),
                reason == null ? null : reason.message(), callback.occurredAt()) != 1) {
            return false;
        }
        PullTaskGroupExecution execution =
                executionMapper.selectById(callback.groupExecutionId());
        if (execution == null) {
            throw new IllegalStateException("群设置结果找不到执行行");
        }
        // 任务被放弃、或执行行已推进到后续阶段时，唤醒 CAS 必然为 0。此时若抛异常会连同动作
        // 收敛一起回滚，消息重投后重复同一路径，形成永远出不来的 poison message。
        // 正确做法是让动作落到终态、跳过唤醒：任务恢复时 ensureGroupSettings 会按动作事实
        // 决定继续还是重发，闭环不依赖这次唤醒。
        if (!awaitingGroupSettings(execution)) {
            log.info("拉群加人权限结果到达时执行行已不在本阶段，只收敛动作 executionId={} "
                            + "actionId={} status={} stage={}",
                    callback.groupExecutionId(), callback.actionId(),
                    execution.getExecutionStatus(), execution.getStage());
            return true;
        }
        if (success) {
            submitJoinApprovalCommand(action, callback, execution);
        }
        long nextRunAt = success ? 0L : callback.occurredAt() + properties.getRetryDelayMs();
        if (executionMapper.transitionManagerJoinResult(new PullTaskManagerJoinResultTransition(
                execution.getId(), execution.getTaskId(), execution.getVersion(),
                new PullTaskManagerJoinResultTransition.Expected(
                        PullTaskExecutionStatus.EXECUTING.code(),
                        PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()),
                new PullTaskManagerJoinResultTransition.Target(
                        PullTaskExecutionStatus.EXECUTING.code(),
                        PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code(),
                        null, null,
                        reason == null ? null : reason.name(),
                        reason == null ? null : reason.message(),
                        nextRunAt, null),
                callback.occurredAt())) != 1) {
            throw new IllegalStateException("群设置执行行唤醒 CAS 失败");
        }
        log.info("拉群加人权限结果收敛 taskId={} executionId={} actionId={} commandId={} "
                        + "attemptNo={} outcome={} reasonCode={}",
                callback.pullTaskId(), callback.groupExecutionId(), callback.actionId(),
                callback.commandId(), callback.attemptNo(), callback.outcome(),
                callback.reasonCode());
        return true;
    }

    /**
     * 关闭进群审核：只写动作行。
     *
     * <p>不读执行行也不 CAS 执行行，因此本方法对执行行已推进到 PULLER_INVITE 甚至更后的阶段
     * 保持可写。失败只留 reason_code 供排查与统计，不重试。</p>
     */
    private boolean applyJoinApproval(
            PullTaskAccountAction action,
            PullTaskGroupSettingsCallback callback,
            int targetStatus) {
        boolean success = callback.outcome() == PullTaskGroupSettingsProtocolOutcome.SUCCESS;
        PullTaskExecutionReasonCode reason =
                success ? null : PullTaskExecutionReasonCode.GROUP_JOIN_APPROVAL_CLOSE_FAILED;
        if (actionMapper.transitionManagerAdminResult(
                action.getId(), callback.commandId(), callback.attemptNo(), ACTION_OPEN,
                targetStatus, false, reason == null ? null : reason.name(),
                reason == null ? null : reason.message(), callback.occurredAt()) != 1) {
            return false;
        }
        if (!success) {
            log.warn("拉群关闭进群审核失败，不阻断阶段推进 taskId={} executionId={} actionId={} "
                            + "commandId={} reasonCode={}",
                    callback.pullTaskId(), callback.groupExecutionId(), callback.actionId(),
                    callback.commandId(), callback.reasonCode());
        }
        return true;
    }

    /**
     * 「群信息设置」整块下发结果：只写动作行。
     *
     * <p>与关闭进群审核同形状——不读执行行也不 CAS 执行行，因此结果晚到（执行行早已推进到拉人
     * 甚至收尾阶段）仍能正常落库。群资料是运营展示需求，拉不拉得到人与它无关，让它阻断执行行
     * 等于用一个展示层问题卡死整条行。</p>
     *
     * <p>失败按<b>第一个失败项</b>分派到各自的原因码，让运营在执行明细里直接看到是哪一项没设上；
     * 只留痕，不重发：一条 {@code group.profile.apply} 命令只发一次，理由见类注释。</p>
     */
    private boolean applyGroupProfile(
            PullTaskAccountAction action,
            PullTaskGroupSettingsCallback callback,
            int targetStatus) {
        boolean success = callback.outcome() == PullTaskGroupSettingsProtocolOutcome.SUCCESS;
        PullTaskExecutionReasonCode reason = success ? null : groupProfileReason(callback);
        if (actionMapper.transitionManagerAdminResult(
                action.getId(), callback.commandId(), callback.attemptNo(), ACTION_OPEN,
                targetStatus, false, reason == null ? null : reason.name(),
                reason == null ? null : reason.message(), callback.occurredAt()) != 1) {
            return false;
        }
        if (!success) {
            log.warn("拉群群信息设置失败，只留痕不阻断执行行 taskId={} executionId={} actionId={} "
                            + "commandId={} failedItem={} reasonCode={}",
                    callback.pullTaskId(), callback.groupExecutionId(), callback.actionId(),
                    callback.commandId(), callback.failedItem(), reason.name());
        }
        return true;
    }

    /**
     * 把协议回传的失败项翻成原因码。
     *
     * <p>八项各有各的码，不许合并成一个笼统码：只回「设置失败」运营还得回去翻协议日志。协议侧
     * 没给失败项时退回群名那一项——它是执行顺序里的第一项，多项失败只回报第一项。</p>
     */
    private static PullTaskExecutionReasonCode groupProfileReason(
            PullTaskGroupSettingsCallback callback) {
        PullTaskGroupSettingItem item = callback.failedItem();
        if (item == null) {
            return PullTaskExecutionReasonCode.GROUP_NAME_SET_FAILED;
        }
        return switch (item) {
            case GROUP_NAME -> PullTaskExecutionReasonCode.GROUP_NAME_SET_FAILED;
            case AVATAR -> PullTaskExecutionReasonCode.GROUP_AVATAR_SET_FAILED;
            case DESCRIPTION -> PullTaskExecutionReasonCode.GROUP_DESCRIPTION_SET_FAILED;
            case MUTE -> PullTaskExecutionReasonCode.GROUP_MUTE_SET_FAILED;
            case EDIT_PERMISSION ->
                    PullTaskExecutionReasonCode.GROUP_EDIT_PERMISSION_SET_FAILED;
            case MEMBER_ADD_PERMISSION ->
                    PullTaskExecutionReasonCode.GROUP_MEMBER_ADD_PERMISSION_SET_FAILED;
            case JOIN_APPROVAL -> PullTaskExecutionReasonCode.GROUP_JOIN_APPROVAL_SET_FAILED;
            case DISAPPEARING_MESSAGE ->
                    PullTaskExecutionReasonCode.GROUP_DISAPPEARING_MESSAGE_SET_FAILED;
        };
    }

    /**
     * 在加人权限成功的同一事务内追加关闭审核动作与命令。
     *
     * <p>不与加人权限同时发出：管理员权限尚未被协议层实际验证可用时，关闭审核必然一并失败，
     * 而它是一次性不重试的，会白白烧掉唯一一次机会。</p>
     */
    private void submitJoinApprovalCommand(
            PullTaskAccountAction memberAddAction,
            PullTaskGroupSettingsCallback callback,
            PullTaskGroupExecution execution) {
        PullTaskGroupAccount manager =
                accountMapper.selectById(memberAddAction.getActorGroupAccountId());
        if (manager == null || manager.getAccountId() == null) {
            throw new IllegalStateException("群设置结果找不到任务管理员角色行");
        }
        // 协议身份取账号当前的活跃事实，而非回调事件里的快照：命令是现在才发出去的。
        ProtocolAccountRef account = accountLookup
                .findActiveProtocolRefs(List.of(manager.getAccountId()))
                .stream()
                .findFirst()
                .orElse(null);
        if (account == null) {
            throw new IllegalStateException("群设置结果任务管理员协议身份不可用");
        }
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setTenantId(callback.tenantId());
        row.setTaskId(callback.pullTaskId());
        row.setGroupExecutionId(callback.groupExecutionId());
        row.setActionType(PullTaskAccountActionType.CLOSE_JOIN_APPROVAL.code());
        // 群设置没有对象账号；actor 与 target 同为管理员角色行本身，使唯一键表达
        // "每个执行行每种群设置至多一行"。
        row.setActorGroupAccountId(memberAddAction.getActorGroupAccountId());
        row.setTargetGroupAccountId(memberAddAction.getActorGroupAccountId());
        row.setCreatedAt(callback.occurredAt());
        row.setUpdatedAt(callback.occurredAt());
        if (actionMapper.insertIfAbsent(row) != 1 || row.getId() == null) {
            // 已存在同键动作说明本执行行已经发过关闭审核命令，不重复发。
            return;
        }
        ProtocolCommandOutboxEnqueueResult enqueued =
                outboxService.enqueuePullTaskGroupSettingsCommands(List.of(
                        new ProtocolPullTaskGroupSettingsCommandRequest(
                                callback.tenantId(), callback.pullTaskId(),
                                execution.getId(), row.getId(), account)));
        // submitAttempt 而非 markSubmitted：关闭进群审核与放开加人权限共用注水器和结果服务，
        // 注水器发真实 attempt_no、结果服务按 attempt_no 对账，两者同属"真实计数"约定。
        // markSubmitted 不递增 attempt_no，首次提交会发出 attemptNo=0，被协议层
        // validateGroupActionCorrelation 的 "attemptNo must be positive" 判为永久校验失败、
        // 提交 offset 后静默丢弃，动作行永远停在已提交且等不到任何结果。
        if (enqueued.commandIds().size() != 1
                || actionMapper.submitAttempt(
                row.getId(), CLOSE_JOIN_APPROVAL_SUBMITTABLE,
                enqueued.commandIds().get(0), callback.occurredAt()) != 1) {
            throw new IllegalStateException("关闭进群审核命令提交状态写入不完整");
        }
    }

    /** 只有仍停在本阶段的执行中行才需要被唤醒。 */
    private static boolean awaitingGroupSettings(PullTaskGroupExecution execution) {
        return Objects.equals(execution.getExecutionStatus(),
                PullTaskExecutionStatus.EXECUTING.code())
                && Objects.equals(execution.getStage(),
                PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
    }

    private static PullTaskExecutionReasonCode memberAddReason(
            PullTaskGroupSettingsCallback callback) {
        return "GROUP_PERMISSION_DENIED".equals(callback.reasonCode())
                ? PullTaskExecutionReasonCode.GROUP_MEMBER_ADD_PERMISSION_DENIED
                : PullTaskExecutionReasonCode.GROUP_MEMBER_ADD_PERMISSION_UNCONFIRMED;
    }

    private static int actionStatus(PullTaskGroupSettingsProtocolOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> PullTaskActionStatus.SUCCESS.code();
            case FAILED -> PullTaskActionStatus.FAILED.code();
            case UNKNOWN -> PullTaskActionStatus.UNKNOWN.code();
        };
    }

    private static boolean matchesAction(
            PullTaskAccountAction action, PullTaskGroupSettingsCallback callback) {
        return action != null
                && Objects.equals(action.getId(), callback.actionId())
                && Objects.equals(action.getTenantId(), callback.tenantId())
                && Objects.equals(action.getTaskId(), callback.pullTaskId())
                && Objects.equals(action.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(action.getCommandId(), callback.commandId())
                && Objects.equals(action.getAttemptNo(), callback.attemptNo())
                && isGroupSettingsAction(action.getActionType());
    }

    private static boolean isGroupSettingsAction(Integer actionType) {
        return actionType != null
                && (actionType == PullTaskAccountActionType.OPEN_MEMBER_ADD.code()
                || actionType == PullTaskAccountActionType.CLOSE_JOIN_APPROVAL.code()
                || actionType == PullTaskAccountActionType.APPLY_GROUP_SETTINGS.code());
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
