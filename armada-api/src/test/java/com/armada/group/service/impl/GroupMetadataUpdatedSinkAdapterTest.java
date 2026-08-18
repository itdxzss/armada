package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.armada.group.model.dto.GroupMetadataPatch;
import com.armada.group.model.dto.GroupMetadataPatchField;
import com.armada.group.model.enums.GroupMetadataFieldSource;
import com.armada.group.service.GroupMetadataPatchService;
import com.armada.platform.kafka.consumer.group.ProtocolGroupMetadataUpdatedEvent;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 锁定协议 fieldMask 到业务白名单的映射行为。
 *
 * <p>白名单过滤放在 group 域而非 platform 层：未识别的字段名跳过即可，不能阻塞同一事件里已识别
 * 的字段，也不能因此拒绝整条事件（群变更事件直投影设计 §10）。</p>
 */
@ExtendWith(MockitoExtension.class)
class GroupMetadataUpdatedSinkAdapterTest {

    @Mock
    private GroupMetadataPatchService patchService;

    @InjectMocks
    private GroupMetadataUpdatedSinkAdapter adapter;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void unknownFieldsAreSkippedWithoutBlockingRecognizedOnes() {
        adapter.handleMetadataUpdated(event(List.of("subject", "avatarUrl", "announceOnly")));

        GroupMetadataPatch patch = captured();
        assertThat(patch.fieldMask())
                .as("未识别字段跳过，已识别字段仍须写入")
                .containsExactlyInAnyOrder(
                        GroupMetadataPatchField.SUBJECT,
                        GroupMetadataPatchField.ANNOUNCE_ONLY);
    }

    @Test
    void allUnknownFieldsMeanConfirmedWithoutWrite() {
        adapter.handleMetadataUpdated(event(List.of("avatarUrl", "memberCount")));

        verify(patchService, never()).applyPatch(any());
    }

    @Test
    void wireNamesMapToWhitelistCaseInsensitively() {
        adapter.handleMetadataUpdated(event(List.of(
                "adminOnlyEditInfo", "MEMBERADDMODE", "joinApprovalMode",
                "ephemeralDurationSeconds", "description")));

        assertThat(captured().fieldMask()).containsExactlyInAnyOrder(
                GroupMetadataPatchField.ADMIN_ONLY_EDIT_INFO,
                GroupMetadataPatchField.MEMBER_ADD_MODE,
                GroupMetadataPatchField.JOIN_APPROVAL_MODE,
                GroupMetadataPatchField.EPHEMERAL_DURATION_SECONDS,
                GroupMetadataPatchField.DESCRIPTION);
    }

    @Test
    void patchCarriesMetadataEventSourceAndEventOccurredAt() {
        adapter.handleMetadataUpdated(event(List.of("subject")));

        GroupMetadataPatch patch = captured();
        assertThat(patch.source())
                .as("精确字段事件的可信度必须高于完整快照")
                .isEqualTo(GroupMetadataFieldSource.METADATA_EVENT);
        assertThat(patch.observedAt()).isEqualTo(2_000L);
        assertThat(patch.groupJid()).isEqualTo("120363-abc@g.us");
        assertThat(patch.tenantId()).isEqualTo(1L);
    }

    @Test
    void tenantContextIsClearedAfterHandling() {
        adapter.handleMetadataUpdated(event(List.of("subject")));

        assertThat(TenantContext.get())
                .as("消费线程被复用，租户上下文必须归还，否则会污染下一条消息")
                .isNull();
    }

    private GroupMetadataPatch captured() {
        ArgumentCaptor<GroupMetadataPatch> captor =
                ArgumentCaptor.forClass(GroupMetadataPatch.class);
        verify(patchService).applyPatch(captor.capture());
        return captor.getValue();
    }

    private static ProtocolGroupMetadataUpdatedEvent event(List<String> fieldMask) {
        return new ProtocolGroupMetadataUpdatedEvent(
                "acc-100:group.metadata_updated:1",
                1L,
                100L,
                "protocol-account-100",
                "WEB",
                "120363-abc@g.us",
                fieldMask,
                "Name",
                "Desc",
                true,
                false,
                true,
                false,
                0,
                "8613900000000@s.whatsapp.net",
                "wa_groups_update",
                2_000L,
                "worker-1");
    }
}
