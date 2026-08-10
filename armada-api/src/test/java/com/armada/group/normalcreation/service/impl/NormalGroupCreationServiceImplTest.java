package com.armada.group.normalcreation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemIdentity;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberReplacement;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.group.normalcreation.model.dto.NormalGroupCreationCreateDTO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationContactFailureVO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskVO;
import com.armada.group.normalcreation.support.NormalGroupCreationAdmissionGuard;
import com.armada.group.service.GroupFolderService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class NormalGroupCreationServiceImplTest {

    private final AccountGroupService accountGroupService = mock(AccountGroupService.class);
    private final AccountProtocolLookupService accountLookupService =
            mock(AccountProtocolLookupService.class);
    private final GroupFolderService groupFolderService = mock(GroupFolderService.class);
    private final NormalGroupCreationMapper mapper = mock(NormalGroupCreationMapper.class);
    private final NormalGroupCreationCommandDispatcher commandDispatcher =
            mock(NormalGroupCreationCommandDispatcher.class);
    private final NormalGroupCreationAdmissionGuard admissionGuard =
            mock(NormalGroupCreationAdmissionGuard.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void detailExposesRetainedContactFailuresAlongsideItems() {
        NormalGroupCreationContactFailureVO failure = new NormalGroupCreationContactFailureVO(
                21L, 1, 383L, "ANDROID",
                "FAILED", "ACCOUNT_NOT_ONLINE", "建群账号当前不在线，请重新上线后重试",
                "SUCCESS", null, null);
        when(mapper.selectTask(9L)).thenReturn(new NormalGroupCreationTaskVO(
                9L, "PARTIAL", 1, 1, 0, 100L, 200L));
        when(mapper.selectItems(9L)).thenReturn(List.of());
        when(mapper.selectContactFailures(9L)).thenReturn(List.of(failure));

        assertThat(service().detail(9L).contactFailures()).containsExactly(failure);
    }

    @Test
    void createRejectsWhenDatabaseHasNoOnlineMember() {
        TenantContext.set(1L);
        ProtocolAccountRef creator = account(100L, ProtocolBackend.WEB);
        when(mapper.selectTaskIdByIdempotencyKey(1L, "create-db-online-empty"))
                .thenReturn(null);
        when(accountLookupService.findOnlineNormalStrictByGroupId(10L))
                .thenReturn(List.of(creator));
        when(accountLookupService.findOnlineNormalStrictByGroupId(20L))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service().create(
                "create-db-online-empty", createRequest(), 9L))
                .hasMessageContaining("成员分组当前可执行在线账号不足");
    }

    @Test
    void createUsesDatabaseOnlineAccountFromMemberGroup() {
        TenantContext.set(1L);
        ProtocolAccountRef creator = account(100L, ProtocolBackend.WEB);
        ProtocolAccountRef onlineMember = account(201L, ProtocolBackend.ANDROID);
        MemberWork onlineMemberWork = new MemberWork(
                31L, 201L, "acc_201", "ANDROID", "91201",
                "PENDING", "PENDING", null, null, "PENDING");
        NormalGroupCreationTaskVO task =
                new NormalGroupCreationTaskVO(9L, "PENDING", 1, 0, 0, 100L, 100L);
        when(mapper.selectTaskIdByIdempotencyKey(1L, "create-select-online"))
                .thenReturn(null, 9L);
        when(accountLookupService.findOnlineNormalStrictByGroupId(10L))
                .thenReturn(List.of(creator));
        when(accountLookupService.findOnlineNormalStrictByGroupId(20L))
                .thenReturn(List.of(onlineMember));
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.selectItemIdentities(9L)).thenReturn(List.of(new ItemIdentity(21L, 1)));
        when(mapper.selectItemWork(21L)).thenReturn(item());
        when(mapper.selectMemberWorks(21L)).thenReturn(List.of(onlineMemberWork));
        when(mapper.selectTask(9L)).thenReturn(task);

        assertThat(service().create("create-select-online", createRequest(), 9L))
                .isEqualTo(task);

        verify(mapper).insertMembers(argThat(rows -> rows.size() == 1
                && rows.get(0).memberAccountId().equals(201L)));
        verify(commandDispatcher).enqueueContactPrepare(item(), List.of(onlineMemberWork));
    }

    @Test
    void createKeepsEnoughDatabaseOnlineMembersForMultiGroupRotation() {
        TenantContext.set(1L);
        List<ProtocolAccountRef> creators = List.of(
                account(100L, ProtocolBackend.WEB),
                account(101L, ProtocolBackend.WEB),
                account(102L, ProtocolBackend.WEB));
        List<ProtocolAccountRef> onlineMembers = List.of(
                account(200L, ProtocolBackend.ANDROID),
                account(201L, ProtocolBackend.ANDROID),
                account(202L, ProtocolBackend.ANDROID));
        NormalGroupCreationTaskVO task =
                new NormalGroupCreationTaskVO(9L, "PENDING", 3, 0, 0, 100L, 100L);
        when(mapper.selectTaskIdByIdempotencyKey(1L, "create-rotation"))
                .thenReturn(null, 9L);
        when(accountLookupService.findOnlineNormalStrictByGroupId(10L))
                .thenReturn(creators);
        when(accountLookupService.findOnlineNormalStrictByGroupId(20L))
                .thenReturn(onlineMembers);
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.selectItemIdentities(9L)).thenReturn(List.of(
                new ItemIdentity(21L, 1),
                new ItemIdentity(22L, 2),
                new ItemIdentity(23L, 3)));
        when(mapper.selectItemWork(any())).thenReturn(item());
        when(mapper.selectMemberWorks(21L)).thenReturn(List.of());
        when(mapper.selectTask(9L)).thenReturn(task);

        assertThat(service().create("create-rotation", createRequest(3), 9L))
                .isEqualTo(task);

        verify(mapper).insertMembers(argThat(rows -> rows.size() == 3
                && rows.stream().map(row -> row.memberAccountId()).distinct().count() == 3));
    }

    @Test
    void retryReplacesMemberThatIsNoLongerDatabaseOnlineInOriginalGroup() {
        TenantContext.set(1L);
        ItemWork item = item();
        MemberWork unavailable = new MemberWork(
                31L, 200L, "acc_200", "ANDROID", "91200",
                "SUCCESS", "FAILED", "cmd-creator-success", "cmd-member-failed",
                "PENDING");
        MemberWork replacementWork = new MemberWork(
                31L, 201L, "acc_201", "ANDROID", "91201",
                "PENDING", "PENDING", null, null, "PENDING");
        ProtocolAccountRef replacement = account(201L, ProtocolBackend.ANDROID);
        when(mapper.selectItemWork(21L)).thenReturn(item);
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectMemberAccountGroupId(9L)).thenReturn(20L);
        when(mapper.selectMemberWorks(21L))
                .thenReturn(
                        List.of(unavailable),
                        List.of(replacementWork));
        when(accountLookupService.findOnlineNormalStrictByGroupId(20L))
                .thenReturn(List.of(replacement));
        when(mapper.replaceMember(any(MemberReplacement.class))).thenReturn(1);

        service().retry(9L, 21L, 7L);

        ArgumentCaptor<MemberReplacement> replacementCaptor =
                ArgumentCaptor.forClass(MemberReplacement.class);
        verify(mapper).replaceMember(replacementCaptor.capture());
        assertThat(replacementCaptor.getValue()).satisfies(row -> {
            assertThat(row.memberId()).isEqualTo(31L);
            assertThat(row.itemId()).isEqualTo(21L);
            assertThat(row.memberAccountId()).isEqualTo(201L);
            assertThat(row.memberProtocolAccountId()).isEqualTo("acc_201");
        });
        verify(commandDispatcher).enqueueFailedContactPrepare(
                item, List.of(replacementWork));
        verify(mapper).refreshTaskSummary(9L, replacementCaptor.getValue().now());
        verify(accountLookupService, never()).findActiveProtocolRef(any());
    }

    @Test
    void retryReusesFrozenMemberAfterItIsDatabaseOnlineAgain() {
        TenantContext.set(1L);
        ItemWork item = item();
        MemberWork recoveredMember = new MemberWork(
                31L, 200L, "acc_200", "ANDROID", "91200",
                "SUCCESS", "FAILED", "cmd-creator-success", "cmd-member-failed",
                "PENDING");
        ProtocolAccountRef recoveredRef = account(200L, ProtocolBackend.ANDROID);
        when(mapper.selectItemWork(21L)).thenReturn(item);
        when(mapper.selectItemWorkForUpdate(1L, 21L)).thenReturn(item);
        when(mapper.selectMemberAccountGroupId(9L)).thenReturn(20L);
        when(mapper.selectMemberWorks(21L)).thenReturn(List.of(recoveredMember));
        when(accountLookupService.findOnlineNormalStrictByGroupId(20L))
                .thenReturn(List.of(recoveredRef));

        service().retry(9L, 21L, 7L);

        verify(mapper, never()).replaceMember(any(MemberReplacement.class));
        verify(commandDispatcher).enqueueFailedContactPrepare(
                item, List.of(recoveredMember));
    }

    private NormalGroupCreationServiceImpl service() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        return new NormalGroupCreationServiceImpl(
                accountGroupService,
                accountLookupService,
                groupFolderService,
                mapper,
                commandDispatcher,
                admissionGuard,
                transactionManager);
    }

    private static NormalGroupCreationCreateDTO createRequest() {
        return createRequest(1);
    }

    private static NormalGroupCreationCreateDTO createRequest(int groupCount) {
        return new NormalGroupCreationCreateDTO(
                10L, "KEEP", "CONTROLLED_GROUP", 20L, 1,
                null, "测试普群", groupCount, 1, "NORMAL", null, null, null);
    }

    private static ItemWork item() {
        return new ItemWork(
                21L, 1L, 9L, "测试普群", "测试普群",
                100L, "acc_100", "WEB", "91100",
                null, "FAILED", "PREPARING_CONTACTS", "NONE",
                null, null, null, "KEEP", null, null, null,
                true, false, true, false, 0);
    }

    private static ProtocolAccountRef account(Long accountId, ProtocolBackend backend) {
        return new ProtocolAccountRef(
                accountId, backend, "acc_" + accountId, "91" + accountId);
    }
}
