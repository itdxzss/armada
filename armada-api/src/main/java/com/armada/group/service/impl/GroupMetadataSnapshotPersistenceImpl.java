package com.armada.group.service.impl;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.mapper.WhatsappGroupMemberSnapshotMapper;
import com.armada.group.model.dto.GroupCurrentLocalProfileWrite;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.group.model.enums.AccountGroupMembershipStatus;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.group.service.GroupMetadataSnapshotPersistence;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 群详情和成员快照事务写入实现。 */
@Service
public class GroupMetadataSnapshotPersistenceImpl implements GroupMetadataSnapshotPersistence {

    private static final String SNAPSHOT_STATUS_SOURCE = "GROUP_SNAPSHOT";

    private final GroupLinkPreviewMapper previewMapper;
    private final WhatsappGroupMemberSnapshotMapper memberMapper;
    private final GroupLinkMapper groupLinkMapper;
    private final AccountGroupMembershipMapper membershipMapper;
    private final GroupInviteLinkService inviteLinkService;
    private final AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;
    private final GroupCurrentLocalPersistence currentLocalPersistence;

    /**
     * 创建快照持久化实现。
     *
     * @param previewMapper 群预览数据访问
     * @param memberMapper 群成员快照数据访问
     * @param groupLinkMapper 群入口数据访问
     * @param membershipMapper 账号群关系数据访问
     * @param inviteLinkService 当前群邀请链接事实服务
     * @param currentSnapshotPersistence 新群模型当前成员快照写入
     */
    public GroupMetadataSnapshotPersistenceImpl(
            GroupLinkPreviewMapper previewMapper,
            WhatsappGroupMemberSnapshotMapper memberMapper,
            GroupLinkMapper groupLinkMapper,
            AccountGroupMembershipMapper membershipMapper,
            GroupInviteLinkService inviteLinkService,
            AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence,
            GroupCurrentLocalPersistence currentLocalPersistence) {
        this.previewMapper = previewMapper;
        this.memberMapper = memberMapper;
        this.groupLinkMapper = groupLinkMapper;
        this.membershipMapper = membershipMapper;
        this.inviteLinkService = inviteLinkService;
        this.currentSnapshotPersistence = currentSnapshotPersistence;
        this.currentLocalPersistence = currentLocalPersistence;
    }

    @Override
    @Transactional
    public boolean persist(GroupLinkPreview preview, List<WhatsappGroupMemberSnapshot> members) {
        if (previewMapper.upsertMetadataSnapshot(preview) <= 0) {
            return false;
        }
        applyCurrentInvite(preview);
        String subject = preview.getWaSubject();
        boolean subjectObserved = subject != null && !subject.isBlank();
        if (subjectObserved) {
            groupLinkMapper.updateGroupName(
                    preview.getGroupLinkId(), subject, preview.getUpdatedAt());
        }
        String avatarUrl = preview.getAvatarUrl();
        boolean avatarObserved = avatarUrl != null && !avatarUrl.isBlank();
        if (subjectObserved || avatarObserved) {
            currentLocalPersistence.applyProfile(new GroupCurrentLocalProfileWrite(
                    preview.getGroupLinkId(), subject, subjectObserved,
                    null, false, avatarUrl, avatarObserved, preview.getUpdatedAt()));
        }
        memberMapper.deleteByGroupLinkId(preview.getGroupLinkId());
        if (members != null && !members.isEmpty()) {
            memberMapper.insertBatch(members);
        }
        writeCurrentGroupSnapshot(preview, members);
        reconcileControlledMemberships(preview);
        return true;
    }

    private void writeCurrentGroupSnapshot(
            GroupLinkPreview preview,
            List<WhatsappGroupMemberSnapshot> members) {
        List<WhatsappGroupMemberSnapshot> snapshotMembers = members == null ? List.of() : members;
        long snapshotAt = snapshotMembers.isEmpty()
                ? preview.getUpdatedAt()
                : snapshotMembers.get(0).getSnapshotAt();
        List<GroupParticipantResult> participants = snapshotMembers.stream()
                .map(member -> new GroupParticipantResult(
                        member.getParticipantJid(),
                        member.getPhone(),
                        member.getIsAdmin(),
                        member.getIsOwner(),
                        member.getRole()))
                .toList();
        currentSnapshotPersistence.replaceCompleteGroupMetadataSnapshot(
                preview,
                participants,
                snapshotAt,
                "metadata:" + preview.getGroupLinkId() + ":" + snapshotAt);
    }

    private void reconcileControlledMemberships(GroupLinkPreview preview) {
        long observedAt = preview.getMetadataObservedAt() == null
                ? preview.getUpdatedAt()
                : preview.getMetadataObservedAt();
        long writtenAt = preview.getUpdatedAt() == null ? observedAt : preview.getUpdatedAt();
        for (AccountGroupMembership membership
                : membershipMapper.selectControlledMembershipsByGroupLinkId(
                        preview.getGroupLinkId())) {
            membership.setMembershipStatus(AccountGroupMembershipStatus.IN_GROUP.code());
            membership.setStatusSource(SNAPSHOT_STATUS_SOURCE);
            membership.setStatusUpdatedAt(observedAt);
            membership.setJoinedAt(observedAt);
            membership.setLastSeenAt(observedAt);
            membership.setCreatedAt(writtenAt);
            membership.setUpdatedAt(writtenAt);
            membershipMapper.upsertMembership(membership);
        }
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
