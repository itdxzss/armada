package com.armada.feed.task.controller;

import com.armada.feed.task.model.dto.FeedTaskActionRequest;
import com.armada.feed.task.model.dto.FeedTaskFormDTO;
import com.armada.feed.task.model.dto.FeedTaskQuery;
import com.armada.feed.task.model.vo.FeedTaskAccountVO;
import com.armada.feed.task.model.vo.FeedTaskVO;
import com.armada.feed.task.service.FeedTaskService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 动态发布任务接口。 */
@RestController
@RequestMapping("/api/feed-tasks")
@PreAuthorize("hasAuthority('tenant:feed_task:view')")
public class FeedTaskController {

    private final FeedTaskService service;

    public FeedTaskController(FeedTaskService service) {
        this.service = service;
    }

    /** 分页查询动态发布任务。 */
    @GetMapping
    public ApiResponse<PageResult<FeedTaskVO>> list(@ModelAttribute FeedTaskQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /** 查询动态发布任务详情。 */
    @GetMapping("/{id}")
    public ApiResponse<FeedTaskVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(id));
    }

    /** 创建动态发布任务。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('tenant:feed_task:create')")
    public ApiResponse<FeedTaskVO> create(
            @ModelAttribute FeedTaskFormDTO request,
            @RequestParam(value = "linkPreviewImage", required = false) MultipartFile linkPreviewImage,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.create(request, linkPreviewImage, principal.userId()));
    }

    /** 编辑未开始的动态发布任务。 */
    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('tenant:feed_task:edit')")
    public ApiResponse<FeedTaskVO> update(
            @PathVariable Long id,
            @ModelAttribute FeedTaskFormDTO request,
            @RequestParam(value = "linkPreviewImage", required = false) MultipartFile linkPreviewImage) {
        return ApiResponse.ok(service.update(id, request, linkPreviewImage));
    }

    /** 执行动作：start / pause / resume / stop。 */
    @PostMapping("/{id}/action")
    @PreAuthorize("hasAuthority('tenant:feed_task:operate')")
    public ApiResponse<FeedTaskVO> action(
            @PathVariable Long id,
            @RequestBody(required = false) FeedTaskActionRequest request) {
        return ApiResponse.ok(service.action(id, request == null ? null : request.action()));
    }

    /** 分页查询任务账号发布明细。 */
    @GetMapping("/{id}/data")
    public ApiResponse<PageResult<FeedTaskAccountVO>> accountData(
            @PathVariable Long id,
            @RequestParam(required = false) String accountPhone,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(service.accountData(id, accountPhone, page, pageSize));
    }
}
