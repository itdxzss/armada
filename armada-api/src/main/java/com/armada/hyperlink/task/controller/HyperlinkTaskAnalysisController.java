package com.armada.hyperlink.task.controller;

import com.armada.hyperlink.task.model.dto.HyperlinkAttributionQuery;
import com.armada.hyperlink.task.model.dto.HyperlinkVisitTrendQuery;
import com.armada.hyperlink.task.model.vo.HyperlinkAttributionItemVO;
import com.armada.hyperlink.task.model.vo.HyperlinkBanStatsVO;
import com.armada.hyperlink.task.model.vo.HyperlinkVisitTrendVO;
import com.armada.hyperlink.task.service.HyperlinkTaskAnalysisExportService;
import com.armada.hyperlink.task.service.HyperlinkTaskAnalysisService;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskExportJobVO;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** H6 归因分析接口。 */
@RestController
@RequestMapping("/api/hyperlink-tasks")
public class HyperlinkTaskAnalysisController {
    private final HyperlinkTaskAnalysisService service;
    private final HyperlinkTaskAnalysisExportService exportService;

    public HyperlinkTaskAnalysisController(HyperlinkTaskAnalysisService service,
            HyperlinkTaskAnalysisExportService exportService) {
        this.service = service;
        this.exportService = exportService;
    }

    @GetMapping("/{id}/clicks")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:view')")
    public ApiResponse<PageResult<HyperlinkAttributionItemVO>> clicks(@PathVariable long id,
            @ModelAttribute HyperlinkAttributionQuery query,
            @AuthenticationPrincipal AuthPrincipal principal) {
        boolean sensitive = principal != null && principal.permissions() != null
                && principal.permissions().contains(HyperlinkTaskAnalysisService.SENSITIVE_PERMISSION);
        return ApiResponse.ok(service.attribution(id, query, sensitive,
                principal.tenantId(), principal.userId()));
    }

    @GetMapping("/{id}/visit-trend")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:view')")
    public ApiResponse<HyperlinkVisitTrendVO> visitTrend(@PathVariable long id,
            @ModelAttribute HyperlinkVisitTrendQuery query) {
        return ApiResponse.ok(service.visitTrend(id, query));
    }

    @GetMapping("/{id}/ban-stats")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:view')")
    public ApiResponse<HyperlinkBanStatsVO> banStats(@PathVariable long id) {
        return ApiResponse.ok(service.banStats(id));
    }

    @PostMapping("/{id}/click-attribution/export")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:export') and hasAuthority('tenant:hyperlink_task:attribution_sensitive')")
    public ResponseEntity<ApiResponse<HyperlinkTaskExportJobVO>> exportAttribution(
            @PathVariable long id, @RequestBody HyperlinkAttributionQuery query,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(exportService.createAttribution(id, query, principal)));
    }

    @PostMapping("/{id}/visit-trend/export")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:export')")
    public ResponseEntity<ApiResponse<HyperlinkTaskExportJobVO>> exportVisitTrend(
            @PathVariable long id, @RequestBody HyperlinkVisitTrendQuery query,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(exportService.createVisitTrend(id, query, principal)));
    }
}
