package com.armada.group.service.impl;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.dto.GroupCurrentLocalProfileWrite;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
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
    private final AccountGroupMembershipMapper membershipMapper;
    private final GroupInviteLinkService inviteLinkService;
    private final AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;
    private final GroupCurrentLocalPersistence currentLocalPersistence;

    /**
     * 创建快照持久化实现。
     *
     * @param previewMapper 群预览数据访问
     * @param membershipMapper 账号群关系数据访问
     * @param inviteLinkService 当前群邀请链接事实服务
     * @param currentSnapshotPersistence 新群模型当前成员快照写入
     */
    public GroupMetadataSnapshotPersistenceImpl(
            GroupLinkPreviewMapper previewMapper,
            AccountGroupMembershipMapper membershipMapper,
            GroupInviteLinkService inviteLinkService,
            AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence,
            GroupCurrentLocalPersistence currentLocalPersistence) {
        this.previewMapper = previewMapper;
        this.membershipMapper = membershipMapper;
        this.inviteLinkService = inviteLinkService;
        this.currentSnapshotPersistence = currentSnapshotPersistence;
        this.currentLocalPersistence = currentLocalPersistence;
    }

    @Override
    @Transactional
    public boolean persist(GroupLinkPreview preview, List<WhatsappGroupMemberSnapshot> members) {
        if (Boolean.TRUE.equals(preview.getOwnerPhoneObserved())
                || Boolean.TRUE.equals(preview.getCreatorCountryObserved())) {
            previewMapper.upsertCreatorCompatibility(List.of(preview));
        }
        applyCurrentInvite(preview);
        String subject = preview.getWaSubject();
        boolean subjectObserved = subject != null && !subject.isBlank();
        String avatarUrl = preview.getAvatarUrl();
        boolean avatarObserved = avatarUrl != null && !avatarUrl.isBlank();
        if (subjectObserved || avatarObserved) {
            currentLocalPersistence.applyProfile(new GroupCurrentLocalProfileWrite(
                    preview.getGroupLinkId(), subject, subjectObserved,
                    null, false, avatarUrl, avatarObserved, preview.getUpdatedAt()));
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
                        // 由本地快照行重建，不是协议观察，缺少可信 PN 来源时不猜身份。
                        null,
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
        String eventId = "metadata:" + preview.getGroupLinkId() + ":" + observedAt;
        for (AccountGroupMembership membership
                : membershipMapper.selectControlledMembershipsByGroupLinkId(
                        preview.getGroupLinkId())) {
            currentSnapshotPersistence.applyControlledParticipantObservation(
                    membership.getAccountId(), membership.getGroupJid(), true,
                    Boolean.TRUE.equals(membership.getAdmin()), observedAt,
                    eventId, SNAPSHOT_STATUS_SOURCE);
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
