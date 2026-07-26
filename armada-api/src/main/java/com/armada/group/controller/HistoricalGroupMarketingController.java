package com.armada.group.controller;

import com.armada.group.model.dto.HistoricalGroupMarketingSendDTO;
import com.armada.group.model.vo.HistoricalGroupPullExecutionVO;
import com.armada.group.service.HistoricalGroupMarketingService;
import com.armada.shared.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 历史群执行的全部营销账号发送端点。 */
@RestController
@RequestMapping("/api/historical-group-pull-executions")
@PreAuthorize("hasAuthority('tenant:historical_group:view')")
public class HistoricalGroupMarketingController {

    private final HistoricalGroupMarketingService marketingService;

    /**
     * 创建历史群营销发送端点。
     *
     * @param marketingService 全部营销账号发送服务
     */
    public HistoricalGroupMarketingController(HistoricalGroupMarketingService marketingService) {
        this.marketingService = marketingService;
    }

    /**
     * 为执行中的全部营销账号各下发一次所选模板。
     *
     * @param id      历史群执行 ID
     * @param request 当前租户模板选择
     * @return 启动后或重复请求时的当前执行状态
     */
    @PostMapping("/{id}/marketing-send")
    public ApiResponse<HistoricalGroupPullExecutionVO> send(
            @PathVariable Long id,
            @RequestBody HistoricalGroupMarketingSendDTO request) {
        return ApiResponse.ok(marketingService.send(id, request));
    }
}
