package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.port.GroupInvitePort;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 批量刷新群链接执行器单测。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupBatchLinkRefreshWorkerTest {

    private static final long GROUP_LINK_ID = 101L;
    private static final String GROUP_JID = "120363batch@g.us";

    @Mock
    private GroupExecutionAccountSelector selector;

    @Mock
    private GroupInvitePort invitePort;

    @Mock
    private GroupInviteLinkService inviteLinkService;

    @Mock
    private GroupLinkPreviewMapper previewMapper;

    @Mock
    private GroupBatchTaskSettlement settlement;

    @Test
    void missingAdminFailsTheItemWithoutEverCallingTheProtocol() {
        when(selector.findAdmin(GROUP_LINK_ID)).thenReturn(Optional.empty());

        worker().execute(item(), 5_000L);

        GroupBatchTaskItem outcome = settled();
        assertThat(outcome.getStatus()).isEqualTo(GroupBatchTaskItemStatus.FAILED.code());
        assertThat(outcome.getDescription()).isEqualTo("系统内没有可用管理员账号");
        assertThat(outcome.getErrorCode()).isEqualTo("NO_AVAILABLE_ADMIN");
        verify(invitePort, never()).getInvite(any(), anyString());
    }

    @Test
    void successfulFetchPersistsTheInviteAndSettlesTheItemWithExecutingAccount() {
        GroupExecutionAccount admin = new GroupExecutionAccount(
                77L, "WEB", "acc_77", "923310000001", true);
        when(selector.findAdmin(GROUP_LINK_ID)).thenReturn(Optional.of(admin));
        when(previewMapper.selectByGroupLinkId(GROUP_LINK_ID)).thenReturn(preview());
        when(invitePort.getInvite(any(), org.mockito.ArgumentMatchers.eq(GROUP_JID)))
                .thenReturn(new GroupInviteResult(
                        GROUP_JID, "NEWCODE", "https://chat.whatsapp.com/NEWCODE"));

        worker().execute(item(), 6_000L);

        verify(inviteLinkService).applyCurrentInvite(any());
        GroupBatchTaskItem outcome = settled();
        assertThat(outcome.getStatus()).isEqualTo(GroupBatchTaskItemStatus.SUCCESS.code());
        assertThat(outcome.getAccountId()).isEqualTo(77L);
        assertThat(outcome.getGroupJid()).isEqualTo(GROUP_JID);
        assertThat(outcome.getOperatedAt()).isEqualTo(6_000L);
    }

    @Test
    void missingGroupJidFailsTheItemBeforeTheProtocolCall() {
        GroupExecutionAccount admin = new GroupExecutionAccount(
                77L, "WEB", "acc_77", "923310000001", true);
        when(selector.findAdmin(GROUP_LINK_ID)).thenReturn(Optional.of(admin));
        when(previewMapper.selectByGroupLinkId(GROUP_LINK_ID)).thenReturn(null);

        worker().execute(item(), 7_000L);

        assertThat(settled().getStatus()).isEqualTo(GroupBatchTaskItemStatus.FAILED.code());
        verify(invitePort, never()).getInvite(any(), anyString());
    }

    @Test
    void protocolFailureKeepsTheOldLinkAndRecordsAConcreteReason() {
        GroupExecutionAccount admin = new GroupExecutionAccount(
                77L, "WEB", "acc_77", "923310000001", true);
        when(selector.findAdmin(GROUP_LINK_ID)).thenReturn(Optional.of(admin));
        when(previewMapper.selectByGroupLinkId(GROUP_LINK_ID)).thenReturn(preview());
        when(invitePort.getInvite(any(), anyString()))
                .thenThrow(new IllegalStateException("boom"));

        worker().execute(item(), 8_000L);

        GroupBatchTaskItem outcome = settled();
        assertThat(outcome.getStatus()).isEqualTo(GroupBatchTaskItemStatus.FAILED.code());
        // 失败必须有具体原因，PRD 6.3 禁止只返回通用失败。
        assertThat(outcome.getDescription()).isNotBlank();
        verify(inviteLinkService, never()).applyCurrentInvite(any());
    }

    private GroupBatchTaskItem settled() {
        ArgumentCaptor<GroupBatchTaskItem> captor =
                ArgumentCaptor.forClass(GroupBatchTaskItem.class);
        verify(settlement).settle(captor.capture());
        return captor.getValue();
    }

    private static GroupLinkPreview preview() {
        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setGroupLinkId(GROUP_LINK_ID);
        preview.setGroupJid(GROUP_JID);
        return preview;
    }

    private static GroupBatchTaskItem item() {
        GroupBatchTaskItem item = new GroupBatchTaskItem();
        item.setId(9L);
        item.setTaskId(900L);
        item.setGroupLinkId(GROUP_LINK_ID);
        item.setStatus(GroupBatchTaskItemStatus.PENDING.code());
        return item;
    }

    private GroupBatchLinkRefreshWorker worker() {
        return new GroupBatchLinkRefreshWorker(
                selector, invitePort, inviteLinkService, previewMapper, settlement);
    }
}
