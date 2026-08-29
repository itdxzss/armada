package com.armada.marketing.controller;

import com.armada.marketing.model.vo.MarketingTemplateFileContent;
import com.armada.marketing.model.vo.MarketingTemplateFileVO;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.shared.response.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 营销模板图片文件接口。
 */
@RestController
@RequestMapping("/api/marketing-template-files")
@PreAuthorize("hasAuthority('tenant:marketing_template:view')")
public class MarketingTemplateFileController {

    private final MarketingTemplateFileService service;

    public MarketingTemplateFileController(MarketingTemplateFileService service) {
        this.service = service;
    }

    /** 上传图片,返回可保存到模板的文件 ID 与预览 URL。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('tenant:marketing_template:view', "
            + "'tenant:hyperlink_template:create', 'tenant:hyperlink_template:edit', "
            + "'tenant:contact_task:create', 'tenant:contact_task:edit')")
    public ApiResponse<MarketingTemplateFileVO> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(service.uploadImage(file));
    }

    /** 读取图片内容,供模板列表/编辑/预览回显。 */
    @GetMapping("/{id}/content")
    @PreAuthorize("hasAnyAuthority('tenant:marketing_template:view', 'tenant:historical_group:view', "
            + "'tenant:marketing_task:view', 'tenant:group_pull_marketing:view', "
            + "'tenant:group_creation_marketing:view', 'tenant:hyperlink_template:view', "
            + "'tenant:hyperlink_template:create', 'tenant:hyperlink_template:edit', "
            + "'tenant:contact_task:view')")
    public ResponseEntity<byte[]> content(@PathVariable Long id) {
        MarketingTemplateFileContent file = service.content(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.noCache())
                .body(file.content());
    }
}
