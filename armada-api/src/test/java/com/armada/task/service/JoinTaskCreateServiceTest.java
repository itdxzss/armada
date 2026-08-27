package com.armada.task.service;

import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountGroup;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.dto.CreateJoinTaskDTO;
import com.armada.task.model.dto.SelectedAccount;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.model.enums.JoinTaskDispatchState;
import com.armada.task.service.impl.JoinTaskServiceImpl;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class JoinTaskCreateServiceTest {

    @Mock
    private JoinTaskMapper joinTaskMapper;

    @Mock
    private JoinTaskResultMapper resultMapper;

    @Mock
    private GroupLinkRegistryService groupLinkRegistryService;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private AccountGroupMapper accountGroupMapper;

    private JoinTaskServiceImpl service;
    private DataScopeContext.Scope dataScope;

    @BeforeEach
    void setUp() {
        TenantContext.set(1L);
        dataScope = DataScopeContext.open(DataScope.self(1001L));
        service = new JoinTaskServiceImpl(
                joinTaskMapper, resultMapper, groupLinkRegistryService, accountMapper, accountGroupMapper);
    }

    @AfterEach
    void tearDown() {
        dataScope.close();
        TenantContext.clear();
    }

    @Test
    void createTask_rejectsWhenNoValidLinksRemainAfterCleaning() {
        CreateJoinTaskDTO req = new CreateJoinTaskDTO(
                "无有效链接校验",
                null,
                null,
                List.of(new SelectedAccount(1L, "911")),
                "\n   \n",
                "FIXED_ACCOUNTS_PER_LINK",
                1,
                null,
                null,
                5,
                10,
                null,
                null,
                false,
                0,
                "SKIP");

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCode.VALIDATION.code());
                    assertThat(be.getMessage()).contains("当前没有有效群链接");
                });

        verify(joinTaskMapper, never()).insert(any(JoinTask.class));
        verify(resultMapper, never()).insertResults(any());
        verify(groupLinkRegistryService, never()).registerJoinTaskTargets(any());
    }

    @Test
    void createTask_mode2RejectsWhenCheckedAccountCountDiffersFromConfiguredCount() {
        CreateJoinTaskDTO req = modeTwoRequest(
                List.of(account(1L), account(2L)),
                links(2),
                1,
                2);

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("勾选账号数量与填写的执行账号数量不一致");

        verify(joinTaskMapper, never()).insert(any(JoinTask.class));
    }

    @Test
    void createTask_mode2RejectsWhenValidLinksExceedAccountCapacity() {
        CreateJoinTaskDTO req = modeTwoRequest(
                List.of(account(1L), account(2L)),
                links(5),
                2,
                2);

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("有效群链接数量超过任务容量")
                .hasMessageContaining("补充账号");

        verify(joinTaskMapper, never()).insert(any(JoinTask.class));
    }

    @Test
    void createTask_mode2RejectsDuplicateAccountIds() {
        CreateJoinTaskDTO req = modeTwoRequest(
                List.of(account(1L), account(1L)),
                links(2),
                2,
                1);

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("执行账号无效或重复");

        verify(joinTaskMapper, never()).insert(any(JoinTask.class));
    }

    @Test
    void createTask_mode2RejectsNullAccountId() {
        CreateJoinTaskDTO req = modeTwoRequest(
                List.of(account(1L), new SelectedAccount(null, "unknown")),
                links(2),
                2,
                1);

        assertThatThrownBy(() -> service.createTask(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("执行账号无效或重复");

        verify(joinTaskMapper, never()).insert(any(JoinTask.class));
    }

    @Test
    void createTask_mode2AcceptsFewerValidLinksThanCapacity() {
        CreateJoinTaskDTO req = modeTwoRequest(
                List.of(account(1L), account(2L)),
                links(3),
                2,
                3);
        AtomicReference<JoinTask> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            JoinTask task = invocation.getArgument(0);
            task.setId(99L);
            inserted.set(task);
            return 1;
        }).when(joinTaskMapper).insert(any(JoinTask.class));
        when(joinTaskMapper.selectByTenantAndIdForScope(eq(99L), any())).thenAnswer(invocation -> inserted.get());
        when(accountMapper.selectActiveByIds(List.of(1L, 2L)))
                .thenReturn(List.of(accountRow(1L, 1L, 1001L), accountRow(2L, 1L, 1001L)));
        when(accountGroupMapper.selectByIds(List.of(1L)))
                .thenReturn(List.of(groupRow(1L, 1001L)));

        service.createTask(req);

        ArgumentCaptor<List<JoinTaskResult>> rows = ArgumentCaptor.forClass(List.class);
        verify(resultMapper).insertResults(rows.capture());
        assertThat(inserted.get().getTotal()).isEqualTo(3);
        assertThat(inserted.get().getOwnerUserId()).isEqualTo(1001L);
        assertThat(inserted.get().getCreatedBy()).isEqualTo(1001L);
        assertThat(rows.getValue()).hasSize(3);
        assertThat(rows.getValue()).allSatisfy(row -> {
            assertThat(row.getDispatchState()).isEqualTo(JoinTaskDispatchState.WAITING);
            assertThat(row.getNextExecuteAt()).isNull();
            assertThat(row.getCommandId()).isNull();
            assertThat(row.getAttemptNo()).isZero();
        });
    }

    private static CreateJoinTaskDTO modeTwoRequest(
            List<SelectedAccount> accounts,
            String links,
            int executorAccountCount,
            int linksPerAccount) {
        return new CreateJoinTaskDTO(
                "方式二任务",
                List.of(1L),
                List.of("账号组"),
                accounts,
                links,
                "FIXED_ACCOUNT_MULTI_LINK",
                null,
                executorAccountCount,
                linksPerAccount,
                null,
                null,
                5,
                10,
                true,
                2,
                "RETRY_THEN_EXPORT");
    }

    private static SelectedAccount account(long id) {
        return new SelectedAccount(id, "9" + id);
    }

    private static Account accountRow(long id, long groupId, Long ownerUserId) {
        Account account = new Account();
        account.setId(id);
        account.setAccountGroupId(groupId);
        account.setOwnerUserId(ownerUserId);
        return account;
    }

    private static AccountGroup groupRow(long id, Long ownerUserId) {
        AccountGroup group = new AccountGroup();
        group.setId(id);
        group.setOwnerUserId(ownerUserId);
        return group;
    }

    private static String links(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                text.append('\n');
            }
            text.append("https://chat.whatsapp.com/LINK").append(i);
        }
        return text.toString();
    }
}
