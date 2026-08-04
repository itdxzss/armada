package com.armada.task.service.impl;

import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskStandardReadMapper;
import org.springframework.stereotype.Component;

/** 普通群链接详情的执行行、聚合与明细事实依赖集合。 */
@Component
public record PullTaskStandardReadResources(
        PullTaskGroupExecutionMapper executionMapper,
        PullTaskStandardReadMapper readMapper,
        PullTaskStandardReadFactMappers facts) {
}
