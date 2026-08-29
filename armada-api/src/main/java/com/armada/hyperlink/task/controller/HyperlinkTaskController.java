package com.armada.hyperlink.task.controller;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskActionDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskListQuery;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskQuoteDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskSaveDTO;
import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountMatchCountVO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskCreateContextVO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskDetailVO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskMutationReceiptVO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskListExportFile;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskListItemVO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskQuoteVO;
import com.armada.hyperlink.task.service.HyperlinkTaskActionService;
import com.armada.hyperlink.task.service.HyperlinkTaskLifecycleService;
import com.armada.hyperlink.task.service.HyperlinkTaskListQueryService;
import com.armada.hyperlink.task.service.HyperlinkTaskQueryService;
import com.armada.hyperlink.task.service.HyperlinkTaskQuoteService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 超链任务列表、发布、准备轮询和四动作 HTTP 合同。 */
@RestController
@RequestMapping("/api/hyperlink-tasks")
public class HyperlinkTaskController {
    private final HyperlinkTaskQuoteService quoteService;
    private final HyperlinkTaskLifecycleService lifecycleService;
    private final HyperlinkTaskActionService actionService;
    private final HyperlinkTaskQueryService queryService;
    private final HyperlinkTaskListQueryService listQueryService;

    public HyperlinkTaskController(HyperlinkTaskQuoteService quoteService,
            HyperlinkTaskLifecycleService lifecycleService, HyperlinkTaskActionService actionService,
            HyperlinkTaskQueryService queryService,
            HyperlinkTaskListQueryService listQueryService) {
        this.quoteService = quoteService;
        this.lifecycleService = lifecycleService;
        this.actionService = actionService;
        this.queryService = queryService;
        this.listQueryService = listQueryService;
    }

    /** 加载新建页的真实钱包和协议容量上下文。 */
    @GetMapping("/create-context")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:create') or "
            + "hasAuthority('tenant:hyperlink_task:edit') or "
            + "hasAuthority('tenant:hyperlink_task:view')")
    public ApiResponse<HyperlinkTaskCreateContextVO> createContext() {
        return ApiResponse.ok(queryService.createContext());
    }

    /** 按最终运行选号口径试算当前筛选可用账号数。 */
    @PostMapping("/account-match-count")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:create') or hasAuthority('tenant:hyperlink_task:edit')")
    public ApiResponse<HyperlinkAccountMatchCountVO> accountMatchCount(
            @RequestBody HyperlinkAccountFilterDTO request) {
        return ApiResponse.ok(queryService.accountMatchCount(request));
    }

    /** 读取编辑、查看和复制共用的完整任务表单事实。 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:view')")
    public ApiResponse<HyperlinkTaskDetailVO> detail(@PathVariable long id) {
        return ApiResponse.ok(queryService.detail(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:view')")
    public ApiResponse<PageResult<HyperlinkTaskListItemVO>> list(
            @ModelAttribute HyperlinkTaskListQuery query) {
        return ApiResponse.ok(listQueryService.list(query));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:export')")
    public ResponseEntity<byte[]> export(@ModelAttribute HyperlinkTaskListQuery query) {
        HyperlinkTaskListExportFile file = listQueryService.export(query);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(file.bytes().length);
        headers.set("X-Export-Count", String.valueOf(file.exportedCount()));
        headers.setAccessControlExposeHeaders(List.of(
                HttpHeaders.CONTENT_DISPOSITION, "X-Export-Count"));
        return ResponseEntity.ok().headers(headers).body(file.bytes());
    }

    @PostMapping("/quote")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:create') or hasAuthority('tenant:hyperlink_task:action')")
    public ApiResponse<HyperlinkTaskQuoteVO> quote(@RequestBody HyperlinkTaskQuoteDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(quoteService.quote(request, principal));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:create')")
    public ResponseEntity<ApiResponse<HyperlinkTaskMutationReceiptVO>> create(
            @RequestBody HyperlinkTaskSaveDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return response(lifecycleService.create(request, principal));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:edit')")
    public ResponseEntity<ApiResponse<HyperlinkTaskMutationReceiptVO>> update(
            @PathVariable long id, @RequestBody HyperlinkTaskSaveDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return response(lifecycleService.update(id, request, principal));
    }

    @GetMapping("/{id}/provision-status")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:view')")
    public ApiResponse<HyperlinkTaskMutationReceiptVO> provisionStatus(@PathVariable long id) {
        return ApiResponse.ok(lifecycleService.provisionStatus(id));
    }

    @PostMapping("/{id}/action")
    @PreAuthorize("hasAuthority('tenant:hyperlink_task:action')")
    public ResponseEntity<ApiResponse<HyperlinkTaskMutationReceiptVO>> action(
            @PathVariable long id, @RequestBody HyperlinkTaskActionDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return response(actionService.action(id, request, principal));
    }

    private ResponseEntity<ApiResponse<HyperlinkTaskMutationReceiptVO>> response(
            HyperlinkTaskMutationReceiptVO receipt) {
        HttpStatus status = receipt.provisionStatus() == HyperlinkProvisionStatus.PROCESSING
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(receipt));
    }
}
