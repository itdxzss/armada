package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.observability.GroupMetadataSyncMetrics;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupMetadataSnapshotPersistence;
import com.armada.group.service.GroupMetadataSyncProtocolPorts;
import com.armada.platform.country.model.vo.CountryReferenceVO;
import com.armada.platform.country.service.CountryService;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupInvitePort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 单群 metadata 与完整成员快照执行器单测。 */
@ExtendWith(MockitoExtension.class)
class GroupMetadataSnapshotServiceImplTest {

    @Mock
    private FixedAccountGroupMetadataPort metadataPort;

    @Mock
    private GroupInvitePort invitePort;

    @Mock
    private GroupMetadataSnapshotPersistence persistence;

    @Mock
    private GroupExecutionAccountSelector selector;

    @Mock
    private CountryService countryService;

    @Test
    void administratorPersistsCompleteMetadataInviteGeoAndOwnerFirstSnapshot() {
        GroupMetadataSyncTask task = task();
        GroupExecutionAccount account = new GroupExecutionAccount(
                77L, "WEB", "acc_77", "8613800000000", true);
        when(metadataPort.getMetadata(account.protocolRef(), task.getGroupJid()))
                .thenReturn(metadata(1_722_470_400L, true));
        when(selector.findAdminByPhones(
                task.getGroupLinkId(), List.of("8613800000000"), 0))
                .thenReturn(Optional.of(account));
        when(invitePort.getInvite(account.protocolRef(), task.getGroupJid()))
                .thenReturn(new GroupInviteResult(task.getGroupJid(), "invite-code", "url"));
        when(countryService.resolveActiveCountriesByPhoneNumbers(List.of("8613800000000")))
                .thenReturn(Map.of("8613800000000",
                        new CountryReferenceVO(1L, "CN", "中国", "+86", "🇨🇳", "ASIA")));
        when(persistence.persist(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(true);

        service().execute(task, account);

        ArgumentCaptor<GroupLinkPreview> preview = ArgumentCaptor.forClass(GroupLinkPreview.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsappGroupMemberSnapshot>> members = ArgumentCaptor.forClass(List.class);
        verify(persistence).persist(preview.capture(), members.capture());
        assertThat(preview.getValue()).satisfies(row -> {
            assertThat(row.getGroupLinkId()).isEqualTo(10L);
            assertThat(row.getGroupJid()).isEqualTo("120363history@g.us");
            assertThat(row.getInviteCode()).isEqualTo("invite-code");
            assertThat(row.getWaDescription()).isEqualTo("群说明");
            assertThat(row.getOwnerPhone()).isEqualTo("8613800000000");
            assertThat(row.getGroupCreatedAt()).isEqualTo(1_722_470_400L);
            assertThat(row.getCreatorCountryIso2()).isEqualTo("CN");
            assertThat(row.getCreatorContinentCode()).isEqualTo("ASIA");
            assertThat(row.getMemberSize()).isEqualTo(2);
        });
        assertThat(members.getValue())
                .extracting(WhatsappGroupMemberSnapshot::getRole)
                .containsExactly("OWNER", "MEMBER");
        assertThat(members.getValue().get(0).getIsAdmin()).isTrue();
    }

    @Test
    void staleAdminFlagUsesFreshMetadataOwnerToReadInvite() {
        GroupMetadataSyncTask task = task();
        task.setAttemptCount(1);
        GroupExecutionAccount reader = account(false);
        GroupExecutionAccount freshOwner = new GroupExecutionAccount(
                78L, "WEB", "acc_owner", "8613800000000", false);
        when(metadataPort.getMetadata(reader.protocolRef(), task.getGroupJid()))
                .thenReturn(metadata(1_722_470_400L, true));
        when(selector.findAdminByPhones(
                task.getGroupLinkId(), List.of("8613800000000"), 0))
                .thenReturn(Optional.of(freshOwner));
        when(invitePort.getInvite(freshOwner.protocolRef(), task.getGroupJid()))
                .thenReturn(new GroupInviteResult(task.getGroupJid(), "fresh-invite", "url"));
        when(countryService.resolveActiveCountriesByPhoneNumbers(List.of("8613800000000")))
                .thenReturn(Map.of());
        when(persistence.persist(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(true);

        service().execute(task, reader);

        ArgumentCaptor<GroupLinkPreview> preview = ArgumentCaptor.forClass(GroupLinkPreview.class);
        verify(persistence).persist(preview.capture(), org.mockito.ArgumentMatchers.anyList());
        assertThat(preview.getValue().getInviteCode()).isEqualTo("fresh-invite");
    }

    @Test
    void ordinaryMemberDoesNotReadInviteAndFutureCreationRemainsUnknown() {
        GroupMetadataSyncTask task = task();
        GroupExecutionAccount account = account(false);
        when(metadataPort.getMetadata(account.protocolRef(), task.getGroupJid()))
                .thenReturn(metadata(Long.MAX_VALUE, true));
        when(countryService.resolveActiveCountriesByPhoneNumbers(List.of("8613800000000")))
                .thenReturn(Map.of());
        when(persistence.persist(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(true);

        service().execute(task, account);

        ArgumentCaptor<GroupLinkPreview> preview = ArgumentCaptor.forClass(GroupLinkPreview.class);
        verify(persistence).persist(preview.capture(), org.mockito.ArgumentMatchers.anyList());
        assertThat(preview.getValue().getGroupCreatedAt()).isNull();
        assertThat(preview.getValue().getInviteCode()).isNull();
        verifyNoInteractions(invitePort);
    }

    @Test
    void androidSelfBuiltGroupRetriesAfterInviteReadTemporarilyFails() {
        GroupMetadataSyncTask task = task();
        task.setInviteRequired(true);
        GroupExecutionAccount account = new GroupExecutionAccount(
                77L, "ANDROID", "acc_77", "8613800000000", true);
        when(metadataPort.getMetadata(account.protocolRef(), task.getGroupJid()))
                .thenReturn(metadata(1_722_470_400L, true));
        when(selector.findAdminByPhones(
                task.getGroupLinkId(), List.of("8613800000000"), 0))
                .thenReturn(Optional.of(account));
        when(invitePort.getInvite(account.protocolRef(), task.getGroupJid()))
                .thenThrow(new IllegalStateException("Android invite temporarily unavailable"));
        when(countryService.resolveActiveCountriesByPhoneNumbers(List.of("8613800000000")))
                .thenReturn(Map.of());
        when(persistence.persist(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(true);

        assertThatThrownBy(() -> service().execute(task, account))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("邀请码暂未取得");

        verify(persistence).persist(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void incompleteParticipantsRejectWholeSnapshotAndPreserveOldData() {
        GroupMetadataSyncTask task = task();
        GroupExecutionAccount account = account(true);
        when(metadataPort.getMetadata(account.protocolRef(), task.getGroupJid()))
                .thenReturn(metadata(null, false));

        assertThatThrownBy(() -> service().execute(task, account))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("成员快照不完整");

        verify(persistence, never()).persist(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
        verifyNoInteractions(invitePort);
    }

    private GroupMetadataSnapshotServiceImpl service() {
        return new GroupMetadataSnapshotServiceImpl(
                new GroupMetadataSyncProtocolPorts(metadataPort, invitePort),
                persistence,
                selector,
                countryService,
                new GroupMetadataSyncMetrics());
    }

    private static GroupMetadataSyncTask task() {
        GroupMetadataSyncTask task = new GroupMetadataSyncTask();
        task.setId(1L);
        task.setTenantId(7L);
        task.setGroupLinkId(10L);
        task.setGroupJid("120363history@g.us");
        return task;
    }

    private static GroupExecutionAccount account(boolean admin) {
        return new GroupExecutionAccount(77L, "WEB", "acc_77", "8613900000000", admin);
    }

    private static GroupMetadataResult metadata(Long creation, boolean complete) {
        return new GroupMetadataResult(
                "120363history@g.us",
                "历史群",
                "群说明",
                "8613800000000@s.whatsapp.net",
                creation,
                complete,
                false,
                true,
                true,
                false,
                604_800,
                null,
                false,
                null,
                false,
                true,
                List.of(
                        new GroupParticipantResult(
                                "8613800000000@s.whatsapp.net", "8613800000000", true, true, "superadmin"),
                        new GroupParticipantResult(
                                "8613900000000@s.whatsapp.net", "8613900000000", false, false, null)));
    }
}
