package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.dto.PullTaskStandardGroupSettingDTO;
import com.armada.task.model.entity.PullTaskStandardGroupSetting;
import com.armada.task.model.enums.PullTaskDisappearingMessageMode;
import com.armada.task.model.enums.PullTaskEditPermissionMode;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import com.armada.task.model.enums.PullTaskLinkPermissionMode;
import com.armada.task.model.enums.PullTaskMuteMode;
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

    /**
     * 校验、规范化并写入任务级群资料设置。
     *
     * <p>总开关关闭时整块不走：不校验、不采纳任何用户输入，只落一行全默认值。关闭态下前端
     * 根本不展示这些控件，请求里带来的值没有业务含义，主动丢弃比存着一份不生效的配置更清楚。
     * 行本身仍要写，因为读任务详情要求这一行必须存在。</p>
     */
    public void insert(PullTaskStandardGroupSettingDTO request, long taskId) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群资料设置不能为空");
        }
        long now = System.currentTimeMillis();
        PullTaskStandardGroupSetting row = new PullTaskStandardGroupSetting();
        row.setTaskId(taskId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);

        if (!Boolean.TRUE.equals(request.enabled())) {
            applyDisabledDefaults(row);
            mapper.insert(row);
            return;
        }

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

        row.setGroupSettingEnabled(1);
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
        mapper.insert(row);
    }

    /** 关闭态落表默认值，与建表 DDL 的 DEFAULT 保持一致。 */
    private static void applyDisabledDefaults(PullTaskStandardGroupSetting row) {
        row.setGroupSettingEnabled(0);
        row.setSettingTiming(PullTaskGroupSettingTiming.AFTER_PULL.code());
        row.setGroupName(null);
        row.setMaterialFilenameAsGroupName(0);
        row.setAvatarFileKey(null);
        row.setGroupDescription(null);
        row.setAutoUnmuteAfterTask(0);
        row.setAutoCloseInviteAfterTask(0);
        row.setEditPermissionMode(PullTaskEditPermissionMode.UNCHANGED.code());
        row.setMuteMode(PullTaskMuteMode.UNCHANGED.code());
        row.setLinkPermissionMode(PullTaskLinkPermissionMode.ADMIN_ONLY.code());
        row.setDisappearingMessageMode(PullTaskDisappearingMessageMode.UNCHANGED.code());
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
