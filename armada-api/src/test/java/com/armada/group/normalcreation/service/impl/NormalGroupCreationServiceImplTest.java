package com.armada.group.normalcreation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemIdentity;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemInsert;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberInsert;
import com.armada.group.normalcreation.model.dto.NormalGroupCreationCreateDTO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskVO;
import com.armada.group.normalcreation.service.NormalGroupCreationEventPublisher;
import com.armada.group.normalcreation.support.NormalGroupCreationAdmissionGuard;
import com.armada.group.service.GroupFolderService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class NormalGroupCreationServiceImplTest {

    @Mock private AccountGroupService accountGroupService;
    @Mock private AccountProtocolLookupService accountLookupService;
    @Mock private GroupFolderService groupFolderService;
    @Mock private NormalGroupCreationMapper mapper;
    @Mock private NormalGroupCreationEventPublisher publisher;
    @Mock private NormalGroupCreationAdmissionGuard admissionGuard;

    private NormalGroupCreationServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        service = new NormalGroupCreationServiceImpl(
                accountGroupService, accountLookupService, groupFolderService,
                mapper, publisher, admissionGuard);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createFreezesUniqueCreatorsAndAllowsMembersToBeReusedAcrossGroups() {
        when(accountLookupService.findOnlineNormalByGroupId(10L)).thenReturn(List.of(
                account(2L, ProtocolBackend.ANDROID), account(1L, ProtocolBackend.WEB)));
        when(accountLookupService.findOnlineNormalByGroupId(20L)).thenReturn(List.of(
                account(3L, ProtocolBackend.WEB), account(4L, ProtocolBackend.ANDROID)));
        when(mapper.insertTask(any())).thenReturn(1);
        when(mapper.selectTaskIdByIdempotencyKey("request-1")).thenReturn(null, 99L);
        when(mapper.selectItemIdentities(99L)).thenReturn(List.of(
                new ItemIdentity(101L, 1), new ItemIdentity(102L, 2)));
        when(mapper.selectTask(99L)).thenReturn(
                new NormalGroupCreationTaskVO(99L, "PENDING", 2, 0, 0, 1L, 1L));

        NormalGroupCreationTaskVO result = service.create(
                "request-1", request(2, 2), 8L);

        assertThat(result.id()).isEqualTo(99L);
        ArgumentCaptor<List<ItemInsert>> itemRows = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertItems(itemRows.capture());
        assertThat(itemRows.getValue()).hasSize(2);
        assertThat(itemRows.getValue()).extracting(ItemInsert::creatorAccountId)
                .containsExactly(1L, 2L);
        ArgumentCaptor<List<MemberInsert>> memberRows = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertMembers(memberRows.capture());
        assertThat(memberRows.getValue()).hasSize(4);
        assertThat(memberRows.getValue().stream()
                .filter(row -> row.itemId().equals(101L))
                .map(MemberInsert::memberAccountId)
                .toList()).doesNotHaveDuplicates();
        assertThat(memberRows.getValue().stream()
                .filter(row -> row.itemId().equals(102L))
                .map(MemberInsert::memberAccountId)
                .toList()).doesNotHaveDuplicates();
        InOrder admissionOrder = Mockito.inOrder(admissionGuard, accountLookupService);
        admissionOrder.verify(admissionGuard).checkRate(7L, 8L);
        admissionOrder.verify(accountLookupService).findOnlineNormalByGroupId(10L);
        admissionOrder.verify(accountLookupService).findOnlineNormalByGroupId(20L);
        admissionOrder.verify(admissionGuard).lockAndCheckCapacity(7L, 2);
        verify(publisher, times(2)).publish(
                org.mockito.ArgumentMatchers.eq("PREPARE"),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(99L), anyLong(), anyLong());
    }

    @Test
    void createRejectsWhenManagerGroupHasFewerOnlineAccountsThanGroups() {
        when(mapper.selectTaskIdByIdempotencyKey("request-2")).thenReturn(null);
        when(accountLookupService.findOnlineNormalByGroupId(10L))
                .thenReturn(List.of(account(1L, ProtocolBackend.WEB)));
        when(accountLookupService.findOnlineNormalByGroupId(20L))
                .thenReturn(List.of(account(3L, ProtocolBackend.WEB)));

        assertThatThrownBy(() -> service.create("request-2", request(2, 1), 8L))
                .hasMessageContaining("管理员分组可用在线账号不足");
        verify(mapper, never()).insertTask(any());
        verify(admissionGuard, never()).lockAndCheckCapacity(anyLong(), anyInt());
    }

    @Test
    void createReturnsConcurrentIdempotentTaskWithoutCreatingDuplicateItems() {
        when(accountLookupService.findOnlineNormalByGroupId(10L))
                .thenReturn(List.of(account(1L, ProtocolBackend.WEB)));
        when(accountLookupService.findOnlineNormalByGroupId(20L))
                .thenReturn(List.of(account(3L, ProtocolBackend.WEB)));
        when(mapper.selectTaskIdByIdempotencyKey("request-race"))
                .thenReturn(null);
        when(mapper.selectTaskIdByIdempotencyKeyForUpdate("request-race"))
                .thenReturn(88L);
        when(mapper.insertTask(any())).thenThrow(new DuplicateKeyException("duplicate"));
        when(mapper.selectTask(88L)).thenReturn(
                new NormalGroupCreationTaskVO(88L, "PENDING", 1, 0, 0, 1L, 1L));

        NormalGroupCreationTaskVO result = service.create(
                "request-race", request(1, 1), 8L);

        assertThat(result.id()).isEqualTo(88L);
        verify(mapper, never()).insertItems(any());
        verify(publisher, never()).publish(any(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void createRejectsWhenFrozenMemberSnapshotExceedsTaskLimit() {
        when(mapper.selectTaskIdByIdempotencyKey("request-too-large")).thenReturn(null);

        assertThatThrownBy(() -> service.create(
                "request-too-large", request(1_000, 11), 8L))
                .hasMessageContaining("超过单任务上限 10000");

        verify(accountLookupService, never()).findOnlineNormalByGroupId(anyLong());
        verify(mapper, never()).insertTask(any());
    }

    @Test
    void retryRejectsUnknownCreateResult() {
        when(mapper.selectItemWork(101L)).thenReturn(new ItemWork(
                101L, 7L, 99L, "群-1", 1L, "acc_1", "WEB", "10001",
                null, "RESULT_UNKNOWN", "CREATING_GROUP", "NONE", "KEEP", null,
                null, null, true, false, true, false, 0));

        assertThatThrownBy(() -> service.retry(99L, 101L, 8L))
                .hasMessageContaining("必须先完成对账");
        verify(mapper, never()).resetItemForRetry(anyLong(), anyLong(), anyLong());
    }

    private static NormalGroupCreationCreateDTO request(int groupCount, int memberCount) {
        return new NormalGroupCreationCreateDTO(
                10L, "KEEP", "CONTROLLED_GROUP", 20L, memberCount,
                null, "测试群-{no}", groupCount, 1, "NORMAL", null, null, null);
    }

    private static ProtocolAccountRef account(Long id, ProtocolBackend backend) {
        return new ProtocolAccountRef(id, backend, "acc_" + id, "1000" + id);
    }
}
