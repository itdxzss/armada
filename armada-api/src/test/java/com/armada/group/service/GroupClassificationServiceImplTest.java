package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.model.enums.AccountGroupBaselineStateCode;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.mapper.GroupClassificationMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Existing;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.enums.GroupClassification;
import com.armada.group.model.enums.GroupClassificationSource;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.CanonicalGroupClassificationRow;
import com.armada.group.model.vo.CanonicalGroupClassificationWrite;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.model.vo.GroupClassificationPlan;
import com.armada.group.model.vo.GroupPostControlClassificationCandidate;
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
    @Mock private GroupClassificationMapper classificationMapper;
    @Mock private GroupLinkRegistryService registryService;
    @Mock private GroupMetadataSyncTaskService metadataSyncTaskService;

    private GroupClassificationService service;

    @BeforeEach
    void setUp() {
        service = new GroupClassificationServiceImpl(
                currentSnapshotMapper,
                groupLinkMapper,
                classificationMapper,
                registryService,
                metadataSyncTaskService);
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
        GroupLink historical = activeLink(101L);
        GroupLink postControl = activeLink(102L);
        when(groupLinkMapper.selectActiveByIdsForUpdate(List.of(101L, 102L)))
                .thenReturn(List.of(historical, postControl));
        when(classificationMapper.selectByGroupJids(
                7L, List.of("120363-new@g.us", "120363-old@g.us")))
                .thenReturn(List.of(
                        classification("120363-new@g.us", GroupClassification.UNCLASSIFIED),
                        classification("120363-old@g.us", GroupClassification.UNCLASSIFIED)));
        when(classificationMapper.classifyFirstBatch(
                7L,
                List.of(
                        write("120363-new@g.us", GroupClassification.POST_CONTROL,
                                GroupClassificationSource.POST_CONTROL_DISCOVERED, 1_500L),
                        write("120363-old@g.us", GroupClassification.HISTORICAL,
                                GroupClassificationSource.BASELINE_CAPTURED, 1_000L)),
                2_000L)).thenReturn(2);

        withTenant(() -> service.classifyVisibleGroups(ACCOUNT_ID, List.of(
                    candidate(101L, "120363-old@g.us"),
                    candidate(102L, "120363-new@g.us")), 2_000L));

        verify(classificationMapper).classifyFirstBatch(
                7L,
                List.of(
                        write("120363-new@g.us", GroupClassification.POST_CONTROL,
                                GroupClassificationSource.POST_CONTROL_DISCOVERED, 1_500L),
                        write("120363-old@g.us", GroupClassification.HISTORICAL,
                                GroupClassificationSource.BASELINE_CAPTURED, 1_000L)),
                2_000L);
        verify(metadataSyncTaskService).enqueueClassifications(
                Map.of(
                        101L, GroupMetadataSyncTrigger.BASELINE_CAPTURED,
                        102L, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED),
                2_000L);
    }

    @Test
    void pendingBaselineDoesNotGuessClassification() {
        when(currentSnapshotMapper.selectContext(ACCOUNT_ID)).thenReturn(context(1));

        withTenant(() -> service.classifyVisibleGroups(
                ACCOUNT_ID, List.of(candidate(101L, "120363-new@g.us")), 2_000L));

        verifyNoInteractions(groupLinkMapper);
    }

    @Test
    void replaySkipsAlreadyPersistedCanonicalClassification() {
        when(currentSnapshotMapper.selectContext(ACCOUNT_ID)).thenReturn(context(2));
        when(currentSnapshotMapper.selectExisting(
                ACCOUNT_ID,
                "15550000001@s.whatsapp.net",
                List.of("120363-old@g.us")))
                .thenReturn(List.of(existing("120363-old@g.us", 1, null)));
        when(groupLinkMapper.selectActiveByIdsForUpdate(List.of(101L)))
                .thenReturn(List.of(activeLink(101L)));
        when(classificationMapper.selectByGroupJids(7L, List.of("120363-old@g.us")))
                .thenReturn(List.of(classification(
                        "120363-old@g.us", GroupClassification.HISTORICAL)));

        withTenant(() -> service.classifyVisibleGroups(
                ACCOUNT_ID, List.of(candidate(101L, "120363-old@g.us")), 2_000L));

        verify(classificationMapper, never()).classifyFirstBatch(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyLong());
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
        when(groupLinkMapper.selectActiveByIdsForUpdate(List.of(102L)))
                .thenReturn(List.of(activeLink(102L)));
        when(classificationMapper.selectByGroupJids(7L, List.of("120363-new@g.us")))
                .thenReturn(List.of(classification(
                        "120363-new@g.us", GroupClassification.UNCLASSIFIED)));
        when(classificationMapper.classifyFirstBatch(
                7L,
                List.of(write("120363-new@g.us", GroupClassification.POST_CONTROL,
                        GroupClassificationSource.POST_CONTROL_DISCOVERED, 1_001L)),
                2_001L)).thenReturn(1);

        withTenant(() -> {
            service.classifyMembershipAdded(
                    ACCOUNT_ID, candidate(101L, "120363-old@g.us"), 1_001L, 2_000L);
            service.classifyMembershipAdded(
                    ACCOUNT_ID, candidate(102L, "120363-new@g.us"), 1_001L, 2_001L);
        });

        verify(classificationMapper).classifyFirstBatch(
                7L,
                List.of(write("120363-new@g.us", GroupClassification.POST_CONTROL,
                        GroupClassificationSource.POST_CONTROL_DISCOVERED, 1_001L)),
                2_001L);
    }

    @Test
    void initialBaselineRegistersThenMarksHistorical() {
        when(registryService.registerAccountObservedGroups(
                java.util.Map.of("120363-old@g.us", "历史群"),
                ProtocolBackend.WEB,
                2_000L)).thenReturn(java.util.Map.of("120363-old@g.us", 101L));
        when(groupLinkMapper.selectActiveByIdsForUpdate(List.of(101L)))
                .thenReturn(List.of(activeLink(101L)));
        when(classificationMapper.selectByGroupJids(7L, List.of("120363-old@g.us")))
                .thenReturn(List.of(classification(
                        "120363-old@g.us", GroupClassification.UNCLASSIFIED)));
        when(classificationMapper.classifyFirstBatch(
                7L,
                List.of(write("120363-old@g.us", GroupClassification.HISTORICAL,
                        GroupClassificationSource.BASELINE_CAPTURED, 2_000L)),
                2_000L)).thenReturn(1);

        withTenant(() -> service.captureHistoricalBaseline(
                    List.of(new GroupClassificationCandidate(
                            null, "120363-old@g.us", "历史群")),
                    ProtocolBackend.WEB,
                    2_000L));

        verify(classificationMapper).classifyFirstBatch(
                7L,
                List.of(write("120363-old@g.us", GroupClassification.HISTORICAL,
                        GroupClassificationSource.BASELINE_CAPTURED, 2_000L)),
                2_000L);
        verify(metadataSyncTaskService).enqueueClassifications(
                Map.of(101L, GroupMetadataSyncTrigger.BASELINE_CAPTURED), 2_000L);
    }

    @Test
    void oppositeCandidateLoserUsesCanonicalWinnerAndSkipsEnqueue() {
        when(registryService.registerAccountObservedGroups(
                java.util.Map.of("120363-old@g.us", "历史群"),
                ProtocolBackend.WEB,
                2_000L)).thenReturn(java.util.Map.of("120363-old@g.us", 101L));
        when(groupLinkMapper.selectActiveByIdsForUpdate(List.of(101L)))
                .thenReturn(List.of(activeLink(101L)));
        when(classificationMapper.selectByGroupJids(7L, List.of("120363-old@g.us")))
                .thenReturn(List.of(classification(
                        "120363-old@g.us", GroupClassification.POST_CONTROL)));

        withTenant(() -> service.captureHistoricalBaseline(
                    List.of(new GroupClassificationCandidate(
                            null, "120363-old@g.us", "历史群")),
                    ProtocolBackend.WEB,
                    2_000L));

        verify(classificationMapper, never()).classifyFirstBatch(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyLong());
        verifyNoInteractions(metadataSyncTaskService);
    }

    @Test
    void reliablePostCutoffEvidenceCanBeStagedBeforeBaselineCommit() {
        when(groupLinkMapper.selectActiveByIdsForUpdate(List.of(102L)))
                .thenReturn(List.of(activeLink(102L)));
        when(classificationMapper.selectByGroupJids(7L, List.of("120363-new@g.us")))
                .thenReturn(List.of(classification(
                        "120363-new@g.us", GroupClassification.UNCLASSIFIED)));
        when(classificationMapper.classifyFirstBatch(
                7L,
                List.of(write("120363-new@g.us", GroupClassification.POST_CONTROL,
                        GroupClassificationSource.POST_CONTROL_DISCOVERED, 1_500L)),
                2_000L)).thenReturn(1);

        final GroupClassificationPlan[] result = new GroupClassificationPlan[1];
        withTenant(() -> result[0] = service.stagePostControlEvidence(
                List.of(new GroupPostControlClassificationCandidate(
                        102L, "120363-new@g.us", "新群", 1_500L)),
                2_000L));

        assertThat(result[0].newlyPersisted()).containsEntry(
                102L, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED);
        verifyNoInteractions(currentSnapshotMapper, metadataSyncTaskService);
    }

    private static GroupClassificationCandidate candidate(long id, String jid) {
        return new GroupClassificationCandidate(id, jid, null);
    }

    private static GroupLink activeLink(long id) {
        GroupLink link = new GroupLink();
        link.setId(id);
        return link;
    }

    private static CanonicalGroupClassificationRow classification(
            String groupJid,
            GroupClassification classification) {
        return new CanonicalGroupClassificationRow(groupJid, classification.code());
    }

    private static CanonicalGroupClassificationWrite write(
            String groupJid,
            GroupClassification classification,
            GroupClassificationSource source,
            long classifiedAt) {
        return new CanonicalGroupClassificationWrite(
                groupJid, classification.code(), source.code(), classifiedAt);
    }

    private static void withTenant(Runnable action) {
        com.armada.shared.tenant.TenantContext.set(7L);
        try {
            action.run();
        } finally {
            com.armada.shared.tenant.TenantContext.clear();
        }
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
