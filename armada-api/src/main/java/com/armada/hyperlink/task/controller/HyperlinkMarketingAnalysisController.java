package com.armada.hyperlink.task.controller;

import com.armada.hyperlink.task.model.dto.HyperlinkMarketingStatsQuery;
import com.armada.hyperlink.task.model.vo.HyperlinkMarketingCountriesVO;
import com.armada.hyperlink.task.model.vo.HyperlinkMarketingStatsVO;
import com.armada.hyperlink.task.service.HyperlinkMarketingStatsService;
import com.armada.shared.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 跨任务超链市场分析；账号明细和导出继续归任务详情所有。 */
@RestController
@RequestMapping("/api/hyperlink-tasks/marketing-stats")
public class HyperlinkMarketingAnalysisController {
    private final HyperlinkMarketingStatsService service;

    public HyperlinkMarketingAnalysisController(HyperlinkMarketingStatsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('tenant:hyperlink_analysis:view')")
    public ApiResponse<HyperlinkMarketingStatsVO> stats(
            @ModelAttribute HyperlinkMarketingStatsQuery query) {
        return ApiResponse.ok(service.stats(query));
    }

    @GetMapping("/countries")
    @PreAuthorize("hasAuthority('tenant:hyperlink_analysis:view')")
    public ApiResponse<HyperlinkMarketingCountriesVO> countries(
            @ModelAttribute HyperlinkMarketingStatsQuery query) {
        return ApiResponse.ok(service.countries(query));
    }
}
