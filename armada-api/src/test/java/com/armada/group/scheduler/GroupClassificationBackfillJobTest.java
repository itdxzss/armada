package com.armada.group.scheduler;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.entity.GroupLink;
import com.armada.group.model.vo.GroupClassificationBackfillCandidate;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** 历史群与上控后群存量回填 job 单测。 */
class GroupClassificationBackfillJobTest {

    private final GroupLinkMapper mapper = Mockito.mock(GroupLinkMapper.class);
    private final GroupMetadataSyncTaskService metadataSyncTaskService =
            Mockito.mock(GroupMetadataSyncTaskService.class);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void backfillCreatesMissingHistoricalLinkWithoutRemoteCallAndDoesNotReviveDeletedLink() {
        GroupClassificationBackfillProperties properties =
                new GroupClassificationBackfillProperties(true, 30_000L, 500);
        GroupClassificationBackfillJob job = new GroupClassificationBackfillJob(
                mapper, properties, metadataSyncTaskService);
        when(mapper.selectHistoricalClassificationBackfillCandidates(500)).thenReturn(List.of(
                new GroupClassificationBackfillCandidate(
                        7L, null, "120363-missing@g.us", "缺失历史群", null),
                new GroupClassificationBackfillCandidate(
                        8L, 202L, "120363-deleted@g.us", "已删除历史群", 9_000L)));
        when(mapper.selectPostControlClassificationBackfillCandidates(500)).thenReturn(List.of());
        when(mapper.insertHistoricalBaselineGroupIgnore(Mockito.any())).thenReturn(1);
        GroupLink inserted = new GroupLink();
        inserted.setId(101L);
        when(mapper.selectAnyByUrl("wa://group/120363-missing@g.us")).thenReturn(inserted);

        job.backfillOnce();

        ArgumentCaptor<GroupLink> row = ArgumentCaptor.forClass(GroupLink.class);
        verify(mapper).insertHistoricalBaselineGroupIgnore(row.capture());
        org.assertj.core.api.Assertions.assertThat(row.getValue()).satisfies(link -> {
            org.assertj.core.api.Assertions.assertThat(link.getLinkUrl())
                    .isEqualTo("wa://group/120363-missing@g.us");
            org.assertj.core.api.Assertions.assertThat(link.getGroupName()).isEqualTo("缺失历史群");
            org.assertj.core.api.Assertions.assertThat(link.getIsHistorical()).isTrue();
        });
        verify(mapper).markHistorical(101L, row.getValue().getUpdatedAt());
        verify(mapper).markHistoricalIncludingDeleted(202L, row.getValue().getUpdatedAt());
        verify(metadataSyncTaskService).enqueue(
                Mockito.eq(101L),
                Mockito.eq(com.armada.group.model.enums.GroupMetadataSyncTrigger.BACKFILL),
                Mockito.anyLong());
    }

    @Test
    void backfillMarksPostControlPerTenantAndRestoresCallerContext() {
        GroupClassificationBackfillProperties properties =
                new GroupClassificationBackfillProperties(true, 30_000L, 100);
        GroupClassificationBackfillJob job = new GroupClassificationBackfillJob(
                mapper, properties, metadataSyncTaskService);
        when(mapper.selectHistoricalClassificationBackfillCandidates(100)).thenReturn(List.of());
        when(mapper.selectPostControlClassificationBackfillCandidates(100)).thenReturn(List.of(
                new GroupClassificationBackfillCandidate(
                        8L, 303L, "120363-new@g.us", "上控后群", null)));
        when(mapper.markPostControl(Mockito.eq(303L), Mockito.anyLong())).thenReturn(1);
        TenantContext.set(99L);

        job.backfillOnce();

        verify(mapper).markPostControl(Mockito.eq(303L), Mockito.anyLong());
        verify(metadataSyncTaskService).enqueue(
                Mockito.eq(303L),
                Mockito.eq(com.armada.group.model.enums.GroupMetadataSyncTrigger.BACKFILL),
                Mockito.anyLong());
        org.assertj.core.api.Assertions.assertThat(TenantContext.get()).isEqualTo(99L);
    }
}
