package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupSettingsCommandRequest;
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
 * <p>两个调用点共用本组件：设置顺序为拉人前时由接触阶段调用，拉完人后时由波次结算调用。
 * 收口阶段永远不调用——群资料是给人看的，拖到收口意味着运营会看着旧群名度过整个料子
 * 管理员阶段。</p>
 *
 * <p>本组件只写动作行与 outbox，不做任何协议调用，也<b>绝不改动执行行</b>：群资料设置失败
 * 不能阻断拉人。命令只发一次，失败留痕不重发（口径见
 * {@code docs/business/pull-task-group-profile-command-contract.md} §4.2）。</p>
 */
@Component
public class PullTaskGroupProfileDispatcher {

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
     * <p>调用方无需先判断开关或时机，全部条件在本方法内判定；任何不满足都安静跳过，
     * 不抛异常、不返回失败，调用方的主流程不受影响。</p>
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
        // 已经发过就不再发：阶段会重跑，重复提交会产生两条同义命令。
        List<PullTaskAccountAction> existing = actionMapper.selectByExecutionAndType(
                execution.getId(), PullTaskAccountActionType.APPLY_GROUP_SETTINGS.code());
        if (!existing.isEmpty()) {
            return;
        }
        PullTaskGroupAccount manager = availableManager(execution.getId());
        if (manager == null) {
            // 没有可用管理员就跳过：群资料是展示需求，不值得为它卡住执行行。
            log.info("群信息设置跳过：无可用任务管理员 executionId={}", execution.getId());
            return;
        }
        ProtocolAccountRef account = accountLookup
                .findActiveProtocolRefs(List.of(manager.getAccountId())).stream()
                .filter(ref -> Objects.equals(ref.armadaAccountId(), manager.getAccountId()))
                .findFirst()
                .orElse(null);
        if (account == null) {
            log.info("群信息设置跳过：管理员协议账号不可用 executionId={}", execution.getId());
            return;
        }
        Long actionId = insertAction(execution, manager, now);
        if (actionId == null) {
            return;
        }
        // 复用既有群设置入队通道：命令类型由动作行 action_type 决定，路由与租户校验一致。
        ProtocolCommandOutboxEnqueueResult enqueued = outboxService
                .enqueuePullTaskGroupSettingsCommands(List.of(
                        new ProtocolPullTaskGroupSettingsCommandRequest(
                                execution.getTenantId(), execution.getTaskId(),
                                execution.getId(), actionId, account)));
        if (enqueued.commandIds().size() != 1
                || actionMapper.submitAttempt(actionId, SUBMITTABLE,
                enqueued.commandIds().get(0), now) != 1) {
            throw new IllegalStateException("群信息设置命令提交状态写入不完整");
        }
        log.info("群信息设置命令已提交 executionId={} actionId={} timing={}",
                execution.getId(), actionId, timing);
    }

    /** 群设置没有对象账号，actor 与 target 同为管理员角色行本身。 */
    private Long insertAction(
            PullTaskGroupExecution execution, PullTaskGroupAccount manager, long now) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setTenantId(execution.getTenantId());
        row.setTaskId(execution.getTaskId());
        row.setGroupExecutionId(execution.getId());
        row.setActionType(PullTaskAccountActionType.APPLY_GROUP_SETTINGS.code());
        row.setActorGroupAccountId(manager.getId());
        row.setTargetGroupAccountId(manager.getId());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return actionMapper.insertIfAbsent(row) == 1 ? row.getId() : null;
    }

    /**
     * 取一个可执行的任务管理员；没有则返回 null。
     *
     * <p>只要求角色行存在且未被移出：更细的在群与在线判断留给协议层——群资料设置不阻断执行行，
     * 在这里预判反而会让本该发出去的命令被静默丢掉。</p>
     */
    private PullTaskGroupAccount availableManager(long executionId) {
        return groupAccountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.MANAGER.code()).stream()
                .filter(row -> !Objects.equals(row.getAvailabilityStatus(),
                        PullTaskGroupAccountAvailability.REMOVED.code()))
                .findFirst()
                .orElse(null);
    }

    private static final List<Integer> SUBMITTABLE = List.of(
            PullTaskActionStatus.PENDING.code());
}
