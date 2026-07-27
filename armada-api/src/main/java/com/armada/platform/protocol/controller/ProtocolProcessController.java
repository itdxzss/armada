package com.armada.platform.protocol.controller;

import com.armada.platform.protocol.process.ProtocolProcessRestartService;
import com.armada.platform.protocol.process.ProtocolRestartVO;
import com.armada.shared.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/protocol")
@PreAuthorize("hasAuthority('tenant:account:view')")
public class ProtocolProcessController {

    private final ProtocolProcessRestartService restartService;

    public ProtocolProcessController(ProtocolProcessRestartService restartService) {
        this.restartService = restartService;
    }

    @PostMapping("/restart")
    public ApiResponse<ProtocolRestartVO> restart() {
        return ApiResponse.ok(restartService.restart());
    }
}
