package com.armada.group.normalcreation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountService;
import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.group.normalcreation.model.dto.NormalGroupCreationCommand;
import com.armada.group.normalcreation.service.NormalGroupCreationEventPublisher;
import com.armada.group.normalcreation.support.NormalGroupCreationAccountLock;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.GroupLinkService;
import com.armada.platform.protocol.model.command.ContactSaveCommand;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupLeavePort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NormalGroupCreationExecutionServiceImplTest {

    @Mock private NormalGroupCreationMapper mapper;
    @Mock private NormalGroupCreationEventPublisher publisher;
    @Mock private ContactPort contactPort;
    @Mock private GroupCreatePort groupCreatePort;
    @Mock private GroupSettingsPort groupSettingsPort;
    @Mock private FixedAccountGroupMetadataPort metadataPort;
    @Mock private GroupParticipantPort participantPort;
    @Mock private GroupLeavePort groupLeavePort;
    @Mock private GroupLinkRegistryService groupLinkRegistryService;
    @Mock private GroupLinkService groupLinkService;
    @Mock private AccountService accountService;
    @Mock private NormalGroupCreationAccountLock accountLock;

    private NormalGroupCreationExecutionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NormalGroupCreationExecutionServiceImpl(
                mapper, publisher, contactPort, groupCreatePort, groupSettingsPort,
                metadataPort, participantPort, groupLeavePort, groupLinkRegistryService,
                groupLinkService, accountService, accountLock, 3);
        lenient().doAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return null;
        }).when(accountLock).runWithLocks(anyLong(), any(), any(Runnable.class));
        lenient().when(accountLock.callWithLocks(anyLong(), any(), any()))
                .thenAnswer(invocation ->
                        ((Supplier<?>) invocation.getArgument(2)).get());
    }

    @Test
    void prepareRoutesEachDirectedContactSaveByItsFrozenBackend() {
        ItemWork item = item(null, "PREPARING_CONTACTS", "PENDING", "RUNNING");
        MemberWork member = member();
        when(mapper.selectItemWork(101L)).thenReturn(item);
        when(mapper.claimStage(eq(101L), eq("PREPARING_CONTACTS"), eq("event-1"),
                eq("prepare"), anyLong()))
                .thenReturn(1);
        when(mapper.selectMemberWorks(101L)).thenReturn(List.of(member));
        when(mapper.completePrepare(eq(101L), eq("event-1"), anyLong())).thenReturn(1);

        service.execute(command("event-1", "PREPARE", 10L));

        ArgumentCaptor<ContactSaveCommand> commands =
                ArgumentCaptor.forClass(ContactSaveCommand.class);
        verify(contactPort, org.mockito.Mockito.times(2)).save(commands.capture());
        assertThat(commands.getAllValues().get(0).account().backend().name()).isEqualTo("ANDROID");
        assertThat(commands.getAllValues().get(1).account().backend().name()).isEqualTo("WEB");
        verify(mapper).completePrepare(eq(101L), eq("event-1"), anyLong());
        verify(publisher).publish("CREATE", 7L, 99L, 101L, 1L);
    }

    @Test
    void createCallsProtocolOnceWithAllFrozenMembers() {
        ItemWork item = item(null, "CREATING_GROUP", "PENDING", "RUNNING");
        MemberWork member = member();
        when(mapper.selectItemWork(101L)).thenReturn(item);
        when(mapper.claimStage(eq(101L), eq("CREATING_GROUP"), eq("event-2"),
                eq("create"), anyLong()))
                .thenReturn(1);
        when(mapper.selectMemberWorks(101L)).thenReturn(List.of(member));
        when(groupCreatePort.create(any())).thenReturn(new GroupCreateResult(
                "1203@g.us", false,
                List.of(new GroupCreateParticipantResult("20002@s.whatsapp.net", "OK", "200"))));
        when(mapper.persistCreatedGroup(
                eq(101L), eq("1203@g.us"), eq(false), eq("event-2"), anyLong()))
                .thenReturn(1);
        when(mapper.completeCreate(eq(101L), eq("event-2"), anyLong())).thenReturn(1);

        service.execute(command("event-2", "CREATE", 20L));

        ArgumentCaptor<GroupCreateCommand> create =
                ArgumentCaptor.forClass(GroupCreateCommand.class);
        verify(groupCreatePort).create(create.capture());
        assertThat(create.getValue().account().backend().name()).isEqualTo("ANDROID");
        assertThat(create.getValue().participants()).containsExactly("20002");
        verify(mapper).persistCreatedGroup(
                eq(101L), eq("1203@g.us"), eq(false), eq("event-2"), anyLong());
        verify(mapper).completeCreate(eq(101L), eq("event-2"), anyLong());
        verify(publisher).publish("POST_PROCESS", 7L, 99L, 101L, 1L);
    }

    @Test
    void createPersistsPartialAndMarksMissingParticipantReceiptUnknown() {
        ItemWork item = item(null, "CREATING_GROUP", "PENDING", "RUNNING");
        MemberWork member = member();
        when(mapper.selectItemWork(101L)).thenReturn(item);
        when(mapper.claimStage(eq(101L), eq("CREATING_GROUP"), eq("event-partial"),
                eq("create"), anyLong()))
                .thenReturn(1);
        when(mapper.selectMemberWorks(101L)).thenReturn(List.of(member));
        when(groupCreatePort.create(any())).thenReturn(
                new GroupCreateResult("1203@g.us", true, List.of()));
        when(mapper.persistCreatedGroup(
                eq(101L), eq("1203@g.us"), eq(true), eq("event-partial"), anyLong()))
                .thenReturn(1);
        when(mapper.completeCreate(eq(101L), eq("event-partial"), anyLong())).thenReturn(1);

        service.execute(command("event-partial", "CREATE", 20L));

        InOrder order = inOrder(mapper);
        order.verify(mapper).persistCreatedGroup(
                eq(101L), eq("1203@g.us"), eq(true), eq("event-partial"), anyLong());
        order.verify(mapper).updateParticipantStatus(
                eq(201L), eq("UNKNOWN"), eq(null), anyLong());
        order.verify(mapper).completeCreate(eq(101L), eq("event-partial"), anyLong());
        verify(publisher).publish("POST_PROCESS", 7L, 99L, 101L, 1L);
    }

    @Test
    void createTurnsResultUnknownWhenReturnedGroupJidCannotBePersisted() {
        ItemWork item = item(null, "CREATING_GROUP", "PENDING", "RUNNING");
        when(mapper.selectItemWork(101L)).thenReturn(item);
        when(mapper.claimStage(eq(101L), eq("CREATING_GROUP"), eq("event-cas"),
                eq("create"), anyLong()))
                .thenReturn(1);
        when(mapper.selectMemberWorks(101L)).thenReturn(List.of(member()));
        when(groupCreatePort.create(any())).thenReturn(
                new GroupCreateResult("1203@g.us", false, List.of()));
        when(mapper.persistCreatedGroup(
                eq(101L), eq("1203@g.us"), eq(false), eq("event-cas"), anyLong()))
                .thenReturn(0);
        when(mapper.failItem(eq(101L), eq("RESULT_UNKNOWN"),
                eq("IllegalStateException"), any(), eq("event-cas"), anyLong()))
                .thenReturn(1);

        service.execute(command("event-cas", "CREATE", 20L));

        verify(mapper).failItem(eq(101L), eq("RESULT_UNKNOWN"),
                eq("IllegalStateException"), any(), eq("event-cas"), anyLong());
        verify(publisher, never()).publish(
                eq("POST_PROCESS"), anyLong(), anyLong(), anyLong(), anyLong());
        verify(mapper, never()).updateParticipantStatus(anyLong(), any(), any(), anyLong());
    }

    @Test
    void retryableCreateFailureReleasesStageAndPropagatesToKafka() {
        ItemWork item = item(null, "CREATING_GROUP", "PENDING", "RUNNING");
        when(mapper.selectItemWork(101L)).thenReturn(item);
        when(mapper.claimStage(eq(101L), eq("CREATING_GROUP"), eq("event-retry"),
                eq("create"), anyLong()))
                .thenReturn(1);
        when(mapper.selectMemberWorks(101L)).thenReturn(List.of(member()));
        when(groupCreatePort.create(any())).thenThrow(
                new ProtocolException(ProtocolErrorCode.NETWORK, "网络暂时不可用"));
        when(mapper.releaseStageForRetry(eq(101L), eq("CREATING_GROUP"),
                eq("event-retry"), eq(3), eq("NETWORK"), eq("协议服务暂时不可用，请稍后重试"),
                anyLong(), anyLong()))
                .thenReturn(1);

        assertThatThrownBy(() -> service.execute(command("event-retry", "CREATE", 20L)))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("网络暂时不可用");

        verify(mapper).releaseStageForRetry(eq(101L), eq("CREATING_GROUP"),
                eq("event-retry"), eq(3), eq("NETWORK"), eq("协议服务暂时不可用，请稍后重试"),
                anyLong(), anyLong());
        verify(mapper).refreshTaskSummary(eq(99L), anyLong());
        verify(mapper, never()).failItem(anyLong(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void postFailureKeepsCreatedFactAndUsesSuccessMigration() {
        ItemWork item = item("1203@g.us", "POST_PROCESSING", "PENDING", "RUNNING");
        when(mapper.selectItemWork(101L)).thenReturn(item);
        when(mapper.claimStage(eq(101L), eq("POST_PROCESSING"), eq("event-3"),
                eq("post"), anyLong()))
                .thenReturn(1);
        when(mapper.selectMemberWorks(101L)).thenReturn(List.of(member()));
        when(groupLinkRegistryService.registerSelfBuiltGroup(
                eq("1203@g.us"), eq("群-1"), eq(1L), eq("10001"), eq(2), anyLong()))
                .thenReturn(501L);
        when(mapper.updateGroupLink(eq(101L), eq(501L), anyLong())).thenReturn(1);
        when(mapper.failItem(anyLong(), any(), any(), any(), any(), anyLong())).thenReturn(1);
        doThrow(new IllegalStateException("设置失败"))
                .when(groupSettingsPort)
                .setSendMessagesAllowed(any(ProtocolAccountRef.class), eq("1203@g.us"), eq(true));

        service.execute(command("event-3", "POST_PROCESS", 30L));

        verify(mapper).failItem(
                eq(101L), eq("CREATED_PARTIAL"), eq("IllegalStateException"),
                eq("系统执行异常，请联系管理员"), eq("event-3"), anyLong());
        verify(accountService).migrateGroup(List.of(1L), 30L);
        verify(mapper).refreshTaskSummary(eq(99L), anyLong());
        InOrder order = inOrder(groupLinkRegistryService, mapper, groupSettingsPort);
        order.verify(groupLinkRegistryService).registerSelfBuiltGroup(
                eq("1203@g.us"), eq("群-1"), eq(1L), eq("10001"), eq(2), anyLong());
        order.verify(mapper).updateGroupLink(eq(101L), eq(501L), anyLong());
        order.verify(groupSettingsPort).setSendMessagesAllowed(
                any(ProtocolAccountRef.class), eq("1203@g.us"), eq(true));
    }

    @Test
    void leaveTimeoutBecomesResultUnknownAndNeverReplaysPostProcess() {
        ItemWork item = item(
                "1203@g.us", "POST_PROCESSING", "PENDING", "RUNNING", "LEAVE");
        MemberWork member = new MemberWork(
                201L, 2L, "acc_2", "WEB", "20002",
                "SUCCESS", "SUCCESS", "OK");
        GroupMetadataResult metadata = new GroupMetadataResult(
                "1203@g.us", "群-1", null, "10001@s.whatsapp.net", 1L,
                true, false, true, true, false, 0,
                null, false, null, false, true,
                List.of(new GroupParticipantResult(
                        "20002@s.whatsapp.net", "20002", true, false, "admin")));
        when(mapper.selectItemWork(101L)).thenReturn(item);
        when(mapper.claimStage(eq(101L), eq("POST_PROCESSING"), eq("event-leave-timeout"),
                eq("post"), anyLong()))
                .thenReturn(1);
        when(mapper.selectMemberWorks(101L)).thenReturn(List.of(member));
        when(groupLinkRegistryService.registerSelfBuiltGroup(
                eq("1203@g.us"), eq("群-1"), eq(1L), eq("10001"), eq(2), anyLong()))
                .thenReturn(501L);
        when(mapper.updateGroupLink(eq(101L), eq(501L), anyLong())).thenReturn(1);
        when(metadataPort.getMetadata(any(), eq("1203@g.us"))).thenReturn(metadata);
        doThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "下游原始响应可能包含敏感内容"))
                .when(groupLeavePort).leave(any(), eq("1203@g.us"));
        when(mapper.failItem(eq(101L), eq("RESULT_UNKNOWN"), eq("TIMEOUT"),
                eq("协议调用超时，请稍后重试"), eq("event-leave-timeout"), anyLong()))
                .thenReturn(1);

        service.execute(command("event-leave-timeout", "POST_PROCESS", 40L));

        verify(mapper).failItem(eq(101L), eq("RESULT_UNKNOWN"), eq("TIMEOUT"),
                eq("协议调用超时，请稍后重试"), eq("event-leave-timeout"), anyLong());
        verify(mapper, never()).releaseStageForRetry(
                anyLong(), any(), any(), anyInt(), any(), any(), anyLong(), anyLong());
        verify(accountService).migrateGroup(List.of(1L), 30L);
    }

    private static ItemWork item(
            String groupJid, String currentStep, String dispatchStatus, String status) {
        return item(groupJid, currentStep, dispatchStatus, status, "KEEP");
    }

    private static ItemWork item(
            String groupJid,
            String currentStep,
            String dispatchStatus,
            String status,
            String leavePolicy) {
        return new ItemWork(
                101L, 7L, 99L, "群-1", 1L, "acc_1", "ANDROID", "10001",
                groupJid, status, currentStep, dispatchStatus, leavePolicy, null,
                30L, 40L, true, false, true, false, 0);
    }

    private static MemberWork member() {
        return new MemberWork(
                201L, 2L, "acc_2", "WEB", "20002",
                "PENDING", "PENDING", "PENDING");
    }

    private static NormalGroupCreationCommand command(
            String eventId, String action, long occurredAt) {
        return new NormalGroupCreationCommand(1, eventId, 7L, 99L, 101L, action, occurredAt);
    }
}
