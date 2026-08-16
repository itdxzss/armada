package com.armada.task.mapper;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.task.model.dto.PullTaskParticipantAttemptTransition;
import com.armada.task.model.dto.PullTaskPullWaveCandidate;
import com.armada.task.model.dto.PullTaskPlannedCallPullerBinding;
import com.armada.task.model.dto.PullTaskLegacyPullerGenerationBinding;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 批量拉人逐号码执行台账数据访问层。 */
@Mapper
public interface PullTaskPullCallMemberAttemptMapper {

    /** 写入一条已经填好生命周期的执行记录并回填主键。 */
    int insertInitialized(PullTaskPullCallMemberAttempt row);

    /** 新规划记录占用活动槽位。 */
    default int insertPlanned(PullTaskPullCallMemberAttempt row) {
        row.setLifecycleStatus(PullTaskParticipantAttemptStatus.PLANNED.code());
        row.setActiveSlot(1);
        return insertInitialized(row);
    }

    /** 按批次读取冻结参与者，使用台账主键保持规划顺序。 */
    List<PullTaskPullCallMemberAttempt> selectByCall(@Param("pullCallId") long pullCallId);

    /** 按主键读取逐号码执行记录，用于迟到成功检查更新活动 attempt。 */
    PullTaskPullCallMemberAttempt selectById(@Param("id") long id);

    /** 按批次和生命周期读取不可变提交集合。 */
    List<PullTaskPullCallMemberAttempt> selectByCallAndStatus(
            @Param("pullCallId") long pullCallId,
            @Param("lifecycleStatus") int lifecycleStatus);

    /** 计算参与者下一单调执行序号；取消和未知释放都不复用旧序号。 */
    int selectNextAttemptNo(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("participantType") int participantType,
            @Param("participantRefId") long participantRefId);

    /** 按批次和冻结目标 JID 定位逐号码回调。 */
    PullTaskPullCallMemberAttempt selectByCallAndTarget(
            @Param("pullCallId") long pullCallId,
            @Param("targetJid") String targetJid);

    /** 以生命周期和租户条件 CAS 写入一次执行事实。 */
    int transition(@Param("transition") PullTaskParticipantAttemptTransition transition);

    /** 批次提交时把其完整计划集合一次性推进为已提交。 */
    int transitionCallParticipants(
            @Param("pullCallId") long pullCallId,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("now") long now);

    /** 使用固定生命周期提交当前批次参与者。 */
    default int markSubmittedByCall(long pullCallId, long now) {
        return transitionCallParticipants(
                pullCallId,
                PullTaskParticipantAttemptStatus.PLANNED.code(),
                PullTaskParticipantAttemptStatus.SUBMITTED.code(),
                now);
    }

    /**
     * 从指定已结算波次读取明确失败或已释放的可重试参与者。
     *
     * <p>明确失败按原因码白名单筛选：Web 与 Android 两端协议对逐成员结果的判定都是
     * "明确失败中只有 TIMEOUT 可重试"，其余（PRIVACY_BLOCKED、GROUP_FULL、
     * GROUP_JOIN_REJECTED 等）属于确定性终态，换拉手重试也不会成功。白名单让协议层
     * 未来新增的未知原因码默认不重试，与协议侧判定保持同向。</p>
     *
     * <p>限流与账号受限走 UNKNOWN + UNCERTAIN 分支，不受本白名单约束：那类失败归属于
     * 拉手账号而非目标号码，换个拉手就能成功，必须继续重试。</p>
     */
    default List<PullTaskPullWaveCandidate> selectRetryCandidatesByWave(
            long pullWaveId, long maxFailureCount) {
        return selectRetryCandidatesByWaveInternal(
                pullWaveId,
                maxFailureCount,
                List.of(ProtocolErrorCode.TIMEOUT.name()));
    }

    /**
     * 可重试候选查询的底层入口；业务原因码由 Java 参数传入，不写死在 XML。
     *
     * @param pullWaveId                 已结算波次 ID
     * @param maxFailureCount            单参与者累计明确失败次数上限
     * @param retryableFailureReasonCodes 明确失败中仍允许重试的原因码
     * @return 可进入下一波次的参与者
     */
    List<PullTaskPullWaveCandidate> selectRetryCandidatesByWaveInternal(
            @Param("pullWaveId") long pullWaveId,
            @Param("maxFailureCount") long maxFailureCount,
            @Param("retryableFailureReasonCodes") List<String> retryableFailureReasonCodes);

    /** 统计波次内仍未关闭、释放或取消的 attempt。 */
    int countOpenByWave(
            @Param("pullWaveId") long pullWaveId,
            @Param("openStatuses") List<Integer> openStatuses);

    /** 把调用下全部计划 attempt 绑定到与调用相同的拉手代际。 */
    int bindPlannedPullerByCall(
            @Param("binding") PullTaskPlannedCallPullerBinding binding);

    /** 把历史开放调用下尚未标记波次的 attempt 原样挂到新波次。 */
    int attachLegacyCallAttemptsToWave(
            @Param("pullCallId") long pullCallId,
            @Param("pullWaveId") long pullWaveId,
            @Param("now") long now);

    /** 为与粘性拉手相同的历史 attempt 补齐分配代际。 */
    int bindLegacyPullerGeneration(
            @Param("binding") PullTaskLegacyPullerGenerationBinding binding);

    /** 任务结束时取消尚未提交协议命令的计划参与者，并释放活动槽位。 */
    int cancelPlannedByTask(
            @Param("taskId") long taskId,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("plannedCallStatus") int plannedCallStatus,
            @Param("executionState") String executionState,
            @Param("reasonCode") String reasonCode,
            @Param("reasonMessage") String reasonMessage,
            @Param("now") long now);

    /** 单群结束时取消尚未提交协议命令的计划参与者，并释放活动槽位。 */
    int cancelPlannedByExecution(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("plannedCallStatus") int plannedCallStatus,
            @Param("executionState") String executionState,
            @Param("reasonCode") String reasonCode,
            @Param("reasonMessage") String reasonMessage,
            @Param("now") long now);

    /** 取消 Outbox 已确认未发布的参与者；已发布或发布结果不明的记录不动。 */
    int cancelUnpublishedSubmitted(
            @Param("taskId") long taskId,
            @Param("groupExecutionId") Long groupExecutionId,
            @Param("expectedStatus") int expectedStatus,
            @Param("targetStatus") int targetStatus,
            @Param("canceledOutboxStatus") int canceledOutboxStatus,
            @Param("executionState") String executionState,
            @Param("reasonCode") String reasonCode,
            @Param("reasonMessage") String reasonMessage,
            @Param("now") long now);
}
