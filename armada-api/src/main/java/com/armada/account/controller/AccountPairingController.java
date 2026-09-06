package com.armada.account.controller;

import com.armada.account.model.dto.AccountPairingCreateDTO;
import com.armada.promotion.pairing.model.command.ControlPairingCreateCommand;
import com.armada.promotion.pairing.model.vo.ControlPairingCreatedVO;
import com.armada.promotion.pairing.model.vo.ControlPairingStatusVO;
import com.armada.promotion.pairing.service.ControlPairingService;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.response.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletResponse;

/** 账号导入页使用的认证码登录接口。 */
@RestController
@RequestMapping("/api/account-pairing-sessions")
@PreAuthorize("hasAuthority('tenant:account:edit')")
public class AccountPairingController {

    private final ControlPairingService service;

    public AccountPairingController(ControlPairingService service) {
        this.service = service;
    }

    /** 发起固定为 88888888 的短时配对会话。 */
    @PostMapping
    public ApiResponse<ControlPairingCreatedVO> create(
            @RequestBody AccountPairingCreateDTO request,
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.ok(service.create(new ControlPairingCreateCommand(
                request == null ? null : request.phone(),
                request == null ? null : request.accountGroupId(),
                request == null ? null : request.remark(),
                principal.userId())));
    }

    /** 只允许当前租户轮询本租户的会话和认证码。 */
    @GetMapping("/{sessionId}")
    public ApiResponse<ControlPairingStatusVO> status(
            @PathVariable Long sessionId,
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.ok(service.status(sessionId, principal.tenantId()));
    }
}
