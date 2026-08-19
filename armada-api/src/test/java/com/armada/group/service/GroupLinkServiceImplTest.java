package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.Account;
import com.armada.group.converter.GroupConverter;
import com.armada.group.mapper.GroupFolderMapper;
import com.armada.group.mapper.GroupLinkLabelMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.mapper.GroupListCurrentMapper;
import com.armada.group.model.dto.GroupAnnouncementTextCommandDTO;
import com.armada.group.model.dto.GroupCurrentLocalProfileWrite;
import com.armada.group.model.dto.GroupDescriptionCommandDTO;
import com.armada.group.model.dto.GroupLinkProfileDTO;
import com.armada.group.model.dto.GroupLinkPreviewDTO;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.dto.GroupPictureCommandDTO;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupFolder;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.vo.GroupLinkPreviewBatchVO;
import com.armada.group.model.vo.GroupCurrentIdentity;
import com.armada.group.model.vo.GroupLinkVO;
import com.armada.group.model.vo.GroupLinkVoRow;
import com.armada.group.service.impl.GroupLinkServiceImpl;
import com.armada.group.service.impl.GroupCurrentLocalPersistence;
import com.armada.group.service.impl.GroupCurrentInvitePersistence;
import com.armada.platform.protocol.model.result.GroupPreviewResult;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.port.GroupProfilePort;
import com.armada.platform.protocol.port.GroupPreviewPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import com.armada.shared.tenant.TenantContext;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * GroupLinkService 业务规则单测(mock mapper/converter,验证业务逻辑;SQL/真库另由 DbTest 覆盖)。
 */
@ExtendWith(MockitoExtension.class)
class GroupLinkServiceImplTest {

    private static final Long TENANT_ID = 7L;

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private GroupListCurrentMapper groupListCurrentMapper;

    @Mock
    private GroupFolderMapper folderMapper;

    @Mock
    private GroupLinkPreviewMapper previewMapper;

    @Mock
    private GroupLinkLabelMapper labelMapper;

    @Mock
    private GroupConverter converter;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private GroupPreviewPort groupPreviewPort;

    @Mock
    private GroupProfilePort groupProfilePort;

    private GroupLinkServiceImpl service;

    @Mock
    private GroupCurrentLocalPersistence currentLocalPersistence;

    @Mock
    private GroupCurrentInvitePersistence currentInvitePersistence;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
        service = new GroupLinkServiceImpl(
                groupLinkMapper, groupListCurrentMapper, folderMapper, previewMapper, labelMapper,
                converter, accountMapper, groupPreviewPort, groupProfilePort,
                currentLocalPersistence, currentInvitePersistence);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void findWhatsAppGroupNamesByIdsDeduplicatesIdsAndIgnoresBlankSubjects() {
        GroupLinkVoRow named = new GroupLinkVoRow();
        named.setId(11L);
        named.setWaSubject("WhatsApp 真实群名");
        GroupLinkVoRow blank = new GroupLinkVoRow();
        blank.setId(12L);
        blank.setWaSubject("   ");
        GroupLinkVoRow missing = new GroupLinkVoRow();
        missing.setId(13L);
        when(groupListCurrentMapper.selectWhatsAppGroupNames(
                TENANT_ID, List.of(11L, 12L, 13L)))
                .thenReturn(List.of(named, blank, missing));

        Map<Long, String> result = service.findWhatsAppGroupNamesByIds(
                Arrays.asList(11L, null, 11L, 12L, 13L));

        assertThat(result).containsExactly(Map.entry(11L, "WhatsApp 真实群名"));
        verify(groupListCurrentMapper).selectWhatsAppGroupNames(
                TENANT_ID, List.of(11L, 12L, 13L));
    }

    @Test
    void findWhatsAppGroupNamesByIdsReturnsEmptyMapWithoutQueryForEmptyInput() {
        assertThat(service.findWhatsAppGroupNamesByIds(null)).isEmpty();
        assertThat(service.findWhatsAppGroupNamesByIds(List.of())).isEmpty();

        verifyNoInteractions(groupListCurrentMapper);
    }

    // ---- listByLabel ----

    @Test
    void listByLabel_returnsEmptyPage_whenTotalZero() {
        GroupLinkQuery q = new GroupLinkQuery();
        q.setLabelId(1L);
        when(groupListCurrentMapper.count(TENANT_ID, q)).thenReturn(0L);

        PageResult<GroupLinkVO> result = service.listByLabel(q);

        assertThat(result.total()).isEqualTo(0L);
        assertThat(result.list()).isEmpty();
        verify(groupListCurrentMapper, never()).selectPage(anyLong(), any());
    }

    @Test
    void listByLabel_usesPageProjectionWithoutProtocolAggregationQuery() {
        GroupLinkQuery query = new GroupLinkQuery();
        GroupLinkVoRow row = new GroupLinkVoRow();
        row.setId(11L);
        row.setSyncProtocolMask(3);
        when(groupListCurrentMapper.count(TENANT_ID, query)).thenReturn(1L);
        when(groupListCurrentMapper.selectPage(TENANT_ID, query)).thenReturn(List.of(row));
        when(converter.toGroupLinkVOList(List.of(row))).thenReturn(List.of());

        service.listByLabel(query);

        verify(groupListCurrentMapper).count(TENANT_ID, query);
        verify(groupListCurrentMapper).selectPage(TENANT_ID, query);
        verifyNoMoreInteractions(groupLinkMapper);
        verify(converter).toGroupLinkVOList(List.of(row));
    }

    @Test
    void listByLabel_callsSelectPage_whenTotalNonZero() {
        GroupLinkQuery q = new GroupLinkQuery();
        q.setLabelId(1L);
        GroupLinkVoRow row = new GroupLinkVoRow();
        GroupLinkVO vo = new GroupLinkVO(
                1L, "https://chat.whatsapp.com/abc", "群A", null, null, "links.txt",
                "UNCHECKED", "未检测", null, null, null, null,
                3, null, null, null, null, null, null, null, null, null, null, 1000L,
                false, false, null, null, null, List.of(), false, 0,
                null, null, null, null, null, null, null, null, null);
        when(groupListCurrentMapper.count(TENANT_ID, q)).thenReturn(1L);
        when(groupListCurrentMapper.selectPage(TENANT_ID, q)).thenReturn(List.of(row));
        when(converter.toGroupLinkVOList(List.of(row))).thenReturn(List.of(vo));

        PageResult<GroupLinkVO> result = service.listByLabel(q);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.list()).hasSize(1);
        assertThat(result.list().get(0).url()).isEqualTo("https://chat.whatsapp.com/abc");
    }

    @Test
    void listByLabel_invalidStatus_throwsValidationAndSkipsMapper() {
        GroupLinkQuery q = new GroupLinkQuery();
        q.setStatus("AVAILBLE");

        assertThatThrownBy(() -> service.listByLabel(q))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("status");
        verify(groupListCurrentMapper, never()).count(any(), any());
        verify(groupListCurrentMapper, never()).selectPage(any(), any());
    }

    @Test
    void listByLabelRejectsFolderIdTogetherWithWithoutFolder() {
        GroupLinkQuery query = new GroupLinkQuery();
        query.setFolderId(10L);
        query.setWithoutFolder(true);

        assertThatThrownBy(() -> service.listByLabel(query))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("folderId 与 withoutFolder 不能同时使用");
        verify(groupListCurrentMapper, never()).count(any(), any());
    }

    @Test
    void listByLabel_normalizesLocationAndUsesOneQueryClock() {
        GroupLinkQuery query = new GroupLinkQuery();
        query.setCountryIso2(" in ");
        query.setContinentCode(" asia ");
        when(groupListCurrentMapper.count(TENANT_ID, query)).thenReturn(0L);

        service.listByLabel(query);

        assertThat(query.getCountryIso2()).isEqualTo("IN");
        assertThat(query.getContinentCode()).isEqualTo("ASIA");
        assertThat(query.getNowSeconds()).isPositive();
        verify(groupListCurrentMapper).count(TENANT_ID, query);
    }

    @Test
    void listByLabel_acceptsAntarcticaAsSeventhContinent() {
        GroupLinkQuery query = new GroupLinkQuery();
        query.setContinentCode(" antarctica ");
        when(groupListCurrentMapper.count(TENANT_ID, query)).thenReturn(0L);

        service.listByLabel(query);

        assertThat(query.getContinentCode()).isEqualTo("ANTARCTICA");
        verify(groupListCurrentMapper).count(TENANT_ID, query);
    }

    @Test
    void listByLabel_rejectsInvalidRangesBeforeMapper() {
        GroupLinkQuery query = new GroupLinkQuery();
        query.setMemberCountMin(20);
        query.setMemberCountMax(10);

        assertThatThrownBy(() -> service.listByLabel(query))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("成员数");
        verify(groupListCurrentMapper, never()).count(any(), any());
    }

    @Test
    void assignFolderLocksFolderAndAllGroupsThenUpdates() {
        GroupFolder folder = new GroupFolder();
        folder.setId(10L);
        GroupLink first = new GroupLink();
        first.setId(101L);
        GroupLink second = new GroupLink();
        second.setId(102L);
        when(folderMapper.selectActiveByIdsForUpdate(List.of(10L))).thenReturn(List.of(folder));
        when(groupLinkMapper.selectActiveByIdsForUpdate(List.of(101L, 102L)))
                .thenReturn(List.of(first, second));
        when(groupLinkMapper.assignFolder(eq(List.of(101L, 102L)), eq(10L), anyLong()))
                .thenReturn(2);

        int updated = service.assignFolder(List.of(102L, 101L, 101L), 10L);

        assertThat(updated).isEqualTo(2);
        InOrder order = inOrder(folderMapper, groupLinkMapper);
        order.verify(folderMapper).selectActiveByIdsForUpdate(List.of(10L));
        order.verify(groupLinkMapper).selectActiveByIdsForUpdate(List.of(101L, 102L));
        order.verify(groupLinkMapper).assignFolder(eq(List.of(101L, 102L)), eq(10L), anyLong());
    }

    @Test
    void assignFolderAllowsUnassignedAndRejectsMissingGroups() {
        GroupLink first = new GroupLink();
        first.setId(101L);
        when(groupLinkMapper.selectActiveByIdsForUpdate(List.of(101L, 102L)))
                .thenReturn(List.of(first));

        assertThatThrownBy(() -> service.assignFolder(List.of(101L, 102L), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("部分群组不存在");
        verify(folderMapper, never()).selectActiveByIdsForUpdate(any());
        verify(groupLinkMapper, never()).assignFolder(any(), any(), anyLong());
    }

    @Test
    void assignFolderRejectsMissingTargetFolder() {
        when(folderMapper.selectActiveByIdsForUpdate(List.of(10L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.assignFolder(List.of(101L), 10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标群组分组不存在");
        verify(groupLinkMapper, never()).selectActiveByIdsForUpdate(any());
    }

    @Test
    void listByLabel_rejectsUnknownContinentAndMalformedCountry() {
        GroupLinkQuery query = new GroupLinkQuery();
        query.setContinentCode("ATLANTIS");
        query.setCountryIso2("IND");

        assertThatThrownBy(() -> service.listByLabel(query))
                .isInstanceOf(BusinessException.class);

        verify(groupListCurrentMapper, never()).count(any(), any());
    }

    // ---- updateProfile ----

    @Test
    void updateProfile_trimsAndPersistsLocalProfileAndAvatar() {
        GroupLink link = new GroupLink();
        link.setId(10L);
        link.setGroupName("旧群名");
        link.setRemark("旧备注");
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(link);
        when(groupLinkMapper.updateProfile(eq(10L), eq("运营群A"), eq("重点客户"), anyLong()))
                .thenReturn(1);

        service.updateProfile(10L, new GroupLinkProfileDTO(
                " 运营群A ",
                " 重点客户 ",
                " https://cdn.example.test/group-a.jpg "));

        verify(groupLinkMapper).updateProfile(eq(10L), eq("运营群A"), eq("重点客户"), anyLong());
        verify(currentLocalPersistence).applyProfile(any(GroupCurrentLocalProfileWrite.class));
    }

    @Test
    void updateProfile_onlyAvatarKeepsExistingNameAndRemark() {
        GroupLink link = new GroupLink();
        link.setId(10L);
        link.setGroupName("旧群名");
        link.setRemark("旧备注");
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(link);
        when(groupLinkMapper.updateProfile(eq(10L), eq("旧群名"), eq("旧备注"), anyLong()))
                .thenReturn(1);

        service.updateProfile(10L, new GroupLinkProfileDTO(
                null,
                null,
                "https://cdn.example.test/group-a.jpg"));

        verify(groupLinkMapper).updateProfile(eq(10L), eq("旧群名"), eq("旧备注"), anyLong());
        verify(currentLocalPersistence).applyProfile(any(GroupCurrentLocalProfileWrite.class));
    }

    @Test
    void updateProfile_emptyPayloadThrowsValidationAndSkipsMapper() {
        assertThatThrownBy(() -> service.updateProfile(10L, new GroupLinkProfileDTO(null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少提交一个字段");

        verify(groupLinkMapper, never()).selectActiveById(anyLong());
        verify(groupLinkMapper, never()).updateProfile(anyLong(), any(), any(), anyLong());
        verifyNoInteractions(currentLocalPersistence);
    }

    @Test
    void updateProfile_missingLinkThrowsNotFoundAndSkipsUpdates() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(null);

        assertThatThrownBy(() -> service.updateProfile(10L, new GroupLinkProfileDTO("运营群A", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("群链接不存在或已删除");

        verify(groupLinkMapper, never()).updateProfile(anyLong(), any(), any(), anyLong());
        verifyNoInteractions(currentLocalPersistence);
    }

    // ---- remote group profile commands ----

    @Test
    void updateDescription_callsProtocolWithoutLocalProfileUpdate() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", "备注"));
        when(groupLinkMapper.selectCurrentIdentity(10L)).thenReturn(
                new GroupCurrentIdentity(10L, "120363profile@g.us", null));
        when(accountMapper.selectActiveById(7L)).thenReturn(account(7L, "acc_7"));
        when(accountMapper.selectOnlineAccountIdsByIds(List.of(7L), AccountLoginStateCode.ONLINE))
                .thenReturn(List.of(7L));

        service.updateDescription(10L, new GroupDescriptionCommandDTO(7L, " 群描述 "));

        verify(groupProfilePort).updateDescription(accountRef(), "120363profile@g.us", "群描述");
        verify(groupLinkMapper, never()).updateProfile(anyLong(), any(), any(), anyLong());
        verifyNoInteractions(currentLocalPersistence);
    }

    @Test
    void updateAnnouncementText_callsProtocolWithoutLocalProfileUpdate() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", "备注"));
        when(groupLinkMapper.selectCurrentIdentity(10L)).thenReturn(
                new GroupCurrentIdentity(10L, "120363profile@g.us", null));
        when(accountMapper.selectActiveById(7L)).thenReturn(account(7L, "acc_7"));
        when(accountMapper.selectOnlineAccountIdsByIds(List.of(7L), AccountLoginStateCode.ONLINE))
                .thenReturn(List.of(7L));

        service.updateAnnouncementText(10L, new GroupAnnouncementTextCommandDTO(7L, " 群公告 "));

        verify(groupProfilePort).updateAnnouncementText(accountRef(), "120363profile@g.us", "群公告");
        verify(groupLinkMapper, never()).updateProfile(anyLong(), any(), any(), anyLong());
        verifyNoInteractions(currentLocalPersistence);
    }

    @Test
    void updatePicture_callsProtocolAndPersistsAvatarUrl() {
        when(groupLinkMapper.selectActiveById(10L)).thenReturn(activeLink(10L, "群名", "备注"));
        when(groupLinkMapper.selectCurrentIdentity(10L)).thenReturn(
                new GroupCurrentIdentity(10L, "120363profile@g.us", null));
        when(accountMapper.selectActiveById(7L)).thenReturn(account(7L, "acc_7"));
        when(accountMapper.selectOnlineAccountIdsByIds(List.of(7L), AccountLoginStateCode.ONLINE))
                .thenReturn(List.of(7L));

        service.updatePicture(10L, new GroupPictureCommandDTO(
                7L, " https://cdn.example.test/group.jpg ", null));

        verify(groupProfilePort).updatePicture(accountRef(), "120363profile@g.us",
                "https://cdn.example.test/group.jpg", null);
        ArgumentCaptor<GroupCurrentLocalProfileWrite> currentWrite =
                ArgumentCaptor.forClass(GroupCurrentLocalProfileWrite.class);
        verify(currentLocalPersistence).applyProfile(currentWrite.capture());
        assertThat(currentWrite.getValue()).satisfies(row -> {
            assertThat(row.groupLinkId()).isEqualTo(10L);
            assertThat(row.avatarUrl()).isEqualTo("https://cdn.example.test/group.jpg");
            assertThat(row.avatarObserved()).isTrue();
            assertThat(row.displayNameObserved()).isFalse();
            assertThat(row.remarkObserved()).isFalse();
        });
    }

    // ---- migrate ----

    @Test
    void migrate_nullIds_throws() {
        assertThatThrownBy(() -> service.migrate(null, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1.." + 100);
    }

    @Test
    void migrate_emptyIds_throws() {
        assertThatThrownBy(() -> service.migrate(List.of(), 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void migrate_exceedsMax_throws() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();
        assertThatThrownBy(() -> service.migrate(ids, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void migrate_nullTargetLabelId_throws() {
        assertThatThrownBy(() -> service.migrate(List.of(1L), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标分组 ID 不能为空");
    }

    @Test
    void migrate_targetLabelNotFound_throws() {
        when(labelMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.migrate(List.of(1L, 2L), 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标分组不存在");
        verify(groupLinkMapper, never()).migrateToLabel(any(), any(), anyLong());
    }

    @Test
    void migrate_someLinksInactive_throws() {
        GroupLinkLabel label = new GroupLinkLabel();
        label.setId(5L);
        when(labelMapper.selectById(5L)).thenReturn(label);
        List<Long> ids = List.of(1L, 2L, 3L);
        when(groupLinkMapper.countActiveByIds(ids)).thenReturn(2); // 只有 2 个活跃,期望 3

        assertThatThrownBy(() -> service.migrate(ids, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("部分群链接不存在或已删除");
        verify(groupLinkMapper, never()).migrateToLabel(any(), any(), anyLong());
    }

    @Test
    void migrate_allActiveAndLabelExists_migrates() {
        GroupLinkLabel label = new GroupLinkLabel();
        label.setId(5L);
        when(labelMapper.selectById(5L)).thenReturn(label);
        List<Long> ids = List.of(1L, 2L);
        when(groupLinkMapper.countActiveByIds(ids)).thenReturn(2);
        when(groupLinkMapper.migrateToLabel(eq(ids), eq(5L), anyLong())).thenReturn(2);

        int result = service.migrate(ids, 5L);

        assertThat(result).isEqualTo(2);
        verify(groupLinkMapper).migrateToLabel(eq(ids), eq(5L), anyLong());
    }

    // ---- batchDelete ----

    @Test
    void batchDelete_nullIds_throws() {
        assertThatThrownBy(() -> service.batchDelete(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void batchDelete_emptyIds_throws() {
        assertThatThrownBy(() -> service.batchDelete(List.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void batchDelete_exceedsMax_throws() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();
        assertThatThrownBy(() -> service.batchDelete(ids))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void batchDelete_valid_softDeletes() {
        List<Long> ids = List.of(1L, 2L, 3L);
        when(groupLinkMapper.softDeleteByIds(eq(ids), anyLong())).thenReturn(3);

        int result = service.batchDelete(ids);

        assertThat(result).isEqualTo(3);
        verify(groupLinkMapper).softDeleteByIds(eq(ids), anyLong());
    }

    // ---- previewBatch ----

    @Test
    void previewBatch_resolvesProtocolAccountPreviewsLinksAndPersistsSuccessfulSnapshots() {
        Account account = new Account();
        account.setId(7L);
        account.setProtocolAccountId("acc_861111");
        when(accountMapper.selectActiveById(7L)).thenReturn(account);

        GroupLink link = new GroupLink();
        link.setId(10L);
        link.setLinkUrl("https://chat.whatsapp.com/ABC123");
        when(groupLinkMapper.selectActiveByIds(List.of(10L))).thenReturn(List.of(link));
        when(groupPreviewPort.preview("acc_861111", "https://chat.whatsapp.com/ABC123"))
                .thenReturn(new GroupPreviewResult(
                        "120363preview@g.us",
                        "预览群",
                        12,
                        false,
                        "8613999999999@s.whatsapp.net",
                        "hello",
                        true,
                        false,
                        "ABC123",
                        Instant.parse("2026-06-02T10:00:00Z")));

        GroupLinkPreviewBatchVO result = service.previewBatch(new GroupLinkPreviewDTO(7L, List.of(10L)));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).groupLinkId()).isEqualTo(10L);
        assertThat(result.items().get(0).success()).isTrue();
        assertThat(result.items().get(0).groupJid()).isEqualTo("120363preview@g.us");
        assertThat(result.items().get(0).ownerPhone()).isEqualTo("8613999999999");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupLinkPreview>> previewRows = ArgumentCaptor.forClass(List.class);
        verify(previewMapper).upsertCreatorCompatibility(previewRows.capture());
        GroupLinkPreview previewRow = previewRows.getValue().get(0);
        assertThat(previewRow.getGroupLinkId()).isEqualTo(10L);
        assertThat(previewRow.getOwnerPhone()).isEqualTo("8613999999999");
        assertThat(previewRow.getOwnerPhoneObserved()).isTrue();

        ArgumentCaptor<GroupLinkHealth> healthCaptor = ArgumentCaptor.forClass(GroupLinkHealth.class);
        verify(currentInvitePersistence).applyHealth(
                eq("120363preview@g.us"), healthCaptor.capture());
        assertThat(healthCaptor.getValue().getGroupLinkId()).isEqualTo(10L);
        assertThat(healthCaptor.getValue().getHealthStatus()).isEqualTo(1);
        assertThat(healthCaptor.getValue().getBanned()).isFalse();
        assertThat(healthCaptor.getValue().getCurrentCount()).isEqualTo(12);
        assertThat(healthCaptor.getValue().getLastHealthError()).isNull();
        assertThat(healthCaptor.getValue().getHealthFailureCount()).isZero();
    }

    @Test
    void previewBatch_clearsPhoneIntentForLidOwner() {
        OwnerPreviewOutcome outcome = previewOwner("193088878297313@lid");

        assertThat(outcome.responsePhone()).isNull();
        assertThat(outcome.persisted().getOwnerPhone()).isNull();
        assertThat(outcome.persisted().getOwnerPhoneObserved()).isTrue();
    }

    @Test
    void previewBatch_preservesPhoneIntentForUnknownOwner() {
        OwnerPreviewOutcome outcome = previewOwner("193088878297313");

        assertThat(outcome.responsePhone()).isNull();
        assertThat(outcome.persisted()).isNull();
    }

    private OwnerPreviewOutcome previewOwner(String ownerJid) {
        Account account = new Account();
        account.setId(7L);
        account.setProtocolAccountId("acc_owner_identity");
        when(accountMapper.selectActiveById(7L)).thenReturn(account);

        GroupLink link = new GroupLink();
        link.setId(10L);
        link.setLinkUrl("https://chat.whatsapp.com/OwnerIdentity");
        when(groupLinkMapper.selectActiveByIds(List.of(10L))).thenReturn(List.of(link));
        when(groupPreviewPort.preview("acc_owner_identity", link.getLinkUrl()))
                .thenReturn(new GroupPreviewResult(
                        "120363-owner@g.us",
                        "群主身份群",
                        12,
                        false,
                        ownerJid,
                        null,
                        false,
                        false,
                        "OwnerIdentity",
                        Instant.parse("2026-07-31T08:00:00Z")));

        GroupLinkPreviewBatchVO result = service.previewBatch(
                new GroupLinkPreviewDTO(7L, List.of(10L)));
        if (!ownerJid.endsWith("@lid") && !ownerJid.endsWith("@s.whatsapp.net")) {
            verify(previewMapper, never()).upsertCreatorCompatibility(any());
            return new OwnerPreviewOutcome(result.items().get(0).ownerPhone(), null);
        }
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupLinkPreview>> rows = ArgumentCaptor.forClass(List.class);
        verify(previewMapper).upsertCreatorCompatibility(rows.capture());
        return new OwnerPreviewOutcome(
                result.items().get(0).ownerPhone(),
                rows.getValue().get(0));
    }

    private record OwnerPreviewOutcome(String responsePhone, GroupLinkPreview persisted) {
    }

    private static GroupLink activeLink(Long id, String groupName, String remark) {
        GroupLink link = new GroupLink();
        link.setId(id);
        link.setGroupName(groupName);
        link.setRemark(remark);
        return link;
    }

    private static GroupLinkPreview preview(String groupJid) {
        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setGroupJid(groupJid);
        return preview;
    }

    private static Account account(Long id, String protocolAccountId) {
        Account account = new Account();
        account.setId(id);
        account.setProtocolAccountId(protocolAccountId);
        account.setWsPhone("919000000001");
        return account;
    }

    private static ProtocolAccountRef accountRef() {
        return new ProtocolAccountRef(7L, null, "acc_7", "919000000001");
    }
}
