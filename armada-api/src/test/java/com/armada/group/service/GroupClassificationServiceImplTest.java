package com.armada.group.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Existing;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.service.impl.GroupClassificationServiceImpl;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 历史群与上控后群新模型分类测试。 */
@ExtendWith(MockitoExtension.class)
class GroupClassificationServiceImplTest {

    private static final long ACCOUNT_ID = 11L;
    private static final long CAPTURED_AT = 1_000L;

    @Mock private AccountGroupCurrentSnapshotMapper currentSnapshotMapper;
    @Mock private GroupLinkMapper groupLinkMapper;
    @Mock private GroupLinkRegistryService registryService;
    @Mock private GroupMetadataSyncTaskService metadataSyncTaskService;

    private GroupClassificationService service;

    @BeforeEach
    void setUp() {
        service = new GroupClassificationServiceImpl(
                currentSnapshotMapper, groupLinkMapper, registryService, metadataSyncTaskService);
    }

    @Test
    void capturedBindingsClassifyBaselineAndPostControlWithoutGuessingJoinedAt() {
        when(currentSnapshotMapper.selectContext(ACCOUNT_ID)).thenReturn(context(2));
        when(currentSnapshotMapper.selectExisting(
                ACCOUNT_ID,
                "15550000001@s.whatsapp.net",
                List.of("120363-old@g.us", "120363-new@g.us")))
                .thenReturn(List.of(
                        existing("120363-old@g.us", 1, null),
                        existing("120363-new@g.us", 0, 1_500L)));
        GroupLink historical = activeLink(101L, false, false);
        GroupLink postControl = activeLink(102L, false, false);
        when(groupLinkMapper.selectActiveByIds(List.of(101L, 102L)))
                .thenReturn(List.of(historical, postControl));
        when(groupLinkMapper.selectActiveByIdsForUpdate(List.of(101L, 102L)))
                .thenReturn(List.of(historical, postControl));
        when(groupLinkMapper.markClassifications(
                List.of(101L), List.of(102L), 2_000L)).thenReturn(2);

        service.classifyVisibleGroups(ACCOUNT_ID, List.of(
                candidate(101L, "120363-old@g.us"),
                candidate(102L, "120363-new@g.us")), 2_000L);

        verify(groupLinkMapper).markClassifications(
                List.of(101L), List.of(102L), 2_000L);
        verify(groupLinkMapper, never()).markPostControl(101L, 2_000L);
        verify(metadataSyncTaskService).enqueueClassifications(
                Map.of(
                        101L, GroupMetadataSyncTrigger.BASELINE_CAPTURED,
                        102L, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED),
                2_000L);
    }

    @Test
    void pendingBaselineDoesNotGuessClassification() {
        when(currentSnapshotMapper.selectContext(ACCOUNT_ID)).thenReturn(context(1));

        service.classifyVisibleGroups(
                ACCOUNT_ID, List.of(candidate(101L, "120363-new@g.us")), 2_000L);

        verifyNoInteractions(groupLinkMapper);
    }

    @Test
    void replaySkipsAlreadyPersistedClassificationWithoutTakingGroupLinkWriteLock() {
        when(currentSnapshotMapper.selectContext(ACCOUNT_ID)).thenReturn(context(2));
        when(currentSnapshotMapper.selectExisting(
                ACCOUNT_ID,
                "15550000001@s.whatsapp.net",
                List.of("120363-old@g.us")))
                .thenReturn(List.of(existing("120363-old@g.us", 1, null)));
        GroupLink alreadyHistorical = new GroupLink();
        alreadyHistorical.setId(101L);
        alreadyHistorical.setIsHistorical(true);
        when(groupLinkMapper.selectActiveByIds(List.of(101L)))
                .thenReturn(List.of(alreadyHistorical));

        service.classifyVisibleGroups(
                ACCOUNT_ID, List.of(candidate(101L, "120363-old@g.us")), 2_000L);

        verify(groupLinkMapper, never()).markHistorical(101L, 2_000L);
        verifyNoInteractions(metadataSyncTaskService);
    }

    @Test
    void preciseAddAfterCapturedBaselineMarksOnlyNonBaselineBinding() {
        when(currentSnapshotMapper.selectContext(ACCOUNT_ID)).thenReturn(context(2));
        when(currentSnapshotMapper.selectSelfMembershipExisting(
                ACCOUNT_ID, "15550000001@s.whatsapp.net", "120363-old@g.us"))
                .thenReturn(existing("120363-old@g.us", 1, null));
        when(currentSnapshotMapper.selectSelfMembershipExisting(
                ACCOUNT_ID, "15550000001@s.whatsapp.net", "120363-new@g.us"))
                .thenReturn(null);
        when(groupLinkMapper.markPostControl(102L, 2_001L)).thenReturn(1);

        service.classifyMembershipAdded(
                ACCOUNT_ID, candidate(101L, "120363-old@g.us"), 1_001L, 2_000L);
        service.classifyMembershipAdded(
                ACCOUNT_ID, candidate(102L, "120363-new@g.us"), 1_001L, 2_001L);

        verify(groupLinkMapper, never()).markPostControl(101L, 2_000L);
        verify(groupLinkMapper).markPostControl(102L, 2_001L);
    }

    @Test
    void initialBaselineRegistersThenMarksHistorical() {
        when(registryService.registerAccountObservedGroups(
                java.util.Map.of("120363-old@g.us", "历史群"),
                ProtocolBackend.WEB,
                2_000L)).thenReturn(java.util.Map.of("120363-old@g.us", 101L));
        when(groupLinkMapper.selectActiveByIds(List.of(101L)))
                .thenReturn(List.of(activeLink(101L, false, false)));
        when(groupLinkMapper.selectActiveByIdsForUpdate(List.of(101L)))
                .thenReturn(List.of(activeLink(101L, false, false)));
        when(groupLinkMapper.markClassifications(List.of(101L), List.of(), 2_000L))
                .thenReturn(1);

        service.captureHistoricalBaseline(
                List.of(new GroupClassificationCandidate(null, "120363-old@g.us", "历史群")),
                ProtocolBackend.WEB,
                2_000L);

        verify(groupLinkMapper).markClassifications(List.of(101L), List.of(), 2_000L);
        verify(metadataSyncTaskService).enqueueClassifications(
                Map.of(101L, GroupMetadataSyncTrigger.BASELINE_CAPTURED), 2_000L);
    }

    @Test
    void concurrentLoserSkipsEnqueueAfterLockedCurrentReadSeesWinner() {
        when(registryService.registerAccountObservedGroups(
                java.util.Map.of("120363-old@g.us", "历史群"),
                ProtocolBackend.WEB,
                2_000L)).thenReturn(java.util.Map.of("120363-old@g.us", 101L));
        when(groupLinkMapper.selectActiveByIds(List.of(101L)))
                .thenReturn(List.of(activeLink(101L, false, false)));
        when(groupLinkMapper.selectActiveByIdsForUpdate(List.of(101L)))
                .thenReturn(List.of(activeLink(101L, true, false)));

        service.captureHistoricalBaseline(
                List.of(new GroupClassificationCandidate(null, "120363-old@g.us", "历史群")),
                ProtocolBackend.WEB,
                2_000L);

        verify(groupLinkMapper, never()).markClassifications(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyLong());
        verifyNoInteractions(metadataSyncTaskService);
    }

    private static GroupClassificationCandidate candidate(long id, String jid) {
        return new GroupClassificationCandidate(id, jid, null);
    }

    private static GroupLink activeLink(long id, boolean historical, boolean postControl) {
        GroupLink link = new GroupLink();
        link.setId(id);
        link.setIsHistorical(historical);
        link.setIsPostControl(postControl);
        return link;
    }

    private static Context context(int state) {
        return new Context(
                ACCOUNT_ID, "15550000001", "WEB", "acc-11", state,
                state == AccountGroupBaselineStateCode.CAPTURED ? 1 : 0,
                1, CAPTURED_AT, null, null);
    }

    private static Existing existing(String jid, int baseline, Long postControlAt) {
        return new Existing(
                jid, 1L, 2L, 1, "GROUP_SNAPSHOT", 1_500L,
                3L, baseline, postControlAt, null, null);
    }
}
