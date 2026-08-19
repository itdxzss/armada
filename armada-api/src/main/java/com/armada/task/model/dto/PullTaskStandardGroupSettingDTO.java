package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskDisappearingMessageMode;
import com.armada.task.model.enums.PullTaskEditPermissionMode;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import com.armada.task.model.enums.PullTaskLinkPermissionMode;
import com.armada.task.model.enums.PullTaskMuteMode;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * 普通群链接任务的群资料与权限设置。
 *
 * <p>{@code enabled} 是整块设置的总开关。关闭时其余字段一律不落库，也不参与必填与长度
 * 校验——前端在关闭态下根本不展示这些控件，它们的取值没有业务含义。</p>
 */
public record PullTaskStandardGroupSettingDTO(
        Boolean enabled,
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
