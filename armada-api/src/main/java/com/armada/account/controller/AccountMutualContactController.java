package com.armada.account.controller;
import com.armada.account.model.dto.AccountMutualContactCreateDTO;
import com.armada.account.model.dto.AccountMutualContactQuery;
import com.armada.account.model.vo.AccountMutualContactItemVO;
import com.armada.account.model.vo.AccountMutualContactPreviewVO;
import com.armada.account.model.vo.AccountMutualContactTaskVO;
import com.armada.account.service.AccountMutualContactService;
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
/** 账号列表的两分组互存入口，写操作沿用账号编辑权限。 */
@RestController
@RequestMapping("/api/accounts/mutual-contact-tasks")
@PreAuthorize("hasAuthority('tenant:account:view')")
public class AccountMutualContactController {
    private final AccountMutualContactService service;
    public AccountMutualContactController(AccountMutualContactService service) {
        this.service = service;
    }
    /** 预览当前可参与账号和双向操作规模。 */
    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<AccountMutualContactPreviewVO> preview(
            @RequestBody AccountMutualContactCreateDTO request, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.preview(request, p));
    }
    /** 幂等创建任务并冻结参与账号。 */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<AccountMutualContactTaskVO> create(
            @RequestBody AccountMutualContactCreateDTO request, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.create(request, p));
    }
    /** 分页查询当前用户可见任务。 */
    @GetMapping
    public ApiResponse<PageResult<AccountMutualContactTaskVO>> list(
            @ModelAttribute AccountMutualContactQuery q, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.list(q, p));
    }
    /** 读取任务及方向统计。 */
    @GetMapping("/{id}")
    public ApiResponse<AccountMutualContactTaskVO> detail(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.detail(id, p));
    }
    /** 分页查询定向保存事实。 */
    @GetMapping("/{id}/items")
    public ApiResponse<PageResult<AccountMutualContactItemVO>> items(@PathVariable Long id,
            @ModelAttribute AccountMutualContactQuery q, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.items(id, q, p));
    }
    /** 停止未派发操作，保留在途回执接收。 */
    @PostMapping("/{id}/stop")
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<Void> stop(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal p) {
        service.stop(id, p);
        return ApiResponse.ok();
    }
    /** 仅将明确可重试失败项重新排队。 */
    @PostMapping("/{id}/retry-failed")
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<Integer> retry(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.retry(id, p));
    }
}
