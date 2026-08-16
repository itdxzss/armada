package com.armada.group.service.impl;

import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.mapper.GroupListCurrentMapper;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.entity.WhatsappGroupMemberSnapshot;
import com.armada.group.service.GroupDetailSnapshotReader;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.springframework.stereotype.Component;

/** MyBatis 实现的群详情本地快照读取器。 */
@Component
public class GroupDetailSnapshotReaderImpl implements GroupDetailSnapshotReader {

    private final GroupListCurrentMapper currentMapper;
    private final GroupMetadataSyncTaskMapper taskMapper;

    public GroupDetailSnapshotReaderImpl(
            GroupListCurrentMapper currentMapper,
            GroupMetadataSyncTaskMapper taskMapper) {
        this.currentMapper = currentMapper;
        this.taskMapper = taskMapper;
    }

    @Override
    public GroupLinkPreview profile(Long groupLinkId) {
        return currentMapper.selectGroupDetail(TenantContext.get(), groupLinkId);
    }

    @Override
    public List<WhatsappGroupMemberSnapshot> members(Long groupLinkId) {
        return currentMapper.selectGroupDetailMembers(TenantContext.get(), groupLinkId);
    }

    @Override
    public GroupMetadataSyncTask task(Long groupLinkId) {
        return taskMapper.selectByGroupLinkId(groupLinkId);
    }
}
