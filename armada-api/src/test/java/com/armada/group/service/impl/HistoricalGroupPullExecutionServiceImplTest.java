package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.mapper.HistoricalGroupPullExecutionMapper;
import com.armada.group.mapper.HistoricalGroupPullMemberMapper;
import com.armada.group.model.dto.HistoricalGroupPullCreateDTO;
import com.armada.group.model.entity.HistoricalGroupPullExecution;
import com.armada.group.model.entity.HistoricalGroupPullMember;
import com.armada.group.model.enums.HistoricalGroupAddStatus;
import com.armada.group.model.enums.HistoricalGroupContactStatus;
import com.armada.group.model.enums.HistoricalGroupMaterialType;
import com.armada.group.model.enums.HistoricalGroupMarketingStatus;
import com.armada.group.model.enums.HistoricalGroupMemberSendStatus;
import com.armada.group.model.enums.HistoricalGroupPullStatus;
import com.armada.group.model.vo.HistoricalGroupDetailVO;
import com.armada.group.model.vo.HistoricalGroupPullExecutionVO;
import com.armada.group.service.HistoricalGroupMaterialParser;
import com.armada.group.service.HistoricalGroupPullCreateValidator;
import com.armada.group.service.HistoricalGroupPullDispatchTrigger;
import com.armada.group.service.HistoricalGroupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;

/** 历史群拉人待执行创建服务测试。 */
@ExtendWith(MockitoExtension.class)
class HistoricalGroupPullExecutionServiceImplTest {

    private static final long TENANT_ID = 71L;
    private static final long OWNER_USER_ID = 11L;

    @Mock
    private HistoricalGroupMaterialParser parser;
    @Mock
    private AccountProtocolLookupService accountLookupService;
    @Mock
    private AccountGroupService accountGroupService;
    @Mock
    private HistoricalGroupService historicalGroupService;
    @Mock
    private HistoricalGroupPullExecutionMapper executionMapper;
    @Mock
    private HistoricalGroupPullMemberMapper memberMapper;
    @Mock
    private HistoricalGroupPullDispatchTrigger dispatchTrigger;

    private HistoricalGroupPullExecutionServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
        DataScopeContext.open(DataScope.self(OWNER_USER_ID));
        service = new HistoricalGroupPullExecutionServiceImpl(
                parser,
                new HistoricalGroupPullCreateValidator(
                        accountGroupService, historicalGroupService),
                accountLookupService,
                executionMapper,
                memberMapper,
                dispatchTrigger);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        DataScopeContext.clear();
    }

    @Test
    void rejectsNonPositiveSingleAddCountBeforeReadingFile() {
        HistoricalGroupPullCreateDTO request = request(0, "invalid-count");

        assertThatThrownBy(() -> service.create(request, file()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("单次添加人数");
        verify(parser, never()).parse(any());
    }

    @Test
    void requiresFreshNonEmptyInviteButDoesNotRequireOperationAccountToBeAdmin() {
        HistoricalGroupPullCreateDTO request = request(10, "fresh-link");
        prepareOwnership(request);
        when(historicalGroupService.getHistoricalGroupDetail(request.sourceAccountGroupId(), request.groupJid()))
                .thenReturn(detail("fresh-invite", true, false));
        when(parser.parse(any())).thenReturn(parseResult());
        when(accountLookupService.findActiveProtocolRefsByPhones(List.of("8613900000002")))
                .thenReturn(Map.of());
        when(executionMapper.insert(any())).thenAnswer(invocation -> {
            invocation.<HistoricalGroupPullExecution>getArgument(0).setId(901L);
            return 1;
        });
        when(memberMapper.selectOrderedByExecutionId(901L)).thenReturn(List.of());

        HistoricalGroupPullExecutionVO result = service.create(request, file());

        assertThat(result.id()).isEqualTo(901L);
        ArgumentCaptor<HistoricalGroupPullExecution> executionCaptor =
                ArgumentCaptor.forClass(HistoricalGroupPullExecution.class);
        verify(executionMapper).insert(executionCaptor.capture());
        assertThat(executionCaptor.getValue().getInviteLink()).isEqualTo("fresh-invite");
        assertThat(executionCaptor.getValue().getGroupSubjectSnapshot()).isEqualTo("fresh-subject");
        assertThat(executionCaptor.getValue().getSourceAccountGroupId()).isEqualTo(201L);
        assertThat(executionCaptor.getValue().getOperationAccountId()).isEqualTo(101L);
        assertThat(executionCaptor.getValue().getOwnerUserId()).isEqualTo(OWNER_USER_ID);
        assertThat(executionCaptor.getValue().getCreatedBy()).isEqualTo(OWNER_USER_ID);
    }

    @Test
    void rejectsDifferentOwnerSourceAndPullerGroupsBeforeReadingHistoricalGroup() {
        HistoricalGroupPullCreateDTO request = request(10, "mixed-owner");
        AccountGroup source = new AccountGroup();
        source.setOwnerUserId(11L);
        AccountGroup puller = new AccountGroup();
        puller.setOwnerUserId(22L);
        when(accountGroupService.requireExisting(request.sourceAccountGroupId())).thenReturn(source);
        when(accountGroupService.requireExisting(request.pullerAccountGroupId())).thenReturn(puller);

        assertThatThrownBy(() -> service.create(request, file()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("历史群拉人任务账号分组归属不一致");

        verify(historicalGroupService, never()).getHistoricalGroupDetail(any(), any());
    }

    @Test
    void administratorCannotCreateAnExecutionFromAnotherUsersGroups() {
        DataScopeContext.clear();
        DataScopeContext.open(DataScope.all(99L));
        HistoricalGroupPullCreateDTO request = request(10, "admin-other-owner");
        prepareOwnership(request);

        assertThatThrownBy(() -> service.create(request, file()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能使用当前操作者自己的资源");

        verify(parser, never()).parse(any());
    }

    @Test
    void rejectsUnavailableOrBlankServerInviteLink() {
        HistoricalGroupPullCreateDTO request = request(10, "missing-link");
        prepareOwnership(request);
        when(historicalGroupService.getHistoricalGroupDetail(request.sourceAccountGroupId(), request.groupJid()))
                .thenReturn(detail(" ", true, true));

        assertThatThrownBy(() -> service.create(request, file()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("邀请链接");
        verify(parser, never()).parse(any());
    }

    @Test
    void rejectsInviteWhenServerMarksLinkUnavailableEvenIfTextIsPresent() {
        HistoricalGroupPullCreateDTO request = request(10, "unavailable-link");
        prepareOwnership(request);
        when(historicalGroupService.getHistoricalGroupDetail(request.sourceAccountGroupId(), request.groupJid()))
                .thenReturn(detail("stale-looking-link", false, false));

        assertThatThrownBy(() -> service.create(request, file()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("邀请链接");
        verify(parser, never()).parse(any());
    }

    @Test
    void persistsMarketingFirstAndMatchesMarketingAccountsInOneBatch() {
        HistoricalGroupPullCreateDTO request = request(10, "create-members");
        prepareOwnership(request);
        when(historicalGroupService.getHistoricalGroupDetail(request.sourceAccountGroupId(), request.groupJid()))
                .thenReturn(detail("fresh-invite", true, true));
        when(parser.parse(any())).thenReturn(parseResult());
        ProtocolAccountRef marketingAccount =
                new ProtocolAccountRef(801L, ProtocolBackend.WEB, "protocol-801", "8613900000002");
        when(accountLookupService.findActiveProtocolRefsByPhones(List.of("8613900000002")))
                .thenReturn(Map.of("8613900000002", marketingAccount));
        when(executionMapper.insert(any())).thenAnswer(invocation -> {
            invocation.<HistoricalGroupPullExecution>getArgument(0).setId(902L);
            return 1;
        });
        when(memberMapper.selectOrderedByExecutionId(902L)).thenReturn(List.of());

        service.create(request, file());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HistoricalGroupPullMember>> membersCaptor = ArgumentCaptor.forClass(List.class);
        verify(memberMapper).batchInsert(membersCaptor.capture());
        assertThat(membersCaptor.getValue()).extracting(HistoricalGroupPullMember::getPhone)
                .containsExactly("8613900000002", "8613800000001");
        HistoricalGroupPullMember marketing = membersCaptor.getValue().get(0);
        assertThat(marketing.getLineNo()).isEqualTo(2);
        assertThat(marketing.getMaterialType()).isEqualTo(HistoricalGroupMaterialType.MARKETING.code());
        assertThat(marketing.getAccountId()).isEqualTo(801L);
        assertThat(marketing.getProtocolAccountIdSnapshot()).isEqualTo("protocol-801");
        assertThat(marketing.getContactStatus()).isEqualTo(HistoricalGroupContactStatus.PENDING.code());
        assertThat(marketing.getAddStatus()).isEqualTo(HistoricalGroupAddStatus.PENDING.code());
        assertThat(marketing.getSendStatus()).isEqualTo(HistoricalGroupMemberSendStatus.PENDING.code());
        assertThat(membersCaptor.getValue().get(1).getSendStatus())
                .isEqualTo(HistoricalGroupMemberSendStatus.NOT_APPLICABLE.code());
        verify(accountLookupService).findActiveProtocolRefsByPhones(List.of("8613900000002"));
    }

    @Test
    void repeatedIdempotencyKeyReturnsExistingWithoutParsingOrInsertingMembers() {
        HistoricalGroupPullCreateDTO request = request(10, "existing-key");
        HistoricalGroupPullExecution existing = execution(903L, request);
        when(executionMapper.selectByTenantOwnerAndIdempotencyKey(
                TENANT_ID, OWNER_USER_ID, request.idempotencyKey()))
                .thenReturn(existing);
        when(memberMapper.selectOrderedByExecutionId(903L)).thenReturn(List.of());

        HistoricalGroupPullExecutionVO result = service.create(request, file());

        assertThat(result.id()).isEqualTo(903L);
        verify(parser, never()).parse(any());
        verify(executionMapper, never()).insert(any());
        verify(memberMapper, never()).batchInsert(any());
        verify(historicalGroupService, never()).getHistoricalGroupDetail(any(), any());
    }

    @Test
    void concurrentDuplicateKeyReturnsWinnerWithoutInsertingMembers() {
        HistoricalGroupPullCreateDTO request = request(10, "concurrent-key");
        HistoricalGroupPullExecution winner = execution(904L, request);
        prepareOwnership(request);
        when(historicalGroupService.getHistoricalGroupDetail(request.sourceAccountGroupId(), request.groupJid()))
                .thenReturn(detail("fresh-invite", true, false));
        when(parser.parse(any())).thenReturn(parseResult());
        when(accountLookupService.findActiveProtocolRefsByPhones(List.of("8613900000002")))
                .thenReturn(Map.of());
        when(executionMapper.selectByTenantOwnerAndIdempotencyKey(
                TENANT_ID, OWNER_USER_ID, request.idempotencyKey()))
                .thenReturn(null);
        doThrow(new DuplicateKeyException("idempotency race")).when(executionMapper).insert(any());
        when(executionMapper.selectByTenantOwnerAndIdempotencyKeyForUpdate(
                TENANT_ID, OWNER_USER_ID, request.idempotencyKey()))
                .thenReturn(winner);
        when(memberMapper.selectOrderedByExecutionId(904L)).thenReturn(List.of());

        HistoricalGroupPullExecutionVO result = service.create(request, file());

        assertThat(result.id()).isEqualTo(904L);
        verify(memberMapper, never()).batchInsert(any());
    }

    @Test
    void startRevalidatesFreshLinkThenClaimsPendingAndDispatchesAfterCommit() {
        HistoricalGroupPullCreateDTO request = request(10, "start-pending");
        HistoricalGroupPullExecution pending = execution(905L, request);
        pending.setInviteLink("persisted-create-link");
        HistoricalGroupPullExecution running = execution(905L, request);
        running.setInviteLink("persisted-create-link");
        running.setPullStatus(HistoricalGroupPullStatus.RUNNING.code());
        prepareOwnership(request);
        when(executionMapper.selectByTenantAndIdForScope(
                org.mockito.ArgumentMatchers.eq(TENANT_ID),
                org.mockito.ArgumentMatchers.eq(905L),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(pending, running);
        when(historicalGroupService.getHistoricalGroupDetail(request.sourceAccountGroupId(), request.groupJid()))
                .thenReturn(detail("fresh-start-link", true, false));
        when(executionMapper.claimStatus(
                org.mockito.ArgumentMatchers.eq(905L),
                org.mockito.ArgumentMatchers.eq(HistoricalGroupPullStatus.PENDING.code()),
                org.mockito.ArgumentMatchers.eq(HistoricalGroupPullStatus.RUNNING.code()),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(1);
        when(memberMapper.selectOrderedByExecutionId(905L)).thenReturn(List.of());

        HistoricalGroupPullExecutionVO result = service.start(905L);

        assertThat(result.pullStatus()).isEqualTo(HistoricalGroupPullStatus.RUNNING);
        assertThat(result.inviteUrl()).isEqualTo("persisted-create-link");
        verify(historicalGroupService).getHistoricalGroupDetail(
                request.sourceAccountGroupId(), request.groupJid());
        verify(dispatchTrigger).dispatchAfterCommit(TENANT_ID, 905L);
    }

    @Test
    void repeatedStartConflictsBeforeFreshProtocolValidationOrDispatch() {
        HistoricalGroupPullCreateDTO request = request(10, "start-running");
        HistoricalGroupPullExecution running = execution(906L, request);
        running.setPullStatus(HistoricalGroupPullStatus.RUNNING.code());
        when(executionMapper.selectByTenantAndIdForScope(
                org.mockito.ArgumentMatchers.eq(TENANT_ID),
                org.mockito.ArgumentMatchers.eq(906L),
                org.mockito.ArgumentMatchers.any())).thenReturn(running);

        assertThatThrownBy(() -> service.start(906L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("待执行");

        verify(historicalGroupService, never()).getHistoricalGroupDetail(any(), any());
        verify(executionMapper, never()).claimStatus(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(dispatchTrigger, never()).dispatchAfterCommit(any(), any());
    }

    @Test
    void startClaimRaceConflictsWithoutDispatchingWorker() {
        HistoricalGroupPullCreateDTO request = request(10, "start-race");
        HistoricalGroupPullExecution pending = execution(907L, request);
        prepareOwnership(request);
        when(executionMapper.selectByTenantAndIdForScope(
                org.mockito.ArgumentMatchers.eq(TENANT_ID),
                org.mockito.ArgumentMatchers.eq(907L),
                org.mockito.ArgumentMatchers.any())).thenReturn(pending);
        when(historicalGroupService.getHistoricalGroupDetail(request.sourceAccountGroupId(), request.groupJid()))
                .thenReturn(detail("fresh-start-link", true, false));
        when(executionMapper.claimStatus(
                org.mockito.ArgumentMatchers.eq(907L),
                org.mockito.ArgumentMatchers.eq(HistoricalGroupPullStatus.PENDING.code()),
                org.mockito.ArgumentMatchers.eq(HistoricalGroupPullStatus.RUNNING.code()),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.start(907L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("状态已变化");

        verify(dispatchTrigger, never()).dispatchAfterCommit(any(), any());
    }

    @Test
    void getByIdIncludesCompletePullerPhoneAndParticipantJidFromAccountService() {
        HistoricalGroupPullCreateDTO request = request(10, "puller-vo");
        HistoricalGroupPullExecution persisted = execution(908L, request);
        persisted.setPullerAccountId(501L);
        ProtocolAccountRef puller =
                new ProtocolAccountRef(501L, ProtocolBackend.WEB, "puller-501", "8613700000501");
        when(executionMapper.selectByTenantAndIdForScope(
                org.mockito.ArgumentMatchers.eq(TENANT_ID),
                org.mockito.ArgumentMatchers.eq(908L),
                org.mockito.ArgumentMatchers.any())).thenReturn(persisted);
        when(memberMapper.selectOrderedByExecutionId(908L)).thenReturn(List.of());
        when(accountLookupService.findActiveProtocolRef(501L)).thenReturn(Optional.of(puller));

        HistoricalGroupPullExecutionVO result = service.getById(908L);

        assertThat(result.pullerPhone()).isEqualTo("8613700000501");
        assertThat(result.pullerParticipantJid()).isEqualTo("8613700000501@s.whatsapp.net");
    }

    private void prepareOwnership(HistoricalGroupPullCreateDTO request) {
        AccountGroup source = new AccountGroup();
        source.setOwnerUserId(OWNER_USER_ID);
        AccountGroup puller = new AccountGroup();
        puller.setOwnerUserId(OWNER_USER_ID);
        when(accountGroupService.requireExisting(request.sourceAccountGroupId())).thenReturn(source);
        when(accountGroupService.requireExisting(request.pullerAccountGroupId())).thenReturn(puller);
    }

    private static HistoricalGroupMaterialParser.ParseResult parseResult() {
        return new HistoricalGroupMaterialParser.ParseResult(
                List.of(
                        new HistoricalGroupMaterialParser.ParsedMember(
                                "8613900000002", HistoricalGroupMaterialType.MARKETING, 2),
                        new HistoricalGroupMaterialParser.ParsedMember(
                                "8613800000001", HistoricalGroupMaterialType.NORMAL, 1)),
                1,
                1,
                1,
                2);
    }

    private static HistoricalGroupDetailVO detail(
            String inviteUrl,
            boolean linkAvailable,
            boolean operationAllowed) {
        return new HistoricalGroupDetailVO(
                101L,
                "120363test@g.us",
                "fresh-subject",
                null,
                null,
                null,
                null,
                20,
                false,
                inviteUrl,
                linkAvailable,
                operationAllowed,
                operationAllowed ? null : "当前账号不是管理员",
                null,
                null,
                List.of());
    }

    private static HistoricalGroupPullCreateDTO request(int singleAddCount, String idempotencyKey) {
        return new HistoricalGroupPullCreateDTO(
                201L, "120363test@g.us", 301L, singleAddCount, idempotencyKey);
    }

    private static HistoricalGroupPullExecution execution(
            Long id,
            HistoricalGroupPullCreateDTO request) {
        HistoricalGroupPullExecution row = new HistoricalGroupPullExecution();
        row.setId(id);
        row.setTenantId(TENANT_ID);
        row.setOwnerUserId(OWNER_USER_ID);
        row.setCreatedBy(OWNER_USER_ID);
        row.setIdempotencyKey(request.idempotencyKey());
        row.setSourceAccountGroupId(request.sourceAccountGroupId());
        row.setOperationAccountId(101L);
        row.setGroupJid(request.groupJid());
        row.setPullerAccountGroupId(request.pullerAccountGroupId());
        row.setSingleAddCount(request.singleAddCount());
        row.setPullStatus(HistoricalGroupPullStatus.PENDING.code());
        row.setMarketingStatus(HistoricalGroupMarketingStatus.NOT_APPLICABLE.code());
        row.setCreatedAt(1000L);
        row.setUpdatedAt(1000L);
        return row;
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "material.txt", "text/plain", "8613800000001".getBytes());
    }
}
