package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskDisappearingMessageMode;
import com.armada.task.model.enums.PullTaskEditPermissionMode;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import com.armada.task.model.enums.PullTaskLinkPermissionMode;
import com.armada.task.model.enums.PullTaskMuteMode;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/** 普通群链接任务的群资料与权限设置。 */
public record PullTaskStandardGroupSettingDTO(
        PullTaskGroupSettingTiming settingTiming,
        String groupName,
        Boolean useMaterialFileNameAsGroupName,
        String avatarFileKey,
        String groupDescription,
        Boolean autoCloseMuteAfterTask,
        Boolean autoCloseInviteAfterTask,
        PullTaskEditPermissionMode editPermission,
        PullTaskMuteMode muteMode,
        PullTaskLinkPermissionMode linkPermission,
        PullTaskDisappearingMessageMode disappearingMessage) {

    /** 拒绝合同之外的群资料 JSON 字段。 */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignoredValue) {
        throw new IllegalArgumentException("不支持的群资料字段: " + fieldName);
    }
}
