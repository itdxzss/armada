package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.dto.PullTaskFactStatusCriteria;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskParticipantAggregateTransition;
import com.armada.task.model.dto.PullTaskParticipantAttemptBinding;
import com.armada.task.model.dto.PullTaskParticipantPlanBinding;
import com.armada.task.model.dto.PullTaskStationBinding;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.vo.PullTaskGroupAccountRoleCount;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 执行行角色账号数据访问层。 */
@Mapper
public interface PullTaskGroupAccountMapper {

    /** 按主键读取当前租户的角色账号事实。 */
    PullTaskGroupAccount selectById(@Param("id") long id);

    /**
     * 为执行行选中一个角色账号。
     *
     * <p>拉手行插入即取得跨任务占用：生成列 {@code occupancy_key} 在
     * {@code role_type=2 且 released_at IS NULL} 时取账号 ID，唯一键保证同一账号同时
     * 只服务一条执行行（ADR-0008）。冲突时抛
     * {@link org.springframework.dao.DuplicateKeyException}，调用方应把它当作
     * "该拉手已被占用"的预期路径处理，让本行进入等待拉手，不得当系统错误上抛。</p>
     *
     * @param row 角色账号；写入后回填 id
     * @return 新增行数
     */
    int insertInitialized(PullTaskGroupAccount row);

    /** 初始化角色事实；角色判断留在 Java，XML 只持久化明确值。 */
    default int insert(PullTaskGroupAccount row) {
        row.setMembershipStatus(PullTaskGroupAccountMembershipStatus.NOT_JOINED.code());
        row.setAdminStatus(row.getRoleType() == PullTaskGroupAccountRole.MANAGER.code()
                ? PullTaskGroupAccountAdminStatus.PENDING.code()
                : PullTaskGroupAccountAdminStatus.NOT_APPLICABLE.code());
        row.setAvailabilityStatus(PullTaskGroupAccountAvailability.AVAILABLE.code());
        return insertInitialized(row);
    }

    /**
     * 读取执行行内某个角色的全部账号，按 roleSeq 升序。
     *
     * @param groupExecutionId 执行行 ID
     * @param roleType 角色，取值见 PullTaskGroupAccountRole
     * @return 角色账号列表
     */
    List<PullTaskGroupAccount> selectByExecutionAndRole(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("roleType") int roleType);

    /** 选择失败次数未达上限且没有活动 attempt 的待拉站台。 */
    List<PullTaskGroupAccount> selectPendingStationsByGuard(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("guard") PullTaskParticipantAttemptBinding.Guard guard,
            @Param("limit") int limit);

    /** 使用普通链接站台固定守卫选择待拉池。 */
    default List<PullTaskGroupAccount> selectPendingStations(long groupExecutionId, int limit) {
        return selectPendingStationsByGuard(
                groupExecutionId, PullTaskParticipantAttemptBinding.stationGuard(), limit);
    }

    /**
     * 按角色统计执行行当前可用账号数，供详情页现算"当前数 / 计划数"。
     *
     * @param groupExecutionId 执行行 ID
     * @return 每个角色一行；没有可用账号的角色不出现在结果里
     */
    List<PullTaskGroupAccountRoleCount> countAvailableByRole(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("availabilityStatus") int availabilityStatus);

    /** 详情读模型兼容入口；可用状态码由 Java 枚举传入 XML。 */
    default List<PullTaskGroupAccountRoleCount> countAvailableByRole(long groupExecutionId) {
        return countAvailableByRole(
                groupExecutionId,
                com.armada.task.model.enums.PullTaskGroupAccountAvailability.AVAILABLE.code());
    }

    /**
     * 释放单个拉手的跨任务占用。
     *
     * @param id 角色账号行 ID
     * @param now 释放时间(epoch 毫秒)
     * @return 实际释放行数
     */
    int releasePuller(@Param("id") long id,
                      @Param("roleType") int roleType,
                      @Param("now") long now);

    /** 执行域兼容入口；角色码由 Java 枚举传给 XML。 */
    default int releasePuller(long id, long now) {
        return releasePuller(
                id, com.armada.task.model.enums.PullTaskGroupAccountRole.PULLER.code(), now);
    }

    /**
     * 执行行恢复时重新占用原拉手。
     *
     * <p>该账号已被其他执行行占走时抛
     * {@link org.springframework.dao.DuplicateKeyException}，这是"恢复时重新竞争拉手"
     * 的预期结果，调用方据此让本行继续等待。</p>
     *
     * @param id 角色账号行 ID
     * @param now 重新占用时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int reoccupyPuller(@Param("id") long id,
                       @Param("roleType") int roleType,
                       @Param("now") long now);

    /** 执行域兼容入口；角色码由 Java 枚举传给 XML。 */
    default int reoccupyPuller(long id, long now) {
        return reoccupyPuller(
                id, com.armada.task.model.enums.PullTaskGroupAccountRole.PULLER.code(), now);
    }

    /**
     * 释放执行行下全部仍在占用中的拉手。
     *
     * <p>执行行完成、失败、被人工暂停或进入资源等待时调用。管理与站台角色不参与占用，
     * 不受影响。</p>
     *
     * @param groupExecutionId 执行行 ID
     * @param roleType 拉手角色码，由 Java 枚举传入
     * @param now 释放时间(epoch 毫秒)
     * @return 实际释放行数
     */
    int releaseAllPullersOfExecution(@Param("groupExecutionId") long groupExecutionId,
                                     @Param("roleType") int roleType,
                                     @Param("now") long now);

    /** 执行域兼容入口；角色码仍由 Java 枚举传入 XML。 */
    default int releaseAllPullersOfExecution(long groupExecutionId, long now) {
        return releaseAllPullersOfExecution(
                groupExecutionId,
                com.armada.task.model.enums.PullTaskGroupAccountRole.PULLER.code(),
                now);
    }

    /** 任务暂停或结束时释放其全部拉手占用。 */
    int releaseAllPullersOfTask(@Param("taskId") long taskId,
                                @Param("roleType") int roleType,
                                @Param("now") long now);

    /**
     * 标记账号在本执行行不可用。
     *
     * @param id 角色账号行 ID
     * @param availabilityStatus 可用性，取值见 PullTaskGroupAccountAvailability
     * @param reasonCode 不可用原因码
     * @param cooldownUntil 风控冷却到期时间(epoch 毫秒)；非冷却场景传 null
     * @param now 更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int markUnavailable(@Param("id") long id,
                        @Param("availabilityStatus") int availabilityStatus,
                        @Param("reasonCode") String reasonCode,
                        @Param("cooldownUntil") Long cooldownUntil,
                        @Param("now") long now);

    /**
     * 查询指定候选中仍带某种账号级不可用事实的拉手账号。
     *
     * <p>查询不要求角色行仍占用：执行行进入资源等待后会释放租约，但尚未完成真实校验的
     * 风控账号仍不能被另一个父任务重新选中。</p>
     *
     * @param accountIds 候选账号 ID，调用方保证非空
     * @param roleType 拉手角色码
     * @param availabilityStatus 要排除的可用性状态
     * @return 命中账号 ID，已去重
     */
    List<Long> selectAccountIdsByAvailability(
            @Param("accountIds") List<Long> accountIds,
            @Param("roleType") int roleType,
            @Param("availabilityStatus") int availabilityStatus);

    /** 从候选集合中查询当前仍被拉手角色占用的账号 ID。 */
    List<Long> selectOccupiedAccountIds(
            @Param("accountIds") List<Long> accountIds,
            @Param("roleType") int roleType);

    /**
     * 对已经通过实时在线正常校验的账号恢复到期冷却事实。
     *
     * <p>只有调用方显式传入的账号、预期状态匹配且冷却时间已到才更新；
     * {@code cooldown_until IS NULL} 代表不定时恢复，永远不会被本方法命中。</p>
     *
     * @param accountIds 已通过真实可用性校验的账号 ID，调用方保证非空
     * @param roleType 拉手角色码
     * @param expectedAvailabilityStatus 预期风控冷却状态
     * @param targetAvailabilityStatus 恢复后的可用状态
     * @param now 当前时间(epoch 毫秒)
     * @return 恢复的角色事实行数
     */
    int restoreExpiredPullerCooldowns(
            @Param("accountIds") List<Long> accountIds,
            @Param("roleType") int roleType,
            @Param("expectedAvailabilityStatus") int expectedAvailabilityStatus,
            @Param("targetAvailabilityStatus") int targetAvailabilityStatus,
            @Param("now") long now);

    /**
     * 对已通过实时在线正常校验的账号恢复指定可用性事实。
     *
     * <p>调用方只应把已完成真实校验的账号 ID 传入；风控冷却仍使用带到期条件的专用
     * 方法，不能通过本入口绕过冷却时长。</p>
     */
    int restoreValidatedAvailability(
            @Param("accountIds") List<Long> accountIds,
            @Param("roleType") int roleType,
            @Param("expectedAvailabilityStatuses") List<Integer> expectedAvailabilityStatuses,
            @Param("targetAvailabilityStatus") int targetAvailabilityStatus,
            @Param("now") long now);

    /**
     * 回写账号的在群状态。
     *
     * @param id 角色账号行 ID
     * @param membershipStatus 在群状态，取值见 PullTaskGroupAccountMembershipStatus
     * @param joinedAt 确认在群时间(epoch 毫秒)；非成功场景传 null
     * @param now 更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int updateMembership(@Param("id") long id,
                         @Param("membershipStatus") int membershipStatus,
                         @Param("joinedAt") Long joinedAt,
                         @Param("now") long now);

    /** 从入群中/未知等允许状态 CAS 收敛角色账号的在群结果。 */
    int transitionMembership(@Param("transition") PullTaskFactTransition transition);

    /** 把未分配的补充站台按角色、来源和可用性 CAS 绑定到一次调用。 */
    int bindStationToPullCall(@Param("binding") PullTaskStationBinding binding);

    /** 以角色、可用性、失败上限和空活动指针 CAS 绑定站台 attempt。 */
    int bindMembershipAttemptIfEligible(
            @Param("binding") PullTaskParticipantPlanBinding binding,
            @Param("guard") PullTaskParticipantAttemptBinding.Guard guard);

    /** 使用普通链接站台固定守卫绑定 attempt。 */
    default int bindMembershipAttempt(PullTaskParticipantPlanBinding binding) {
        return bindMembershipAttemptIfEligible(
                binding, PullTaskParticipantAttemptBinding.stationGuard());
    }

    /** 兼容旧单调用规划入口；真实拉手仍只在提交阶段写入聚合。 */
    default int bindMembershipAttempt(PullTaskParticipantAttemptBinding binding) {
        return bindMembershipAttempt(new PullTaskParticipantPlanBinding(
                binding.participantId(), binding.attemptId(),
                binding.pullCallId(), binding.now()));
    }

    /** 批次真实提交时记录站台最近执行拉手。 */
    int markMembershipAttemptSubmitted(
            @Param("binding") PullTaskParticipantAttemptBinding binding);

    /** 当前活动 attempt 与失败计数匹配时推进站台聚合状态。 */
    int transitionMembershipAttempt(
            @Param("transition") PullTaskParticipantAggregateTransition transition);

    /** 单调提升站台为在群；迟到成功不得清除更新 attempt 的活动指针。 */
    int promoteMembershipSuccess(
            @Param("transition") PullTaskParticipantAggregateTransition transition);

    /** 统计一次拉人调用中仍处于指定在群状态的站台数量。 */
    int countByPullCallAndMembershipStatuses(
            @Param("criteria") PullTaskFactStatusCriteria criteria);

    /** 任务结束时释放尚未提交协议命令的计划站台。 */
    int cancelPlannedStationMembershipByTask(
            @Param("taskId") long taskId,
            @Param("roleType") int roleType,
            @Param("expectedStatus") int expectedStatus,
            @Param("plannedCallStatus") int plannedCallStatus,
            @Param("targetStatus") int targetStatus,
            @Param("now") long now);

    /** 单群结束时释放尚未提交协议命令的计划站台。 */
    int cancelPlannedStationMembershipByExecution(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("roleType") int roleType,
            @Param("expectedStatus") int expectedStatus,
            @Param("plannedCallStatus") int plannedCallStatus,
            @Param("targetStatus") int targetStatus,
            @Param("now") long now);

    /** Outbox 已确认未发布时取消站台提交；发布不明只标未知并保留活动绑定。 */
    int cancelUnpublishedSubmittedStationMembership(
            @Param("taskId") long taskId,
            @Param("groupExecutionId") Long groupExecutionId,
            @Param("roleType") int roleType,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("canceledOutboxStatus") int canceledOutboxStatus,
            @Param("releaseBinding") boolean releaseBinding,
            @Param("now") long now);

    /** 以允许的原状态集合 CAS 更新管理账号实时权限事实。 */
    int transitionAdminStatus(
            @Param("id") long id,
            @Param("expectedAdminStatuses") List<Integer> expectedAdminStatuses,
            @Param("adminStatus") int adminStatus,
            @Param("now") long now);
}
