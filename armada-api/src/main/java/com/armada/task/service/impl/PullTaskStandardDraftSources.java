package com.armada.task.service.impl;

import com.armada.group.service.GroupFolderService;
import com.armada.task.service.PullTaskLinkProbeService;
import org.springframework.stereotype.Component;

/** 创建页的外部来源服务；将来源依赖与草稿持久化依赖分开。 */
@Component
public record PullTaskStandardDraftSources(PullTaskLinkProbeService probeService,
        GroupFolderService groupFolderService, PullTaskDataPackageSourceService dataPackageSourceService) { }
