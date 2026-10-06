package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskDisappearingMessageMode;
import com.armada.task.model.enums.PullTaskEditPermissionMode;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import com.armada.task.model.enums.PullTaskLinkPermissionMode;
import com.armada.task.model.enums.PullTaskMaterialAdminTiming;
import com.armada.task.model.enums.PullTaskMuteMode;
import com.armada.task.model.enums.PullTaskPullerSyncMode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.List;

/** 新群模式（新）：只接收建群、接管注销及拉人所需配置，一次创建正式任务。 */
public record PullTaskSimpleNewGroupCreateDTO(
        String requestId, String taskName, String remark, Integer autoStart,
        List<Long> packageIds, Integer earlyPullCount, Integer earlyPullCallCount,
        Integer pullCountMin, Integer pullCountMax, Integer pullIntervalSeconds,
        Integer pullerCountPerGroup, Integer stationCountPerCall, Integer concurrentGroupCount,
        Long pullerGroupId, Long stationGroupId, Long pullerFinishGroupId,
        Long creatorGroupId, Long managerGroupId, Long managerFinishGroupId,
        Boolean creatorDeleteAfterTakeover, String groupName, String avatarFileKey,
        String groupDescription, Integer pullIntervalMaxSeconds) implements PullTaskDirectCreateRequest {

    /** 自建群没有来源分组。 */
    @Override
    public Long groupFolderId() { return null; }

    /** 自建群不接收邀请链接。 */
    @Override
    public String linksText() { return null; }

    /** 固定建群后资料设置、普通拉手链接入群；保留独立管理接管和可选注销。 */
    @Override
    public PullTaskStandardCreateDTO frozenSettings() {
        var profile = new PullTaskStandardGroupSettingDTO(true, PullTaskGroupSettingTiming.BEFORE_PULL,
                groupName, false, avatarFileKey, groupDescription, false, false,
                PullTaskEditPermissionMode.UNCHANGED, PullTaskMuteMode.UNCHANGED,
                PullTaskLinkPermissionMode.ADMIN_ONLY, PullTaskDisappearingMessageMode.UNCHANGED);
        return new PullTaskStandardCreateDTO(null, taskName, remark, autoStart, null,
                PullTaskPullerSyncMode.SINGLE, PullTaskMaterialAdminTiming.AFTER_GROUP_DONE.code(),
                false, true, earlyPullCount, earlyPullCallCount, pullCountMin, pullCountMax,
                pullIntervalSeconds, pullerCountPerGroup, stationCountPerCall, concurrentGroupCount,
                managerGroupId, pullerGroupId,
                stationCountPerCall != null && stationCountPerCall > 0 ? stationGroupId : null,
                managerFinishGroupId, pullerFinishGroupId, profile, PullTaskCreationMode.SIMPLE_NEW_GROUP,
                creatorGroupId, 0, false, pullIntervalMaxSeconds,
                Boolean.TRUE.equals(creatorDeleteAfterTakeover));
    }

    /** 不接受草稿、清群、资料时机、群主退群等已移除选项。 */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("不支持的新群模式（新）字段: " + name);
    }
}
