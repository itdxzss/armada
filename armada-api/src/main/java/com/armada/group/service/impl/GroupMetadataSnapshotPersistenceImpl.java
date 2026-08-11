package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.mapper.WhatsappGroupMemberSnapshotMapper;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.group.service.GroupInviteLinkService;
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
    private final GroupInviteLinkService inviteLinkService;

    /**
     * 创建快照持久化实现。
     *
     * @param previewMapper 群预览数据访问
     * @param memberMapper 群成员快照数据访问
     * @param groupLinkMapper 群入口数据访问
     * @param inviteLinkService 当前群邀请链接事实服务
     */
    public GroupMetadataSnapshotPersistenceImpl(
            GroupLinkPreviewMapper previewMapper,
            WhatsappGroupMemberSnapshotMapper memberMapper,
            GroupLinkMapper groupLinkMapper,
            GroupInviteLinkService inviteLinkService) {
        this.previewMapper = previewMapper;
        this.memberMapper = memberMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.inviteLinkService = inviteLinkService;
    }

    @Override
    @Transactional
    public boolean persist(GroupLinkPreview preview, List<WhatsappGroupMemberSnapshot> members) {
        if (previewMapper.upsertMetadataSnapshot(preview) <= 0) {
            return false;
        }
        applyCurrentInvite(preview);
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

    private void applyCurrentInvite(GroupLinkPreview preview) {
        if (preview.getInviteCode() == null || preview.getInviteCode().isBlank()) {
            return;
        }
        long observedAt = preview.getMetadataObservedAt() == null
                ? preview.getUpdatedAt()
                : preview.getMetadataObservedAt();
        inviteLinkService.applyCurrentInvite(new GroupInviteLinkObservation(
                "metadata-sync:" + preview.getGroupLinkId() + ":" + observedAt,
                preview.getGroupLinkId(), preview.getGroupJid(), preview.getInviteCode(),
                null, "GROUP_METADATA_SYNC", observedAt));
    }
}
