package com.armada.marketing.grouppull.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecutionMaterial;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStage;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullResourceStatus;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.enums.MarketingTaskStatus;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.port.GroupParticipantPort;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 拉群营销逐条添加料子的持久化状态机测试。 */
@ExtendWith(MockitoExtension.class)
class GroupPullMarketingMaterialEntryServiceTest {

    private static final long TASK_ID = 101L;
    private static final long EXECUTION_ID = 501L;

    private static final PlatformTransactionManager NO_OP_TRANSACTION_MANAGER =
            new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                    // 测试只验证事务回调中的状态写入。
                }

                @Override
                public void rollback(TransactionStatus status) {
                    // 测试只验证事务回调中的状态写入。
                }
            };

    @Mock
    private GroupPullMarketingMapper mapper;

    @Mock
    private GroupParticipantPort participantPort;

    @Mock
    private GroupPullMarketingFinalizer finalizer;

    @Captor
    private ArgumentCaptor<List<String>> targetsCaptor;

    @Captor
    private ArgumentCaptor<GroupPullMarketingMapper.MaterialStageProgress> progressCaptor;

    private GroupPullMarketingMaterialEntryService service;

    @BeforeEach
    void setUp() {
        GroupPullMaterialEntryDelayPolicy delayPolicy =
                new GroupPullMaterialEntryDelayPolicy((origin, bound) -> origin);
        service = new GroupPullMarketingMaterialEntryService(
                mapper,
                participantPort,
                finalizer,
                delayPolicy,
                NO_OP_TRANSACTION_MANAGER);
    }

    @Test
    void addsOnlyOnePendingMaterialAndTreatsAlreadyInAsSuccess() {
        GroupPullMarketingExecution execution = activeExecution(0);
        stubActiveTask();
        when(mapper.selectNextPendingExecutionMaterial(EXECUTION_ID)).thenReturn(material());
        when(participantPort.updateParticipants(
                any(ProtocolAccountRef.class),
                eq("group@g.us"),
                anyList(),
                eq(GroupParticipantAction.ADD)))
                .thenReturn(new GroupParticipantBatchResult(false, List.of(
                        new GroupParticipantBatchResult.Item(
                                "8613800000001@s.whatsapp.net", "ALREADY_IN", null))));
        when(mapper.updateMaterialEntryResult(eq(601L), eq(2), eq(null), anyLong())).thenReturn(1);
        when(mapper.countPendingExecutionMaterials(EXECUTION_ID)).thenReturn(1L);
        when(mapper.updateMaterialStageProgress(any())).thenReturn(1);

        long before = System.currentTimeMillis();
        service.process(execution, builder());

        verify(participantPort).updateParticipants(
                any(ProtocolAccountRef.class),
                eq("group@g.us"),
                targetsCaptor.capture(),
                eq(GroupParticipantAction.ADD));
        assertThat(targetsCaptor.getValue()).hasSize(1);
        verify(mapper).updateMaterialStageProgress(progressCaptor.capture());
        GroupPullMarketingMapper.MaterialStageProgress progress = progressCaptor.getValue();
        assertThat(progress.expectedRetryCount()).isZero();
        assertThat(progress.nextRetryCount()).isZero();
        assertThat(progress.nextExecuteAt()).isGreaterThanOrEqualTo(before + 240_000L);
    }

    @Test
    void schedulesASeparatedRetryWithoutFinalizingTheMaterial() {
        GroupPullMarketingExecution execution = activeExecution(0);
        stubActiveTask();
        when(mapper.selectNextPendingExecutionMaterial(EXECUTION_ID)).thenReturn(material());
        when(participantPort.updateParticipants(
                any(ProtocolAccountRef.class), anyString(), anyList(), any()))
                .thenReturn(new GroupParticipantBatchResult(false, List.of(
                        new GroupParticipantBatchResult.Item(
                                "8613800000001@s.whatsapp.net", "FAILED", "403"))));
        when(mapper.updateMaterialStageProgress(any())).thenReturn(1);

        service.process(execution, builder());

        verify(mapper, never()).updateMaterialEntryResult(anyLong(), anyInt(), any(), anyLong());
        verify(mapper).updateMaterialStageProgress(progressCaptor.capture());
        assertThat(progressCaptor.getValue().expectedRetryCount()).isZero();
        assertThat(progressCaptor.getValue().nextRetryCount()).isEqualTo(1);
    }

    @Test
    void marksMaterialFailedAfterThirdAttemptAndAdvancesWhenNoneRemain() {
        GroupPullMarketingExecution execution = activeExecution(2);
        stubActiveTask();
        when(mapper.selectNextPendingExecutionMaterial(EXECUTION_ID)).thenReturn(material());
        when(participantPort.updateParticipants(
                any(ProtocolAccountRef.class), anyString(), anyList(), any()))
                .thenReturn(new GroupParticipantBatchResult(false, List.of(
                        new GroupParticipantBatchResult.Item(
                                "8613800000001@s.whatsapp.net", "FAILED", "403"))));
        when(mapper.updateMaterialEntryResult(eq(601L), eq(3), eq("403"), anyLong())).thenReturn(1);
        when(mapper.countPendingExecutionMaterials(EXECUTION_ID)).thenReturn(0L);
        when(mapper.advanceExecutionStage(
                eq(EXECUTION_ID), eq(2), eq(5), eq(6), eq(2), anyLong(), anyLong()))
                .thenReturn(1);

        service.process(execution, builder());

        verify(mapper).updateMaterialEntryResult(eq(601L), eq(3), eq("403"), anyLong());
        verify(mapper).advanceExecutionStage(
                eq(EXECUTION_ID), eq(2), eq(5), eq(6), eq(2), anyLong(), anyLong());
        verify(mapper, never()).updateMaterialStageProgress(any());
    }

    @Test
    void pauseDefersWithoutCallingParticipantProtocol() {
        GroupPullMarketingExecution execution = activeExecution(0);
        MarketingTask runtime = runtime(MarketingTaskStatus.PAUSED);
        when(mapper.selectTaskRuntime(TASK_ID)).thenReturn(runtime);
        when(mapper.selectTaskById(TASK_ID)).thenReturn(extension(GroupPullResourceStatus.LOCKED));
        when(mapper.delayExecution(
                eq(EXECUTION_ID), eq(2), eq(5), anyLong(), eq(null), anyLong())).thenReturn(1);

        service.process(execution, builder());

        verifyNoInteractions(participantPort);
        verify(mapper).delayExecution(
                eq(EXECUTION_ID), eq(2), eq(5), anyLong(), eq(null), anyLong());
    }

    @Test
    void terminalTaskFailsPendingMaterialsAndContinuesPostGroupCleanup() {
        GroupPullMarketingExecution execution = activeExecution(1);
        when(mapper.selectTaskRuntime(TASK_ID)).thenReturn(runtime(MarketingTaskStatus.CLOSED));
        when(mapper.selectTaskById(TASK_ID)).thenReturn(extension(GroupPullResourceStatus.RELEASING));
        when(mapper.failPendingExecutionMaterials(
                eq(EXECUTION_ID), eq("任务已停止，未继续拉料"), anyLong())).thenReturn(2);
        when(mapper.appendExecutionFailureReason(
                eq(EXECUTION_ID), eq("任务已停止，未继续拉料"), anyLong())).thenReturn(1);
        when(mapper.advanceExecutionStage(
                eq(EXECUTION_ID), eq(2), eq(5), eq(6), eq(2), anyLong(), anyLong()))
                .thenReturn(1);

        service.process(execution, builder());

        verifyNoInteractions(participantPort);
        verify(mapper).failPendingExecutionMaterials(
                eq(EXECUTION_ID), eq("任务已停止，未继续拉料"), anyLong());
        verify(mapper).advanceExecutionStage(
                eq(EXECUTION_ID), eq(2), eq(5), eq(6), eq(2), anyLong(), anyLong());
    }

    @Test
    void explicitGroupBanFailsExecutionWithoutSchedulingAnotherMaterialAttempt() {
        GroupPullMarketingExecution execution = activeExecution(0);
        stubActiveTask();
        when(mapper.selectNextPendingExecutionMaterial(EXECUTION_ID)).thenReturn(material());
        when(participantPort.updateParticipants(
                any(ProtocolAccountRef.class), anyString(), anyList(), any()))
                .thenThrow(new ProtocolException(ProtocolErrorCode.GROUP_UNAVAILABLE, "群已封禁"));
        when(mapper.markGroupBanned(eq(EXECUTION_ID), anyLong())).thenReturn(1);

        service.process(execution, builder());

        verify(mapper).markGroupBanned(eq(EXECUTION_ID), anyLong());
        verify(finalizer).fail(EXECUTION_ID, "添加料子失败：群已封禁");
        verify(mapper, never()).updateMaterialStageProgress(any());
    }

    @Test
    void explicitGroupBanParticipantResultAlsoFailsWithoutRetry() {
        GroupPullMarketingExecution execution = activeExecution(0);
        stubActiveTask();
        when(mapper.selectNextPendingExecutionMaterial(EXECUTION_ID)).thenReturn(material());
        when(participantPort.updateParticipants(
                any(ProtocolAccountRef.class), anyString(), anyList(), any()))
                .thenReturn(new GroupParticipantBatchResult(false, List.of(
                        new GroupParticipantBatchResult.Item(
                                "8613800000001@s.whatsapp.net", "GROUP_BANNED", "403"))));
        when(mapper.markGroupBanned(eq(EXECUTION_ID), anyLong())).thenReturn(1);

        service.process(execution, builder());

        verify(mapper).markGroupBanned(eq(EXECUTION_ID), anyLong());
        verify(finalizer).fail(EXECUTION_ID, "添加料子失败：GROUP_BANNED");
        verify(mapper, never()).updateMaterialStageProgress(any());
    }

    @Test
    void structuredProgressLogContainsSafeIdsButNoPhoneOrJid() {
        GroupPullMarketingExecution execution = activeExecution(0);
        stubActiveTask();
        when(mapper.selectNextPendingExecutionMaterial(EXECUTION_ID)).thenReturn(material());
        when(participantPort.updateParticipants(
                any(ProtocolAccountRef.class), anyString(), anyList(), any()))
                .thenReturn(new GroupParticipantBatchResult(false, List.of(
                        new GroupParticipantBatchResult.Item(
                                "8613800000001@s.whatsapp.net", "OK", null))));
        when(mapper.updateMaterialEntryResult(eq(601L), eq(2), eq(null), anyLong())).thenReturn(1);
        when(mapper.countPendingExecutionMaterials(EXECUTION_ID)).thenReturn(0L);
        when(mapper.advanceExecutionStage(
                eq(EXECUTION_ID), eq(2), eq(5), eq(6), eq(2), anyLong(), anyLong()))
                .thenReturn(1);
        Logger logger = (Logger) LoggerFactory.getLogger(
                GroupPullMarketingMaterialEntryService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            service.process(execution, builder());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message)
                        .contains(
                                "taskId=101",
                                "executionId=501",
                                "allocationNo=1",
                                "attempt=1",
                                "result=SUCCESS",
                                "nextExecuteAt=")
                        .doesNotContain(
                                "8613800000001",
                                "8613800000201",
                                "group@g.us"));
    }

    private void stubActiveTask() {
        when(mapper.selectTaskRuntime(TASK_ID)).thenReturn(runtime(MarketingTaskStatus.SENDING));
        when(mapper.selectTaskById(TASK_ID)).thenReturn(extension(GroupPullResourceStatus.LOCKED));
    }

    private static GroupPullMarketingExecution activeExecution(int retryCount) {
        GroupPullMarketingExecution execution = new GroupPullMarketingExecution();
        execution.setId(EXECUTION_ID);
        execution.setTaskId(TASK_ID);
        execution.setGroupJid("group@g.us");
        execution.setExecutionStatus(GroupPullExecutionStatus.EXECUTING.code());
        execution.setCurrentStage(GroupPullExecutionStage.ADD_MATERIALS.code());
        execution.setStageRetryCount(retryCount);
        return execution;
    }

    private static GroupPullMarketingExecutionMaterial material() {
        GroupPullMarketingExecutionMaterial material = new GroupPullMarketingExecutionMaterial();
        material.setId(601L);
        material.setAllocationNo(1);
        material.setMaterialPhone("8613800000001");
        material.setEntryStatus(1);
        return material;
    }

    private static MarketingTask runtime(MarketingTaskStatus status) {
        MarketingTask task = new MarketingTask();
        task.setId(TASK_ID);
        task.setStatus(status.code());
        task.setTaskEndAt(System.currentTimeMillis() + 3_600_000L);
        return task;
    }

    private static GroupPullMarketingTask extension(GroupPullResourceStatus resourceStatus) {
        GroupPullMarketingTask task = new GroupPullMarketingTask();
        task.setMarketingTaskId(TASK_ID);
        task.setResourceStatus(resourceStatus.code());
        task.setMaterialEntryIntervalSeconds(300);
        return task;
    }

    private static ProtocolAccountRef builder() {
        return new ProtocolAccountRef(201L, ProtocolBackend.WEB, "acc-201", "8613800000201");
    }
}
