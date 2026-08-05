package com.armada.marketing.grouppull.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.group.service.WhatsappGroupBusinessDepartureService;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStage;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullResourceStatus;
import com.armada.marketing.grouppull.model.vo.GroupPullAccountRefRow;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.platform.protocol.port.GroupLeavePort;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 主执行 worker 进入逐料阶段前的首条等待测试。 */
@ExtendWith(MockitoExtension.class)
class GroupPullMarketingFirstMaterialDelayTest {

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
    private GroupPullMarketingFinalizer finalizer;
    @Mock
    private GroupLinkRegistryService groupRegistry;
    @Mock
    private ContactPort contactPort;
    @Mock
    private GroupCreatePort groupCreatePort;
    @Mock
    private GroupParticipantPort participantPort;
    @Mock
    private GroupSettingsPort settingsPort;
    @Mock
    private GroupMemberListPort memberListPort;
    @Mock
    private GroupInvitePort invitePort;
    @Mock
    private GroupLeavePort leavePort;
    @Mock
    private WhatsappGroupBusinessDepartureService businessDepartureService;
    @Mock
    private GroupPullMarketingMaterialEntryService materialEntryService;

    private GroupPullMarketingExecutionWorker worker;

    @BeforeEach
    void setUp() {
        GroupPullMaterialEntryDelayPolicy delayPolicy =
                new GroupPullMaterialEntryDelayPolicy((origin, bound) -> origin);
        worker = new GroupPullMarketingExecutionWorker(
                mapper,
                finalizer,
                groupRegistry,
                contactPort,
                groupCreatePort,
                participantPort,
                settingsPort,
                memberListPort,
                invitePort,
                leavePort,
                businessDepartureService,
                materialEntryService,
                delayPolicy,
                NO_OP_TRANSACTION_MANAGER);
    }

    @Test
    void marketerConfirmedDuringCreationSchedulesFirstMaterialAfterRandomDelay() {
        GroupPullMarketingExecution execution = execution(GroupPullExecutionStage.CREATE_GROUP);
        execution.setGroupName("间隔测试群-1");
        stubDispatch(execution, builder(true));
        when(mapper.selectAccountRef(301L)).thenReturn(marketer());
        when(groupCreatePort.create(any(GroupCreateCommand.class))).thenReturn(new GroupCreateResult(
                "group@g.us",
                false,
                List.of(new GroupCreateParticipantResult(
                        "8613800000301@s.whatsapp.net", "ALREADY_IN", null))));
        when(mapper.markGroupCreated(any())).thenReturn(1);
        when(mapper.confirmMarketingQuota(eq(101L), eq(301L), anyLong())).thenReturn(1);
        when(mapper.updateBlockReason(eq(101L), anyInt(), anyLong())).thenReturn(1);
        ArgumentCaptor<GroupPullMarketingMapper.GroupCreatedUpdate> updateCaptor =
                ArgumentCaptor.forClass(GroupPullMarketingMapper.GroupCreatedUpdate.class);

        worker.process(501L);

        verify(mapper).markGroupCreated(updateCaptor.capture());
        GroupPullMarketingMapper.GroupCreatedUpdate update = updateCaptor.getValue();
        assertThat(update.nextStage()).isEqualTo(GroupPullExecutionStage.ADD_MATERIALS.code());
        assertThat(update.nextExecuteAt() - update.createdAt()).isEqualTo(240_000L);
    }

    @Test
    void separatelyAddedMarketerAlsoSchedulesFirstMaterialAfterRandomDelay() {
        GroupPullMarketingExecution execution = execution(GroupPullExecutionStage.ADD_MARKETER);
        stubDispatch(execution, builder(true));
        when(mapper.selectAccountRef(301L)).thenReturn(marketer());
        when(participantPort.updateParticipants(
                any(ProtocolAccountRef.class),
                eq("group@g.us"),
                anyList(),
                eq(GroupParticipantAction.ADD)))
                .thenReturn(new GroupParticipantBatchResult(false, List.of(
                        new GroupParticipantBatchResult.Item(
                                "8613800000301@s.whatsapp.net", "OK", null))));
        when(mapper.confirmMarketingQuota(eq(101L), eq(301L), anyLong())).thenReturn(1);
        when(mapper.advanceExecutionStage(
                eq(501L), eq(2), eq(4), eq(5), eq(2), anyLong(), anyLong())).thenReturn(1);
        ArgumentCaptor<Long> nextExecuteAt = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> updatedAt = ArgumentCaptor.forClass(Long.class);

        worker.process(501L);

        verify(mapper).advanceExecutionStage(
                eq(501L),
                eq(2),
                eq(4),
                eq(5),
                eq(2),
                nextExecuteAt.capture(),
                updatedAt.capture());
        assertThat(nextExecuteAt.getValue() - updatedAt.getValue()).isEqualTo(240_000L);
    }

    @Test
    void materialStageDelegatesOneStepToMaterialEntryService() {
        GroupPullMarketingExecution execution = execution(GroupPullExecutionStage.ADD_MATERIALS);
        GroupPullAccountRefRow builder = builder(true);
        stubDispatch(execution, builder);

        worker.process(501L);

        verify(materialEntryService).process(execution, builder.protocolRef());
    }

    @Test
    void offlineBuilderCountsAsOneMaterialAttemptInsteadOfGenericRecheck() {
        GroupPullMarketingExecution execution = execution(GroupPullExecutionStage.ADD_MATERIALS);
        stubDispatch(execution, builder(false));

        worker.process(501L);

        verify(materialEntryService).processBuilderUnavailable(execution);
    }

    private void stubDispatch(
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow builder) {
        when(mapper.selectExecutionById(501L)).thenReturn(execution);
        when(mapper.tryLeaseExecution(eq(501L), anyInt(), anyInt(), anyLong(), anyLong()))
                .thenReturn(1);
        when(mapper.selectTaskById(101L)).thenReturn(task());
        when(mapper.selectAccountRef(201L)).thenReturn(builder);
    }

    private static GroupPullMarketingExecution execution(GroupPullExecutionStage stage) {
        GroupPullMarketingExecution execution = new GroupPullMarketingExecution();
        execution.setId(501L);
        execution.setTaskId(101L);
        execution.setBuilderAccountId(201L);
        execution.setMarketingAccountId(301L);
        execution.setGroupJid("group@g.us");
        execution.setExecutionStatus(GroupPullExecutionStatus.EXECUTING.code());
        execution.setCurrentStage(stage.code());
        execution.setStageRetryCount(0);
        execution.setNextExecuteAt(0L);
        return execution;
    }

    private static GroupPullMarketingTask task() {
        GroupPullMarketingTask task = new GroupPullMarketingTask();
        task.setMarketingTaskId(101L);
        task.setGroupNamePrefix("间隔测试群");
        task.setMaterialEntryIntervalSeconds(300);
        task.setResourceStatus(GroupPullResourceStatus.LOCKED.code());
        return task;
    }

    private static GroupPullAccountRefRow builder(boolean online) {
        GroupPullAccountRefRow account = account(201L, "8613800000201");
        account.setLoginState(online ? AccountLoginStateCode.ONLINE : AccountLoginStateCode.OFFLINE);
        return account;
    }

    private static GroupPullAccountRefRow marketer() {
        return account(301L, "8613800000301");
    }

    private static GroupPullAccountRefRow account(Long id, String phone) {
        GroupPullAccountRefRow account = new GroupPullAccountRefRow();
        account.setAccountId(id);
        account.setWsPhone(phone);
        account.setProtocolId("WEB");
        account.setProtocolAccountId("acc-" + id);
        account.setAccountState(AccountStateCode.NORMAL);
        account.setLoginState(AccountLoginStateCode.ONLINE);
        return account;
    }
}
