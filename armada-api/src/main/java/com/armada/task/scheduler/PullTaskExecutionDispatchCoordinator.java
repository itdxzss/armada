package com.armada.task.scheduler;

import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionClaimState;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 组织普通群链接执行链路的一轮跨租户 claim、分阶段处理和检查点回写。 */
@Component
public class PullTaskExecutionDispatchCoordinator {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";

    private static final Logger log =
            LoggerFactory.getLogger(PullTaskExecutionDispatchCoordinator.class);

    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskExecutionStageRouter stageRouter;
    private final PullTaskResourceRecoveryTransactionService resourceRecovery;
    private final PullTaskExecutionDispatchProperties properties;
    private final String lockOwner;

    /** 生产构造器：每个应用实例生成稳定且不含业务数据的租约标识。 */
    @Autowired
    public PullTaskExecutionDispatchCoordinator(
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskExecutionStageRouter stageRouter,
            PullTaskResourceRecoveryTransactionService resourceRecovery,
            PullTaskExecutionDispatchProperties properties) {
        this(executionMapper, stageRouter, resourceRecovery, properties,
                "pull-execution-" + UUID.randomUUID());
    }

    /**
     * 可指定租约标识的构造器，便于部署诊断和确定性测试。
     *
     * @param executionMapper 执行行 Mapper
     * @param stageRouter     业务阶段路由
     * @param resourceRecovery 资源等待恢复事务
     * @param properties      调度配置
     * @param lockOwner       当前实例租约标识
     */
    public PullTaskExecutionDispatchCoordinator(
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskExecutionStageRouter stageRouter,
            PullTaskResourceRecoveryTransactionService resourceRecovery,
            PullTaskExecutionDispatchProperties properties,
            String lockOwner) {
        this.executionMapper = executionMapper;
        this.stageRouter = stageRouter;
        this.resourceRecovery = resourceRecovery;
        this.properties = properties;
        this.lockOwner = lockOwner;
    }

    /** @return 使用系统时间执行一轮调度 */
    public PullTaskExecutionDispatchStats dispatchOnce() {
        return dispatchOnce(System.currentTimeMillis());
    }

    /**
     * 使用给定时间执行一轮有界调度。
     *
     * @param now 本轮统一时间(epoch 毫秒)
     * @return 单轮统计
     */
    public PullTaskExecutionDispatchStats dispatchOnce(long now) {
        long startedAt = System.currentTimeMillis();
        long lockExpiresAt = Math.addExact(now, properties.getLeaseMs());
        int batchSize = properties.getBatchSize();
        // 分两池抢占：待启动行的 next_run_at 恒为 0，与执行中行放在同一次
        // ORDER BY next_run_at 里会把后者永久挤出窗口，导致并发槽位填满后整个任务停止推进。
        // 因此先让已开始的行吃满整批，待启动行只使用剩余名额。
        PullTaskExecutionClaimCriteria advancingCriteria =
                claimCriteria(advancingStates(), batchSize, now, lockExpiresAt);
        int advancing = executionMapper.claimDue(advancingCriteria);
        int startingLimit = batchSize - advancing;
        PullTaskExecutionClaimCriteria startingCriteria = startingLimit > 0
                ? claimCriteria(startingStates(), startingLimit, now, lockExpiresAt)
                : null;
        int starting = startingCriteria == null
                ? 0
                : executionMapper.claimDue(startingCriteria);
        int backlog = backlogDepth(
                advancingCriteria, advancing, batchSize, startingCriteria, starting, startingLimit);
        List<PullTaskGroupExecution> claimed = executionMapper.selectClaimed(lockOwner, now);
        PullTaskExecutionDispatchStats stats =
                PullTaskExecutionDispatchStats.empty().withClaimed(claimed.size());
        for (PullTaskGroupExecution candidate : claimed) {
            stats = process(candidate, now, stats);
        }
        log.info("普通拉群执行调度完成 claimed={} started={} advanced={} failed={} deferred={} "
                        + "skipped={} backlog={} costMs={}",
                stats.claimed(), stats.started(), stats.advanced(), stats.failed(),
                stats.deferred(), stats.skipped(), backlog,
                System.currentTimeMillis() - startedAt);
        return stats;
    }

    /**
     * 统计本轮抢占之后仍然到期未处理的行数。
     *
     * <p>只有对应的池把整批吃满时才可能有剩余，此时才付出一次计数查询；
     * 没吃满说明该池已经取空，积压按 0 计，空闲时不产生任何额外开销。</p>
     *
     * @param advancingCriteria 推进池扫描条件
     * @param advancing         推进池本轮实际抢到的行数
     * @param batchSize         本轮批量上限
     * @param startingCriteria  启动池扫描条件；本轮没有剩余名额时为 {@code null}
     * @param starting          启动池本轮实际抢到的行数
     * @param startingLimit     启动池本轮可用名额
     * @return 两池合计的积压深度
     */
    private int backlogDepth(
            PullTaskExecutionClaimCriteria advancingCriteria,
            int advancing,
            int batchSize,
            PullTaskExecutionClaimCriteria startingCriteria,
            int starting,
            int startingLimit) {
        int backlog = 0;
        if (advancing >= batchSize) {
            backlog += executionMapper.countDue(advancingCriteria);
        }
        if (startingCriteria != null && starting >= startingLimit) {
            backlog += executionMapper.countDue(startingCriteria);
        }
        return backlog;
    }

    /** 已开始的执行行：本轮优先推进，可以吃满整批。 */
    private static List<PullTaskExecutionClaimState> advancingStates() {
        return List.of(
                new PullTaskExecutionClaimState(
                        PullTaskExecutionStatus.EXECUTING.code(),
                        List.of(PullTaskExecutionStage.LINK_VALIDATION.code(),
                                PullTaskExecutionStage.MANAGER_JOIN.code(),
                                PullTaskExecutionStage.MANAGER_ADMIN.code(),
                                PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code(),
                                PullTaskExecutionStage.PULLER_INVITE.code(),
                                PullTaskExecutionStage.PULL_EXECUTION.code(),
                                PullTaskExecutionStage.MATERIAL_ADMIN.code(),
                                PullTaskExecutionStage.CLOSING.code())),
                new PullTaskExecutionClaimState(
                        PullTaskExecutionStatus.WAIT_RESOURCE.code(),
                        List.of(PullTaskExecutionStage.MANAGER_JOIN.code(),
                                PullTaskExecutionStage.MANAGER_ADMIN.code(),
                                PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code(),
                                PullTaskExecutionStage.PULLER_INVITE.code(),
                                PullTaskExecutionStage.PULL_EXECUTION.code(),
                                PullTaskExecutionStage.MATERIAL_ADMIN.code()),
                        List.of(
                                PullTaskWaitResourceType.MANAGER.code(),
                                PullTaskWaitResourceType.PULLER.code(),
                                PullTaskWaitResourceType.STATION.code())));
    }

    /** 尚未启动的执行行：只使用第一池剩余的名额。 */
    private static List<PullTaskExecutionClaimState> startingStates() {
        return List.of(
                new PullTaskExecutionClaimState(
                        PullTaskExecutionStatus.WAIT_START.code(),
                        List.of(PullTaskExecutionStage.LINK_VALIDATION.code(),
                                PullTaskExecutionStage.MANAGER_JOIN.code())));
    }

    private PullTaskExecutionClaimCriteria claimCriteria(
            List<PullTaskExecutionClaimState> eligibleStates,
            int limit,
            long now,
            long lockExpiresAt) {
        return new PullTaskExecutionClaimCriteria(
                new PullTaskExecutionClaimCriteria.Lease(limit, now, lockOwner, lockExpiresAt),
                eligibleStates,
                new PullTaskExecutionClaimCriteria.Parent(
                        PullTaskType.STANDARD.name(), NORMAL_LINK_MODE,
                        PullTaskStandardStatus.EXECUTING.name()),
                0);
    }

    private PullTaskExecutionDispatchStats process(
            PullTaskGroupExecution candidate, long now,
            PullTaskExecutionDispatchStats stats) {
        try {
            PullTaskExecutionDispatchResult result = processStage(candidate, now);
            return result == PullTaskExecutionDispatchResult.LOST
                    ? stats.skip()
                    : stats.add(result);
        } catch (RuntimeException ex) {
            // 释放租约但不推后 next_run_at，会让本行下一轮立刻回到队首再次失败；
            // 不可恢复的行会因此每秒重试并长期占用 claim 名额。异常一律退避后再试。
            long nextRunAt = Math.addExact(now, properties.getRetryDelayMs());
            executionMapper.releaseLockWithBackoff(candidate.getId(), lockOwner, now, nextRunAt);
            log.error("普通拉群执行行调度异常 tenantId={} taskId={} executionId={} errorType={} "
                            + "nextRunAt={} reason={}",
                    candidate.getTenantId(), candidate.getTaskId(), candidate.getId(),
                    ex.getClass().getSimpleName(), nextRunAt, ex.getMessage());
            return stats.skip();
        }
    }

    private PullTaskExecutionDispatchResult processStage(
            PullTaskGroupExecution candidate, long now) {
        if (candidate.getExecutionStatus() == PullTaskExecutionStatus.WAIT_RESOURCE.code()) {
            return resourceRecovery.recover(
                    candidate, lockOwner, now, properties.getRetryDelayMs());
        }
        return stageRouter.process(candidate, lockOwner, now);
    }
}
