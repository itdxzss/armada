package com.armada.marketing.service;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.service.AccountRestrictionService;
import com.armada.marketing.mapper.GroupCreationMarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.enums.GroupCreationMarketingItemStatus;
import com.armada.marketing.model.support.GroupCreationMarketingItemMarketingDispatch;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.marketing.service.impl.GroupCreationMarketingRetryService;
import com.armada.marketing.service.impl.GroupCreationMarketingWorker;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ContactSaveCommand;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.command.GroupMemberListQuery;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.platform.protocol.port.MessageSendPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupCreationMarketingWorkerTest {

    @Mock
    private GroupCreationMarketingTaskMapper groupCreationMapper;
    @Mock
    private MarketingTemplateMapper templateMapper;
    @Mock
    private MarketingTemplateFileMapper fileMapper;
    @Mock
    private MarketingMessageComposer messageComposer;
    @Mock
    private MessageSendPort messageSendPort;
    @Mock
    private ContactPort contactPort;
    @Mock
    private GroupCreatePort groupCreatePort;
    @Mock
    private GroupMemberListPort groupMemberListPort;
    @Mock
    private GroupCreationMarketingRetryService retryService;
    @Mock
    private AccountRestrictionService accountRestrictionService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private GroupCreationMarketingWorker worker;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(messageSendPort.enqueue(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MessageSendCommand> commands = invocation.getArgument(0, List.class);
            return new MessageSendEnqueueResult(commands.stream()
                    .map(command -> MessageSendEnqueueItem.accepted(command.commandId()))
                    .toList());
        });
        worker = new GroupCreationMarketingWorker(
                groupCreationMapper,
                templateMapper,
                fileMapper,
                messageComposer,
                messageSendPort,
                contactPort,
                groupCreatePort,
                groupMemberListPort,
                retryService,
                accountRestrictionService,
                new ObjectMapper(),
                transactionManager);
    }

    @Test
    void processOnlineItemCreatesGroupAndEnqueuesOneShotGroupCreationMessage() {
        seedSuccessfulOnlineItem();

        worker.processDueItems(10);

        verify(groupCreatePort).create(argThat(command ->
                matchesGroupCreateCommand(command, "acc_7")));
        ArgumentCaptor<List<MessageSendCommand>> commands = ArgumentCaptor.forClass(List.class);
        verify(messageSendPort).enqueue(commands.capture());
        assertThat(commands.getValue()).singleElement().satisfies(command -> {
            assertThat(command.correlation().marketing()).isNull();
            assertThat(command.correlation().groupCreation().taskId()).isEqualTo(22L);
            assertThat(command.correlation().groupCreation().itemId()).isEqualTo(11L);
            assertThat(command.target().groupJid()).isEqualTo("120363created@g.us");
            assertThat(command.payload().content().text()).isEqualTo("hello");
            assertThat(command.payload().mentionAll()).isTrue();
            assertThat(command.sendIntervalMs()).isEqualTo(500);
            assertThat(command.correlation().source()).isEqualTo("group_creation_marketing");
        });
        verify(groupCreationMapper).markItemMarketingSending(argThat(dispatch ->
                Long.valueOf(11L).equals(dispatch.getId())
                        && "120363created@g.us".equals(dispatch.getGroupJid())
                        && dispatch.getMarketingTaskId() == null
                        && dispatch.getMarketingTargetId() == null
                        && dispatch.getMarketingAttemptId() == null
                        && dispatch.getCommandId() != null
                        && dispatch.getParticipantResultJson() != null));
    }

    @Test
    void androidCandidateUsesCurrentProtocolFactsForMessageRouting() {
        seedSuccessfulOnlineItem();
        GroupCreationMarketingAccountCandidate android = account(
                7L,
                "919000000001",
                "acc_android",
                AccountStateCode.NORMAL,
                AccountLoginStateCode.ONLINE);
        android.setProtocolId("ANDROID");
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L)).thenReturn(android);
        when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenReturn(new GroupCreateResult("120363created@g.us", false, List.of()));

        worker.processDueItems(10);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageSendCommand>> commands = ArgumentCaptor.forClass(List.class);
        verify(messageSendPort).enqueue(commands.capture());
        ArgumentCaptor<GroupCreateCommand> groupCreate = ArgumentCaptor.forClass(GroupCreateCommand.class);
        verify(groupCreatePort).create(groupCreate.capture());
        assertThat(groupCreate.getValue().account()).isEqualTo(new ProtocolAccountRef(
                7L,
                ProtocolBackend.ANDROID,
                "acc_android",
                "919000000001"));
        ArgumentCaptor<ContactSaveCommand> contactSaves = ArgumentCaptor.forClass(ContactSaveCommand.class);
        verify(contactPort, timeout(500).times(2)).save(contactSaves.capture());
        assertThat(contactSaves.getAllValues())
                .extracting(ContactSaveCommand::account)
                .containsOnly(new ProtocolAccountRef(
                        7L,
                        ProtocolBackend.ANDROID,
                        "acc_android",
                        "919000000001"));
        assertThat(contactSaves.getAllValues())
                .extracting(ContactSaveCommand::operationId)
                .containsOnly("group-creation-marketing-item:11");
        ArgumentCaptor<GroupMemberListQuery> memberList = ArgumentCaptor.forClass(GroupMemberListQuery.class);
        verify(groupMemberListPort).list(memberList.capture());
        assertThat(memberList.getValue().account()).isEqualTo(new ProtocolAccountRef(
                7L,
                ProtocolBackend.ANDROID,
                "acc_android",
                "919000000001"));
        assertThat(memberList.getValue().operationId()).isEqualTo("group-creation-marketing-item:11");
        assertThat(commands.getValue()).singleElement().satisfies(command ->
                assertThat(command.account()).isEqualTo(new ProtocolAccountRef(
                        7L,
                        ProtocolBackend.ANDROID,
                        "acc_android",
                        "919000000001")));
    }

    @Test
    void locallyRejectedMessageStoresCreatedGroupThenMarksItemFailedWithoutAccountRetry() {
        seedSuccessfulOnlineItem();
        when(messageSendPort.enqueue(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<MessageSendCommand> commands = invocation.getArgument(0, List.class);
            return new MessageSendEnqueueResult(List.of(MessageSendEnqueueItem.rejected(
                    commands.get(0).commandId(),
                    "INVALID_ANDROID_BUTTON_CONFIG",
                    "按钮数量只支持 1 个")));
        });
        when(groupCreationMapper.markItemFailedByCommandId(
                eq(11L), anyString(), anyString(), anyString(), anyLong())).thenReturn(1);

        worker.processDueItems(10);

        ArgumentCaptor<GroupCreationMarketingItemMarketingDispatch> dispatch =
                ArgumentCaptor.forClass(GroupCreationMarketingItemMarketingDispatch.class);
        verify(groupCreationMapper).markItemMarketingSending(dispatch.capture());
        assertThat(dispatch.getValue().getGroupJid()).isEqualTo("120363created@g.us");
        assertThat(dispatch.getValue().getCommandId()).startsWith("cmd_");
        verify(groupCreationMapper).markItemFailedByCommandId(
                11L,
                dispatch.getValue().getCommandId(),
                "INVALID_ANDROID_BUTTON_CONFIG",
                "按钮数量只支持 1 个",
                dispatch.getValue().getUpdatedAt());
        verifyNoInteractions(retryService);
    }

    @Test
    void processOnlineItemDoesNotLinkTaskToContinuousMarketingTask() {
        seedSuccessfulOnlineItem();

        worker.processDueItems(10);

        verify(groupCreationMapper, never()).updateTaskMarketingTaskIdIfAbsent(anyLong(), anyLong(), anyLong());
    }

    @Test
    void processOnlineItemStoresSendMemberCountSnapshotBeforeMarketingSend() {
        seedSuccessfulOnlineItem();
        when(groupMemberListPort.list(any(GroupMemberListQuery.class)))
                .thenReturn(List.of(
                        new GroupParticipantResult("8613000000000@s.whatsapp.net", "8613000000000", true, true, "superadmin"),
                        new GroupParticipantResult("8613900000000@s.whatsapp.net", "8613900000000", false, false, null),
                        new GroupParticipantResult("8613911111111@s.whatsapp.net", "8613911111111", false, false, null)));

        worker.processDueItems(10);

        ArgumentCaptor<GroupMemberListQuery> query = ArgumentCaptor.forClass(GroupMemberListQuery.class);
        verify(groupMemberListPort).list(query.capture());
        assertThat(query.getValue().account().protocolAccountId()).isEqualTo("acc_7");
        assertThat(query.getValue().groupJid()).isEqualTo("120363created@g.us");
        assertThat(query.getValue().operationId()).isEqualTo("group-creation-marketing-item:11");
        verify(groupCreationMapper).markItemMarketingSending(argThat(dispatch ->
                Long.valueOf(11L).equals(dispatch.getId())
                        && "120363created@g.us".equals(dispatch.getGroupJid())
                        && Integer.valueOf(3).equals(dispatch.getSendMemberCount())
                        && dispatch.getSendMemberCountCheckedAt() != null
                        && dispatch.getUpdatedAt() != null));
    }

    @Test
    void processOnlineItemContinuesWhenMemberSnapshotQueryFails() {
        seedSuccessfulOnlineItem();
        when(groupMemberListPort.list(any(GroupMemberListQuery.class)))
                .thenThrow(new IllegalStateException("participants timeout"));

        worker.processDueItems(10);

        verify(messageSendPort).enqueue(anyList());
        verify(groupCreationMapper).markItemMarketingSending(argThat(dispatch ->
                Long.valueOf(11L).equals(dispatch.getId())
                        && "120363created@g.us".equals(dispatch.getGroupJid())
                        && dispatch.getSendMemberCount() == null
                        && dispatch.getSendMemberCountCheckedAt() == null
                        && dispatch.getUpdatedAt() != null));
    }

    @Test
    void processDueItemsProcessesItemsConcurrently() {
        GroupCreationMarketingItem first = item();
        GroupCreationMarketingItem second = item();
        second.setId(12L);
        second.setAccountId(8L);
        second.setAccountPhone("8613000000001");
        second.setProtocolAccountId("acc_8");
        GroupCreationMarketingTask task = task(null);
        MarketingTemplate template = template();
        when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(first, second));
        when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.claimItem(eq(12L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L))
                .thenReturn(account(7L, "8613000000000", "acc_7",
                        AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE));
        when(groupCreationMapper.selectAccountCandidateByAccountId(8L))
                .thenReturn(account(8L, "8613000000001", "acc_8",
                        AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE));
        when(templateMapper.selectById(18L)).thenReturn(template);
        when(messageComposer.compose(eq(template), any())).thenReturn(new MarketingMessageComposer.ComposedMessage(
                "TEXT", "hello", null, null));
        when(groupCreationMapper.markItemMarketingSending(any(GroupCreationMarketingItemMarketingDispatch.class))).thenReturn(1);

        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        doAnswer(invocation -> {
            int running = active.incrementAndGet();
            maxActive.updateAndGet(previous -> Math.max(previous, running));
            bothStarted.countDown();
            bothStarted.await(500, TimeUnit.MILLISECONDS);
            active.decrementAndGet();
            GroupCreateCommand command = invocation.getArgument(0);
            String protocolAccountId = command.account().protocolAccountId();
            return new GroupCreateResult("120363" + protocolAccountId + "@g.us", false, List.of());
        }).when(groupCreatePort).create(any(GroupCreateCommand.class));

        worker.processDueItems(10);

        assertThat(maxActive.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void processOnlineItemSubmitsMaterialContactPreSaves() {
        seedSuccessfulOnlineItem();

        worker.processDueItems(10);

        ArgumentCaptor<ContactSaveCommand> commands = ArgumentCaptor.forClass(ContactSaveCommand.class);
        verify(contactPort, timeout(500).times(2)).save(commands.capture());
        assertThat(commands.getAllValues())
                .extracting(ContactSaveCommand::contact)
                .containsExactlyInAnyOrder("8613900000000", "8613911111111");
        assertThat(commands.getAllValues())
                .extracting(command -> command.account().protocolAccountId())
                .containsOnly("acc_7");
        verify(groupCreatePort).create(argThat(command ->
                matchesGroupCreateCommand(command, "acc_7")));
    }

    @Test
    void contactPreSaveDoesNotDelayGroupCreate() throws Exception {
        GroupCreationMarketingItem item = item();
        GroupCreationMarketingTask task = task(null);
        GroupCreationMarketingAccountCandidate account = account(AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE);
        MarketingTemplate template = template();
        when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
        when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L)).thenReturn(account);
        when(templateMapper.selectById(18L)).thenReturn(template);
        when(messageComposer.compose(eq(template), any())).thenReturn(new MarketingMessageComposer.ComposedMessage(
                "TEXT", "hello", null, null));
        when(groupCreationMapper.markItemMarketingSending(any(GroupCreationMarketingItemMarketingDispatch.class))).thenReturn(1);
        CountDownLatch contactSaveStarted = new CountDownLatch(1);
        CountDownLatch releaseContactSave = new CountDownLatch(1);
        CountDownLatch groupCreateStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            contactSaveStarted.countDown();
            releaseContactSave.await(2, TimeUnit.SECONDS);
            return null;
        }).when(contactPort).save(any(ContactSaveCommand.class));
        doAnswer(invocation -> {
            groupCreateStarted.countDown();
            return new GroupCreateResult("120363created@g.us", false, List.of());
        }).when(groupCreatePort).create(any(GroupCreateCommand.class));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(() -> worker.processDueItems(10));
        try {
            assertThat(contactSaveStarted.await(200, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(groupCreateStarted.await(200, TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            releaseContactSave.countDown();
            future.get(2, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    void contactPreSaveSummaryCountsSubmittedRequestsAndDoesNotBlockGroupCreate() {
        seedSuccessfulOnlineItem();
        ArgumentCaptor<GroupCreationMarketingItemMarketingDispatch> dispatch =
                ArgumentCaptor.forClass(GroupCreationMarketingItemMarketingDispatch.class);

        worker.processDueItems(10);

        verify(groupCreatePort).create(argThat(command ->
                matchesGroupCreateCommand(command, "acc_7")));
        verify(groupCreationMapper).markItemMarketingSending(dispatch.capture());
        assertThat(dispatch.getValue().getParticipantResultJson())
                .contains("\"contactSave\"")
                .contains("\"total\":2")
                .contains("\"success\":2")
                .contains("\"failed\":0")
                .contains("\"failures\":[]");
    }

    @Test
    void processOfflineItemIsAbandonedWithoutCallingProtocol() {
        GroupCreationMarketingItem item = item();
        when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
        when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task(null));
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L))
                .thenReturn(account(AccountStateCode.NORMAL, AccountLoginStateCode.OFFLINE));
        when(retryService.replaceClaimedItemAccountForRetry(eq(item), any(), eq(GroupCreationMarketingRetryService.STAGE_ACCOUNT_CHECK),
                eq("ACCOUNT_OFFLINE"), eq("账号离线"), anyLong())).thenReturn(null);

        worker.processDueItems(10);

        verify(retryService).replaceClaimedItemAccountForRetry(eq(item), any(),
                eq(GroupCreationMarketingRetryService.STAGE_ACCOUNT_CHECK), eq("ACCOUNT_OFFLINE"), eq("账号离线"),
                anyLong());
        verify(contactPort, never()).save(any(ContactSaveCommand.class));
        verify(groupCreatePort, never()).create(any(GroupCreateCommand.class));
    }

    @Test
    void offlineAssignedAccountIsReplacedByAvailableGroupAccountBeforeGroupCreate() {
        GroupCreationMarketingItem item = item();
        GroupCreationMarketingTask task = task(null);
        GroupCreationMarketingAccountCandidate offlineAccount = account(
                7L, "8613000000000", "acc_7", AccountStateCode.NORMAL, AccountLoginStateCode.OFFLINE);
        GroupCreationMarketingAccountCandidate replacementAccount = account(
                9L, "919000000009", "acc_9", AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE);
        replacementAccount.setProtocolId("ANDROID");
        MarketingTemplate template = template();
        when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
        when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L)).thenReturn(offlineAccount);
        doAnswer(invocation -> {
            item.setAccountId(9L);
            item.setAccountPhone("919000000009");
            item.setProtocolAccountId("acc_9");
            return replacementAccount;
        }).when(retryService).replaceClaimedItemAccountForRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_ACCOUNT_CHECK), eq("ACCOUNT_OFFLINE"), eq("账号离线"),
                anyLong());
        when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenReturn(new GroupCreateResult("120363created@g.us", false, List.of()));
        when(templateMapper.selectById(18L)).thenReturn(template);
        when(messageComposer.compose(eq(template), any())).thenReturn(new MarketingMessageComposer.ComposedMessage(
                "TEXT", "hello", null, null));
        when(groupCreationMapper.markItemMarketingSending(any(GroupCreationMarketingItemMarketingDispatch.class))).thenReturn(1);

        worker.processDueItems(10);

        verify(retryService).replaceClaimedItemAccountForRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_ACCOUNT_CHECK), eq("ACCOUNT_OFFLINE"), eq("账号离线"),
                anyLong());
        ArgumentCaptor<GroupCreateCommand> create = ArgumentCaptor.forClass(GroupCreateCommand.class);
        verify(groupCreatePort).create(create.capture());
        ProtocolAccountRef replacementRef = new ProtocolAccountRef(
                9L,
                ProtocolBackend.ANDROID,
                "acc_9",
                "919000000009");
        assertThat(create.getValue().account()).isEqualTo(replacementRef);
        ArgumentCaptor<List<MessageSendCommand>> commands = ArgumentCaptor.forClass(List.class);
        verify(messageSendPort).enqueue(commands.capture());
        assertThat(commands.getValue()).singleElement().satisfies(command -> {
            assertThat(command.account()).isEqualTo(replacementRef);
            assertThat(command.correlation().groupCreation().itemId()).isEqualTo(11L);
        });
    }

    @Test
    void processBannedItemIsAbandonedWithoutCallingProtocol() {
        GroupCreationMarketingItem item = item();
        when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
        when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task(null));
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L))
                .thenReturn(account(AccountStateCode.BANNED, AccountLoginStateCode.ONLINE));
        when(retryService.replaceClaimedItemAccountForRetry(eq(item), any(), eq(GroupCreationMarketingRetryService.STAGE_ACCOUNT_CHECK),
                eq("ACCOUNT_UNUSABLE"), eq("账号不可用"), anyLong())).thenReturn(null);

        worker.processDueItems(10);

        verify(retryService).replaceClaimedItemAccountForRetry(eq(item), any(),
                eq(GroupCreationMarketingRetryService.STAGE_ACCOUNT_CHECK), eq("ACCOUNT_UNUSABLE"), eq("账号不可用"),
                anyLong());
        verify(contactPort, never()).save(any(ContactSaveCommand.class));
        verify(groupCreatePort, never()).create(any(GroupCreateCommand.class));
    }

    @Test
    void processProtocolGroupCreateFailureSchedulesAccountRetry() {
        GroupCreationMarketingItem item = item();
        GroupCreationMarketingTask task = task(null);
        when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
        when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L))
                .thenReturn(account(AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE));
        when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenThrow(new IllegalStateException("protocol down"));
        when(retryService.resetItemForAccountRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_GROUP_CREATE), eq("GROUP_CREATE_FAILED"),
                eq("protocol down"), anyLong())).thenReturn(true);

        worker.processDueItems(10);

        verify(retryService).resetItemForAccountRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_GROUP_CREATE), eq("GROUP_CREATE_FAILED"),
                eq("protocol down"), anyLong());
        verify(groupCreationMapper, never()).markItemFailed(anyLong(), eq("GROUP_CREATE_FAILED"),
                any(), any(), anyLong());
    }

    @Test
    void processRestrictedGroupCreateFailureMarksActualAccountRestrictedAndRetries() {
        GroupCreationMarketingItem item = item();
        GroupCreationMarketingTask task = task(null);
        when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
        when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L))
                .thenReturn(account(AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE));
        when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenThrow(new ProtocolException(ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED,
                        "协议层错误 422 ACCOUNT_REACHOUT_RESTRICTED: account_reachout_restricted"));
        when(retryService.resetItemForAccountRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_GROUP_CREATE), eq("GROUP_CREATE_FAILED"),
                anyString(), anyLong())).thenReturn(true);

        worker.processDueItems(10);

        verify(accountRestrictionService).markGroupCreateRestricted(
                eq(7L), eq("acc_7"), eq("account_reachout_restricted"), anyLong());
        verify(retryService).resetItemForAccountRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_GROUP_CREATE), eq("GROUP_CREATE_FAILED"),
                anyString(), anyLong());
    }

    @Test
    void processRestrictedFailureAfterAccountReplacementMarksReplacementAccount() {
        GroupCreationMarketingItem item = item();
        GroupCreationMarketingTask task = task(null);
        GroupCreationMarketingAccountCandidate replacement =
                account(8L, "8613000000001", "acc_8", AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE);
        when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
        when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L))
                .thenReturn(account(AccountStateCode.NORMAL, AccountLoginStateCode.OFFLINE));
        when(retryService.replaceClaimedItemAccountForRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_ACCOUNT_CHECK),
                eq("ACCOUNT_OFFLINE"), eq("账号离线"), anyLong())).thenReturn(replacement);
        when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenThrow(new ProtocolException(ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED,
                        "协议层错误 429 ACCOUNT_REACHOUT_RESTRICTED: rate-overlimit"));
        when(retryService.resetItemForAccountRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_GROUP_CREATE), eq("GROUP_CREATE_FAILED"),
                anyString(), anyLong())).thenReturn(true);

        worker.processDueItems(10);

        verify(accountRestrictionService).markGroupCreateRestricted(
                eq(8L), eq("acc_8"), eq("rate-overlimit"), anyLong());
    }

    @Test
    void processAccountBusyGroupCreateFailureDoesNotMarkRestricted() {
        GroupCreationMarketingItem item = item();
        GroupCreationMarketingTask task = task(null);
        when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
        when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L))
                .thenReturn(account(AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE));
        when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenThrow(new ProtocolException(ProtocolErrorCode.ACCOUNT_BUSY,
                        "协议层错误 429 ACCOUNT_BUSY: group operation in progress"));
        when(retryService.resetItemForAccountRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_GROUP_CREATE), eq("GROUP_CREATE_FAILED"),
                anyString(), anyLong())).thenReturn(true);

        worker.processDueItems(10);

        verify(accountRestrictionService, never()).markGroupCreateRestricted(anyLong(), anyString(), anyString(), anyLong());
        verify(retryService).resetItemForAccountRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_GROUP_CREATE), eq("GROUP_CREATE_FAILED"),
                anyString(), anyLong());
    }

    private void seedSuccessfulOnlineItem() {
        GroupCreationMarketingItem item = item();
        GroupCreationMarketingTask task = task(null);
        GroupCreationMarketingAccountCandidate account = account(AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE);
        MarketingTemplate template = template();
        when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
        when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L)).thenReturn(account);
        lenient().when(groupCreatePort.create(any(GroupCreateCommand.class)))
                .thenReturn(new GroupCreateResult("120363created@g.us", false, List.of()));
        when(templateMapper.selectById(18L)).thenReturn(template);
        when(messageComposer.compose(eq(template), any())).thenReturn(new MarketingMessageComposer.ComposedMessage(
                "TEXT", "hello", null, null, true));
        when(groupCreationMapper.markItemMarketingSending(any(GroupCreationMarketingItemMarketingDispatch.class))).thenReturn(1);
    }

    private GroupCreationMarketingItem item() {
        GroupCreationMarketingItem item = new GroupCreationMarketingItem();
        item.setId(11L);
        item.setTenantId(1L);
        item.setTaskId(22L);
        item.setAccountId(7L);
        item.setAccountPhone("8613000000000");
        item.setProtocolAccountId("acc_7");
        item.setGroupSubject("活动群-1");
        item.setMaterialContent("8613900000000\n8613911111111");
        item.setParticipantCount(2);
        item.setStatus(GroupCreationMarketingItemStatus.PENDING.code());
        return item;
    }

    private static boolean matchesGroupCreateCommand(
            GroupCreateCommand command,
            String protocolAccountId) {
        return command != null
                && protocolAccountId.equals(command.account().protocolAccountId())
                && "活动群-1".equals(command.subject())
                && List.of("8613900000000", "8613911111111").equals(command.participants())
                && command.announceOnly()
                && "group-creation-marketing-item:11".equals(command.operationId());
    }

    private GroupCreationMarketingTask task(Long marketingTaskId) {
        GroupCreationMarketingTask task = new GroupCreationMarketingTask();
        task.setId(22L);
        task.setTenantId(1L);
        task.setTaskName("建群营销");
        task.setAccountGroupId(8L);
        task.setAccountGroupName("A组");
        task.setMarketingTemplateId(18L);
        task.setMarketingTemplateName("模板");
        task.setMarketingTaskId(marketingTaskId);
        task.setMatchedItemCount(1);
        task.setSendIntervalSeconds(45);
        return task;
    }

    private GroupCreationMarketingAccountCandidate account(int accountState, int loginState) {
        return account(7L, "8613000000000", "acc_7", accountState, loginState);
    }

    private GroupCreationMarketingAccountCandidate account(Long accountId,
                                                           String accountPhone,
                                                           String protocolAccountId,
                                                           int accountState,
                                                           int loginState) {
        GroupCreationMarketingAccountCandidate account = new GroupCreationMarketingAccountCandidate();
        account.setAccountId(accountId);
        account.setAccountPhone(accountPhone);
        account.setProtocolAccountId(protocolAccountId);
        account.setProtocolId("WEB");
        account.setAccountState(accountState);
        account.setLoginState(loginState);
        account.setRiskStatus(1);
        return account;
    }

    private MarketingTemplate template() {
        MarketingTemplate template = new MarketingTemplate();
        template.setId(18L);
        template.setTemplateName("模板");
        template.setLinkMode(1);
        template.setContent("hello");
        return template;
    }
}
