package com.armada.account.controller;
import com.armada.account.model.vo.AccountTierCheckHistoryVO;
import com.armada.account.service.AccountTierCheckService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/** 账号档位历史只读入口，不触发真实查询。 */
@RestController
@RequestMapping("/api/accounts")
@PreAuthorize("hasAuthority('tenant:account:view')")
public class AccountTierCheckHistoryController {
    private final AccountTierCheckService service;
    /** 注入批次应用服务。 */
    public AccountTierCheckHistoryController(AccountTierCheckService service) { this.service = service; }
    /** 查询当前可见账号的成功/失败检测记录，按完成时间倒序。 */
    @GetMapping("/{id}/tier-history")
    public ApiResponse<AccountTierCheckHistoryVO> history(@PathVariable Long id, @AuthenticationPrincipal AuthPrincipal p) {
        return ApiResponse.ok(service.history(id, p));
    }
}
