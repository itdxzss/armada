package com.armada.marketing.export.controller;

import com.armada.marketing.export.model.dto.MarketingTaskExportRequestDTO;
import com.armada.marketing.export.model.vo.MarketingTaskExportFile;
import com.armada.marketing.export.model.vo.MarketingTaskExportJobVO;
import com.armada.marketing.export.service.MarketingTaskExportService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

/** 普通营销任务异步导出、状态轮询和当前作业文件下载接口。 */
@RestController
@RequestMapping("/api/marketing-task-exports")
@PreAuthorize("hasAuthority('tenant:marketing_task:export')")
public class MarketingTaskExportController {

    private final MarketingTaskExportService service;

    /**
     * @param service 普通营销任务导出服务
     */
    public MarketingTaskExportController(MarketingTaskExportService service) {
        this.service = service;
    }

    /**
     * 创建异步导出作业，统计截止时间由服务端在本次请求内固定。
     *
     * @param request 导出模式、任务 ID 和可选国家范围
     * @param principal 当前认证用户及租户身份
     * @return HTTP 202 及创建或复用的导出作业
     */
    @PostMapping
    public ResponseEntity<ApiResponse<MarketingTaskExportJobVO>> create(
            @RequestBody MarketingTaskExportRequestDTO request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(service.createJob(request, principal)));
    }

    /**
     * 查询当前用户创建的单个导出作业，供页面短轮询。
     *
     * @param id 导出作业 ID
     * @param principal 当前认证用户及租户身份
     * @return 当前导出作业状态
     */
    @GetMapping("/{id}")
    public ApiResponse<MarketingTaskExportJobVO> status(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.getJob(id, principal));
    }

    /**
     * 下载已成功生成且未过期的导出文件，不接受客户端文件路径。
     *
     * @param id 导出作业 ID
     * @param principal 当前认证用户及租户身份
     * @return 带 UTF-8 附件文件名的受控文件响应
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        MarketingTaskExportFile file = service.getDownload(id, principal);
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
