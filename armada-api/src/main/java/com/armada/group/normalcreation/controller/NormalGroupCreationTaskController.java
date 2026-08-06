package com.armada.group.normalcreation.controller;

import com.armada.group.normalcreation.model.dto.NormalGroupCreationCreateDTO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskDetailVO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskVO;
import com.armada.group.normalcreation.service.NormalGroupCreationService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 群组列表“新建普群”异步任务接口。 */
@RestController
@RequestMapping("/api/normal-group-creation-tasks")
public class NormalGroupCreationTaskController {

    private final NormalGroupCreationService service;

    public NormalGroupCreationTaskController(NormalGroupCreationService service) {
        this.service = service;
    }

    /** 创建任务并返回已冻结的任务摘要，不等待 WhatsApp 执行。 */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:normal_group:create')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<NormalGroupCreationTaskVO> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody NormalGroupCreationCreateDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.create(idempotencyKey, request, principal.userId()));
    }

    /** 查询任务进度和逐群失败明细。 */
    @GetMapping("/{taskId}")
    @PreAuthorize("hasAuthority('tenant:normal_group:view')")
    public ApiResponse<NormalGroupCreationTaskDetailVO> detail(@PathVariable long taskId) {
        return ApiResponse.ok(service.detail(taskId));
    }

    /** 人工重试一个明确失败的计划群；结果未知项由人工对账处理。 */
    @PostMapping("/{taskId}/items/{itemId}/retry")
    @PreAuthorize("hasAuthority('tenant:normal_group:retry')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Void> retry(
            @PathVariable long taskId,
            @PathVariable long itemId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        service.retry(taskId, itemId, principal.userId());
        return ApiResponse.ok();
    }
}
