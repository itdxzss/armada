package com.armada.hyperlink.click.controller;

import com.armada.hyperlink.click.model.dto.HyperlinkClickAnalysisExportDTO;
import com.armada.hyperlink.click.model.dto.HyperlinkClickAnalysisQuery;
import com.armada.hyperlink.click.model.enums.HyperlinkClickAnalysisMode;
import com.armada.hyperlink.click.model.vo.HyperlinkClickAnalysisVO;
import com.armada.hyperlink.click.service.HyperlinkClickAnalysisService;
import com.armada.hyperlink.data.model.vo.DataPackageExportFile;
import com.armada.shared.response.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 超链任务点击分析与分档号码导出接口。 */
@RestController
@RequestMapping("/api/hyperlink-tasks/click-analysis")
@PreAuthorize("hasAuthority('tenant:hyperlink_data:view')")
public class HyperlinkClickAnalysisController {

    private final HyperlinkClickAnalysisService service;

    public HyperlinkClickAnalysisController(HyperlinkClickAnalysisService service) {
        this.service = service;
    }

    @GetMapping("/never-click")
    public ApiResponse<HyperlinkClickAnalysisVO> neverClick(
            @ModelAttribute HyperlinkClickAnalysisQuery query) {
        return ApiResponse.ok(service.analyze(HyperlinkClickAnalysisMode.NEVER_CLICK, query));
    }

    @GetMapping("/uv-ratio")
    public ApiResponse<HyperlinkClickAnalysisVO> uvRatio(
            @ModelAttribute HyperlinkClickAnalysisQuery query) {
        return ApiResponse.ok(service.analyze(HyperlinkClickAnalysisMode.UV_RATIO, query));
    }

    @PostMapping("/never-click/export")
    @PreAuthorize("hasAuthority('tenant:hyperlink_data:export')")
    public ResponseEntity<byte[]> exportNeverClick(
            @RequestBody HyperlinkClickAnalysisExportDTO request) {
        return exportResponse(service.export(HyperlinkClickAnalysisMode.NEVER_CLICK, request));
    }

    @PostMapping("/uv-ratio/export")
    @PreAuthorize("hasAuthority('tenant:hyperlink_data:export')")
    public ResponseEntity<byte[]> exportUvRatio(
            @RequestBody HyperlinkClickAnalysisExportDTO request) {
        return exportResponse(service.export(HyperlinkClickAnalysisMode.UV_RATIO, request));
    }

    private static ResponseEntity<byte[]> exportResponse(DataPackageExportFile file) {
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
}
