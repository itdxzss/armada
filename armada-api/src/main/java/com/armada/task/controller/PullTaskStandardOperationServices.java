package com.armada.task.controller;

import com.armada.task.service.PullTaskStandardExecutionLifecycleService;
import com.armada.task.service.PullTaskStandardLifecycleService;
import com.armada.task.service.PullTaskStandardStartService;
import org.springframework.stereotype.Component;

/** 普通群链接 Controller 的启动、任务生命周期和单群生命周期服务集合。 */
@Component
public record PullTaskStandardOperationServices(
        PullTaskStandardStartService startService,
        PullTaskStandardLifecycleService lifecycleService,
        PullTaskStandardExecutionLifecycleService executionLifecycleService,
        PullTaskResourceSupplementServices supplementServices) {
}
