package com.armada.task.service.impl;

import com.armada.group.service.GroupLinkRegistryService;
import com.armada.task.service.PullTaskGroupAvatarService;
import org.springframework.stereotype.Component;

/** 正式提交冻结配置与来源资源所需服务，所有操作沿用同一事务。 */
@Component
public record PullTaskStandardCreateResources(PullTaskStandardSettingWriter settingWriter,
        PullTaskStandardGroupSettingWriter groupSettingWriter, PullTaskGroupAvatarService avatarService,
        GroupLinkRegistryService groupLinkRegistryService, PullTaskDataPackageSourceService dataPackageSourceService) { }
