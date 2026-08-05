package com.armada.task.model.vo;

import com.armada.task.model.enums.PullTaskDisappearingMessageMode;
import com.armada.task.model.enums.PullTaskEditPermissionMode;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import com.armada.task.model.enums.PullTaskLinkPermissionMode;
import com.armada.task.model.enums.PullTaskMuteMode;

/** 普通群链接任务群资料与权限设置回读。 */
public record PullTaskStandardGroupSettingVO(
        PullTaskGroupSettingTiming settingTiming,
        String groupName,
        boolean useMaterialFileNameAsGroupName,
        String avatarFileKey,
        String avatarPreviewUrl,
        String groupDescription,
        boolean autoCloseMuteAfterTask,
        boolean autoCloseInviteAfterTask,
        PullTaskEditPermissionMode editPermission,
        PullTaskMuteMode muteMode,
        PullTaskLinkPermissionMode linkPermission,
        PullTaskDisappearingMessageMode disappearingMessage) {
}
