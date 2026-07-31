package com.armada.task.controller;

import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import com.armada.task.model.dto.PullTaskGroupMarketingSettingDTO;
import com.armada.task.model.vo.PullTaskGroupMarketingSettingVO;
import com.armada.task.service.PullTaskGroupMarketingSettingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 租户拉群营销全局设置接口。 */
@RestController
@RequestMapping("/api/pull-tasks/group-marketing-setting")
@PreAuthorize("hasAuthority('tenant:pull_task:view')")
public class PullTaskGroupMarketingSettingController {

    private final PullTaskGroupMarketingSettingService service;

    /**
     * 创建全局设置接口。
     *
     * @param service 设置服务
     */
    public PullTaskGroupMarketingSettingController(
            PullTaskGroupMarketingSettingService service) {
        this.service = service;
    }

    /**
     * 查询当前租户设置。
     *
     * @return 设置状态和值
     */
    @GetMapping
    public ApiResponse<PullTaskGroupMarketingSettingVO> get() {
        return ApiResponse.ok(service.get());
    }

    /**
     * 保存当前租户设置。
     *
     * @param request   三项设置值
     * @param principal 当前可信登录身份
     * @return 保存后的设置
     */
    @PutMapping
    @PreAuthorize("hasAuthority('tenant:pull_task:settings')")
    public ApiResponse<PullTaskGroupMarketingSettingVO> save(
            @RequestBody PullTaskGroupMarketingSettingDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.save(request, principal.userId()));
    }
}
