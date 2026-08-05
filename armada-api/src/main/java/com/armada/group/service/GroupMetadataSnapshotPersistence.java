package com.armada.group.service;

import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import java.util.List;

/** 群详情预览和完整成员快照的原子持久化边界。 */
public interface GroupMetadataSnapshotPersistence {

    /** 仅当观察时间不旧于当前快照时原子替换预览和成员。 */
    boolean persist(GroupLinkPreview preview, List<WhatsappGroupMemberSnapshot> members);
}
