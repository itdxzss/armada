package com.armada.account.contact;

import com.armada.account.contact.config.AccountContactProperties;
import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
import com.armada.account.contact.model.AccountContactSyncResult;
import com.armada.account.contact.model.ContactSyncSource;
import com.armada.account.contact.model.entity.AccountContact;
import com.armada.account.contact.model.entity.AccountContactSync;
import com.armada.account.contact.service.AccountContactNormalizer;
import com.armada.account.contact.service.impl.AccountContactSyncServiceImpl;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import com.armada.platform.protocol.port.ContactListPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountContactSyncServiceImplTest {

    private static final long NOW = 1_756_345_678_901L;
    private static final long TENANT = 1L;
    private static final long ACCOUNT = 501L;
    private static final long HOUR = 3_600_000L;

    private ContactListPort contactListPort;
    private AccountContactMapper contactMapper;
    private AccountContactSyncMapper syncMapper;
    private AccountStateMapper accountStateMapper;
    private AccountContactSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        contactListPort = mock(ContactListPort.class);
        contactMapper = mock(AccountContactMapper.class);
        syncMapper = mock(AccountContactSyncMapper.class);
        accountStateMapper = mock(AccountStateMapper.class);
        service = new AccountContactSyncServiceImpl(
                contactListPort,
                contactMapper,
                syncMapper,
                accountStateMapper,
                new AccountContactNormalizer(),
                new AccountContactProperties(true, 24),
                accountId -> new ProtocolAccountRef(
                        ACCOUNT, ProtocolBackend.WEB, "acc_501", "8613800000000"),
                () -> TENANT,
                () -> NOW);
    }

    private static AccountContactSnapshot snapshot(int size) {
        return new AccountContactSnapshot(
                IntStream.range(0, size)
                        .mapToObj(i -> {
                            String phone = "861380000" + String.format("%04d", i);
                            return new AccountContactSnapshot.Contact(
                                    phone, phone + "@s.whatsapp.net", "联系人" + i, null, null, null);
                        })
                        .toList(),
                NOW);
    }

    @Test
    void syncNowWritesRowsSweepsStaleAndBackfillsCounts() {
        when(contactListPort.list(any())).thenReturn(snapshot(3));

        AccountContactSyncResult result = service.syncNow(ACCOUNT, ContactSyncSource.ONLINE_EVENT);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.refreshed()).isTrue();
        assertThat(result.contactNum()).isEqualTo(3);
        assertThat(result.namedNum()).isEqualTo(3);
        assertThat(result.mutualNum()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AccountContact>> rows = ArgumentCaptor.forClass(List.class);
        verify(contactMapper).upsertBatch(rows.capture());
        assertThat(rows.getValue()).hasSize(3);
        assertThat(rows.getValue().get(0).getTenantId()).isEqualTo(TENANT);
        assertThat(rows.getValue().get(0).getSyncedAt()).isEqualTo(NOW);

        // 扫尾删除必须用同一个 syncedAt，否则会把刚写进去的行删掉
        verify(contactMapper).deleteStale(ACCOUNT, NOW);
        verify(accountStateMapper).updateContactCounts(ACCOUNT, 3, 0, NOW);
    }

    @Test
    void emptySnapshotStillSweepsAndZeroesCounts() {
        when(contactListPort.list(any())).thenReturn(new AccountContactSnapshot(List.of(), NOW));

        AccountContactSyncResult result = service.syncNow(ACCOUNT, ContactSyncSource.MANUAL);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.contactNum()).isZero();
        // 空批次不能调 upsertBatch（foreach 会生成空 VALUES 导致语法错），但扫尾与归零必须发生
        verify(contactMapper, never()).upsertBatch(any());
        verify(contactMapper).deleteStale(ACCOUNT, NOW);
        verify(accountStateMapper).updateContactCounts(ACCOUNT, 0, 0, NOW);
    }

    @Test
    void protocolFailureLeavesExistingSnapshotUntouched() {
        when(contactListPort.list(any())).thenThrow(new ProtocolException(
                ProtocolErrorCode.UNSUPPORTED_BACKEND, "账号不在线"));

        AccountContactSyncResult result = service.syncNow(ACCOUNT, ContactSyncSource.TASK_START);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failReason()).contains("账号不在线");
        verify(contactMapper, never()).upsertBatch(any());
        verify(contactMapper, never()).deleteStale(anyLong(), anyLong());
        verify(accountStateMapper, never())
                .updateContactCounts(anyLong(), anyInt(), anyInt(), anyLong());

        ArgumentCaptor<AccountContactSync> saved = ArgumentCaptor.forClass(AccountContactSync.class);
        verify(syncMapper).upsert(saved.capture());
        assertThat(saved.getValue().getSyncStatus()).isEqualTo(AccountContactSync.STATUS_FAILED);
    }

    @Test
    void syncIfStaleSkipsWhenSnapshotIsFresh() {
        AccountContactSync existing = new AccountContactSync();
        existing.setAccountId(ACCOUNT);
        existing.setLastSyncedAt(NOW - HOUR);
        existing.setContactNum(7);
        existing.setNamedNum(5);
        existing.setMutualNum(0);
        when(syncMapper.selectByAccountId(ACCOUNT)).thenReturn(existing);

        AccountContactSyncResult result = service.syncIfStale(ACCOUNT, ContactSyncSource.TASK_START);

        assertThat(result.refreshed()).isFalse();
        assertThat(result.succeeded()).isTrue();
        assertThat(result.contactNum()).isEqualTo(7);
        assertThat(result.namedNum()).isEqualTo(5);
        verify(contactListPort, never()).list(any());
    }

    @Test
    void syncIfStaleRefetchesWhenSnapshotExpired() {
        AccountContactSync existing = new AccountContactSync();
        existing.setAccountId(ACCOUNT);
        existing.setLastSyncedAt(NOW - 25 * HOUR);
        when(syncMapper.selectByAccountId(ACCOUNT)).thenReturn(existing);
        when(contactListPort.list(any())).thenReturn(snapshot(2));

        AccountContactSyncResult result = service.syncIfStale(ACCOUNT, ContactSyncSource.TASK_START);

        assertThat(result.refreshed()).isTrue();
        assertThat(result.contactNum()).isEqualTo(2);
        verify(contactListPort).list(any());
    }

    @Test
    void syncIfStaleRefetchesWhenNeverSynced() {
        when(syncMapper.selectByAccountId(ACCOUNT)).thenReturn(null);
        when(contactListPort.list(any())).thenReturn(snapshot(1));

        AccountContactSyncResult result = service.syncIfStale(ACCOUNT, ContactSyncSource.ONLINE_EVENT);

        assertThat(result.refreshed()).isTrue();
        verify(contactListPort).list(any());
    }

    @Test
    void successRecordsSourceTenantAndClearsFailReason() {
        when(contactListPort.list(any())).thenReturn(snapshot(1));

        service.syncNow(ACCOUNT, ContactSyncSource.ONLINE_EVENT);

        ArgumentCaptor<AccountContactSync> saved = ArgumentCaptor.forClass(AccountContactSync.class);
        verify(syncMapper).upsert(saved.capture());
        assertThat(saved.getValue().getSyncStatus()).isEqualTo(AccountContactSync.STATUS_SUCCESS);
        assertThat(saved.getValue().getLastSyncSource()).isEqualTo("ONLINE_EVENT");
        assertThat(saved.getValue().getLastSyncedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getFailReason()).isNull();
        assertThat(saved.getValue().getTenantId()).isEqualTo(TENANT);
    }
}
