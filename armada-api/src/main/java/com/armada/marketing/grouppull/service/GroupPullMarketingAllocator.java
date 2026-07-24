package com.armada.marketing.grouppull.service;

import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingAccountStat;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecutionMaterial;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingMaterial;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullBlockReason;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStage;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullResourceStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullSpeakPermission;
import com.armada.marketing.grouppull.model.vo.GroupPullAccountRefRow;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 在独立短事务内为拉群营销任务原子分配一套建群资源。 */
@Service
public class GroupPullMarketingAllocator {

    private static final Logger log = LoggerFactory.getLogger(GroupPullMarketingAllocator.class);
    private static final int MAX_INFLIGHT_EXECUTIONS = 5;
    private static final int MAX_BUILDER_CONFLICT_RETRIES = 5;
    private static final int PENDING_OPERATION = 1;

    private final GroupPullMarketingMapper mapper;
    private final MarketingAccountOccupancyService occupancyService;
    private final TransactionTemplate transactionTemplate;

    public GroupPullMarketingAllocator(GroupPullMarketingMapper mapper,
                                       MarketingAccountOccupancyService occupancyService,
                                       PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.occupancyService = occupancyService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 尝试分配一个建群执行；资源不足只更新对应阻塞原因，不留下任何半占用。
     *
     * @param taskId 拉群营销统一任务 ID
     * @return 本次分配结果
     */
    public AllocationResult allocateOne(Long taskId) {
        for (int attempt = 1; attempt <= MAX_BUILDER_CONFLICT_RETRIES; attempt++) {
            try {
                AllocationAttempt result = transactionTemplate.execute(status -> allocateInTransaction(taskId, status));
                if (result == null) {
                    throw new BusinessException(ErrorCode.CONFLICT, "拉群营销资源分配事务未返回结果");
                }
                if (result.outcome == InternalOutcome.RETRY_BUILDER) {
                    continue;
                }
                AllocationResult publicResult = toPublicResult(result);
                recordBlockingReason(taskId, publicResult.outcome());
                return publicResult;
            } catch (DuplicateKeyException ex) {
                if (isBuilderExecutionConflict(ex) && attempt < MAX_BUILDER_CONFLICT_RETRIES) {
                    continue;
                }
                throw ex;
            }
        }
        AllocationResult result = new AllocationResult(Outcome.WAIT_BUILDER, null);
        recordBlockingReason(taskId, result.outcome());
        return result;
    }

    private AllocationAttempt allocateInTransaction(Long taskId,
                                                    org.springframework.transaction.TransactionStatus status) {
        MarketingTask task = mapper.selectTaskForUpdate(taskId);
        GroupPullMarketingTask extension = mapper.selectTaskByIdForUpdate(taskId);
        if (task == null || extension == null
                || !Integer.valueOf(MarketingTaskStatus.SENDING.code()).equals(task.getStatus())
                || !Integer.valueOf(GroupPullResourceStatus.LOCKED.code()).equals(extension.getResourceStatus())) {
            return new AllocationAttempt(InternalOutcome.NOT_RUNNABLE, null);
        }
        if (mapper.countInflightExecutions(taskId) >= MAX_INFLIGHT_EXECUTIONS) {
            return new AllocationAttempt(InternalOutcome.CONCURRENCY_FULL, null);
        }

        GroupPullAccountRefRow builder = mapper.selectBuilderCandidateForUpdate(
                taskId, extension.getBuilderGroupId());
        if (builder == null) {
            return new AllocationAttempt(InternalOutcome.WAIT_BUILDER, null);
        }
        long now = System.currentTimeMillis();
        if (!occupancyService.tryOccupyTaskAccount(taskId, builder.getAccountId(), now)) {
            status.setRollbackOnly();
            return new AllocationAttempt(InternalOutcome.RETRY_BUILDER, null);
        }

        GroupPullAccountRefRow marketer = mapper.selectMarketerCandidateForUpdate(
                taskId, task.getAccountGroupId(), extension.getMarketingAccountGroupLimit());
        if (marketer == null) {
            status.setRollbackOnly();
            return new AllocationAttempt(InternalOutcome.WAIT_MARKETER, null);
        }
        reserveMarketingQuota(taskId, marketer.getAccountId(), extension.getMarketingAccountGroupLimit(), now);

        List<GroupPullMarketingMaterial> materials = mapper.selectAvailableMaterialsForUpdate(
                taskId, extension.getMaterialPerGroup());
        if (materials.size() < extension.getMaterialPerGroup()) {
            status.setRollbackOnly();
            return new AllocationAttempt(InternalOutcome.WAIT_MATERIAL, null);
        }

        GroupPullMarketingExecution execution = buildExecution(taskId, builder.getAccountId(),
                marketer.getAccountId(), extension, now);
        mapper.insertExecution(execution);
        List<Long> materialIds = materials.stream().map(GroupPullMarketingMaterial::getId).toList();
        if (mapper.reserveMaterials(materialIds, execution.getId(), now) != materialIds.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "料子预留状态已变化，请稍后重试");
        }
        mapper.insertExecutionMaterials(buildExecutionMaterials(execution.getId(), materials, now));
        mapper.updateBlockReason(taskId, GroupPullBlockReason.NONE.code(), now);
        log.info("拉群营销资源分配完成 taskId={} executionId={} builderAccountId={} marketerAccountId={} materials={}",
                taskId, execution.getId(), builder.getAccountId(), marketer.getAccountId(), materialIds.size());
        return new AllocationAttempt(InternalOutcome.ALLOCATED, execution.getId());
    }

    private void reserveMarketingQuota(Long taskId, Long accountId, int limit, long now) {
        GroupPullMarketingAccountStat stat = mapper.selectAccountStatForUpdate(taskId, accountId);
        if (stat == null) {
            stat = new GroupPullMarketingAccountStat();
            stat.setTaskId(taskId);
            stat.setAccountId(accountId);
            stat.setReservedGroupCount(0);
            stat.setJoinedGroupCount(0);
            stat.setCreatedAt(now);
            stat.setUpdatedAt(now);
            mapper.insertAccountStat(stat);
        }
        if (mapper.reserveMarketingQuota(taskId, accountId, limit, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "营销账号群额度已用完，请稍后重试");
        }
    }

    private GroupPullMarketingExecution buildExecution(Long taskId,
                                                        Long builderAccountId,
                                                        Long marketerAccountId,
                                                        GroupPullMarketingTask task,
                                                        long now) {
        boolean adminRequired = Boolean.TRUE.equals(task.getBuilderExitEnabled())
                || Integer.valueOf(GroupPullSpeakPermission.MUTED.code()).equals(task.getSpeakPermission());
        GroupPullMarketingExecution row = new GroupPullMarketingExecution();
        row.setTaskId(taskId);
        row.setBuilderAccountId(builderAccountId);
        row.setMarketingAccountId(marketerAccountId);
        row.setExecutionStatus(GroupPullExecutionStatus.PREPARING.code());
        row.setCurrentStage(GroupPullExecutionStage.FRIEND_PREPARATION.code());
        row.setStageRetryCount(0);
        row.setNextExecuteAt(now);
        row.setMarketerAdminStatus(adminRequired ? PENDING_OPERATION : 0);
        row.setBuilderExitStatus(Boolean.TRUE.equals(task.getBuilderExitEnabled()) ? PENDING_OPERATION : 0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private List<GroupPullMarketingExecutionMaterial> buildExecutionMaterials(
            Long executionId,
            List<GroupPullMarketingMaterial> materials,
            long now) {
        List<GroupPullMarketingExecutionMaterial> rows = new ArrayList<>(materials.size());
        int allocationNo = 1;
        for (GroupPullMarketingMaterial material : materials) {
            GroupPullMarketingExecutionMaterial row = new GroupPullMarketingExecutionMaterial();
            row.setExecutionId(executionId);
            row.setMaterialId(material.getId());
            row.setAllocationNo(allocationNo++);
            row.setFriendStatus(PENDING_OPERATION);
            row.setEntryStatus(PENDING_OPERATION);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            rows.add(row);
        }
        return rows;
    }

    private void recordBlockingReason(Long taskId, Outcome outcome) {
        Integer blockReason = switch (outcome) {
            case WAIT_BUILDER -> GroupPullBlockReason.WAITING_BUILDER.code();
            case WAIT_MARKETER -> GroupPullBlockReason.WAITING_MARKETER.code();
            case WAIT_MATERIAL -> GroupPullBlockReason.WAITING_MATERIAL.code();
            default -> null;
        };
        if (blockReason != null) {
            transactionTemplate.executeWithoutResult(status ->
                    mapper.updateBlockReason(taskId, blockReason, System.currentTimeMillis()));
        }
    }

    private static AllocationResult toPublicResult(AllocationAttempt result) {
        Outcome outcome = switch (result.outcome) {
            case ALLOCATED -> Outcome.ALLOCATED;
            case CONCURRENCY_FULL -> Outcome.CONCURRENCY_FULL;
            case WAIT_BUILDER -> Outcome.WAIT_BUILDER;
            case WAIT_MARKETER -> Outcome.WAIT_MARKETER;
            case WAIT_MATERIAL -> Outcome.WAIT_MATERIAL;
            case NOT_RUNNABLE -> Outcome.NOT_RUNNABLE;
            case RETRY_BUILDER -> throw new IllegalStateException("建群账号冲突不应直接返回");
        };
        return new AllocationResult(outcome, result.executionId);
    }

    private static boolean isBuilderExecutionConflict(DuplicateKeyException ex) {
        String message = ex.getMostSpecificCause().getMessage();
        return message != null && (message.contains("uq_gpme_task_builder")
                || message.contains("uq_gpme_active_builder"));
    }

    /** 资源分配结果。 */
    public record AllocationResult(Outcome outcome, Long executionId) {
    }

    /** 对调度器稳定暴露的分配结果。 */
    public enum Outcome {
        ALLOCATED,
        CONCURRENCY_FULL,
        WAIT_BUILDER,
        WAIT_MARKETER,
        WAIT_MATERIAL,
        NOT_RUNNABLE
    }

    private record AllocationAttempt(InternalOutcome outcome, Long executionId) {
    }

    private enum InternalOutcome {
        ALLOCATED,
        CONCURRENCY_FULL,
        WAIT_BUILDER,
        WAIT_MARKETER,
        WAIT_MATERIAL,
        NOT_RUNNABLE,
        RETRY_BUILDER
    }
}
