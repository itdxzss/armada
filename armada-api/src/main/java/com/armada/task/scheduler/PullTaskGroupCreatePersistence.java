package com.armada.task.scheduler;

import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import org.springframework.stereotype.Component;

/** 聚合建群阶段使用的任务域持久化依赖。 */
@Component
public record PullTaskGroupCreatePersistence(
        PullTaskStandardSettingMapper settingMapper,
        PullTaskStandardGroupSettingMapper groupSettingMapper,
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskGroupAccountMapper accountMapper) {
}
