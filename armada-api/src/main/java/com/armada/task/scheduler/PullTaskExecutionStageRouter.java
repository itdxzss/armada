package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import org.springframework.stereotype.Component;

/** 把共享调度器已抢占的执行行分发到当前业务阶段处理器。 */
@Component
public class PullTaskExecutionStageRouter {

    private final PullTaskLinkValidationProcessor linkValidationProcessor;
    private final PullTaskManagerJoinProcessor managerJoinProcessor;
    private final PullTaskManagerAdminProcessor managerAdminProcessor;
    private final PullTaskManagerPullerContactProcessor managerPullerContactProcessor;
    private final PullTaskPullerInviteProcessor pullerInviteProcessor;
    private final PullTaskPullExecutionProcessor pullExecutionProcessor;
    private final PullTaskMaterialAdminProcessor materialAdminProcessor;

    /**
     * @param linkValidationProcessor      链接校验处理器
     * @param managerJoinProcessor         管理员入群处理器
     * @param managerAdminProcessor        任务管理员权限设置处理器
     * @param managerPullerContactProcessor 管理—拉手联系人处理器
     * @param pullerInviteProcessor         管理员邀请拉手处理器
     * @param pullExecutionProcessor        站台和料子拉人处理器
     * @param materialAdminProcessor         A/a 料子提权处理器
     */
    public PullTaskExecutionStageRouter(
            PullTaskLinkValidationProcessor linkValidationProcessor,
            PullTaskManagerJoinProcessor managerJoinProcessor,
            PullTaskManagerAdminProcessor managerAdminProcessor,
            PullTaskManagerPullerContactProcessor managerPullerContactProcessor,
            PullTaskPullerInviteProcessor pullerInviteProcessor,
            PullTaskPullExecutionProcessor pullExecutionProcessor,
            PullTaskMaterialAdminProcessor materialAdminProcessor) {
        this.linkValidationProcessor = linkValidationProcessor;
        this.managerJoinProcessor = managerJoinProcessor;
        this.managerAdminProcessor = managerAdminProcessor;
        this.managerPullerContactProcessor = managerPullerContactProcessor;
        this.pullerInviteProcessor = pullerInviteProcessor;
        this.pullExecutionProcessor = pullExecutionProcessor;
        this.materialAdminProcessor = materialAdminProcessor;
    }

    /** 根据持久化阶段执行一次有界动作。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate,
            String lockOwner,
            long now) {
        if (candidate.getStage() == PullTaskExecutionStage.LINK_VALIDATION.code()) {
            return linkValidationProcessor.process(candidate, lockOwner, now);
        }
        if (candidate.getStage() == PullTaskExecutionStage.MANAGER_JOIN.code()) {
            return managerJoinProcessor.process(candidate, lockOwner, now);
        }
        if (candidate.getStage() == PullTaskExecutionStage.MANAGER_ADMIN.code()) {
            return managerAdminProcessor.process(candidate, lockOwner, now);
        }
        if (candidate.getStage() == PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()) {
            return managerPullerContactProcessor.process(candidate, lockOwner, now);
        }
        if (candidate.getStage() == PullTaskExecutionStage.PULLER_INVITE.code()) {
            return pullerInviteProcessor.process(candidate, lockOwner, now);
        }
        if (candidate.getStage() == PullTaskExecutionStage.PULL_EXECUTION.code()) {
            return pullExecutionProcessor.process(candidate, lockOwner, now);
        }
        if (candidate.getStage() == PullTaskExecutionStage.MATERIAL_ADMIN.code()) {
            return materialAdminProcessor.process(candidate, lockOwner, now);
        }
        if (candidate.getStage() == PullTaskExecutionStage.CLOSING.code()) {
            return pullExecutionProcessor.close(candidate, lockOwner, now);
        }
        return PullTaskExecutionDispatchResult.LOST;
    }
}
