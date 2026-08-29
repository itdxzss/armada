package com.armada.contact.task;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.model.AccountContactSyncResult;
import com.armada.account.contact.model.ContactSyncSource;
import com.armada.account.contact.model.entity.AccountContact;
import com.armada.account.contact.service.AccountContactSyncService;
import com.armada.account.selection.AccountFilterSelector;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.contact.task.service.ContactTaskExpansionService;
import com.armada.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通讯录任务展开服务的纯 Mockito 测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContactTaskExpansionServiceTest {

    @Mock
    private AccountFilterSelector selector;
    @Mock
    private AccountContactSyncService syncService;
    @Mock
    private AccountContactMapper contactMapper;
    @Mock
    private ContactFriendTaskMapper taskMapper;
    @Mock
    private ContactFriendTaskAccountMapper accountMapper;
    @Mock
    private ContactFriendTaskRecipientMapper recipientMapper;

    private ContactTaskExpansionService service() {
        return new ContactTaskExpansionService(
                selector, syncService, contactMapper, taskMapper, accountMapper,
                recipientMapper, () -> 1_000L, () -> 5L);
    }

    private static ContactFriendTask task(int concurrency, int maxSendsPerAccount) {
        ContactFriendTask task = new ContactFriendTask();
        task.setId(1L);
        task.setTenantId(5L);
        task.setAccountFilter("{}");
        task.setConcurrency(concurrency);
        task.setMaxSendsPerAccount(maxSendsPerAccount);
        return task;
    }

    private static AccountContact contact(String phone) {
        AccountContact row = new AccountContact();
        row.setContactPhone(phone);
        row.setContactJid(phone + "@s.whatsapp.net");
        row.setIsNamed(1);
        return row;
    }

    private static AccountContactSyncResult fresh(int namedNum) {
        return new AccountContactSyncResult(false, true, namedNum, namedNum, 0, 900L, null);
    }

    private void givenGeneratedAccountIds(Long... ids) {
        List<Long> assigned = new ArrayList<>(List.of(ids));
        when(accountMapper.insert(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, ContactFriendTaskAccount.class).setId(assigned.remove(0));
            return 1;
        });
    }

    @Test
    void rejectsEnablingWhenFilterMatchesNoAccount() {
        when(selector.select(any(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service().expand(task(10, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号");
    }

    @Test
    void usesConcurrencyAsAccountSelectionLimit() {
        when(selector.select(any(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service().expand(task(7, 0)))
                .isInstanceOf(BusinessException.class);

        verify(selector).select(eq("{}"), eq(7));
    }

    @Test
    void expandsNamedContactsIntoRecipients() {
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncService.syncIfStale(11L, ContactSyncSource.TASK_START)).thenReturn(fresh(2));
        when(contactMapper.selectNamedByAccount(eq(11L), anyInt()))
                .thenReturn(List.of(contact("8613900000001"), contact("8613900000002")));
        givenGeneratedAccountIds(101L);

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 0));

        assertThat(result.accountCount()).isEqualTo(1);
        assertThat(result.recipientCount()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ContactFriendTaskRecipient>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(recipientMapper).insertBatch(captor.capture());
        List<ContactFriendTaskRecipient> rows = captor.getValue();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getTaskAccountId()).isEqualTo(101L);
        assertThat(rows.get(0).getContactJid()).isEqualTo("8613900000001@s.whatsapp.net");
        assertThat(rows.get(0).getTenantId()).isEqualTo(5L);
        assertThat(rows.get(0).getTaskId()).isEqualTo(1L);
    }

    @Test
    void appliesPerAccountSendCap() {
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncService.syncIfStale(anyLong(), any())).thenReturn(fresh(100));
        when(contactMapper.selectNamedByAccount(eq(11L), eq(3)))
                .thenReturn(List.of(contact("1"), contact("2"), contact("3")));
        givenGeneratedAccountIds(101L);

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 3));

        assertThat(result.recipientCount()).isEqualTo(3);
        verify(contactMapper).selectNamedByAccount(11L, 3);
    }

    @Test
    void skipsAccountWithoutAnyUsableSnapshot() {
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncService.syncIfStale(anyLong(), any()))
                .thenReturn(new AccountContactSyncResult(true, false, 0, 0, 0, null, "protocol down"));

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 0));

        assertThat(result.recipientCount()).isZero();
        verify(recipientMapper, never()).insertBatch(any());

        ArgumentCaptor<ContactFriendTaskAccount> captor =
                ArgumentCaptor.forClass(ContactFriendTaskAccount.class);
        verify(accountMapper).insert(captor.capture());
        assertThat(captor.getValue().getState())
                .isEqualTo(ContactFriendTaskAccount.STATE_SKIPPED);
        assertThat(captor.getValue().getNeedSendNum()).isZero();
    }

    @Test
    void usesStaleSnapshotWhenRefreshFailsButHistoryExists() {
        // 拉取失败但有历史快照时仍能发，不能因为一次协议抖动就把整个账号跳过
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncService.syncIfStale(anyLong(), any()))
                .thenReturn(new AccountContactSyncResult(true, false, 0, 0, 0, 800L, "protocol down"));
        when(contactMapper.selectNamedByAccount(anyLong(), anyInt()))
                .thenReturn(List.of(contact("8613900000001")));
        givenGeneratedAccountIds(101L);

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 0));

        assertThat(result.recipientCount()).isEqualTo(1);
    }

    @Test
    void skipsAccountWithZeroNamedContacts() {
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncService.syncIfStale(anyLong(), any())).thenReturn(fresh(0));
        when(contactMapper.selectNamedByAccount(anyLong(), anyInt())).thenReturn(List.of());

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 0));

        assertThat(result.accountCount()).isZero();
        verify(recipientMapper, never()).insertBatch(any());
    }

    @Test
    void neverCallsBatchInsertWithEmptyList() {
        // 空批次 foreach 会生成空 VALUES 语法错
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncService.syncIfStale(anyLong(), any())).thenReturn(fresh(0));
        when(contactMapper.selectNamedByAccount(anyLong(), anyInt())).thenReturn(null);

        service().expand(task(10, 0));

        verify(recipientMapper, never()).insertBatch(any());
    }

    @Test
    void writesTaskTotalsAfterExpansion() {
        when(selector.select(any(), anyInt())).thenReturn(List.of(
                new SelectedAccount(11L, "p1", "web", "acc_1"),
                new SelectedAccount(12L, "p2", "web", "acc_2")));
        when(syncService.syncIfStale(anyLong(), any())).thenReturn(fresh(1));
        when(contactMapper.selectNamedByAccount(eq(11L), anyInt())).thenReturn(List.of(contact("1")));
        when(contactMapper.selectNamedByAccount(eq(12L), anyInt()))
                .thenReturn(List.of(contact("2"), contact("3")));
        givenGeneratedAccountIds(101L, 102L);

        service().expand(task(10, 0));

        verify(taskMapper).applyExpansionTotals(eq(1L), eq(3), eq(2), anyLong());
    }
}
