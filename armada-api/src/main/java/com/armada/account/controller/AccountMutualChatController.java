package com.armada.account.controller;
import com.armada.account.model.dto.AccountMutualChatCreateDTO;
import com.armada.account.model.dto.AccountMutualChatQuery;
import com.armada.account.model.vo.AccountMutualChatItemVO;
import com.armada.account.model.vo.AccountMutualContactPreviewVO;
import com.armada.account.model.vo.AccountMutualChatTaskVO;
import com.armada.account.service.AccountMutualChatService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
/** 账号列表的两分组互聊入口，写操作沿用账号编辑权限。 */
@RestController
@RequestMapping("/api/accounts/mutual-chat-tasks")
@PreAuthorize("hasAuthority('tenant:account:view')")
public class AccountMutualChatController {
    private final AccountMutualChatService service;
    public AccountMutualChatController(AccountMutualChatService service) {
        this.service = service;
    }
    /** 预览当前可参与账号和双向操作规模。 */
    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<AccountMutualContactPreviewVO> preview(
            @RequestBody AccountMutualChatCreateDTO request, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.preview(request, p));
    }
    /** 幂等创建任务并冻结参与账号。 */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<AccountMutualChatTaskVO> create(
            @RequestBody AccountMutualChatCreateDTO request, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.create(request, p));
    }
    /** 分页查询当前用户可见任务。 */
    @GetMapping
    public ApiResponse<PageResult<AccountMutualChatTaskVO>> list(
            @ModelAttribute AccountMutualChatQuery q, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.list(q, p));
    }
    /** 读取任务及方向统计。 */
    @GetMapping("/{id}")
    public ApiResponse<AccountMutualChatTaskVO> detail(
            @PathVariable Long id, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.detail(id, p));
    }
    /** 分页查询定向发送事实。 */
    @GetMapping("/{id}/items")
    public ApiResponse<PageResult<AccountMutualChatItemVO>> items(@PathVariable Long id,
            @ModelAttribute AccountMutualChatQuery q, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.items(id, q, p));
    }
    /** 停止未派发操作，保留在途回执接收。 */
    @PostMapping("/{id}/stop")
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<Void> stop(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal p) {
        service.stop(id, p);
        return ApiResponse.ok();
    }
    /** 将失败、待确认和已取消方向重新排队；待确认重试可能重复发送。 */
    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('tenant:account:edit')")
    public ApiResponse<Integer> retry(@PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean confirmUnknown, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.retry(id, confirmUnknown, p));
    }
}
