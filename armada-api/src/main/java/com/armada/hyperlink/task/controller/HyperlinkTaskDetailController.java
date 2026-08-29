package com.armada.hyperlink.task.controller;

import com.armada.hyperlink.task.model.dto.HyperlinkRecipientExportRequestDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkRecipientQuery;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientItemVO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskExportJobVO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskSummaryVO;
import com.armada.hyperlink.task.service.HyperlinkTaskDetailService;
import com.armada.hyperlink.task.service.HyperlinkTaskExportService;
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

/** 详情抽屉公共摘要、H4 收信人流水与 CSV 作业创建接口。 */
@RestController
@RequestMapping("/api/hyperlink-tasks")
public class HyperlinkTaskDetailController {

    private final HyperlinkTaskDetailService detailService;
    private final HyperlinkTaskExportService exportService;

    public HyperlinkTaskDetailController(
            HyperlinkTaskDetailService detailService,
            HyperlinkTaskExportService exportService) {
        this.detailService = detailService;
        this.exportService = exportService;
    }

    @GetMapping("/{id}/summary")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:view')")
    public ApiResponse<HyperlinkTaskSummaryVO> summary(@PathVariable long id) {
        return ApiResponse.ok(detailService.summary(id));
    }

    @GetMapping("/{id}/recipients")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:view')")
    public ApiResponse<PageResult<HyperlinkRecipientItemVO>> recipients(
            @PathVariable long id,
            @ModelAttribute HyperlinkRecipientQuery query) {
        return ApiResponse.ok(detailService.recipients(id, query));
    }

    @PostMapping("/{id}/recipients/export")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:export')")
    public ResponseEntity<ApiResponse<HyperlinkTaskExportJobVO>> exportRecipients(
            @PathVariable long id,
            @RequestBody(required = false) HyperlinkRecipientExportRequestDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(exportService.createRecipientJob(id, request, principal)));
    }
}
