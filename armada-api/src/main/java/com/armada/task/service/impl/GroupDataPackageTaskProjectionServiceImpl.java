package com.armada.task.service.impl;

import com.armada.resource.model.enums.GroupDataPackagePhoneStatus;
import com.armada.resource.service.GroupDataPackageAllocationService;
import com.armada.resource.service.GroupDataPackageAllocationService.AllocationRef;
import com.armada.resource.service.GroupDataPackageAllocationService.Settlement;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.GroupDataPackageTaskProjectionMapper;
import com.armada.task.model.dto.GroupDataPackageTaskFact;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.service.GroupDataPackageTaskProjectionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将最新有效执行投影到资源状态，历史执行和旧分配不能覆盖新事实。 */
@Service
public class GroupDataPackageTaskProjectionServiceImpl implements GroupDataPackageTaskProjectionService {
    private static final int RESULT_BATCH_SIZE = 500;
    private static final Set<String> PRIVACY_REASONS = Set.of("PRIVACY_BLOCKED", "PRIVACY_REJECTED");
    private static final Set<String> UNREGISTERED_REASONS = Set.of("NOT_REGISTERED", "UNREGISTERED");
    private static final List<String> INACTIVE_STATUSES = List.of(
            PullTaskStandardStatus.DRAFT.name(), PullTaskStandardStatus.COMPLETED.name(),
            PullTaskStandardStatus.ENDED.name());
    private final GroupDataPackageTaskProjectionMapper mapper;
    private final GroupDataPackageAllocationService allocationService;

    /** 注入任务事实和独立资源领取服务，避免资源菜单服务循环依赖。 */
    public GroupDataPackageTaskProjectionServiceImpl(GroupDataPackageTaskProjectionMapper mapper,
            GroupDataPackageAllocationService allocationService) {
        this.mapper = mapper;
        this.allocationService = allocationService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void synchronize(List<Long> packageIds) {
        if (packageIds == null || packageIds.isEmpty()) {
            return;
        }
        applyExecutions(mapper.lockLatestExecutions(packageIds));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void synchronizeMaterialMembers(long executionId, List<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty() || mapper.lockSourceExecution(executionId) == null) {
            return;
        }
        for (int offset = 0; offset < memberIds.size(); offset += RESULT_BATCH_SIZE) {
            applyFacts(mapper.selectMemberFacts(List.of(executionId), memberIds.subList(
                    offset, Math.min(offset + RESULT_BATCH_SIZE, memberIds.size()))));
        }
    }

    private void applyFacts(List<GroupDataPackageTaskFact> facts) {
        List<Settlement> settlements = new ArrayList<>();
        List<AllocationRef> released = new ArrayList<>();
        for (GroupDataPackageTaskFact fact : facts) {
            AllocationRef ref = new AllocationRef(fact.phoneId(), fact.allocationVersion(),
                    fact.taskId(), fact.executionSeq());
            if (canRelease(fact)) {
                released.add(ref);
            } else {
                settlements.add(new Settlement(ref, status(fact)));
            }
            if (settlements.size() == RESULT_BATCH_SIZE) {
                allocationService.settle(List.copyOf(settlements));
                settlements.clear();
            }
            if (released.size() == RESULT_BATCH_SIZE) {
                allocationService.release(List.copyOf(released));
                released.clear();
            }
        }
        if (!settlements.isEmpty()) {
            allocationService.settle(settlements);
        }
        if (!released.isEmpty()) {
            allocationService.release(released);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void synchronizeExecution(long executionId) {
        if (mapper.lockSourceExecution(executionId) != null) {
            applyFacts(mapper.selectFacts(List.of(executionId)));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void synchronizeTask(long taskId) {
        applyExecutions(mapper.lockLatestTaskExecutions(taskId));
    }

    /** 执行先按 ID 加锁；资源再按包 ID 升序逐包结算，与最终领取保持相同锁序。 */
    private void applyExecutions(List<PullTaskGroupExecution> executions) {
        java.util.Map<Long, List<Long>> executionIdsByPackage = new java.util.TreeMap<>();
        for (PullTaskGroupExecution execution : executions) {
            executionIdsByPackage.computeIfAbsent(execution.getSourcePackageId(), ignored -> new ArrayList<>())
                    .add(execution.getId());
        }
        for (List<Long> executionIds : executionIdsByPackage.values()) {
            applyFacts(mapper.selectFacts(executionIds));
        }
    }

    @Override
    public Set<String> privacyRejectedPhones(List<String> phones, long cutoff) {
        if (phones == null || phones.isEmpty()) {
            return Set.of();
        }
        Set<String> rejected = new java.util.HashSet<>();
        for (int offset = 0; offset < phones.size(); offset += RESULT_BATCH_SIZE) {
            rejected.addAll(mapper.selectPrivacyRejectedPhones(
                    phones.subList(offset, Math.min(offset + RESULT_BATCH_SIZE, phones.size())),
                    cutoff, PullTaskMaterialPullStatus.FAILED.code(), List.copyOf(PRIVACY_REASONS)));
        }
        return Set.copyOf(rejected);
    }

    @Override
    public void assertNotActivelyUsed(long packageId) {
        if (mapper.hasActiveUsage(packageId, INACTIVE_STATUSES)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "数据包仍被待启动、运行或暂停任务使用，请结束任务后再操作");
        }
    }

    /** 只有最新执行不再运行且能确认整个逻辑分配未产生外部动作，才自动回收。 */
    private static boolean canRelease(GroupDataPackageTaskFact fact) {
        boolean pending = fact.pullStatus() == PullTaskMaterialPullStatus.UNCONSUMED.code()
                || fact.pullStatus() == PullTaskMaterialPullStatus.CANCELED.code();
        return pending && terminal(fact) && fact.activeAttemptId() == null
                && fact.pullCallId() == null && fact.pullFailureCount() == 0
                && !fact.uncertainHistory() && !fact.priorConsumed();
    }

    /** 最新群变化可以令旧成功回到已占用，但不会令号码变成别的任务可用。 */
    private static GroupDataPackagePhoneStatus status(GroupDataPackageTaskFact fact) {
        if (fact.pullStatus() == PullTaskMaterialPullStatus.SUCCESS.code()) {
            return GroupDataPackagePhoneStatus.SUCCESS;
        }
        if (fact.uncertainHistory()) {
            return GroupDataPackagePhoneStatus.UNKNOWN;
        }
        if (fact.pullStatus() == PullTaskMaterialPullStatus.FAILED.code()) {
            String reason = fact.pullReasonCode() == null ? "" : fact.pullReasonCode();
            if (PRIVACY_REASONS.contains(reason)) {
                return GroupDataPackagePhoneStatus.PRIVACY_REJECTED;
            }
            return UNREGISTERED_REASONS.contains(reason)
                    ? GroupDataPackagePhoneStatus.UNREGISTERED
                    : GroupDataPackagePhoneStatus.RETRYABLE_FAILED;
        }
        if (fact.pullStatus() == PullTaskMaterialPullStatus.UNKNOWN.code()
                || terminal(fact) && fact.pullStatus() == PullTaskMaterialPullStatus.SUBMITTED.code()) {
            return GroupDataPackagePhoneStatus.UNKNOWN;
        }
        if (terminal(fact) && (fact.priorConsumed() || fact.pullFailureCount() > 0)) {
            return GroupDataPackagePhoneStatus.RETRYABLE_FAILED;
        }
        return GroupDataPackagePhoneStatus.CLAIMED;
    }

    private static boolean terminal(GroupDataPackageTaskFact fact) {
        return fact.taskDeletedAt() != null
                || INACTIVE_STATUSES.contains(fact.taskStatus())
                || fact.executionStatus() == PullTaskExecutionStatus.ABANDONED.code()
                || fact.executionStatus() == PullTaskExecutionStatus.COMPLETED.code()
                || fact.executionStatus() == PullTaskExecutionStatus.FAILED.code();
    }
}
