package com.armada.group.service.impl;

import com.armada.group.mapper.GroupMetadataPatchMapper;
import com.armada.group.model.dto.GroupMetadataPatch;
import com.armada.group.model.dto.GroupMetadataPatchField;
import com.armada.group.model.dto.GroupMetadataPatchRow;
import com.armada.group.service.GroupMetadataPatchService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 按字段版本把群资料 patch 写入 wa_group_profile。 */
@Service
public class GroupMetadataPatchServiceImpl implements GroupMetadataPatchService {

    private static final int SUBJECT_MAX_LENGTH = 255;
    private static final int DESCRIPTION_MAX_LENGTH = 1024;

    private final GroupMetadataPatchMapper mapper;

    public GroupMetadataPatchServiceImpl(GroupMetadataPatchMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean applyPatch(GroupMetadataPatch patch) {
        validate(patch);
        if (patch.fieldMask() == null || patch.fieldMask().isEmpty()) {
            // 空 mask 不是错误：协议可能只带 id/author 而没有受支持字段，确认消费即可。
            return false;
        }
        long now = System.currentTimeMillis();
        Long groupId = resolveGroupId(patch.groupJid(), now);
        return mapper.upsertFieldPatch(toRow(patch, groupId, now)) > 0;
    }

    private void validate(GroupMetadataPatch patch) {
        if (patch == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群资料 patch 为空");
        }
        if (TenantContext.get() == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        if (blankToNull(patch.groupJid()) == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群资料 patch 缺少 groupJid");
        }
        if (patch.source() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群资料 patch 缺少来源");
        }
        if (patch.observedAt() <= 0) {
            // 事实时间非法时拒绝，绝不用消费时间伪造：那会让旧事实获得最新水位并永久压过真实事件。
            throw new BusinessException(ErrorCode.VALIDATION, "群资料 patch 事实时间非法");
        }
    }

    /** 解析群主键；群尚未建档时创建最小群身份后重查。 */
    private Long resolveGroupId(String groupJid, long now) {
        String normalized = groupJid.trim();
        Long groupId = mapper.selectGroupIdByJid(normalized);
        if (groupId != null) {
            return groupId;
        }
        mapper.insertMinimalGroup(normalized, now);
        groupId = mapper.selectGroupIdByJid(normalized);
        if (groupId == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "群资料 patch 无法解析 groupId");
        }
        return groupId;
    }

    /**
     * 把 fieldMask 归约成持久化行。
     *
     * <p>未进 mask 的字段值与版本列一律置 null，SQL 据此判断"本次未观察到"；进了 mask 的字段
     * 即使值为 false/0/空描述也带上来源与事实时间，保证明确关闭与未观察可区分。</p>
     */
    private GroupMetadataPatchRow toRow(GroupMetadataPatch patch, Long groupId, long now) {
        String source = patch.source().name();
        long observedAt = patch.observedAt();
        return new GroupMetadataPatchRow(
                groupId,
                patch.source().rank(),
                now,
                observed(patch, GroupMetadataPatchField.SUBJECT)
                        ? clamp(patch.subject(), SUBJECT_MAX_LENGTH) : null,
                versionValue(patch, GroupMetadataPatchField.SUBJECT, source),
                versionAt(patch, GroupMetadataPatchField.SUBJECT, observedAt),
                observed(patch, GroupMetadataPatchField.DESCRIPTION)
                        ? clamp(patch.description(), DESCRIPTION_MAX_LENGTH) : null,
                versionValue(patch, GroupMetadataPatchField.DESCRIPTION, source),
                versionAt(patch, GroupMetadataPatchField.DESCRIPTION, observedAt),
                observed(patch, GroupMetadataPatchField.ANNOUNCE_ONLY)
                        ? patch.announceOnly() : null,
                versionValue(patch, GroupMetadataPatchField.ANNOUNCE_ONLY, source),
                versionAt(patch, GroupMetadataPatchField.ANNOUNCE_ONLY, observedAt),
                observed(patch, GroupMetadataPatchField.ADMIN_ONLY_EDIT_INFO)
                        ? patch.adminOnlyEditInfo() : null,
                versionValue(patch, GroupMetadataPatchField.ADMIN_ONLY_EDIT_INFO, source),
                versionAt(patch, GroupMetadataPatchField.ADMIN_ONLY_EDIT_INFO, observedAt),
                observed(patch, GroupMetadataPatchField.MEMBER_ADD_MODE)
                        ? patch.memberAddMode() : null,
                versionValue(patch, GroupMetadataPatchField.MEMBER_ADD_MODE, source),
                versionAt(patch, GroupMetadataPatchField.MEMBER_ADD_MODE, observedAt),
                observed(patch, GroupMetadataPatchField.JOIN_APPROVAL_MODE)
                        ? patch.joinApprovalMode() : null,
                versionValue(patch, GroupMetadataPatchField.JOIN_APPROVAL_MODE, source),
                versionAt(patch, GroupMetadataPatchField.JOIN_APPROVAL_MODE, observedAt),
                observed(patch, GroupMetadataPatchField.EPHEMERAL_DURATION_SECONDS)
                        ? patch.ephemeralDurationSeconds() : null,
                versionValue(patch, GroupMetadataPatchField.EPHEMERAL_DURATION_SECONDS, source),
                versionAt(patch, GroupMetadataPatchField.EPHEMERAL_DURATION_SECONDS, observedAt));
    }

    private static boolean observed(GroupMetadataPatch patch, GroupMetadataPatchField field) {
        return patch.observed(field);
    }

    private static String versionValue(
            GroupMetadataPatch patch, GroupMetadataPatchField field, String source) {
        return patch.observed(field) ? source : null;
    }

    private static Long versionAt(
            GroupMetadataPatch patch, GroupMetadataPatchField field, long observedAt) {
        return patch.observed(field) ? observedAt : null;
    }

    private static String clamp(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
