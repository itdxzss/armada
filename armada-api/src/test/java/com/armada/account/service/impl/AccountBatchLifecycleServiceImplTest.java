package com.armada.account.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountBatchPreviewDTO;
import com.armada.account.model.dto.AccountBatchQueryDTO;
import com.armada.account.model.dto.AccountBatchTargetQuery;
import com.armada.account.model.dto.AccountQuery;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.enums.AccountBatchOperation;
import com.armada.account.model.enums.AccountBatchScope;
import com.armada.account.model.vo.AccountBatchCommandResultVO;
import com.armada.account.model.vo.AccountBatchOnlineItemVO;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.model.vo.AccountBatchPreviewRow;
import com.armada.account.model.vo.AccountBatchPreviewVO;
import com.armada.account.model.vo.AccountBatchTargetRow;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.shared.exception.BusinessException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 账号批量生命周期编排服务单测。
 */
@ExtendWith(MockitoExtension.class)
class AccountBatchLifecycleServiceImplTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private AccountOnlineCommandService commandService;

    @InjectMocks
    private AccountBatchLifecycleServiceImpl service;

    @Test
    void onlineByIdsAccepts2000AndCallsFour500AccountChunks() {
        List<Long> ids = range(1, 2_000);
        when(accountMapper.selectBatchTargetsByIds(ids)).thenReturn(targets(ids, null, true));
        when(commandService.onlineBatch(any())).thenAnswer(invocation -> accepted(invocation.getArgument(0)));

        AccountBatchCommandResultVO result = service.onlineByIds(ids);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> chunks = ArgumentCaptor.forClass(List.class);
        verify(commandService, times(4)).onlineBatch(chunks.capture());
        assertThat(chunks.getAllValues()).allSatisfy(chunk -> assertThat(chunk).hasSize(500));
        assertThat(result.requested()).isEqualTo(2_000);
        assertThat(result.submitted()).isEqualTo(2_000);
        assertThat(result.accepted()).isEqualTo(2_000);
        assertThat(result.failed()).isZero();
    }

    @Test
    void onlineByIdsSkipsBlockedAndMissingCredentialButKeepsNormalAccount() {
        List<Long> ids = List.of(1L, 2L, 3L, 4L, 5L);
        when(accountMapper.selectBatchTargetsByIds(ids)).thenReturn(List.of(
                target(1L, AccountStateCode.BANNED, true),
                target(2L, AccountStateCode.UNBOUND, true),
                target(3L, AccountStateCode.TAKING_OVER, true),
                target(4L, AccountStateCode.NORMAL, false),
                target(5L, AccountStateCode.NORMAL, true)));
        when(commandService.onlineBatch(List.of(5L))).thenReturn(accepted(List.of(5L)));

        AccountBatchCommandResultVO result = service.onlineByIds(ids);

        verify(commandService).onlineBatch(List.of(5L));
        assertThat(result.skipped()).isEqualTo(4);
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.skipReasons())
                .containsEntry("BANNED", 1)
                .containsEntry("UNBOUND", 1)
                .containsEntry("TAKING_OVER", 1)
                .containsEntry("MISSING_CREDENTIAL", 1);
    }

    @Test
    void onlineByIdsRejects2001BeforeQueryingTargets() {
        List<Long> ids = range(1, 2_001);

        assertThatThrownBy(() -> service.onlineByIds(ids))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("一次最多 2000 个账号");

        verifyNoInteractions(accountMapper, commandService);
    }

    @Test
    void onlineByIdsRejectsNullAndDuplicateIdsBeforeQueryingTargets() {
        assertThatThrownBy(() -> service.onlineByIds(List.of(1L, 1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能重复");
        assertThatThrownBy(() -> service.onlineByIds(Arrays.asList(1L, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");

        verifyNoInteractions(accountMapper, commandService);
    }

    @Test
    void onlineByIdsRejectsTargetsMissingFromCurrentTenantBeforeSubmitting() {
        List<Long> ids = List.of(1L, 2L);
        when(accountMapper.selectBatchTargetsByIds(ids))
                .thenReturn(List.of(target(1L, AccountStateCode.NORMAL, true)));

        assertThatThrownBy(() -> service.onlineByIds(ids))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前租户");

        verifyNoInteractions(commandService);
    }

    @Test
    void offlineByIdsCallsTwo1000AccountChunksWithoutCredentialSkipping() {
        List<Long> ids = range(1, 2_000);
        when(accountMapper.selectBatchTargetsByIds(ids))
                .thenReturn(targets(ids, AccountStateCode.BANNED, false));
        when(commandService.offlineBatch(any())).thenAnswer(invocation -> accepted(invocation.getArgument(0)));

        AccountBatchCommandResultVO result = service.offlineByIds(ids);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> chunks = ArgumentCaptor.forClass(List.class);
        verify(commandService, times(2)).offlineBatch(chunks.capture());
        assertThat(chunks.getAllValues()).allSatisfy(chunk -> assertThat(chunk).hasSize(1_000));
        assertThat(result.submitted()).isEqualTo(2_000);
        assertThat(result.skipped()).isZero();
        assertThat(result.accepted()).isEqualTo(2_000);
    }

    @Test
    void previewOnlineByQueryReturnsMatchedExecutableAndExclusiveSkipCounts() {
        when(accountMapper.previewBatchTargetsByQuery(any(AccountQuery.class)))
                .thenReturn(previewRow(1_256, 20, 10, 6, 20));

        AccountBatchPreviewVO result = service.preview(new AccountBatchPreviewDTO(
                AccountBatchOperation.ONLINE,
                AccountBatchScope.QUERY,
                null,
                emptyQuery()));

        assertThat(result.matched()).isEqualTo(1_256);
        assertThat(result.skipped()).isEqualTo(56);
        assertThat(result.executable()).isEqualTo(1_200);
        assertThat(result.skipReasons())
                .containsEntry("BANNED", 20L)
                .containsEntry("UNBOUND", 10L)
                .containsEntry("TAKING_OVER", 6L)
                .containsEntry("MISSING_CREDENTIAL", 20L);
    }

    @Test
    void previewOfflineByQueryDoesNotApplyLoginSkipRules() {
        when(accountMapper.previewBatchTargetsByQuery(any(AccountQuery.class)))
                .thenReturn(previewRow(20, 5, 4, 3, 2));

        AccountBatchPreviewVO result = service.preview(new AccountBatchPreviewDTO(
                AccountBatchOperation.OFFLINE,
                AccountBatchScope.QUERY,
                null,
                emptyQuery()));

        assertThat(result.matched()).isEqualTo(20);
        assertThat(result.executable()).isEqualTo(20);
        assertThat(result.skipped()).isZero();
        assertThat(result.skipReasons()).isEmpty();
    }

    @Test
    void onlineByQueryScansAllPagesAndContinuesAfterMiddleChunkFailure() {
        List<AccountBatchTargetRow> first = targets(range(1, 500), null, true);
        List<AccountBatchTargetRow> second = targets(range(501, 1_000), null, true);
        List<AccountBatchTargetRow> third = targets(range(1_001, 1_100), null, true);
        when(accountMapper.selectBatchTargetsAfterId(any(AccountBatchTargetQuery.class)))
                .thenReturn(first, second, third);
        when(commandService.onlineBatch(ids(first))).thenReturn(accepted(ids(first)));
        when(commandService.onlineBatch(ids(second))).thenThrow(new RuntimeException("outbox unavailable"));
        when(commandService.onlineBatch(ids(third))).thenReturn(accepted(ids(third)));

        AccountBatchCommandResultVO result = service.onlineByQuery(emptyQuery());

        verify(commandService, times(3)).onlineBatch(any());
        assertThat(result.requested()).isEqualTo(1_100);
        assertThat(result.submitted()).isEqualTo(1_100);
        assertThat(result.accepted()).isEqualTo(600);
        assertThat(result.failed()).isEqualTo(500);
        assertThat(result.batchErrors()).containsExactly("outbox unavailable");
        assertThat(result.results()).isEmpty();
    }

    @Test
    void previewRejectsIdsScopeWithoutIds() {
        assertThatThrownBy(() -> service.preview(new AccountBatchPreviewDTO(
                AccountBatchOperation.ONLINE,
                AccountBatchScope.IDS,
                null,
                null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("IDS 预估必须提供账号 ID");
    }

    private static List<Long> range(long first, long last) {
        return LongStream.rangeClosed(first, last).boxed().toList();
    }

    private static AccountBatchQueryDTO emptyQuery() {
        return new AccountBatchQueryDTO(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static List<Long> ids(List<AccountBatchTargetRow> rows) {
        return rows.stream().map(AccountBatchTargetRow::getId).toList();
    }

    private static AccountBatchPreviewRow previewRow(
            long matched,
            long banned,
            long unbound,
            long takingOver,
            long missingCredential) {
        AccountBatchPreviewRow row = new AccountBatchPreviewRow();
        row.setMatched(matched);
        row.setBanned(banned);
        row.setUnbound(unbound);
        row.setTakingOver(takingOver);
        row.setMissingCredential(missingCredential);
        return row;
    }

    private static AccountBatchTargetRow target(Long id, Integer state, boolean credentialPresent) {
        AccountBatchTargetRow row = new AccountBatchTargetRow();
        row.setId(id);
        row.setAccountState(state);
        row.setCredentialPresent(credentialPresent);
        return row;
    }

    private static List<AccountBatchTargetRow> targets(
            List<Long> ids,
            Integer state,
            boolean credentialPresent) {
        return ids.stream().map(id -> target(id, state, credentialPresent)).toList();
    }

    private static AccountBatchOnlineVO accepted(List<Long> ids) {
        return new AccountBatchOnlineVO(
                ids.size(),
                ids.size(),
                ids.size(),
                0,
                0,
                0,
                0,
                0L,
                ids.stream()
                        .map(id -> new AccountBatchOnlineItemVO(
                                id, "acc_" + id, "ACCEPTED", null, null))
                        .toList(),
                List.of());
    }
}
