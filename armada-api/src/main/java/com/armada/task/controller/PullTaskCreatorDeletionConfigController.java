package com.armada.task.controller;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import com.armada.task.model.dto.PullTaskCreatorDeletionConfigDTO;
import com.armada.task.service.impl.PullTaskCreatorDeletionConfigService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/** 创建者注销的任务级配置入口。 */
@RestController
@RequestMapping("/api/pull-tasks/standard")
public class PullTaskCreatorDeletionConfigController {
    private final PullTaskCreatorDeletionConfigService service;
    /** 注入配置服务。 */
    public PullTaskCreatorDeletionConfigController(PullTaskCreatorDeletionConfigService service) {
        this.service = service;
    }
    /** 草稿及首次启动前允许编辑；租户来自可信上下文。 */
    @PutMapping("/{taskId}/creator-deletion")
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<Void> update(@PathVariable long taskId,
            @RequestBody PullTaskCreatorDeletionConfigDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        service.update(taskId, principal.userId(), request);
        return ApiResponse.ok();
    }
}
