package com.armada.group.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupBatchTaskItemMapper;
import com.armada.group.mapper.GroupBatchTaskMapper;
import com.armada.group.model.entity.GroupBatchTask;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.enums.GroupBatchTaskStatus;
import com.armada.group.model.enums.GroupBatchTaskType;
import com.armada.group.service.impl.GroupBatchInfoRefreshWorker;
import com.armada.group.service.impl.GroupBatchLinkRefreshWorker;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 批量任务调度器单测:验证按类型分派与逐任务租户上下文。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupBatchTaskJobTest {

    private static final long TENANT_ID = 7L;

    @Mock
    private GroupBatchTaskMapper taskMapper;

    @Mock
    private GroupBatchTaskItemMapper itemMapper;

    @Mock
    private GroupBatchLinkRefreshWorker linkWorker;

    @Mock
    private GroupBatchInfoRefreshWorker infoWorker;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void linkTasksGoToTheLinkWorkerUnderTheOwningTenantContext() {
        stubRunnable(task(GroupBatchTaskType.REFRESH_LINK));
        when(itemMapper.selectPending(eq(900L), anyInt(), anyInt())).thenReturn(List.of(item()));
        AtomicReference<Long> seenTenant = new AtomicReference<>();
        doAnswer(invocation -> {
            // 明细查询依赖租户拦截器，调度器必须在分派前把租户上下文切过来。
            seenTenant.set(TenantContext.get());
            return null;
        }).when(linkWorker).execute(any(), anyLong());

        job().runOnce();
        drain();

        assertThat(seenTenant.get()).isEqualTo(TENANT_ID);
        verify(infoWorker, never()).execute(any(), anyLong());
    }

    @Test
    void infoTasksGoToTheObservingWorker() {
        stubRunnable(task(GroupBatchTaskType.REFRESH_INFO));
        when(itemMapper.selectPending(eq(900L), anyInt(), anyInt())).thenReturn(List.of(item()));

        job().runOnce();
        drain();

        verify(infoWorker).execute(any(), anyLong());
        verify(linkWorker, never()).execute(any(), anyLong());
    }

    @Test
    void tenantContextIsRestoredAfterEachTaskSoSchedulerThreadsStayClean() {
        stubRunnable(task(GroupBatchTaskType.REFRESH_INFO));
        when(itemMapper.selectPending(eq(900L), anyInt(), anyInt())).thenReturn(List.of(item()));

        job().runOnce();
        drain();

        assertThat(TenantContext.get()).isNull();
    }

    private void stubRunnable(GroupBatchTask task) {
        when(taskMapper.selectRunnableTasks(
                List.of(GroupBatchTaskStatus.PENDING.code(), GroupBatchTaskStatus.RUNNING.code()),
                20)).thenReturn(List.of(task));
    }

    private static GroupBatchTask task(GroupBatchTaskType type) {
        GroupBatchTask task = new GroupBatchTask();
        task.setId(900L);
        task.setTenantId(TENANT_ID);
        task.setTaskType(type.code());
        task.setStatus(GroupBatchTaskStatus.PENDING.code());
        return task;
    }

    private static GroupBatchTaskItem item() {
        return item(9L);
    }

    private static GroupBatchTaskItem item(long id) {
        GroupBatchTaskItem item = new GroupBatchTaskItem();
        item.setId(id);
        item.setTaskId(900L);
        item.setGroupLinkId(100L + id);
        item.setStatus(GroupBatchTaskItemStatus.PENDING.code());
        return item;
    }

    private final Deque<Runnable> submitted = new ArrayDeque<>();

    /** 手控执行器：投递不执行，drain 时才跑，用来证明调度线程没有同步等协议。 */
    private final Executor manualExecutor = submitted::add;

    private void drain() {
        while (!submitted.isEmpty()) {
            submitted.poll().run();
        }
    }

    /** 明细同步跑完，用于验证分派与租户上下文这类与并发无关的行为。 */
    private GroupBatchTaskJob job() {
        return job(Runnable::run);
    }

    private GroupBatchTaskJob job(Executor itemExecutor) {
        return new GroupBatchTaskJob(
                taskMapper,
                itemMapper,
                new GroupBatchTaskWorkers(linkWorker, infoWorker),
                new GroupBatchTaskExecutors(manualExecutor, itemExecutor),
                new GroupBatchTaskJobProperties(true, 3_000L, 20, 50));
    }

    @Test
    void itemsAdvanceConcurrentlyAndTheRoundWaitsForAllOfThem() throws InterruptedException {
        // 两个按钮都实时直调协议，单条约 1~2 秒。串行推进上千个群要几十分钟，
        // 因此明细必须并发；同时本轮必须等全部明细结束才释放 inFlight。
        stubRunnable(task(GroupBatchTaskType.REFRESH_INFO));
        when(itemMapper.selectPending(eq(900L), anyInt(), anyInt()))
                .thenReturn(List.of(item(1L), item(2L), item(3L), item(4L)));
        CyclicBarrier allInside = new CyclicBarrier(4);
        AtomicInteger completed = new AtomicInteger();
        doAnswer(invocation -> {
            // 串行执行时这里必然超时：后一条要等前一条返回，barrier 永远凑不满 4 个。
            allInside.await(3L, TimeUnit.SECONDS);
            completed.incrementAndGet();
            return null;
        }).when(infoWorker).execute(any(), anyLong());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            job(pool).runOnce();
            drain();
        } finally {
            pool.shutdownNow();
        }

        assertThat(completed.get()).isEqualTo(4);
    }

    @Test
    void canceledTaskStopsMidRoundWithoutSendingAnyMoreProtocolCalls() {
        stubRunnable(task(GroupBatchTaskType.REFRESH_INFO));
        when(itemMapper.selectPending(eq(900L), anyInt(), anyInt()))
                .thenReturn(List.of(item(1L), item(2L)));
        // 用户关弹窗后，本轮已投递的明细也必须停下来，否则剩下上千个群会白跑完。
        when(taskMapper.selectStatusById(900L))
                .thenReturn(GroupBatchTaskStatus.CANCELED.code());

        job().runOnce();
        drain();

        verify(infoWorker, never()).execute(any(), anyLong());
        verify(linkWorker, never()).execute(any(), anyLong());
    }

    @Test
    void oneFailingItemDoesNotAbortTheRestOfTheRound() {
        stubRunnable(task(GroupBatchTaskType.REFRESH_INFO));
        when(itemMapper.selectPending(eq(900L), anyInt(), anyInt()))
                .thenReturn(List.of(item(1L), item(2L)));
        AtomicInteger advanced = new AtomicInteger();
        doAnswer(invocation -> {
            // 协议失败由执行器自行结算；能逃到调度器的是落库等基础设施异常。
            if (advanced.incrementAndGet() == 1) {
                throw new IllegalStateException("settlement boom");
            }
            return null;
        }).when(infoWorker).execute(any(), anyLong());

        job().runOnce();
        drain();

        assertThat(advanced.get()).isEqualTo(2);
    }

    @Test
    void runOnceOnlyDispatchesSoTheSharedSchedulerThreadIsNeverBlockedByProtocolCalls() {
        stubRunnable(task(GroupBatchTaskType.REFRESH_LINK));
        when(itemMapper.selectPending(eq(900L), anyInt(), anyInt())).thenReturn(List.of(item()));

        job().runOnce();

        // 应用里 17 个 @Scheduled 共用一条默认单线程调度器；协议调用必须挪到自有线程池，
        // 否则一轮批量会把群详情同步等任务全部堵住。
        assertThat(submitted).hasSize(1);
        verify(linkWorker, never()).execute(any(), anyLong());

        drain();
        verify(linkWorker).execute(any(), anyLong());
    }

    @Test
    void inFlightTaskIsNotDispatchedAgainByTheNextRound() {
        stubRunnable(task(GroupBatchTaskType.REFRESH_LINK));
        when(itemMapper.selectPending(eq(900L), anyInt(), anyInt())).thenReturn(List.of(item()));
        GroupBatchTaskJob job = job();

        job.runOnce();
        job.runOnce();

        // 上一轮还没跑完就再次投递，会对同一批明细重复发协议调用。
        assertThat(submitted).hasSize(1);
    }
}
