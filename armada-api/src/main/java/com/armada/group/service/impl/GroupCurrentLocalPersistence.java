package com.armada.group.service.impl;

import com.armada.group.mapper.GroupCurrentLocalMapper;
import com.armada.group.model.dto.GroupCurrentLocalProfileWrite;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.springframework.stereotype.Service;

/** 保持旧群入口写行为不变，同时同步新群模型的本地展示字段。 */
@Service
public class GroupCurrentLocalPersistence {

    private final GroupCurrentLocalMapper mapper;

    public GroupCurrentLocalPersistence(GroupCurrentLocalMapper mapper) {
        this.mapper = mapper;
    }

    public void applyProfile(GroupCurrentLocalProfileWrite row) {
        Long tenantId = requiredTenantId();
        mapper.updateResolvedGroupProfile(tenantId, row);
        mapper.updateUnresolvedInviteProfile(tenantId, row);
    }

    public void applyInviteLabel(List<Long> groupLinkIds, Long labelId, long updatedAt) {
        mapper.updateInviteLabel(requiredTenantId(), groupLinkIds, labelId, updatedAt);
    }

    public void applyGroupFolder(List<Long> groupLinkIds, Long folderId, long updatedAt) {
        mapper.updateGroupFolder(requiredTenantId(), groupLinkIds, folderId, updatedAt);
    }

    private static Long requiredTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return tenantId;
    }
}
