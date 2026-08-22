package com.armada.task.service.impl;

import com.armada.platform.kafka.consumer.group.ProtocolGroupActionResultReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupActionResultReportedSink;
import com.armada.task.model.dto.PullTaskContactSaveCallback;
import com.armada.task.model.dto.PullTaskCreatorLeaveCallback;
import com.armada.task.model.enums.PullTaskContactSaveOutcome;
import com.armada.task.model.dto.PullTaskPullerInviteCallback;
import com.armada.task.model.dto.PullTaskMaterialAdminCallback;
import com.armada.task.model.dto.PullTaskGroupSettingsCallback;
import com.armada.task.model.dto.PullTaskManagerAdminCallback;
import com.armada.task.model.enums.PullTaskGroupSettingsProtocolOutcome;
import com.armada.task.model.enums.PullTaskCreatorLeaveOperation;
import com.armada.task.model.enums.PullTaskCreatorLeaveProtocolOutcome;
import com.armada.task.model.enums.PullTaskManagerAdminProtocolOutcome;
import com.armada.task.model.enums.PullTaskMaterialAdminProtocolOutcome;
import com.armada.task.model.enums.PullTaskPullerInviteProtocolOutcome;
import com.armada.task.service.PullTaskContactSaveResultService;
import com.armada.task.service.PullTaskCreatorLeaveResultService;
import com.armada.task.service.PullTaskPullerInviteResultService;
import com.armada.task.service.PullTaskGroupSettingsResultService;
import com.armada.task.service.PullTaskManagerAdminResultService;
import com.armada.task.service.PullTaskProtocolResultCallbackService;
import org.springframework.stereotype.Component;

/** 把协议群动作结果转换为拉群任务域的强类型回调。 */
@Component
public class ProtocolGroupActionResultAdapter implements ProtocolGroupActionResultReportedSink {

    private final PullTaskContactSaveResultService contactSaveResultService;
    private final PullTaskPullerInviteResultService pullerInviteResultService;
    private final PullTaskManagerAdminResultService managerAdminResultService;
    private final PullTaskGroupSettingsResultService groupSettingsResultService;
    private final PullTaskProtocolResultCallbackService callbackService;
    private final PullTaskCreatorLeaveResultService creatorLeaveResultService;

    /**
     * 创建群动作结果适配器。
     *
     * @param contactSaveResultService 联系人保存结果状态机
     * @param pullerInviteResultService 管理员邀请结果状态机
     * @param managerAdminResultService 任务管理员提权结果状态机
     * @param groupSettingsResultService 群设置结果状态机
     * @param callbackService 批量拉人和料子提权结果状态机
     * @param creatorLeaveResultService 群主退群结果状态机
     */
    public ProtocolGroupActionResultAdapter(
            PullTaskContactSaveResultService contactSaveResultService,
            PullTaskPullerInviteResultService pullerInviteResultService,
            PullTaskManagerAdminResultService managerAdminResultService,
            PullTaskGroupSettingsResultService groupSettingsResultService,
            PullTaskProtocolResultCallbackService callbackService,
            PullTaskCreatorLeaveResultService creatorLeaveResultService) {
        this.contactSaveResultService = contactSaveResultService;
        this.pullerInviteResultService = pullerInviteResultService;
        this.managerAdminResultService = managerAdminResultService;
        this.groupSettingsResultService = groupSettingsResultService;
        this.callbackService = callbackService;
        this.creatorLeaveResultService = creatorLeaveResultService;
    }

    /** {@inheritDoc} */
    @Override
    public void handleActionResultReported(ProtocolGroupActionResultReportedEvent event) {
        if ("pull_task_contact_save".equals(event.source())) {
            contactSaveResultService.apply(new PullTaskContactSaveCallback(
                    event.tenantId(), event.pullTaskId(), event.groupExecutionId(), event.actionId(),
                    event.accountId(), event.protocolAccountId(), event.commandId(), event.attemptNo(),
                    PullTaskContactSaveOutcome.valueOf(event.outcome()), event.reasonCode(),
                    event.reasonMessage(), event.retryable(), event.timestamp()));
            return;
        }
        if ("pull_task_puller_invite".equals(event.source())) {
            pullerInviteResultService.apply(new PullTaskPullerInviteCallback(
                    event.tenantId(), event.pullTaskId(), event.groupExecutionId(), event.actionId(),
                    event.accountId(), event.protocolAccountId(), event.commandId(), event.attemptNo(),
                    event.targetJid(), PullTaskPullerInviteProtocolOutcome.valueOf(event.outcome()),
                    event.reasonCode(), event.reasonMessage(), event.retryable(), event.timestamp()));
            return;
        }
        if ("pull_task_manager_admin".equals(event.source())) {
            managerAdminResultService.apply(new PullTaskManagerAdminCallback(
                    event.tenantId(), event.pullTaskId(), event.groupExecutionId(), event.actionId(),
                    event.accountId(), event.protocolAccountId(), event.commandId(), event.attemptNo(),
                    event.targetJid(), PullTaskManagerAdminProtocolOutcome.valueOf(event.outcome()),
                    event.reasonCode(), event.reasonMessage(), event.retryable(), event.timestamp()));
            return;
        }
        if ("pull_task_group_settings".equals(event.source())) {
            // 群设置改的是群属性，事件没有 targetJid。放开加人权限与关闭进群审核仍是一条命令
            // 一个设置项，命令级 outcome 就是该设置项的结果。
            // 「群信息设置」整块下发的失败项还没从协议事件里透出：
            // ProtocolGroupActionResultReportedEvent 尚无对应字段，补齐前只能传 null。
            groupSettingsResultService.apply(new PullTaskGroupSettingsCallback(
                    event.tenantId(), event.pullTaskId(), event.groupExecutionId(),
                    event.actionId(), event.accountId(), event.protocolAccountId(),
                    event.commandId(), event.attemptNo(),
                    PullTaskGroupSettingsProtocolOutcome.valueOf(event.outcome()),
                    null, event.reasonCode(), event.reasonMessage(), event.timestamp()));
            return;
        }
        if ("pull_task_material_admin".equals(event.source())) {
            callbackService.handleMaterialAdmin(new PullTaskMaterialAdminCallback(
                    event.tenantId(), event.pullTaskId(), event.groupExecutionId(), event.actionId(),
                    event.accountId(), event.protocolAccountId(), event.commandId(), event.attemptNo(),
                    event.targetJid(), PullTaskMaterialAdminProtocolOutcome.valueOf(event.outcome()),
                    event.reasonCode(), event.reasonMessage(), event.retryable(), event.timestamp()));
            return;
        }
        if ("pull_task_creator_leave".equals(event.source())) {
            creatorLeaveResultService.apply(new PullTaskCreatorLeaveCallback(
                    event.tenantId(), event.pullTaskId(), event.groupExecutionId(), event.actionId(),
                    event.accountId(), event.protocolAccountId(), event.commandId(), event.attemptNo(),
                    "PARTICIPANT_PROMOTE".equals(event.operation())
                            ? PullTaskCreatorLeaveOperation.PROMOTE
                            : PullTaskCreatorLeaveOperation.LEAVE,
                    event.targetJid(), PullTaskCreatorLeaveProtocolOutcome.valueOf(event.outcome()),
                    event.reasonCode(), event.reasonMessage(), event.timestamp()));
            return;
        }
        throw new IllegalArgumentException("不支持的群动作结果来源");
    }
}
