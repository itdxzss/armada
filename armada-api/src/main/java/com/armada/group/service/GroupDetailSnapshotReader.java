package com.armada.group.service;

import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import java.util.List;

/** 群详情本地快照只读访问。 */
public interface GroupDetailSnapshotReader {

    /** 读取最后一次完整成员快照。 */
    List<WhatsappGroupMemberSnapshot> members(Long groupLinkId);

    /** 读取单群耐久 metadata 同步任务。 */
    GroupMetadataSyncTask task(Long groupLinkId);
}
