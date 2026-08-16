package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.GroupMetadataSnapshotRequest;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.model.vo.GroupCurrentIdentity;
import com.armada.group.service.GroupBatchAccountThrottle;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupMetadataSnapshotService;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 批量获取最新群信息执行器单测:实时直调协议读快照，不再排进耐久队列等结果。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupBatchInfoRefreshWorkerTest {

    private static final long GROUP_LINK_ID = 101L;
    private static final String GROUP_JID = "120363batch@g.us";

    @Mock
    private GroupExecutionAccountSelector selector;

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private GroupBatchAccountThrottle throttle;

    @Mock
    private GroupBatchTaskSettlement settlement;

    @Mock
    private GroupMetadataSnapshotService snapshotService;

    private final AtomicReference<Long> throttledAccountId = new AtomicReference<>();

    @BeforeEach
    void passThroughThrottle() {
        doAnswer(invocation -> {
            throttledAccountId.set(invocation.getArgument(0));
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(throttle).run(any(), any());
    }

    @Test
    void missingExecutionAccountFailsTheItemWithoutCallingTheProtocol() {
        when(selector.find(eq(GROUP_LINK_ID), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());

        worker().execute(item(), 5_000L);

        GroupBatchTaskItem outcome = settled();
        assertThat(outcome.getStatus()).isEqualTo(GroupBatchTaskItemStatus.FAILED.code());
        assertThat(outcome.getErrorCode()).isEqualTo("NO_AVAILABLE_ACCOUNT");
        assertThat(outcome.getDescription()).isNotBlank();
        verify(snapshotService, never()).refresh(any(), any());
    }

    @Test
    void missingGroupJidFailsTheItemBeforeTheProtocolCall() {
        stubAccount();
        when(groupLinkMapper.selectCurrentIdentity(GROUP_LINK_ID)).thenReturn(null);

        worker().execute(item(), 6_000L);

        GroupBatchTaskItem outcome = settled();
        assertThat(outcome.getStatus()).isEqualTo(GroupBatchTaskItemStatus.FAILED.code());
        assertThat(outcome.getErrorCode()).isEqualTo("GROUP_JID_UNKNOWN");
        verify(snapshotService, never()).refresh(any(), any());
    }

    @Test
    void successfulRefreshReadsTheSnapshotInRealTimeAndSettlesWithExecutingAccount() {
        stubAccount();
        stubPreview();

        worker().execute(item(), 7_000L);

        ArgumentCaptor<GroupMetadataSnapshotRequest> request =
                ArgumentCaptor.forClass(GroupMetadataSnapshotRequest.class);
        verify(snapshotService).refresh(request.capture(), any());
        assertThat(request.getValue().groupLinkId()).isEqualTo(GROUP_LINK_ID);
        assertThat(request.getValue().groupJid()).isEqualTo(GROUP_JID);
        // 本按钮只读最新群信息；缺邀请码不能把整项判失败，那是"刷新群链接"按钮的职责。
        assertThat(request.getValue().inviteRequired()).isFalse();

        GroupBatchTaskItem outcome = settled();
        assertThat(outcome.getStatus()).isEqualTo(GroupBatchTaskItemStatus.SUCCESS.code());
        assertThat(outcome.getAccountId()).isEqualTo(77L);
        assertThat(outcome.getGroupJid()).isEqualTo(GROUP_JID);
        assertThat(outcome.getOperatedAt()).isEqualTo(7_000L);
    }

    @Test
    void protocolCallRunsInsideTheThrottleOfTheExecutingAccount() {
        stubAccount();
        stubPreview();

        worker().execute(item(), 8_000L);

        // 并发放开后同一账号会被多条明细同时选中，协议调用必须在该账号的闸门内。
        assertThat(throttledAccountId.get()).isEqualTo(77L);
    }

    @Test
    void protocolFailureRecordsAConcreteReasonAndKeepsTheOldSnapshot() {
        stubAccount();
        stubPreview();
        doThrow(new IllegalStateException("群 metadata 成员快照不完整"))
                .when(snapshotService).refresh(any(), any());

        worker().execute(item(), 9_000L);

        GroupBatchTaskItem outcome = settled();
        assertThat(outcome.getStatus()).isEqualTo(GroupBatchTaskItemStatus.FAILED.code());
        assertThat(outcome.getErrorCode()).isEqualTo("METADATA_FETCH_FAILED");
        // 失败必须给出具体原因，PRD 6.3 禁止只返回通用失败。
        assertThat(outcome.getDescription()).contains("成员快照不完整");
        assertThat(outcome.getAccountId()).isEqualTo(77L);
    }

    @Test
    void databaseFailureIsReportedSeparatelyAndNeverLeaksSqlToTheDialog() {
        stubAccount();
        stubPreview();
        doThrow(new org.springframework.dao.DeadlockLoserDataAccessException(
                "### Error updating database.  Cause: com.mysql.cj.jdbc.exceptions."
                        + "MySQLTransactionRollbackException: Deadlock found when trying to get lock\n"
                        + "### The error may exist in mapper/group/WhatsappGroupMemberSnapshotMapper.xml",
                null))
                .when(snapshotService).refresh(any(), any());

        worker().execute(item(), 9_000L);

        GroupBatchTaskItem outcome = settled();
        assertThat(outcome.getStatus()).isEqualTo(GroupBatchTaskItemStatus.FAILED.code());
        // 写库失败不能顶着"读协议失败"的错误码，否则运维会往协议层查。
        assertThat(outcome.getErrorCode()).isEqualTo("DB_WRITE_FAILED");
        assertThat(outcome.getDescription())
                .contains("数据库繁忙")
                .contains("DeadlockLoserDataAccessException")
                .doesNotContain("###")
                .doesNotContain("Mapper.xml")
                .doesNotContain("com.mysql");
        assertThat(outcome.getGroupJid()).isEqualTo(GROUP_JID);
    }

    private void stubAccount() {
        when(selector.find(eq(GROUP_LINK_ID), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.of(new GroupExecutionAccount(
                        77L, "WEB", "acc_77", "923310000001", false)));
    }

    private void stubPreview() {
        when(groupLinkMapper.selectCurrentIdentity(GROUP_LINK_ID))
                .thenReturn(new GroupCurrentIdentity(GROUP_LINK_ID, GROUP_JID, null));
    }

    private GroupBatchTaskItem settled() {
        ArgumentCaptor<GroupBatchTaskItem> captor =
                ArgumentCaptor.forClass(GroupBatchTaskItem.class);
        verify(settlement).settle(captor.capture());
        return captor.getValue();
    }

    private static GroupBatchTaskItem item() {
        GroupBatchTaskItem item = new GroupBatchTaskItem();
        item.setId(9L);
        item.setTaskId(900L);
        item.setGroupLinkId(GROUP_LINK_ID);
        item.setStatus(GroupBatchTaskItemStatus.PENDING.code());
        return item;
    }

    private GroupBatchInfoRefreshWorker worker() {
        return new GroupBatchInfoRefreshWorker(
                new GroupBatchRefreshSupport(selector, groupLinkMapper, throttle, settlement),
                snapshotService);
    }
}
