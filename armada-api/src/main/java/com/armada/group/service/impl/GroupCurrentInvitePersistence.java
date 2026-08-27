package com.armada.group.service.impl;

import com.armada.group.mapper.GroupCurrentInviteMapper;
import com.armada.group.model.entity.GroupLinkHealth;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将现有当前邀请码事实同步写入新群模型。 */
@Service
public class GroupCurrentInvitePersistence {

    private final GroupCurrentInviteMapper mapper;

    public GroupCurrentInvitePersistence(GroupCurrentInviteMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void apply(Long groupLinkId, String groupJid, String inviteCode, long observedAt) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        String code = requiredText(inviteCode, "当前邀请码为空");
        if (observedAt <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "当前邀请码缺少观察时间");
        }
        String normalizedGroupJid = normalizeGroupJid(groupJid);
        long now = System.currentTimeMillis();
        lockLegacyGroupLink(tenantId, groupLinkId);
        Long groupId = resolveGroupId(tenantId, normalizedGroupJid, now);
        if (groupId != null) {
            mapper.lockProfile(tenantId, groupId, now);
        }
        mapper.upsertInvite(tenantId, groupId, code, observedAt, now);
        if (groupId == null) {
            return;
        }
        Long inviteId = mapper.selectInviteIdForUpdate(tenantId, code);
        if (inviteId == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法解析当前邀请码");
        }
        if (groupLinkId != null) {
            mapper.updateLegacyGroupAndInviteReferences(
                    tenantId, groupLinkId, groupId, inviteId);
        }
        mapper.updateCurrentInvite(tenantId, groupId, inviteId, observedAt, now);
    }

    /** 将已确认的群 JID 绑定到保留的数字群入口，不要求当时已经存在邀请码。 */
    @Transactional(rollbackFor = Exception.class)
    public void bindGroup(Long groupLinkId, String groupJid, long observedAt) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        if (groupLinkId == null || observedAt <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "群入口与群 JID 绑定事实不完整");
        }
        String normalizedGroupJid = normalizeGroupJid(groupJid);
        if (normalizedGroupJid == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群入口与群 JID 绑定事实不完整");
        }
        lockLegacyGroupLink(tenantId, groupLinkId);
        Long groupId = resolveGroupId(tenantId, normalizedGroupJid, System.currentTimeMillis());
        mapper.updateGroupReference(tenantId, groupLinkId, groupId);
    }

    /** 将现有按群 JID 检测的健康结论同步到群资料，不依赖当前邀请码。 */
    @Transactional(rollbackFor = Exception.class)
    public void applyHealth(String groupJid, GroupLinkHealth health) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        String normalizedGroupJid = normalizeGroupJid(groupJid);
        if (normalizedGroupJid == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群链接健康结果缺少群 JID");
        }
        Long groupId = mapper.selectAnyGroupId(tenantId, normalizedGroupJid);
        if (groupId == null || mapper.selectGroupIdByIdForUpdate(tenantId, groupId) == null) {
            return;
        }
        mapper.updateGroupHealth(
                tenantId, groupId, health, System.currentTimeMillis());
    }

    /** 读取群资料中的当前健康投影，供失败次数和成员数续写。 */
    @Transactional(readOnly = true)
    public GroupLinkHealth findHealth(String groupJid) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        String normalizedGroupJid = normalizeGroupJid(groupJid);
        return normalizedGroupJid == null
                ? null : mapper.selectGroupHealth(tenantId, normalizedGroupJid);
    }

    /** 将已通过导入校验的公开邀请页资料同步到新邀请表。 */
    @Transactional(rollbackFor = Exception.class)
    public void applyPublicPreview(GroupLinkPreview preview, Long labelId) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        if (preview == null || preview.getInviteCode() == null
                || labelId == null || preview.getLastPreviewAt() == null
                || preview.getLastPreviewAt() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "公开邀请页资料不完整");
        }
        lockLegacyGroupLink(tenantId, preview.getGroupLinkId());
        mapper.upsertPublicPreview(tenantId, preview, labelId, System.currentTimeMillis());
        Long inviteId = mapper.selectInviteIdForUpdate(tenantId, preview.getInviteCode().trim());
        if (inviteId == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法解析公开预览邀请");
        }
        mapper.updateLegacyInviteReference(
                tenantId, preview.getGroupLinkId(), inviteId);
    }

    private Long resolveGroupId(Long tenantId, String groupJid, long now) {
        if (groupJid == null) {
            return null;
        }
        Long groupId = mapper.selectGroupId(tenantId, groupJid);
        if (groupId != null) {
            return lockGroupId(tenantId, groupId);
        }
        groupId = mapper.selectAnyGroupId(tenantId, groupJid);
        if (groupId != null) {
            Long lockedGroupId = lockGroupId(tenantId, groupId);
            mapper.insertGroup(tenantId, groupJid, now);
            return lockedGroupId;
        }
        mapper.insertGroup(tenantId, groupJid, now);
        groupId = mapper.selectGroupIdForUpdate(tenantId, groupJid);
        if (groupId == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法解析当前邀请码的群");
        }
        return lockGroupId(tenantId, groupId);
    }

    private Long lockGroupId(Long tenantId, Long groupId) {
        Long lockedGroupId = mapper.selectGroupIdByIdForUpdate(tenantId, groupId);
        if (lockedGroupId == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法锁定当前邀请码的群");
        }
        return lockedGroupId;
    }

    private void lockLegacyGroupLink(Long tenantId, Long groupLinkId) {
        if (groupLinkId != null) {
            mapper.selectLegacyGroupLinkIdByIdForUpdate(tenantId, groupLinkId);
        }
    }

    private static String normalizeGroupJid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String groupJid = value.trim().toLowerCase(Locale.ROOT);
        if (!groupJid.endsWith("@g.us")) {
            throw new BusinessException(ErrorCode.VALIDATION, "当前邀请码的群 JID 非法");
        }
        return groupJid;
    }

    private static String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, message);
        }
        return value.trim();
    }
}
