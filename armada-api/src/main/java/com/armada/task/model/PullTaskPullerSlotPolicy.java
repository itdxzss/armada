package com.armada.task.model;

import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import java.util.Objects;
import java.util.Set;

/** 自动选号、资源恢复及人工补充共用的拉手名额规则。 */
public final class PullTaskPullerSlotPolicy {

    /** 旧角色已由新账号接替，只保留历史，不再自动恢复。 */
    public static final String REPLACED_REASON = "PULLER_REPLACED";

    private static final Set<String> TEMPORARY_OFFLINE_REASONS = Set.of(
            "ACCOUNT_NOT_ONLINE", "ACCOUNT_OFFLINE", "ACCOUNT_UNAVAILABLE", "LOGIN_REPLACED", "PROXY_FAILED");

    private PullTaskPullerSlotPolicy() {
    }

    /** 暂时连接中断可以等待上线；解绑、封禁和账号缺失不属于此范围。 */
    public static boolean isTemporaryOfflineReason(String reasonCode) {
        return reasonCode != null && TEMPORARY_OFFLINE_REASONS.contains(reasonCode);
    }

    /** 供持久化筛选复用与调度一致的临时离线原因白名单；返回不可变集合。 */
    public static Set<String> temporaryOfflineReasons() {
        return TEMPORARY_OFFLINE_REASONS;
    }

    /** 已选角色因临时连接中断等待原账号恢复，保留名额和既有业务进度。 */
    public static boolean waitingForOnline(PullTaskGroupAccount row) {
        return Objects.equals(row.getAvailabilityStatus(), PullTaskGroupAccountAvailability.OFFLINE.code())
                && isTemporaryOfflineReason(row.getUnavailableReasonCode());
    }

    /** 已发出的进群请求必须先收敛结果，暂时离线不能让同一名额被重复补充。 */
    public static boolean awaitingJoinResult(PullTaskGroupAccount row) {
        return Objects.equals(row.getMembershipStatus(), PullTaskGroupAccountMembershipStatus.JOINING.code())
                || Objects.equals(row.getMembershipStatus(), PullTaskGroupAccountMembershipStatus.UNKNOWN.code())
                || Objects.equals(row.getMembershipStatus(), PullTaskGroupAccountMembershipStatus.PENDING_APPROVAL.code());
    }

    /** 返回已占用角色是否保留名额；eligibleIds 来自账号域的实时拉手资格校验。 */
    public static boolean occupiesSlot(PullTaskGroupAccount row, Set<Long> eligibleIds) {
        if (Objects.equals(row.getAvailabilityStatus(), PullTaskGroupAccountAvailability.REMOVED.code())
                || Objects.equals(row.getMembershipStatus(), PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code())
                && !isTemporaryOfflineReason(row.getMembershipReasonCode())) {
            return false;
        }
        // 资源等待可能已释放账号租约；已发出的请求仍保留计划名额，防止结果未知时超补。
        return awaitingJoinResult(row)
                || row.getReleasedAt() == null
                && (waitingForOnline(row)
                || Objects.equals(row.getAvailabilityStatus(), PullTaskGroupAccountAvailability.AVAILABLE.code())
                && eligibleIds.contains(row.getAccountId()));
    }

    /** 新账号占位成功后可退出的旧角色；在途进群和已退出角色不得被再次替换。 */
    public static boolean replaceable(PullTaskGroupAccount row, Set<Long> eligibleIds) {
        return !Objects.equals(row.getAvailabilityStatus(), PullTaskGroupAccountAvailability.REMOVED.code())
                && !awaitingJoinResult(row) && !occupiesSlot(row, eligibleIds);
    }
}
