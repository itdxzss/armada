package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.mapper.WhatsappGroupMemberSnapshotMapper;
import com.armada.group.model.entity.GroupLinkPreview;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 群 metadata 快照事务持久化单测。 */
@ExtendWith(MockitoExtension.class)
class GroupMetadataSnapshotPersistenceImplTest {

    @Mock
    private GroupLinkPreviewMapper previewMapper;

    @Mock
    private WhatsappGroupMemberSnapshotMapper memberMapper;

    @Mock
    private GroupLinkMapper groupLinkMapper;

    @Test
    void freshMetadataMirrorsWhatsappSubjectToGroupListName() {
        GroupLinkPreview preview = preview("test-Android");
        when(previewMapper.upsertMetadataSnapshot(preview)).thenReturn(1);

        boolean persisted = service().persist(preview, List.of());

        assertThat(persisted).isTrue();
        verify(groupLinkMapper).updateGroupName(10L, "test-Android", 1_786_190_145_628L);
        verify(memberMapper).deleteByGroupLinkId(10L);
    }

    @Test
    void staleMetadataDoesNotOverwriteGroupListName() {
        GroupLinkPreview preview = preview("旧群名");
        when(previewMapper.upsertMetadataSnapshot(preview)).thenReturn(0);

        boolean persisted = service().persist(preview, List.of());

        assertThat(persisted).isFalse();
        verify(groupLinkMapper, never()).updateGroupName(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(memberMapper, never()).deleteByGroupLinkId(10L);
    }

    @Test
    void missingWhatsappSubjectPreservesExistingGroupListName() {
        GroupLinkPreview preview = preview(null);
        when(previewMapper.upsertMetadataSnapshot(preview)).thenReturn(1);

        boolean persisted = service().persist(preview, List.of());

        assertThat(persisted).isTrue();
        verify(groupLinkMapper, never()).updateGroupName(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private GroupMetadataSnapshotPersistenceImpl service() {
        return new GroupMetadataSnapshotPersistenceImpl(previewMapper, memberMapper, groupLinkMapper);
    }

    private static GroupLinkPreview preview(String subject) {
        GroupLinkPreview preview = new GroupLinkPreview();
        preview.setGroupLinkId(10L);
        preview.setWaSubject(subject);
        preview.setUpdatedAt(1_786_190_145_628L);
        return preview;
    }
}
