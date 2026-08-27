package com.armada.marketing.grouppull.scheduler;

import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.vo.GroupPullExecutionDispatchRow;
import com.armada.marketing.grouppull.model.vo.GroupPullTaskDispatchRow;
import com.armada.marketing.grouppull.service.GroupPullMarketingAllocator;
import com.armada.marketing.grouppull.service.GroupPullMarketingExecutionWorker;
import com.armada.marketing.grouppull.service.GroupPullMarketingReleaseService;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** 拉群营销跨租户调度与本机执行并发测试。 */
class GroupPullMarketingSchedulerTest {

    private static final PlatformTransactionManager NO_OP_TRANSACTION_MANAGER =
            new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                }

                @Override
                public void rollback(TransactionStatus status) {
                }
            };

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        DataScopeContext.clear();
    }

    @Test
    void scanDispatchesThreeBoundedPhasesWithTenantContext() throws InterruptedException {
        GroupPullMarketingMapper mapper = mapper(
                List.of(new GroupPullTaskDispatchRow(11L, 1001L, 101L)),
                List.of(new GroupPullExecutionDispatchRow(22L, 2002L, 202L)),
                List.of(new GroupPullTaskDispatchRow(33L, 3003L, 303L)));
        RecordingAllocator allocator = new RecordingAllocator(List.of(
                allocated(1L), allocated(2L), allocated(3L), allocated(4L), allocated(5L)));
        RecordingWorker worker = new RecordingWorker(1);
        RecordingReleaseService releaseService = new RecordingReleaseService();
        GroupPullMarketingScheduler scheduler =
                new GroupPullMarketingScheduler(mapper, allocator, worker, releaseService);
        TenantContext.set(99L);

        scheduler.scan();

        assertThat(worker.await()).isTrue();
        assertThat(allocator.taskIds).containsExactly(101L, 101L, 101L, 101L, 101L);
        assertThat(allocator.tenantIds).containsOnly(11L);
        assertThat(allocator.ownerUserIds).containsOnly(1001L);
        assertThat(worker.executionIds).containsExactly(202L);
        assertThat(worker.tenantIds).containsExactly(22L);
        assertThat(worker.ownerUserIds).containsExactly(2002L);
        assertThat(releaseService.taskIds).containsExactly(303L);
        assertThat(releaseService.tenantIds).containsExactly(33L);
        assertThat(releaseService.ownerUserIds).containsExactly(3003L);
        assertThat(TenantContext.get()).isEqualTo(99L);
        scheduler.shutdown();
    }

    @Test
    void allocationStopsWhenAResourceBecomesUnavailable() {
        GroupPullMarketingMapper mapper = mapper(
                List.of(new GroupPullTaskDispatchRow(11L, 1001L, 101L)), List.of(), List.of());
        RecordingAllocator allocator = new RecordingAllocator(List.of(
                allocated(1L),
                new GroupPullMarketingAllocator.AllocationResult(
                        GroupPullMarketingAllocator.Outcome.WAIT_MATERIAL, null)));
        GroupPullMarketingScheduler scheduler = new GroupPullMarketingScheduler(
                mapper, allocator, new RecordingWorker(0), new RecordingReleaseService());

        scheduler.scan();

        assertThat(allocator.taskIds).containsExactly(101L, 101L);
        scheduler.shutdown();
    }

    @Test
    void oneTaskFailureDoesNotBlockFollowingTasks() {
        GroupPullMarketingMapper mapper = mapper(
                List.of(
                        new GroupPullTaskDispatchRow(11L, 1001L, 101L),
                        new GroupPullTaskDispatchRow(22L, 2002L, 202L)),
                List.of(),
                List.of());
        RecordingAllocator allocator = new RecordingAllocator(List.of(
                new GroupPullMarketingAllocator.AllocationResult(
                        GroupPullMarketingAllocator.Outcome.WAIT_MATERIAL, null))) {
            @Override
            public AllocationResult allocateOne(Long taskId) {
                if (Long.valueOf(101L).equals(taskId)) {
                    throw new IllegalStateException("expected");
                }
                return super.allocateOne(taskId);
            }
        };
        GroupPullMarketingScheduler scheduler = new GroupPullMarketingScheduler(
                mapper, allocator, new RecordingWorker(0), new RecordingReleaseService());

        scheduler.scan();

        assertThat(allocator.taskIds).containsExactly(202L);
        assertThat(allocator.tenantIds).containsExactly(22L);
        assertThat(allocator.ownerUserIds).containsExactly(2002L);
        scheduler.shutdown();
    }

    @Test
    void repeatedScanDoesNotQueueTheSameExecutionWhileItIsStillRunning()
            throws InterruptedException {
        GroupPullMarketingMapper mapper = mapper(
                List.of(),
                List.of(new GroupPullExecutionDispatchRow(22L, 2002L, 202L)),
                List.of());
        BlockingWorker worker = new BlockingWorker();
        GroupPullMarketingScheduler scheduler = new GroupPullMarketingScheduler(
                mapper, new RecordingAllocator(List.of()), worker, new RecordingReleaseService());

        scheduler.scan();
        assertThat(worker.awaitFirstExecution()).isTrue();
        scheduler.scan();

        assertThat(worker.awaitDuplicateExecution()).isFalse();
        worker.release();
        scheduler.shutdown();
    }

    @Test
    void unownedHistoricalRowsAreNeverDispatched() throws InterruptedException {
        GroupPullMarketingMapper mapper = mapper(
                List.of(new GroupPullTaskDispatchRow(11L, null, 101L)),
                List.of(new GroupPullExecutionDispatchRow(22L, null, 202L)),
                List.of(new GroupPullTaskDispatchRow(33L, null, 303L)));
        RecordingAllocator allocator = new RecordingAllocator(List.of());
        RecordingWorker worker = new RecordingWorker(0);
        RecordingReleaseService releaseService = new RecordingReleaseService();
        GroupPullMarketingScheduler scheduler =
                new GroupPullMarketingScheduler(mapper, allocator, worker, releaseService);

        scheduler.scan();

        assertThat(worker.await()).isTrue();
        assertThat(allocator.taskIds).isEmpty();
        assertThat(worker.executionIds).isEmpty();
        assertThat(releaseService.taskIds).isEmpty();
        scheduler.shutdown();
    }

    private static GroupPullMarketingAllocator.AllocationResult allocated(Long executionId) {
        return new GroupPullMarketingAllocator.AllocationResult(
                GroupPullMarketingAllocator.Outcome.ALLOCATED, executionId);
    }

    private static GroupPullMarketingMapper mapper(
            List<GroupPullTaskDispatchRow> allocatableTasks,
            List<GroupPullExecutionDispatchRow> dueExecutions,
            List<GroupPullTaskDispatchRow> releasingTasks) {
        return (GroupPullMarketingMapper) Proxy.newProxyInstance(
                GroupPullMarketingMapper.class.getClassLoader(),
                new Class<?>[]{GroupPullMarketingMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectAllocatableTaskDispatches" -> allocatableTasks;
                    case "selectDueExecutionDispatches" -> dueExecutions;
                    case "selectReleasingTaskDispatches" -> releasingTasks;
                    case "toString" -> "GroupPullMarketingMapperTestProxy";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static class RecordingAllocator extends GroupPullMarketingAllocator {

        private final Deque<AllocationResult> results;
        private final List<Long> taskIds = new ArrayList<>();
        private final List<Long> tenantIds = new ArrayList<>();
        private final List<Long> ownerUserIds = new ArrayList<>();

        private RecordingAllocator(List<AllocationResult> results) {
            super(null, null, NO_OP_TRANSACTION_MANAGER);
            this.results = new ArrayDeque<>(results);
        }

        @Override
        public AllocationResult allocateOne(Long taskId) {
            taskIds.add(taskId);
            tenantIds.add(TenantContext.get());
            ownerUserIds.add(DataScopeContext.requireCurrent().actorUserId());
            return results.removeFirst();
        }
    }

    private static final class RecordingWorker extends GroupPullMarketingExecutionWorker {

        private final CountDownLatch latch;
        private final List<Long> executionIds = new CopyOnWriteArrayList<>();
        private final List<Long> tenantIds = new CopyOnWriteArrayList<>();
        private final List<Long> ownerUserIds = new CopyOnWriteArrayList<>();

        private RecordingWorker(int expectedExecutions) {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null,
                    NO_OP_TRANSACTION_MANAGER);
            this.latch = new CountDownLatch(expectedExecutions);
        }

        @Override
        public void process(Long executionId) {
            executionIds.add(executionId);
            tenantIds.add(TenantContext.get());
            ownerUserIds.add(DataScopeContext.requireCurrent().actorUserId());
            latch.countDown();
        }

        private boolean await() throws InterruptedException {
            return latch.await(2, TimeUnit.SECONDS);
        }
    }

    /** 在测试结束前保持首条执行在途，用于观察重复扫描是否再次提交相同 execution。 */
    private static final class BlockingWorker extends GroupPullMarketingExecutionWorker {

        private final AtomicInteger invocationCount = new AtomicInteger();
        private final CountDownLatch firstExecution = new CountDownLatch(1);
        private final CountDownLatch duplicateExecution = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingWorker() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null,
                    NO_OP_TRANSACTION_MANAGER);
        }

        @Override
        public void process(Long executionId) {
            if (invocationCount.incrementAndGet() == 1) {
                firstExecution.countDown();
            } else {
                duplicateExecution.countDown();
            }
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean awaitFirstExecution() throws InterruptedException {
            return firstExecution.await(2, TimeUnit.SECONDS);
        }

        private boolean awaitDuplicateExecution() throws InterruptedException {
            return duplicateExecution.await(200, TimeUnit.MILLISECONDS);
        }

        private void release() {
            release.countDown();
        }
    }

    private static final class RecordingReleaseService extends GroupPullMarketingReleaseService {

        private final List<Long> taskIds = new ArrayList<>();
        private final List<Long> tenantIds = new ArrayList<>();
        private final List<Long> ownerUserIds = new ArrayList<>();

        private RecordingReleaseService() {
            super(null, null, null, null, null);
        }

        @Override
        public boolean tryRelease(Long taskId) {
            taskIds.add(taskId);
            tenantIds.add(TenantContext.get());
            ownerUserIds.add(DataScopeContext.requireCurrent().actorUserId());
            return true;
        }
    }
}
