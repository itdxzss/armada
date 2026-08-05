package com.armada.group.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.vo.AccountGroupBaselineRow;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.service.impl.GroupClassificationServiceImpl;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 历史群与上控后群固化分类真值表测试。 */
@ExtendWith(MockitoExtension.class)
class GroupClassificationServiceImplTest {

    private static final long ACCOUNT_ID = 11L;
    private static final long CAPTURED_AT = 1_000L;

    @Mock
    private AccountGroupMembershipMapper membershipMapper;

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Mock
    private GroupLinkRegistryService registryService;

    @Mock
    private GroupMetadataSyncTaskService metadataSyncTaskService;

    private GroupClassificationService service;

    @BeforeEach
    void setUp() {
        service = new GroupClassificationServiceImpl(
                membershipMapper,
                groupLinkMapper,
                registryService,
                new ObjectMapper(),
                metadataSyncTaskService);
    }

    @Test
    void capturedSnapshotMarksBaselineAndPostControlWithoutClearingEitherFact() {
        when(membershipMapper.selectAccountBaselineRow(ACCOUNT_ID))
                .thenReturn(baseline(AccountGroupBaselineStateCode.CAPTURED));
        GroupClassificationCandidate oldGroup = candidate(101L, "120363-old@g.us");
        GroupClassificationCandidate newGroup = candidate(102L, "120363-new@g.us");
        when(groupLinkMapper.markHistorical(101L, 2_000L)).thenReturn(1);
        when(groupLinkMapper.markPostControl(102L, 2_000L)).thenReturn(1);

        service.classifyVisibleGroups(ACCOUNT_ID, List.of(oldGroup, newGroup), 2_000L);

        verify(groupLinkMapper).markHistorical(101L, 2_000L);
        verify(groupLinkMapper).markPostControl(102L, 2_000L);
        verify(groupLinkMapper, never()).markPostControl(101L, 2_000L);
        verify(groupLinkMapper, never()).markHistorical(102L, 2_000L);
        verify(metadataSyncTaskService).enqueue(
                101L, com.armada.group.model.enums.GroupMetadataSyncTrigger.BASELINE_CAPTURED, 2_000L);
        verify(metadataSyncTaskService).enqueue(
                102L, com.armada.group.model.enums.GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED, 2_000L);
    }

    @Test
    void pendingAndDisabledSnapshotsDoNotGuessClassification() {
        when(membershipMapper.selectAccountBaselineRow(ACCOUNT_ID))
                .thenReturn(baseline(AccountGroupBaselineStateCode.PENDING));
        service.classifyVisibleGroups(ACCOUNT_ID, List.of(candidate(101L, "120363-new@g.us")), 2_000L);

        when(membershipMapper.selectAccountBaselineRow(ACCOUNT_ID))
                .thenReturn(baseline(AccountGroupBaselineStateCode.DISABLED));
        service.classifyVisibleGroups(ACCOUNT_ID, List.of(candidate(101L, "120363-new@g.us")), 3_000L);

        verifyNoInteractions(groupLinkMapper);
    }

    @Test
    void preciseSelfAddRequiresCapturedBaselineOutsideJidAndLaterFactTime() {
        when(membershipMapper.selectAccountBaselineRow(ACCOUNT_ID))
                .thenReturn(baseline(AccountGroupBaselineStateCode.CAPTURED));
        GroupClassificationCandidate oldGroup = candidate(101L, "120363-old@g.us");
        GroupClassificationCandidate newGroup = candidate(102L, "120363-new@g.us");
        when(groupLinkMapper.markPostControl(102L, 2_001L)).thenReturn(1);

        service.classifyMembershipAdded(ACCOUNT_ID, oldGroup, CAPTURED_AT + 1, 2_000L);
        service.classifyMembershipAdded(ACCOUNT_ID, newGroup, CAPTURED_AT, 2_000L);
        service.classifyMembershipAdded(ACCOUNT_ID, newGroup, CAPTURED_AT + 1, 2_001L);

        verify(groupLinkMapper).markPostControl(102L, 2_001L);
        verify(groupLinkMapper, never()).markPostControl(101L, 2_000L);
        verify(groupLinkMapper, never()).markPostControl(102L, 2_000L);
    }

    @Test
    void initialBaselineRegistersEveryGroupThenMarksHistorical() {
        when(registryService.registerAccountObservedGroup(
                "120363-old@g.us", "历史群", ProtocolBackend.WEB, 2_000L))
                .thenReturn(101L);
        when(groupLinkMapper.markHistorical(101L, 2_000L)).thenReturn(1);

        service.captureHistoricalBaseline(
                List.of(new GroupClassificationCandidate(null, "120363-old@g.us", "历史群")),
                ProtocolBackend.WEB,
                2_000L);

        verify(groupLinkMapper).markHistorical(101L, 2_000L);
        verify(groupLinkMapper, never()).markPostControl(101L, 2_000L);
        verify(metadataSyncTaskService).enqueue(
                101L, com.armada.group.model.enums.GroupMetadataSyncTrigger.BASELINE_CAPTURED, 2_000L);
    }

    private static GroupClassificationCandidate candidate(long groupLinkId, String groupJid) {
        return new GroupClassificationCandidate(groupLinkId, groupJid, null);
    }

    private static AccountGroupBaselineRow baseline(int state) {
        AccountGroupBaselineRow row = new AccountGroupBaselineRow();
        row.setAccountId(ACCOUNT_ID);
        row.setGroupBaselineState(state);
        row.setBaselineGroupJidsJson("[\"120363-old@g.us\"]");
        row.setCapturedAt(CAPTURED_AT);
        return row;
    }
}
