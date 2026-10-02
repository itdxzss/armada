package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskMaterialAdminTiming;
import com.armada.task.model.enums.PullTaskPullerSyncMode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.List;

/** 群链接模式（新）一次正式创建合同；不接收草稿、管理或提权配置。 */
public record PullTaskDirectLinkCreateDTO(
        String requestId,
        String taskName,
        String remark,
        Integer autoStart,
        Long groupFolderId,
        String linksText,
        List<Long> packageIds,
        Integer earlyPullCount,
        Integer earlyPullCallCount,
        Integer pullCountMin,
        Integer pullCountMax,
        Integer pullIntervalSeconds,
        Integer pullerCountPerGroup,
        Integer stationCountPerCall,
        Integer concurrentGroupCount,
        Long pullerGroupId,
        Long stationGroupId,
        Long pullerFinishGroupId) {

    /** 只复用冻结配置写入器；无草稿 ID，禁用配置由服务端给定。 */
    public PullTaskStandardCreateDTO frozenSettings() {
        var disabled = new PullTaskStandardGroupSettingDTO(false, null, null, false,
                null, null, false, false, null, null, null, null);
        return new PullTaskStandardCreateDTO(null, taskName, remark, autoStart, groupFolderId,
                PullTaskPullerSyncMode.SINGLE, PullTaskMaterialAdminTiming.AFTER_GROUP_DONE.code(),
                false, true, earlyPullCount, earlyPullCallCount, pullCountMin, pullCountMax,
                pullIntervalSeconds, pullerCountPerGroup, stationCountPerCall, concurrentGroupCount,
                null, pullerGroupId, stationCountPerCall != null && stationCountPerCall > 0 ? stationGroupId : null,
                null, pullerFinishGroupId, disabled, PullTaskCreationMode.DIRECT_LINK, null, 0, false);
    }

    /** 隐藏的旧模式字段不能通过直接创建接口偷偷生效。 */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("不支持的新群链接任务字段: " + name);
    }
}
