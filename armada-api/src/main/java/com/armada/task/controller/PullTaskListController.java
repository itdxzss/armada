package com.armada.task.controller;

import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.task.model.dto.PullTaskIdsDTO;
import com.armada.task.model.dto.PullTaskQuery;
import com.armada.task.model.vo.PullTaskListVO;
import com.armada.task.service.PullTaskListService;
import com.armada.task.service.PullTaskMutationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 拉群任务统一一级列表接口。 */
@RestController
@RequestMapping("/api/pull-tasks")
@PreAuthorize("hasAuthority('tenant:pull_task:view')")
public class PullTaskListController {

    private final PullTaskListService service;
    private final PullTaskMutationService mutationService;

    /**
     * 创建拉群任务列表接口。
     *
     * @param service         统一列表读服务
     * @param mutationService 公共任务变更服务
     */
    public PullTaskListController(
            PullTaskListService service,
            PullTaskMutationService mutationService) {
        this.service = service;
        this.mutationService = mutationService;
    }

    /**
     * 按公共任务条件分页查询九列列表。
     *
     * @param query 分页和筛选参数
     * @return 统一响应中的分页列表
     */
    @GetMapping
    public ApiResponse<PageResult<PullTaskListVO>> list(
            @ModelAttribute PullTaskQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /**
     * 按任务类型和状态策略批量软删任务。
     *
     * @param request 待删除任务 ID
     * @return 实际删除数量
     */
    @PostMapping("/batch-delete")
    @PreAuthorize("hasAuthority('tenant:pull_task:delete')")
    public ApiResponse<Integer> batchDelete(@RequestBody PullTaskIdsDTO request) {
        return ApiResponse.ok(mutationService.batchDelete(
                request == null ? null : request.ids()));
    }
}
