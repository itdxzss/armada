package com.armada.account.wbcompanion.controller;

import com.armada.account.wbcompanion.model.dto.WbCompanionCreateDTO;
import com.armada.account.wbcompanion.model.vo.WbCompanionVOs;
import com.armada.account.wbcompanion.service.WbCompanionService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 账号列表"登录到 WB 分身"：把安卓号以关联设备的形式挂到指定 WB 会员名下。 */
@RestController
@RequestMapping("/api/account/wb-companion")
public class WbCompanionController {

    private final WbCompanionService service;

    public WbCompanionController(WbCompanionService service) {
        this.service = service;
    }

    /** 本环境可导入的 WB 环境。 */
    @GetMapping("/targets")
    @PreAuthorize("hasAuthority('tenant:account:view')")
    public ApiResponse<List<WbCompanionVOs.WbTargetVO>> targets() {
        return ApiResponse.ok(service.targets());
    }

    /** 在目标 WB 按用户名精确查会员。 */
    @GetMapping("/members")
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<WbCompanionVOs.WbMemberVO> member(@RequestParam String wbEnvId, @RequestParam String username) {
        return ApiResponse.ok(service.member(wbEnvId, username));
    }

    /** 为选中的安卓号发起登录，逐个账号返回结果。 */
    @PostMapping("/tasks")
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<WbCompanionVOs.WbCompanionCreateResultVO> create(
            @RequestBody WbCompanionCreateDTO dto, @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.create(dto, principal.userId()));
    }

    /** 任务进度。 */
    @GetMapping("/tasks/{taskId}")
    @PreAuthorize("hasAuthority('tenant:account:view')")
    public ApiResponse<WbCompanionVOs.WbCompanionTaskVO> task(@PathVariable Long taskId) {
        return ApiResponse.ok(service.task(taskId));
    }

    /** 停止剩余连接，已挂上的保留。 */
    @PostMapping("/tasks/{taskId}/stop")
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<Void> stop(@PathVariable Long taskId) {
        service.stop(taskId);
        return ApiResponse.ok();
    }

    /** 账号列表"WB 分身"列。 */
    @PostMapping("/summaries")
    @PreAuthorize("hasAuthority('tenant:account:view')")
    public ApiResponse<List<WbCompanionVOs.WbCompanionSummaryVO>> summaries(@RequestBody List<Long> accountIds) {
        return ApiResponse.ok(service.summaries(accountIds));
    }

    /** 账号的 WB 分身详情，实时查询 WB。 */
    @GetMapping("/accounts/{accountId}")
    @PreAuthorize("hasAuthority('tenant:account:view')")
    public ApiResponse<WbCompanionVOs.WbCompanionDetailVO> detail(@PathVariable Long accountId) {
        return ApiResponse.ok(service.detail(accountId));
    }

    /** 全部解绑。 */
    @PostMapping("/accounts/{accountId}/unbind")
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<Void> unbind(@PathVariable Long accountId) {
        service.unbind(accountId);
        return ApiResponse.ok();
    }
}
