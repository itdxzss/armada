package com.armada.account.controller;

import com.armada.account.model.dto.AccountMessageCapQueryDTO;
import com.armada.account.model.vo.AccountMessageCapBatchVO;
import com.armada.account.service.AccountMessageCapService;
import com.armada.shared.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 账号“新聊天额度”批量查询入口，只读，沿用账号查看权限。 */
@RestController
@RequestMapping("/api/accounts/message-cap")
@PreAuthorize("hasAuthority('tenant:account:view')")
public class AccountMessageCapController {

    private final AccountMessageCapService service;

    public AccountMessageCapController(AccountMessageCapService service) {
        this.service = service;
    }

    /**
     * 批量查询选中账号当前在线会话的新聊天额度。
     *
     * <p>会真实触达 WhatsApp，每个账号只查询一次且不重试；单个账号失败不影响其它账号。</p>
     *
     * @param request 账号 ID（最多 100 个）与是否返回原始响应
     * @return 逐账号查询结果
     */
    @PostMapping("/query")
    public ApiResponse<AccountMessageCapBatchVO> query(@RequestBody AccountMessageCapQueryDTO request) {
        return ApiResponse.ok(service.query(request));
    }
}
