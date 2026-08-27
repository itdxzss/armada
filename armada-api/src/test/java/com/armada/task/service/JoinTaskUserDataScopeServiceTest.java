package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountGroup;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.dto.CreateJoinTaskDTO;
import com.armada.task.model.dto.JoinTaskFilter;
import com.armada.task.model.dto.JoinTaskQuery;
import com.armada.task.model.dto.SelectedAccount;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.service.impl.JoinTaskServiceImpl;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 进群任务 Service 用户归属边界测试。 */
@ExtendWith(MockitoExtension.class)
class JoinTaskUserDataScopeServiceTest {

    @Mock private JoinTaskMapper taskMapper;
    @Mock private JoinTaskResultMapper resultMapper;
    @Mock private GroupLinkRegistryService groupLinkRegistryService;
    @Mock private AccountMapper accountMapper;
    @Mock private AccountGroupMapper accountGroupMapper;

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }

    @Test
    void adminCannotCreateTaskOnBehalfOfAnotherOwner() {
        try (var ignored = DataScopeContext.open(DataScope.all(9001L))) {
            when(accountMapper.selectActiveByIds(List.of(11L)))
                    .thenReturn(List.of(account(11L, 21L, 1002L)));
            when(accountGroupMapper.selectByIds(List.of(21L)))
                    .thenReturn(List.of(group(21L, 1002L)));

            assertThatThrownBy(() -> service().createTask(request()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(ErrorCode.VALIDATION.code()))
                    .hasMessageContaining("当前操作者自己的资源");

            verify(taskMapper, never()).insert(any());
            verifyNoInteractions(resultMapper);
        }
    }

    @Test
    void mixedOwnerBatchDeleteRejectsWholeRequest() {
        try (var ignored = DataScopeContext.open(DataScope.self(1001L))) {
            JoinTask own = new JoinTask();
            own.setId(1L);
            own.setOwnerUserId(1001L);
            when(taskMapper.selectByIdsForScope(eq(List.of(1L, 2L)), any()))
                    .thenReturn(List.of(own));

            assertThatThrownBy(() -> service().batchDelete(List.of(1L, 2L)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(ErrorCode.NOT_FOUND.code()));

            verify(taskMapper, never()).batchSoftDelete(any(), anyLong());
        }
    }

    @Test
    void resultsAuthorizeTaskRootBeforeReadingChildren() {
        try (var ignored = DataScopeContext.open(DataScope.self(1001L))) {
            when(taskMapper.selectByTenantAndIdForScope(eq(2L), any())).thenReturn(null);

            assertThatThrownBy(() -> service().results(2L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(ErrorCode.NOT_FOUND.code()));

            verifyNoInteractions(resultMapper);
        }
    }

    @Test
    void listInjectsTrustedScopeAndMissingScopeFailsClosed() {
        try (var ignored = DataScopeContext.open(DataScope.self(1001L))) {
            when(taskMapper.countPage(any())).thenReturn(0L);
            service().listTasks(new JoinTaskQuery());

            ArgumentCaptor<JoinTaskFilter> filter = ArgumentCaptor.forClass(JoinTaskFilter.class);
            verify(taskMapper).countPage(filter.capture());
            assertThat(filter.getValue().dataScope()).isEqualTo(DataScope.self(1001L));
        }

        assertThatThrownBy(() -> service().listTasks(new JoinTaskQuery()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.ACCESS_DENIED.code()));
    }

    private JoinTaskServiceImpl service() {
        return new JoinTaskServiceImpl(
                taskMapper, resultMapper, groupLinkRegistryService, accountMapper, accountGroupMapper);
    }

    private static CreateJoinTaskDTO request() {
        return new CreateJoinTaskDTO(
                "管理员不可代建", List.of(21L), List.of("U2 分组"),
                List.of(new SelectedAccount(11L, "8613800000011")),
                "https://chat.whatsapp.com/OWNERTEST", "FIXED_ACCOUNTS_PER_LINK",
                1, null, null, 5, 10, null, null, false, 0, "SKIP");
    }

    private static Account account(long id, long groupId, Long ownerUserId) {
        Account row = new Account();
        row.setId(id);
        row.setAccountGroupId(groupId);
        row.setOwnerUserId(ownerUserId);
        return row;
    }

    private static AccountGroup group(long id, Long ownerUserId) {
        AccountGroup row = new AccountGroup();
        row.setId(id);
        row.setOwnerUserId(ownerUserId);
        return row;
    }
}
