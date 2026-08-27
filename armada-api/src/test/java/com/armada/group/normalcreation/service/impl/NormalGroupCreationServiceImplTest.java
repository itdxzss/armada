package com.armada.group.normalcreation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemIdentity;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberReplacement;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.SecondaryAdminInsert;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.SecondaryAdminWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.TaskExecutionScope;
import com.armada.group.normalcreation.model.dto.NormalGroupCreationCreateDTO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationContactFailureVO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskVO;
import com.armada.group.normalcreation.support.NormalGroupCreationAdmissionGuard;
import com.armada.group.service.GroupFolderService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class NormalGroupCreationServiceImplTest {

    private static final DataScope SELF_SCOPE = DataScope.self(9L);

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

    @BeforeEach
    void stubOwnedAccountGroups() {
        DataScopeContext.open(SELF_SCOPE);
        when(accountGroupService.requireExisting(10L)).thenReturn(accountGroup(10L, 9L));
        when(accountGroupService.requireExisting(20L)).thenReturn(accountGroup(20L, 9L));
        when(accountGroupService.requireExisting(30L)).thenReturn(accountGroup(30L, 9L));
        when(mapper.selectTaskExecutionScope(1L, 9L))
                .thenReturn(new TaskExecutionScope(9L, 9L));
    }

    @AfterEach
    void clearTenant() {
        DataScopeContext.clear();
        TenantContext.clear();
    }

    @Test
    void detailExposesRetainedContactFailuresAlongsideItems() {
        NormalGroupCreationContactFailureVO failure = new NormalGroupCreationContactFailureVO(
                21L, 1, 383L, "ANDROID",
                "FAILED", "ACCOUNT_NOT_ONLINE", "建群账号当前不在线，请重新上线后重试",
                "SUCCESS", null, null);
        when(mapper.selectTask(9L, SELF_SCOPE)).thenReturn(new NormalGroupCreationTaskVO(
                9L, "PARTIAL", 1, 1, 0, 100L, 200L));
        when(mapper.selectItems(9L, SELF_SCOPE)).thenReturn(List.of());
        when(mapper.selectContactFailures(9L, SELF_SCOPE)).thenReturn(List.of(failure));

        assertThat(service().detail(9L).contactFailures()).containsExactly(failure);
    }

    @Test
    void detailRejectsInvisibleTaskBeforeReadingChildren() {
        assertThatThrownBy(() -> service().detail(88L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("新建普群任务不存在");

        verify(mapper, never()).selectItems(
                org.mockito.ArgumentMatchers.eq(88L), any());
        verify(mapper, never()).selectContactFailures(
                org.mockito.ArgumentMatchers.eq(88L), any());
    }

    @Test
    void createRejectsWhenDatabaseHasNoOnlineMember() {
        TenantContext.set(1L);
        ProtocolAccountRef creator = account(100L, ProtocolBackend.WEB);
        when(mapper.selectTaskIdByIdempotencyKey(1L, 9L, "create-db-online-empty"))
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
    void createRejectsDifferentOwnerAccountGroupsBeforeLookingUpAccounts() {
        TenantContext.set(1L);
        when(mapper.selectTaskIdByIdempotencyKey(1L, 9L, "create-mixed-owner"))
                .thenReturn(null);
        AccountGroup foreignMemberGroup = new AccountGroup();
        foreignMemberGroup.setId(20L);
        foreignMemberGroup.setOwnerUserId(22L);
        when(accountGroupService.requireExisting(20L)).thenReturn(foreignMemberGroup);

        assertThatThrownBy(() -> service().create(
                "create-mixed-owner", createRequest(), 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("建群任务账号分组归属不一致");

        verify(accountLookupService, never()).findOnlineNormalStrictByGroupId(any());
        verify(mapper, never()).insertTask(any());
    }

    @Test
    void tenantAdminCannotCreateTaskFromAnotherOwnersResources() {
        TenantContext.set(1L);
        when(mapper.selectTaskIdByIdempotencyKey(1L, 9L, "admin-create-for-u2"))
                .thenReturn(null);
        when(accountGroupService.requireExisting(10L)).thenReturn(accountGroup(10L, 22L));
        when(accountGroupService.requireExisting(20L)).thenReturn(accountGroup(20L, 22L));
        when(accountGroupService.requireExisting(30L)).thenReturn(accountGroup(30L, 22L));

        try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.all(9L))) {
            assertThatThrownBy(() -> service().create(
                    "admin-create-for-u2", createRequest(), 9L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("只能使用当前操作者自己的资源");
        }

        verify(accountLookupService, never()).findOnlineNormalStrictByGroupId(any());
        verify(mapper, never()).insertTask(any());
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
        when(mapper.selectTaskIdByIdempotencyKey(1L, 9L, "create-select-online"))
                .thenReturn(null, 9L);
        when(accountLookupService.findOnlineNormalStrictByGroupId(10L))
                .thenReturn(List.of(creator));
        when(accountLookupService.findOnlineNormalStrictByGroupId(20L))
                .thenReturn(List.of(onlineMember));
        when(accountLookupService.findOnlineNormalStrictByGroupId(30L))
                .thenReturn(List.of(account(301L, ProtocolBackend.WEB)));
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.selectItemIdentities(9L)).thenReturn(List.of(new ItemIdentity(21L, 1)));
        when(mapper.selectItemWork(21L)).thenReturn(item());
        when(mapper.selectMemberWorks(21L)).thenReturn(List.of(onlineMemberWork));
        when(mapper.selectTask(9L, SELF_SCOPE)).thenReturn(task);

        assertThat(service().create("create-select-online", createRequest(), 9L))
                .isEqualTo(task);

        verify(mapper).insertTask(argThat(row -> row.ownerUserId().equals(9L)
                && row.createdBy() == 9L));
        verify(mapper).insertMembers(argThat(rows -> rows.size() == 1
                && rows.get(0).memberAccountId().equals(201L)));
        verify(commandDispatcher).enqueueContactPrepare(
                item(), List.of(onlineMemberWork), List.of());
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
        when(mapper.selectTaskIdByIdempotencyKey(1L, 9L, "create-rotation"))
                .thenReturn(null, 9L);
        when(accountLookupService.findOnlineNormalStrictByGroupId(10L))
                .thenReturn(creators);
        when(accountLookupService.findOnlineNormalStrictByGroupId(20L))
                .thenReturn(onlineMembers);
        when(accountLookupService.findOnlineNormalStrictByGroupId(30L))
                .thenReturn(List.of(account(301L, ProtocolBackend.WEB)));
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.selectItemIdentities(9L)).thenReturn(List.of(
                new ItemIdentity(21L, 1),
                new ItemIdentity(22L, 2),
                new ItemIdentity(23L, 3)));
        when(mapper.selectItemWork(any())).thenReturn(item());
        when(mapper.selectMemberWorks(21L)).thenReturn(List.of());
        when(mapper.selectTask(9L, SELF_SCOPE)).thenReturn(task);

        assertThat(service().create("create-rotation", createRequest(3), 9L))
                .isEqualTo(task);

        verify(mapper).insertMembers(argThat(rows -> rows.size() == 3
                && rows.stream().map(row -> row.memberAccountId()).distinct().count() == 3));
    }

    @Test
    void createRejectsWhenSecondaryAdminGroupHasFewerOnlineAccountsThanEachGroupNeeds() {
        TenantContext.set(1L);
        when(mapper.selectTaskIdByIdempotencyKey(1L, 9L, "create-secondary-shortage"))
                .thenReturn(null);
        when(accountLookupService.findOnlineNormalStrictByGroupId(10L))
                .thenReturn(List.of(account(100L, ProtocolBackend.WEB)));
        when(accountLookupService.findOnlineNormalStrictByGroupId(20L))
                .thenReturn(List.of(account(200L, ProtocolBackend.ANDROID)));
        when(accountLookupService.findOnlineNormalStrictByGroupId(30L))
                .thenReturn(List.of(account(300L, ProtocolBackend.WEB)));

        assertThatThrownBy(() -> service().create(
                "create-secondary-shortage", createRequest(1, 2), 9L))
                .hasMessageContaining("次管理员分组当前状态正常且在线的账号不足")
                .hasMessageContaining("每群需要 2 个")
                .hasMessageContaining("实际 1 个");
    }

    @Test
    void createReusesSecondaryAdminsAcrossGroupsButNeverDuplicatesWithinOneGroup() {
        TenantContext.set(1L);
        List<ProtocolAccountRef> creators = List.of(
                account(100L, ProtocolBackend.WEB),
                account(101L, ProtocolBackend.WEB));
        List<ProtocolAccountRef> members = List.of(
                account(200L, ProtocolBackend.ANDROID),
                account(201L, ProtocolBackend.ANDROID));
        List<ProtocolAccountRef> secondaryAdmins = List.of(
                account(300L, ProtocolBackend.WEB),
                account(301L, ProtocolBackend.ANDROID));
        NormalGroupCreationTaskVO task =
                new NormalGroupCreationTaskVO(9L, "PENDING", 2, 0, 0, 100L, 100L);
        when(mapper.selectTaskIdByIdempotencyKey(1L, 9L, "create-secondary-reuse"))
                .thenReturn(null, 9L);
        when(accountLookupService.findOnlineNormalStrictByGroupId(10L)).thenReturn(creators);
        when(accountLookupService.findOnlineNormalStrictByGroupId(20L)).thenReturn(members);
        when(accountLookupService.findOnlineNormalStrictByGroupId(30L)).thenReturn(secondaryAdmins);
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.selectItemIdentities(9L)).thenReturn(List.of(
                new ItemIdentity(21L, 1), new ItemIdentity(22L, 2)));
        when(mapper.selectItemWork(any())).thenReturn(item());
        when(mapper.selectMemberWorks(any())).thenReturn(List.of());
        when(mapper.selectSecondaryAdminWorks(any())).thenReturn(List.of());
        when(mapper.selectTask(9L, SELF_SCOPE)).thenReturn(task);

        assertThat(service().create("create-secondary-reuse", createRequest(2, 2), 9L))
                .isEqualTo(task);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SecondaryAdminInsert>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertSecondaryAdmins(captor.capture());
        assertThat(captor.getValue()).hasSize(4);
        assertThat(captor.getValue().stream()
                .collect(java.util.stream.Collectors.groupingBy(SecondaryAdminInsert::itemId))
                .values())
                .allSatisfy(rows -> assertThat(rows)
                        .extracting(SecondaryAdminInsert::secondaryAdminAccountId)
                        .doesNotHaveDuplicates()
                        .hasSize(2));
        assertThat(captor.getValue())
                .extracting(SecondaryAdminInsert::secondaryAdminAccountId)
                .containsOnly(300L, 301L);
    }

    @Test
    void createAnchorsEverySecondaryAdminToAnActualMemberOfTheSameGroup() {
        TenantContext.set(1L);
        ProtocolAccountRef creator = account(100L, ProtocolBackend.WEB);
        ProtocolAccountRef member = account(200L, ProtocolBackend.ANDROID);
        ProtocolAccountRef secondaryAdmin = account(300L, ProtocolBackend.WEB);
        NormalGroupCreationTaskVO task =
                new NormalGroupCreationTaskVO(9L, "PENDING", 1, 0, 0, 100L, 100L);
        when(mapper.selectTaskIdByIdempotencyKey(1L, 9L, "create-secondary-anchor"))
                .thenReturn(null, 9L);
        when(accountLookupService.findOnlineNormalStrictByGroupId(10L)).thenReturn(List.of(creator));
        when(accountLookupService.findOnlineNormalStrictByGroupId(20L)).thenReturn(List.of(member));
        when(accountLookupService.findOnlineNormalStrictByGroupId(30L))
                .thenReturn(List.of(secondaryAdmin));
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.selectItemIdentities(9L)).thenReturn(List.of(new ItemIdentity(21L, 1)));
        when(mapper.selectItemWork(21L)).thenReturn(item());
        when(mapper.selectMemberWorks(21L)).thenReturn(List.of());
        when(mapper.selectSecondaryAdminWorks(21L)).thenReturn(List.of());
        when(mapper.selectTask(9L, SELF_SCOPE)).thenReturn(task);

        service().create("create-secondary-anchor", createRequest(1, 1), 9L);

        verify(mapper).insertSecondaryAdmins(argThat(rows -> rows.size() == 1
                && rows.get(0).secondaryAdminAccountId().equals(300L)
                && rows.get(0).anchorMemberAccountId().equals(200L)
                && rows.get(0).itemId().equals(21L)));
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

        when(mapper.selectTask(9L, SELF_SCOPE)).thenReturn(
                new NormalGroupCreationTaskVO(9L, "FAILED", 1, 0, 1, 100L, 100L));

        service().retry(9L, 21L, 9L);

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

        when(mapper.selectTask(9L, SELF_SCOPE)).thenReturn(
                new NormalGroupCreationTaskVO(9L, "FAILED", 1, 0, 1, 100L, 100L));

        service().retry(9L, 21L, 9L);

        verify(mapper, never()).replaceMember(any(MemberReplacement.class));
        verify(commandDispatcher).enqueueFailedContactPrepare(
                item, List.of(recoveredMember));
    }

    @Test
    void retryRejectsInvisibleTaskBeforeLockingItem() {
        TenantContext.set(1L);

        assertThatThrownBy(() -> service().retry(88L, 21L, 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("新建普群任务不存在");

        verify(mapper, never()).selectItemWorkForUpdate(any(), any());
    }

    @Test
    void administratorCannotRetryHistoricalUnownedTask() {
        TenantContext.set(1L);
        DataScope adminScope = DataScope.all(99L);
        when(mapper.selectTask(9L, adminScope)).thenReturn(
                new NormalGroupCreationTaskVO(9L, "FAILED", 1, 0, 1, 100L, 100L));
        when(mapper.selectTaskExecutionScope(1L, 9L))
                .thenReturn(new TaskExecutionScope(null, 9L));

        try (DataScopeContext.Scope ignored = DataScopeContext.open(adminScope)) {
            assertThatThrownBy(() -> service().retry(9L, 21L, 99L))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED.code()));
        }

        verify(mapper, never()).selectItemWorkForUpdate(any(), any());
        verifyNoInteractions(commandDispatcher);
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
        return createRequest(groupCount, 1);
    }

    private static NormalGroupCreationCreateDTO createRequest(
            int groupCount, int secondaryAdminCount) {
        return new NormalGroupCreationCreateDTO(
                10L, 30L, secondaryAdminCount,
                "KEEP", "CONTROLLED_GROUP", 20L, 1,
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

    private static AccountGroup accountGroup(Long id, Long ownerUserId) {
        AccountGroup group = new AccountGroup();
        group.setId(id);
        group.setOwnerUserId(ownerUserId);
        return group;
    }
}
