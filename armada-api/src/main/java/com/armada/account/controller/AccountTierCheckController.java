package com.armada.account.controller;
import com.armada.account.model.dto.AccountTierCheckRequestDTO;
import com.armada.account.model.dto.AccountTierCheckQuery;
import com.armada.account.model.vo.AccountTierCheckItemVO;
import com.armada.account.model.vo.AccountTierCheckPreviewVO;
import com.armada.account.model.vo.AccountTierCheckTaskVO;
import com.armada.account.service.AccountTierCheckService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/** 账号档位检测批次入口，写操作沿用账号编辑权限。 */
@RestController
@RequestMapping("/api/accounts/tier-check-tasks")
@PreAuthorize("hasAuthority('tenant:account:view')")
public class AccountTierCheckController {
    private final AccountTierCheckService service;
    public AccountTierCheckController(AccountTierCheckService service) {
        this.service = service;
    }
    /** 预览当前可查账号和排除原因。 */
    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<AccountTierCheckPreviewVO> preview(
            @RequestBody AccountTierCheckRequestDTO request, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.preview(request, p));
    }
    /** 幂等创建任务并冻结参与账号。 */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<AccountTierCheckTaskVO> create(
            @RequestBody AccountTierCheckRequestDTO request, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.create(request, p));
    }
    /** 分页查询当前用户可见任务。 */
    @GetMapping
    public ApiResponse<PageResult<AccountTierCheckTaskVO>> list(
            @ModelAttribute AccountTierCheckQuery q, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.list(q, p));
    }
    /** 读取批次分布及变化统计。 */
    @GetMapping("/{id}")
    public ApiResponse<AccountTierCheckTaskVO> detail(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.detail(id, p));
    }
    /** 分页查询归一化快照。 */
    @GetMapping("/{id}/items")
    public ApiResponse<PageResult<AccountTierCheckItemVO>> items(@PathVariable Long id,
            @ModelAttribute AccountTierCheckQuery q, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.items(id, q, p));
    }
    /** 停止待查条目，保留在途结果接收。 */
    @PostMapping("/{id}/stop")
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<Void> stop(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal p) {
        service.stop(id, p);
        return ApiResponse.ok();
    }
    /** 手动重新排队失败项，保留成功历史。 */
    @PostMapping("/{id}/retry-failed")
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<Integer> retry(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.retry(id, p));
    }
    /** 为固定同一批账号创建复查批次。 */
    @PostMapping("/{id}/recheck")
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<AccountTierCheckTaskVO> recheck(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.recheck(id, p));
    }
}
