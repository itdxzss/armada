package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.mapper.WhatsappGroupMemberSnapshotMapper;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.group.service.GroupMetadataSnapshotPersistence;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 群详情和成员快照事务写入实现。 */
@Service
public class GroupMetadataSnapshotPersistenceImpl implements GroupMetadataSnapshotPersistence {

    private final GroupLinkPreviewMapper previewMapper;
    private final WhatsappGroupMemberSnapshotMapper memberMapper;
    private final GroupLinkMapper groupLinkMapper;

    /** 创建快照持久化实现。 */
    public GroupMetadataSnapshotPersistenceImpl(
            GroupLinkPreviewMapper previewMapper,
            WhatsappGroupMemberSnapshotMapper memberMapper,
            GroupLinkMapper groupLinkMapper) {
        this.previewMapper = previewMapper;
        this.memberMapper = memberMapper;
        this.groupLinkMapper = groupLinkMapper;
    }

    @Override
    @Transactional
    public boolean persist(GroupLinkPreview preview, List<WhatsappGroupMemberSnapshot> members) {
        if (previewMapper.upsertMetadataSnapshot(preview) <= 0) {
            return false;
        }
        String subject = preview.getWaSubject();
        if (subject != null && !subject.isBlank()) {
            groupLinkMapper.updateGroupName(
                    preview.getGroupLinkId(), subject, preview.getUpdatedAt());
        }
        memberMapper.deleteByGroupLinkId(preview.getGroupLinkId());
        if (members != null && !members.isEmpty()) {
            memberMapper.insertBatch(members);
        }
        return true;
    }
}
