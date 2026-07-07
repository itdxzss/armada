package com.armada.marketing.service;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.marketing.mapper.GroupCreationMarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.enums.GroupCreationMarketingItemStatus;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.marketing.service.impl.GroupCreationMarketingRetryService;
import com.armada.marketing.service.impl.GroupCreationMarketingWorker;
import com.armada.platform.protocol.model.command.ProtocolMarketingMessageCommandRequest;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
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
    private ProtocolCommandOutboxService outboxService;
    @Mock
    private ContactPort contactPort;
    @Mock
    private GroupCreatePort groupCreatePort;
    @Mock
    private GroupCreationMarketingRetryService retryService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private GroupCreationMarketingWorker worker;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        worker = new GroupCreationMarketingWorker(
                groupCreationMapper,
                templateMapper,
                fileMapper,
                messageComposer,
                outboxService,
                contactPort,
                groupCreatePort,
                retryService,
                new ObjectMapper(),
                transactionManager);
    }

    @Test
    void processOnlineItemCreatesGroupAndEnqueuesOneShotGroupCreationMessage() {
        seedSuccessfulOnlineItem();

        worker.processDueItems(10);

        verify(groupCreatePort).create("acc_7", "活动群-1", List.of("8613900000000", "8613911111111"), true);
        ArgumentCaptor<List<ProtocolMarketingMessageCommandRequest>> commands = ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueMarketingMessageCommands(commands.capture());
        assertThat(commands.getValue()).singleElement().satisfies(command -> {
            assertThat(command.marketingTaskId()).isNull();
            assertThat(command.attemptId()).isNull();
            assertThat(command.targetId()).isNull();
            assertThat(command.roundNo()).isNull();
            assertThat(command.groupCreationTaskId()).isEqualTo(22L);
            assertThat(command.groupCreationItemId()).isEqualTo(11L);
            assertThat(command.groupJid()).isEqualTo("120363created@g.us");
            assertThat(command.text()).isEqualTo("hello");
            assertThat(command.source()).isEqualTo("group_creation_marketing");
        });
        verify(groupCreationMapper).markItemMarketingSending(eq(11L), eq("120363created@g.us"), isNull(),
                isNull(), isNull(), isNull(), any(), any(), anyLong());
    }

    @Test
    void processOnlineItemDoesNotLinkTaskToContinuousMarketingTask() {
        seedSuccessfulOnlineItem();

        worker.processDueItems(10);

        verify(groupCreationMapper, never()).updateTaskMarketingTaskIdIfAbsent(anyLong(), anyLong(), anyLong());
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
        when(groupCreationMapper.markItemMarketingSending(anyLong(), anyString(), isNull(), isNull(), isNull(),
                isNull(), any(), any(), anyLong())).thenReturn(1);

        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        doAnswer(invocation -> {
            int running = active.incrementAndGet();
            maxActive.updateAndGet(previous -> Math.max(previous, running));
            bothStarted.countDown();
            bothStarted.await(500, TimeUnit.MILLISECONDS);
            active.decrementAndGet();
            String protocolAccountId = invocation.getArgument(0);
            return new GroupCreateResult("120363" + protocolAccountId + "@g.us", false, List.of());
        }).when(groupCreatePort).create(any(), eq("活动群-1"), anyList(), eq(true));

        worker.processDueItems(10);

        assertThat(maxActive.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void processOnlineItemSubmitsMaterialContactPreSaves() {
        seedSuccessfulOnlineItem();

        worker.processDueItems(10);

        verify(contactPort, timeout(500)).saveContact("acc_7", "8613900000000", "8613900000000");
        verify(contactPort, timeout(500)).saveContact("acc_7", "8613911111111", "8613911111111");
        verify(groupCreatePort).create("acc_7", "活动群-1",
                List.of("8613900000000", "8613911111111"), true);
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
        when(groupCreationMapper.markItemMarketingSending(eq(11L), eq("120363created@g.us"), isNull(),
                isNull(), isNull(), isNull(), any(), any(), anyLong())).thenReturn(1);
        CountDownLatch contactSaveStarted = new CountDownLatch(1);
        CountDownLatch releaseContactSave = new CountDownLatch(1);
        CountDownLatch groupCreateStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            contactSaveStarted.countDown();
            releaseContactSave.await(2, TimeUnit.SECONDS);
            return null;
        }).when(contactPort).saveContact(eq("acc_7"), anyString(), anyString());
        doAnswer(invocation -> {
            groupCreateStarted.countDown();
            return new GroupCreateResult("120363created@g.us", false, List.of());
        }).when(groupCreatePort).create(eq("acc_7"), eq("活动群-1"), anyList(), eq(true));
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
        ArgumentCaptor<String> protocolJson = ArgumentCaptor.forClass(String.class);

        worker.processDueItems(10);

        verify(groupCreatePort).create("acc_7", "活动群-1",
                List.of("8613900000000", "8613911111111"), true);
        verify(groupCreationMapper).markItemMarketingSending(
                eq(11L),
                eq("120363created@g.us"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                any(),
                protocolJson.capture(),
                anyLong());
        assertThat(protocolJson.getValue())
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
        verify(contactPort, never()).saveContact(any(), any(), any());
        verify(groupCreatePort, never()).create(any(), any(), anyList(), anyBoolean());
    }

    @Test
    void offlineAssignedAccountIsReplacedByAvailableGroupAccountBeforeGroupCreate() {
        GroupCreationMarketingItem item = item();
        GroupCreationMarketingTask task = task(null);
        GroupCreationMarketingAccountCandidate offlineAccount = account(
                7L, "8613000000000", "acc_7", AccountStateCode.NORMAL, AccountLoginStateCode.OFFLINE);
        GroupCreationMarketingAccountCandidate replacementAccount = account(
                9L, "8613999999999", "acc_9", AccountStateCode.NORMAL, AccountLoginStateCode.ONLINE);
        MarketingTemplate template = template();
        when(groupCreationMapper.selectDueItems(anyInt(), anyLong())).thenReturn(List.of(item));
        when(groupCreationMapper.claimItem(eq(11L), eq(GroupCreationMarketingItemStatus.PENDING.code()),
                eq(GroupCreationMarketingItemStatus.GROUP_CREATING.code()), anyLong())).thenReturn(1);
        when(groupCreationMapper.selectTaskById(22L)).thenReturn(task);
        when(groupCreationMapper.selectAccountCandidateByAccountId(7L)).thenReturn(offlineAccount);
        doAnswer(invocation -> {
            item.setAccountId(9L);
            item.setAccountPhone("8613999999999");
            item.setProtocolAccountId("acc_9");
            return replacementAccount;
        }).when(retryService).replaceClaimedItemAccountForRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_ACCOUNT_CHECK), eq("ACCOUNT_OFFLINE"), eq("账号离线"),
                anyLong());
        when(groupCreatePort.create(eq("acc_9"), eq("活动群-1"), anyList(), eq(true)))
                .thenReturn(new GroupCreateResult("120363created@g.us", false, List.of()));
        when(templateMapper.selectById(18L)).thenReturn(template);
        when(messageComposer.compose(eq(template), any())).thenReturn(new MarketingMessageComposer.ComposedMessage(
                "TEXT", "hello", null, null));
        when(groupCreationMapper.markItemMarketingSending(eq(11L), eq("120363created@g.us"), isNull(),
                isNull(), isNull(), isNull(), any(), any(), anyLong())).thenReturn(1);

        worker.processDueItems(10);

        verify(retryService).replaceClaimedItemAccountForRetry(eq(item), eq(task),
                eq(GroupCreationMarketingRetryService.STAGE_ACCOUNT_CHECK), eq("ACCOUNT_OFFLINE"), eq("账号离线"),
                anyLong());
        verify(groupCreatePort).create("acc_9", "活动群-1", List.of("8613900000000", "8613911111111"), true);
        ArgumentCaptor<List<ProtocolMarketingMessageCommandRequest>> commands = ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueueMarketingMessageCommands(commands.capture());
        assertThat(commands.getValue()).singleElement().satisfies(command -> {
            assertThat(command.accountId()).isEqualTo(9L);
            assertThat(command.protocolAccountId()).isEqualTo("acc_9");
            assertThat(command.groupCreationItemId()).isEqualTo(11L);
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
        verify(contactPort, never()).saveContact(any(), any(), any());
        verify(groupCreatePort, never()).create(any(), any(), anyList(), anyBoolean());
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
        when(groupCreatePort.create(eq("acc_7"), eq("活动群-1"), anyList(), eq(true)))
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
        when(groupCreatePort.create(eq("acc_7"), eq("活动群-1"), anyList(), eq(true)))
                .thenReturn(new GroupCreateResult("120363created@g.us", false, List.of()));
        when(templateMapper.selectById(18L)).thenReturn(template);
        when(messageComposer.compose(eq(template), any())).thenReturn(new MarketingMessageComposer.ComposedMessage(
                "TEXT", "hello", null, null));
        when(groupCreationMapper.markItemMarketingSending(eq(11L), eq("120363created@g.us"), isNull(),
                isNull(), isNull(), isNull(), any(), any(), anyLong())).thenReturn(1);
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
