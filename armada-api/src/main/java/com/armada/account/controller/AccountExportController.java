package com.armada.account.controller;

import com.armada.account.model.dto.AccountExportCompleteDTO;
import com.armada.account.model.dto.AccountExportCreateDTO;
import com.armada.account.model.vo.AccountExportJobVO;
import com.armada.account.service.AccountExportService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 所选账号凭据导出与交付，所有操作受账号查看和编辑权限共同保护。 */
@RestController
@RequestMapping("/api/accounts/exports")
@PreAuthorize("hasAuthority('tenant:account:view') and hasAuthority('tenant:account:edit')")
public class AccountExportController {
    private final AccountExportService service;

    /** 构造导出入口。 */
    public AccountExportController(AccountExportService service) { this.service = service; }

    /** 只接受明确勾选，不执行下线。 */
    @PostMapping
    public ApiResponse<AccountExportJobVO> create(@RequestBody AccountExportCreateDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.create(request, principal));
    }

    /** 本人的最近导出记录可用于下载中断或刷新后的恢复。 */
    @GetMapping
    public ApiResponse<List<AccountExportJobVO>> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.list(principal));
    }

    /** 敏感附件不进入 JSON 响应或浏览器缓存。 */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable String id, @AuthenticationPrincipal AuthPrincipal principal) {
        var job = service.download(id, principal);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(job.getFilename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(job.getArchive().length).body(job.getArchive());
    }

    /** 客户端完整接收并核对摘要后提交控端移除。 */
    @PostMapping("/{id}/complete")
    public ApiResponse<AccountExportJobVO> complete(@PathVariable String id,
            @RequestBody AccountExportCompleteDTO request, @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.complete(id, request.sha256(), principal));
    }

    /** 下载前取消预占，账号仍保持离线。 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String id, @AuthenticationPrincipal AuthPrincipal principal) {
        service.cancel(id, principal);
        return ApiResponse.ok();
    }
}
