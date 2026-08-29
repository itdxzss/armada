package com.armada.hyperlink.task.controller;

import com.armada.hyperlink.task.model.vo.HyperlinkTaskExportFile;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskExportJobVO;
import com.armada.hyperlink.task.service.HyperlinkTaskExportService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公共超链导出作业状态和受控文件下载。 */
@RestController
@RequestMapping("/api/hyperlink-task-exports")
@PreAuthorize("hasAuthority('tenant:hyperlink_task:export')")
public class HyperlinkTaskExportController {

    private final HyperlinkTaskExportService service;

    public HyperlinkTaskExportController(HyperlinkTaskExportService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ApiResponse<HyperlinkTaskExportJobVO> status(
            @PathVariable long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.getJob(id, principal));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(
            @PathVariable long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        HyperlinkTaskExportFile file = service.getDownload(id, principal);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(file.size());
        headers.setAccessControlExposeHeaders(List.of(HttpHeaders.CONTENT_DISPOSITION));
        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(file.path()));
    }
}
