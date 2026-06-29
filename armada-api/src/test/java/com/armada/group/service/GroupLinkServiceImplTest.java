package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.Account;
import com.armada.group.converter.GroupConverter;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkLabelMapper;
import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.dto.GroupLinkPreviewDTO;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.entity.GroupLinkLabel;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.vo.GroupLinkPreviewBatchVO;
import com.armada.group.model.vo.GroupLinkVO;
import com.armada.group.model.vo.GroupLinkVoRow;
import com.armada.group.model.vo.GroupLinkMemberListVO;
import com.armada.group.model.vo.GroupMemberLookupTarget;
import com.armada.group.model.vo.GroupMemberQueryAccount;
import com.armada.group.service.impl.GroupLinkServiceImpl;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.model.result.GroupPreviewResult;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupPreviewPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * GroupLinkService 业务规则单测(mock mapper/converter,验证业务逻辑;SQL/真库另由 DbTest 覆盖)。
 */
@ExtendWith(MockitoExtension.class)
class GroupLinkServiceImplTest {

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private GroupLinkPreviewMapper previewMapper;

    @Mock
    private GroupLinkHealthMapper healthMapper;

    @Mock
    private GroupLinkLabelMapper labelMapper;

    @Mock
    private AccountGroupMembershipMapper membershipMapper;

    @Mock
    private GroupConverter converter;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private GroupPreviewPort groupPreviewPort;

    @Mock
    private GroupParticipantPort groupParticipantPort;

    private GroupLinkServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GroupLinkServiceImpl(
                groupLinkMapper, previewMapper, healthMapper, labelMapper, membershipMapper,
                converter, accountMapper, groupPreviewPort, groupParticipantPort);
    }

    // ---- listByLabel ----

    @Test
    void listByLabel_returnsEmptyPage_whenTotalZero() {
        GroupLinkQuery q = new GroupLinkQuery();
        q.setLabelId(1L);
        when(groupLinkMapper.countByLabel(q)).thenReturn(0L);

        PageResult<GroupLinkVO> result = service.listByLabel(q);

        assertThat(result.total()).isEqualTo(0L);
        assertThat(result.list()).isEmpty();
        verify(groupLinkMapper, never()).selectPageByLabel(any());
    }

    @Test
    void listByLabel_callsSelectPage_whenTotalNonZero() {
        GroupLinkQuery q = new GroupLinkQuery();
        q.setLabelId(1L);
        GroupLinkVoRow row = new GroupLinkVoRow();
        GroupLinkVO vo = new GroupLinkVO(
                1L, "https://chat.whatsapp.com/abc", "群A", null, null, "links.txt",
                "UNCHECKED", "未检测", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, 1000L);
        when(groupLinkMapper.countByLabel(q)).thenReturn(1L);
        when(groupLinkMapper.selectPageByLabel(q)).thenReturn(List.of(row));
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
        verify(groupLinkMapper, never()).countByLabel(any());
        verify(groupLinkMapper, never()).selectPageByLabel(any());
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

    // ---- members ----

    @Test
    void members_usesOnlineMembershipAccountAndReturnsRealtimeParticipants() {
        when(groupLinkMapper.selectMemberLookupTarget(10L))
                .thenReturn(new GroupMemberLookupTarget(10L, "120363members@g.us"));
        when(membershipMapper.selectOnlineMemberQueryAccount(10L, AccountLoginStateCode.ONLINE))
                .thenReturn(new GroupMemberQueryAccount(7L, "acc_861111"));
        when(groupParticipantPort.listParticipants("acc_861111", "120363members@g.us"))
                .thenReturn(List.of(
                        new GroupParticipantResult(
                                "8613800000000@s.whatsapp.net", "8613800000000", true, true, "superadmin"),
                        new GroupParticipantResult(
                                "8613900000000@s.whatsapp.net", "8613900000000", false, false, null)));

        GroupLinkMemberListVO result = service.members(10L);

        assertThat(result.groupLinkId()).isEqualTo(10L);
        assertThat(result.groupJid()).isEqualTo("120363members@g.us");
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.members()).hasSize(2);
        assertThat(result.members().get(0).jid()).isEqualTo("8613800000000@s.whatsapp.net");
        assertThat(result.members().get(0).phone()).isEqualTo("8613800000000");
        assertThat(result.members().get(0).admin()).isTrue();
        assertThat(result.members().get(0).owner()).isTrue();
        assertThat(result.members().get(0).role()).isEqualTo("superadmin");
        verify(groupParticipantPort).listParticipants("acc_861111", "120363members@g.us");
    }

    @Test
    void members_withoutOnlineMembershipAccount_throwsBusinessException() {
        when(groupLinkMapper.selectMemberLookupTarget(10L))
                .thenReturn(new GroupMemberLookupTarget(10L, "120363members@g.us"));
        when(membershipMapper.selectOnlineMemberQueryAccount(10L, AccountLoginStateCode.ONLINE))
                .thenReturn(null);

        assertThatThrownBy(() -> service.members(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂无可用在线账号");
        verify(groupParticipantPort, never()).listParticipants(any(), any());
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

        ArgumentCaptor<GroupLinkPreview> previewCaptor = ArgumentCaptor.forClass(GroupLinkPreview.class);
        verify(previewMapper).upsert(previewCaptor.capture());
        assertThat(previewCaptor.getValue().getGroupLinkId()).isEqualTo(10L);
        assertThat(previewCaptor.getValue().getGroupJid()).isEqualTo("120363preview@g.us");
        assertThat(previewCaptor.getValue().getWaSubject()).isEqualTo("预览群");
        assertThat(previewCaptor.getValue().getMemberSize()).isEqualTo(12);
        assertThat(previewCaptor.getValue().getOwnerPhone()).isEqualTo("8613999999999");
        assertThat(previewCaptor.getValue().getAnnounceOnly()).isTrue();

        ArgumentCaptor<GroupLinkHealth> healthCaptor = ArgumentCaptor.forClass(GroupLinkHealth.class);
        verify(healthMapper).upsert(healthCaptor.capture());
        assertThat(healthCaptor.getValue().getGroupLinkId()).isEqualTo(10L);
        assertThat(healthCaptor.getValue().getHealthStatus()).isEqualTo(1);
        assertThat(healthCaptor.getValue().getBanned()).isFalse();
        assertThat(healthCaptor.getValue().getCurrentCount()).isEqualTo(12);
        assertThat(healthCaptor.getValue().getLastHealthError()).isNull();
        assertThat(healthCaptor.getValue().getHealthFailureCount()).isZero();
    }
}
