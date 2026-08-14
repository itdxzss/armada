package com.armada.task.model.vo;

import com.armada.task.model.enums.PullTaskPullerSyncMode;

/** 普通群链接任务冻结执行设置回读。 */
public record PullTaskStandardSettingVO(
        int autoStart,
        Long groupFolderId,
        String groupFolderName,
        PullTaskPullerSyncMode pullerSyncMode,
        int materialAdminTiming,
        boolean clearExistingMembers,
        boolean pullerJoinByLink,
        int earlyPullCount,
        int earlyPullCallCount,
        int pullCountMin,
        int pullCountMax,
        int pullIntervalSeconds,
        int pullerCountPerGroup,
        int stationCountPerCall,
        int concurrentGroupCount,
        Long managerGroupId,
        String managerGroupName,
        Long pullerGroupId,
        String pullerGroupName,
        Long stationGroupId,
        String stationGroupName,
        Long managerFinishGroupId,
        String managerFinishGroupName,
        Long pullerFinishGroupId,
        String pullerFinishGroupName) {
}
