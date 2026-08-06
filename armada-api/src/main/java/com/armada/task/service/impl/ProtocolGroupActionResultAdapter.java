package com.armada.task.service.impl;

import com.armada.platform.kafka.consumer.group.ProtocolGroupActionResultReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupActionResultReportedSink;
import com.armada.task.model.dto.PullTaskContactSaveCallback;
import com.armada.task.model.enums.PullTaskContactSaveOutcome;
import com.armada.task.model.dto.PullTaskPullerInviteCallback;
import com.armada.task.model.dto.PullTaskMaterialAdminCallback;
import com.armada.task.model.dto.PullTaskManagerAdminCallback;
import com.armada.task.model.enums.PullTaskManagerAdminProtocolOutcome;
import com.armada.task.model.enums.PullTaskMaterialAdminProtocolOutcome;
import com.armada.task.model.enums.PullTaskPullerInviteProtocolOutcome;
import com.armada.task.service.PullTaskContactSaveResultService;
import com.armada.task.service.PullTaskPullerInviteResultService;
import com.armada.task.service.PullTaskManagerAdminResultService;
import com.armada.task.service.PullTaskProtocolResultCallbackService;
import org.springframework.stereotype.Component;

/** 把协议群动作结果转换为拉群任务域的强类型回调。 */
@Component
public class ProtocolGroupActionResultAdapter implements ProtocolGroupActionResultReportedSink {

    private final PullTaskContactSaveResultService contactSaveResultService;
    private final PullTaskPullerInviteResultService pullerInviteResultService;
    private final PullTaskManagerAdminResultService managerAdminResultService;
    private final PullTaskProtocolResultCallbackService callbackService;

    /**
     * 创建群动作结果适配器。
     *
     * @param contactSaveResultService 联系人保存结果状态机
     * @param pullerInviteResultService 管理员邀请结果状态机
     * @param managerAdminResultService 任务管理员提权结果状态机
     * @param callbackService 批量拉人和料子提权结果状态机
     */
    public ProtocolGroupActionResultAdapter(
            PullTaskContactSaveResultService contactSaveResultService,
            PullTaskPullerInviteResultService pullerInviteResultService,
            PullTaskManagerAdminResultService managerAdminResultService,
            PullTaskProtocolResultCallbackService callbackService) {
        this.contactSaveResultService = contactSaveResultService;
        this.pullerInviteResultService = pullerInviteResultService;
        this.managerAdminResultService = managerAdminResultService;
        this.callbackService = callbackService;
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
        if ("pull_task_material_admin".equals(event.source())) {
            callbackService.handleMaterialAdmin(new PullTaskMaterialAdminCallback(
                    event.tenantId(), event.pullTaskId(), event.groupExecutionId(), event.actionId(),
                    event.accountId(), event.protocolAccountId(), event.commandId(), event.attemptNo(),
                    event.targetJid(), PullTaskMaterialAdminProtocolOutcome.valueOf(event.outcome()),
                    event.reasonCode(), event.reasonMessage(), event.retryable(), event.timestamp()));
            return;
        }
        throw new IllegalArgumentException("不支持的群动作结果来源");
    }
}
