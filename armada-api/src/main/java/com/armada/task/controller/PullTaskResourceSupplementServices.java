package com.armada.task.controller;

import com.armada.task.service.PullTaskManagerSupplementService;
import com.armada.task.service.PullTaskPullerSupplementService;
import com.armada.task.service.PullTaskStationSupplementService;
import org.springframework.stereotype.Component;

/** 普通群链接三类人工补充用例集合。 */
@Component
public record PullTaskResourceSupplementServices(
        PullTaskManagerSupplementService managerService,
        PullTaskPullerSupplementService pullerService,
        PullTaskStationSupplementService stationService) {
}
