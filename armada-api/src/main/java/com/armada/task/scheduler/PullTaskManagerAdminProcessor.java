package com.armada.task.scheduler;

import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.task.model.dto.PullTaskManagerAdminWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskActionStatus;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 在事务外核验群权限事实，并驱动管理员设置短事务。 */
@Component
public class PullTaskManagerAdminProcessor {

    private final PullTaskManagerAdminTransactionService transactions;
    private final GroupMemberListPort memberListPort;

    /** 创建管理员设置阶段处理器。 */
    public PullTaskManagerAdminProcessor(
            PullTaskManagerAdminTransactionService transactions,
            GroupMemberListPort memberListPort) {
        this.transactions = transactions;
        this.memberListPort = memberListPort;
    }

    /** 执行一条处于 MANAGER_ADMIN 阶段的执行行。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        PullTaskManagerAdminPreparation preparation =
                transactions.prepare(candidate, lockOwner, now);
        if (!preparation.ready()) {
            return preparation.result();
        }
        PullTaskManagerAdminWork work = preparation.work();
        PullTaskManagerAdminObservation observation;
        try {
            observation = observe(
                    memberListPort.list(work.memberQuery()),
                    work.promoter().wsPhone(),
                    work.manager().getAccountPhone());
        } catch (RuntimeException exception) {
            return transactions.deferObservation(work, now);
        }
        if (observation.managerAlreadyAdmin()) {
            return transactions.confirmManagerAdmin(work, now);
        }
        if (!observation.promoterStillAdmin()) {
            return transactions.rejectPromoter(work, now);
        }
        if (Objects.equals(
                work.action().getActionStatus(), PullTaskActionStatus.SUCCESS.code())) {
            return transactions.deferUnconfirmed(work, now);
        }
        return transactions.submitOrDefer(work, now);
    }

    private static PullTaskManagerAdminObservation observe(
            List<GroupParticipantResult> members,
            String promoterPhone,
            String managerPhone) {
        return new PullTaskManagerAdminObservation(
                hasAdmin(members, promoterPhone), hasAdmin(members, managerPhone));
    }

    private static boolean hasAdmin(List<GroupParticipantResult> members, String accountPhone) {
        if (members == null || members.isEmpty()) {
            return false;
        }
        String expected = phone(accountPhone);
        return members.stream()
                .filter(Objects::nonNull)
                .filter(member -> Boolean.TRUE.equals(member.admin())
                        || Boolean.TRUE.equals(member.owner()))
                .filter(member -> expected.equals(phone(
                        member.phone() == null ? member.jid() : member.phone())))
                .findAny()
                .isPresent();
    }

    private static String phone(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        int at = normalized.indexOf('@');
        if (at >= 0) {
            normalized = normalized.substring(0, at);
        }
        int device = normalized.indexOf(':');
        if (device >= 0) {
            normalized = normalized.substring(0, device);
        }
        return normalized.replaceAll("[^0-9]", "");
    }
}
