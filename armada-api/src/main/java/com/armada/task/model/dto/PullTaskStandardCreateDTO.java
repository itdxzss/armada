package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskPullerSyncMode;
import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * 普通群链接任务整单提交入参。
 *
 * <p>只包含本期已批准且会持久化的字段。营销模板、发送规则以及所有标记“后期”的字段
 * 不属于该合同；未知 JSON 字段会被明确拒绝，避免前端误以为配置已经生效。</p>
 */
public record PullTaskStandardCreateDTO(
        Long draftTaskId,
        String taskName,
        String remark,
        Integer autoStart,
        Long groupFolderId,
        PullTaskPullerSyncMode pullerSyncMode,
        Integer materialAdminTiming,
        Boolean clearExistingMembers,
        Boolean pullerJoinByLink,
        Integer earlyPullCount,
        Integer earlyPullCallCount,
        Integer pullCountMin,
        Integer pullCountMax,
        Integer pullIntervalSeconds,
        Integer pullerCountPerGroup,
        Integer stationCountPerCall,
        Integer concurrentGroupCount,
        Long managerGroupId,
        Long pullerGroupId,
        Long stationGroupId,
        Long managerFinishGroupId,
        Long pullerFinishGroupId,
        PullTaskStandardGroupSettingDTO groupSetting) {

    /** 拒绝合同之外的顶层 JSON 字段。 */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignoredValue) {
        throw new IllegalArgumentException("不支持的拉群任务字段: " + fieldName);
    }
}
