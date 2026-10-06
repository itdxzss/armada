package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupProfileCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardGroupSetting;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 在指定时机下发「群信息设置」命令。
 *
 * <p>新群由建群步骤调用；群链接模式设置顺序为拉人前时由接触阶段调用，拉完人后由波次结算调用。
 * 收口阶段永远不调用——群资料是给人看的，拖到收口意味着运营会看着旧群名度过整个料子
 * 管理员阶段。</p>
 *
 * <p>本组件只写动作与 outbox。新群模式由建群状态机等待并核验资料，群链接模式保留
 * 可选设置时机。新群明确失败可重试，未知结果仅在读回确认缺项后补写，共用三次上限。</p>
 */
@Component
public class PullTaskGroupProfileDispatcher {

    private static final int MAX_NEW_GROUP_PROFILE_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(PullTaskGroupProfileDispatcher.class);

    private final PullTaskStandardGroupSettingMapper groupSettingMapper;
    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskGroupAccountMapper groupAccountMapper;
    private final AccountProtocolLookupService accountLookup;
    private final ProtocolCommandOutboxService outboxService;

    public PullTaskGroupProfileDispatcher(
            PullTaskStandardGroupSettingMapper groupSettingMapper,
            PullTaskAccountActionMapper actionMapper,
            PullTaskGroupAccountMapper groupAccountMapper,
            AccountProtocolLookupService accountLookup,
            ProtocolCommandOutboxService outboxService) {
        this.groupSettingMapper = groupSettingMapper;
        this.actionMapper = actionMapper;
        this.groupAccountMapper = groupAccountMapper;
        this.accountLookup = accountLookup;
        this.outboxService = outboxService;
    }

    /**
     * 若当前时机匹配任务配置，则下发一次群信息设置。
     *
     * <p>本方法不推进执行行。新群调用方必须检查动作并核验真实资料，条件不足时保持阻断；
     * 群链接模式的可选资料设置仍由原调用点控制。</p>
     *
     * @param execution 当前执行行
     * @param timing 调用点所处的时机
     * @param now 当前时间(epoch 毫秒)
     */
    public void dispatchIfDue(
            PullTaskGroupExecution execution,
            PullTaskGroupSettingTiming timing,
            long now) {
        if (execution == null || execution.getTaskId() == null) {
            return;
        }
        PullTaskStandardGroupSetting setting =
                groupSettingMapper.selectByTaskId(execution.getTaskId());
        if (setting == null
                || !Integer.valueOf(1).equals(setting.getGroupSettingEnabled())
                || !Objects.equals(setting.getSettingTiming(), timing.code())) {
            return;
        }
        if (execution.getGroupJid() == null || execution.getGroupJid().isBlank()) {
            return;
        }
        // 在途、成功与未知结果均不重发；新群只重试确定失败且尚未耗尽的动作。
        List<PullTaskAccountAction> existing = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.APPLY_GROUP_SETTINGS.code());
        PullTaskAccountAction retry = existing.stream().findFirst().orElse(null);
        if (retry != null && (!Objects.equals(execution.getStage(), PullTaskExecutionStage.GROUP_CREATE.code())
                || !Objects.equals(retry.getActionStatus(), PullTaskActionStatus.FAILED.code())
                || retry.getAttemptNo() == null || retry.getAttemptNo() >= MAX_NEW_GROUP_PROFILE_ATTEMPTS)) {
            return;
        }
        PullTaskGroupAccount actor = availableSettingsActor(execution);
        if (actor == null) {
            // 新群调用方会保持在资料步骤并显示资源不可用；不能改用尚未提权的管理员。
            log.info("群信息设置跳过：无可用群设置执行账号 executionId={}", execution.getId());
            return;
        }
        List<ProtocolAccountRef> accounts = Objects.equals(
                execution.getStage(), PullTaskExecutionStage.GROUP_CREATE.code())
                ? accountLookup.findOnlineProtocolRefs(List.of(actor.getAccountId()))
                : accountLookup.findActiveProtocolRefs(List.of(actor.getAccountId()));
        ProtocolAccountRef account = accounts.stream()
                .filter(ref -> Objects.equals(ref.armadaAccountId(), actor.getAccountId()))
                .findFirst()
                .orElse(null);
        if (account == null) {
            log.info("群信息设置跳过：执行账号协议身份不可用 executionId={}", execution.getId());
            return;
        }
        Long actionId = retry == null ? insertAction(execution, actor, now) : retry.getId();
        if (actionId == null) {
            return;
        }
        submit(execution, actionId, account, null, now);
        log.info("群信息设置命令已提交 executionId={} actionId={} timing={}",
                execution.getId(), actionId, timing);
    }

    /**
     * 未知结果只补写读回明确不符的资料或成员权限；调用方持有执行行锁并已读回同一群。
     *
     * @param execution 当前建群执行行
     * @param action 与本次读回绑定的未知结果动作
     * @param account 当前在线的固定建群人
     * @param repair 本次读回确认的缺失字段
     * @param now 当前时间(epoch 毫秒)
     * @return 已补发返回 true；不可重试或达到三次上限返回 false
     * @throws IllegalStateException 命令或动作更新不完整，调用方事务必须回滚
     */
    public boolean repairUnknownProfile(
            PullTaskGroupExecution execution, PullTaskAccountAction action,
            ProtocolAccountRef account, ProtocolPullTaskGroupProfileCommandRequest.Repair repair, long now) {
        if (!Objects.equals(execution.getStage(), PullTaskExecutionStage.GROUP_CREATE.code())
                || !Objects.equals(action.getActionStatus(), PullTaskActionStatus.UNKNOWN.code())
                || action.getAttemptNo() == null || action.getAttemptNo() >= MAX_NEW_GROUP_PROFILE_ATTEMPTS
                || repair == null || !repair.subject() && !repair.description()
                        && !repair.memberPermissions() && !repair.avatar()) {
            return false;
        }
        submit(execution, action.getId(), account, repair, now);
        return true;
    }

    private void submit(
            PullTaskGroupExecution execution, Long actionId, ProtocolAccountRef account,
            ProtocolPullTaskGroupProfileCommandRequest.Repair repair, long now) {
        ProtocolCommandOutboxEnqueueResult enqueued = outboxService
                .enqueuePullTaskGroupProfileCommands(List.of(
                        new ProtocolPullTaskGroupProfileCommandRequest(
                                execution.getTenantId(), execution.getTaskId(),
                                execution.getId(), actionId, account, repair)));
        if (enqueued.commandIds().size() != 1
                || actionMapper.submitAttempt(actionId,
                repair == null ? SUBMITTABLE : List.of(PullTaskActionStatus.UNKNOWN.code()),
                enqueued.commandIds().get(0), now) != 1) {
            throw new IllegalStateException("群信息设置命令提交状态写入不完整");
        }
    }

    /** 群设置没有对象账号，actor 与 target 同为执行账号角色行本身。 */
    private Long insertAction(
            PullTaskGroupExecution execution, PullTaskGroupAccount actor, long now) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setTenantId(execution.getTenantId());
        row.setTaskId(execution.getTaskId());
        row.setGroupExecutionId(execution.getId());
        row.setActionType(PullTaskAccountActionType.APPLY_GROUP_SETTINGS.code());
        row.setActorGroupAccountId(actor.getId());
        row.setTargetGroupAccountId(actor.getId());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return actionMapper.insertIfAbsent(row) == 1 ? row.getId() : null;
    }

    /**
     * 取一个有权执行群设置的账号；没有则返回 null。
     *
     * <p>建群阶段使用角色 4 建群人，其它阶段使用角色 1 次管理员。只要求角色行存在且未被移出：
     * 协议层确认实际写入，新群建群步骤继续核验真实资料后才推进。</p>
     */
    private PullTaskGroupAccount availableSettingsActor(PullTaskGroupExecution execution) {
        if (Objects.equals(execution.getStage(), PullTaskExecutionStage.GROUP_CREATE.code())) {
            return availableRole(execution.getId(), PullTaskGroupAccountRole.PROMOTER);
        }
        return availableRole(execution.getId(), PullTaskGroupAccountRole.MANAGER);
    }

    /** 建群阶段只有建群人确定具备群主管理权限；既有阶段继续使用任务管理员。 */
    private PullTaskGroupAccount availableRole(
            long executionId,
            PullTaskGroupAccountRole role) {
        return groupAccountMapper.selectByExecutionAndRole(
                        executionId, role.code()).stream()
                .filter(row -> !Objects.equals(row.getAvailabilityStatus(),
                        PullTaskGroupAccountAvailability.REMOVED.code()))
                .findFirst()
                .orElse(null);
    }

    private static final List<Integer> SUBMITTABLE = List.of(
            PullTaskActionStatus.PENDING.code(), PullTaskActionStatus.FAILED.code());
}
