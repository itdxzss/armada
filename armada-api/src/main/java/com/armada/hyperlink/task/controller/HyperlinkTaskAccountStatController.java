package com.armada.hyperlink.task.controller;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountStatFilterDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountStatQuery;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountStatItemVO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskExportJobVO;
import com.armada.hyperlink.task.service.HyperlinkAccountStatExportService;
import com.armada.hyperlink.task.service.HyperlinkAccountStatQueryService;
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

/** H5 发信账号维度统计查询和导出入口。 */
@RestController
@RequestMapping("/api/hyperlink-tasks")
public class HyperlinkTaskAccountStatController {

    private final HyperlinkAccountStatQueryService queryService;
    private final HyperlinkAccountStatExportService exportService;

    public HyperlinkTaskAccountStatController(HyperlinkAccountStatQueryService queryService,
            HyperlinkAccountStatExportService exportService) {
        this.queryService = queryService;
        this.exportService = exportService;
    }

    @GetMapping("/{id}/account-stats")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:view')")
    public ApiResponse<PageResult<HyperlinkAccountStatItemVO>> accountStats(
            @PathVariable long id, @ModelAttribute HyperlinkAccountStatQuery query) {
        return ApiResponse.ok(queryService.list(id, query));
    }

    @PostMapping("/{id}/account-stats/export")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:export')")
    public ResponseEntity<ApiResponse<HyperlinkTaskExportJobVO>> exportAccountStats(
            @PathVariable long id, @RequestBody HyperlinkAccountStatFilterDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(exportService.createAccountStatsJob(id, request, principal)));
    }
}
