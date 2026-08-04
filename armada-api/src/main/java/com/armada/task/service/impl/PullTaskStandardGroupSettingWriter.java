package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.dto.PullTaskStandardGroupSettingDTO;
import com.armada.task.model.entity.PullTaskStandardGroupSetting;
import org.springframework.stereotype.Service;

/** 创建普通群链接任务时组装并写入群资料与权限设置。 */
@Service
public class PullTaskStandardGroupSettingWriter {

    private static final int GROUP_NAME_MAX_LENGTH = 128;
    private static final int DESCRIPTION_MAX_LENGTH = 1024;
    private static final int AVATAR_KEY_MAX_LENGTH = 512;

    private final PullTaskStandardGroupSettingMapper mapper;

    public PullTaskStandardGroupSettingWriter(PullTaskStandardGroupSettingMapper mapper) {
        this.mapper = mapper;
    }

    /** 校验、规范化并写入任务级群资料设置。 */
    public void insert(PullTaskStandardGroupSettingDTO request, long taskId) {
        validateRequired(request);
        String groupName = trimToNull(request.groupName());
        String description = trimToNull(request.groupDescription());
        String avatarKey = trimToNull(request.avatarFileKey());
        validateLength(groupName, GROUP_NAME_MAX_LENGTH, "群名称");
        validateLength(description, DESCRIPTION_MAX_LENGTH, "群描述");
        validateLength(avatarKey, AVATAR_KEY_MAX_LENGTH, "群头像文件 key");
        if (Boolean.TRUE.equals(request.useMaterialFileNameAsGroupName())) {
            groupName = null;
        }

        long now = System.currentTimeMillis();
        PullTaskStandardGroupSetting row = new PullTaskStandardGroupSetting();
        row.setTaskId(taskId);
        row.setSettingTiming(request.settingTiming().code());
        row.setGroupName(groupName);
        row.setMaterialFilenameAsGroupName(
                Boolean.TRUE.equals(request.useMaterialFileNameAsGroupName()) ? 1 : 0);
        row.setAvatarFileKey(avatarKey);
        row.setGroupDescription(description);
        row.setAutoUnmuteAfterTask(Boolean.TRUE.equals(request.autoCloseMuteAfterTask()) ? 1 : 0);
        row.setAutoCloseInviteAfterTask(
                Boolean.TRUE.equals(request.autoCloseInviteAfterTask()) ? 1 : 0);
        row.setEditPermissionMode(request.editPermission().code());
        row.setMuteMode(request.muteMode().code());
        row.setLinkPermissionMode(request.linkPermission().code());
        row.setDisappearingMessageMode(request.disappearingMessage().code());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        mapper.insert(row);
    }

    private void validateRequired(PullTaskStandardGroupSettingDTO request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群资料设置不能为空");
        }
        if (request.settingTiming() == null
                || request.useMaterialFileNameAsGroupName() == null
                || request.autoCloseMuteAfterTask() == null
                || request.autoCloseInviteAfterTask() == null
                || request.editPermission() == null
                || request.muteMode() == null
                || request.linkPermission() == null
                || request.disappearingMessage() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群资料设置选项不能为空");
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private static void validateLength(String value, int maxLength, String fieldName) {
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    fieldName + "不能超过 " + maxLength + " 个字符");
        }
    }
}
