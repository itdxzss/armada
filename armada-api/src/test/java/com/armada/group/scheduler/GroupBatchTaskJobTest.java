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
import java.util.concurrent.Executor;
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
        GroupBatchTaskItem item = new GroupBatchTaskItem();
        item.setId(9L);
        item.setTaskId(900L);
        item.setGroupLinkId(101L);
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

    private GroupBatchTaskJob job() {
        return new GroupBatchTaskJob(
                taskMapper,
                itemMapper,
                new GroupBatchTaskWorkers(linkWorker, infoWorker),
                manualExecutor,
                new GroupBatchTaskJobProperties(true, 3_000L, 20, 50));
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
