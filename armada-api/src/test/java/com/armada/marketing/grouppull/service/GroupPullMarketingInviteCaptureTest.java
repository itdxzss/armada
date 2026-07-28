package com.armada.marketing.grouppull.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStage;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullResourceStatus;
import com.armada.marketing.grouppull.model.vo.GroupPullAccountRefRow;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.model.result.GroupInviteResult;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 拉群建群结果落库后的邀请链接捕获与兜底测试。 */
@ExtendWith(MockitoExtension.class)
class GroupPullMarketingInviteCaptureTest {

    private static final PlatformTransactionManager NO_OP_TRANSACTION_MANAGER =
            new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                    // 测试只验证事务回调触发的 Mapper 行为。
                }

                @Override
                public void rollback(TransactionStatus status) {
                    // 测试只验证事务回调触发的 Mapper 行为。
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
                materialEntryService,
                delayPolicy,
                NO_OP_TRANSACTION_MANAGER);
    }

    @Test
    void capturesInviteImmediatelyAfterCreatedGroupIsPersisted() {
        GroupPullMarketingExecution execution = execution(GroupPullExecutionStage.CREATE_GROUP);
        stubDispatch(execution, builder());
        when(mapper.selectAccountRef(301L)).thenReturn(marketer());
        when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenReturn(new GroupCreateResult("group@g.us", false, List.of()));
        when(mapper.markGroupCreated(any())).thenReturn(1);
        when(invitePort.getInvite(any(ProtocolAccountRef.class), eq("group@g.us")))
                .thenReturn(new GroupInviteResult(
                        "group@g.us",
                        "invite-code",
                        "https://chat.whatsapp.com/invite-code"));
        when(mapper.saveInitialGroupInviteUrl(
                eq(501L),
                eq("group@g.us"),
                eq("https://chat.whatsapp.com/invite-code"),
                anyLong())).thenReturn(1);
        when(mapper.updateBlockReason(eq(101L), anyInt(), anyLong())).thenReturn(1);

        worker.process(501L);

        InOrder order = inOrder(mapper, invitePort);
        order.verify(mapper).markGroupCreated(any());
        order.verify(invitePort).getInvite(any(ProtocolAccountRef.class), eq("group@g.us"));
        order.verify(mapper).saveInitialGroupInviteUrl(
                eq(501L),
                eq("group@g.us"),
                eq("https://chat.whatsapp.com/invite-code"),
                anyLong());
        verify(finalizer, never()).fail(anyLong(), anyString());
    }

    @Test
    void immediateInviteFailureDoesNotFailCreatedExecution() {
        GroupPullMarketingExecution execution = execution(GroupPullExecutionStage.CREATE_GROUP);
        stubDispatch(execution, builder());
        when(mapper.selectAccountRef(301L)).thenReturn(marketer());
        when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenReturn(new GroupCreateResult("group@g.us", false, List.of()));
        when(mapper.markGroupCreated(any())).thenReturn(1);
        when(invitePort.getInvite(any(ProtocolAccountRef.class), eq("group@g.us")))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.TEMPORARY_FAILURE,
                        "expected immediate failure"));
        when(mapper.updateBlockReason(eq(101L), anyInt(), anyLong())).thenReturn(1);

        worker.process(501L);

        verify(mapper).markGroupCreated(any());
        verify(mapper, never()).saveInitialGroupInviteUrl(
                anyLong(), anyString(), anyString(), anyLong());
        verify(finalizer, never()).fail(anyLong(), anyString());
    }

    @Test
    void saveGroupInfoReusesInitialInviteWithoutProtocolCall() {
        GroupPullMarketingExecution execution = execution(GroupPullExecutionStage.SAVE_GROUP_INFO);
        execution.setGroupJid("group@g.us");
        execution.setGroupInviteUrl("https://chat.whatsapp.com/already-saved");
        stubDispatch(execution, builder());
        when(mapper.selectAccountRef(301L)).thenReturn(marketer());
        when(memberListPort.list(any())).thenReturn(List.of());
        when(groupRegistry.registerSelfBuiltGroup(
                eq("group@g.us"),
                eq("邀请链接测试群"),
                eq(201L),
                eq("8613800000201"),
                eq(0),
                anyLong())).thenReturn(801L);
        when(mapper.saveGroupInfo(
                eq(501L),
                eq(801L),
                anyString(),
                eq(0),
                isNull(),
                anyLong())).thenReturn(1);
        when(mapper.advanceExecutionStage(
                eq(501L),
                eq(2),
                eq(8),
                eq(9),
                eq(2),
                anyLong(),
                anyLong())).thenReturn(1);
        ArgumentCaptor<String> inviteUrl = ArgumentCaptor.forClass(String.class);

        worker.process(501L);

        verifyNoInteractions(invitePort);
        verify(mapper).saveGroupInfo(
                eq(501L),
                eq(801L),
                inviteUrl.capture(),
                eq(0),
                isNull(),
                anyLong());
        assertThat(inviteUrl.getValue())
                .isEqualTo("https://chat.whatsapp.com/already-saved");
    }

    @Test
    void saveGroupInfoFetchesInviteWhenInitialCaptureIsMissing() {
        GroupPullMarketingExecution execution = execution(GroupPullExecutionStage.SAVE_GROUP_INFO);
        execution.setGroupJid("group@g.us");
        stubDispatch(execution, builder());
        when(mapper.selectAccountRef(301L)).thenReturn(marketer());
        when(memberListPort.list(any())).thenReturn(List.of());
        when(invitePort.getInvite(any(ProtocolAccountRef.class), eq("group@g.us")))
                .thenReturn(new GroupInviteResult(
                        "group@g.us",
                        "fallback-code",
                        "https://chat.whatsapp.com/fallback-code"));
        when(groupRegistry.registerSelfBuiltGroup(
                eq("group@g.us"),
                eq("邀请链接测试群"),
                eq(201L),
                eq("8613800000201"),
                eq(0),
                anyLong())).thenReturn(801L);
        when(mapper.saveGroupInfo(
                eq(501L),
                eq(801L),
                anyString(),
                eq(0),
                isNull(),
                anyLong())).thenReturn(1);
        when(mapper.advanceExecutionStage(
                eq(501L),
                eq(2),
                eq(8),
                eq(9),
                eq(2),
                anyLong(),
                anyLong())).thenReturn(1);
        ArgumentCaptor<String> inviteUrl = ArgumentCaptor.forClass(String.class);

        worker.process(501L);

        verify(invitePort).getInvite(any(ProtocolAccountRef.class), eq("group@g.us"));
        verify(mapper).saveGroupInfo(
                eq(501L),
                eq(801L),
                inviteUrl.capture(),
                eq(0),
                isNull(),
                anyLong());
        assertThat(inviteUrl.getValue())
                .isEqualTo("https://chat.whatsapp.com/fallback-code");
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
        execution.setGroupName("邀请链接测试群");
        execution.setExecutionStatus(stage == GroupPullExecutionStage.CREATE_GROUP
                ? GroupPullExecutionStatus.PREPARING.code()
                : GroupPullExecutionStatus.EXECUTING.code());
        execution.setCurrentStage(stage.code());
        execution.setStageRetryCount(0);
        execution.setNextExecuteAt(0L);
        return execution;
    }

    private static GroupPullMarketingTask task() {
        GroupPullMarketingTask task = new GroupPullMarketingTask();
        task.setMarketingTaskId(101L);
        task.setGroupNamePrefix("邀请链接测试群");
        task.setMaterialEntryIntervalSeconds(300);
        task.setResourceStatus(GroupPullResourceStatus.LOCKED.code());
        return task;
    }

    private static GroupPullAccountRefRow builder() {
        return account(201L, "8613800000201");
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
