package com.armada.account.contact;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountStatusAudienceMapper;
import com.armada.account.contact.model.StatusAudienceResolution;
import com.armada.account.contact.model.StatusAudienceView;
import com.armada.account.contact.model.entity.AccountStatusAudience;
import com.armada.account.contact.service.CloudStatusAudienceService;
import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.account.service.impl.AccountMessagingAudienceServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 私聊准备本任务的新云端名单，不经过动态的具名联系人优先或隐私筛选规则。 */
class ContactCloudAudienceResolutionTest {
    private final AccountContactMapper contacts = mock(AccountContactMapper.class);
    private final AccountStatusAudienceMapper snapshots = mock(AccountStatusAudienceMapper.class);
    private final CloudStatusAudienceService cloud = mock(CloudStatusAudienceService.class);
    private final SelectedAccount account = new SelectedAccount(20L, "100000", "ANDROID", "account");
    private final AccountMessagingAudienceServiceImpl service = new AccountMessagingAudienceServiceImpl(
            mock(AccountFilterSelectionMapper.class), contacts, snapshots, cloud);

    @Test
    void androidUsesCloudLidsWithoutRequiringNamedContacts() {
        var ready = new StatusAudienceResolution(new StatusAudienceView("READY", "CLOUD_LID", 1, 900L,
                null, null), List.of("123456@lid"));
        when(cloud.resolve(account, false)).thenReturn(ready);

        assertThat(service.resolveContactAudience(account, 1L)).isEqualTo(ready);
        verifyNoInteractions(contacts);
    }

    @Test
    void newTaskCanRefreshFailureFromBeforeItsCreation() {
        when(snapshots.selectByAccountId(20L)).thenReturn(failed(100L));
        service.resolveContactAudience(account, 200L);
        verify(cloud).resolve(account, true);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void newTaskRefreshesCompletedAudienceFromBeforeItsCreation(int contactNum) {
        when(snapshots.selectByAccountId(20L)).thenReturn(complete(100L, contactNum));
        service.resolveContactAudience(account, 200L);
        verify(cloud).resolve(account, true);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void currentTaskReusesItsCompletedAudience(int contactNum) {
        when(snapshots.selectByAccountId(20L)).thenReturn(complete(200L, contactNum));
        service.resolveContactAudience(account, 200L);
        verify(cloud).resolve(account, false);
    }

    @Test
    void newTaskSharesAnUnexpiredCollectionAlreadyInProgress() {
        var row = new AccountStatusAudience();
        row.setSyncStatus(AccountStatusAudience.SYNCING);
        row.setLeaseUntil(Long.MAX_VALUE);
        row.setUpdatedAt(100L);
        when(snapshots.selectByAccountId(20L)).thenReturn(row);
        service.resolveContactAudience(account, 200L);
        verify(cloud).resolve(account, false);
    }

    @Test
    void currentTaskFailureIsNotAutomaticallyRetried() {
        when(snapshots.selectByAccountId(20L)).thenReturn(failed(200L));
        service.resolveContactAudience(account, 100L);
        verify(cloud).resolve(account, false);
    }

    @Test
    void webCannotAccidentallyStartAndroidCollection() {
        var result = service.resolveContactAudience(new SelectedAccount(20L, "100000", "web", "account"), 1L);
        assertThat(result.view().status()).isEqualTo("FAILED");
        assertThat(result.jids()).isEmpty();
        verifyNoInteractions(cloud, contacts, snapshots);
    }

    private static AccountStatusAudience failed(long updatedAt) {
        var row = new AccountStatusAudience();
        row.setSyncStatus(AccountStatusAudience.FAILED);
        row.setUpdatedAt(updatedAt);
        return row;
    }

    private static AccountStatusAudience complete(long updatedAt, int contactNum) {
        var row = new AccountStatusAudience();
        row.setSyncStatus(AccountStatusAudience.COMPLETE);
        row.setUpdatedAt(updatedAt);
        row.setExpiresAt(Long.MAX_VALUE);
        row.setContactNum(contactNum);
        return row;
    }
}
