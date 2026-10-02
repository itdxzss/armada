package com.armada.task.scheduler;

import com.armada.group.service.GroupFolderService;
import org.springframework.stereotype.Component;

/** 收口事务需要的群归档、拉手归档与父任务聚合服务。 */
@Component
public record PullTaskClosingResources(
        PullTaskParentCompletionService parentCompletionService,
        GroupFolderService groupFolderService,
        PullTaskDirectLinkFinishArchiveService directLinkFinishArchiveService) {
}
