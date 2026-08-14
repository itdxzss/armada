package com.armada.task.scheduler;

import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.task.model.dto.PullTaskMemberFact;
import com.armada.task.model.dto.PullTaskMemberQueryRequest;
import com.armada.task.model.dto.PullTaskMemberQueryResult;
import com.armada.task.model.dto.PullTaskManagerAdminWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 优先按协议回执推进管理员设置，仅在无明确回执时查询群权限事实兜底。 */
@Component
public class PullTaskManagerAdminProcessor {

    private final PullTaskManagerAdminTransactionService transactions;
    private final PullTaskMemberQueryAwaitService memberQueryAwaitService;

    /** 创建管理员设置阶段处理器。 */
    public PullTaskManagerAdminProcessor(
            PullTaskManagerAdminTransactionService transactions,
            PullTaskMemberQueryAwaitService memberQueryAwaitService) {
        this.transactions = transactions;
        this.memberQueryAwaitService = memberQueryAwaitService;
    }

    /** 执行一条处于 MANAGER_ADMIN 阶段的执行行。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        PullTaskManagerAdminPreparation preparation =
                transactions.prepare(candidate, lockOwner, now);
        return processPreparation(candidate, lockOwner, now, preparation);
    }

    private PullTaskExecutionDispatchResult processPreparation(
            PullTaskGroupExecution candidate,
            String lockOwner,
            long now,
            PullTaskManagerAdminPreparation preparation) {
        if (preparation.discoveryRequest() != null) {
            return discoverAdmin(candidate, lockOwner, now, preparation.discoveryRequest());
        }
        if (!preparation.ready()) {
            return preparation.result();
        }
        PullTaskManagerAdminWork work = preparation.work();
        Integer actionStatus = work.action().getActionStatus();
        if (Objects.equals(actionStatus, PullTaskActionStatus.SUCCESS.code())) {
            return transactions.confirmManagerAdmin(work, now);
        }
        boolean needsFallbackObservation = Objects.equals(
                actionStatus, PullTaskActionStatus.SUBMITTED.code())
                || Objects.equals(actionStatus, PullTaskActionStatus.UNKNOWN.code());
        if (!needsFallbackObservation) {
            return transactions.submitOrDefer(work, now);
        }
        String promoterJid = WhatsappJids.userJid(work.promoter().wsPhone());
        String managerJid = WhatsappJids.userJid(work.manager().getAccountPhone());
        PullTaskMemberQueryResult query = memberQueryAwaitService.readOrDefer(
                work.tenantId(), new PullTaskMemberQueryRequest(
                        work.taskId(), work.executionId(),
                        "manager-admin-membership:" + work.action().getId()
                                + ":" + work.promoter().accountId()
                                + ":post:" + work.action().getAttemptNo(),
                        PullTaskMemberQueryPurpose.MANAGER_ADMIN_MEMBERSHIP,
                        work.promoter().protocolRef(), work.groupJid(),
                        java.util.List.of(promoterJid, managerJid)),
                work.expectedVersion(), work.lockOwner(),
                PullTaskExecutionStage.MANAGER_ADMIN.code(), now);
        if (query.state() == PullTaskMemberQueryResult.State.PENDING) {
            return PullTaskExecutionDispatchResult.DEFERRED;
        }
        if (query.state() == PullTaskMemberQueryResult.State.FAILED) {
            return transactions.deferObservation(work, now);
        }
        PullTaskManagerAdminObservation observation = new PullTaskManagerAdminObservation(
                hasAdmin(query.members(), promoterJid), hasAdmin(query.members(), managerJid));
        if (observation.managerAlreadyAdmin()) {
            return transactions.confirmManagerAdmin(work, now);
        }
        if (!observation.promoterStillAdmin()) {
            return transactions.rejectPromoter(work, now);
        }
        return transactions.submitOrDefer(work, now);
    }

    private PullTaskExecutionDispatchResult discoverAdmin(
            PullTaskGroupExecution candidate,
            String lockOwner,
            long now,
            PullTaskMemberQueryRequest request) {
        PullTaskMemberQueryResult query = memberQueryAwaitService.readOrDefer(
                candidate.getTenantId(), request, candidate.getVersion(), lockOwner,
                PullTaskExecutionStage.MANAGER_ADMIN.code(), now);
        if (query.state() == PullTaskMemberQueryResult.State.PENDING) {
            return PullTaskExecutionDispatchResult.DEFERRED;
        }
        if (query.state() == PullTaskMemberQueryResult.State.FAILED) {
            return transactions.deferDiscovery(candidate, lockOwner, now);
        }
        PullTaskManagerAdminPreparation refreshed =
                transactions.prepareAfterDiscovery(candidate, lockOwner, now);
        if (refreshed.discoveryRequest() != null) {
            throw new IllegalStateException("管理员定点查询结果不得重复创建 discovery");
        }
        return processPreparation(candidate, lockOwner, now, refreshed);
    }

    private static boolean hasAdmin(
            java.util.List<PullTaskMemberFact> members, String targetJid) {
        if (members == null || members.isEmpty()) {
            return false;
        }
        return members.stream()
                .filter(Objects::nonNull)
                .filter(PullTaskMemberFact::admin)
                .filter(member -> targetJid.equals(member.targetJid()))
                .findAny()
                .isPresent();
    }
}
