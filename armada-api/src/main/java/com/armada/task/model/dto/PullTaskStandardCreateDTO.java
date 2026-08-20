package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskCreationMode;
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
        PullTaskStandardGroupSettingDTO groupSetting,
        /**
         * 新建模式；为空按群链接模式处理，兼容不传该字段的既有前端。
         *
         * <p>与 {@code pull_task.mode} 无关：后者恒为 {@code NORMAL_LINK}，
         * 两个模式共用同一条执行链路。</p>
         */
        PullTaskCreationMode creationMode,
        /** 建群人账号分组；新群模式必填，群链接模式忽略。 */
        Long creatorGroupId,
        /** 建群时作为初始成员加入的站台数量；为空按 0 处理。 */
        Integer initialStationCount) {

    /** 拒绝合同之外的顶层 JSON 字段。 */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignoredValue) {
        throw new IllegalArgumentException("不支持的拉群任务字段: " + fieldName);
    }
}
