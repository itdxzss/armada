package com.armada.contact.task;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.model.entity.AccountContact;
import com.armada.account.contact.config.AccountContactProperties;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
import com.armada.account.contact.model.entity.AccountContactSync;
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
    private AccountContactSyncMapper syncMapper;
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
                selector, syncMapper, new AccountContactProperties(24),
                contactMapper, taskMapper, accountMapper,
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

    private static AccountContactSync snapshot(Long lastSyncedAt, String status) {
        AccountContactSync row = new AccountContactSync();
        row.setAccountId(11L);
        row.setLastSyncedAt(lastSyncedAt);
        row.setSyncStatus(status);
        return row;
    }

    private static AccountContactSync fresh() {
        return snapshot(900L, AccountContactSync.STATUS_SUCCESS);
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
        when(syncMapper.selectByAccountId(11L)).thenReturn(fresh());
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
        when(syncMapper.selectByAccountId(anyLong())).thenReturn(fresh());
        when(contactMapper.selectNamedByAccount(eq(11L), eq(3)))
                .thenReturn(List.of(contact("1"), contact("2"), contact("3")));
        givenGeneratedAccountIds(101L);

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 3));

        assertThat(result.recipientCount()).isEqualTo(3);
        verify(contactMapper).selectNamedByAccount(11L, 3);
    }

    @Test
    void skipsAccountWithoutAnySnapshot() {
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncMapper.selectByAccountId(11L)).thenReturn(null);

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
    void skipsAccountWhoseSnapshotIsStale() {
        // 宁可少发，也不拿三天前的通讯录发
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncMapper.selectByAccountId(11L)).thenReturn(
                snapshot(1_000L - 100L * 3_600_000L, AccountContactSync.STATUS_SUCCESS));

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 0));

        assertThat(result.recipientCount()).isZero();
        verify(contactMapper, never()).selectNamedByAccount(anyLong(), anyInt());
    }

    @Test
    void usesPartialSnapshot() {
        // PARTIAL 的数据是全的，只是可能多几条已删的，可以用
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncMapper.selectByAccountId(11L)).thenReturn(
                snapshot(900L, AccountContactSync.STATUS_PARTIAL));
        when(contactMapper.selectNamedByAccount(eq(11L), anyInt()))
                .thenReturn(List.of(contact("8613900000001")));
        givenGeneratedAccountIds(101L);

        ContactTaskExpansionService.ExpansionResult result = service().expand(task(10, 0));

        assertThat(result.recipientCount()).isEqualTo(1);
    }

    @Test
    void neverTriggersASynchronousPull() {
        // 拉取路径已退役，展开时绝不能再有任何同步拉取
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncMapper.selectByAccountId(11L)).thenReturn(fresh());
        when(contactMapper.selectNamedByAccount(anyLong(), anyInt()))
                .thenReturn(List.of(contact("8613900000001")));
        givenGeneratedAccountIds(101L);

        service().expand(task(10, 0));

        // syncMapper 只读不写
        verify(syncMapper, never()).upsert(any());
    }

    @Test
    void stampsSnapshotTimeOnTheTaskAccountRow() {
        // 任务账号行记的是「用的哪一份快照」，必须是协议给的快照时间
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncMapper.selectByAccountId(11L)).thenReturn(fresh());
        when(contactMapper.selectNamedByAccount(anyLong(), anyInt()))
                .thenReturn(List.of(contact("8613900000001")));
        givenGeneratedAccountIds(101L);

        service().expand(task(10, 0));

        ArgumentCaptor<ContactFriendTaskAccount> captor =
                ArgumentCaptor.forClass(ContactFriendTaskAccount.class);
        verify(accountMapper).insert(captor.capture());
        assertThat(captor.getValue().getContactSyncedAt()).isEqualTo(900L);
    }

    @Test
    void skipsAccountWithZeroNamedContacts() {
        when(selector.select(any(), anyInt())).thenReturn(
                List.of(new SelectedAccount(11L, "8613800000000", "web", "acc_1")));
        when(syncMapper.selectByAccountId(anyLong())).thenReturn(fresh());
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
        when(syncMapper.selectByAccountId(anyLong())).thenReturn(fresh());
        when(contactMapper.selectNamedByAccount(anyLong(), anyInt())).thenReturn(null);

        service().expand(task(10, 0));

        verify(recipientMapper, never()).insertBatch(any());
    }

    @Test
    void writesTaskTotalsAfterExpansion() {
        when(selector.select(any(), anyInt())).thenReturn(List.of(
                new SelectedAccount(11L, "p1", "web", "acc_1"),
                new SelectedAccount(12L, "p2", "web", "acc_2")));
        when(syncMapper.selectByAccountId(anyLong())).thenReturn(fresh());
        when(contactMapper.selectNamedByAccount(eq(11L), anyInt())).thenReturn(List.of(contact("1")));
        when(contactMapper.selectNamedByAccount(eq(12L), anyInt()))
                .thenReturn(List.of(contact("2"), contact("3")));
        givenGeneratedAccountIds(101L, 102L);

        service().expand(task(10, 0));

        verify(taskMapper).applyExpansionTotals(eq(1L), eq(3), eq(2), anyLong());
    }
}
