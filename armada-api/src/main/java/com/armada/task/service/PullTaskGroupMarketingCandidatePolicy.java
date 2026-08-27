package com.armada.task.service;

import com.armada.task.model.enums.PullTaskGroupCandidateStatus;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateRow;

/** 根据持久化群组、账号和占用事实计算候选群组可选结论。 */
public final class PullTaskGroupMarketingCandidatePolicy {

    private static final int HEALTH_AVAILABLE = 1;
    private static final int HEALTH_LINK_INVALID = 2;
    private static final int HEALTH_UNAVAILABLE = 3;

    private PullTaskGroupMarketingCandidatePolicy() {
    }

    /**
     * 计算候选群组当前状态。
     *
     * <p>普通离线管理员仍允许把群加入等待池；实际协议操作必须等账号恢复在线后
     * 再次校验。</p>
     *
     * @param row 群组聚合事实
     * @param operatorId 当前登录用户 ID
     * @param reservationToken 当前创建页等待池标识
     * @param revealOccupiedTaskName 是否允许暴露占用任务名称
     * @return 可选择结论
     */
    public static Decision evaluate(
            PullTaskGroupMarketingCandidateRow row,
            long operatorId,
            String reservationToken,
            boolean revealOccupiedTaskName) {
        boolean currentPool = row.getOccupancyType() != null
                && reservationToken != null
                && reservationToken.equals(row.getReservationToken())
                && Long.valueOf(operatorId).equals(row.getOccupiedBy());
        if (currentPool) {
            Decision underlying = evaluateUnoccupied(row);
            return decision(
                    underlying.status(),
                    false,
                    true,
                    underlying.selectable() ? "已在当前等待任务池" : underlying.disabledReason());
        }
        if (row.getOccupancyType() != null) {
            String taskName = !revealOccupiedTaskName || blank(row.getOccupiedTaskName())
                    ? "其他等待池或任务"
                    : row.getOccupiedTaskName();
            return decision(PullTaskGroupCandidateStatus.OCCUPIED,
                    false, false, "当前群组正在任务「" + taskName + "」中使用");
        }
        return evaluateUnoccupied(row);
    }

    private static Decision evaluateUnoccupied(PullTaskGroupMarketingCandidateRow row) {
        if (zero(row.getAdminRelationCount()) == 0) {
            return decision(PullTaskGroupCandidateStatus.NO_ADMIN_PERMISSION,
                    false, false, "当前平台账号均不是群创建者或管理员");
        }
        if (zero(row.getEligibleAccountCount()) == 0) {
            return decision(PullTaskGroupCandidateStatus.NO_ELIGIBLE_ACCOUNT,
                    false, false, "群内管理账号已封禁、解绑或失效");
        }
        if (Boolean.TRUE.equals(row.getBanned())) {
            return decision(PullTaskGroupCandidateStatus.GROUP_BANNED,
                    false, false, "群组已封禁");
        }
        Integer health = row.getHealthStatus();
        if (health == null) {
            return decision(PullTaskGroupCandidateStatus.UNKNOWN,
                    false, false, "群组健康状态未知，请先重新同步");
        }
        if (health == HEALTH_LINK_INVALID) {
            return decision(PullTaskGroupCandidateStatus.LINK_INVALID,
                    false, false, "群邀请链接已失效");
        }
        if (health == HEALTH_UNAVAILABLE || health != HEALTH_AVAILABLE) {
            return decision(PullTaskGroupCandidateStatus.GROUP_UNAVAILABLE,
                    false, false, blank(row.getHealthError()) ? "群组当前不可用" : row.getHealthError());
        }
        if (zero(row.getOnlineAccountCount()) == 0) {
            return decision(PullTaskGroupCandidateStatus.WAITING_ACCOUNT_ONLINE,
                    true, false, "管理账号当前离线，入池后等待恢复在线");
        }
        return decision(PullTaskGroupCandidateStatus.NORMAL, true, false, null);
    }

    private static Decision decision(
            PullTaskGroupCandidateStatus status,
            boolean selectable,
            boolean currentPool,
            String reason) {
        return new Decision(status, selectable, currentPool, reason);
    }

    private static int zero(Integer value) {
        return value == null ? 0 : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 候选群组派生选择状态。 */
    public record Decision(
            PullTaskGroupCandidateStatus status,
            boolean selectable,
            boolean inCurrentWaitingPool,
            String disabledReason) {
    }
}
