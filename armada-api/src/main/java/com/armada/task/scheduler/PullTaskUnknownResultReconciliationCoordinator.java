package com.armada.task.scheduler;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskUnknownReconciliationCriteria;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskType;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 周期扫描普通链接任务的未知协议结果，并在对应租户上下文内收敛。 */
@Component
public class PullTaskUnknownResultReconciliationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(
            PullTaskUnknownResultReconciliationCoordinator.class);
    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";

    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskUnknownResultReconciliationService service;
    private final PullTaskExecutionDispatchProperties properties;
    private final AtomicLong nextRunAt = new AtomicLong();

    /** 构造跨租户未知结果协调器。 */
    public PullTaskUnknownResultReconciliationCoordinator(
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskUnknownResultReconciliationService service,
            PullTaskExecutionDispatchProperties properties) {
        this.executionMapper = executionMapper;
        this.service = service;
        this.properties = properties;
    }

    /** 被共享调度线程高频调用，但只按独立配置间隔真正扫描。 */
    public PullTaskUnknownResultReconciliationStats reconcileIfDue() {
        long now = System.currentTimeMillis();
        long expected = nextRunAt.get();
        if (now < expected || !nextRunAt.compareAndSet(
                expected, Math.addExact(now, properties.getResultReconciliationIntervalMs()))) {
            return PullTaskUnknownResultReconciliationStats.empty();
        }
        return reconcileOnce(now);
    }

    /** 使用统一时间执行一轮有界扫描，供确定性测试调用。 */
    public PullTaskUnknownResultReconciliationStats reconcileOnce(long now) {
        long cutoff = Math.subtractExact(now, properties.getResultReconciliationDelayMs());
        List<PullTaskGroupExecution> candidates = executionMapper
                .selectUnknownResultCandidates(criteria(cutoff));
        Counter total = new Counter();
        for (PullTaskGroupExecution execution : candidates) {
            reconcile(execution, cutoff, now, total);
        }
        PullTaskUnknownResultReconciliationStats stats = total.snapshot();
        log.info("普通拉群未知结果收敛完成 scanned={} confirmed={} markedUnknown={}",
                candidates.size(), stats.confirmed(), stats.markedUnknown());
        return stats;
    }

    private void reconcile(
            PullTaskGroupExecution execution,
            long cutoff,
            long now,
            Counter total) {
        Long previous = TenantContext.get();
        try {
            TenantContext.set(execution.getTenantId());
            total.add(service.reconcile(execution, cutoff, now));
        } catch (RuntimeException ex) {
            log.warn("普通拉群未知结果单行收敛失败 tenantId={} executionId={} errorType={}",
                    execution.getTenantId(), execution.getId(), ex.getClass().getSimpleName());
        } finally {
            restoreTenant(previous);
        }
    }

    private PullTaskUnknownReconciliationCriteria criteria(long cutoff) {
        List<Integer> executionStatuses = Arrays.stream(PullTaskExecutionStatus.values())
                .filter(status -> status != PullTaskExecutionStatus.DRAFT)
                .map(PullTaskExecutionStatus::code).toList();
        return new PullTaskUnknownReconciliationCriteria(
                new PullTaskUnknownReconciliationCriteria.Scope(
                        properties.getResultReconciliationBatchSize(), cutoff),
                executionStatuses,
                List.of(PullTaskExecutionReasonCode.GROUP_BANNED.name()),
                new PullTaskUnknownReconciliationCriteria.Parent(
                        PullTaskType.STANDARD.name(), NORMAL_LINK_MODE),
                new PullTaskUnknownReconciliationCriteria.Facts(
                        new PullTaskUnknownReconciliationCriteria.Action(
                                PullTaskActionStatus.SUBMITTED.code(),
                                PullTaskActionStatus.UNKNOWN.code()),
                        new PullTaskUnknownReconciliationCriteria.Call(
                                PullTaskPullCallStatus.SUBMITTED.code(),
                                PullTaskPullCallStatus.UNKNOWN.code(),
                                PullTaskParticipantAttemptStatus.SUBMITTED.code(),
                                PullTaskGroupAccountAvailability.AVAILABLE.code()),
                        new PullTaskUnknownReconciliationCriteria.Material(
                                PullTaskMaterialPullStatus.SUBMITTED.code(),
                                PullTaskMaterialPullStatus.UNKNOWN.code(),
                                PullTaskMaterialAdminStatus.SUBMITTED.code(),
                                PullTaskMaterialAdminStatus.UNKNOWN.code()),
                        new PullTaskUnknownReconciliationCriteria.Account(
                                PullTaskGroupAccountMembershipStatus.JOINING.code(),
                                PullTaskGroupAccountMembershipStatus.UNKNOWN.code(),
                                PullTaskGroupAccountAdminStatus.SUBMITTED.code(),
                                PullTaskGroupAccountAdminStatus.UNKNOWN.code())));
    }

    private static void restoreTenant(Long previous) {
        if (previous == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previous);
        }
    }

    private static final class Counter {
        private int confirmed;
        private int markedUnknown;

        private void add(PullTaskUnknownResultReconciliationStats stats) {
            confirmed += stats.confirmed();
            markedUnknown += stats.markedUnknown();
        }

        private PullTaskUnknownResultReconciliationStats snapshot() {
            return new PullTaskUnknownResultReconciliationStats(confirmed, markedUnknown);
        }
    }
}
