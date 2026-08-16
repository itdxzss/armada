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
    public void apply(String groupJid, String inviteCode, long observedAt) {
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
        mapper.updateCurrentInvite(tenantId, groupId, inviteId, observedAt, now);
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
        mapper.updateGroupHealth(
                tenantId, normalizedGroupJid, health, System.currentTimeMillis());
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
        mapper.upsertPublicPreview(tenantId, preview, labelId, System.currentTimeMillis());
    }

    private Long resolveGroupId(Long tenantId, String groupJid, long now) {
        if (groupJid == null) {
            return null;
        }
        Long groupId = mapper.selectGroupId(tenantId, groupJid);
        if (groupId != null) {
            return groupId;
        }
        mapper.insertGroup(tenantId, groupJid, now);
        groupId = mapper.selectGroupIdForUpdate(tenantId, groupJid);
        if (groupId == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "新群模型无法解析当前邀请码的群");
        }
        return groupId;
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
