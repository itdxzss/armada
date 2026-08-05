package com.armada.group.service.impl;

import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.mapper.WhatsappGroupMemberSnapshotMapper;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.group.service.GroupDetailSnapshotReader;
import java.util.List;
import org.springframework.stereotype.Component;

/** MyBatis 实现的群详情本地快照读取器。 */
@Component
public class GroupDetailSnapshotReaderImpl implements GroupDetailSnapshotReader {

    private final WhatsappGroupMemberSnapshotMapper memberMapper;
    private final GroupMetadataSyncTaskMapper taskMapper;

    public GroupDetailSnapshotReaderImpl(
            WhatsappGroupMemberSnapshotMapper memberMapper,
            GroupMetadataSyncTaskMapper taskMapper) {
        this.memberMapper = memberMapper;
        this.taskMapper = taskMapper;
    }

    @Override
    public List<WhatsappGroupMemberSnapshot> members(Long groupLinkId) {
        return memberMapper.selectByGroupLinkId(groupLinkId);
    }

    @Override
    public GroupMetadataSyncTask task(Long groupLinkId) {
        return taskMapper.selectByGroupLinkId(groupLinkId);
    }
}
