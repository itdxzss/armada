package com.armada.task.scheduler;

import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.enums.PullTaskActionStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 按事实优先级选择管理员提权候选，永久失败候选不会再次返回。 */
@Component
public class PullTaskManagerAdminCandidateSelector {

    /**
     * 选择待核验、未尝试或可重试的提权候选。
     *
     * @param candidates 群域已排序的实时可用管理员候选
     * @param promoterRoles 执行行已有提权角色
     * @param actions 执行行已有管理员设置动作
     * @param managerRoleId 目标任务管理员角色 ID
     * @return 最优候选及其已有角色、动作
     */
    public Optional<Selection> select(
            List<GroupExecutionAccount> candidates,
            List<PullTaskGroupAccount> promoterRoles,
            List<PullTaskAccountAction> actions,
            long managerRoleId) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, PullTaskGroupAccount> rolesByAccount = rolesByAccount(promoterRoles);
        Map<Long, GroupExecutionAccount> candidatesByAccount = candidatesByAccount(candidates);
        List<PullTaskAccountAction> relevant = relevantActions(actions, managerRoleId);

        Optional<Selection> verification = relevant.stream()
                .filter(row -> status(row, PullTaskActionStatus.SUBMITTED)
                        || status(row, PullTaskActionStatus.SUCCESS))
                .map(row -> selection(row, promoterRoles, candidatesByAccount))
                .flatMap(Optional::stream)
                .findFirst();
        if (verification.isPresent()) {
            return verification;
        }

        Optional<Selection> pending = relevant.stream()
                .filter(row -> status(row, PullTaskActionStatus.PENDING))
                .map(row -> selection(row, promoterRoles, candidatesByAccount))
                .flatMap(Optional::stream)
                .findFirst();
        if (pending.isPresent()) {
            return pending;
        }

        for (GroupExecutionAccount candidate : candidates) {
            PullTaskGroupAccount role = rolesByAccount.get(candidate.accountId());
            boolean tried = role != null && relevant.stream().anyMatch(
                    action -> Objects.equals(action.getActorGroupAccountId(), role.getId()));
            if (!tried) {
                return Optional.of(new Selection(candidate, role, null));
            }
        }

        return relevant.stream()
                .filter(PullTaskManagerAdminCandidateSelector::retryable)
                .map(row -> selection(row, promoterRoles, candidatesByAccount))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static List<PullTaskAccountAction> relevantActions(
            List<PullTaskAccountAction> actions, long managerRoleId) {
        if (actions == null) {
            return List.of();
        }
        return actions.stream()
                .filter(Objects::nonNull)
                .filter(row -> Objects.equals(row.getTargetGroupAccountId(), managerRoleId))
                .toList();
    }

    private static Map<Long, PullTaskGroupAccount> rolesByAccount(
            List<PullTaskGroupAccount> roles) {
        Map<Long, PullTaskGroupAccount> result = new HashMap<>();
        if (roles != null) {
            roles.stream().filter(Objects::nonNull)
                    .filter(row -> row.getAccountId() != null)
                    .forEach(row -> result.putIfAbsent(row.getAccountId(), row));
        }
        return result;
    }

    private static Map<Long, GroupExecutionAccount> candidatesByAccount(
            List<GroupExecutionAccount> candidates) {
        Map<Long, GroupExecutionAccount> result = new HashMap<>();
        candidates.stream().filter(Objects::nonNull)
                .filter(row -> row.accountId() != null)
                .forEach(row -> result.putIfAbsent(row.accountId(), row));
        return result;
    }

    private static Optional<Selection> selection(
            PullTaskAccountAction action,
            List<PullTaskGroupAccount> roles,
            Map<Long, GroupExecutionAccount> candidatesByAccount) {
        if (roles == null) {
            return Optional.empty();
        }
        return roles.stream()
                .filter(role -> Objects.equals(role.getId(), action.getActorGroupAccountId()))
                .map(role -> new Selection(
                        candidatesByAccount.get(role.getAccountId()), role, action))
                .filter(row -> row.candidate() != null)
                .findFirst();
    }

    private static boolean status(PullTaskAccountAction action, PullTaskActionStatus status) {
        return Objects.equals(action.getActionStatus(), status.code());
    }

    private static boolean retryable(PullTaskAccountAction action) {
        return (status(action, PullTaskActionStatus.UNKNOWN)
                || status(action, PullTaskActionStatus.FAILED))
                && Boolean.TRUE.equals(action.getRetryable());
    }

    /** 候选、执行行角色和已有动作的组合选择结果。 */
    public record Selection(
            GroupExecutionAccount candidate,
            PullTaskGroupAccount promoterRole,
            PullTaskAccountAction action) {
    }
}
