package com.armada.task.controller;

import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import com.armada.task.model.dto.PullTaskGroupMarketingCandidateQuery;
import com.armada.task.model.dto.PullTaskGroupMarketingWaitingPoolAddDTO;
import com.armada.task.model.dto.PullTaskGroupMarketingWaitingPoolRemoveDTO;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateVO;
import com.armada.task.model.vo.PullTaskGroupMarketingWaitingPoolVO;
import com.armada.task.service.PullTaskGroupMarketingGroupService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 拉群营销候选群组和创建前等待池接口。 */
@RestController
@RequestMapping("/api/pull-tasks/group-marketing")
@PreAuthorize("hasAuthority('tenant:pull_task:view')")
public class PullTaskGroupMarketingGroupController {

    private final PullTaskGroupMarketingGroupService service;

    /** @param service 候选群组和等待池服务 */
    public PullTaskGroupMarketingGroupController(PullTaskGroupMarketingGroupService service) {
        this.service = service;
    }

    /**
     * 分页查询当前租户拉群营销候选群组。
     *
     * @param query 筛选、分页和当前等待池标识
     * @param principal 当前可信身份
     * @return 按 JID 去重并聚合管理账号的候选群组
     */
    @GetMapping("/candidate-groups")
    public ApiResponse<PageResult<PullTaskGroupMarketingCandidateVO>> candidates(
            @ModelAttribute PullTaskGroupMarketingCandidateQuery query,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.listCandidates(query, principal.userId()));
    }

    /**
     * 读取当前用户创建页的等待池。
     *
     * @param reservationToken 等待池随机标识
     * @param principal 当前可信身份
     * @return 等待池最新快照
     */
    @GetMapping("/waiting-pool")
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<PullTaskGroupMarketingWaitingPoolVO> waitingPool(
            @RequestParam String reservationToken,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.getWaiting(reservationToken, principal.userId()));
    }

    /**
     * 重新校验选中群组并加入等待池形成软占用。
     *
     * @param request 群 JID 和已有等待池标识；首次为空时服务端生成
     * @param principal 当前可信身份
     * @return 最新等待池及逐群拒绝原因
     */
    @PostMapping("/waiting-pool")
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<PullTaskGroupMarketingWaitingPoolVO> addWaiting(
            @RequestBody PullTaskGroupMarketingWaitingPoolAddDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.addWaiting(request, principal.userId()));
    }

    /**
     * 从当前用户等待池移出单群并释放软占用。
     *
     * @param request 等待池标识和群 JID
     * @param principal 当前可信身份
     * @return 释放后的等待池
     */
    @PostMapping("/waiting-pool/remove")
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<PullTaskGroupMarketingWaitingPoolVO> removeWaiting(
            @RequestBody PullTaskGroupMarketingWaitingPoolRemoveDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.removeWaiting(request, principal.userId()));
    }

    /**
     * 取消创建时释放当前用户拥有的整个等待池。
     *
     * @param reservationToken 等待池随机标识
     * @param principal 当前可信身份
     * @return 成功响应
     */
    @DeleteMapping("/waiting-pool")
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    public ApiResponse<Void> releaseWaiting(
            @RequestParam String reservationToken,
            @AuthenticationPrincipal AuthPrincipal principal) {
        service.releaseWaiting(reservationToken, principal.userId());
        return ApiResponse.ok();
    }
}
