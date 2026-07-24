package com.armada.marketing.grouppull.scheduler;

import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.vo.GroupPullExecutionDispatchRow;
import com.armada.marketing.grouppull.model.vo.GroupPullTaskDispatchRow;
import com.armada.marketing.grouppull.service.GroupPullMarketingAllocator;
import com.armada.marketing.grouppull.service.GroupPullMarketingExecutionWorker;
import com.armada.marketing.grouppull.service.GroupPullMarketingReleaseService;
import com.armada.shared.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 拉群营销任务后台调度器。
 *
 * <p>每轮依次执行资源分配、到期建群执行和安全释放三个有界扫描。
 * 跨租户扫描只读取任务 ID，真正的业务调用会先恢复对应租户上下文；
 * 单群执行提交到固定五线程池，协议调用期间不占用调度线程。</p>
 */
@Component
@Profile("kafka")
public class GroupPullMarketingScheduler {

    /** 安全日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(GroupPullMarketingScheduler.class);

    /** 单轮每类跨租户扫描的最大记录数。 */
    private static final int SCAN_LIMIT = 100;

    /** 单任务每轮最多尝试补足五个建群并发槽位。 */
    private static final int MAX_ALLOCATION_ATTEMPTS_PER_TASK = 5;

    /** 建群协议执行固定并发数。 */
    private static final int EXECUTOR_SIZE = 5;

    /** 到期执行等待队列上限；队列满时保留数据库到期状态供下一轮重扫。 */
    private static final int EXECUTOR_QUEUE_CAPACITY = 100;

    /** 建群执行线程编号。 */
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    /** 拉群任务及执行的跨租户调度投影数据访问。 */
    private final GroupPullMarketingMapper mapper;

    /** 单套建群资源的短事务分配器。 */
    private final GroupPullMarketingAllocator allocator;

    /** 按短租约推进单群状态机的执行器。 */
    private final GroupPullMarketingExecutionWorker executionWorker;

    /** 任务结束后的安全资源释放服务。 */
    private final GroupPullMarketingReleaseService releaseService;

    /** 固定大小且有界的单群执行线程池。 */
    private final ExecutorService executor;

    /** 本机已经排队或正在执行的 execution ID，避免重复扫描挤占有界队列。 */
    private final Set<Long> scheduledExecutionIds = ConcurrentHashMap.newKeySet();

    /**
     * 创建拉群营销后台调度器。
     *
     * @param mapper 拉群任务及执行的调度投影数据访问
     * @param allocator 单套建群资源分配器
     * @param executionWorker 单群状态机执行器
     * @param releaseService 安全资源释放服务
     */
    public GroupPullMarketingScheduler(
            GroupPullMarketingMapper mapper,
            GroupPullMarketingAllocator allocator,
            GroupPullMarketingExecutionWorker executionWorker,
            GroupPullMarketingReleaseService releaseService) {
        this.mapper = mapper;
        this.allocator = allocator;
        this.executionWorker = executionWorker;
        this.releaseService = releaseService;
        this.executor = new ThreadPoolExecutor(
                EXECUTOR_SIZE,
                EXECUTOR_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(EXECUTOR_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "group-pull-marketing-worker-" + THREAD_SEQUENCE.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 每秒扫描一次可分配任务、到期执行和释放中任务。
     *
     * <p>三个阶段互相隔离；其中一个扫描失败只记录日志，
     * 不阻止同轮另外两个阶段继续执行。</p>
     */
    @Scheduled(fixedDelayString = "${armada.group-pull-marketing.scheduler.fixed-delay-ms:1000}")
    public void scan() {
        long now = System.currentTimeMillis();
        scanAllocatableTasks(now);
        scanDueExecutions(now);
        scanReleasingTasks();
    }

    /**
     * 应用关闭时停止接收新的单群执行并中断线程池等待任务。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 扫描执行中且允许继续分配资源的任务。
     *
     * @param now 当前调度时间（epoch 毫秒）
     */
    private void scanAllocatableTasks(long now) {
        List<GroupPullTaskDispatchRow> tasks;
        try {
            tasks = mapper.selectAllocatableTaskDispatches(now, SCAN_LIMIT);
        } catch (RuntimeException exception) {
            log.error("拉群营销可分配任务扫描失败", exception);
            return;
        }
        for (GroupPullTaskDispatchRow task : tasks) {
            allocateSafely(task);
        }
    }

    /**
     * 在任务所属租户内尝试补足建群并发槽位。
     *
     * @param task 跨租户扫描得到的任务最小投影
     */
    private void allocateSafely(GroupPullTaskDispatchRow task) {
        try {
            runInTenant(task.tenantId(), () -> allocateAvailableSlots(task.taskId()));
        } catch (RuntimeException exception) {
            log.error(
                    "拉群营销资源分配调度失败 tenantId={} taskId={}",
                    task.tenantId(),
                    task.taskId(),
                    exception);
        }
    }

    /**
     * 最多尝试五次资源分配，首次遇到并发满或资源不足即停止本任务本轮分配。
     *
     * @param taskId 统一营销任务 ID
     */
    private void allocateAvailableSlots(Long taskId) {
        for (int attempt = 0; attempt < MAX_ALLOCATION_ATTEMPTS_PER_TASK; attempt++) {
            GroupPullMarketingAllocator.AllocationResult result = allocator.allocateOne(taskId);
            if (result == null
                    || result.outcome() != GroupPullMarketingAllocator.Outcome.ALLOCATED) {
                return;
            }
        }
    }

    /**
     * 扫描已到 {@code next_execute_at} 且当前任务状态允许推进的建群执行。
     *
     * @param now 当前调度时间（epoch 毫秒）
     */
    private void scanDueExecutions(long now) {
        List<GroupPullExecutionDispatchRow> executions;
        try {
            executions = mapper.selectDueExecutionDispatches(now, SCAN_LIMIT);
        } catch (RuntimeException exception) {
            log.error("拉群营销到期执行扫描失败", exception);
            return;
        }
        for (GroupPullExecutionDispatchRow execution : executions) {
            submitExecution(execution);
        }
    }

    /**
     * 将一条到期执行提交到有界线程池；队列已满时保留数据库状态等待下一轮重扫。
     *
     * @param execution 跨租户扫描得到的执行最小投影
     */
    private void submitExecution(GroupPullExecutionDispatchRow execution) {
        if (!scheduledExecutionIds.add(execution.executionId())) {
            return;
        }
        try {
            executor.execute(() -> processSafely(execution));
        } catch (RejectedExecutionException exception) {
            scheduledExecutionIds.remove(execution.executionId());
            log.warn(
                    "拉群营销执行线程池已满 tenantId={} executionId={}",
                    execution.tenantId(),
                    execution.executionId());
        }
    }

    /**
     * 在执行所属租户内推进一次单群状态机，单条失败不影响线程池其他任务。
     *
     * @param execution 待推进的执行最小投影
     */
    private void processSafely(GroupPullExecutionDispatchRow execution) {
        try {
            runInTenant(execution.tenantId(), () -> executionWorker.process(execution.executionId()));
        } catch (RuntimeException exception) {
            log.error(
                    "拉群营销到期执行调度失败 tenantId={} executionId={}",
                    execution.tenantId(),
                    execution.executionId(),
                    exception);
        } finally {
            scheduledExecutionIds.remove(execution.executionId());
        }
    }

    /**
     * 扫描资源释放中的任务并尝试完成一次安全收口。
     */
    private void scanReleasingTasks() {
        List<GroupPullTaskDispatchRow> tasks;
        try {
            tasks = mapper.selectReleasingTaskDispatches(SCAN_LIMIT);
        } catch (RuntimeException exception) {
            log.error("拉群营销释放中任务扫描失败", exception);
            return;
        }
        for (GroupPullTaskDispatchRow task : tasks) {
            releaseSafely(task);
        }
    }

    /**
     * 在任务所属租户内尝试安全释放资源。
     *
     * @param task 跨租户扫描得到的任务最小投影
     */
    private void releaseSafely(GroupPullTaskDispatchRow task) {
        try {
            runInTenant(task.tenantId(), () -> releaseService.tryRelease(task.taskId()));
        } catch (RuntimeException exception) {
            log.error(
                    "拉群营销资源释放调度失败 tenantId={} taskId={}",
                    task.tenantId(),
                    task.taskId(),
                    exception);
        }
    }

    /**
     * 临时切换到指定租户执行后台任务，并在 finally 中恢复线程原有上下文。
     *
     * @param tenantId 后台任务所属租户 ID
     * @param action 需要在该租户上下文内执行的动作
     */
    private static void runInTenant(Long tenantId, Runnable action) {
        Long previousTenantId = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            action.run();
        } finally {
            if (previousTenantId == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenantId);
            }
        }
    }
}
